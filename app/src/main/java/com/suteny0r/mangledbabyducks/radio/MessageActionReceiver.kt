package com.suteny0r.mangledbabyducks.radio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.suteny0r.mangledbabyducks.container
import com.suteny0r.mangledbabyducks.ui.ThreadTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Handles the Reply and Mark-as-read actions on a message notification. Android Auto
 * drives these from voice ("reply to ..."), so neither may open UI; the reply text
 * arrives as a RemoteInput result and goes out over the mesh like any other send.
 */
class MessageActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val target = targetFrom(intent) ?: return
        val container = context.container
        val pending = goAsync()
        scope.launch {
            try {
                when (intent.action) {
                    ACTION_REPLY -> {
                        val text = RemoteInput.getResultsFromIntent(intent)
                            ?.getCharSequence(KEY_REPLY)?.toString()?.trim()
                        if (!text.isNullOrEmpty()) {
                            when (target) {
                                is ThreadTarget.Channel ->
                                    container.radioManager.sendTextMessage(text, channel = target.index)
                                is ThreadTarget.Direct ->
                                    container.radioManager.sendTextMessage(text, toNum = target.peerNum)
                            }
                        }
                    }
                    ACTION_MARK_READ -> {
                        val dao = container.database.messageDao()
                        when (target) {
                            is ThreadTarget.Channel -> dao.markChannelRead(target.index)
                            is ThreadTarget.Direct ->
                                dao.markDmRead(container.radioManager.myNodeNum.value, target.peerNum)
                        }
                    }
                }
                // Either way the thread is dealt with; Auto expects the notification to go.
                container.messageNotifier.dismiss(target)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_REPLY = "com.suteny0r.mangledbabyducks.REPLY"
        const val ACTION_MARK_READ = "com.suteny0r.mangledbabyducks.MARK_READ"
        const val KEY_REPLY = "reply_text"
        private const val EXTRA_CHANNEL = "channel"
        private const val EXTRA_PEER = "peer"
        private const val EXTRA_NAME = "name"

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun intent(context: Context, action: String, target: ThreadTarget): Intent =
            Intent(context, MessageActionReceiver::class.java).setAction(action).apply {
                when (target) {
                    is ThreadTarget.Channel -> putExtra(EXTRA_CHANNEL, target.index).putExtra(EXTRA_NAME, target.name)
                    is ThreadTarget.Direct -> putExtra(EXTRA_PEER, target.peerNum).putExtra(EXTRA_NAME, target.name)
                }
            }

        private fun targetFrom(intent: Intent): ThreadTarget? {
            val name = intent.getStringExtra(EXTRA_NAME) ?: ""
            val peer = intent.getLongExtra(EXTRA_PEER, -1L)
            val channel = intent.getIntExtra(EXTRA_CHANNEL, -1)
            return when {
                peer >= 0 -> ThreadTarget.Direct(peer, name)
                channel >= 0 -> ThreadTarget.Channel(channel, name)
                else -> null
            }
        }
    }
}
