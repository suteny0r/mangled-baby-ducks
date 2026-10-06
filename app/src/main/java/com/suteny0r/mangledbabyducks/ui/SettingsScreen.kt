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
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.SettingsInputAntenna
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Tag
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.suteny0r.mangledbabyducks.container
import com.suteny0r.mangledbabyducks.radio.RadioState
import com.suteny0r.mangledbabyducks.ui.theme.IosGreen
import org.meshtastic.proto.AppOnlyProtos

@Composable
fun SettingsScreen(vm: SettingsViewModel = viewModel()) {
    val myInfo by vm.myInfo.collectAsState()
    val state by vm.state.collectAsState()
    val nodeCount by vm.nodeCount.collectAsState()
    val myUser by vm.myUser.collectAsState()
    val shareLocation by vm.shareLocation.collectAsState()

    val context = LocalContext.current
    var editingOwner by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }

    // Settings is a tab, not a nav graph, so a config section is a sub-screen held in
    // local state with the system back gesture wired to it.
    var section by rememberSaveable { mutableStateOf<ConfigSection?>(null) }
    // Remembered above the sub-screen early returns: state created below them leaves the
    // composition when a section opens, so the list would come back scrolled to the top.
    val listScroll = rememberScrollState()
    var showChannels by rememberSaveable { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    val router = LocalContext.current.container.router
    val pendingAbout by router.pendingAbout.collectAsState()
    LaunchedEffect(pendingAbout) {
        if (pendingAbout) {
            section = null
            showAbout = true
            router.pendingAbout.value = false
        }
    }

    val connected = state is RadioState.Subscribed

    if (showAbout) {
        AboutScreen(onBack = { showAbout = false })
        return
    }

    if (showChannels) {
        BackHandler { showChannels = false }
        ChannelsScreen(vm, connected, onBack = { showChannels = false })
        return
    }

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

    val broadcastResult by vm.broadcastResult.collectAsState()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(listScroll),
    ) {
        AppHeader("Settings")
        Column(
            Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Settings.swift top group: app-level rows with accent glyphs.
            GroupCard {
                NavRow("About and licenses", Icons.Outlined.HelpOutline) { showAbout = true }
                RowDivider()
                NavRow("Help & Documentation", Icons.AutoMirrored.Outlined.MenuBook) {
                    openUrl(context, SOURCE_URL)
                }
                RowDivider()
                NavRow(
                    "Share phone location",
                    Icons.Outlined.MyLocation,
                    subtitle = "Broadcast the phone's GPS as this node's position",
                    chevron = false,
                    trailing = {
                        Switch(checked = shareLocation, onCheckedChange = { vm.setShareLocation(it) })
                    },
                )
            }

            // "Configure" + the connected-node chip.
            SectionHeader("Configure")
            GroupCard {
                NavRow(
                    title = if (connected) "Connected Node ${myUser?.longName ?: "?"}"
                    else "Connect to a Node",
                    icon = Icons.Outlined.Bluetooth,
                    iconTint = if (connected) IosGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                    chevron = false,
                    onClick = if (connected) null else { { router.selectedTab.value = Router.TAB_CONNECT } },
                )
            }

            // iOS Settings groups: Radio Configuration holds LoRa/Security (+ channel QR),
            // Device Configuration holds the rest of the sections.
            SectionHeader("Radio Configuration")
            GroupCard {
                NavRow(ConfigSection.LORA.title, Icons.Outlined.SettingsInputAntenna, ConfigSection.LORA.summary) {
                    section = ConfigSection.LORA
                }
                RowDivider()
                NavRow("Channels", Icons.Outlined.Tag, "Primary and secondary channels, keys, MQTT") {
                    showChannels = true
                }
                RowDivider()
                NavRow("Import channels", Icons.Outlined.Link, "From a meshtastic.org/e/# link", enabled = connected) {
                    showImport = true
                }
                RowDivider()
                NavRow(ConfigSection.SECURITY.title, Icons.Outlined.Shield, ConfigSection.SECURITY.summary) {
                    section = ConfigSection.SECURITY
                }
                RowDivider()
                NavRow("Share QR Code", Icons.Outlined.QrCode2, "This radio's channels as QR and URL") {
                    showExport = true
                }
            }

            SectionHeader("Device Configuration")
            GroupCard {
                NavRow(
                    "User",
                    Icons.Outlined.Badge,
                    myUser?.let { "${it.longName ?: "?"} (${it.shortName ?: "?"})" } ?: "Owner name",
                    enabled = myUser != null,
                ) { editingOwner = true }
                listOf(
                    ConfigSection.BLUETOOTH to Icons.Outlined.Bluetooth,
                    ConfigSection.DEVICE to Icons.Outlined.Smartphone,
                    ConfigSection.DISPLAY to Icons.Outlined.DesktopWindows,
                    ConfigSection.NETWORK to Icons.Outlined.Lan,
                    ConfigSection.POSITION to Icons.Outlined.LocationOn,
                    ConfigSection.POWER to Icons.Outlined.Bolt,
                ).forEach { (entry, icon) ->
                    RowDivider()
                    NavRow(entry.title, icon, entry.summary) { section = entry }
                }
            }

            SectionHeader("Tools")
            GroupCard {
                NavRow(
                    "Broadcast node info",
                    Icons.Outlined.Campaign,
                    subtitle = "Re-announce this node to the mesh",
                    enabled = connected,
                    chevron = false,
                ) { vm.broadcastNodeInfo() }
            }

            // The full trademark notice lives in About; this is the one-line minimum.
            Text(
                "Not affiliated with or endorsed by Meshtastic LLC.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp),
            )
        }
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
    // ExchangeUserInfoButton.swift confirms the send with an alert; the row's subtitle
    // alone was too quiet to read as feedback.
    broadcastResult?.let { ok ->
        AlertDialog(
            onDismissRequest = { vm.clearBroadcastResult() },
            title = { Text(if (ok) "Node Info Sent" else "Broadcast Failed") },
            text = {
                Text(
                    if (ok) "Your node info has been broadcast to the mesh."
                    else "Could not send the node info broadcast."
                )
            },
            confirmButton = { TextButton(onClick = { vm.clearBroadcastResult() }) { Text("OK") } },
        )
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

fun qrBitmap(content: String, size: Int = 720): Bitmap {
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
