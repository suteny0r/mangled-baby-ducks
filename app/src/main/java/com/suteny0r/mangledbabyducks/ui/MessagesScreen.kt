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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
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
import com.suteny0r.mangledbabyducks.db.NodeEntity
import com.suteny0r.mangledbabyducks.db.UserEntity
import com.suteny0r.mangledbabyducks.db.nodeNumString
import com.suteny0r.mangledbabyducks.radio.MeshProtocol
import com.suteny0r.mangledbabyducks.ui.theme.IosGreen
import com.suteny0r.mangledbabyducks.ui.theme.IosOrange
import com.suteny0r.mangledbabyducks.ui.theme.IosRed
import kotlinx.coroutines.delay
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

/** MessageEntity.sendAckTimeout: generous so a slow multi-hop mesh does not trip it. */
private const val SEND_ACK_TIMEOUT_MS = 5 * 60 * 1000L

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
    // Which conversation list is open (Channels / Direct Messages), and where it is
    // scrolled. Both live here rather than in ThreadList: opening a thread takes that
    // composable out of composition, which discarded the state and made Back from a
    // thread land on the two-row sidebar instead of the list it was opened from.
    var section by rememberSaveable { mutableStateOf<String?>(null) }
    val sectionListState = rememberLazyListState()
    // LazyColumn anchors on item keys, so changing the search leaves the list parked on
    // whichever row was visible rather than at the first match; iOS starts at the top of
    // every result set. Driven from here so that coming back from a thread, which does
    // not change the search, leaves the list where it was.
    val contactSearch by vm.contactSearch.collectAsState()
    LaunchedEffect(contactSearch) { sectionListState.scrollToItem(0) }

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
        null -> ThreadList(
            vm = vm,
            section = section,
            onSection = { section = it },
            listState = sectionListState,
            onOpen = { openThread = it },
        )
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
private fun ThreadList(
    vm: MessagesViewModel,
    section: String?,
    onSection: (String?) -> Unit,
    listState: LazyListState,
    onOpen: (ThreadTarget) -> Unit,
) {
    val channels by vm.channels.collectAsState()
    val contacts by vm.contacts.collectAsState()
    val unreadChannels by vm.unreadChannels.collectAsState()
    val unreadDirect by vm.unreadDirect.collectAsState()
    // Messages.swift: the sidebar is two rows, Channels and Direct Messages, and each
    // opens its own list (ChannelList / UserList). The open section is hoisted to
    // MessagesScreen so a thread opened from it can come back to it.
    section?.let { open ->
        BackHandler { onSection(null) }
        val channelPreviews by vm.channelPreviews.collectAsState()
        val dmPreviews by vm.dmPreviews.collectAsState()
        val unreadChannelSet by vm.unreadChannelSet.collectAsState()
        val unreadDmSet by vm.unreadDmSet.collectAsState()
        // ChannelList / UserList confirmationDialog("This conversation will be deleted.").
        var pendingDelete by remember { mutableStateOf<ThreadTarget?>(null) }
        pendingDelete?.let { target ->
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text("This conversation will be deleted.") },
                text = { Text("Only this phone's copy is removed. Other clients keep their history.") },
                confirmButton = {
                    TextButton(onClick = {
                        when (target) {
                            is ThreadTarget.Channel -> vm.deleteChannelMessages(target.index)
                            is ThreadTarget.Direct -> vm.deleteDirectMessages(target.peerNum)
                        }
                        pendingDelete = null
                    }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
            )
        }
        val contactSearch by vm.contactSearch.collectAsState()
        Column(Modifier.fillMaxSize()) {
            // ChannelList / UserList: round back button, then the large title.
            RoundBackButton(onBack = { onSection(null) }, modifier = Modifier.padding(start = 12.dp, top = 8.dp))
            Text(
                // UserList.navigationTitle is "Contacts (<count shown>)", so it tracks the
                // search as well as the node DB.
                if (open == "channels") "Channels" else "Contacts (${contacts.size})",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp),
            )
            if (open != "channels") {
                SearchField(
                    value = contactSearch,
                    onValueChange = { vm.contactSearch.value = it },
                    placeholder = "Find a contact",
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
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
                            onDelete = if (channelPreviews[channel.index] != null) {
                                { pendingDelete = ThreadTarget.Channel(channel.index, name) }
                            } else null,
                        )
                    }
                } else {
                    if (contacts.isEmpty()) {
                        item {
                            Text(
                                if (contactSearch.isNotBlank()) "No matching contacts"
                                else "No contacts yet. Connect a radio to load its node list.",
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
                            onDelete = if (dmPreviews[user.num] != null) {
                                { pendingDelete = ThreadTarget.Direct(user.num, name) }
                            } else null,
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
            onClick = { onSection("channels") },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SectionRow(
            icon = Icons.Outlined.Person,
            title = "Direct Messages",
            badge = unreadDirect,
            onClick = { onSection("direct") },
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
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    unread: Boolean,
    avatar: @Composable () -> Unit,
    lock: LockGlyph,
    name: String,
    preview: MessageEntity?,
    muted: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    // iOS row contextMenu: Delete Messages (destructive) when the thread has any.
    var menu by remember { mutableStateOf(false) }
    Box {
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (onDelete != null) {
                DropdownMenuItem(
                    text = { Text("Delete Messages", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    onClick = {
                        menu = false
                        onDelete()
                    },
                )
            }
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = { if (onDelete != null) menu = true })
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
    // Hoisted above the node-detail branch below, which returns early and takes the thread
    // out of composition: state remembered further down would be discarded and the thread
    // would come back scrolled to the bottom instead of where it was left.
    var detailNode by rememberSaveable { mutableStateOf<Long?>(null) }
    var lastScrolled by rememberSaveable { mutableStateOf(-1) }
    var replyTo by remember { mutableStateOf<ReplyContext?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    // The delivery dialog is opened by the user from a message and held here, not in
    // the row: LazyColumn discards a row's state when it scrolls off, which happened
    // whenever a new message auto-scrolled the thread while the dialog was up.
    var statusFor by rememberSaveable { mutableStateOf<Long?>(null) }
    var detailsFor by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleteFor by rememberSaveable { mutableStateOf<Long?>(null) }

    val byId = remember(list) { list.associateBy { it.messageId } }
    val tapbacksByTarget = remember(tapbackList) { tapbackList.groupBy { it.replyId } }
    val shown = remember(list, query) {
        if (query.isBlank()) list else list.filter { it.payload?.contains(query, ignoreCase = true) == true }
    }

    LaunchedEffect(Unit) { onOpened() }
    // Only a thread that actually grew scrolls to the end, so coming back from a node
    // detail leaves the list where it was.
    LaunchedEffect(list.size) {
        if (list.isNotEmpty() && query.isBlank() && list.size != lastScrolled) {
            lastScrolled = list.size
            listState.animateScrollToItem(list.size - 1)
        }
    }
    // Delivery status is derived from the send time, so re-evaluate it every half minute
    // and an unacknowledged send flips to "Not delivered" without leaving the thread.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    // ChannelMessageRow wraps the sender avatar in a NavigationLink to NodeDetail, pushed
    // on the Messages stack: Back returns to this thread, not to the Nodes tab.
    detailNode?.let { num ->
        BackHandler { detailNode = null }
        NodeDetailScreen(
            nodeNum = num,
            onBack = { detailNode = null },
            onToggleFavorite = { vm.toggleFavorite(num) },
            onToggleIgnore = { vm.toggleIgnored(num) },
            isSelf = num == myNum,
            onMessage = {
                detailNode = null
                if (target !is ThreadTarget.Direct || target.peerNum != num) {
                    vm.openThread(ThreadTarget.Direct(num, "Node $num"))
                }
            },
        )
        return
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
                    now = now,
                    vm = vm,
                    repliedPreview = if (message.replyId > 0) {
                        byId[message.replyId]?.payload ?: "EMPTY MESSAGE"
                    } else null,
                    tapbacks = tapbacksByTarget[message.messageId].orEmpty(),
                    onTapback = { emoji -> onSend(emoji, message.messageId, true) },
                    onReply = {
                        replyTo = ReplyContext(message.messageId, (message.payload ?: "").take(80))
                    },
                    onShowStatus = { statusFor = message.messageId },
                    onShowDetails = { detailsFor = message.messageId },
                    onRetry = { vm.retry(message) },
                    onDelete = { deleteFor = message.messageId },
                    onOpenNode = { detailNode = message.fromNum },
                )
            }
        }
        deleteFor?.let { id ->
            AlertDialog(
                onDismissRequest = { deleteFor = null },
                title = { Text("Are you sure you want to delete this message?") },
                confirmButton = {
                    TextButton(onClick = {
                        vm.deleteMessage(id)
                        deleteFor = null
                    }) { Text("Delete Message", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Cancel") } },
            )
        }
        detailsFor?.let { id ->
            val message = byId[id]
            if (message == null) {
                detailsFor = null
            } else {
                MessageDetailsDialog(
                    message = message,
                    mine = message.fromNum == myNum,
                    status = if (message.fromNum == myNum) {
                        deliveryOf(message, target is ThreadTarget.Direct, now)
                    } else null,
                    vm = vm,
                    onDismiss = { detailsFor = null },
                )
            }
        }
        statusFor?.let { id ->
            // The row's message is looked up fresh so the dialog reflects the latest ack,
            // and it closes on its own once a retry has deleted the message.
            val message = byId[id]
            if (message == null) {
                statusFor = null
            } else {
                DeliveryDialog(
                    status = deliveryOf(message, target is ThreadTarget.Direct, now),
                    onRetry = {
                        statusFor = null
                        vm.retry(message)
                    },
                    onDismiss = { statusFor = null },
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
    now: Long,
    vm: MessagesViewModel,
    repliedPreview: String?,
    tapbacks: List<MessageEntity>,
    onTapback: (String) -> Unit,
    onReply: () -> Unit,
    onShowStatus: () -> Unit,
    onShowDetails: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onOpenNode: () -> Unit,
) {
    val status = if (mine) deliveryOf(message, isDirect, now) else null
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
                NodeAvatar(
                    sender?.shortName,
                    message.fromNum,
                    50.dp,
                    Modifier
                        .padding(end = 10.dp, bottom = 6.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onOpenNode),
                )
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
                Bubble(
                    message, mine, onTapback, onReply,
                    onRetry = if (status?.canRetry == true) onRetry else null,
                    onDelete = onDelete,
                    onDetails = onShowDetails,
                )
                if (tapbacks.isNotEmpty()) {
                    TapbackPill(tapbacks, vm)
                }
                if (status != null) {
                    DeliveryStatus(status, onClick = onShowStatus)
                }
            }
            if (!mine) Spacer(Modifier.width(50.dp).weight(1f))
        }
    }
}

/** MessageText: 15 pt corner radius, accent with white text for ours, gray bubble for theirs. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    message: MessageEntity,
    mine: Boolean,
    onTapback: (String) -> Unit,
    onReply: () -> Unit,
    onRetry: (() -> Unit)? = null,
    onDelete: () -> Unit,
    onDetails: () -> Unit,
) {
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
        // MessageText.cornerBadges: a white glyph on a green disc, hung off the bubble's
        // bottom trailing corner. Affirmative only, so plain traffic carries nothing.
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.align(Alignment.BottomEnd).offset(x = 7.dp, y = 7.dp),
        ) {
            if (message.pkiEncrypted) CornerBadge(Icons.Filled.Lock, "Encrypted")
            if (message.xeddsaSigned) CornerBadge(Icons.Filled.VerifiedUser, "Signed, verified")
        }
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
            DropdownMenuItem(
                text = { Text("Message Details") },
                leadingIcon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onDetails()
                },
            )
            if (onRetry != null) {
                DropdownMenuItem(
                    text = { Text("Try Again") },
                    leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onRetry()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                onClick = {
                    menuOpen = false
                    onDelete()
                },
            )
        }
    }
}

/** One corner badge: the glyph knocked out of a filled green disc, as the SF palette style draws it. */
@Composable
private fun CornerBadge(icon: ImageVector, label: String) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(20.dp)
            .background(IosGreen, CircleShape)
            .border(1.5.dp, MaterialTheme.colorScheme.background, CircleShape),
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(12.dp))
    }
}

/** A labelled line in the details dialog, the shape of SwiftUI's Label(_:systemImage:). */
@Composable
private fun IconLine(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, contentDescription = null, tint = IosGreen, modifier = Modifier.size(16.dp))
        Text(text)
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

/** MessageDeliveryStatus.swift: what the radio has told us about one of our sends. */
private data class Delivery(
    val icon: ImageVector,
    val text: String,
    val detail: String,
    val tint: Color,
    val canRetry: Boolean,
)

/**
 * MessageEntity.deliveryStatus(isDirectMessage:). The radio acks or naks a wantAck
 * message within its retransmit window; past [SEND_ACK_TIMEOUT_MS] with nothing back the
 * ack never reached the app (weak or absent mesh, or we were disconnected when it
 * arrived), so the row becomes retryable instead of an endless "Sending...".
 */
@Composable
private fun deliveryOf(message: MessageEntity, isDirect: Boolean, now: Long): Delivery {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    return when {
        message.receivedAck && isDirect && message.realAck -> Delivery(
            Icons.Filled.CheckCircle, "Delivered to recipient",
            "The recipient confirmed this message.", secondary, false,
        )
        message.receivedAck && isDirect -> Delivery(
            Icons.Filled.Error, "Relayed, not confirmed by recipient",
            "A node relayed this message, but the recipient has not confirmed it.", IosOrange, true,
        )
        message.receivedAck -> Delivery(
            Icons.Filled.CheckCircle, "Delivered to mesh", "A node on the mesh confirmed this message.", secondary, false,
        )
        message.ackError != 0 -> {
            // MessageDeliveryStatus.failed(error): the error's own label, and a retry
            // offered only where one could work. Orange when it can, red with an X when
            // it cannot, exactly as RoutingError.color does.
            val error = RoutingError.forCode(message.ackError)
            if (error == null) {
                Delivery(
                    Icons.Filled.Error, "Could not send message",
                    "The radio reported an unknown delivery error.", IosOrange, true,
                )
            } else {
                Delivery(
                    if (error.canRetry) Icons.Filled.Error else Icons.Filled.Cancel,
                    error.display,
                    error.detail,
                    if (error.canRetry) IosOrange else MaterialTheme.colorScheme.error,
                    error.canRetry,
                )
            }
        }
        message.timestamp > 0 && now - message.timestamp > SEND_ACK_TIMEOUT_MS -> Delivery(
            // notDelivered reuses maxRetransmit's detail so the two cannot drift: to the
            // user the outcome is the same, the mesh never confirmed it.
            Icons.Filled.Error, "Not delivered",
            RoutingError.MAX_RETRANSMIT.detail, IosOrange, true,
        )
        else -> Delivery(
            Icons.Filled.Schedule, "Sending...", "Waiting for the mesh to acknowledge this message.", IosOrange, false,
        )
    }
}

/** MessageDeliveryStatusLabel: the badge under our bubble; a tap asks the thread for the dialog. */
@Composable
private fun DeliveryStatus(status: Delivery, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(top = 2.dp)
            .clickable(onClick = onClick),
    ) {
        Icon(status.icon, contentDescription = null, tint = status.tint, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(3.dp))
        Text(status.text, style = MaterialTheme.typography.labelSmall, color = status.tint)
    }
}

/**
 * MessageContextMenuItems' "Message Details" submenu: the send time, who relayed it, the
 * link quality of a direct neighbour or the hop count otherwise, the relay tally, and for
 * our own sends the delivery status with its explanation.
 */
@Composable
private fun MessageDetailsDialog(
    message: MessageEntity,
    mine: Boolean,
    status: Delivery?,
    vm: MessagesViewModel,
    onDismiss: () -> Unit,
) {
    val relay by produceState<String?>(initialValue = null, message.messageId) {
        value = vm.relayDisplay(message)
    }
    val node by produceState<NodeEntity?>(initialValue = null, message.fromNum) {
        value = vm.nodeFor(message.fromNum)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Message Details") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(detailTimestamp(message.timestamp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                // The static header of the iOS context menu: Encrypted, Signed · verified,
                // Channel. Affirmative only, as there it shows nothing for plain traffic.
                if (message.pkiEncrypted) {
                    IconLine(Icons.Filled.Lock, "Encrypted")
                }
                if (message.xeddsaSigned) {
                    IconLine(Icons.Filled.VerifiedUser, "Signed · verified")
                }
                Text("Channel: ${message.channel}")
                relay?.let {
                    // "Ack Relay:" once the recipient itself confirmed, plain "Relay:" while
                    // only a relaying node has been heard from.
                    Text((if (message.realAck) "Ack Relay: " else "Relay: ") + it)
                }
                val hops = node?.hopsAway ?: -1
                if (!mine && node?.viaMqtt != true) {
                    if (hops == 0) {
                        Text("SNR ${"%.2f".format(message.snr)} dB")
                        Text("RSSI ${message.rssi} dBm")
                    } else {
                        Text("Hops Away ${hops.coerceAtLeast(0)}")
                    }
                }
                if (message.relays != 0 && !message.realAck) {
                    Text("Relayed by ${message.relays} ${if (message.relays == 1) "node" else "nodes"}")
                }
                status?.let {
                    Text(it.text)
                    Text(it.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

/** "10/5/2026, 1:24:07 PM": messageDate.formatted(date: .numeric, time: .standard). */
private fun detailTimestamp(epochMillis: Long): String =
    java.text.SimpleDateFormat("M/d/yyyy, h:mm:ss a", java.util.Locale.getDefault()).format(epochMillis)

/** RetryButton.swift's alert: the status, its detail, and Try Again when a resend makes sense. */
@Composable
private fun DeliveryDialog(status: Delivery, onRetry: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(status.text) },
        text = { Text(status.detail) },
        confirmButton = {
            if (status.canRetry) {
                TextButton(onClick = onRetry) { Text("Try Again") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (status.canRetry) "Cancel" else "OK") }
        },
    )
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
