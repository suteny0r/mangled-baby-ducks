package com.suteny0r.mangledbabyducks.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.suteny0r.mangledbabyducks.container
import com.suteny0r.mangledbabyducks.db.NodeWithUser

@Composable
fun NodesScreen(vm: NodesViewModel = viewModel()) {
    val nodes by vm.nodes.collectAsState()
    val myNum by vm.myNodeNum.collectAsState()
    val router = LocalContext.current.container.router
    var detailNode by rememberSaveable { mutableStateOf<Long?>(null) }

    detailNode?.let { num ->
        NodeDetailScreen(
            nodeNum = num,
            onBack = { detailNode = null },
            onMessage = {
                val entry = nodes.find { it.node.num == num }
                router.openThread(
                    ThreadTarget.Direct(num, entry?.user?.longName ?: "Node $num")
                )
            },
        )
        return
    }

    if (nodes.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No nodes yet — connect a radio", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(nodes, key = { it.node.num }) { entry ->
            NodeRow(
                entry = entry,
                isSelf = entry.node.num == myNum,
                onOpen = { detailNode = entry.node.num },
                onToggleFavorite = { vm.toggleFavorite(entry.node.num, !entry.node.favorite) },
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

@Composable
private fun NodeRow(
    entry: NodeWithUser,
    isSelf: Boolean,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
    onMessage: () -> Unit,
) {
    val user = entry.user
    val node = entry.node
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        headlineContent = {
            Text((user?.longName ?: "Node ${node.num}") + if (isSelf) "  (this radio)" else "")
        },
        supportingContent = {
            val parts = buildList {
                node.lastHeard?.let { add("heard ${relativeTime(it)}") }
                if (node.snr != 0f) add("SNR %.1f".format(node.snr))
                if (node.hopsAway == 0) add("direct") else if (node.hopsAway > 0) add("${node.hopsAway} hops")
                if (node.viaMqtt) add("MQTT")
            }
            Text(parts.joinToString("  •  "))
        },
        leadingContent = {
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
