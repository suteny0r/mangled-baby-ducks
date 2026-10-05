package com.suteny0r.mangledbabyducks.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.suteny0r.mangledbabyducks.container
import com.suteny0r.mangledbabyducks.db.ChannelEntity
import com.suteny0r.mangledbabyducks.db.MessageEntity
import com.suteny0r.mangledbabyducks.db.UserEntity
import com.suteny0r.mangledbabyducks.db.nodeNumString
import com.suteny0r.mangledbabyducks.radio.MeshProtocol
import com.suteny0r.mangledbabyducks.ui.theme.IosGreen
import com.suteny0r.mangledbabyducks.ui.theme.IosOrange
import com.suteny0r.mangledbabyducks.ui.theme.IosRed
import kotlinx.coroutines.flow.Flow

/*
 * Port of the iOS Messages stack: Messages.swift (two-row sidebar), ChannelList.swift and
 * UserList.swift (conversation rows), ChannelMessageList / UserMessageList (the thread),
 * ChannelMessageRow / UserMessageRow + MessageText (bubbles), TapbackResponses, and
 * TextMessageField (the composer).
 */

/** Canonical tapback set from the iOS MessagingEnums; arbitrary emoji also arrive fine. */
private val TAPBACKS = listOf("👋", "❤️", "👍", "👎", "🤣", "‼️", "❓", "💩")

/** CircleText(..., color: .accentColor).brightness(0.2): the lighter accent for channel avatars. */
private const val CHANNEL_AVATAR_NUM = 0x5E8BE6L

