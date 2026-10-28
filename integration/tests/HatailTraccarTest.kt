package io.homeassistant.companion.android.hatail

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class HatailTraccarTest {
    private val now = 1_800_000_000_000L
    private val config = TraccarConfig("http://100.64.0.10:5055/", "test-device")
    private val point = TraccarPosition(time = now, latitude = 40.0, longitude = -3.0, accuracy = 10f)
    private val preferences = mockk<TraccarPreferences>(relaxed = true)
    private val dao = mockk<TraccarPositionDao>(relaxed = true)
    private val transport = mockk<TraccarTransport>(relaxed = true)
    private var pending: TraccarPosition? = null
    private val repository: TraccarRepository
        get() = TraccarRepository(preferences, dao, transport, Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC))

    init {
        coEvery { preferences.read() } returns config.copy(enabled = true)
        coEvery { preferences.tailnet() } returns "shared.ts.net"
        coEvery { preferences.lastSent() } returns 0
        coEvery { dao.pending() } answers { pending }
        coEvery { dao.save(any()) } coAnswers { pending = firstArg() }
        coEvery { dao.clear() } coAnswers { pending = null }
    }

    @Test fun `only the linked tailnet accepts endpoints without credentials or redirects`() {
        assertEquals(config, validateTraccarConfig(config, "shared.ts.net"))
        for (url in listOf("http://example.test:5055", "http://127.0.0.1", "http://100.64.0.10:5055/?id=test", "http://user:pass@100.64.0.10")) {
            assertThrows(IllegalArgumentException::class.java) { validateTraccarConfig(config.copy(url = url), "shared.ts.net") }
        }
        assertThrows(IllegalArgumentException::class.java) { validateTraccarConfig(config.copy(intervalSeconds = 1), "shared.ts.net") }
        assertThrows(IllegalArgumentException::class.java) { validateTraccarConfig(config.copy(deviceId = ""), "shared.ts.net") }
    }

    @Test fun `OsmAnd form preserves fix time and converts speed to knots`() {
        val body = traccarForm(config, point.copy(speedMetersPerSecond = 10f, bearing = 90f, battery = 80))
        val fields = (0 until body.size).associate { body.name(it) to body.value(it) }
        assertEquals("test-device", fields["id"])
        assertEquals(now.toString(), fields["timestamp"])
        assertEquals(19.4384449, fields.getValue("speed").toDouble(), 0.00001)
        assertEquals("80", fields["batt"])
        assertEquals("true", fields["valid"])
    }

    @Test fun `invalid inaccurate stale or future coordinates are rejected`() {
        assertTrue(point.acceptable(now, 100f))
        for (bad in listOf(point.copy(latitude = Double.NaN), point.copy(longitude = 181.0), point.copy(accuracy = 101f), point.copy(time = now - 120001), point.copy(time = now + 30001))) {
            assertFalse(bad.acceptable(now, 100f))
        }
    }

    @Test fun `VPN outage keeps only latest position and recovers without dashboard selection`() = runTest {
        val repo = repository
        coEvery { transport.send(any(), any()) } throws TraccarFailure(TraccarIssue.VPN)
        repo.record(point.copy(time = now - 1000))
        repo.record(point)
        repo.flush()
        assertEquals(point, pending)
        assertEquals(TraccarIssue.VPN, repo.state.value.issue)
        coEvery { transport.send(any(), any()) } returns Unit
        repo.flush()
        assertNull(pending)
        assertEquals(now, repo.state.value.lastSent)
        assertEquals(TraccarIssue.NONE, repo.state.value.issue)
    }

    @Test fun `disabled tracking never records or sends and stop purges pending location`() = runTest {
        val repo = repository
        repo.record(point)
        repo.enabled(false)
        repo.record(point.copy(time = now + 1))
        repo.flush()
        assertNull(pending)
        coVerify(exactly = 0) { transport.send(any(), any()) }
    }

    @Test fun `changing destination requires stop and clears old identity status and position`() = runTest {
        val repo = repository
        repo.record(point)
        try {
            repo.save(config.copy(deviceId = "other"))
            fail<Unit>("Must stop before changing identity")
        } catch (_: IllegalStateException) {
            // Expected: the old tracker keeps its configured identity.
        }
        repo.enabled(false)
        repo.save(config.copy(deviceId = "other"))
        assertNull(pending)
        assertEquals(0L, repo.state.value.lastSent)
        assertEquals("other", repo.state.value.config.deviceId)
    }

    @Test fun `restart prunes expired pending coordinates instead of replaying an old arrival`() = runTest {
        pending = point.copy(time = now - 180000)
        val repo = repository
        repo.load()
        repo.flush()
        assertNull(pending)
        coVerify(exactly = 0) { transport.send(any(), any()) }
    }

    @Test fun `a rejected server response retains the fix and does not claim acceptance`() = runTest {
        val repo = repository
        coEvery { transport.send(any(), any()) } throws TraccarFailure(TraccarIssue.SERVER)
        repo.record(point)
        repo.flush()
        assertEquals(0L, repo.state.value.lastSent)
        assertEquals(TraccarIssue.SERVER, repo.state.value.issue)
        assertTrue(repo.state.value.pending)
    }
}
