package com.suteny0r.mangledbabyducks.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.core.animate
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.PortableWifiOff
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.outlined.QrCode2
import com.suteny0r.mangledbabyducks.db.NodeWithUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.suteny0r.mangledbabyducks.RememberedRadio
import com.suteny0r.mangledbabyducks.radio.RadioState
import com.suteny0r.mangledbabyducks.ui.theme.IosGray
import com.suteny0r.mangledbabyducks.ui.theme.IosGreen
import com.suteny0r.mangledbabyducks.ui.theme.IosOrange
import com.suteny0r.mangledbabyducks.ui.theme.IosRed
import com.suteny0r.mangledbabyducks.ui.theme.IosTeal
import com.suteny0r.mangledbabyducks.ui.theme.IosYellow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Connect.swift. One box at the top for the radio (connected, connecting, or "No device
 * connected"); the radio lists underneath exist only while nothing is connected or
 * connecting, exactly as the iOS tab hides them. Rows are DeviceConnectRow: star for the
 * preferred radio, name, transport glyph, signal bars; tap to connect, long-press to
 * forget (the iOS swipe-to-delete / context-menu Delete).
 */
@Composable
fun ConnectScreen(vm: ConnectViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val devices by vm.devices.collectAsState()
    val deviceName by vm.deviceName.collectAsState()
    val remembered by vm.remembered.collectAsState()
    val knownRadios by vm.knownRadios.collectAsState()
    val myUser by vm.myUser.collectAsState()
    val myInfo by vm.myInfo.collectAsState()
    val myNodeNum by vm.myNodeNum.collectAsState()
    val myBattery by vm.myBattery.collectAsState()
    val linkRssi by vm.linkRssi.collectAsState()
    val lanDevices by vm.lanDevices.collectAsState()
    val bluetoothOff by vm.bluetoothOff.collectAsState()
    val identityReady by vm.identityReady.collectAsState()
    val loraPreset by vm.loraPreset.collectAsState()
    val myEntry by vm.myEntry.collectAsState()

    // isConnected || isConnecting on iOS: the device box owns the screen and the lists go.
    val idle = state is RadioState.Idle || state is RadioState.Failed
    val target = deviceName ?: remembered?.label
    // iOS keys the device box on `activeConnection?.device`, not on the handshake being
    // finished: while connecting it is the same box with a "?" avatar and only the rows
    // whose data has arrived. The orange antenna box is its else-branch, for a connect
    // with no device at all.
    val hasDevice = !idle && target != null
    val tcp = remembered?.type == "tcp"

    // iOS scans (BLE + Bonjour) for the whole time the tab is up and no radio is linked.
    DisposableEffect(Unit) {
        vm.startLanScan()
        onDispose {
            vm.stopLanScan()
            vm.stopScan()
        }
    }
    LaunchedEffect(idle, bluetoothOff) {
        if (idle && !bluetoothOff) vm.startScan() else vm.stopScan()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { AppHeader("Connect") }

        item {
            val cardPadding = Modifier.padding(horizontal = 16.dp)
            if (idle) {
                GroupCard(cardPadding) { NoDeviceBox((state as? RadioState.Failed)?.reason) }
            } else {
                SwipeToDisconnect(onDisconnect = vm::disconnect) {
                    if (hasDevice) {
                        // my_info and our own user row still describe the PREVIOUS radio
                        // until this radio's handshake rewrites them. Showing them while a
                        // different radio is being reached for named the old radio in the
                        // box while the pairing prompt named the new one. Until the
                        // handshake is under way the box shows only the target's name,
                        // which is iOS's "the rows whose data has arrived".
                        // Keyed on this session's MyNodeInfo having landed, not on the
                        // connection state: myNodeNum, my_info and our user row all still
                        // hold the previous radio's identity through Communicating and
                        // RetrievingDatabase, which is how a finished connect could sit
                        // there wearing the old radio's name until a reconnect.
                        val live = identityReady
                        ConnectedDeviceBox(
                            state = state,
                            longName = if (live) myUser?.longName else null,
                            shortName = if (live) myUser?.shortName else null,
                            nodeNum = if (live) myNodeNum else 0L,
                            connectionName = if (live) myInfo?.bleName ?: deviceName ?: "?"
                                else deviceName ?: target ?: "?",
                            tcp = tcp,
                            rssi = linkRssi,
                            battery = if (live) myBattery else null,
                            firmware = if (live) myInfo?.firmwareVersion else null,
                            preset = if (live) loraPreset else null,
                            shareEntry = if (live) myEntry else null,
                            onDisconnect = vm::disconnect,
                            onShutdown = vm::shutdownConnectedRadio,
                        )
                    } else {
                        ConnectingBox(state, target)
                    }
                }
            }
        }

        if (!idle) return@LazyColumn

        // Available Radios: everything BLE (saved or scanned) plus radios found on this
        // network, preferred radio first then by name (sortedAvailableDevices).
        val preferred = remembered?.address
        val savedAddresses = knownRadios.map { it.address }.toSet()
        val available = buildList {
            knownRadios.filter { it.type == "ble" }.forEach { radio ->
                // iOS lists live scan results, so a row always wears the name the radio
                // advertises now. A saved row shows the saved name only while the radio
                // is out of range.
                val live = devices[radio.address]?.name?.takeIf { it.isNotBlank() }
                add(
                    AvailableRadio(
                        key = radio.address,
                        name = live ?: radio.label,
                        tcp = false,
                        rssi = devices[radio.address]?.rssi,
                        saved = radio,
                        connect = { vm.connectKnown(radio) },
                    )
                )
            }
            devices.values.filterNot { it.id in savedAddresses }.forEach { device ->
                add(
                    AvailableRadio(
                        key = device.id,
                        name = device.name,
                        tcp = false,
                        rssi = device.rssi,
                        saved = null,
                        connect = { vm.connectBle(device) },
                    )
                )
            }
            lanDevices.values.filterNot { it.id in savedAddresses }.forEach { device ->
                add(
                    AvailableRadio(
                        key = device.id,
                        name = device.name,
                        tcp = true,
                        rssi = null,
                        saved = null,
                        connect = { vm.connectLan(device) },
                    )
                )
            }
        }.sortedWith(
            compareByDescending<AvailableRadio> { it.key == preferred }
                .thenBy { it.name.lowercase() }
        )

        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionTitle("Available Radios")
                Spacer(Modifier.weight(1f))
                ManualConnectionMenu(onConnect = vm::connectManual)
            }
        }
        item {
            GroupCard(Modifier.padding(horizontal = 16.dp)) {
                if (bluetoothOff) BluetoothPoweredOffRow()
                if (available.isEmpty() && !bluetoothOff) {
                    Text(
                        "Looking for radios…",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                // Keyed on the radio, not on the slot. Scan results arrive while the list
                // is on screen, and an insertion or removal shifts every row below it into
                // the next slot: an unkeyed row kept the slot and silently adopted its
                // neighbour's data, so a press that had already landed fired the
                // neighbour's connect. That is the "it connected to the one below" bug.
                available.forEachIndexed { index, radio ->
                    key(radio.key) {
                        if (index > 0 || bluetoothOff) RowDivider()
                        DeviceConnectRow(
                            name = radio.name,
                            tcp = radio.tcp,
                            preferred = radio.key == preferred,
                            rssi = radio.rssi,
                            lastSeen = null,
                            // The row hands over an identity, never a captured object, and
                            // the ViewModel resolves it against live state when it fires.
                            onConnect = { vm.connectByKey(radio.key) },
                            onForget = radio.saved?.let { saved -> { vm.forget(saved) } },
                        )
                    }
                }
            }
        }

        // Manual Connections: the TCP radios added by address (ManualConnectionList).
        val manual = knownRadios.filter { it.type == "tcp" }
        if (manual.isNotEmpty()) {
            item { SectionTitle("Manual Connections", Modifier.padding(horizontal = 16.dp)) }
            item {
                GroupCard(Modifier.padding(horizontal = 16.dp)) {
                    manual.forEachIndexed { index, radio ->
                        key(radio.address) {
                            if (index > 0) RowDivider()
                            val seen = lanDevices[radio.address]
                            DeviceConnectRow(
                                name = radio.address,
                                tcp = true,
                                preferred = radio.address == preferred,
                                rssi = null,
                                lastSeen = (seen?.name ?: radio.name)?.takeIf { it != radio.address.substringBefore(':') },
                                onConnect = { vm.connectByKey(radio.address) },
                                onForget = { vm.forget(radio) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private class AvailableRadio(
    val key: String,
    val name: String,
    val tcp: Boolean,
    val rssi: Int?,
    val saved: RememberedRadio?,
    val connect: () -> Unit,
)

/** The iOS `.font(.title)` section header: large, not the small gray caption. */
@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.headlineSmall,
        modifier = modifier.padding(top = 4.dp),
    )
}

/** Text("Label").font(.callout) + Text(": value"), gray like the rest of the box. */
@Composable
private fun CalloutLine(text: String, color: Color = IosGray) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = color)
}

/** TransportIcon: the transport glyph (accent for BLE) and its name in title3. */
@Composable
private fun TransportIcon(tcp: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Icon(
            if (tcp) Icons.Default.Wifi else Icons.Default.Bluetooth,
            contentDescription = null,
            tint = if (tcp) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Text(
            if (tcp) "TCP" else "BLE",
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
            color = IosGray,
        )
    }
}

// ---------------------------------------------------------------------------------------
// The connected-device box

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConnectedDeviceBox(
    state: RadioState,
    longName: String?,
    shortName: String?,
    nodeNum: Long,
    connectionName: String,
    tcp: Boolean,
    rssi: Int?,
    battery: Int?,
    firmware: String?,
    preset: String?,
    shareEntry: NodeWithUser?,
    onDisconnect: () -> Unit,
    onShutdown: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var confirmShutdown by remember { mutableStateOf(false) }
    var shareQr by remember { mutableStateOf(false) }

    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = {}, onLongClick = { menu = true })
                .padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                NodeAvatar(shortName = shortName, num = nodeNum, size = 90.dp)
                BatteryCompact(battery, Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (longName != null) {
                    Text(
                        longName,
                        style = MaterialTheme.typography.titleLarge,
                        color = IosGray,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                CalloutLine("Connection Name: $connectionName")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TransportIcon(tcp)
                    if (!tcp && rssi != null) BleSignalBars(rssi, width = 5.dp, height = 20.dp)
                }
                if (firmware != null) CalloutLine("Firmware Version: $firmware")
                if (preset != null) CalloutLine("Preset: $preset")
                when (state) {
                    is RadioState.Subscribed -> CalloutLine("Subscribed", IosGreen)
                    is RadioState.RetrievingDatabase ->
                        ActivityLine("Retrieving nodes ${state.nodeCount}", IosTeal)
                    is RadioState.Communicating -> ActivityLine("Communicating", IosOrange)
                    is RadioState.Reconnecting -> ActivityLine("Retrying (attempt ${state.attempt})", IosOrange)
                    is RadioState.Searching -> ActivityLine(attemptLabel(state.attempt, state.of), IosOrange)
                    is RadioState.Connecting -> ActivityLine(attemptLabel(state.attempt, state.of), IosOrange)
                    else -> {}
                }
            }
        }
        // Connect.swift .contextMenu: node number, Share Contact, Disconnect, Power Off.
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(nodeNum.toString()) },
                leadingIcon = { Icon(Icons.Default.Tag, contentDescription = null) },
                enabled = false,
                onClick = {},
            )
            if (canShareContact(shareEntry)) {
                DropdownMenuItem(
                    text = { Text("Share Contact") },
                    leadingIcon = { Icon(Icons.Outlined.QrCode2, contentDescription = null) },
                    // Eligibility is re-checked at tap time: the menu condition ran when the
                    // menu was built, and a node that loses its key in between would produce
                    // a QR of an empty string.
                    onClick = { menu = false; if (canShareContact(shareEntry)) shareQr = true },
                )
            }
            DropdownMenuItem(
                text = { Text("Disconnect", color = IosRed) },
                leadingIcon = { Icon(Icons.Default.LinkOff, contentDescription = null, tint = IosRed) },
                onClick = { menu = false; onDisconnect() },
            )
            DropdownMenuItem(
                text = { Text("Power Off", color = IosRed) },
                leadingIcon = { Icon(Icons.Default.PowerSettingsNew, contentDescription = null, tint = IosRed) },
                onClick = { menu = false; confirmShutdown = true },
            )
        }
    }

    if (confirmShutdown) {
        AlertDialog(
            onDismissRequest = { confirmShutdown = false },
            title = { Text("Are you sure?") },
            confirmButton = {
                TextButton(onClick = { confirmShutdown = false; onShutdown() }) {
                    Text("Shutdown Node?", color = IosRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmShutdown = false }) { Text("Cancel") }
            },
        )
    }
    // This menu only ever shows on the connected radio, which is verified by definition.
    if (shareQr) {
        shareEntry?.let { ShareContactQRDialog(entry = it, manuallyVerified = true, onDismiss = { shareQr = false }) }
    }
}

/**
 * Connect.swift `.swipeActions { Disconnect }` on the device box, drawn the way iOS draws
 * a swipe action: the card stays inside the page margins and **narrows** from its trailing
 * edge while its content slides left and is clipped, uncovering the page background, where
 * the action sits as a red rounded pill with the "Disconnect" caption under it. The card
 * tints gray while the action shows. The action parks open: releasing the drag must not
 * disconnect (user rule), only a tap on the pill does; a tap on the card or a drag back
 * closes it.
 */
@Composable
private fun SwipeToDisconnect(onDisconnect: () -> Unit, content: @Composable () -> Unit) {
    val actionWidth = 110.dp
    val density = LocalDensity.current
    val actionWidthPx = with(density) { actionWidth.toPx() }
    var offsetX by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    fun settle(open: Boolean) {
        scope.launch {
            animate(offsetX, if (open) -actionWidthPx else 0f) { value, _ -> offsetX = value }
        }
    }
    val open = offsetX < 0f

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // The content keeps the width it had before the swipe, so sliding the card open
        // translates it instead of re-wrapping its text.
        val contentWidth = maxWidth - 32.dp
        val revealed = with(density) { (-offsetX).toDp() }

        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .width(actionWidth),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier
                    .size(64.dp)
                    .background(IosRed, RoundedCornerShape(22.dp))
                    .clickable(enabled = open) { settle(false); onDisconnect() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.PortableWifiOff,
                    contentDescription = "Disconnect",
                    tint = Color.White,
                    modifier = Modifier.size(30.dp),
                )
            }
            Text(
                "Disconnect",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        GroupCard(
            Modifier
                .padding(start = 16.dp, end = 16.dp + revealed)
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        offsetX = (offsetX + delta).coerceIn(-actionWidthPx, 0f)
                    },
                    onDragStopped = { velocity ->
                        settle(
                            when {
                                velocity < -500f -> true
                                velocity > 500f -> false
                                else -> offsetX < -actionWidthPx / 2
                            }
                        )
                    },
                ),
            // iOS tints the row while its swipe action is open.
            containerColor = if (open) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.surface,
        ) {
            Box(
                Modifier
                    // requiredWidth, not width: width() is clamped by the narrowing card's
                    // constraints, which re-wraps the text instead of sliding it.
                    .requiredWidth(contentWidth)
                    .offset { IntOffset(offsetX.roundToInt(), 0) },
            ) {
                content()
                if (open) {
                    // A tap anywhere on the shifted card closes the action instead of
                    // reaching the card's own long-press handler.
                    Box(
                        Modifier
                            .matchParentSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { settle(false) },
                    )
                }
            }
        }
    }
}