/** CircleText(..., color: .accentColor) at full strength: the thread's principal avatar. */
private const val ACCENT_NUM = 0x2855A8L

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

    if (openThread != null) {
        BackHandler { openThread = null }
    }
    when (val thread = openThread) {
        null -> ThreadList(vm, onOpen = { openThread = it })
        is ThreadTarget.Channel -> ThreadView(
            target = thread,
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
            target = thread,
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

// ---------------------------------------------------------------------------------------
// Sidebar + conversation lists

@Composable
private fun ThreadList(vm: MessagesViewModel, onOpen: (ThreadTarget) -> Unit) {
    val channels by vm.channels.collectAsState()
    val contacts by vm.dmContacts.collectAsState()
    val unreadChannels by vm.unreadChannels.collectAsState()
    val unreadDirect by vm.unreadDirect.collectAsState()
    // Messages.swift: the sidebar is two rows, Channels and Direct Messages, and each
    // opens its own list (ChannelList / UserList). Held in local state like the thread.
    var section by rememberSaveable { mutableStateOf<String?>(null) }

    section?.let { open ->
        BackHandler { section = null }
        val channelPreviews by vm.channelPreviews.collectAsState()
        val dmPreviews by vm.dmPreviews.collectAsState()
        val unreadChannelSet by vm.unreadChannelSet.collectAsState()
        val unreadDmSet by vm.unreadDmSet.collectAsState()
        Column(Modifier.fillMaxSize()) {
            // ChannelList / UserList: round back button, then the large title.
            RoundBackButton(onBack = { section = null }, modifier = Modifier.padding(start = 12.dp, top = 8.dp))
            Text(
                if (open == "channels") "Channels" else "Direct Messages",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LazyColumn(Modifier.fillMaxSize()) {
                if (open == "channels") {
                    items(channels, key = { "c${it.index}" }) { channel ->
                        val name = channelDisplayName(channel)
                        ConversationRow(
                            unread = channel.index in unreadChannelSet,
                            avatar = { NodeAvatar(channel.index.toString(), CHANNEL_AVATAR_NUM, 46.dp) },
                            lock = channelLock(channel),
                            name = name,
                            preview = channelPreviews[channel.index],
                            muted = channel.mute,
                            onClick = { onOpen(ThreadTarget.Channel(channel.index, name)) },
                        )
                    }
                } else {
                    if (contacts.isEmpty()) {
                        item {
                            Text(
                                "No conversations yet. Start one from the Nodes tab.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                    items(contacts, key = { "u${it.num}" }) { user ->
                        val name = user.longName ?: "Node ${user.num}"
                        ConversationRow(
                            unread = user.num in unreadDmSet,
                            avatar = { NodeAvatar(user.shortName, user.num, 46.dp) },
                            lock = userLock(user),
                            name = name,
                            preview = dmPreviews[user.num],
                            muted = user.mute,
                            onClick = { onOpen(ThreadTarget.Direct(user.num, name)) },
                        )
                    }
                }
            }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        // Messages.swift: large title under the logo; no status pill on this tab.
        AppHeader("Messages", showStatus = false)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SectionRow(
            icon = Icons.Outlined.Groups,
            title = "Channels",
            badge = unreadChannels,
            onClick = { section = "channels" },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SectionRow(
            icon = Icons.Outlined.Person,
            title = "Direct Messages",
            badge = unreadDirect,
            onClick = { section = "direct" },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

private fun channelDisplayName(channel: ChannelEntity): String =
    channel.name?.takeIf { it.isNotEmpty() }
        ?: if (channel.role == 1) "Primary Channel" else "Channel ${channel.index}"

private data class LockGlyph(val icon: ImageVector, val tint: Color)

private val LockGold = Color(0xFFB8860B)

/** ChannelLock: the default one-byte PSK is "open" (gold), a real key is closed green. */
private fun channelLock(channel: ChannelEntity) =
    if ((channel.psk?.size ?: 0) > 1) LockGlyph(Icons.Filled.Lock, IosGreen)
    else LockGlyph(Icons.Filled.LockOpen, LockGold)

/** UserList: green closed lock when PKI key matches, red key when it does not, open gold otherwise. */
private fun userLock(user: UserEntity) = when {
    user.pkiEncrypted && !user.keyMatch -> LockGlyph(Icons.Filled.Key, IosRed)
    user.pkiEncrypted -> LockGlyph(Icons.Filled.Lock, IosGreen)
    else -> LockGlyph(Icons.Filled.LockOpen, LockGold)
}

/** One of the two big sidebar rows: accent glyph, title2 text, unread badge, chevron. */
@Composable
private fun SectionRow(icon: ImageVector, title: String, badge: Int, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(34.dp),
        )
        Spacer(Modifier.width(24.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        if (badge > 0) {
            Text(
                badge.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
    }
}

/**
 * ChannelList / UserList row: unread dot, avatar, lock + bold name with the time at the
 * trailing edge, the last message preview beneath, chevron.
 */
@Composable
private fun ConversationRow(
    unread: Boolean,
    avatar: @Composable () -> Unit,
    lock: LockGlyph,
    name: String,
    preview: MessageEntity?,
    muted: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .background(if (unread) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        avatar()
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(lock.icon, contentDescription = null, tint = lock.tint, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (muted) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Filled.NotificationsOff, contentDescription = "muted", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(8.dp))
                preview?.let {
                    Text(
                        listTimestamp(it.timestamp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            preview?.payload?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * iOS list-row timestamp convention (ChannelList.makeChannelRow): time-of-day today,
 * literal "Yesterday", else the date.
 */
private fun listTimestamp(epochMillis: Long): String {
    val cal = java.util.Calendar.getInstance()
    val then = (cal.clone() as java.util.Calendar).apply { timeInMillis = epochMillis }
    val nowDay = cal.get(java.util.Calendar.DAY_OF_YEAR)
    val nowYear = cal.get(java.util.Calendar.YEAR)
    val thenDay = then.get(java.util.Calendar.DAY_OF_YEAR)
    val thenYear = then.get(java.util.Calendar.YEAR)
    return when {
        nowDay == thenDay && nowYear == thenYear ->
            java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(epochMillis)
        nowYear == thenYear && nowDay - thenDay == 1 -> "Yesterday"
        else ->
            java.text.SimpleDateFormat("M/d/yy", java.util.Locale.getDefault()).format(epochMillis)
    }
}

/** Per-bubble time: "9:51 PM" today, otherwise "10/4, 9:51 PM". */
private fun bubbleTime(epochMillis: Long): String {
    val cal = java.util.Calendar.getInstance()
    val then = (cal.clone() as java.util.Calendar).apply { timeInMillis = epochMillis }
    val sameDay = cal.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR) &&
        cal.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR)
    val pattern = if (sameDay) "h:mm a" else "M/d, h:mm a"
    return java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(epochMillis)
}

/** "Oct 4, 2026 at 9:51 PM": the centered header above a message after a 60 minute gap. */
private fun headerTimestamp(epochMillis: Long): String =
    java.text.SimpleDateFormat("MMM d, yyyy 'at' h:mm a", java.util.Locale.getDefault()).format(epochMillis)

@Composable
fun RoundBackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.size(44.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Filled.ArrowBackIosNew, contentDescription = "Back", modifier = Modifier.size(20.dp))
        }
    }
}

// ---------------------------------------------------------------------------------------
// Thread

private data class ReplyContext(val messageId: Long, val preview: String)

@Composable
private fun ThreadView(
    target: ThreadTarget,
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
    var query by rememberSaveable { mutableStateOf("") }

    val byId = remember(list) { list.associateBy { it.messageId } }
    val tapbacksByTarget = remember(tapbackList) { tapbackList.groupBy { it.replyId } }
    val shown = remember(list, query) {
        if (query.isBlank()) list else list.filter { it.payload?.contains(query, ignoreCase = true) == true }
    }

    LaunchedEffect(Unit) { onOpened() }
    LaunchedEffect(list.size) {
        if (list.isNotEmpty() && query.isBlank()) listState.animateScrollToItem(list.size - 1)
    }

    // No imePadding here: the activity does not draw edge to edge, so the window itself
    // shrinks for the keyboard. Padding on top of that pushed the header and the whole
    // list off the top of the screen while typing.
    Column(Modifier.fillMaxSize()) {
        // ChannelMessageList toolbar: back, the channel / peer avatar as the principal
        // item, ConnectedDevice at the trailing edge.
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            RoundBackButton(onBack, Modifier.align(Alignment.CenterStart))
            Box(Modifier.align(Alignment.Center)) {
                when (target) {
                    is ThreadTarget.Channel -> NodeAvatar(target.index.toString(), ACCENT_NUM, 44.dp)
                    is ThreadTarget.Direct -> {
                        val peer by produceState<UserEntity?>(initialValue = null, target.peerNum) {
                            value = vm.userFor(target.peerNum)
                        }
                        NodeAvatar(peer?.shortName, target.peerNum, 44.dp)
                    }
                }
            }
            ConnectedDevicePill(Modifier.align(Alignment.CenterEnd))
        }
        // MessageSearchBar: "Find in conversation".
        TextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Find in conversation") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") }
                }
            },
            shape = RoundedCornerShape(22.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        ) {
            itemsIndexed(shown, key = { _, m -> m.messageId }) { index, message ->
                val previous = shown.getOrNull(index - 1)
                MessageRow(
                    message = message,
                    previous = previous,
                    mine = message.fromNum == myNum,
                    isDirect = target is ThreadTarget.Direct,
                    vm = vm,
                    repliedPreview = if (message.replyId > 0) {
                        byId[message.replyId]?.payload ?: "EMPTY MESSAGE"
                    } else null,
                    tapbacks = tapbacksByTarget[message.messageId].orEmpty(),
                    onTapback = { emoji -> onSend(emoji, message.messageId, true) },
                    onReply = {
                        replyTo = ReplyContext(message.messageId, (message.payload ?: "").take(80))
                    },
                )
            }
        }
        Composer(
            replyTo = replyTo,
            onCancelReply = { replyTo = null },
            onSend = { text ->
                onSend(text, replyTo?.messageId ?: 0, false)
                replyTo = null
            },
        )
    }
}

/**
 * ChannelMessageRow / UserMessageRow: optional timestamp header, the quoted reply above,
 * then avatar (others) + sender caption + bubble + tapback pill + delivery status.
 */
@Composable
private fun MessageRow(
    message: MessageEntity,
    previous: MessageEntity?,
    mine: Boolean,
    isDirect: Boolean,
    vm: MessagesViewModel,
    repliedPreview: String?,
    tapbacks: List<MessageEntity>,
    onTapback: (String) -> Unit,
    onReply: () -> Unit,
) {
    val sender by produceState<UserEntity?>(initialValue = null, message.fromNum) {
        value = vm.userFor(message.fromNum)
    }
    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        // displayTimestamp(aboveMessage:): a header when more than 60 minutes passed.
        if (previous != null && message.timestamp - previous.timestamp > 3_600_000L) {
            Text(
                headerTimestamp(message.timestamp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                textAlign = TextAlign.Center,
            )
        }
        repliedPreview?.let { quoted ->
            Row(
                Modifier.fillMaxWidth().padding(start = if (mine) 50.dp else 60.dp, end = if (mine) 0.dp else 50.dp, bottom = 4.dp),
                horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    quoted,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .border(0.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(18.dp))
                        .padding(10.dp),
                )
                Icon(
                    Icons.AutoMirrored.Filled.Reply,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 8.dp).size(22.dp),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            if (mine) {
                Spacer(Modifier.width(50.dp).weight(1f))
            } else {
                NodeAvatar(sender?.shortName, message.fromNum, 50.dp, Modifier.padding(end = 10.dp, bottom = 6.dp))
            }
            Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                if (!mine) {
                    Text(
                        "${sender?.longName ?: "Unknown"} (${sender?.userId ?: nodeNumString(message.fromNum)})",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Bubble(message, mine, onTapback, onReply)
                if (tapbacks.isNotEmpty()) {
                    TapbackPill(tapbacks, vm)
                }
                // Every bubble carries its time (user request; iOS shows only the
                // hour-gap headers). Ours shares the line with the delivery status.
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    Text(
                        bubbleTime(message.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (mine) {
                        Spacer(Modifier.width(8.dp))
                        DeliveryStatus(message, isDirect)
                    }
                }
            }
            if (!mine) Spacer(Modifier.width(50.dp).weight(1f))
        }
    }
}

/** MessageText: 15 pt corner radius, accent with white text for ours, gray bubble for theirs. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(message: MessageEntity, mine: Boolean, onTapback: (String) -> Unit, onReply: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Text(
            message.payload ?: "EMPTY MESSAGE",
            style = MaterialTheme.typography.bodyLarge,
            color = if (mine) Color.White else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(
                    if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(15.dp),
                )
                .combinedClickable(onClick = {}, onLongClick = { menuOpen = true })
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
        // MessageContextMenuItems: the tapback strip, then Reply.
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
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onReply()
                },
            )
        }
    }
}

/** TapbackResponses: a bordered pill of emoji over the sender's short name. */
@Composable
private fun TapbackPill(tapbacks: List<MessageEntity>, vm: MessagesViewModel) {
    Row(
        Modifier
            .padding(top = 4.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        tapbacks.forEach { tb ->
            val from by produceState<UserEntity?>(initialValue = null, tb.fromNum) { value = vm.userFor(tb.fromNum) }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(40.dp)) {
                Text(tb.payload ?: "", fontSize = 20.sp, maxLines = 1)
                Text(
                    from?.shortName ?: "?",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** MessageDeliveryStatusLabel under our own bubbles. */
@Composable
private fun DeliveryStatus(message: MessageEntity, isDirect: Boolean) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val (icon, text, tint) = when {
        message.ackError != 0 -> Triple(Icons.Filled.Error, "Not delivered", IosOrange)
        message.realAck -> Triple(Icons.Filled.CheckCircle, if (isDirect) "Delivered" else "Delivered to mesh", secondary)
        message.receivedAck && isDirect -> Triple(Icons.Filled.Error, "Relayed, not confirmed by recipient", IosOrange)
        message.receivedAck -> Triple(Icons.Filled.CheckCircle, "Delivered to mesh", secondary)
        else -> Triple(Icons.Filled.Schedule, "Sending...", IosOrange)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(3.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

/** TextMessageField: a capsule text field, an up-arrow send button once there is text. */
@Composable
private fun Composer(replyTo: ReplyContext?, onCancelReply: () -> Unit, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val bytes = text.encodeToByteArray().size
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (replyTo != null) {
                IconButton(onClick = onCancelReply) {
                    Icon(Icons.Filled.Cancel, contentDescription = "Cancel reply", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
                }
                Text("Reply", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp))
            }
            OutlinedTextField(
                value = text,
                onValueChange = { candidate ->
                    // Enforce the 200-byte wire limit on UTF-8 size, not char count.
                    if (candidate.encodeToByteArray().size <= MeshProtocol.MAX_TEXT_BYTES) text = candidate
                },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message") },
                maxLines = 4,
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedBorderColor = MaterialTheme.colorScheme.outline,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                ),
            )
            if (text.isNotBlank()) {
                IconButton(onClick = {
                    onSend(text.trim())
                    text = ""
                }) {
                    Icon(
                        Icons.Filled.ArrowCircleUp,
                        contentDescription = "Send",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp),
                    )
                }
            }
        }
        if (text.isNotEmpty()) {
            Text(
                "$bytes / ${MeshProtocol.MAX_TEXT_BYTES}",
                style = MaterialTheme.typography.labelSmall,
                color = if (bytes >= MeshProtocol.MAX_TEXT_BYTES) IosRed else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
            )
        }
    }
}
