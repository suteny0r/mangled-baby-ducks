package com.suteny0r.mangledbabyducks.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.suteny0r.mangledbabyducks.db.MessageEntity
import com.suteny0r.mangledbabyducks.radio.MeshProtocol
import kotlinx.coroutines.flow.Flow

private sealed interface Thread {
    data class Channel(val index: Int, val name: String) : Thread
    data class Direct(val peerNum: Long, val name: String) : Thread
}

@Composable
fun MessagesScreen(vm: MessagesViewModel = viewModel()) {
    var openThread by rememberSaveable(
        stateSaver = androidx.compose.runtime.saveable.Saver(
            save = {
                when (it) {
                    null -> ""
                    is Thread.Channel -> "c:${it.index}:${it.name}"
                    is Thread.Direct -> "d:${it.peerNum}:${it.name}"
                }
            },
            restore = {
                val parts = it.split(":", limit = 3)
                when (parts.getOrNull(0)) {
                    "c" -> Thread.Channel(parts[1].toInt(), parts[2])
                    "d" -> Thread.Direct(parts[1].toLong(), parts[2])
                    else -> null
                }
            },
        )
    ) { mutableStateOf<Thread?>(null) }

    when (val thread = openThread) {
        null -> ThreadList(vm, onOpen = { openThread = it })
        is Thread.Channel -> ThreadView(
            title = "#${thread.name}",
            messages = vm.channelMessages(thread.index),
            vm = vm,
            onSend = { vm.sendToChannel(it, thread.index) },
            onOpened = { vm.markChannelRead(thread.index) },
            onBack = { openThread = null },
        )
        is Thread.Direct -> ThreadView(
            title = thread.name,
            messages = vm.directMessages(thread.peerNum),
            vm = vm,
            onSend = { vm.sendDirect(it, thread.peerNum) },
            onOpened = { vm.markDmRead(thread.peerNum) },
            onBack = { openThread = null },
        )
    }
}

@Composable
private fun ThreadList(vm: MessagesViewModel, onOpen: (Thread) -> Unit) {
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
            ListItem(
                headlineContent = { Text(channel.name?.ifEmpty { "Primary" } ?: "Primary") },
                leadingContent = { Icon(Icons.Default.Tag, contentDescription = null) },
                supportingContent = { Text("Channel ${channel.index}") },
                modifier = Modifier.clickableListItem {
                    onOpen(Thread.Channel(channel.index, channel.name?.ifEmpty { "Primary" } ?: "Primary"))
                },
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
                    "No conversations yet",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        items(contacts, key = { "u${it.num}" }) { user ->
            ListItem(
                headlineContent = { Text(user.longName ?: "Node ${user.num}") },
                leadingContent = { Icon(Icons.Default.Person, contentDescription = null) },
                supportingContent = {
                    user.lastMessage?.let { Text(relativeTime(it)) }
                },
                modifier = Modifier.clickableListItem {
                    onOpen(Thread.Direct(user.num, user.longName ?: "Node ${user.num}"))
                },
            )
        }
    }
}

private fun Modifier.clickableListItem(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThreadView(
    title: String,
    messages: Flow<List<MessageEntity>>,
    vm: MessagesViewModel,
    onSend: (String) -> Unit,
    onOpened: () -> Unit,
    onBack: () -> Unit,
) {
    val list by messages.collectAsState(initial = emptyList())
    val myNum by vm.myNodeNum.collectAsState()
    val listState = rememberLazyListState()

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
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(list, key = { it.messageId }) { message ->
                MessageBubble(message, mine = message.fromNum == myNum, vm = vm)
            }
        }
        Composer(onSend)
    }
}

@Composable
private fun MessageBubble(message: MessageEntity, mine: Boolean, vm: MessagesViewModel) {
    val senderName by produceState(initialValue = if (mine) "You" else "…", message.fromNum) {
        value = if (mine) "You"
        else vm.userFor(message.fromNum)?.let { it.longName ?: "Node ${it.num}" }
            ?: "Node ${message.fromNum}"
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (mine) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
            ),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (!mine) {
                    Text(senderName, style = MaterialTheme.typography.labelSmall)
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
