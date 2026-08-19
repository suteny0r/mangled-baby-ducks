package com.suteny0r.meshtastic.radio

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.suteny0r.meshtastic.MainActivity
import com.suteny0r.meshtastic.R
import com.suteny0r.meshtastic.db.MeshDatabase
import com.suteny0r.meshtastic.db.MessageEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Posts a notification for each inbound text message. Port of the message half of
 * LocalNotificationManager.swift. Self-echoes and dedupes never reach this point
 * (the ingest layer drops them); reactions are skipped like the iOS badge logic.
 */
class MessageNotifier(
    private val context: Context,
    private val db: MeshDatabase,
    radioManager: RadioManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Messages",
                    NotificationManager.IMPORTANCE_HIGH,
                )
            )
        }
        scope.launch {
            radioManager.incomingMessages.collect { message ->
                if (!message.isEmoji) notify(message)
            }
        }
    }

    private suspend fun notify(message: MessageEntity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val sender = db.userDao().get(message.fromNum)?.let { it.longName ?: "Node ${it.num}" }
            ?: "Node ${message.fromNum}"
        val title = if (message.toNum == null) {
            val channelName = db.channelDao().get(message.channel)?.name?.ifEmpty { null }
                ?: "channel ${message.channel}"
            "$sender (#$channelName)"
        } else {
            sender
        }
        val contentIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(context)
        }
        val notification = builder
            .setContentTitle(title)
            .setContentText(message.payload)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        // One notification id per message so a burst doesn't collapse to the last one.
        notificationManager.notify((message.messageId and 0x7FFFFFFF).toInt(), notification)
    }

    companion object {
        private const val CHANNEL_ID = "messages"
    }
}
