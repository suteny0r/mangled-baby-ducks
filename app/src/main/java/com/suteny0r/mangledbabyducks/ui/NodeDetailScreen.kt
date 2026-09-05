package com.suteny0r.mangledbabyducks.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.RemoveCircle
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.google.protobuf.ByteString
import org.meshtastic.proto.AdminProtos
import org.meshtastic.proto.MeshProtos
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.suteny0r.mangledbabyducks.container
import com.suteny0r.mangledbabyducks.radio.RadioState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.suteny0r.mangledbabyducks.db.NodeWithUser
import com.suteny0r.mangledbabyducks.db.nodeNumString
import com.suteny0r.mangledbabyducks.db.TelemetryEntity
import com.suteny0r.mangledbabyducks.db.TracerouteEntity
import kotlinx.coroutines.launch

class NodeDetailViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app.container
    private val db = container.database

    fun node(num: Long) = db.nodeDao().nodeWithUserFlow(num)
    fun deviceMetrics(num: Long) =
        db.telemetryDao().history(num, 0, System.currentTimeMillis() - 48 * 3600_000L)
    fun environmentMetrics(num: Long) =
        db.telemetryDao().history(num, 1, System.currentTimeMillis() - 48 * 3600_000L)
    fun latestPosition(num: Long) = db.positionDao().latestFlow(num)
    fun latestDevice(num: Long) = db.telemetryDao().latestDeviceMetrics(num)
    fun traceroutes(num: Long) = db.tracerouteDao().forNode(num)

    fun runTraceroute(num: Long) {
        viewModelScope.launch { container.radioManager.sendTraceroute(num) }
    }

    /** Tap a completed traceroute card: show its path on the map. */
    fun openRoute(route: TracerouteEntity) {
        container.router.openRoute(route)
    }

    suspend fun nameFor(num: Long): String =
        db.userDao().get(num)?.let { it.longName ?: "!%08x".format(num) } ?: "!%08x".format(num)

    private suspend fun run(action: suspend () -> Boolean): Boolean =
        try { action() } catch (_: Exception) { false }

    private fun launchAdmin(action: suspend () -> Boolean) {
        viewModelScope.launch { action() }
    }

    fun shutdown(target: Long) =
        launchAdmin { run { container.radioManager.sendNodeShutdown(target) } }

    fun reboot(target: Long) =
        launchAdmin { run { container.radioManager.sendNodeReboot(target) } }

    fun remove(target: Long, removed: Long) =
        launchAdmin { run { container.radioManager.removeNode(target, removed) } }

    fun sfHistory(target: Long, channel: Int = 0) =
        launchAdmin { run { container.radioManager.requestStoreAndForwardClientHistory(target, channel) } }

    fun exchangeUser(target: Long) =
        launchAdmin { run { container.radioManager.exchangeUserInfo(target) } }

    fun sendPosition(target: Long) =
        launchAdmin {
            val fix = container.locationSharer.lastFix.value
            if (fix == null) false
            else run {
                container.radioManager.sendDestPosition(
                    toNum = target,
                    latitudeI = fix.latitudeI,
                    longitudeI = fix.longitudeI,
                    altitude = fix.altitude,
                    channel = 0,
                )
            }
        }

    fun localStats(target: Long, key: ByteArray?) =
        launchAdmin { run { container.radioManager.sendLocalStatsRequest(target, key) } }

    fun deviceMetadata(target: Long) =
        launchAdmin { run { container.radioManager.requestDeviceMetadata(target) } }

    fun sfConfig(target: Long) =
        launchAdmin { run { container.radioManager.requestStoreAndForwardConfig(target) } }

    val muted: MutableStateFlow<Boolean> = MutableStateFlow(false)
    fun syncMute(mute: Boolean) {
        if (muted.value != mute) muted.value = mute
    }
    fun setMute(num: Long, mute: Boolean) {
        muted.value = mute
        viewModelScope.launch { db.userDao().setMute(num, mute) }
    }

    val radioAvailable: StateFlow<Boolean> = container.radioManager.state.map {
        it is RadioState.Subscribed || it is RadioState.RetrievingDatabase
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun myNum(nodeNum: Long, isSelf: Boolean): Long =
        if (isSelf) nodeNum else container.radioManager.myNodeNum.value
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeDetailScreen(
    nodeNum: Long,
    onBack: () -> Unit,
    onMessage: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleIgnore: () -> Unit,
    isSelf: Boolean,
    vm: NodeDetailViewModel = viewModel(),
) {
    val context = LocalContext.current
    val entry by vm.node(nodeNum).collectAsState(initial = null)
    val metrics by vm.deviceMetrics(nodeNum).collectAsState(initial = emptyList())
    val envMetrics by vm.environmentMetrics(nodeNum).collectAsState(initial = emptyList())
    val position by vm.latestPosition(nodeNum).collectAsState(initial = null)
    val traceroutes by vm.traceroutes(nodeNum).collectAsState(initial = emptyList())
    val device by vm.latestDevice(nodeNum).collectAsState(initial = null)
    val userId = entry?.user?.userId ?: "!%08x".format(nodeNum)

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(entry?.user?.longName ?: "Node $nodeNum") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier.semantics { contentDescription = if (entry?.node?.favorite == true) "Unfavorite" else "Favorite" },
                ) {
                    Icon(
                        imageVector = if (entry?.node?.favorite == true) Icons.Filled.Star else Icons.Outlined.StarOutline,
                        contentDescription = null,
                        tint = if (entry?.node?.favorite == true) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!isSelf) {
                    IconButton(
                        onClick = onToggleIgnore,
                        modifier = Modifier.semantics { contentDescription = if (entry?.node?.ignored == true) "Unignore" else "Ignore" },
                    ) {
                        Icon(
                            imageVector = if (entry?.node?.ignored == true) Icons.Filled.RemoveCircle else Icons.Outlined.RemoveCircle,
                            contentDescription = null,
                            tint = if (entry?.node?.ignored == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = onMessage) {
                    Icon(Icons.AutoMirrored.Outlined.Message, contentDescription = "Message")
                }
            },
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // iOS Section("Node") header above the identity rows.
            Text("Node", style = MaterialTheme.typography.titleMedium)
            Card(Modifier.fillMaxWidth()) {
                Column {
                    val user = entry?.user
                    val node = entry?.node
                    ListItem(
                        headlineContent = { Text("Name") },
                        supportingContent = { Text(entry?.user?.longName ?: "Node $nodeNum") },
                        trailingContent = if (!isSelf) {
                            { CopyButton(context, entry?.user?.longName ?: "Node $nodeNum") }
                        } else {
                            null
                        },
                    )
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text("Node Number") },
                        supportingContent = { Text(nodeNum.toString()) },
                        trailingContent = { CopyButton(context, nodeNum.toString()) },
                    )
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text("User Id") },
                        supportingContent = { Text(userId) },
                    )
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text("Identity") },
                        supportingContent = {
                            Text(
                                listOfNotNull(
                                    user?.shortName?.let { "$it" },
                                    user?.hwModel,
                                    user?.role?.let { roleLabel(it) },
                                    if (user?.isLicensed == true) "licensed" else null,
                                ).joinToString("  •  ").ifEmpty { "unknown" },
                            )
                        },
                    )
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text("Link") },
                        supportingContent = {
                            Text(
                                listOfNotNull(
                                    node?.lastHeard?.let { "heard ${relativeTime(it)}" },
                                    node?.firstHeard?.let { "first heard ${relativeTime(it)}" },
                                    node?.snr?.takeIf { it != 0f }?.let { "SNR %.1f".format(it) },
                                    node?.rssi?.takeIf { it != 0 }?.let { "RSSI $it" },
                                    node?.hopsAway?.takeIf { it >= 0 }
                                        ?.let { if (it == 0) "direct" else "$it hops" },
                                    if (node?.viaMqtt == true) "via MQTT" else null,
                                ).joinToString("  •  ").ifEmpty { "—" },
                            )
                        },
                    )
                    val battery = device?.batteryLevel
                    if (battery != null) {
                        HorizontalDivider()
                        ListItem(
                            headlineContent = { Text("Battery") },
                            trailingContent = {
                                Text("${battery.coerceIn(0, 100)}%")
                            },
                        )
                    }
                    val uptime = device?.uptimeSeconds
                    if (uptime != null && uptime > 0) {
                        HorizontalDivider()
                        ListItem(
                            headlineContent = { Text("Uptime") },
                            trailingContent = { Text(uptimeLabel(uptime)) },
                        )
                    }
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text("Position") },
                        supportingContent = {
                            Text(
                                position?.let {
                                    val parts = buildList {
                                        add("%.5f, %.5f".format(it.latitude, it.longitude))
                                        if (it.altitude != 0) add("${it.altitude} m")
                                        if (it.satsInView > 0) add("${it.satsInView} sats")
                                        if (it.speed > 0) add("${it.speed} km/h")
                                        if (it.heading in 1..359) add("${it.heading}°")
                                        add(relativeTime(it.time))
                                    }
                                    parts.joinToString("  •  ")
                                } ?: "no position",
                            )
                        },
                    )
                    val publicKey = user?.publicKey
                    if (publicKey != null) {
                        HorizontalDivider()
                        ListItem(
                            headlineContent = { Text("Public Key") },
                            supportingContent = { Text(Base64.encodeToString(publicKey, Base64.NO_WRAP)) },
                            trailingContent = { CopyButton(context, Base64.encodeToString(publicKey, Base64.NO_WRAP)) },
                        )
                    }
                    if (entry?.user?.pkiEncrypted == true && entry?.user?.keyMatch == true) {
                        HorizontalDivider()
                        ListItem(
                            headlineContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Filled.Lock,
                                        contentDescription = null,
                                        tint = Color(0xFF2E7D32),
                                        modifier = Modifier.size(20.dp).padding(end = 8.dp),
                                    )
                                    Text("Signed node")
                                }
                            },
                            supportingContent = { Text("Verified automatically") },
                        )
                    }
                    if (entry?.user?.keyMatch == false) {
                        HorizontalDivider()
                        ListItem(
                            headlineContent = {
                                Text("Key mismatch", color = MaterialTheme.colorScheme.error)
                            },
                            supportingContent = {
                                Text("This node's public key changed; DMs may fail until re-verified.")
                            },
                        )
                    }
                }
            }

            NodeActions(
                nodeNum = nodeNum, isSelf = isSelf, entry = entry, vm = vm,
                onAfterRemove = onBack,
            )

            if (metrics.isNotEmpty()) {
                Text("Battery (48h)", style = MaterialTheme.typography.titleMedium)
                Card(Modifier.fillMaxWidth()) {
                    MetricChart(
                        points = metrics.mapNotNull { m ->
                            m.batteryLevel?.let { m.time to it.coerceAtMost(100).toFloat() }
                        },
                        unit = "%",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .padding(12.dp),
                    )
                }
                Text("Channel utilization (48h)", style = MaterialTheme.typography.titleMedium)
                Card(Modifier.fillMaxWidth()) {
                    MetricChart(
                        points = metrics.mapNotNull { m ->
                            m.channelUtilization?.let { m.time to it }
                        },
                        unit = "%",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .padding(12.dp),
                    )
                }
            }

            if (envMetrics.isNotEmpty()) {
                Text("Temperature (48h)", style = MaterialTheme.typography.titleMedium)
                Card(Modifier.fillMaxWidth()) {
                    MetricChart(
                        points = envMetrics.mapNotNull { m ->
                            m.temperature?.let { m.time to it }
                        },
                        unit = "°C",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .padding(12.dp),
                    )
                }
                val humidity = envMetrics.mapNotNull { m ->
                    m.relativeHumidity?.let { m.time to it }
                }
                if (humidity.size >= 2) {
                    Text("Humidity (48h)", style = MaterialTheme.typography.titleMedium)
                    Card(Modifier.fillMaxWidth()) {
                        MetricChart(
                            points = humidity,
                            unit = "%",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(140.dp)
                                .padding(12.dp),
                        )
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Traceroute", style = MaterialTheme.typography.titleMedium)
                Button(onClick = { vm.runTraceroute(nodeNum) }) { Text("Run") }
            }
            if (traceroutes.isEmpty()) {
                Text("No traceroutes yet", style = MaterialTheme.typography.bodyMedium)
            }
            traceroutes.forEach { route ->
                TracerouteCard(route, vm)
            }
        }
    }
}

