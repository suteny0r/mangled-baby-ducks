package com.suteny0r.mangledbabyducks.radio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.suteny0r.mangledbabyducks.MainActivity
import com.suteny0r.mangledbabyducks.R

/**
 * Foreground service that keeps the process alive while a radio session is up —
 * the Android stand-in for iOS's bluetooth-central background mode.
 */
class RadioService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification(intent?.getStringExtra(EXTRA_NAME)))
        return START_STICKY
    }

    private fun buildNotification(deviceName: String?): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Radio connection",
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(deviceName?.let { "Connected to $it" } ?: "Connected")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "radio_connection"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "stop"
        const val EXTRA_NAME = "device_name"

        fun start(context: Context, deviceName: String?) {
            val intent = Intent(context, RadioService::class.java)
                .putExtra(EXTRA_NAME, deviceName)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, RadioService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