/** "Connecting . ." on its own, or with the attempt count when there is more than one. */
private fun attemptLabel(attempt: Int, of: Int): String =
    if (of > 1) "Connecting . .  (attempt $attempt of $of)" else "Connecting . ."

/** The stacked-squares glyph plus a colored callout, for the in-progress states. */
@Composable
private fun ActivityLine(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Default.Layers, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        CalloutLine(text, color)
    }
}

/** isConnecting with no device yet: the orange antenna and "Connecting . .". */
@Composable
private fun ConnectingBox(state: RadioState, target: String?) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Default.SettingsInputAntenna,
            contentDescription = null,
            tint = IosOrange,
            modifier = Modifier.size(60.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Connecting . .", style = MaterialTheme.typography.titleLarge, color = IosOrange)
            // iOS names nothing here; every in-progress state in this app names its target.
            if (target != null) CalloutLine(target, IosOrange)
            val (attempt, of) = when (state) {
                is RadioState.Searching -> state.attempt to state.of
                is RadioState.Connecting -> state.attempt to state.of
                else -> 1 to 1
            }
            if (of > 1) CalloutLine("Connection Attempt $attempt of $of", IosOrange)
        }
    }
}

/** Nothing connected: the last error in red, then the broken link and the caption. */
@Composable
private fun NoDeviceBox(error: String?) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (error != null) CalloutLine(error, IosRed)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.LinkOff,
                contentDescription = null,
                tint = IosRed,
                modifier = Modifier.size(60.dp),
            )
            Spacer(Modifier.width(16.dp))
            Text("No device connected", style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp))
        }
    }
}

