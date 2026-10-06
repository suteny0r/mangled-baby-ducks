package com.suteny0r.mangledbabyducks.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.Battery5Bar
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CellTower
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Grain
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhonelinkErase
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.StickyNote2
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import com.google.protobuf.ByteString
import com.suteny0r.mangledbabyducks.container
import com.suteny0r.mangledbabyducks.db.NodeWithUser
import com.suteny0r.mangledbabyducks.db.PositionEntity
import com.suteny0r.mangledbabyducks.db.TelemetryEntity
import com.suteny0r.mangledbabyducks.db.TracerouteEntity
import com.suteny0r.mangledbabyducks.db.nodeNumString
import com.suteny0r.mangledbabyducks.radio.HardwareCatalog
import com.suteny0r.mangledbabyducks.radio.RadioState
import com.suteny0r.mangledbabyducks.ui.theme.IosGreen
import com.suteny0r.mangledbabyducks.ui.theme.IosOrange
import com.suteny0r.mangledbabyducks.ui.theme.IosRed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.meshtastic.proto.AdminProtos
import org.meshtastic.proto.MeshProtos
import kotlin.math.roundToInt

class NodeDetailViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app.container
    private val db = container.database

    fun node(num: Long) = db.nodeDao().nodeWithUserFlow(num)
    val hardware: StateFlow<Map<Int, HardwareCatalog.Info>> = container.hardwareCatalog.byModel
    fun deviceMetrics(num: Long) =
        db.telemetryDao().history(num, 0, System.currentTimeMillis() - 48 * 3600_000L)
    fun environmentMetrics(num: Long) =
        db.telemetryDao().history(num, 1, System.currentTimeMillis() - 48 * 3600_000L)
    fun latestPosition(num: Long) = db.positionDao().latestFlow(num)
    fun positionHistory(num: Long) = db.positionDao().history(num)
    fun latestDevice(num: Long) = db.telemetryDao().latestDeviceMetrics(num)
    fun traceroutes(num: Long) = db.tracerouteDao().forNode(num)

    fun runTraceroute(num: Long) {
        viewModelScope.launch { container.radioManager.sendTraceroute(num) }
    }

    /** Tap a completed traceroute card: show its path on the map. */
    fun openRoute(route: TracerouteEntity) {
        container.router.openRoute(route)
    }

    /** "Node Map" log row: the Map tab, centered on this node. */
    fun openOnMap(num: Long) {
        container.router.openMapNode(num)
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

    /** One-shot outcome of Accept new key, shown as a toast and then cleared. */
    val keyAcceptResult = MutableStateFlow<String?>(null)

    fun acceptNewKey(num: Long) {
        viewModelScope.launch {
            val ok = run { container.radioManager.acceptNewKey(num) }
            keyAcceptResult.value =
                if (ok) "New key accepted; the radio will re-learn this node"
                else "Could not accept the key (radio not connected?)"
        }
    }

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

/** The log sub-pages reachable from the Logs section (iOS pushes these on the stack). */
private enum class DetailLog(val title: String) {
    DEVICE("Device Metrics Log"),
    ENVIRONMENT("Environment Metrics Log"),
    POSITION("Position Log"),
    TRACEROUTE("Trace Route Log"),
}

/**
 * Port of NodeDetail.swift (+ NodeInfoItem.swift for the hardware card, BatteryGauge,
 * the compact weather widgets): an inset-grouped list with the sections Hardware, Node,
 * Environment, Logs, Actions, Administration under an inline centered title.
 */
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
    val keyAcceptResult by vm.keyAcceptResult.collectAsState()
    LaunchedEffect(keyAcceptResult) {
        keyAcceptResult?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            vm.keyAcceptResult.value = null
        }
    }
    val hardware by vm.hardware.collectAsState()
    val metrics by vm.deviceMetrics(nodeNum).collectAsState(initial = emptyList())
    val envMetrics by vm.environmentMetrics(nodeNum).collectAsState(initial = emptyList())
    val position by vm.latestPosition(nodeNum).collectAsState(initial = null)
    val traceroutes by vm.traceroutes(nodeNum).collectAsState(initial = emptyList())
    val device by vm.latestDevice(nodeNum).collectAsState(initial = null)
    val user = entry?.user
    val node = entry?.node
    val title = user?.longName ?: "Node $nodeNum"
    val userId = user?.userId ?: nodeNumString(nodeNum)

    var log by rememberSaveable { mutableStateOf<DetailLog?>(null) }
    log?.let { open ->
        BackHandler { log = null }
        LogPage(open, nodeNum, metrics, envMetrics, traceroutes, vm, onBack = { log = null })
        return
    }

    var relativeDates by rememberSaveable { mutableStateOf(true) }

    Column(Modifier.fillMaxSize()) {
        DetailHeader(title, onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // MARK: Hardware (NodeInfoItem.swift): catalog image, model name, support rosette.
            val info = user?.let { hardware[it.hwModelId] }
            SectionHeader(
                when {
                    user?.hwModel == "UNSET" || info == null -> "Hardware"
                    user?.hwModel == "PORTDUINO" -> "Community Hardware"
                    else -> info.sectionTitle
                },
            )
            GroupCard {
                HardwareCard(
                    imageUrl = info?.imageUrl,
                    name = info?.displayName ?: user?.hwDisplayName ?: user?.hwModel ?: "Unknown",
                    supported = info?.activelySupported,
                )
            }

            // MARK: Node
            SectionHeader("Node")
            GroupCard {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NodeAvatar(user?.shortName, nodeNum, 75.dp)
                    if (node != null && node.snr != 0f && !node.viaMqtt && node.hopsAway == 0 && !isSelf) {
                        SignalColumn(node.snr, node.rssi)
                    }
                    if (device != null) {
                        BatteryGauge(device?.batteryLevel, device?.voltage)
                    }
                }
                if (user?.keyMatch == false) {
                    KeyMismatchRow(nodeNum, user.publicKey, user.newPublicKey, vm)
                }
                DetailRow(Icons.Outlined.AccountCircle, "Name", title)
                RowDivider()
                DetailRow(Icons.Outlined.Tag, "Node Number", nodeNum.toString()) {
                    CopyButton(context, nodeNum.toString())
                }
                RowDivider()
                DetailRow(Icons.Outlined.Person, "User Id", userId)
                if (node?.hasXeddsaSigned == true) {
                    RowDivider()
                    DetailRow(Icons.Filled.VerifiedUser, "Signed node", "Verified automatically", iconTint = IosGreen)
                }
                val publicKey = user?.publicKey
                if (publicKey != null && user.keyMatch) {
                    RowDivider()
                    DetailRow(Icons.Filled.Lock, "Public Key", null, iconTint = IosGreen) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            CopyButton(context, Base64.encodeToString(publicKey, Base64.NO_WRAP))
                        }
                    }
                }
                node?.firmwareVersion?.takeIf { it.isNotBlank() }?.let {
                    RowDivider()
                    DetailRow(Icons.Outlined.Memory, "Firmware Version", it)
                }
                RowDivider()
                DetailRow(Icons.Outlined.Smartphone, "Role", roleLabel(user?.role ?: 0))
                node?.nodeStatus?.takeIf { it.isNotBlank() }?.let {
                    RowDivider()
                    DetailRow(Icons.Outlined.StickyNote2, "Status Message", it)
                }
                if (user?.unmessagable == true) {
                    RowDivider()
                    DetailRow(Icons.Outlined.PhonelinkErase, "Messaging", "Unmonitored")
                }
                device?.uptimeSeconds?.takeIf { it > 0 }?.let {
                    RowDivider()
                    DetailRow(Icons.Filled.CheckCircle, "Uptime", uptimeFull(it), iconTint = IosGreen)
                }
                node?.firstHeard?.takeIf { it > 0 }?.let {
                    RowDivider()
                    DetailRow(
                        Icons.Outlined.Schedule, "First heard",
                        if (relativeDates) relativeLong(it) else absoluteTime(it),
                        iconTint = IosOrange,
                        onClick = { relativeDates = !relativeDates },
                    )
                }
                node?.lastHeard?.takeIf { it > 0 }?.let {
                    RowDivider()
                    DetailRow(
                        Icons.Outlined.History, "Last heard",
                        if (relativeDates) relativeLong(it) else absoluteTime(it),
                        onClick = { relativeDates = !relativeDates },
                    )
                }
            }

            // MARK: Environment (compact weather widgets)
            val env = envMetrics.lastOrNull()
            if (env != null && (env.temperature != null || env.relativeHumidity != null ||
                    env.barometricPressure != null || env.windSpeed != null)
            ) {
                SectionHeader("Environment")
                GroupCard {
                    EnvironmentGrid(env)
                }
            }

            // MARK: Logs
            SectionHeader("Logs")
            GroupCard {
                NavRow("Device Metrics Log", Icons.Outlined.Smartphone, enabled = metrics.isNotEmpty()) { log = DetailLog.DEVICE }
                RowDivider()
                NavRow("Node Map", Icons.Outlined.Map, enabled = position != null) { vm.openOnMap(nodeNum) }
                RowDivider()
                NavRow("Position Log", Icons.Outlined.Place, iconTint = IosRed, enabled = position != null) { log = DetailLog.POSITION }
                RowDivider()
                NavRow("Environment Metrics Log", Icons.Outlined.Cloud, enabled = envMetrics.isNotEmpty()) { log = DetailLog.ENVIRONMENT }
                RowDivider()
                NavRow("Air Quality Metrics Log", Icons.Outlined.Grain, enabled = false) {}
                RowDivider()
                NavRow("Trace Route Log", Icons.Outlined.Route, enabled = true) { log = DetailLog.TRACEROUTE }
                RowDivider()
                NavRow("Power Metrics Log", Icons.Outlined.Bolt, iconTint = Color(0xFFC9A227), enabled = false) {}
                RowDivider()
                NavRow("Detection Sensor Log", Icons.Outlined.Sensors, enabled = false) {}
                RowDivider()
                NavRow("Local Stats Log", Icons.Outlined.BarChart, enabled = false) {}
            }

            NodeActions(
                nodeNum = nodeNum, isSelf = isSelf, entry = entry, vm = vm,
                onMessage = onMessage,
                onToggleFavorite = onToggleFavorite,
                onToggleIgnore = onToggleIgnore,
                onAfterRemove = onBack,
            )
        }
    }
}

