package io.homeassistant.companion.android.hatail

import es.tecnogesfausti.hatail.core.TailnetAddressPolicy
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.database.server.Server
import java.net.URI
import javax.inject.Inject
import kotlinx.coroutines.withTimeout

private const val HA_VERIFICATION_TIMEOUT_MS = 30_000L

/** Verify completed Companion registrations, preserving in-progress OAuth/device registration. */
internal class HatailHomeAssistantRepository @Inject constructor(private val servers: ServerManager) {
    suspend fun verify(boundId: Int?, verifiedUrl: String?, tailnet: String): Server? {
        val registered = servers.servers().filter { it.connection.isRegistered }
        if (registered.isEmpty()) return null
        val server = if (verifiedUrl == null) {
            registered.single()
        } else {
            registered.single { it.id == boundId && URI(it.connection.externalUrl) == URI(verifiedUrl) }
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
