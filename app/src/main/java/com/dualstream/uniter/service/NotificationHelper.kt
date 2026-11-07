package com.dualstream.uniter.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dualstream.uniter.R
import com.dualstream.uniter.ui.MainActivity

/** Foreground-service notifications for both roles. */
object NotificationHelper {

    const val CHANNEL_RECEIVER = "receiver"
    const val CHANNEL_TRANSMITTER = "transmitter"
    const val ID_RECEIVER = 1001
    const val ID_TRANSMITTER = 1002

    fun ensureChannels(context: Context) {
        val nm = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RECEIVER,
                context.getString(R.string.chan_receiver_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_TRANSMITTER,
                context.getString(R.string.chan_transmitter_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
        )
    }

    fun build(
        context: Context,
        channel: String,
        title: String,
        text: String,
        stopTarget: Class<*>
    ): Notification {
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            context, 1,
            Intent(context, stopTarget).setAction(ServiceContract.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_stream)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun update(context: Context, id: Int, notification: Notification) {
        ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?.notify(id, notification)
    }
}
