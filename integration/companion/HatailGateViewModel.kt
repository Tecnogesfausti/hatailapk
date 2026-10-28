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
import es.tecnogesfausti.hatail.core.TailnetAddressPolicy
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.websocket.WebsocketManager
import java.net.URI
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
private const val KEY_HOME = "hatail.selected_home"
private const val KEY_SOL_URL = "hatail.sol.expected_url"
private const val KEY_SOL_SERVER = "hatail.sol.server"
private const val KEY_SOL_SERVER_URL = "hatail.sol.server_url"
private const val KEY_ONBOARDING_HOME = "hatail.onboarding_home"

internal enum class Home { CASA, SOL }

internal sealed interface GateState {
    data object Loading : GateState
    data object Account : GateState
    data object Permission : GateState
    data class SetupHomeAssistant(val home: Home, val savedUrl: String? = null) : GateState
    data class Onboarding(val home: Home, val url: String? = null) : GateState
    data class Connected(val home: Home, val serverId: Int) : GateState
    data object Error : GateState
}

/** Process-local gate; a persisted selection never proves that a tunnel is active. */
object HatailAccess {
    var allowed: Boolean = false
        internal set
}

/** One verified tailnet, two independently bound Companion server registrations. */
@HiltViewModel
internal class HatailGateViewModel @Inject constructor(
    private val servers: ServerManager,
    private val homeAssistant: HatailHomeAssistantRepository,
    private val notifications: HatailBackgroundNotifications,
) : ViewModel() {
    private val mutableState = MutableStateFlow<GateState>(GateState.Loading)
    val state = mutableState.asStateFlow()
    private val mutableHome = MutableStateFlow(Home.CASA)
    val selected = mutableHome.asStateFlow()
    private val app get() = App.get()
    private val client by lazy { Client(app.applicationScope) }
    private var operation: Job? = null

    init {
        viewModelScope.launch {
            servers.serversFlow.collect { registered ->
                if (registered.any { it.connection.isRegistered } && isShowingHome()) {
                    refresh()
                }
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(MONITOR_MS)
                if (isShowingHome()) {
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
                if (!active && isShowingHome()) {
                    HatailAccess.allowed = false
                    mutableState.value = GateState.Loading
                    refresh()
                }
            }
        }
    }

    /** Notification links may target the other bound home; validate it before exposing its frontend. */
    fun openRequestedServer(serverId: Int?) = runOperation {
        val home = homeForRequestedServer(
            serverId,
            read { getInt(KEY_SERVER, -1) },
            read { getInt(KEY_SOL_SERVER, -1) },
        )
        if (home != null) edit { putString(KEY_HOME, home.name) }
        connectSelected()
    }

    /** Revalidate a foreground session without stopping a correctly running VPN. */
    fun refresh() {
        if (operation?.isActive != true) runOperation { connectSelected() }
    }

    private fun isShowingHome() =
        mutableState.value is GateState.Connected || mutableState.value is GateState.Onboarding

    /** The old Casa flag remains readable so an installed Casa session survives this update. */
    fun selectHome(home: Home) {
        if (operation?.isActive == true || (state.value as? GateState.Connected)?.home == home) return
        mutableHome.value = home
        runOperation {
            HatailAccess.allowed = false
            mutableState.value = GateState.Loading
            edit {
                putString(KEY_HOME, home.name)
                putBoolean(KEY_SELECTED, home == Home.CASA)
            }
            connectSelected()
        }
    }

    /** Associate the shared tailnet with the account actually observed in this APK. */
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

    /** Continue Casa's one-time Companion registration over the verified VPN. */
    fun onboard() {
        HatailAccess.allowed = true
        mutableState.value = GateState.Onboarding(Home.CASA)
    }

    /** Save Sol's private address before opening Companion's second-server registration. */
    fun onboardSol(rawUrl: String, openRegistration: (String) -> Unit) = runOperation {
        require(selectedHome() == Home.SOL)
        val url = URI(rawUrl.trim()).normalize()
        val tailnet = requireNotNull(read { getString(KEY_TAILNET, null) })
        require(TailnetAddressPolicy.accepts(url, tailnet))
        require(read { getString(KEY_SERVER_URL, null) }?.let { sameHomeAssistantEndpoint(url, URI(it)) } != true)
        edit {
            putString(KEY_SOL_URL, url.toString())
            putString(KEY_ONBOARDING_HOME, Home.SOL.name)
        }
        HatailAccess.allowed = true
        mutableState.value = GateState.Onboarding(Home.SOL, url.toString())
        openRegistration(url.toString())
    }

    /** Handle the Android VPN consent result. Denial keeps Casa selected for retry. */
    fun permissionResult(granted: Boolean) {
        app.getAppScopedViewModel().setVpnPrepared(granted)
        if (granted) refresh() else mutableState.value = GateState.Error
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
        val home = selectedHome()
        mutableHome.value = home
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
        configureNotifications()
        verifyHomeAssistant(home)
    }

    private suspend fun selectedHome(): Home = read {
        getString(KEY_HOME, null)?.let(Home::valueOf)
            ?: Home.CASA
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

    private suspend fun verifyHomeAssistant(home: Home) {
        val boundIdKey = if (home == Home.CASA) KEY_SERVER else KEY_SOL_SERVER
        val boundUrlKey = if (home == Home.CASA) KEY_SERVER_URL else KEY_SOL_SERVER_URL
        val server = homeAssistant.verify(
            read { getInt(boundIdKey, -1) }.takeIf { it >= 0 },
            read { getString(boundUrlKey, null) },
            requireNotNull(read { getString(KEY_TAILNET, null) }),
            if (home == Home.SOL) read { getString(KEY_SOL_URL, null) } else null,
            allowSingleUnbound = home == Home.CASA,
        )
        if (server == null) {
            val onboarding = read { getString(KEY_ONBOARDING_HOME, null) } == home.name
            HatailAccess.allowed = onboarding
            mutableState.value = if (onboarding) {
                GateState.Onboarding(home, if (home == Home.SOL) read { getString(KEY_SOL_URL, null) } else null)
            } else {
                GateState.SetupHomeAssistant(
                    home,
                    if (home ==
                        Home.SOL
                    ) {
                        read { getString(KEY_SOL_URL, null) }
                    } else {
                        null
                    },
                )
            }
            return
        }
        require(matchesBinding())
        edit {
            putInt(boundIdKey, server.id)
            putString(boundUrlKey, server.connection.externalUrl)
            remove(KEY_ONBOARDING_HOME)
        }
        configureNotifications()
        HatailAccess.allowed = true
        mutableState.value = GateState.Connected(home, server.id)
    }

    private suspend fun configureNotifications() {
        notifications.configure(
            listOfNotNull(
                read { getInt(KEY_SERVER, -1) }.takeIf { it >= 0 }?.let { id ->
                    read { getString(KEY_SERVER_URL, null) }?.let { HomeServerBinding(id, it) }
                },
                read { getInt(KEY_SOL_SERVER, -1) }.takeIf { it >= 0 }?.let { id ->
                    read { getString(KEY_SOL_SERVER_URL, null) }?.let { HomeServerBinding(id, it) }
                },
            ),
            requireNotNull(read { getString(KEY_TAILNET, null) }),
        )
        WebsocketManager.start(app)
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

internal fun homeForRequestedServer(requested: Int?, casa: Int, sol: Int): Home? = when {
    requested == null || requested < 0 || casa == sol -> null
    requested == casa -> Home.CASA
    requested == sol -> Home.SOL
    else -> null
}
