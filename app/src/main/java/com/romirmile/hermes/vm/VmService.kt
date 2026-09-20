package com.romirmile.hermes.vm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.romirmile.hermes.R

/**
 * Keeps the VM alive while the app is in the background.
 *
 * Android may reclaim a plain background process, which would take QEMU and the agent with it, so
 * the engine runs under a foreground service with a visible notification — the user can always see
 * that a Linux VM is running on their phone, and stop it from the app.
 */
class VmService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.engine_notification_title))
            .setContentText(getString(R.string.engine_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "hermes_engine"
        private const val NOTIFICATION_ID = 4711

        fun start(context: Context) {
            val intent = Intent(context, VmService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VmService::class.java))
        }

        /** Created from both the activity and the service: whichever runs first wins, it is idempotent. */
        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.engine_notification_channel),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = context.getString(R.string.engine_notification_channel_desc) }
            )
        }
    }
}
