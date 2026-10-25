package es.tecnogesfausti.hatail.core

import java.net.URI
import java.util.Locale

enum class HomeId { CASA, SOL }

/** References only. Credentials belong to the upstream secure stores, never to this model. */
data class HomeProfile(
    val home: HomeId,
    val tailscaleProfileId: String,
    val tailnetDnsSuffix: String,
    val homeAssistantUrl: URI,
    val homeAssistantServerId: String,
) {
    init {
        require(tailscaleProfileId.isNotBlank()) { "A Tailscale login profile is required" }
        require(homeAssistantServerId.isNotBlank()) { "A registered HA server is required" }
        require(canonicalDns(tailnetDnsSuffix).matches(Regex("[a-z0-9-]+\\.ts\\.net"))) {
            "Use the tailnet's MagicDNS suffix, not its display name"
        }
        require(homeAssistantUrl.scheme in setOf("http", "https")) { "Use HTTP or HTTPS" }
        require(!homeAssistantUrl.host.isNullOrBlank()) { "An absolute HA URL is required" }
        require(homeAssistantUrl.userInfo == null && homeAssistantUrl.rawQuery == null &&
            homeAssistantUrl.rawFragment == null) { "HA URLs must not contain credentials, queries or fragments" }
    }
}

internal fun canonicalDns(value: String): String =
    value.trimEnd('.').lowercase(Locale.ROOT)

data class TunnelIdentity(
    val profileId: String,
    val tailnetDnsSuffix: String,
    val backendRunning: Boolean,
    val vpnEstablished: Boolean,
) {
    fun matches(profile: HomeProfile): Boolean =
        backendRunning && vpnEstablished && profileId == profile.tailscaleProfileId &&
            canonicalDns(tailnetDnsSuffix) == canonicalDns(profile.tailnetDnsSuffix)
}
