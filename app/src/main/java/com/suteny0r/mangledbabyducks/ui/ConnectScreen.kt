package com.suteny0r.mangledbabyducks.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.suteny0r.mangledbabyducks.radio.MeshProtocol
import com.suteny0r.mangledbabyducks.radio.RadioState

@Composable
fun ConnectScreen(vm: ConnectViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val devices by vm.devices.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val deviceName by vm.deviceName.collectAsState()
    val remembered by vm.remembered.collectAsState()
    val knownRadios by vm.knownRadios.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Radio", style = MaterialTheme.typography.titleMedium)
                // Every in-progress state names its target: "Connecting…" with no
                // device told the user nothing about which radio was being reached.
                val target = deviceName ?: remembered?.label
                when (val s = state) {
                    is RadioState.Idle -> Text("Not connected")
                    is RadioState.Searching -> StatusRow(
                        "Looking for ${target ?: "radio"}…" +
                            if (s.of > 1) "  (try ${s.attempt} of ${s.of})" else ""
                    )
                    is RadioState.Connecting -> StatusRow(
                        "Connecting to ${target ?: "radio"}…" +
                            if (s.of > 1) "  (try ${s.attempt} of ${s.of})" else ""
                    )
                    is RadioState.Communicating -> StatusRow("Retrieving configuration…")
                    is RadioState.RetrievingDatabase ->
                        StatusRow("Loading node database (${s.nodeCount} nodes)…")
                    is RadioState.Subscribed ->
                        Text("Connected to ${deviceName ?: "radio"}")
                    is RadioState.Reconnecting -> StatusRow(
                        "Connection to ${target ?: "radio"} lost, reconnecting (attempt ${s.attempt})…"
                    )
                    is RadioState.Failed ->
                        Text(s.reason, color = MaterialTheme.colorScheme.error)
                }
                if (state is RadioState.Subscribed) {
                    OutlinedButton(onClick = { vm.disconnect() }) { Text("Disconnect") }
                }
                // Auto-connect is spent once per launch, so the radio it gave up on
                // needs an explicit way back.
                if (state is RadioState.Failed) {
                    remembered?.let { radio ->
                        Button(onClick = { vm.connectKnown(radio) }) {
                            Text("Retry ${radio.label}")
                        }
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Bluetooth", style = MaterialTheme.typography.titleMedium)
            Button(onClick = { vm.toggleScan() }) {
                Text(if (scanning) "Stop scan" else "Scan")
            }
        }

        LazyColumn(Modifier.weight(1f)) {
            // Saved radios first: connecting to one of these needs no scan at all.
            if (knownRadios.isNotEmpty()) {
                item {
                    Text(
                        "Saved radios",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                items(knownRadios, key = { "saved-" + it.address }) { radio ->
                    val seen = devices[radio.address]
                    val live = state is RadioState.Subscribed && deviceName == radio.label
                    ListItem(
                        headlineContent = { Text(radio.label) },
                        supportingContent = {
                            Text(
                                buildList {
                                    add(radio.address)
                                    if (seen != null) add("in range  ${seen.rssi} dBm")
                                    if (radio.lastConnectedMs > 0) {
                                        add("last used ${relativeTime(radio.lastConnectedMs)}")
                                    }
                                }.joinToString("  •  ")
                            )
                        },
                        leadingContent = {
                            Icon(
                                if (radio.type == "tcp") Icons.Default.Wifi else Icons.Default.Bluetooth,
                                contentDescription = null,
                            )
                        },
                        trailingContent = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (live) {
                                    Text("Connected", style = MaterialTheme.typography.labelMedium)
                                } else {
                                    Button(onClick = { vm.connectKnown(radio) }) { Text("Connect") }
                                }
                                TextButton(onClick = { vm.forget(radio) }) { Text("Forget") }
                            }
                        },
                    )
                }
                item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            }

            // Scan results, minus anything already saved above.
            // Stable sort: RSSI updates every advertisement and reordering rows while
            // the user is aiming at a Connect button causes mis-taps.
            val savedAddresses = knownRadios.map { it.address }.toSet()
            val found = devices.values
                .filterNot { it.id in savedAddresses }
                .sortedBy { it.name + it.id }
            items(found, key = { it.id }) { device ->
                ListItem(
                    headlineContent = { Text(device.name) },
                    supportingContent = { Text("${device.id}  •  ${device.rssi} dBm") },
                    leadingContent = { Icon(Icons.Default.Bluetooth, contentDescription = null) },
                    trailingContent = {
                        Button(onClick = { vm.connectBle(device) }) { Text("Connect") }
                    },
                )
            }
        }

        TcpConnectRow(onConnect = vm::connectTcp)
    }
}

@Composable
private fun StatusRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(Modifier.padding(4.dp))
        Text(text)
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
            label = { Text("Network radio (host[:port])") },
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
