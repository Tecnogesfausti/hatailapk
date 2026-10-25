package io.homeassistant.companion.android.hatail

import android.net.VpnService
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.App
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.IpnLocal
import com.tailscale.ipn.ui.model.IpnState
import dagger.hilt.android.lifecycle.HiltViewModel
import io.homeassistant.companion.android.common.data.servers.ServerManager
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber

private const val CONNECTION_TIMEOUT_MS = 30_000L
private const val POLL_MS = 500L
private const val MONITOR_MS = 3_000L
private const val KEY_SELECTED = "hatail.casa.selected"
private const val KEY_PROFILE = "hatail.casa.profile"
private const val KEY_TAILNET = "hatail.casa.tailnet"
private const val KEY_SERVER = "hatail.casa.server"
private const val KEY_SERVER_URL = "hatail.casa.server_url"

internal sealed interface GateState {
    data object Loading : GateState
    data object Choose : GateState
    data object Account : GateState
    data object Permission : GateState
    data object SetupHomeAssistant : GateState
    data object Onboarding : GateState
    data object Connected : GateState
    data object Error : GateState
}

/** Process-local gate; a persisted selection never proves that a tunnel is active. */
object HatailAccess {
    var allowed: Boolean = false
        internal set
}

/** First real-device slice: one Casa account, the embedded VPN and the genuine Companion UI. */
@HiltViewModel
internal class HatailGateViewModel @Inject constructor(
    private val servers: ServerManager,
    private val homeAssistant: HatailHomeAssistantRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow<GateState>(GateState.Loading)
    val state = mutableState.asStateFlow()
    private val app get() = App.get()
    private val client by lazy { Client(app.applicationScope) }
    private var operation: Job? = null

    init {
        refresh()
        viewModelScope.launch {
            servers.serversFlow.collect { registered ->
                if (registered.any { it.connection.isRegistered } &&
                    mutableState.value in setOf(GateState.Onboarding, GateState.Connected)
                ) {
                    refresh()
                }
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(MONITOR_MS)
                if (mutableState.value in setOf(GateState.Connected, GateState.Onboarding)) {
                    try {
                        if (!matchesBinding()) refresh()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        HatailAccess.allowed = false
                        mutableState.value = GateState.Error
                    }
                }
            }
        }
        viewModelScope.launch {
            app.getAppScopedViewModel().vpnActive.collect { active ->
                if (!active && mutableState.value in setOf(GateState.Connected, GateState.Onboarding)) {
                    HatailAccess.allowed = false
                    mutableState.value = GateState.Loading
                    refresh()
                }
            }
        }
    }

    /** Revalidate a foreground session without stopping a correctly running VPN. */
    fun refresh() = runOperation { connectSelected() }

    /** Persist the selected home before starting initial account setup. */
    fun selectCasa() = runOperation {
        edit { putBoolean(KEY_SELECTED, true) }
        connectSelected()
    }

    /** Associate Casa with the account actually observed in this APK's native backend. */
    fun bindCurrentAccount() = runOperation {
        val profile = currentProfile()
        val suffix = status().CurrentTailnet?.MagicDNSSuffix.orEmpty()
        require(profile.ID.isNotBlank() && suffix.isNotBlank())
        edit {
            putString(KEY_PROFILE, profile.ID)
            putString(KEY_TAILNET, suffix)
        }
        connectSelected()
    }

    /** Continue the one-time Companion registration over the verified Casa VPN. */
    fun onboard() {
        HatailAccess.allowed = true
        mutableState.value = GateState.Onboarding
    }

    /** Handle the Android VPN consent result. Denial keeps Casa selected for retry. */
    fun permissionResult(granted: Boolean) {
        app.getAppScopedViewModel().setVpnPrepared(granted)
        if (granted) refresh() else mutableState.value = GateState.Error
    }

    /** System Back leaves Casa; foreground/background lifecycle events do not call this. */
    fun exitHome() {
        HatailAccess.allowed = false
        mutableState.value = GateState.Loading
        runOperation {
            edit { putBoolean(KEY_SELECTED, false) }
            servers.servers().forEach { servers.webSocketRepository(it.id).shutdown() }
            stopTunnel()
            mutableState.value = GateState.Choose
        }
    }

    private fun runOperation(block: suspend () -> Unit) {
        val previous = operation
        operation = viewModelScope.launch {
            previous?.cancelAndJoin()
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Timber.w("HATail connection failed (%s)", failure.javaClass.simpleName)
                HatailAccess.allowed = false
                mutableState.value = GateState.Error
            }
        }
    }

    private suspend fun connectSelected() {
        if (!read { getBoolean(KEY_SELECTED, false) }) {
            HatailAccess.allowed = false
            stopTunnel()
            mutableState.value = GateState.Choose
            return
        }
        if (read { getString(KEY_PROFILE, null) } == null) {
            mutableState.value = GateState.Account
            return
        }
        if (VpnService.prepare(app) != null) {
            mutableState.value = GateState.Permission
            return
        }
        if (!matchesBinding()) {
            HatailAccess.allowed = false
            mutableState.value = GateState.Loading
            activateBoundProfile()
        }
        verifyHomeAssistant()
    }

    private suspend fun activateBoundProfile() {
        stopTunnel()
        val expected = read { getString(KEY_PROFILE, null) }
        val profiles = request<List<IpnLocal.LoginProfile>> { client.profiles(it) }
        val profile = profiles.single { it.ID == expected }
        request<String> { client.switchProfile(profile, it) }
        request<Ipn.Prefs> { client.editPrefs(Ipn.MaskedPrefs().apply { WantRunning = true }, it) }
        app.getAppScopedViewModel().setVpnPrepared(true)
        app.startVPN()
        withTimeout(CONNECTION_TIMEOUT_MS) {
            while (!matchesBinding()) delay(POLL_MS)
        }
    }

    private suspend fun stopTunnel() {
        request<Ipn.Prefs> { client.editPrefs(Ipn.MaskedPrefs().apply { WantRunning = false }, it) }
        if (app.getAppScopedViewModel().vpnActive.value) app.stopVPN()
        withTimeout(CONNECTION_TIMEOUT_MS) {
            while (app.getAppScopedViewModel().vpnActive.value || status().BackendState == "Running") delay(POLL_MS)
        }
    }

    private suspend fun matchesBinding(): Boolean {
        val expectedId = read { getString(KEY_PROFILE, null) } ?: return false
        val expectedDns = read { getString(KEY_TAILNET, null) } ?: return false
        val active = currentProfile()
        val observed = status()
        return active.ID == expectedId &&
            observed.BackendState == "Running" &&
            app.getAppScopedViewModel().vpnActive.value &&
            observed.CurrentTailnet?.MagicDNSSuffix?.trimEnd('.')?.equals(expectedDns.trimEnd('.'), true) == true
    }

    private suspend fun verifyHomeAssistant() {
        val server = homeAssistant.verify(
            read { getInt(KEY_SERVER, -1) }.takeIf { it >= 0 },
            read { getString(KEY_SERVER_URL, null) },
            requireNotNull(read { getString(KEY_TAILNET, null) }),
        )
        if (server == null) {
            if (mutableState.value != GateState.Onboarding) mutableState.value = GateState.SetupHomeAssistant
            return
        }
        require(matchesBinding())
        edit {
            putInt(KEY_SERVER, server.id)
            putString(KEY_SERVER_URL, server.connection.externalUrl)
        }
        HatailAccess.allowed = true
        mutableState.value = GateState.Connected
    }

    private suspend fun currentProfile() = request<IpnLocal.LoginProfile> { client.currentProfile(it) }
    private suspend fun status() = request<IpnState.Status> { client.status(it) }

    // Native LocalAPI calls own their timeout. Keep their callback alive during cancellation so
    // a queued switch/stop cannot overtake an older native mutation still in progress.
    private suspend fun <T> request(call: ((Result<T>) -> Unit) -> Unit): T {
        val value = withContext(NonCancellable) {
            suspendCancellableCoroutine<T> { continuation ->
                call { result ->
                    result.fold(continuation::resume, continuation::resumeWithException)
                }
            }
        }
        currentCoroutineContext().ensureActive()
        return value
    }

    private suspend fun <T> read(block: android.content.SharedPreferences.() -> T): T =
        withContext(Dispatchers.IO) { app.getEncryptedPrefs().block() }

    private suspend fun edit(block: android.content.SharedPreferences.Editor.() -> Unit) =
        withContext(Dispatchers.IO) { check(app.getEncryptedPrefs().edit().apply(block).commit()) }
}
