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
import androidx.compose.material.icons.outlined.BugReport
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
import androidx.compose.material.icons.outlined.Storage
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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.suteny0r.mangledbabyducks.diagnostics.LogExport
import com.suteny0r.mangledbabyducks.radio.RadioState
import com.suteny0r.mangledbabyducks.ui.theme.IosGreen
import kotlinx.coroutines.launch
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
    var showLogWarning by remember { mutableStateOf(false) }
    var exportingLog by remember { mutableStateOf(false) }
    var logExportError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Settings is a tab, not a nav graph, so a config section is a sub-screen held in
    // local state with the system back gesture wired to it.
    var section by rememberSaveable { mutableStateOf<ConfigSection?>(null) }
    var moduleSection by rememberSaveable { mutableStateOf<ModuleSection?>(null) }
    // Remembered above the sub-screen early returns: state created below them leaves the
    // composition when a section opens, so the list would come back scrolled to the top.
    val listScroll = rememberScrollState()
    var showChannels by rememberSaveable { mutableStateOf(false) }
    var showBackups by rememberSaveable { mutableStateOf(false) }
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

    if (showBackups) {
        BackHandler { showBackups = false }
        BackupManagementScreen(onBack = { showBackups = false })
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
                Text(open.pageTitle, style = MaterialTheme.typography.headlineSmall)
            }
            ConfigSectionDetail(open, vm, connected)
        }
        return
    }

    moduleSection?.let { open ->
        BackHandler { moduleSection = null }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { moduleSection = null }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text(open.pageTitle, style = MaterialTheme.typography.headlineSmall)
            }
            ModuleSectionDetail(open, vm, connected)
        }
        return
    }

    val broadcastResult by vm.broadcastResult.collectAsState()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(listScroll),
    ) {
        AppHeader("Settings", large = false)
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

            // Settings.swift "Module Configuration": only External Notification is ported
            // so far; the rest of that group (MQTT, Canned Messages, Serial, ...) is a
            // known gap.
            SectionHeader("Module Configuration")
            GroupCard {
                NavRow(ModuleSection.EXTERNAL_NOTIFICATION.title, Icons.Outlined.Campaign, ModuleSection.EXTERNAL_NOTIFICATION.summary) {
                    moduleSection = ModuleSection.EXTERNAL_NOTIFICATION
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

            // Settings.swift developersSection: the per-radio database snapshots.
            SectionHeader("Developers")
            GroupCard {
                NavRow("Backup Management", Icons.Outlined.Storage) { showBackups = true }
                HorizontalDivider()
                NavRow(
                    "Send Diagnostic Log",
                    Icons.Outlined.BugReport,
                    subtitle = "Zip this app's log and share it",
                    enabled = !exportingLog,
                ) { showLogWarning = true }
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
            initialUnmessagable = myUser?.unmessagable == true,
            initialLicensed = myUser?.isLicensed == true,
            onDismiss = { editingOwner = false },
            onSave = { longName, shortName, unmessagable ->
                vm.setOwner(longName, shortName, unmessagable)
                editingOwner = false
            },
            onSaveLicensed = { callSign, longName, shortName, txPower, frequency ->
                vm.setHamMode(callSign, longName, shortName, txPower, frequency)
                editingOwner = false
            },
        )
    }
    // The log is readable plain text and goes to whoever the user picks out of the share
    // sheet, so the warning is a gate rather than a footnote: it names what is actually in
    // there, and nothing is generated until Continue is pressed.
    if (showLogWarning) {
        AlertDialog(
            onDismissRequest = { if (!exportingLog) showLogWarning = false },
            title = { Text("Send Diagnostic Log") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "This collects this app's own log. Android does not let an app read " +
                            "the system log or any other app's, so that is all it can gather."
                    )
                    Text("Expect the log to contain:")
                    Text(
                        "• Your radio's Bluetooth address and name\n" +
                            "• Node names and numbers from your mesh\n" +
                            "• Your radio's own debug output, which can include anything " +
                            "its firmware prints\n" +
                            "• Details about this phone, and when messages arrived",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "It is readable text, and whoever you send it to can read all of it. " +
                            "Only send it to someone you trust.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !exportingLog,
                    onClick = {
                        exportingLog = true
                        scope.launch {
                            runCatching { LogExport.writeZip(context) }
                                .onSuccess {
                                    context.startActivity(LogExport.shareIntent(context, it))
                                    showLogWarning = false
                                }
                                .onFailure { logExportError = it.message ?: it.javaClass.simpleName }
                            exportingLog = false
                        }
                    },
                ) { Text(if (exportingLog) "Collecting…" else "Continue") }
            },
            dismissButton = {
                TextButton(enabled = !exportingLog, onClick = { showLogWarning = false }) {
                    Text("Cancel")
                }
            },
        )
    }
    logExportError?.let { message ->
        AlertDialog(
            onDismissRequest = { logExportError = null },
            title = { Text("Could Not Collect the Log") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { logExportError = null }) { Text("OK") } },
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

/**
 * Port of UserConfig.swift's User Details section: the two names plus the two flags that
 * live on the owner record rather than in any Config section, which is why neither had a
 * home in the radio configuration forms.
 *
 * Licensed Operator is not a flag on the same save. iOS branches at the confirm button:
 * clear, it sends a `User` through `saveUser`; set, it sends `HamParameters` through
 * `saveLicensedUser`. The second takes a call sign, a TX power and a frequency override,
 * and the firmware then names the node after the call sign and turns encryption off, so
 * the consequences are spelled out here rather than left to the toggle.
 */
@Composable
private fun OwnerDialog(
    initialLong: String,
    initialShort: String,
    initialUnmessagable: Boolean,
    initialLicensed: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String, Boolean) -> Unit,
    onSaveLicensed: (String, String, String, Int, Float) -> Unit,
) {
    var longName by remember { mutableStateOf(initialLong) }
    var shortName by remember { mutableStateOf(initialShort) }
    var unmessagable by remember { mutableStateOf(initialUnmessagable) }
    var licensed by remember { mutableStateOf(initialLicensed) }
    var callSign by remember { mutableStateOf("") }
    var txPower by remember { mutableStateOf("30") }
    var frequency by remember { mutableStateOf("0") }

    val power = txPower.toIntOrNull()
    val freq = frequency.toFloatOrNull()
    val canSave = longName.isNotBlank() && shortName.isNotBlank() &&
        (!licensed || (callSign.isNotBlank() && power != null && freq != null))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Radio owner") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
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
                ToggleRow(
                    title = "Unmessagable",
                    detail = "Identifies unmonitored or infrastructure nodes, so other " +
                        "clients do not offer to message a node that will never answer.",
                    checked = unmessagable,
                    onCheckedChange = { unmessagable = it },
                )
                ToggleRow(
                    title = "Licensed operator",
                    detail = "Names the node after your call sign, broadcasts node info " +
                        "every 10 minutes, overrides frequency, duty cycle and TX power, " +
                        "and disables encryption. Check your local regulations first.",
                    checked = licensed,
                    onCheckedChange = { licensed = it },
                )
                if (licensed) {
                    OutlinedTextField(
                        value = callSign,
                        onValueChange = { if (it.length <= 8) callSign = it.uppercase() },
                        label = { Text("Call sign") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = txPower,
                        onValueChange = { txPower = it.filter { c -> c.isDigit() } },
                        label = { Text("TX power (dBm)") },
                        isError = power == null,
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = frequency,
                        onValueChange = { frequency = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Frequency override (MHz, 0 for none)") },
                        isError = freq == null,
                        singleLine = true,
                    )
                    Text(
                        "The radio saves and reboots.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    if (licensed) {
                        onSaveLicensed(
                            callSign.trim(),
                            longName.trim(),
                            shortName.trim(),
                            power ?: 30,
                            freq ?: 0f,
                        )
                    } else {
                        onSave(longName.trim(), shortName.trim(), unmessagable)
                    }
                },
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** A switch with the explanatory caption the iOS toggles carry underneath them. */
@Composable
private fun ToggleRow(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