@Composable
private fun TracerouteCard(route: TracerouteEntity, vm: NodeDetailViewModel) {
    val text by androidx.compose.runtime.produceState(initialValue = "…", route) {
        value = if (!route.response) {
            // No schema for timeouts; anything unanswered after 2 minutes is dead.
            if (System.currentTimeMillis() - route.time > 120_000) "no reply" else "pending"
        } else {
            buildString {
                append("→ ")
                append(routeText(route.routeTowards, route.snrTowards, vm))
                if (route.routeBack.isNotEmpty() || route.snrBack.isNotEmpty()) {
                    append("\n← ")
                    append(routeText(route.routeBack, route.snrBack, vm))
                }
            }
        }
    }
    val cardModifier = Modifier
        .fillMaxWidth()
        .let { if (route.response) it.clickable { vm.openRoute(route) } else it }
    Card(cardModifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(relativeTime(route.time), style = MaterialTheme.typography.labelSmall)
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Resolve "num,num" + "snr,snr" (scaled by 4) into "Name (x.x dB) → Name". */
private suspend fun routeText(routeCsv: String, snrCsv: String, vm: NodeDetailViewModel): String {
    val hops = routeCsv.split(",").filter { it.isNotBlank() }.map { it.toLong() }
    val snrs = snrCsv.split(",").filter { it.isNotBlank() }.map { it.toInt() / 4f }
    if (hops.isEmpty()) {
        return if (snrs.isNotEmpty()) "direct (%.1f dB)".format(snrs.last()) else "direct"
    }
    val names = hops.map { vm.nameFor(it) }
    return buildString {
        names.forEachIndexed { i, name ->
            append(name)
            snrs.getOrNull(i)?.let { append(" (%.1f dB)".format(it)) }
            if (i < names.lastIndex) append(" → ")
        }
        if (snrs.size > names.size) {
            append(" → dest (%.1f dB)".format(snrs.last()))
        }
    }
}

/** Minimal time-series line chart; no chart library needed. */
@Composable
private fun MetricChart(
    points: List<Pair<Long, Float>>,
    unit: String,
    modifier: Modifier = Modifier,
) {
    if (points.size < 2) {
        Text(
            "not enough data",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp),
        )
        return
    }
    val line = MaterialTheme.colorScheme.primary
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    val min = points.minOf { it.second }
    val max = points.maxOf { it.second }
    val range = (max - min).takeIf { it > 0f } ?: 1f
    val t0 = points.first().first
    val t1 = points.last().first
    val tRange = (t1 - t0).takeIf { it > 0 } ?: 1L

    Column(modifier) {
        Text(
            "${"%.0f".format(min)}$unit  –  ${"%.0f".format(max)}$unit",
            style = MaterialTheme.typography.labelSmall,
            color = label,
        )
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val path = Path()
            points.forEachIndexed { i, (t, v) ->
                val x = (t - t0).toFloat() / tRange * size.width
                val y = size.height - (v - min) / range * size.height
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color = line, style = Stroke(width = 4f))
        }
    }
}

