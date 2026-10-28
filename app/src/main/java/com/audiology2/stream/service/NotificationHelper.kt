package com.audiology2.stream.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.audiology2.stream.R
import com.audiology2.stream.config.AppSettings
import com.audiology2.stream.ui.MainActivity

/** Ongoing-notification channel + builders for the foreground streaming service. */
object NotificationHelper {
    const val CHANNEL_ID = "stream_channel"
    const val NOTIF_ID = 1337

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val existing = nm.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.app_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.app_name) + " microphone streaming"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    fun stopIntent(context: Context): PendingIntent {
        val i = Intent(context, StreamService::class.java).setAction(StreamService.ACTION_STOP)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getService(context, 0, i, flags)
    }

    fun buildNotification(context: Context, state: ServiceState, settings: AppSettings): androidx.core.app.NotificationCompat.Builder {
        val host = if (settings.network.host.isBlank()) "(no host)" else settings.network.host
        val content = when (state) {
            ServiceState.CONNECTED, ServiceState.STREAMING ->
                "Streaming ${settings.network.host}:${settings.network.port} (${settings.network.transport})"
            ServiceState.CONNECTING -> "Connecting to $host:${settings.network.port}"
             ServiceState.IDLE -> "Disconnected"
            ServiceState.ERROR -> "Error: ${state.name}"
            else -> "Ready"
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_stat_stop, "Stop", stopIntent(context))
            .setShowWhen(true)
    }
}

/** Lightweight description of what the foreground service is doing. */
enum class ServiceState {
    IDLE, CONNECTING, CONNECTED, STREAMING, PAUSED, ERROR
}
