package com.suteny0r.mangledbabyducks.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.PhonelinkErase
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.RemoveCircle
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.filled.SensorsOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.suteny0r.mangledbabyducks.container
import com.suteny0r.mangledbabyducks.db.NodeWithUser
import com.suteny0r.mangledbabyducks.db.nodeNumString
import com.suteny0r.mangledbabyducks.ui.theme.IosGreen
import com.suteny0r.mangledbabyducks.ui.theme.IosOrange
import com.suteny0r.mangledbabyducks.ui.theme.IosRed
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.ui.draw.rotate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodesScreen(vm: NodesViewModel = viewModel()) {
    val nodes by vm.nodes.collectAsState()
    val myNum by vm.myNodeNum.collectAsState()
    val router = LocalContext.current.container.router
    val pending by router.pendingNode.collectAsState()
    var detailNode by rememberSaveable { mutableStateOf<Long?>(null) }
    // Hoisted above the detail branch below, which returns early and so takes the list out
    // of composition: a state remembered inside the LazyColumn would be discarded and the
    // list would come back from the detail screen scrolled to the top.
    val listState = rememberLazyListState()

    LaunchedEffect(pending) {
        if (pending != null) {
            detailNode = pending
            router.pendingNode.value = null
        }
    }

    detailNode?.let { num ->
        BackHandler { detailNode = null }
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
                    ThreadTarget.Direct(num, entry?.user?.longName ?: nodeNumString(num))
                )
            },
        )
        return
    }

    val filter by vm.filter.collectAsState()
    var editingFilters by rememberSaveable { mutableStateOf(false) }
    val pinFavorites by vm.pinFavorites.collectAsState()
    val search by vm.searchText.collectAsState()
    val batteryByNode by vm.batteryByNode.collectAsState()
    val positionByNode by vm.positionByNode.collectAsState()
    val myLocation by vm.myLocation.collectAsState()
    val totalNodes by vm.totalNodeCount.collectAsState()

    Column(Modifier.fillMaxSize()) {
        // iOS sidebar title is "Nodes (<live count>)" (NodeList.swift), inline next to
        // the logo with the ConnectedDevice pill at the trailing edge.
        //
        // "N of M" whenever a filter is hiding nodes. iOS shows the filtered count alone,
        // which reads identically to an empty node table: "Signed" left on cut a 119-node
        // store down to the connected radio, the header said "Nodes (1)", and nothing on
        // the screen said the other 118 were still there.
        val hidden = nodes.size < totalNodes
        AppHeader(
            if (hidden) "Nodes (${nodes.size} of $totalNodes)" else "Nodes (${nodes.size})",
            large = false,
        )
        // iOS .searchable field: a gray rounded bar with the magnifier inside.
        SearchField(
            value = search,
            onValueChange = { vm.searchText.value = it },
            placeholder = "Find a node",
        )
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Ordering, not filtering: off leaves the list in plain most-recently-heard
            // order. Android Auto's node list follows this too.
            FilterChip(
                selected = pinFavorites,
                onClick = { vm.setPinFavorites(!pinFavorites) },
                label = { Text("Favorites on top") },
                modifier = Modifier.padding(vertical = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            // NodeList.swift bottom bar: a reset button while any filter is on, then the
            // filter button, filled while filtering.
            if (filter.isFiltering) {
                IconButton(onClick = vm::resetFilter) {
                    Icon(
                        Icons.Filled.RestartAlt,
                        contentDescription = "Reset node filters",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            IconButton(onClick = { editingFilters = true }) {
                Icon(
                    if (filter.isFiltering) Icons.Filled.FilterAlt else Icons.Filled.FilterList,
                    contentDescription = "Show node filters",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (editingFilters) {
            NodeFilterSheet(
                filter = filter,
                hasLocation = myLocation != null,
                onChange = vm::setFilter,
                onDismiss = { editingFilters = false },
            )
        }
        val unheard by vm.unheard.collectAsState()
        val removingUnheard by vm.removingUnheard.collectAsState()
        val unheardResult by vm.unheardRemovalResult.collectAsState()
        LaunchedEffect(unheard.staleDismissal) {
            if (unheard.staleDismissal) vm.clearUnheardDismissal()
        }
        if (unheard.show || removingUnheard) {
            UnheardNodesBanner(
                count = unheard.nums.size,
                removing = removingUnheard,
                onDelete = vm::removeUnheard,
                onKeep = vm::keepUnheard,
            )
        }
        unheardResult?.let { result ->
            AlertDialog(
                onDismissRequest = { vm.unheardRemovalResult.value = null },
                title = { Text("Unheard Nodes") },
                text = { Text(result) },
                confirmButton = { TextButton(onClick = { vm.unheardRemovalResult.value = null }) { Text("OK") } },
            )
        }
        if (nodes.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (search.isNotEmpty() || filter.isFiltering) "No matching nodes"
                    else "No nodes yet. Connect a radio.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f), state = listState) {
                items(nodes, key = { it.node.num }) { entry ->
                    val here = myLocation
                    val there = positionByNode[entry.node.num]
                    NodeRow(
                        entry = entry,
                        battery = batteryByNode[entry.node.num],
                        isSelf = entry.node.num == myNum,
                        // NodeListItem: distance and bearing only for other nodes with a
                        // position, and only once we know where we are.
                        range = if (here != null && there != null && entry.node.num != myNum) {
                            haversineMeters(here.first, here.second, there.latitude, there.longitude) to
                                bearingDegrees(here.first, here.second, there.latitude, there.longitude)
                        } else null,
                        onOpen = { detailNode = entry.node.num },
                        onToggleFavorite = { vm.toggleFavorite(entry.node.num, !entry.node.favorite) },
                        onToggleIgnore = { vm.toggleIgnored(entry.node.num, !entry.node.ignored) },
                        onMessage = {
                            router.openThread(
                                ThreadTarget.Direct(
                                    entry.node.num,
                                    entry.user?.longName ?: nodeNumString(entry.node.num),
                                )
                            )
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

/**
 * UnheardNodesBanner.swift: one aggregate offer when most of the radio's node list has not
 * been heard on its current LoRa settings, which is what a settings change does all at
 * once. The wording claims only what the radio knows; it cannot tell that nodes moved to
 * another preset, since it cannot observe a channel it is not tuned to.
 */
@Composable
private fun UnheardNodesBanner(count: Int, removing: Boolean, onDelete: () -> Unit, onKeep: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(12.dp)) {
            Icon(
                Icons.Filled.SensorsOff,
                contentDescription = null,
                tint = Color(0xFFFF9500),
                modifier = Modifier.padding(end = 12.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    "$count nodes not heard on your current LoRa settings",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                )
                Text(
                    "Your radio has not heard them on the settings it is using now, or no longer " +
                        "has them. Favorites and the connected node are kept.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
                if (removing) {
                    // One admin message per node, so a large cleanup takes a while.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(
                            "Removing…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                } else {
                    Row {
                        Button(
                            onClick = { confirming = true },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        ) { Text("Delete") }
                        OutlinedButton(onClick = onKeep, modifier = Modifier.padding(start = 12.dp)) { Text("Keep") }
                    }
                }
            }
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Remove $count nodes?") },
            text = {
                Text(
                    "They are removed from this app, and from the radio if it still has them. " +
                        "Any that are still out there come back when they are next heard."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onDelete()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}

/** iOS treats a node as online when it was heard in the last two hours. */
private const val ONLINE_WINDOW_MS = 2 * 60 * 60 * 1000L

private fun heardTimestamp(epochMs: Long): String =
    java.text.SimpleDateFormat("M/d/yyyy, h:mm a", java.util.Locale.getDefault()).format(epochMs)

/**
 * NodeListItem.swift: 70 pt avatar with battery under it, then the name row (key glyph +
 * long name + favorite star), the Connected line, last heard, role, hops, with the row
 * chevron at the trailing edge. Message / ignore live on a long-press menu, as iOS keeps
 * them in the context menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NodeRow(
    entry: NodeWithUser,
    battery: Int?,
    isSelf: Boolean,
    /** Metres and true bearing from here to the node, when both positions are known. */
    range: Pair<Double, Double>?,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleIgnore: () -> Unit,
    onMessage: () -> Unit,
) {
    val user = entry.user
    val node = entry.node
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onOpen, onLongClick = { menu = true })
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                NodeAvatar(user?.shortName, node.num, 70.dp)
                BatteryCompact(battery, Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // NodeListRowSummary.keyStatus -> NodeSecurityIndicator: signing state on
                    // 2.8 nodes, the PKI locks below that, a key mismatch at any version.
                    val indicator = NodeSecurityIndicator.status(
                        firmwareVersion = node.firmwareVersion,
                        pkiEncrypted = user?.pkiEncrypted == true,
                        keyMatch = user?.keyMatch != false,
                        signed = node.hasXeddsaSigned,
                        verified = node.isKeyManuallyVerified,
                        isOwnNode = isSelf,
                    )
                    IconAndText(
                        icon = indicator.icon,
                        text = user?.longName ?: nodeNumString(node.num),
                        iconTint = indicator.tint,
                        textColor = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (node.favorite) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = "Favorite",
                            tint = Color(0xFFB8860B),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                if (user?.mute == true) {
                    IconAndText(Icons.Filled.NotificationsOff, "Muted")
                }
                if (isSelf) {
                    IconAndText(Icons.Filled.SettingsInputAntenna, "Connected", iconTint = IosGreen)
                }
                node.lastHeard?.takeIf { it > 0 }?.let { heard ->
                    val online = System.currentTimeMillis() - heard < ONLINE_WINDOW_MS
                    IconAndText(
                        icon = if (online) Icons.Filled.CheckCircle else Icons.Filled.Bedtime,
                        text = heardTimestamp(heard),
                        iconTint = if (online) IosGreen else IosOrange,
                    )
                }
                IconAndText(
                    icon = roleIcon(user?.role ?: 0),
                    text = "Role: ${roleLabel(user?.role ?: 0)}",
                )
                if (user?.unmessagable == true) {
                    IconAndText(Icons.Filled.PhonelinkErase, "Unmonitored")
                }
                // NodeListItem: ruler + "x mi away", then a north arrow turned to the
                // bearing and the degrees.
                range?.let { (meters, bearing) ->
                    IconAndText(Icons.Filled.Straighten, "${formatDistance(meters)} away") {
                        Spacer(Modifier.width(10.dp))
                        Icon(
                            Icons.Filled.Navigation,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp).rotate(bearing.toFloat()),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "${bearing.toInt()}°",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (node.viaMqtt && !isSelf) {
                    IconAndText(Icons.Filled.CloudUpload, "MQTT")
                }
                if (node.hopsAway > 0) {
                    IconAndText(Icons.Filled.Pets, "Hops Away:") {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier
                                .size(24.dp)
                                .border(1.5.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(5.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(node.hopsAway.toString(), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                } else if (node.snr != 0f && !node.viaMqtt && !isSelf) {
                    IconAndText(Icons.Filled.NetworkCheck, "SNR %.1f dB  RSSI ${node.rssi}".format(node.snr))
                }
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (!isSelf) {
                DropdownMenuItem(
                    text = { Text("Message") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Message, contentDescription = null) },
                    onClick = { menu = false; onMessage() },
                )
            }
            DropdownMenuItem(
                text = { Text(if (node.favorite) "Remove favorite" else "Favorite") },
                leadingIcon = {
                    Icon(if (node.favorite) Icons.Filled.Star else Icons.Outlined.StarOutline, contentDescription = null)
                },
                onClick = { menu = false; onToggleFavorite() },
            )
            if (!isSelf) {
                DropdownMenuItem(
                    text = { Text(if (node.ignored) "Stop ignoring" else "Ignore") },
                    leadingIcon = {
                        Icon(if (node.ignored) Icons.Filled.RemoveCircle else Icons.Outlined.RemoveCircle, contentDescription = null)
                    },
                    onClick = { menu = false; onToggleIgnore() },
                )
            }
        }
    }
}

/** DeviceRoles.systemName equivalents for the Role line. */
private fun roleIcon(role: Int): ImageVector = when (role) {
    0 -> Icons.Filled.Smartphone          // client: flipphone
    1 -> Icons.Filled.NotificationsOff    // client mute
    2, 11 -> Icons.Filled.Router          // router / router late
    3 -> Icons.Filled.Hub                 // router client
    4 -> Icons.Filled.CellTower           // repeater
    5 -> Icons.Filled.Sensors             // tracker
    6 -> Icons.Filled.Thermostat          // sensor
    7 -> Icons.Filled.Terminal            // TAK
    8 -> Icons.Filled.VisibilityOff       // client hidden
    9 -> Icons.Filled.Campaign            // lost and found
    10 -> Icons.Filled.Terminal           // TAK tracker
    else -> Icons.Filled.Smartphone
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