private data class ConfirmableAction(
    val message: String,
    val confirmLabel: String = "Yes",
    val destructive: Boolean = false,
    val run: () -> Unit,
)

/**
 * Node actions, ordered like the iOS NodeDetail sections: Actions first
 * (mute, share QR, exchanges, stats, history), then Administration
 * (metadata refresh, power off, reboot). Destructive / remote calls
 * (shutdown, reboot, remove) require explicit confirmation behind the
 * canonical "Are you sure?" dialog; the read-only requests are idempotent
 * and go direct. Port of the node-detail action sheet in NodeView.swift +
 * NodeDetail.administrationSection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NodeActions(
    nodeNum: Long,
    isSelf: Boolean,
    entry: NodeWithUser?,
    vm: NodeDetailViewModel,
    onAfterRemove: () -> Unit,
) {
    val connected by vm.radioAvailable.collectAsState()
    val muted by vm.muted.collectAsState()
    val myNum = remember(nodeNum, isSelf) { vm.myNum(nodeNum, isSelf) }
    LaunchedEffect(entry?.user?.mute) {
        vm.syncMute(entry?.user?.mute ?: false)
    }
    val confirmPending = remember { mutableStateOf<ConfirmableAction?>(null) }
    val shareQr = remember { mutableStateOf<NodeWithUser?>(null) }

    // MARK: Actions (iOS NodeDetail actionsSection)
    Text("Actions", style = MaterialTheme.typography.titleMedium)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!connected) {
                Text(
                    "Radio disconnected: actions require a live uplink.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            ListItem(
                headlineContent = { Text(if (muted) "Unmute notifications" else "Mute notifications") },
                supportingContent = {
                    Text(if (muted) "muted" else "unmuted; tap to toggle")
                },
                modifier = Modifier.clickable { vm.setMute(nodeNum, !muted) },
            )
            if (entry?.user?.unmessagable == false) {
                ActionButton(label = "Share Contact QR", enabled = true) {
                    shareQr.value = entry
                }
            }
            ActionButton(label = "Exchange Positions", enabled = connected) {
                vm.sendPosition(target = nodeNum)
            }
            ActionButton(label = "Request Local Stats", enabled = connected) {
                vm.localStats(target = nodeNum, key = entry?.user?.publicKey)
            }
            ActionButton(label = "Exchange User Info", enabled = connected) {
                vm.exchangeUser(target = nodeNum)
            }
            ActionButton(label = "Client History", enabled = connected) {
                vm.sfHistory(target = nodeNum)
            }
            ActionButton(label = "Store & Forward: config", enabled = connected) {
                vm.sfConfig(target = nodeNum)
            }
            if (!isSelf) {
                ActionButton(
                    label = "Delete Node",
                    enabled = connected,
                    destructive = true,
                    onClick = {
                        confirmPending.value = ConfirmableAction(
                            message = "Remove ${nodeNumString(nodeNum)} from ${nodeNumString(myNum)}'s node database?",
                            confirmLabel = "Delete Node",
                            destructive = true,
                        ) {
                            vm.remove(target = myNum, removed = nodeNum); onAfterRemove()
                        }
                    },
                )
            }
        }
    }

    // MARK: Administration (iOS NodeDetail administrationSection)
    Text("Administration", style = MaterialTheme.typography.titleMedium)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(label = "Refresh device metadata", enabled = connected) {
                vm.deviceMetadata(target = nodeNum)
            }
            ActionButton(
                label = "Power Off",
                enabled = connected,
                destructive = true,
                onClick = {
                    confirmPending.value = ConfirmableAction(
                        message = if (isSelf) {
                            "Shut down this radio? It will power off after 5 s."
                        } else {
                            "Shutdown ${nodeNumString(nodeNum)}? It will power off after 5 s."
                        },
                        confirmLabel = "Shutdown Node?",
                        destructive = true,
                    ) { vm.shutdown(target = myNum) }
                },
            )
            ActionButton(
                label = "Reboot",
                enabled = connected,
                destructive = true,
                onClick = {
                    confirmPending.value = ConfirmableAction(
                        message = if (isSelf) {
                            "Reboot this radio? It will reboot after 5 s."
                        } else {
                            "Reboot ${nodeNumString(nodeNum)}? It will reboot after 5 s."
                        },
                        confirmLabel = "Reboot node?",
                        destructive = true,
                    ) { vm.reboot(target = myNum) }
                },
            )
        }
    }
    confirmPending.value?.let { act ->
        // Canonical iOS confirm shape: title "Are you sure?" (titleVisibility .visible),
        // destructive-role button whose label names the action.
        AlertDialog(
            onDismissRequest = { confirmPending.value = null },
            title = { Text("Are you sure?") },
            text = { Text(act.message) },
            confirmButton = {
                TextButton(onClick = {
                    confirmPending.value = null
                    act.run()
                }) {
                    Text(
                        act.confirmLabel,
                        color = if (act.destructive) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmPending.value = null }) { Text("Cancel") }
            },
        )
    }
    shareQr.value?.let { entry ->
        ShareContactQRDialog(entry = entry, onDismiss = { shareQr.value = null })
    }
}

/**
 * Build the meshtastic.org shared-contact URL: prefix + base64url of a
 * serialized SharedContact (admin.proto). Port of ShareContactQR.urlString.
 */
