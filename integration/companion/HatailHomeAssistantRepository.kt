package io.homeassistant.companion.android.hatail

import es.tecnogesfausti.hatail.core.TailnetAddressPolicy
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.database.server.Server
import java.net.URI
import javax.inject.Inject
import kotlinx.coroutines.withTimeout

private const val HA_VERIFICATION_TIMEOUT_MS = 30_000L

/** Companion may add a trailing slash to a manually entered HA URL. */
internal fun sameHomeAssistantEndpoint(first: URI, second: URI): Boolean =
    first.scheme.equals(second.scheme, ignoreCase = true) &&
        first.host.equals(second.host, ignoreCase = true) &&
        first.port == second.port &&
        first.path.trimEnd('/') == second.path.trimEnd('/')

/** Verify completed Companion registrations, preserving in-progress OAuth/device registration. */
internal class HatailHomeAssistantRepository @Inject constructor(private val servers: ServerManager) {
    suspend fun verify(
        boundId: Int?,
        verifiedUrl: String?,
        tailnet: String,
        expectedUrl: String? = null,
        allowSingleUnbound: Boolean = true,
    ): Server? {
        val registered = servers.servers().filter { it.connection.isRegistered }
        if (registered.isEmpty()) return null
        val server = when {
            verifiedUrl != null -> registered.single { it.id == boundId && sameHomeAssistantEndpoint(URI(it.connection.externalUrl), URI(verifiedUrl)) }
            expectedUrl != null -> registered.singleOrNull { sameHomeAssistantEndpoint(URI(it.connection.externalUrl), URI(expectedUrl)) } ?: return null
            !allowSingleUnbound -> return null
            else -> registered.single()
        }
        require(TailnetAddressPolicy.accepts(URI(server.connection.externalUrl), tailnet))
        val connection = server.connection.copy(
            internalUrl = server.connection.externalUrl,
            internalVpn = true,
            prioritizeInternal = false,
            allowInsecureConnection = false,
            useCloud = false,
            cloudUrl = null,
            cloudhookUrl = null,
        )
        val prepared = server.copy(connection = connection)
        if (connection != server.connection) servers.updateServer(prepared)
        servers.activateServer(server.id)
        withTimeout(HA_VERIFICATION_TIMEOUT_MS) { servers.integrationRepository(server.id).getConfig() }
        return prepared
    }
}
