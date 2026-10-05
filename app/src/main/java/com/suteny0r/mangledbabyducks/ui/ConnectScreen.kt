package com.suteny0r.mangledbabyducks.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.suteny0r.mangledbabyducks.radio.MeshProtocol
import com.suteny0r.mangledbabyducks.radio.RadioState
import com.suteny0r.mangledbabyducks.ui.theme.IosGreen

@Composable
fun ConnectScreen(vm: ConnectViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val devices by vm.devices.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val deviceName by vm.deviceName.collectAsState()
    val remembered by vm.remembered.collectAsState()
    val knownRadios by vm.knownRadios.collectAsState()
    val myUser by vm.myUser.collectAsState()
    val myInfo by vm.myInfo.collectAsState()
    val myNodeNum by vm.myNodeNum.collectAsState()
    val myBattery by vm.myBattery.collectAsState()
    val lanDevices by vm.lanDevices.collectAsState()
    val lanScanning by vm.lanScanning.collectAsState()

    // Browse the LAN for the life of this screen, like the iOS Connect tab.
    DisposableEffect(Unit) {
        vm.startLanScan()
        onDispose { vm.stopLanScan() }
    }

    // Every in-progress state names its target: "Connecting…" with no device told the
    // user nothing about which radio was being reached.
    val target = deviceName ?: remembered?.label
    val live = state is RadioState.Subscribed

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // iOS navigationTitle("Connect") with the logo and ConnectedDevice toolbar items.
        item { AppHeader("Connect") }

        // Connect.swift: the connected-device box. Avatar + battery on the left, name,
        // connection name, transport, firmware and the state line on the right.
        item {
            GroupCard(Modifier.padding(horizontal = 16.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        NodeAvatar(
                            shortName = if (live) myUser?.shortName else null,
                            num = if (live) myNodeNum else 0L,
                            size = 90.dp,
                        )
                        if (live) {
                            BatteryCompact(myBattery, Modifier.padding(top = 6.dp))
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            when {
                                live -> myUser?.longName ?: "Unknown"
                                target != null -> target
                                else -> "No radio"
                            },
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (live) {
                            LabelValue("Connection Name", myInfo?.bleName ?: deviceName ?: "?")
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val tcp = remembered?.type == "tcp"
                                Icon(
                                    if (tcp) Icons.Default.Wifi else Icons.Default.Bluetooth,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    if (tcp) "TCP" else "BLE",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            LabelValue("Firmware Version", myInfo?.firmwareVersion ?: "Unknown")
                        }
                        when (val s = state) {
                            is RadioState.Idle -> StateLine("Not connected", MaterialTheme.colorScheme.onSurfaceVariant)
                            is RadioState.Searching -> StatusRow(
                                "Looking for ${target ?: "radio"}…" +
                                    if (s.of > 1) "  (try ${s.attempt} of ${s.of})" else ""
                            )
                            is RadioState.Connecting -> StatusRow(
                                "Connecting…" + if (s.of > 1) "  (try ${s.attempt} of ${s.of})" else ""
                            )
                            is RadioState.Communicating -> StatusRow("Retrieving configuration…")
                            is RadioState.RetrievingDatabase ->
                                StatusRow("Retrieving nodes (${s.nodeCount})…")
                            is RadioState.Subscribed -> StateLine("Subscribed", IosGreen)
                            is RadioState.Reconnecting ->
                                StatusRow("Connection lost, reconnecting (attempt ${s.attempt})…")
                            is RadioState.Failed -> StateLine(s.reason, MaterialTheme.colorScheme.error)
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 6.dp),
                        ) {
                            if (live) {
                                OutlinedButton(onClick = { vm.disconnect() }) { Text("Disconnect") }
                            }
                            // Auto-connect is spent once per launch, so the radio it gave
                            // up on needs an explicit way back.
                            if (state is RadioState.Failed || state is RadioState.Idle) {
                                remembered?.let { radio ->
                                    Button(onClick = { vm.connectKnown(radio) }) {
                                        Text("Connect ${radio.label}")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Saved radios first: connecting to one of these needs no scan at all.
        if (knownRadios.isNotEmpty()) {
            item { SectionHeader("Saved Radios", Modifier.padding(horizontal = 16.dp)) }
            item {
                GroupCard(Modifier.padding(horizontal = 16.dp)) {
                    knownRadios.forEachIndexed { index, radio ->
                        if (index > 0) RowDivider()
                        val seen = devices[radio.address]
                        val isLive = live && deviceName == radio.label
                        NavRow(
                            title = radio.label,
                            icon = if (radio.type == "tcp") Icons.Default.Wifi else Icons.Default.Bluetooth,
                            subtitle = buildList {
                                add(radio.address)
                                if (seen != null) add("in range  ${seen.rssi} dBm")
                                if (radio.type == "tcp" && radio.address in lanDevices) add("on this network")
                                if (radio.lastConnectedMs > 0) add("last used ${relativeTime(radio.lastConnectedMs)}")
                            }.joinToString("  •  "),
                            chevron = false,
                            trailing = {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (isLive) {
                                        Text("Connected", style = MaterialTheme.typography.labelMedium, color = IosGreen)
                                    } else {
                                        TextButton(onClick = { vm.connectKnown(radio) }) { Text("Connect") }
                                    }
                                    TextButton(onClick = { vm.forget(radio) }) { Text("Forget") }
                                }
                            },
                        )
                    }
                }
            }
        }

        // Scan results, minus anything already saved above. Stable sort: RSSI updates
        // every advertisement and reordering rows while the user is aiming at a Connect
        // button causes mis-taps.
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionHeader("Available Radios", Modifier.padding(start = 0.dp))
                TextButton(onClick = { vm.toggleScan() }) {
                    Text(if (scanning) "Stop scan" else "Scan")
                }
            }
        }
        val savedAddresses = knownRadios.map { it.address }.toSet()
        val found = devices.values
            .filterNot { it.id in savedAddresses }
            .sortedBy { it.name + it.id }
        item {
            GroupCard(Modifier.padding(horizontal = 16.dp)) {
                if (found.isEmpty()) {
                    Text(
                        if (scanning) "Scanning…" else "Tap Scan to find radios over Bluetooth.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                found.forEachIndexed { index, device ->
                    if (index > 0) RowDivider()
                    NavRow(
                        title = device.name,
                        icon = Icons.Default.Bluetooth,
                        subtitle = "${device.id}  •  ${device.rssi} dBm",
                        chevron = false,
                        trailing = { TextButton(onClick = { vm.connectBle(device) }) { Text("Connect") } },
                    )
                }
            }
        }

        // Every radio found on this Wi-Fi (mDNS or the port sweep), saved ones included
        // so the scan result is visible; the saved card also notes "on this network".
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionHeader("Network", Modifier.padding(start = 0.dp))
                if (lanScanning) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                }
            }
        }
        val lanFound = lanDevices.values.sortedBy { it.name.lowercase() }
        item {
            GroupCard(Modifier.padding(horizontal = 16.dp)) {
                if (lanFound.isEmpty()) {
                    Text(
                        if (lanScanning) "Looking for radios on this network…"
                        else "No radios found on this network.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                lanFound.forEachIndexed { index, device ->
                    if (index > 0) RowDivider()
                    val isLive = live && remembered?.type == "tcp" && remembered?.address == device.id
                    NavRow(
                        title = device.name,
                        icon = Icons.Default.Wifi,
                        subtitle = if (device.id in savedAddresses) "${device.id}  •  saved" else device.id,
                        chevron = false,
                        trailing = {
                            if (isLive) {
                                Text("Connected", style = MaterialTheme.typography.labelMedium, color = IosGreen)
                            } else {
                                TextButton(onClick = { vm.connectLan(device) }) { Text("Connect") }
                            }
                        },
                    )
                }
                RowDivider()
                Box(Modifier.padding(12.dp)) { TcpConnectRow(onConnect = vm::connectTcp) }
            }
        }
    }
}

@Composable
private fun LabelValue(label: String, value: String) {
    Text(
        buildAnnotatedString {
            append("$label: ")
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append(value) }
        },
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun StateLine(text: String, color: Color) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
}

@Composable
private fun StatusRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TcpConnectRow(onConnect: (String, Int) -> Unit) {
    var host by rememberSaveable { mutableStateOf("") }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Add by address (host[:port])") },
            modifier = Modifier.weight(1f),
            singleLine = true,
        )
        Button(
            enabled = host.isNotBlank(),
            onClick = {
                val parts = host.split(":")
                val port = parts.getOrNull(1)?.toIntOrNull() ?: MeshProtocol.DEFAULT_TCP_PORT
                onConnect(parts[0], port)
            },
        ) { Text("Connect") }
    }
}
