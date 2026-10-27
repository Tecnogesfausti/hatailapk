@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.homeassistant.companion.android.hatail

import android.graphics.Bitmap
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HatailHistoryRepositoryTest {
    private val dao = mockk<HatailHistoryDao>()
    private val images = mockk<HatailHistoryImageStore>(relaxed = true)
    private val clock = mockk<Clock>()
    private var now = Instant.parse("2026-10-27T12:00:00Z")
    private val rows = mutableMapOf<String, HatailHistoryItem>()
    private val image = mockk<Bitmap>()
    private val item = HatailHistoryItem(historyEventId("ha", "event", 1), "ha", now.toEpochMilliseconds(), "Alert", "Text", "Sol", true)

    private fun repository(): HatailHistoryRepository {
        every { clock.now() } answers { now }
        coEvery { dao.get(any()) } answers { rows[firstArg()] }
        coEvery { dao.save(any()) } coAnswers { firstArg<HatailHistoryItem>().let { rows[it.id] = it } }
        coEvery { dao.deleteBefore(any()) } coAnswers {
            rows.entries.removeIf { it.value.postedAtEpochMs < firstArg<Long>() }
            Unit
        }
        coEvery { dao.ids() } answers { rows.keys.toList() }
        coEvery { images.save(any(), any()) } returns true
        return HatailHistoryRepository(dao, images, clock)
    }

    @Test fun `Given repeated delivery when recording then keep one entry and the original image snapshot`() = runTest {
        val repository = repository()
        repository.record(item, image)
        repository.record(item, image)
        assertEquals(1, rows.size)
        assertTrue(rows[item.id]!!.hasImage)
        coVerify(exactly = 1) { images.save(item.id, image) }
    }

    @Test fun `Given an old active notification when importing then reject it and remove expired copies`() = runTest {
        val repository = repository()
        rows[item.id] = item
        now += 49.hours
        repository.record(item, image)
        assertTrue(rows.isEmpty())
        coVerify(exactly = 0) { images.save(any(), any()) }
        coVerify { images.removeExcept(emptySet()) }
    }

    @Test fun `Given text only alert when recording then do not create an attachment`() = runTest {
        val repository = repository()
        repository.record(item.copy(imageExpected = false), null)
        assertFalse(rows[item.id]!!.hasImage)
        assertEquals("Text", rows[item.id]!!.message)
        coVerify(exactly = 0) { images.save(any(), any()) }
    }

    @Test fun `Given image write failure when recording then preserve the notification text`() = runTest {
        val repository = repository()
        coEvery { images.save(any(), any()) } throws IOException("Storage unavailable")
        repository.record(item, image)
        assertFalse(rows[item.id]!!.hasImage)
        assertTrue(rows[item.id]!!.imageExpected)
        assertEquals("Text", rows[item.id]!!.message)
    }

    @Test fun `Given expired attachment when opening then never read the old image`() = runTest {
        val repository = repository()
        rows[item.id] = item.copy(hasImage = true)
        now += 49.hours
        assertNull(repository.image(item.id))
        coVerify(exactly = 0) { images.read(any()) }
    }

    @Test fun `Given same notification key when posted by separate apps then keep their identities separate`() {
        assertEquals(historyEventId("ha", "key", 10), historyEventId("ha", "key", 10))
        assertNotEquals(historyEventId("ha", "key", 10), historyEventId("official", "key", 10))
        assertNotEquals(historyEventId("ha", "key", 10), historyEventId("ha", "key", 11))
        assertEquals(64, historyEventId("ha", "key", 10).length)
    }
}