/** NodeInfoHardwareSection: the product image with the support seal, the model name under it. */
@Composable
private fun HardwareCard(imageUrl: String?, name: String, supported: Boolean?) {
    val context = LocalContext.current
    // The flasher serves SVG; Coil needs the SVG decoder registered for those.
    val loader = remember(context) {
        ImageLoader.Builder(context).components { add(SvgDecoder.Factory()) }.build()
    }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().height(260.dp)) {
            if (imageUrl != null) {
                AsyncImage(
                    model = imageUrl,
                    imageLoader = loader,
                    contentDescription = name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                )
            } else {
                Icon(
                    Icons.Outlined.Memory,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(96.dp).align(Alignment.Center),
                )
            }
            if (supported != null) {
                Icon(
                    if (supported) Icons.Filled.Verified else Icons.Filled.Cancel,
                    contentDescription = if (supported) "Actively supported" else "Not actively supported",
                    tint = if (supported) IosGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 24.dp).size(34.dp),
                )
            }
        }
        Text(
            name,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

// ---------------------------------------------------------------------------------------
// Header and rows

/** Inline centered title with the round back button iOS draws at the leading edge. */
@Composable
private fun DetailHeader(title: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.align(Alignment.CenterStart).size(44.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBackIosNew, contentDescription = "Back", modifier = Modifier.size(20.dp))
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 56.dp),
        )
    }
}

