package com.suteny0r.mangledbabyducks.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.suteny0r.mangledbabyducks.container
import com.suteny0r.mangledbabyducks.db.MessageEntity
import com.suteny0r.mangledbabyducks.radio.MeshProtocol
import kotlinx.coroutines.flow.Flow

/** Canonical tapback set from the iOS MessagingEnums; arbitrary emoji also arrive fine. */
private val TAPBACKS = listOf("👋", "❤️", "👍", "👎", "🤣", "‼️", "❓", "💩")

private val ThreadSaver = Saver<ThreadTarget?, String>(
    save = {
        when (it) {
            null -> ""
            is ThreadTarget.Channel -> "c:${it.index}:${it.name}"
            is ThreadTarget.Direct -> "d:${it.peerNum}:${it.name}"
        }
    },
    restore = {
        val parts = it.split(":", limit = 3)
        when (parts.getOrNull(0)) {
            "c" -> ThreadTarget.Channel(parts[1].toInt(), parts[2])
            "d" -> ThreadTarget.Direct(parts[1].toLong(), parts[2])
            else -> null
        }
    },
)

@Composable
fun MessagesScreen(vm: MessagesViewModel = viewModel()) {
    val router = LocalContext.current.container.router
    var openThread by rememberSaveable(stateSaver = ThreadSaver) {
        mutableStateOf<ThreadTarget?>(null)
    }

    // Consume cross-tab navigation (node list "message" button, notification taps)
    // exactly once.
    val pending by router.pendingThread.collectAsState()
    LaunchedEffect(pending) {
        pending?.let {
            openThread = it
            router.pendingThread.value = null
        }
    }

    when (val thread = openThread) {
        null -> ThreadList(vm, onOpen = { openThread = it })
        is ThreadTarget.Channel -> ThreadView(
            title = "#${thread.name}",
            messages = vm.channelMessages(thread.index),
            tapbacks = vm.channelTapbacks(thread.index),
            vm = vm,
            onSend = { text, replyId, isEmoji ->
                vm.sendToChannel(text, thread.index, replyId, isEmoji)
            },
            onOpened = { vm.markChannelRead(thread.index) },
            onBack = { openThread = null },
        )
        is ThreadTarget.Direct -> ThreadView(
            title = thread.name,
            messages = vm.directMessages(thread.peerNum),
            tapbacks = vm.directTapbacks(thread.peerNum),
            vm = vm,
            onSend = { text, replyId, isEmoji ->
                vm.sendDirect(text, thread.peerNum, replyId, isEmoji)
            },
            onOpened = { vm.markDmRead(thread.peerNum) },
            onBack = { openThread = null },
        )
    }
}

