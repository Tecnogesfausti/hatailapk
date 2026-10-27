package io.homeassistant.companion.android.hatail

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.homeassistant.companion.android.common.R
import javax.inject.Inject

private const val SAMPLE_CHANNEL = "hatail_history_sample"
private const val SAMPLE_TEXT_ID = 48101
private const val SAMPLE_IMAGE_ID = 48102

/** Local test alerts make it possible to verify listener access without involving either HA server. */
internal class HatailHistorySample @Inject constructor(@ApplicationContext private val context: Context) {
    fun post(): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        manager.createNotificationChannel(
            NotificationChannel(
                SAMPLE_CHANNEL,
                context.getString(R.string.hatail_history_sample_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        fun builder() = NotificationCompat.Builder(context, SAMPLE_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.hatail_history_sample_title))
            .setSubText(context.getString(R.string.hatail_history_sample_origin))
            .setAutoCancel(true)
        val image = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        Canvas(image).apply {
            drawColor(Color.rgb(0, 150, 190))
            drawText(
                context.getString(R.string.hatail_history_sample_picture),
                32f,
                190f,
                Paint().apply {
                    color = Color.WHITE
                    textSize = 36f
                    isAntiAlias = true
                },
            )
        }
        manager.notify(
            SAMPLE_TEXT_ID,
            builder().setContentText(context.getString(R.string.hatail_history_sample_text)).build(),
        )
        manager.notify(
            SAMPLE_IMAGE_ID,
            builder().setContentText(context.getString(R.string.hatail_history_sample_image_text))
                .setStyle(NotificationCompat.BigPictureStyle().bigPicture(image)).build(),
        )
        return true
    }
}
