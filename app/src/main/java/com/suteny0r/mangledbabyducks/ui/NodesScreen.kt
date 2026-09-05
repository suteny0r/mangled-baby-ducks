package com.suteny0r.mangledbabyducks.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.RemoveCircle
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.suteny0r.mangledbabyducks.container
import com.suteny0r.mangledbabyducks.db.NodeWithUser

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodesScreen(vm: NodesViewModel = viewModel()) {
    val nodes by vm.nodes.collectAsState()
    val myNum by vm.myNodeNum.collectAsState()
    val router = LocalContext.current.container.router
    val pending by router.pendingNode.collectAsState()
    var detailNode by rememberSaveable { mutableStateOf<Long?>(null) }

    LaunchedEffect(pending) {
        if (pending != null) {
            detailNode = pending
            router.pendingNode.value = null
        }
    }

    detailNode?.let { num ->
        NodeDetailScreen(
            nodeNum = num,
            onBack = { detailNode = null },
            onToggleFavorite = {
                val entry = nodes.find { it.node.num == num }
                vm.toggleFavorite(num, !(entry?.node?.favorite ?: false))
            },
            onToggleIgnore = {
                val entry = nodes.find { it.node.num == num }
                vm.toggleIgnored(num, !(entry?.node?.ignored ?: false))
            },
            isSelf = num == myNum,
            onMessage = {
                val entry = nodes.find { it.node.num == num }
                router.openThread(
                    ThreadTarget.Direct(num, entry?.user?.longName ?: "Node $num")
                )
            },
        )
        return
    }

    val showIgnored by vm.showIgnored.collectAsState()
    val favoritesOnly by vm.favoritesOnly.collectAsState()
    val search by vm.searchText.collectAsState()
    val batteryByNode by vm.batteryByNode.collectAsState()

    Column(Modifier.fillMaxSize()) {
        // iOS sidebar title is "Nodes (<live count>)" (NodeList.swift).
        TopAppBar(title = { Text("Nodes (${nodes.size})") })
        OutlinedTextField(
            value = search,
            onValueChange = { vm.searchText.value = it },
            label = { Text("Find a node") },
            placeholder = { Text("Name, handle, or !num") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (search.isNotEmpty()) {
                    IconButton(onClick = { vm.searchText.value = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search")
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            FilterChip(
                selected = favoritesOnly,
                onClick = { vm.favoritesOnly.value = !favoritesOnly },
                label = { Text("Favorites") },
                modifier = Modifier.padding(vertical = 4.dp),
            )
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = showIgnored,
                onClick = { vm.showIgnored.value = !showIgnored },
                label = { Text("Show ignored") },
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        if (nodes.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (search.isNotEmpty() || favoritesOnly || showIgnored) "No matching nodes"
                    else "No nodes yet — connect a radio",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(nodes, key = { it.node.num }) { entry ->
                    NodeRow(
                        entry = entry,
                        battery = batteryByNode[entry.node.num],
                        isSelf = entry.node.num == myNum,
                        onOpen = { detailNode = entry.node.num },
                        onToggleFavorite = { vm.toggleFavorite(entry.node.num, !entry.node.favorite) },
                        onToggleIgnore = { vm.toggleIgnored(entry.node.num, !entry.node.ignored) },
                        onMessage = {
                            router.openThread(
                                ThreadTarget.Direct(
                                    entry.node.num,
                                    entry.user?.longName ?: "Node ${entry.node.num}",
                                )
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun NodeRow(
    entry: NodeWithUser,
    battery: Int?,
    isSelf: Boolean,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleIgnore: () -> Unit,
    onMessage: () -> Unit,
) {
    val user = entry.user
    val node = entry.node
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        headlineContent = {
            // iOS rows identify the connected radio with the "Connected" metadata
            // line only; no name suffix.
            Text(user?.longName ?: "Node ${node.num}")
        },
        supportingContent = {
            val parts = buildList {
                if (isSelf) add("connected")
                val role = user?.role
                if (role != null && role != 0) add(roleLabel(role))
                battery?.let { add("battery ${it.coerceIn(0, 100)}%") }
                node.lastHeard?.let { add("heard ${relativeTime(it)}") }
                if (node.snr != 0f) add("SNR %.1f".format(node.snr))
                if (node.hopsAway == 0) add("direct") else if (node.hopsAway > 0) add("${node.hopsAway} hops")
                if (node.viaMqtt) add("MQTT")
            }
            Text(parts.joinToString("  •  "))
        },
        leadingContent = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(44.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        user?.shortName?.take(4) ?: "?",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                // PKI state: signed node -> green lock when the key matches, red when
                // it doesn't; unsigned -> no glyph. Mute rides the bell-slash. Port of
                // NodeListItem keyStatus + NodeAlertsButton glyph.
                if (user?.pkiEncrypted == true || user?.mute == true) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (user?.pkiEncrypted == true) {
                            Icon(
                                if (user.keyMatch) Icons.Filled.Lock else Icons.Outlined.Lock,
                                contentDescription = if (user.keyMatch) "key verified" else "key mismatch",
                                tint = if (user.keyMatch) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        if (user?.mute == true) {
                            Icon(
                                Icons.Filled.NotificationsOff,
                                contentDescription = "muted",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        },
        trailingContent = {
            Row {
                if (!isSelf) {
                    IconButton(onClick = onMessage) {
                        Icon(
                            Icons.AutoMirrored.Outlined.Message,
                            contentDescription = "Message",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        if (node.favorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                        contentDescription = "Favorite",
                        tint = if (node.favorite) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!isSelf) {
                    IconButton(onClick = onToggleIgnore) {
                        Icon(
                            if (node.ignored) Icons.Filled.RemoveCircle else Icons.Outlined.RemoveCircle,
                            contentDescription = "Ignore",
                            tint = if (node.ignored) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
    )
}

fun relativeTime(epochMs: Long): String {
    val deltaSec = (System.currentTimeMillis() - epochMs) / 1000
    return when {
        deltaSec < 60 -> "just now"
        deltaSec < 3600 -> "${deltaSec / 60}m ago"
        deltaSec < 86400 -> "${deltaSec / 3600}h ago"
        else -> "${deltaSec / 86400}d ago"
    }
}
