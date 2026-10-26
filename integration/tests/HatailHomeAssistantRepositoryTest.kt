package io.homeassistant.companion.android.hatail

import io.homeassistant.companion.android.common.data.integration.IntegrationRepository
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.database.server.Server
import io.homeassistant.companion.android.database.server.ServerConnectionInfo
import io.homeassistant.companion.android.database.server.ServerSessionInfo
import io.homeassistant.companion.android.database.server.ServerUserInfo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HatailHomeAssistantRepositoryTest {
    private val manager = mockk<ServerManager>(relaxed = true)
    private val integration = mockk<IntegrationRepository>(relaxed = true)
    private val repository = HatailHomeAssistantRepository(manager)
    private val pending = Server(
        id = 1,
        _name = "Casa",
        connection = ServerConnectionInfo(externalUrl = "http://100.64.0.10:8123"),
        session = ServerSessionInfo(),
        user = ServerUserInfo(),
    )

    @Test fun `OAuth row does not trigger a webhook probe until device registration completes`() = runTest {
        val complete = pending.copy(connection = pending.connection.copy(webhookId = "test-webhook"))
        coEvery { manager.servers() } returnsMany listOf(listOf(pending), listOf(complete))
        coEvery { manager.integrationRepository(1) } returns integration
        assertNull(repository.verify(null, null, "casa.ts.net"))
        coVerify(exactly = 0) { integration.getConfig() }
        coVerify(exactly = 0) { manager.updateServer(any()) }
        assertEquals(1, repository.verify(null, null, "casa.ts.net")?.id)
        coVerify(exactly = 1) { integration.getConfig() }
    }

    @Test fun `private HTTP is restricted to VPN and cloud fallback is disabled`() = runTest {
        val complete = pending.copy(
            connection = pending.connection.copy(
                webhookId = "test-webhook",
                secret = "test-secret",
                useCloud = true,
                cloudUrl = "https://example.test",
                cloudhookUrl = "https://example.test/hook",
            ),
        )
        coEvery { manager.servers() } returns listOf(complete)
        coEvery { manager.integrationRepository(1) } returns integration
        val result = requireNotNull(repository.verify(null, null, "casa.ts.net"))
        assertEquals(complete.connection.externalUrl, result.connection.internalUrl)
        assertTrue(result.connection.internalVpn == true)
        assertFalse(result.connection.allowInsecureConnection == true)
        assertFalse(result.connection.useCloud)
        assertNull(result.connection.cloudhookUrl)
        assertEquals("test-webhook", result.connection.webhookId)
        assertEquals("test-secret", result.connection.secret)
    }

    @Test fun `a verified server is not silently rebound to another registration`() = runTest {
        val another = pending.copy(id = 2, connection = pending.connection.copy(webhookId = "test-webhook"))
        coEvery { manager.servers() } returns listOf(another)
        var rejected = false
        try {
            repository.verify(1, pending.connection.externalUrl, "casa.ts.net")
        } catch (_: NoSuchElementException) {
            rejected = true
        }
        assertTrue(rejected)
        coVerify(exactly = 0) { manager.activateServer(any()) }
    }

    @Test fun `Sol requires its own URL before selecting a registered server`() = runTest {
        val casa = pending.copy(connection = pending.connection.copy(webhookId = "casa-webhook"))
        coEvery { manager.servers() } returns listOf(casa)
        assertNull(repository.verify(null, null, "shared.ts.net", allowSingleUnbound = false))
        coVerify(exactly = 0) { manager.activateServer(any()) }
    }

    @Test fun `two homes on one tailnet activate only the matching server`() = runTest {
        val casa = pending.copy(connection = pending.connection.copy(webhookId = "casa-webhook"))
        val sol = pending.copy(
            id = 2,
            _name = "Sol",
            connection = pending.connection.copy(externalUrl = "http://100.64.0.20:8123", webhookId = "sol-webhook"),
        )
        coEvery { manager.servers() } returns listOf(casa, sol)
        coEvery { manager.integrationRepository(2) } returns integration
        val selected = repository.verify(null, null, "shared.ts.net", "${sol.connection.externalUrl}/", allowSingleUnbound = false)
        assertEquals(2, selected?.id)
        coVerify(exactly = 1) { manager.activateServer(2) }
        coVerify(exactly = 0) { manager.activateServer(1) }
        coVerify(exactly = 1) { integration.getConfig() }
    }
}
