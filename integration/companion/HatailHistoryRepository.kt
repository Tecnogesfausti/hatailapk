@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.homeassistant.companion.android.hatail

import android.graphics.Bitmap
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

internal val HISTORY_RETENTION = 48.hours

internal fun historyEventId(packageName: String, notificationKey: String, postedAtEpochMs: Long): String =
    MessageDigest.getInstance(
        "SHA-256",
    ).digest("$packageName\u0000$notificationKey\u0000$postedAtEpochMs".toByteArray())
        .joinToString("") { "%02x".format(it) }

@Singleton
internal class HatailHistoryRepository @Inject constructor(
    private val dao: HatailHistoryDao,
    private val images: HatailHistoryImageStore,
    private val clock: Clock,
) {
    private val mutex = Mutex()

    fun observe(cutoffEpochMs: Long) = dao.observeSince(cutoffEpochMs)

    suspend fun record(item: HatailHistoryItem, image: Bitmap?) = mutex.withLock {
        pruneLocked()
        if (item.postedAtEpochMs < cutoff()) return@withLock
        val previous = dao.get(item.id)
        var hasImage = previous?.hasImage == true
        val received = item.copy(
            hasImage = hasImage,
            imageExpected =
            item.imageExpected || previous?.imageExpected == true,
        )
        dao.save(received) // Persist text before processing an attachment or losing listener access.
        if (image != null && !hasImage) {
            try {
                hasImage = images.save(item.id, image)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Timber.w("HATail history image unavailable (%s)", error.javaClass.simpleName)
            }
        }
        if (hasImage != received.hasImage) dao.save(received.copy(hasImage = hasImage))
    }

    suspend fun image(id: String): ByteArray? = mutex.withLock {
        val item = dao.get(id)?.takeIf { it.postedAtEpochMs >= cutoff() && it.hasImage } ?: return@withLock null
        images.read(item.id)
    }

    suspend fun prune() = mutex.withLock { pruneLocked() }

    private suspend fun pruneLocked() {
        dao.deleteBefore(cutoff())
        images.removeExcept(dao.ids().toSet())
    }

    private fun cutoff() = clock.now().toEpochMilliseconds() - HISTORY_RETENTION.inWholeMilliseconds
}
