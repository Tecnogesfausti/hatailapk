package io.homeassistant.companion.android.hatail

import android.content.Context
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class HatailTraccarRoomTest {
    @Test fun `pending queue stays bounded to latest fix and can be purged`() = runTest {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TraccarDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = database.positions()
            dao.save(TraccarPosition(time = 1000, latitude = 40.0, longitude = -3.0, accuracy = 10f))
            dao.save(TraccarPosition(time = 2000, latitude = 41.0, longitude = -3.0, accuracy = 10f))
            assertEquals(2000L, dao.pending()?.time)
            dao.clear()
            assertNull(dao.pending())
        } finally {
            database.close()
        }
    }
}
