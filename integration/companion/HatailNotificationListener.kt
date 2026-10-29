@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.homeassistant.companion.android.hatail

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.graphics.drawable.toBitmap
import androidx.core.os.BundleCompat
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import io.homeassistant.companion.android.BuildConfig
import java.util.Locale
import javax.inject.Inject
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

internal val OFFICIAL_COMPANION_PACKAGES =
    setOf("io.homeassistant.companion.android", "io.homeassistant.companion.android.minimal")

internal fun historyAcceptsNotification(packageName: String, flags: Int, channel: String?): Boolean =
    (packageName == BuildConfig.APPLICATION_ID || packageName in OFFICIAL_COMPANION_PACKAGES) &&
        channel != "Sensor Worker" &&
        flags and Notification.FLAG_GROUP_SUMMARY == 0 &&
        flags and Notification.FLAG_FOREGROUND_SERVICE == 0 &&
        !channel.orEmpty().lowercase(Locale.ROOT).startsWith("leakcanary")

/** Captures only allowlisted HA alerts; it never reads another app's credentials or downloads a URL. */
internal class HatailNotificationCapture @Inject constructor(
    @ApplicationContext private val context: Context,
    private val history: HatailHistoryRepository,
    private val clock: Clock,
) {
    suspend fun capture(sbn: StatusBarNotification) = withContext(Dispatchers.IO) {
        val notification = sbn.notification
        if (!historyAcceptsNotification(sbn.packageName, notification.flags, notification.channelId)) return@withContext
        val posted = sbn.postTime.takeIf { it > 0 } ?: clock.now().toEpochMilliseconds()
        if (posted < clock.now().toEpochMilliseconds() - HISTORY_RETENTION.inWholeMilliseconds) return@withContext
        val extras = notification.extras
        val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.takeIf { it.isNotBlank() }
            ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n")?.takeIf {
                it.isNotBlank()
            }
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)
        val expected = extras.containsKey(Notification.EXTRA_PICTURE) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && extras.containsKey(Notification.EXTRA_PICTURE_ICON))
        val image = try {
            BundleCompat.getParcelable(extras, Notification.EXTRA_PICTURE, Bitmap::class.java)
                ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    BundleCompat.getParcelable(extras, Notification.EXTRA_PICTURE_ICON, Icon::class.java)
                        ?.loadDrawable(context)?.toBitmap()
                } else {
                    null
                }
        } catch (_: Exception) {
            null // Text remains useful when Android cannot grant access to an attachment.
        }
        val item = HatailHistoryItem(
            id = historyEventId(sbn.packageName, sbn.key, posted),
            sourcePackage = sbn.packageName,
            postedAtEpochMs = posted,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            message = text?.toString().orEmpty(),
            origin = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.takeIf { it.isNotBlank() },
            imageExpected = expected,
        )
        if (item.title.isNotBlank() || item.message.isNotBlank() || expected) history.record(item, image)
    }
}

/** Its coroutine scope belongs to this service and is cancelled when Android destroys it. */
@AndroidEntryPoint
class HatailNotificationListener : NotificationListenerService() {
    @Inject internal lateinit var capture: HatailNotificationCapture
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onListenerConnected() {
        super.onListenerConnected()
        runCatching { activeNotifications.orEmpty() }.getOrDefault(emptyArray()).forEach(::archive)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
        archive(sbn)
    }

    private fun archive(sbn: StatusBarNotification) {
        if (!historyAcceptsNotification(sbn.packageName, sbn.notification.flags, sbn.notification.channelId)) return
        scope.launch {
            try {
                capture.capture(sbn)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Timber.w("HATail notification archive failed (%s)", error.javaClass.simpleName)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