// ---------------------------------------------------------------------------------------
// Radio rows

/**
 * DeviceConnectRow: yellow star for the preferred radio, gray dot otherwise; the name;
 * the transport glyph (and "Last seen device" for a manual connection); signal bars.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeviceConnectRow(
    name: String,
    tcp: Boolean,
    preferred: Boolean,
    rssi: Int?,
    lastSeen: String?,
    onConnect: () -> Unit,
    onForget: (() -> Unit)?,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onConnect, onLongClick = { if (onForget != null) menu = true })
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (preferred) Icons.Default.Star else Icons.Default.Circle,
                contentDescription = if (preferred) "Preferred radio" else null,
                tint = if (preferred) IosYellow else IosGray,
                modifier = Modifier.size(if (preferred) 28.dp else 22.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 3.dp),
                ) {
                    TransportIcon(tcp)
                    if (lastSeen != null) {
                        Column {
                            Text("Last seen device:", style = MaterialTheme.typography.bodySmall, color = IosGray)
                            Text(lastSeen, style = MaterialTheme.typography.bodySmall, color = IosGray)
                        }
                    }
                }
            }
            if (rssi != null) BleSignalBars(rssi)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Forget", color = IosRed) },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = IosRed) },
                onClick = { menu = false; onForget?.invoke() },
            )
        }
    }
}

/** BluetoothPoweredOffRow: the only in-app hint for why the list is empty; opens Settings. */
@Composable
private fun BluetoothPoweredOffRow() {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Default.Warning, contentDescription = null, tint = IosOrange)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Bluetooth is off", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "Turn on Bluetooth in Settings to see nearby radios.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
    }
}

/**
 * ManualConnectionMenu: "+ Manual" opens a menu of transports that take a typed address
 * (TCP here), then an alert with a hostname[:port] field.
 */
@Composable
private fun ManualConnectionMenu(onConnect: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var asking by rememberSaveable { mutableStateOf(false) }
    var connectionString by rememberSaveable { mutableStateOf("") }
    val allowed = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.-:"

    Box {
        TextButton(onClick = { menu = true }) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Manual")
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("TCP") },
                leadingIcon = { Icon(Icons.Default.Wifi, contentDescription = null) },
                onClick = { menu = false; asking = true },
            )
        }
    }

    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("Manual connection string") },
            text = {
                OutlinedTextField(
                    value = connectionString,
                    onValueChange = { v -> connectionString = v.filter { it in allowed } },
                    placeholder = { Text("Enter hostname[:port]") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = connectionString.isNotBlank(),
                    onClick = { asking = false; onConnect(connectionString) },
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { asking = false }) { Text("Cancel") }
            },
        )
    }
}
