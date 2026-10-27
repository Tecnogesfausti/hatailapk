package io.homeassistant.companion.android.hatail

import android.content.Context
import android.graphics.Bitmap
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_IMAGE_EDGE = 1280
private const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
private const val IMAGE_QUALITY = 85

/** Snapshots remain private to this APK and are never re-fetched from camera URLs. */
internal class HatailHistoryImageStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val folder get() = File(context.noBackupFilesDir, "hatail_history_images")

    suspend fun save(id: String, bitmap: Bitmap): Boolean = withContext(Dispatchers.IO) {
        val destination = file(id)
        if (destination.exists()) return@withContext true
        check(folder.isDirectory || folder.mkdirs())
        val ratio = minOf(1f, MAX_IMAGE_EDGE.toFloat() / maxOf(bitmap.width, bitmap.height))
        val resized = if (ratio < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                maxOf(1, (bitmap.width * ratio).toInt()),
                maxOf(1, (bitmap.height * ratio).toInt()),
                true,
            )
        } else {
            bitmap
        }
        val bytes = try {
            ByteArrayOutputStream().use { out ->
                if (!resized.compress(
                        Bitmap.CompressFormat.JPEG,
                        IMAGE_QUALITY,
                        out,
                    )
                ) {
                    throw IOException("Image encoding failed")
                }
                out.toByteArray()
            }
        } finally {
            if (resized !== bitmap) resized.recycle()
        }
        if (bytes.size > MAX_IMAGE_BYTES) return@withContext false
        val atomic = AtomicFile(destination)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
        true
    }

    suspend fun read(id: String): ByteArray? = withContext(Dispatchers.IO) {
        file(id).takeIf { it.isFile && it.length() <= MAX_IMAGE_BYTES }?.readBytes()
    }

    suspend fun removeExcept(ids: Set<String>) = withContext(Dispatchers.IO) {
        folder.listFiles()?.forEach { image ->
            if (image.name.substringBefore('.') !in ids) image.delete()
        }
    }

    private fun file(id: String): File {
        require(id.matches(Regex("[0-9a-f]{64}")))
        return File(folder, "$id.jpg")
    }
}
