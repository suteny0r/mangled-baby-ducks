package com.suteny0r.mangledbabyducks.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.suteny0r.mangledbabyducks.radio.RadioState

@Composable
fun SettingsScreen(vm: SettingsViewModel = viewModel()) {
    val myInfo by vm.myInfo.collectAsState()
    val state by vm.state.collectAsState()
    val nodeCount by vm.nodeCount.collectAsState()
    val lora by vm.loraConfig.collectAsState()
    val device by vm.deviceConfig.collectAsState()
    val myUser by vm.myUser.collectAsState()
    var editingOwner by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        Card(Modifier.fillMaxWidth()) {
            Column {
                ListItem(
                    headlineContent = { Text("Owner") },
                    supportingContent = {
                        Text(
                            myUser?.let { "${it.longName ?: "?"} (${it.shortName ?: "?"})" }
                                ?: "—",
                        )
                    },
                    trailingContent = { Text("Edit") },
                    modifier = Modifier.clickable(enabled = myUser != null) { editingOwner = true },
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Connection") },
                    supportingContent = {
                        Text(
                            when (state) {
                                is RadioState.Subscribed -> "Connected"
                                is RadioState.Idle -> "Disconnected"
                                is RadioState.Failed -> "Failed"
                                else -> "Connecting…"
                            }
                        )
                    },
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Node number") },
                    supportingContent = { Text(myInfo?.myNodeNum?.toString() ?: "—") },
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Firmware") },
                    supportingContent = { Text(myInfo?.firmwareVersion ?: "—") },
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Known nodes") },
                    supportingContent = { Text(nodeCount.toString()) },
                )
            }
        }

        Text("LoRa", style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth()) {
            Column {
                ListItem(
                    headlineContent = { Text("Region") },
                    supportingContent = { Text(lora?.region?.name ?: "—") },
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Modem preset") },
                    supportingContent = {
                        Text(
                            lora?.let {
                                if (it.usePreset) it.modemPreset.name
                                else "custom (bw ${it.bandwidth}, sf ${it.spreadFactor}, cr ${it.codingRate})"
                            } ?: "—",
                        )
                    },
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Hop limit") },
                    supportingContent = { Text(lora?.hopLimit?.toString() ?: "—") },
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Transmit") },
                    supportingContent = {
                        Text(
                            lora?.let {
                                if (it.txEnabled) "enabled, ${it.txPower} dBm" else "disabled"
                            } ?: "—",
                        )
                    },
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Frequency slot") },
                    supportingContent = { Text(lora?.channelNum?.toString() ?: "—") },
                )
            }
        }

        Text("Device", style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth()) {
            Column {
                ListItem(
                    headlineContent = { Text("Role") },
                    supportingContent = { Text(device?.role?.name ?: "—") },
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Node info broadcast") },
                    supportingContent = {
                        Text(device?.nodeInfoBroadcastSecs?.let { "every ${it}s" } ?: "—")
                    },
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Time zone") },
                    supportingContent = { Text(device?.tzdef?.ifEmpty { "—" } ?: "—") },
                )
            }
        }

        val broadcastResult by vm.broadcastResult.collectAsState()
        Button(
            onClick = { vm.broadcastNodeInfo() },
            enabled = state is RadioState.Subscribed,
        ) { Text("Broadcast node info") }
        broadcastResult?.let {
            Text(
                if (it) "Node info broadcast sent" else "Broadcast failed",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            "Mangled Baby Ducks, a meshtastic compatible node",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    if (editingOwner) {
        OwnerDialog(
            initialLong = myUser?.longName ?: "",
            initialShort = myUser?.shortName ?: "",
            onDismiss = { editingOwner = false },
            onSave = { longName, shortName ->
                vm.setOwner(longName, shortName)
                editingOwner = false
            },
        )
    }
}

@Composable
private fun OwnerDialog(
    initialLong: String,
    initialShort: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var longName by remember { mutableStateOf(initialLong) }
    var shortName by remember { mutableStateOf(initialShort) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Radio owner") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = longName,
                    onValueChange = { if (it.length <= 39) longName = it },
                    label = { Text("Long name") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = shortName,
                    onValueChange = { if (it.length <= 4) shortName = it },
                    label = { Text("Short name (max 4)") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = longName.isNotBlank() && shortName.isNotBlank(),
                onClick = { onSave(longName.trim(), shortName.trim()) },
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
