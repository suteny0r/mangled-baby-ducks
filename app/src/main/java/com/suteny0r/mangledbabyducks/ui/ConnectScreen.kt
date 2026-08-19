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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Radio", style = MaterialTheme.typography.titleMedium)
                when (val s = state) {
                    is RadioState.Idle -> Text("Not connected")
                    is RadioState.Connecting -> StatusRow("Connecting…")
                    is RadioState.Communicating -> StatusRow("Retrieving configuration…")
                    is RadioState.RetrievingDatabase ->
                        StatusRow("Loading node database (${s.nodeCount} nodes)…")
                    is RadioState.Subscribed ->
                        Text("Connected to ${deviceName ?: "radio"}")
                    is RadioState.Reconnecting ->
                        StatusRow("Connection lost, reconnecting (attempt ${s.attempt})…")
                    is RadioState.Failed ->
                        Text("Failed: ${s.reason}", color = MaterialTheme.colorScheme.error)
                }
                if (state is RadioState.Subscribed) {
                    OutlinedButton(onClick = { vm.disconnect() }) { Text("Disconnect") }
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
            items(devices.values.sortedByDescending { it.rssi }, key = { it.id }) { device ->
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
