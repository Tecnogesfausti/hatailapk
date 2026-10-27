@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.homeassistant.companion.android.hatail

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class HatailUpdateRepositoryTest {
    private val storage = mockk<HatailUpdateStorage>(relaxed = true)
    private val client = mockk<HatailReleaseClient>()
    private val clock = mockk<Clock>()
    private var now = Instant.parse("2026-10-27T10:00:00Z")
    private val installed = InstalledComponent("home-assistant/android", "development", "a".repeat(40))
    private val build = HatailBuildInformation("build-test", listOf(installed))
    private val release = PublishedRelease("2026.11.0", "Release", "Changes", "2026-10-27", "b".repeat(40), ReleaseRelation.Newer)
    private var saved: HatailUpdateCache? = null

    private fun repository(): HatailUpdateRepository {
        every { clock.now() } answers { now }
        coEvery { storage.buildInformation() } returns build
        coEvery { storage.load() } answers { saved }
        coEvery { storage.save(any()) } coAnswers { saved = firstArg() }
        coEvery { client.latest(any()) } returns release
        return HatailUpdateRepository(storage, client, clock)
    }

    @ParameterizedTest
    @CsvSource("identical,current", "ahead,newer", "behind,older", "diverged,diverged", "unrecognized,unknown")
    fun `Given GitHub comparison when classifying then distinguish upgrades and older stable code`(status: String, expected: String) {
        val actual = when (releaseRelation(status)) {
            ReleaseRelation.Current -> "current"
            ReleaseRelation.Newer -> "newer"
            ReleaseRelation.Older -> "older"
            ReleaseRelation.Diverged -> "diverged"
            ReleaseRelation.Unknown -> "unknown"
        }
        assertEquals(expected, actual)
    }

    @Test fun `Given successful check when reopened then preserve result and throttle repeated checks`() = runTest {
        val repository = repository()
        repository.refresh()
        repository.refresh(manual = true)
        assertTrue(repository.state.value.throttled)
        assertEquals(release, repository.state.value.components.single().release)
        assertEquals(now.toEpochMilliseconds(), repository.state.value.components.single().checkedAtEpochMs)
        coVerify(exactly = 1) { client.latest(installed) }
        val reopened = HatailUpdateRepository(storage, client, clock)
        reopened.load()
        assertEquals(release, reopened.state.value.components.single().release)
    }

    @Test fun `Given cached result when network fails then preserve its date and show failure`() = runTest {
        val repository = repository()
        repository.refresh()
        val checkedAt = now.toEpochMilliseconds()
        now += 25.hours
        coEvery { client.latest(any()) } throws IOException("Offline")
        repository.refresh()
        val component = repository.state.value.components.single()
        assertEquals(release, component.release)
        assertEquals(checkedAt, component.checkedAtEpochMs)
        assertTrue(component.failed)
        assertFalse(repository.state.value.refreshing)
    }

    @Test fun `Given newly embedded commit when opening updated APK then invalidate the old comparison`() = runTest {
        val repository = repository()
        repository.refresh()
        val changed = installed.copy(commit = "c".repeat(40))
        coEvery { storage.buildInformation() } returns build.copy(components = listOf(changed))
        val updated = HatailUpdateRepository(storage, client, clock)
        updated.load()
        assertNull(updated.state.value.components.single().release)
        updated.refresh()
        coVerify(exactly = 1) { client.latest(changed) }
    }

    @Test fun `Given first check offline when loading versions then installed information remains available`() = runTest {
        val repository = repository()
        coEvery { client.latest(any()) } throws IOException("Offline")
        repository.refresh()
        assertEquals(installed, repository.state.value.components.single().installed)
        assertTrue(repository.state.value.components.single().failed)
        assertFalse(repository.state.value.hasNewerRelease)
    }

    @Test fun `Given cancelled check when resuming then allow retry without persisting an incomplete result`() = runTest {
        val repository = repository()
        coEvery { client.latest(any()) } throws CancellationException("Worker stopped")
        try {
            repository.refresh()
        } catch (_: CancellationException) {
            // A stopped worker must propagate cancellation, not turn it into a network result.
        }
        assertEquals(0L, repository.state.value.attemptedAtEpochMs)
        assertFalse(repository.state.value.refreshing)
        assertNull(saved)
        coEvery { client.latest(any()) } returns release
        repository.refresh()
        assertEquals(release, repository.state.value.components.single().release)
    }
}
