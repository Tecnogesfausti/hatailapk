package es.tecnogesfausti.hatail.core

import java.net.URI

/** Restrict first-device onboarding to a private Tailscale origin, with no URL credentials. */
object TailnetAddressPolicy {
    fun accepts(uri: URI, tailnetDnsSuffix: String): Boolean {
        if (uri.scheme !in setOf("http", "https")) return false
        if (uri.userInfo != null || uri.rawQuery != null || uri.rawFragment != null) return false
        if (uri.port != -1 && uri.port !in 1..65535) return false
        val host = uri.host?.let(::canonicalDns) ?: return false
        val suffix = canonicalDns(tailnetDnsSuffix)
        if (!suffix.matches(Regex("[a-z0-9-]+\\.ts\\.net"))) return false
        if (host.endsWith(".$suffix")) return true
        val parts = host.split('.')
        if (parts.size != 4 || parts.any { it.length > 1 && it.startsWith('0') }) return false
        val numbers = parts.map { it.toIntOrNull() ?: return false }
        return numbers[0] == 100 && numbers[1] in 64..127 && numbers.all { it in 0..255 }
    }
}
