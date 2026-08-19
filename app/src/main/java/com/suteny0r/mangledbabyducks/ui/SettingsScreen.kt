package com.suteny0r.mangledbabyducks.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.suteny0r.mangledbabyducks.radio.RadioState

@Composable
fun SettingsScreen(vm: SettingsViewModel = viewModel()) {
    val myInfo by vm.myInfo.collectAsState()
    val state by vm.state.collectAsState()
    val nodeCount by vm.nodeCount.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Card(Modifier.fillMaxWidth()) {
            Column {
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
        Text(
            "Mangled Baby Ducks, a meshtastic compatible node",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
