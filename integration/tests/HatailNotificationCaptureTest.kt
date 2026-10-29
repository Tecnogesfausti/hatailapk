@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.homeassistant.companion.android.hatail

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import dagger.hilt.android.testing.HiltTestApplication
import io.homeassistant.companion.android.BuildConfig
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class HatailNotificationCaptureTest {
    @Test fun `sensor worker status is excluded without hiding ordinary alerts`() {
        assertFalse(historyAcceptsNotification(BuildConfig.APPLICATION_ID, 0, "Sensor Worker"))
        assertFalse(historyAcceptsNotification("io.homeassistant.companion.android", 0, "Sensor Worker"))
        assertTrue(historyAcceptsNotification(BuildConfig.APPLICATION_ID, 0, "alerts"))
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val history = mockk<HatailHistoryRepository>(relaxed = true)
    private val clock = mockk<Clock>()
    private val now = Instant.parse("2026-10-27T12:00:00Z")

    private fun notification(pkg: String, picture: Boolean = false, flags: Int = 0, posted: Long = now.toEpochMilliseconds()): StatusBarNotification {
        val builder = NotificationCompat.Builder(context, "alerts").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Test title").setContentText("Test message").setSubText("Sol")
        if (picture) builder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888)))
        val notification = builder.build().apply { this.flags = this.flags or flags }
        return StatusBarNotification(pkg, pkg, 1, "test", 1000, 0, 0, notification, Process.myUserHandle(), posted)
    }

    @Test fun `Given unrelated apps or service summaries then archive none of them`() = runTest {
        every { clock.now() } returns now
        val capture = HatailNotificationCapture(context, history, clock)
        capture.capture(notification("com.example.other"))
        capture.capture(notification(BuildConfig.APPLICATION_ID, flags = Notification.FLAG_FOREGROUND_SERVICE))
        capture.capture(notification("io.homeassistant.companion.android", flags = Notification.FLAG_GROUP_SUMMARY))
        coVerify(exactly = 0) { history.record(any(), any()) }
    }

    @Test fun `Given official Companion image alert then capture its displayed text origin and bitmap`() = runTest {
        every { clock.now() } returns now
        HatailNotificationCapture(context, history, clock).capture(notification("io.homeassistant.companion.android", picture = true))
        coVerify(exactly = 1) {
            history.record(match { it.title == "Test title" && it.message == "Test message" && it.origin == "Sol" && it.imageExpected }, any<Bitmap>())
        }
    }

    @Test fun `Given own persistent user alert then keep it even though it is ongoing`() = runTest {
        every { clock.now() } returns now
        HatailNotificationCapture(context, history, clock).capture(notification(BuildConfig.APPLICATION_ID, flags = Notification.FLAG_ONGOING_EVENT))
        coVerify(exactly = 1) { history.record(match { !it.imageExpected }, null) }
    }

    @Test fun `Given old tray notification then do not backfill beyond 48 hours`() = runTest {
        every { clock.now() } returns now
        HatailNotificationCapture(context, history, clock).capture(notification("io.homeassistant.companion.android", posted = (now - 49.hours).toEpochMilliseconds()))
        coVerify(exactly = 0) { history.record(any(), any()) }
    }

    @Test fun `Given diagnostic channel then ignore it but accept ordinary HA channels`() {
        assertFalse(historyAcceptsNotification(BuildConfig.APPLICATION_ID, 0, "leakcanary_retained_objects"))
        assertTrue(historyAcceptsNotification(BuildConfig.APPLICATION_ID, 0, "alarm"))
    }
}
