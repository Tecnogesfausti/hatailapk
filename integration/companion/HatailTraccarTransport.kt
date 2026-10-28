package io.homeassistant.companion.android.hatail

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.tailscale.ipn.App
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.IpnLocal
import com.tailscale.ipn.ui.model.IpnState
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

internal enum class TraccarIssue { NONE, CONFIG, PERMISSION, GPS, VPN, NETWORK, SERVER, START, STORAGE }
internal class TraccarFailure(val issue: TraccarIssue) : IOException()

/** Official OsmAnd HTTP form protocol. Speed is in knots, time is the fix time in epoch milliseconds. */
internal fun traccarForm(config: TraccarConfig, point: TraccarPosition): FormBody = FormBody.Builder().apply {
    add("id", config.deviceId)
    add("timestamp", point.time.toString())
    add("lat", point.latitude.toString())
    add("lon", point.longitude.toString())
    add("accuracy", point.accuracy.toString())
    add("valid", "true")
    point.speedMetersPerSecond?.takeIf { it.isFinite() && it >= 0 }?.let { add("speed", (it * 1.94384449).toString()) }
    point.bearing?.takeIf { it.isFinite() && it in 0f..360f }?.let { add("bearing", it.toString()) }
    point.altitude?.takeIf { it.isFinite() }?.let { add("altitude", it.toString()) }
    point.battery?.takeIf { it in 0..100 }?.let { add("batt", it.toString()) }
}.build()

/** Read-only binding check, also used before opening a socket: no VPN/profile mutations here. */
@Singleton
internal class TraccarVpn @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: TraccarPreferences,
) {
    private val client by lazy { Client(App.get().applicationScope) }
    suspend fun network(): Network = try {
        withTimeout(10_000) {
            val app = App.get()
            if (!app.getAppScopedViewModel().vpnActive.value) throw TraccarFailure(TraccarIssue.VPN)
            val profile = request<IpnLocal.LoginProfile> { client.currentProfile(it) }
            val status = request<IpnState.Status> { client.status(it) }
            if (profile.ID != preferences.profile() ||
                status.BackendState != "Running" ||
                !status.CurrentTailnet?.MagicDNSSuffix.orEmpty().trimEnd('.')
                    .equals(preferences.tailnet().trimEnd('.'), ignoreCase = true)
            ) {
                throw TraccarFailure(TraccarIssue.VPN)
            }
            val manager = context.getSystemService(ConnectivityManager::class.java)
            manager.allNetworks.singleOrNull {
                manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            } ?: throw TraccarFailure(TraccarIssue.VPN)
        }
    } catch (_: TimeoutCancellationException) {
        throw TraccarFailure(TraccarIssue.VPN)
    }
    private suspend fun <T> request(call: ((Result<T>) -> Unit) -> Unit): T =
        suspendCancellableCoroutine { continuation ->
            call { result ->
                if (continuation.isActive) result.fold(continuation::resume, continuation::resumeWithException)
            }
        }
}

@Singleton
internal class TraccarTransport @Inject constructor(
    private val vpn: TraccarVpn,
    private val preferences: TraccarPreferences,
) {
    private val base = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .proxy(Proxy.NO_PROXY).callTimeout(10, TimeUnit.SECONDS).build()

    suspend fun send(config: TraccarConfig, point: TraccarPosition) = withContext(Dispatchers.IO) {
        validateTraccarConfig(config, preferences.tailnet())
        val network = vpn.network()
        // Binding sockets and DNS to this VPN prevents fallback to Wi-Fi/mobile during a tunnel outage.
        val client = base.newBuilder().socketFactory(network.socketFactory)
            .dns { network.getAllByName(it).toList() }.build()
        try {
            client.newCall(Request.Builder().url(config.url).post(traccarForm(config, point)).build()).execute().use {
                if (!it.isSuccessful) throw TraccarFailure(TraccarIssue.SERVER)
            }
        } finally {
            client.connectionPool.evictAll()
        }
    }
}
