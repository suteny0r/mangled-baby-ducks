package com.suteny0r.mangledbabyducks.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.suteny0r.mangledbabyducks.radio.RadioState
import org.meshtastic.proto.AppOnlyProtos

@Composable
fun SettingsScreen(vm: SettingsViewModel = viewModel()) {
    val myInfo by vm.myInfo.collectAsState()
    val state by vm.state.collectAsState()
    val nodeCount by vm.nodeCount.collectAsState()
    val myUser by vm.myUser.collectAsState()
    val shareLocation by vm.shareLocation.collectAsState()

    var editingOwner by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }

    // Settings is a tab, not a nav graph, so a config section is a sub-screen held in
    // local state with the system back gesture wired to it.
    var section by rememberSaveable { mutableStateOf<ConfigSection?>(null) }

    val connected = state is RadioState.Subscribed

    section?.let { open ->
        BackHandler { section = null }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { section = null }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text(open.title, style = MaterialTheme.typography.headlineSmall)
            }
            ConfigSectionDetail(open, vm, connected)
        }
        return
    }

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
                            myUser?.let { "${it.longName ?: "?"} (${it.shortName ?: "?"})" } ?: "—",
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

        Text("Phone", style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth()) {
            ListItem(
                headlineContent = { Text("Share phone location") },
                supportingContent = { Text("Broadcast the phone's GPS as this node's position") },
                trailingContent = {
                    Switch(checked = shareLocation, onCheckedChange = { vm.setShareLocation(it) })
                },
            )
        }

        Text("Channels", style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth()) {
            Column {
                ListItem(
                    headlineContent = { Text("Share channels (QR)") },
                    supportingContent = { Text("Show a QR code and URL for this radio's channels") },
                    modifier = Modifier.clickable { showExport = true },
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Import channels from URL") },
                    supportingContent = { Text("Paste a meshtastic.org/e/# link") },
                    modifier = Modifier.clickable(enabled = connected) { showImport = true },
                )
            }
        }

        Text("Radio configuration", style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth()) {
            Column {
                ConfigSection.entries.forEachIndexed { index, entry ->
                    if (index > 0) HorizontalDivider()
                    ListItem(
                        headlineContent = { Text(entry.title) },
                        supportingContent = { Text(entry.summary) },
                        modifier = Modifier.clickable { section = entry },
                    )
                }
            }
        }

        val broadcastResult by vm.broadcastResult.collectAsState()
        Button(
            onClick = { vm.broadcastNodeInfo() },
            enabled = connected,
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
    if (showExport) {
        ChannelExportDialog(vm, onDismiss = { showExport = false })
    }
    if (showImport) {
        ChannelImportDialog(vm, onDismiss = { showImport = false })
    }
}

@Composable
private fun ChannelExportDialog(vm: SettingsViewModel, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { url = vm.channelExportUrl() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share channels") },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                url?.let { u ->
                    val bitmap = remember(u) { qrBitmap(u) }
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Channel QR code",
                        modifier = Modifier.size(280.dp),
                    )
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(u, style = MaterialTheme.typography.bodySmall, maxLines = 4)
                    }
                    Text(
                        "Contains channel keys. Share only with people you trust.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                } ?: Text("No channels to share")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}

@Composable
private fun ChannelImportDialog(vm: SettingsViewModel, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("") }
    var parsed by remember { mutableStateOf<AppOnlyProtos.ChannelSet?>(null) }
    val applyResult by vm.applyResult.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import channels") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        parsed = vm.parseChannelUrl(it)
                    },
                    label = { Text("meshtastic.org/e/# URL") },
                    maxLines = 3,
                )
                parsed?.let { set ->
                    Text(
                        "Channels: " + set.settingsList.mapIndexed { i, s ->
                            s.name.ifEmpty { if (i == 0) "Primary" else "ch$i" }
                        }.joinToString(", ") +
                            (if (set.hasLoraConfig()) "\nLoRa: ${set.loraConfig.region.name}, " +
                                set.loraConfig.modemPreset.name else ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Applying REPLACES this radio's channels and reboots it.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (url.isNotBlank() && parsed == null) {
                    Text("Not a valid channel URL", color = MaterialTheme.colorScheme.error)
                }
                applyResult?.let {
                    Text(if (it) "Applied" else "Apply failed")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsed != null,
                onClick = { parsed?.let { vm.applyChannelSet(it) } },
            ) { Text("Apply to radio") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

private fun qrBitmap(content: String, size: Int = 720): Bitmap {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            pixels[y * size + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
        }
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
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
