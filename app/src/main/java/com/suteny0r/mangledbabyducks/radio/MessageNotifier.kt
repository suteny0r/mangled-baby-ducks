package com.suteny0r.mangledbabyducks.radio

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.suteny0r.mangledbabyducks.MainActivity
import com.suteny0r.mangledbabyducks.R
import com.suteny0r.mangledbabyducks.db.MeshDatabase
import com.suteny0r.mangledbabyducks.db.MessageEntity
import com.suteny0r.mangledbabyducks.db.nodeNumString
import com.suteny0r.mangledbabyducks.ui.ThreadTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Posts a notification per conversation for inbound text messages. Port of the message
 * half of LocalNotificationManager.swift, shaped as a MessagingStyle conversation so
 * Android Auto reads new messages aloud and offers a voice reply: that needs a Person per
 * sender, a Reply action carrying a RemoteInput and a Mark-as-read action, both flagged
 * with their semantic action and as not showing UI. Self-echoes and dedupes never reach
 * this point (the ingest layer drops them); reactions are skipped like the iOS badge logic.
 */
class MessageNotifier(
    private val context: Context,
    private val db: MeshDatabase,
    radioManager: RadioManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** Recent messages per conversation, so the notification shows the thread, not one line. */
    private val history = HashMap<String, ArrayDeque<NotificationCompat.MessagingStyle.Message>>()

    private val me = Person.Builder().setName("Me").setKey("me").build()

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

    /** The user read the thread (phone screen, car screen, or Mark-as-read): drop its notification. */
    fun dismiss(target: ThreadTarget) {
        val key = conversationKey(target)
        synchronized(history) { history.remove(key) }
        notificationManager.cancel(notificationId(key))
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
        val channelName = db.channelDao().get(message.channel)?.name?.ifEmpty { null }
            ?: if (message.channel == 0) "Primary" else "channel ${message.channel}"
        val target = if (message.toNum == null) {
            ThreadTarget.Channel(message.channel, channelName)
        } else {
            ThreadTarget.Direct(message.fromNum, sender)
        }
        val key = conversationKey(target)
        val id = notificationId(key)

        val senderPerson = Person.Builder()
            .setName(sender)
            .setKey(nodeNumString(message.fromNum))
            .build()
        val style = NotificationCompat.MessagingStyle(me)
        if (target is ThreadTarget.Channel) {
            style.setConversationTitle("#$channelName").setGroupConversation(true)
        }
        val thread = synchronized(history) {
            val deque = history.getOrPut(key) { ArrayDeque() }
            deque.addLast(
                NotificationCompat.MessagingStyle.Message(message.payload.orEmpty(), message.timestamp, senderPerson)
            )
            while (deque.size > HISTORY_DEPTH) deque.removeFirst()
            deque.toList()
        }
        thread.forEach(style::addMessage)

        // Deep link: tapping the notification opens the exact conversation.
        val tapIntent = Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_OPEN_THREAD, true)
            .putExtra(MainActivity.EXTRA_THREAD_NAME, target.threadName())
            .apply {
                when (target) {
                    is ThreadTarget.Channel -> putExtra(MainActivity.EXTRA_CHANNEL, target.index)
                    is ThreadTarget.Direct -> putExtra(MainActivity.EXTRA_DM_PEER, target.peerNum)
                }
            }
        val contentIntent = PendingIntent.getActivity(
            context, id, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        // Voice reply: the RemoteInput result rides in the broadcast, so it must be mutable.
        val replyIntent = PendingIntent.getBroadcast(
            context, id,
            MessageActionReceiver.intent(context, MessageActionReceiver.ACTION_REPLY, target),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val replyAction = NotificationCompat.Action.Builder(R.drawable.ic_car_reply, "Reply", replyIntent)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .setAllowGeneratedReplies(true)
            .addRemoteInput(RemoteInput.Builder(MessageActionReceiver.KEY_REPLY).setLabel("Reply").build())
            .build()
        val readIntent = PendingIntent.getBroadcast(
            context, id,
            MessageActionReceiver.intent(context, MessageActionReceiver.ACTION_MARK_READ, target),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val readAction = NotificationCompat.Action.Builder(R.drawable.ic_car_done, "Mark as read", readIntent)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentTitle(if (target is ThreadTarget.Channel) "$sender (#$channelName)" else sender)
            .setContentText(message.payload)
            .setContentIntent(contentIntent)
            .addAction(replyAction)
            .addAction(readAction)
            .setAutoCancel(true)
            .build()
        // One notification id per conversation: a burst stacks into the thread instead of
        // producing a notification per message, which is what the car reads out.
        notificationManager.notify(id, notification)
    }

    companion object {
        private const val CHANNEL_ID = "messages"
        private const val HISTORY_DEPTH = 8

        fun conversationKey(target: ThreadTarget): String = when (target) {
            is ThreadTarget.Channel -> "channel:${target.index}"
            is ThreadTarget.Direct -> "dm:${target.peerNum}"
        }

        private fun notificationId(key: String): Int = key.hashCode() and 0x7FFFFFFF

        private fun ThreadTarget.threadName(): String = when (this) {
            is ThreadTarget.Channel -> name
            is ThreadTarget.Direct -> name
        }
    }
}
