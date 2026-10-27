package io.homeassistant.companion.android.hatail

import android.content.Context
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class HatailHistoryRoomTest {
    private lateinit var database: HatailHistoryDatabase

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), HatailHistoryDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun close() {
        database.close()
    }

    @Test fun `Given records at expiry boundary then show and keep only the last 48 hour window`() = runTest {
        val dao = database.alerts()
        val base = HatailHistoryItem("a".repeat(64), "ha", 100, "Title", "Text", null, false)
        dao.save(base.copy(id = "old", postedAtEpochMs = 99))
        dao.save(base)
        dao.save(base.copy(id = "new", postedAtEpochMs = 101))
        assertEquals(listOf("new", base.id), dao.observeSince(100).first().map { it.id })
        dao.deleteBefore(100)
        assertNull(dao.get("old"))
        assertNotNull(dao.get(base.id))
    }

    @Test fun `Given listener reconnect replay then do not duplicate the same event`() = runTest {
        val dao = database.alerts()
        val item = HatailHistoryItem("a".repeat(64), "ha", 100, "Title", "Text", null, false)
        dao.save(item)
        dao.save(item.copy(hasImage = true))
        assertEquals(1, dao.observeSince(0).first().size)
        assertEquals(true, dao.get(item.id)?.hasImage)
    }
}