/** iOS `Label { } icon: { }` + trailing value row. */
@Composable
private fun DetailRow(
    icon: ImageVector,
    label: String,
    value: String?,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.width(12.dp))
        if (value != null) {
            // The value takes every remaining pixel and right-aligns inside it. Capping it
            // at a fraction of the row wrapped a 10-digit node number onto two lines.
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        trailing?.invoke()
    }
}

/** An Actions / Administration row: accent-colored label, no chevron (iOS Button in a List). */
@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    destructive: Boolean = false,
    iconTint: Color? = null,
    onClick: () -> Unit,
) {
    val color = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    ListItem(
        headlineContent = { Text(label, color = color) },
        leadingContent = {
            Icon(icon, contentDescription = null, tint = iconTint ?: color, modifier = Modifier.size(26.dp))
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    )
}

/**
 * TraceRouteButton.swift: the action is behind a shared 30 s cooldown, and while it runs
 * the row is disabled, counts the seconds down and draws a draining ring where the icon
 * goes (iOS uses the variable-value "progress.ring.dashed" symbol).
 */
@Composable
private fun RateLimitedActionRow(
    icon: ImageVector,
    label: String,
    key: String,
    limitSeconds: Double,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val limit = rememberRateLimit(key)
    if (!limit.running) {
        ActionRow(icon, label, enabled = enabled) {
            RateLimitStorage.actionOccurred(key, limitSeconds)
            onClick()
        }
        return
    }
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    ListItem(
        headlineContent = { Text("$label (in ${limit.secondsRemaining}s)", color = color) },
        leadingContent = { DashedProgressRing(limit.fractionRemaining, color) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/**
 * Stand-in for SF Symbols' variable-value "progress.ring.dashed": a ring of 20 dashes
 * where the leading fraction is drawn solid and the rest faded, so it drains as the
 * cooldown runs down.
 */
@Composable
private fun DashedProgressRing(fraction: Float, color: Color, size: Dp = 26.dp) {
    val dashes = 20
    val lit = (fraction * dashes).roundToInt()
    Canvas(Modifier.size(size)) {
        val stroke = 3.dp.toPx()
        val inset = stroke / 2
        val sweep = 360f / dashes
        val gap = sweep * 0.35f
        repeat(dashes) { index ->
            drawArc(
                color = if (index < lit) color else color.copy(alpha = 0.25f),
                startAngle = -90f + index * sweep + gap / 2,
                sweepAngle = sweep - gap,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(this.size.width - stroke, this.size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}

@Composable
private fun KeyMismatchRow(nodeNum: Long, publicKey: ByteArray?, newKey: ByteArray?, vm: NodeDetailViewModel) {
    var confirmAccept by remember { mutableStateOf(false) }
    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.Key, contentDescription = null, tint = IosRed, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text("Public Key Mismatch", style = MaterialTheme.typography.titleMedium, color = IosRed)
            Text(
                "Verify who you are messaging with by comparing public keys in person or over " +
                    "the phone. The most recent public key for this node does not match the " +
                    "previously recorded key." +
                    if (newKey != null) " Accept the new key (${keyFingerprint(newKey)}) only if you know the node was reset or re-flashed." else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (newKey != null) {
                TextButton(onClick = { confirmAccept = true }) { Text("Accept new key") }
            }
        }
    }
    RowDivider()
    if (confirmAccept && newKey != null) {
        AlertDialog(
            onDismissRequest = { confirmAccept = false },
            title = { Text("Accept new key?") },
            text = {
                Text(
                    "Trusted key ${publicKey?.let { keyFingerprint(it) } ?: "none"} will be " +
                        "replaced by ${keyFingerprint(newKey)}. The radio forgets this node " +
                        "and re-learns it from its next announcement. If you did not expect " +
                        "this node to change keys, cancel: it could be an impostor."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmAccept = false
                    vm.acceptNewKey(nodeNum)
                }) { Text("Accept new key") }
            },
            dismissButton = {
                TextButton(onClick = { confirmAccept = false }) { Text("Cancel") }
            },
        )
    }
}

// ---------------------------------------------------------------------------------------
// Gauges

/** LoRaSignalStrengthIndicator + the SNR / RSSI captions under it. */
@Composable
private fun SignalColumn(snr: Float, rssi: Int) {
    // Three-level approximation of getLoRaSignalStrength (which is preset-aware on iOS).
    val level = when {
        snr >= -7f && rssi >= -115 -> 2
        snr >= -15f && rssi >= -126 -> 1
        else -> 0
    }
    val color = when (level) { 2 -> IosGreen; 1 -> IosOrange; else -> IosRed }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (bar in 0..2) {
                Box(
                    Modifier
                        .width(8.dp)
                        .height((12 + bar * 8).dp)
                        .background(color.copy(alpha = if (bar <= level) 1f else 0.3f), RoundedCornerShape(3.dp)),
                )
            }
        }
        Text(
            "Signal " + when (level) { 2 -> "Good"; 1 -> "Fair"; else -> "Bad" },
            style = MaterialTheme.typography.labelMedium,
        )
        Text("SNR %.2fdB".format(snr), style = MaterialTheme.typography.labelSmall, color = color)
        Text("RSSI ${rssi}dB", style = MaterialTheme.typography.labelSmall, color = if (rssi >= -115) IosGreen else if (rssi >= -126) IosOrange else IosRed)
    }
}

/** BatteryGauge.swift: a 270 degree arc colored red to green, percent in the centre, voltage below. */
@Composable
private fun BatteryGauge(level: Int?, voltage: Float?) {
    val pct = (level ?: 0).coerceIn(0, 100)
    val plugged = (level ?: 0) > 100
    val track = MaterialTheme.colorScheme.surfaceVariant
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round)
                val inset = stroke.width / 2
                val arcSize = Size(size.width - stroke.width, size.height - stroke.width)
                val topLeft = Offset(inset, inset)
                drawArc(track, 135f, 270f, false, topLeft, arcSize, style = stroke)
                val sweep = 270f * (if (plugged) 1f else pct / 100f)
                // Red at empty through orange to green at full, drawn as short segments.
                val steps = (sweep / 4f).toInt().coerceAtLeast(1)
                for (i in 0 until steps) {
                    val start = 135f + i * (sweep / steps)
                    val f = (i + 0.5f) / steps * (sweep / 270f)
                    val c = if (f < 0.5f) lerp(IosRed, IosOrange, f * 2f) else lerp(IosOrange, IosGreen, (f - 0.5f) * 2f)
                    drawArc(c, start, sweep / steps + 1.5f, false, topLeft, arcSize, style = Stroke(width = stroke.width, cap = StrokeCap.Butt))
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (plugged) "PWR" else "$pct%",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Icon(Icons.Filled.Battery5Bar, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }
        voltage?.takeIf { it > 0f }?.let {
            Text("%.2f V".format(it), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Two-column grid of the iOS compact weather widgets. */
@Composable
private fun EnvironmentGrid(env: TelemetryEntity) {
    val tiles = buildList<@Composable () -> Unit> {
        env.temperature?.let { t ->
            add { EnvTile(Icons.Outlined.Thermostat, "TEMP", "%.0f°".format(t)) }
        }
        env.relativeHumidity?.let { h ->
            val dew = env.temperature?.let { t -> dewPoint(t, h) }
            add { EnvTile(Icons.Outlined.WaterDrop, "HUMIDITY", "%.0f%%".format(h), dew?.let { "The dew point is %.0f° right now.".format(it) }) }
        }
        env.barometricPressure?.let { p ->
            add { EnvTile(Icons.Outlined.Speed, "PRESSURE", "%.2f".format(p), (if (p <= 1009.144f) "LOW" else "HIGH") + "\nhPa") }
        }
        env.windSpeed?.let { w ->
            val gust = env.windGust?.takeIf { it > 0f }?.let { "Gusts %.0f m/s".format(it) }
            add { EnvTile(Icons.Outlined.Air, "WIND", "%.0f m/s".format(w), listOfNotNull(env.windDirection?.let { cardinal(it) }, gust).joinToString("\n")) }
        }
    }
    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { tile -> Box(Modifier.weight(1f)) { tile() } }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun EnvTile(icon: ImageVector, label: String, value: String, sub: String? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(value, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
        sub?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

private fun dewPoint(tempC: Float, rh: Float): Float {
    val a = 17.62f; val b = 243.12f
    val gamma = (a * tempC / (b + tempC)) + kotlin.math.ln((rh / 100f).coerceAtLeast(0.01f))
    return b * gamma / (a - gamma)
}

private fun cardinal(deg: Int): String {
    val names = listOf("North", "Northeast", "East", "Southeast", "South", "Southwest", "West", "Northwest")
    return names[((deg % 360 + 360) % 360 + 22) / 45 % 8]
}

// ---------------------------------------------------------------------------------------
// Log pages

@Composable
private fun LogPage(
    log: DetailLog,
    nodeNum: Long,
    metrics: List<TelemetryEntity>,
    envMetrics: List<TelemetryEntity>,
    traceroutes: List<TracerouteEntity>,
    vm: NodeDetailViewModel,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        DetailHeader(log.title, onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (log) {
                DetailLog.DEVICE -> {
                    ChartCard("Battery (48h)", metrics.mapNotNull { m -> m.batteryLevel?.let { m.time to it.coerceAtMost(100).toFloat() } }, "%")
                    ChartCard("Channel utilization (48h)", metrics.mapNotNull { m -> m.channelUtilization?.let { m.time to it } }, "%")
                    ChartCard("Air util TX (48h)", metrics.mapNotNull { m -> m.airUtilTx?.let { m.time to it } }, "%")
                    SectionHeader("Readings")
                    GroupCard {
                        metrics.asReversed().take(30).forEachIndexed { i, m ->
                            if (i > 0) RowDivider()
                            LogLine(
                                absoluteTime(m.time),
                                listOfNotNull(
                                    m.batteryLevel?.let { "$it%" },
                                    m.voltage?.let { "%.2f V".format(it) },
                                    m.channelUtilization?.let { "ch %.1f%%".format(it) },
                                    m.airUtilTx?.let { "air %.1f%%".format(it) },
                                ).joinToString("  •  "),
                            )
                        }
                    }
                }
                DetailLog.ENVIRONMENT -> {
                    ChartCard("Temperature (48h)", envMetrics.mapNotNull { m -> m.temperature?.let { m.time to it } }, "°C")
                    ChartCard("Humidity (48h)", envMetrics.mapNotNull { m -> m.relativeHumidity?.let { m.time to it } }, "%")
                    ChartCard("Pressure (48h)", envMetrics.mapNotNull { m -> m.barometricPressure?.let { m.time to it } }, " hPa")
                    SectionHeader("Readings")
                    GroupCard {
                        envMetrics.asReversed().take(30).forEachIndexed { i, m ->
                            if (i > 0) RowDivider()
                            LogLine(
                                absoluteTime(m.time),
                                listOfNotNull(
                                    m.temperature?.let { "%.1f°C".format(it) },
                                    m.relativeHumidity?.let { "%.0f%%".format(it) },
                                    m.barometricPressure?.let { "%.1f hPa".format(it) },
                                    m.windSpeed?.let { "wind %.1f m/s".format(it) },
                                ).joinToString("  •  "),
                            )
                        }
                    }
                }
                DetailLog.POSITION -> {
                    val history by vm.positionHistory(nodeNum).collectAsState(initial = emptyList())
                    GroupCard {
                        if (history.isEmpty()) {
                            Text("No positions recorded", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        history.forEachIndexed { i, p ->
                            if (i > 0) RowDivider()
                            PositionLine(p)
                        }
                    }
                }
                DetailLog.TRACEROUTE -> {
                    // TraceRouteLog.swift is a log only: the send lives on the node detail's
                    // Actions row, so there is deliberately no Run button here.
                    if (traceroutes.isEmpty()) {
                        Text("No traceroutes yet", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    traceroutes.forEach { route -> TracerouteCard(route, vm) }
                }
            }
        }
    }
}

@Composable
private fun LogLine(time: String, detail: String) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(time, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(detail, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PositionLine(p: PositionEntity) {
    LogLine(
        absoluteTime(p.time),
        buildList {
            add("%.5f, %.5f".format(p.latitude, p.longitude))
            if (p.altitude != 0) add("${p.altitude} m")
            if (p.satsInView > 0) add("${p.satsInView} sats")
            if (p.speed > 0) add("${p.speed} km/h")
            if (p.heading in 1..359) add("${p.heading}°")
        }.joinToString("  •  "),
    )
}

@Composable
private fun ChartCard(title: String, points: List<Pair<Long, Float>>, unit: String) {
    SectionHeader(title)
    GroupCard {
        MetricChart(
            points = points,
            unit = unit,
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .padding(12.dp),
        )
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
    GroupCard(Modifier.let { if (route.response) it.clickable { vm.openRoute(route) } else it }) {
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

// ---------------------------------------------------------------------------------------
// Actions and Administration

private data class ConfirmableAction(
    val message: String,
    val confirmLabel: String = "Yes",
    val destructive: Boolean = false,
    val run: () -> Unit,
)

/**
 * NodeDetail.actionsSection + administrationSection: accent-colored button rows with
 * glyphs. Destructive / remote calls (shutdown, reboot, remove) sit behind the canonical
 * "Are you sure?" dialog; the read-only requests are idempotent and go direct.
 */
@Composable
private fun NodeActions(
    nodeNum: Long,
    isSelf: Boolean,
    entry: NodeWithUser?,
    vm: NodeDetailViewModel,
    onMessage: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleIgnore: () -> Unit,
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
    val favorite = entry?.node?.favorite == true
    val ignored = entry?.node?.ignored == true

    SectionHeader("Actions")
    GroupCard {
        if (!connected) {
            Text(
                "Radio disconnected: mesh actions need a live link.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
            )
        }
        ActionRow(
            if (muted) Icons.Outlined.NotificationsOff else Icons.Outlined.NotificationsNone,
            if (muted) "Unmute notifications" else "Mute notifications",
        ) { vm.setMute(nodeNum, !muted) }
        if (entry?.user?.unmessagable == false) {
            RowDivider()
            ActionRow(Icons.Outlined.QrCode2, "Share Contact QR") { shareQr.value = entry }
        }
        RowDivider()
        ActionRow(
            if (favorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
            if (favorite) "Remove from favorites" else "Add to favorites",
            iconTint = Color(0xFFB8860B),
            onClick = onToggleFavorite,
        )
        if (!isSelf) {
            if (entry?.user?.unmessagable != true) {
                RowDivider()
                ActionRow(Icons.AutoMirrored.Outlined.Message, "Message", onClick = onMessage)
            }
            RowDivider()
            ActionRow(Icons.Outlined.SwapHoriz, "Exchange Positions", enabled = connected) { vm.sendPosition(target = nodeNum) }
            RowDivider()
            ActionRow(Icons.Outlined.BarChart, "Request Local Stats", enabled = connected) { vm.localStats(target = nodeNum, key = entry?.user?.publicKey) }
            RowDivider()
            ActionRow(Icons.Outlined.Badge, "Exchange User Info", enabled = connected) { vm.exchangeUser(target = nodeNum) }
            RowDivider()
            RateLimitedActionRow(
                Icons.Outlined.Route,
                "Trace Route",
                key = TRACEROUTE_RATE_LIMIT_KEY,
                limitSeconds = TRACEROUTE_RATE_LIMIT_SECONDS,
                enabled = connected,
            ) { vm.runTraceroute(nodeNum) }
            RowDivider()
            ActionRow(Icons.Outlined.History, "Client History", enabled = connected) { vm.sfHistory(target = nodeNum) }
            RowDivider()
            ActionRow(Icons.Outlined.CellTower, "Store & Forward Config", enabled = connected) { vm.sfConfig(target = nodeNum) }
            RowDivider()
            ActionRow(
                Icons.Outlined.RemoveCircleOutline,
                if (ignored) "Stop ignoring" else "Ignore Node",
                onClick = onToggleIgnore,
            )
            RowDivider()
            ActionRow(Icons.Outlined.DeleteForever, "Delete Node", enabled = connected, destructive = true) {
                confirmPending.value = ConfirmableAction(
                    message = "Remove ${nodeNumString(nodeNum)} from ${nodeNumString(myNum)}'s node database?",
                    confirmLabel = "Delete Node",
                    destructive = true,
                ) {
                    vm.remove(target = myNum, removed = nodeNum); onAfterRemove()
                }
            }
        }
    }

    SectionHeader("Administration")
    GroupCard {
        ActionRow(Icons.Outlined.Refresh, "Refresh device metadata", enabled = connected) { vm.deviceMetadata(target = nodeNum) }
        RowDivider()
        ActionRow(Icons.Outlined.PowerSettingsNew, "Power Off", enabled = connected) {
            confirmPending.value = ConfirmableAction(
                message = if (isSelf) "Shut down this radio? It will power off after 5 s."
                else "Shutdown ${nodeNumString(nodeNum)}? It will power off after 5 s.",
                confirmLabel = "Shutdown Node?",
                destructive = true,
            ) { vm.shutdown(target = myNum) }
        }
        RowDivider()
        ActionRow(Icons.Outlined.Sync, "Reboot", enabled = connected) {
            confirmPending.value = ConfirmableAction(
                message = if (isSelf) "Reboot this radio? It will reboot after 5 s."
                else "Reboot ${nodeNumString(nodeNum)}? It will reboot after 5 s.",
                confirmLabel = "Reboot node?",
                destructive = true,
            ) { vm.reboot(target = myNum) }
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

// ---------------------------------------------------------------------------------------
// Helpers

/** Short, human-comparable form of a 32-byte key: first 8 base64 characters. */
private fun keyFingerprint(key: ByteArray): String =
    Base64.encodeToString(key, Base64.NO_WRAP).take(8) + "…"

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

/** iOS `.components(style: .narrow)`: "1w 2d 23h 26m 54s". */
fun uptimeFull(seconds: Int): String {
    val w = seconds / 604800
    val d = (seconds % 604800) / 86400
    val h = (seconds % 86400) / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return listOfNotNull(
        if (w > 0) "${w}w" else null,
        if (d > 0 || w > 0) "${d}d" else null,
        if (h > 0 || d > 0 || w > 0) "${h}h" else null,
        "${m}m",
        "${s}s",
    ).joinToString(" ")
}

/** RelativeDateTimeFormatter(.full) style: "19 seconds ago", "2 months ago". */
fun relativeLong(epochMs: Long): String {
    val sec = ((System.currentTimeMillis() - epochMs) / 1000).coerceAtLeast(0)
    fun plural(n: Long, unit: String) = "$n $unit${if (n == 1L) "" else "s"} ago"
    return when {
        sec < 60 -> plural(sec, "second")
        sec < 3600 -> plural(sec / 60, "minute")
        sec < 86400 -> plural(sec / 3600, "hour")
        sec < 7 * 86400 -> plural(sec / 86400, "day")
        sec < 30 * 86400 -> plural(sec / (7 * 86400), "week")
        sec < 365 * 86400 -> plural(sec / (30 * 86400), "month")
        else -> plural(sec / (365 * 86400), "year")
    }
}

fun absoluteTime(epochMs: Long): String =
    java.text.SimpleDateFormat("M/d/yyyy, h:mm:ss a", java.util.Locale.getDefault()).format(epochMs)

/** Small tap-to-copy affordance for identity rows (node number, public key). */
@Composable
fun CopyButton(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    Text(
        "Copy",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clickable {
                clipboard.setPrimaryClip(ClipData.newPlainText("meshtastic", text))
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
            }
            .padding(horizontal = 8.dp),
    )
}