fun shareContactUrl(entry: NodeWithUser): String {
    val user = entry.user ?: return ""
    val contact = AdminProtos.SharedContact.newBuilder()
        .setNodeNum(entry.node.num.toInt())
        .setUser(
            MeshProtos.User.newBuilder()
                .setId(user.userId ?: nodeNumString(entry.node.num))
                .setLongName(user.longName ?: "")
                .setShortName(user.shortName ?: "")
                .setHwModelValue(user.hwModelId)
                .apply {
                    user.publicKey?.let { setPublicKey(ByteString.copyFrom(it)) }
                },
        )
        .setManuallyVerified(false)
        .build()
    val b64 = Base64.encodeToString(contact.toByteArray(), Base64.NO_PADDING)
        .replace('+', '-')
        .replace('/', '_')
    return "https://meshtastic.org/v/#" + b64
}

/**
 * Port of ShareContactQRDialog.swift: a QR the user scans on another phone to
 * import this node as a contact.
 */
@Composable
private fun ShareContactQRDialog(entry: NodeWithUser, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val url = remember(entry.node.num) { shareContactUrl(entry) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share Contact QR") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(entry.user?.longName ?: nodeNumString(entry.node.num), style = MaterialTheme.typography.titleMedium)
                val bmp = remember(url) { runCatching { qrBitmap(url) }.getOrNull() }
                if (bmp != null) {
                    Image(bitmap = bmp.asImageBitmap(), contentDescription = "QR code", modifier = Modifier.size(280.dp).padding(vertical = 12.dp))
                } else {
                    Text("QR generation failed", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "Scan this QR code to add to another device. Or share the link:",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_SUBJECT, "Add Meshtastic contact")
                    putExtra(android.content.Intent.EXTRA_TEXT, url)
                }
                context.startActivity(android.content.Intent.createChooser(intent, "Share contact"))
            }) { Text("Share") }
        },
        dismissButton = {
            TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("meshtastic", url))
            }) { Text("Copy link") }
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}

