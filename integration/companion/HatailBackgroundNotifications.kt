package io.homeassistant.companion.android.hatail

import es.tecnogesfausti.hatail.core.TailnetAddressPolicy
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.database.settings.SensorUpdateFrequencySetting
import io.homeassistant.companion.android.database.settings.Setting
import io.homeassistant.companion.android.database.settings.SettingsDao
import io.homeassistant.companion.android.database.settings.WebsocketSetting
import java.net.URI
import javax.inject.Inject

internal data class HomeServerBinding(val id: Int, val url: String)

/** Configure only explicitly bound homes; selecting a dashboard does not select a notification transport. */
internal class HatailBackgroundNotifications @Inject constructor(
    private val servers: ServerManager,
    private val settings: SettingsDao,
) {
    suspend fun configure(bindings: List<HomeServerBinding>, tailnet: String) {
        val registered = servers.servers()
        for (binding in bindings.distinctBy { it.id }) {
            val server = registered.singleOrNull { it.id == binding.id && it.connection.isRegistered } ?: continue
            val url = URI(server.connection.externalUrl)
            if (!sameHomeAssistantEndpoint(url, URI(binding.url)) ||
                !TailnetAddressPolicy.accepts(url, tailnet)
            ) {
                continue
            }
            val prepared = server.withTailnetConnection()
            if (prepared != server) servers.updateServer(prepared)
            val current = settings.get(server.id)
            if (current?.websocketSetting != WebsocketSetting.ALWAYS) {
                settings.insert(
                    current?.copy(websocketSetting = WebsocketSetting.ALWAYS)
                        ?: Setting(server.id, WebsocketSetting.ALWAYS, SensorUpdateFrequencySetting.NORMAL),
                )
            }
        }
    }
}

/** Equal automation tags from different homes must not replace or clear each other's alerts. */
internal fun scopeHomeNotification(data: Map<String, String>, serverId: Int): Map<String, String> =
    data + listOf("tag", "group").mapNotNull { key ->
        data[key]?.takeIf { it.isNotBlank() }?.let { key to "hatail:$serverId:$it" }
    }.toMap()