@Composable
private fun ThreadList(vm: MessagesViewModel, onOpen: (ThreadTarget) -> Unit) {
    val channels by vm.channels.collectAsState()
    val contacts by vm.dmContacts.collectAsState()

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                "Channels",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(16.dp),
            )
        }
        items(channels, key = { "c${it.index}" }) { channel ->
            val name = channel.name?.ifEmpty { "Primary" } ?: "Primary"
            ListItem(
                headlineContent = { Text(name) },
                leadingContent = { Icon(Icons.Default.Tag, contentDescription = null) },
                supportingContent = { Text("Channel ${channel.index}") },
                modifier = Modifier.clickable { onOpen(ThreadTarget.Channel(channel.index, name)) },
            )
        }
        item {
            Text(
                "Direct messages",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(16.dp),
            )
        }
        if (contacts.isEmpty()) {
            item {
                Text(
                    "No conversations yet — start one from the Nodes tab",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        items(contacts, key = { "u${it.num}" }) { user ->
            val name = user.longName ?: "Node ${user.num}"
            ListItem(
                headlineContent = { Text(name) },
                leadingContent = { Icon(Icons.Default.Person, contentDescription = null) },
                supportingContent = { user.lastMessage?.let { Text(relativeTime(it)) } },
                modifier = Modifier.clickable { onOpen(ThreadTarget.Direct(user.num, name)) },
            )
        }
    }
}

private data class ReplyContext(val messageId: Long, val preview: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThreadView(
    title: String,
    messages: Flow<List<MessageEntity>>,
    tapbacks: Flow<List<MessageEntity>>,
    vm: MessagesViewModel,
    onSend: (text: String, replyId: Long, isEmoji: Boolean) -> Unit,
    onOpened: () -> Unit,
    onBack: () -> Unit,
) {
    val list by messages.collectAsState(initial = emptyList())
    val tapbackList by tapbacks.collectAsState(initial = emptyList())
    val myNum by vm.myNodeNum.collectAsState()
    val listState = rememberLazyListState()
    var replyTo by remember { mutableStateOf<ReplyContext?>(null) }

    val byId = remember(list) { list.associateBy { it.messageId } }
    val tapbacksByTarget = remember(tapbackList) { tapbackList.groupBy { it.replyId } }

    LaunchedEffect(Unit) { onOpened() }
    LaunchedEffect(list.size) {
        if (list.isNotEmpty()) listState.animateScrollToItem(list.size - 1)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(list, key = { it.messageId }) { message ->
                MessageBubble(
                    message = message,
                    mine = message.fromNum == myNum,
                    vm = vm,
                    repliedPreview = if (message.replyId > 0) {
                        byId[message.replyId]?.payload ?: "(original message unavailable)"
                    } else null,
                    tapbacks = tapbacksByTarget[message.messageId].orEmpty(),
                    onTapback = { emoji -> onSend(emoji, message.messageId, true) },
                    onReply = {
                        replyTo = ReplyContext(
                            message.messageId,
                            (message.payload ?: "").take(80),
                        )
                    },
                )
            }
        }
        replyTo?.let { ctx ->
            Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Replying to: ${ctx.preview}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    IconButton(onClick = { replyTo = null }) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel reply")
                    }
                }
            }
        }
        Composer(onSend = { text ->
            onSend(text, replyTo?.messageId ?: 0, false)
            replyTo = null
        })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: MessageEntity,
    mine: Boolean,
    vm: MessagesViewModel,
    repliedPreview: String?,
    tapbacks: List<MessageEntity>,
    onTapback: (String) -> Unit,
    onReply: () -> Unit,
) {
    val senderName by produceState(initialValue = if (mine) "You" else "…", message.fromNum) {
        value = if (mine) "You"
        else vm.userFor(message.fromNum)?.let { it.longName ?: "Node ${it.num}" }
            ?: "Node ${message.fromNum}"
    }
    var menuOpen by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        Box {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (mine) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                ),
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .combinedClickable(onClick = {}, onLongClick = { menuOpen = true }),
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (!mine) {
                        Text(senderName, style = MaterialTheme.typography.labelSmall)
                    }
                    repliedPreview?.let {
                        Surface(
                            tonalElevation = 4.dp,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                modifier = Modifier.padding(6.dp),
                            )
                        }
                    }
                    Text(message.payload ?: "", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(relativeTime(message.timestamp), style = MaterialTheme.typography.labelSmall)
                        if (mine) {
                            Text(
                                when {
                                    message.realAck -> "✓✓"
                                    message.receivedAck -> "✓"
                                    message.ackError != 0 -> "✗"
                                    else -> "…"
                                },
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                Row(Modifier.padding(horizontal = 8.dp)) {
                    TAPBACKS.forEach { emoji ->
                        Text(
                            emoji,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier
                                .padding(4.dp)
                                .clickable {
                                    menuOpen = false
                                    onTapback(emoji)
                                },
                        )
                    }
                }
                DropdownMenuItem(
                    text = { Text("Reply") },
                    onClick = {
                        menuOpen = false
                        onReply()
                    },
                )
            }
        }
        if (tapbacks.isNotEmpty()) {
            val grouped = tapbacks.groupBy { it.payload ?: "" }
            Surface(
                tonalElevation = 2.dp,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.padding(top = 2.dp),
            ) {
                Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
                    grouped.forEach { (emoji, senders) ->
                        Text(
                            if (senders.size > 1) "$emoji${senders.size}" else emoji,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Composer(onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val bytes = text.encodeToByteArray().size
    Row(
        Modifier.fillMaxWidth().padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { candidate ->
                // Enforce the 200-byte wire limit on UTF-8 size, not char count.
                if (candidate.encodeToByteArray().size <= MeshProtocol.MAX_TEXT_BYTES) text = candidate
            },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Message") },
            supportingText = { Text("$bytes/${MeshProtocol.MAX_TEXT_BYTES}") },
            maxLines = 4,
        )
        IconButton(
            enabled = text.isNotBlank(),
            onClick = {
                onSend(text.trim())
                text = ""
            },
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
        }
    }
}
