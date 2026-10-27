package io.homeassistant.companion.android.hatail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import dagger.hilt.android.testing.HiltTestApplication
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class HatailHistoryImageStoreTest {
    @Test fun `Given an image when archived then keep a bounded private copy and delete it during cleanup`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = HatailHistoryImageStore(context)
        val id = "b".repeat(64)
        val source = Bitmap.createBitmap(2000, 1000, Bitmap.Config.ARGB_8888)
        assertTrue(store.save(id, source))
        assertFalse(source.isRecycled)
        val data = store.read(id)
        assertNotNull(data)
        val decoded = BitmapFactory.decodeByteArray(data!!, 0, data.size)
        assertTrue(decoded.width <= 1280 && decoded.height <= 1280)
        assertTrue(File(context.noBackupFilesDir, "hatail_history_images/$id.jpg").exists())
        store.removeExcept(emptySet())
        assertNull(store.read(id))
    }

    @Test fun `Given an invalid file identifier then reject it before writing outside the archive`() = runTest {
        val store = HatailHistoryImageStore(ApplicationProvider.getApplicationContext())
        var rejected = false
        try {
            store.save("../outside", Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888))
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }
}