@Composable
private fun ActionButton(label: String, enabled: Boolean, destructive: Boolean = false, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                destructive -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.primary
            },
        )
    }
}

/** DeviceRole enum name for a stored role ordinal (config.proto Role enum). */
fun roleLabel(role: Int): String = when (role) {
    0 -> "Client"
    1 -> "Client (muted)"
    2 -> "Router"
    3 -> "Router + client"
    4 -> "Repeater"
    5 -> "Tracker"
    6 -> "Sensor"
    7 -> "TAK"
    8 -> "Client (hidden)"
    9 -> "Lost & found"
    10 -> "TAK tracker"
    11 -> "Router (late)"
    12 -> "Client base"
    else -> "role $role"
}

/** "1d 4h 12m" style uptime from raw seconds. */
fun uptimeLabel(seconds: Int): String {
    val d = seconds / 86400
    val h = (seconds % 86400) / 3600
    val m = (seconds % 3600) / 60
    return when {
        d > 0 -> "${d}d ${h}h ${m}m"
        h > 0 -> "${h}h ${m}m"
        else -> "${m}m"
    }
}

/** Small tap-to-copy affordance for identity rows (node number, public key). */
@Composable
fun CopyButton(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    Text(
        "Copy",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clickable {
                clipboard.setPrimaryClip(ClipData.newPlainText("meshtastic", text))
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
            }
            .padding(horizontal = 8.dp),
    )
}
