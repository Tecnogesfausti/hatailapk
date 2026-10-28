package io.homeassistant.companion.android.hatail

import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.database.server.Server
import io.homeassistant.companion.android.database.server.ServerConnectionInfo
import io.homeassistant.companion.android.database.server.ServerSessionInfo
import io.homeassistant.companion.android.database.server.ServerUserInfo
import io.homeassistant.companion.android.database.settings.SensorUpdateFrequencySetting
import io.homeassistant.companion.android.database.settings.Setting
import io.homeassistant.companion.android.database.settings.SettingsDao
import io.homeassistant.companion.android.database.settings.WebsocketSetting
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HatailBackgroundNotificationsTest {
    private val servers = mockk<ServerManager>(relaxed = true)
    private val settings = mockk<SettingsDao>(relaxed = true)
    private val notifications = HatailBackgroundNotifications(servers, settings)
    private fun server(id: Int, url: String) = Server(
        id = id,
        _name = "Home $id",
        connection = ServerConnectionInfo(externalUrl = url, webhookId = "test-$id"),
        session = ServerSessionInfo(),
        user = ServerUserInfo(),
    )

    @Test fun `both bound homes listen with the screen off while keeping sensor preferences`() = runTest {
        val casa = server(1, "http://100.64.0.10:8123")
        val sol = server(2, "http://100.64.0.20:8123")
        coEvery { servers.servers() } returns listOf(casa, sol)
        coEvery { settings.get(1) } returns Setting(1, WebsocketSetting.NEVER, SensorUpdateFrequencySetting.FAST_WHILE_CHARGING)
        coEvery { settings.get(2) } returns Setting(2, WebsocketSetting.SCREEN_ON, SensorUpdateFrequencySetting.NORMAL)
        notifications.configure(listOf(HomeServerBinding(1, casa.connection.externalUrl), HomeServerBinding(2, sol.connection.externalUrl)), "shared.ts.net")
        coVerify { settings.insert(Setting(1, WebsocketSetting.ALWAYS, SensorUpdateFrequencySetting.FAST_WHILE_CHARGING)) }
        coVerify { settings.insert(Setting(2, WebsocketSetting.ALWAYS, SensorUpdateFrequencySetting.NORMAL)) }
        coVerify { servers.updateServer(match { it.id == 1 && it.connection.internalVpn == true && !it.connection.useCloud }) }
        coVerify { servers.updateServer(match { it.id == 2 && it.connection.internalVpn == true && !it.connection.useCloud }) }
        coVerify(exactly = 0) { servers.activateServer(any()) }
        coVerify(exactly = 0) { servers.webSocketRepository(any()) }
    }

    @Test fun `stale bindings and unregistered or public servers cannot be enabled`() = runTest {
        coEvery { servers.servers() } returns listOf(
            server(1, "http://100.64.0.10:8123"),
            server(2, "https://example.test"),
            server(3, "http://100.64.0.30:8123").let { it.copy(connection = it.connection.copy(webhookId = null)) },
        )
        notifications.configure(
            listOf(HomeServerBinding(1, "http://100.64.0.11:8123"), HomeServerBinding(2, "https://example.test"), HomeServerBinding(3, "http://100.64.0.30:8123")),
            "shared.ts.net",
        )
        coVerify(exactly = 0) { settings.insert(any()) }
    }

    @Test fun `equal tags and groups are isolated per home including clear commands`() {
        val raw = mapOf("tag" to "camera", "group" to "security", "message" to "clear_notification", "webhook_id" to "test")
        val casa = scopeHomeNotification(raw, 1)
        val sol = scopeHomeNotification(raw, 2)
        assertEquals("hatail:1:camera", casa["tag"])
        assertEquals("hatail:2:camera", sol["tag"])
        assertEquals("hatail:1:security", casa["group"])
        assertEquals(raw["message"], casa["message"])
        assertEquals(raw["webhook_id"], casa["webhook_id"])
        assertEquals("camera", raw["tag"])
        assertNull(scopeHomeNotification(mapOf("message" to "test"), 1)["tag"])
    }

    @Test fun `notification links select only uniquely bound homes`() {
        assertEquals(Home.CASA, homeForRequestedServer(1, 1, 2))
        assertEquals(Home.SOL, homeForRequestedServer(2, 1, 2))
        assertNull(homeForRequestedServer(3, 1, 2))
        assertNull(homeForRequestedServer(-1, -1, 2))
        assertNull(homeForRequestedServer(1, 1, 1))
    }
}
