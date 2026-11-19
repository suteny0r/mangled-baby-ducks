package com.suteny0r.mangledbabyducks.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SensorsOff
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.suteny0r.mangledbabyducks.container
import com.suteny0r.mangledbabyducks.radio.DeviceProfileExport
import com.suteny0r.mangledbabyducks.radio.DeviceProfileImportError
import com.suteny0r.mangledbabyducks.radio.DeviceProfileImportException
import com.suteny0r.mangledbabyducks.radio.DeviceProfileImportPlan
import com.suteny0r.mangledbabyducks.radio.DeviceProfileImportResult
import com.suteny0r.mangledbabyducks.radio.DeviceProfileImporter
import com.suteny0r.mangledbabyducks.radio.DeviceProfileVerification
import com.suteny0r.mangledbabyducks.radio.DeviceProfileVerificationReadiness
import com.suteny0r.mangledbabyducks.radio.DeviceProfileVerifier
import com.suteny0r.mangledbabyducks.radio.FirmwareSupport
import com.suteny0r.mangledbabyducks.radio.ImportApplyProgress
import com.suteny0r.mangledbabyducks.radio.ImportItemKind
import com.suteny0r.mangledbabyducks.radio.ImportPayload
import com.suteny0r.mangledbabyducks.radio.ImportSection
import com.suteny0r.mangledbabyducks.radio.RadioProfileApplyGateway
import com.suteny0r.mangledbabyducks.radio.RadioState
import com.suteny0r.mangledbabyducks.radio.VerificationOutcome
import com.suteny0r.mangledbabyducks.ui.theme.IosGreen
import com.suteny0r.mangledbabyducks.ui.theme.IosOrange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.meshtastic.proto.ConfigProtos
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "DeviceProfile"

/**
 * State for the radio configuration backup: BackupRestoreSection.swift's export and file
 * handling, and ImportDeviceProfileView.swift's import run and readback check.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DeviceProfileViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app.container
    private val radio = container.radioManager
    private val db = container.database

    sealed interface Phase {
        data object Review : Phase
        data object Applying : Phase
        data class Done(val result: DeviceProfileImportResult) : Phase
    }

    val connected: StateFlow<Boolean> = radio.state
        .map { it is RadioState.Subscribed }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), radio.state.value is RadioState.Subscribed)

    /** NodeInfoEntity.isManaged: a mesh administrator owns this radio's configuration. */
    val isManaged: StateFlow<Boolean> = combine(
        db.configDao().config("config.security"),
        db.configDao().config("config.device"),
    ) { security, device ->
        val securityManaged = security?.let { runCatching { ConfigProtos.Config.parseFrom(it.bytes).security.isManaged }.getOrNull() }
        val deviceManaged = device?.let { runCatching { ConfigProtos.Config.parseFrom(it.bytes).device.isManaged }.getOrNull() }
        securityManaged == true || deviceManaged == true
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val nodeName: StateFlow<String?> = radio.myNodeNum
        .flatMapLatest { db.userDao().observe(it) }
        .map { it?.longName?.takeIf { name -> name.isNotBlank() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // ------------------------------------------------------------------ Export

    val exportFailed = MutableStateFlow<String?>(null)
    val preparingExport = MutableStateFlow(false)
    private var pendingExport: ByteArray? = null

    /** Builds the profile and returns the filename to offer, or null after reporting why not. */
    suspend fun prepareExport(): String? {
        preparingExport.value = true
        try {
            val myNum = radio.myNodeNum.value
            // The ringtone and canned message text are not part of the config the radio
            // streams on connect, so they are asked for now; a radio that does not answer
            // simply leaves them out.
            val (ringtone, canned) = runCatching { radio.fetchRingtoneAndCannedMessages() }.getOrDefault(null to null)
            val profile = DeviceProfileExport.build(db, myNum, ringtone, canned)
            pendingExport = profile.toByteArray()
            val user = db.userDao().get(myNum)
            return DeviceProfileExport.filename(user?.shortName, user?.longName)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build device profile", e)
            exportFailed.value = "The device configuration could not be prepared for export."
            return null
        } finally {
            preparingExport.value = false
        }
    }

    fun writeExport(uri: Uri) {
        val bytes = pendingExport ?: return
        pendingExport = null
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: error("No output stream for $uri")
            }.onSuccess {
                Log.i(TAG, "Device configuration export succeeded.")
            }.onFailure {
                Log.e(TAG, "Device configuration export failed", it)
                exportFailed.value = "The device configuration could not be saved. Please try again."
            }
        }
    }

    // ------------------------------------------------------------------ Import: file

    val importFailed = MutableStateFlow<String?>(null)

    private val _plan = MutableStateFlow<DeviceProfileImportPlan?>(null)
    val plan: StateFlow<DeviceProfileImportPlan?> = _plan

    /** BackupRestoreSection.handleImport: read the picked file, capped, and build the plan. */
    fun openImport(uri: Uri) {
        viewModelScope.launch {
            val myNum = radio.myNodeNum.value
            val me = db.userDao().get(myNum)
            if (!connected.value || me == null) {
                importFailed.value = "Connect to a node before importing a configuration."
                return@launch
            }
            // One byte past the cap, so an oversized file is rejected without loading it all.
            val data = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                        val cap = DeviceProfileImportPlan.MAX_PROFILE_BYTES + 1
                        val buffer = ByteArray(cap)
                        var read = 0
                        while (read < cap) {
                            val n = input.read(buffer, read, cap - read)
                            if (n < 0) break
                            read += n
                        }
                        buffer.copyOf(read)
                    }
                }.getOrNull()
            }
            if (data == null) {
                importFailed.value = "The configuration file could not be opened."
                return@launch
            }
            try {
                val profile = DeviceProfileImportPlan.parseDeviceProfile(data)
                val security = db.configDao().get("config.security")
                    ?.let { runCatching { ConfigProtos.Config.parseFrom(it.bytes).security }.getOrNull() }
                val built = DeviceProfileImportPlan(
                    profile = profile,
                    currentUser = DeviceProfileVerifier.ownerProto(me.longName, me.shortName, me.unmessagable),
                    currentSecurity = security,
                    firmwareVersion = radio.deviceMetadata.value?.firmwareVersion,
                )
                resetRun()
                _plan.value = built
            } catch (e: DeviceProfileImportException) {
                importFailed.value = if (e.error == DeviceProfileImportError.NOTHING_TO_IMPORT) {
                    "This configuration file doesn't contain anything to import."
                } else {
                    "This file isn't a valid Meshtastic configuration."
                }
            }
        }
    }

    // ------------------------------------------------------------------ Import: run

    private val _phase = MutableStateFlow<Phase>(Phase.Review)
    val phase: StateFlow<Phase> = _phase

    private val _progress = MutableStateFlow<ImportApplyProgress?>(null)
    val progress: StateFlow<ImportApplyProgress?> = _progress

    /** Liveness backstop: after a grace period the user can leave even if a send has stalled. */
    val canForceDismiss = MutableStateFlow(false)

    private val _verification = MutableStateFlow<DeviceProfileVerification?>(null)
    val verification: StateFlow<DeviceProfileVerification?> = _verification

    /** What the radio held just before the import, to tell an unchanged section from a normalized one. */
    @Volatile
    private var configBeforeImport: Map<ImportItemKind, ImportPayload> = emptyMap()

    /** The instant before the latest send. Only a config readback after this can be checked. */
    @Volatile
    private var verificationRefreshNotBefore: Long? = null

    private val cancelRequested = AtomicBoolean(false)
    private var importJob: Job? = null
    private var forceDismissJob: Job? = null
    @Volatile
    private var runId = 0

    init {
        // The readback arrives on its own, when the rebooted radio reconnects.
        viewModelScope.launch { radio.lastConfigRefresh.collect { runVerification() } }
    }

    private fun resetRun() {
        runId++
        _phase.value = Phase.Review
        _progress.value = null
        _verification.value = null
        canForceDismiss.value = false
        configBeforeImport = emptyMap()
        verificationRefreshNotBefore = null
        forceDismissJob?.cancel()
    }

    /** Leaves the import screen. A run still in flight carries on to its commit unobserved. */
    fun closeImport() {
        resetRun()
        _plan.value = null
    }

    /** Stops the run at the next step. The transaction is still committed. */
    fun cancelImport() = cancelRequested.set(true)

    fun startImport(selection: Set<ImportSection>) {
        val plan = _plan.value ?: return
        if (importJob?.isActive == true) return // no double apply
        cancelRequested.set(false)
        canForceDismiss.value = false
        _verification.value = null
        _phase.value = Phase.Applying
        val id = ++runId
        // On the application scope, not this ViewModel's: once the transaction is open the
        // run has to reach its commit even if the screen and its ViewModel go away.
        importJob = container.appScope.launch {
            val myNum = radio.myNodeNum.value
            val selected = plan.items(selection)
            configBeforeImport = DeviceProfileVerifier.snapshot(db, myNum, selected.map { it.kind })
            _progress.value = ImportApplyProgress(selected.groupingBy { it.section }.eachCount())
            verificationRefreshNotBefore = System.currentTimeMillis()
            val result = DeviceProfileImporter.apply(
                plan = plan,
                selection = selection,
                gateway = RadioProfileApplyGateway(radio),
                isCancelled = { cancelRequested.get() },
                progress = { item, index, _ ->
                    // A readback cannot vouch for items sent after it.
                    verificationRefreshNotBefore = System.currentTimeMillis()
                    _progress.update { it?.announce(item, index) }
                },
            )
            Log.i(TAG, "Import finished: applied=${result.applied} skipped=${result.skipped} failed=${result.failed} committed=${result.transactionCommitted}")
            if (runId == id) {
                _progress.value = null
                canForceDismiss.value = false
                _phase.value = Phase.Done(result)
                runVerification()
            }
        }
        forceDismissJob?.cancel()
        forceDismissJob = viewModelScope.launch {
            delay(15_000)
            if (_phase.value is Phase.Applying) canForceDismiss.value = true
        }
    }

    fun readiness(result: DeviceProfileImportResult) = DeviceProfileVerificationReadiness(
        expectsReconnect = result.rebooting,
        refreshNotBefore = verificationRefreshNotBefore,
        lastConfigRefresh = radio.lastConfigRefresh.value,
        hasVerification = _verification.value != null,
    )

    fun runVerification() {
        val plan = _plan.value ?: return
        val result = (_phase.value as? Phase.Done)?.result ?: return
        if (!readiness(result).shouldVerify) return
        val id = runId
        viewModelScope.launch {
            val after = DeviceProfileVerifier.snapshot(db, radio.myNodeNum.value, result.applied)
            if (runId != id || _verification.value != null) return@launch
            _verification.value = DeviceProfileVerifier.verify(
                applied = result.applied,
                plan = plan,
                before = configBeforeImport,
                after = after,
                lastConfigRefresh = radio.lastConfigRefresh.value,
                readbackNotBefore = verificationRefreshNotBefore ?: Long.MAX_VALUE,
            )
        }
    }
}

// ====================================================================== Settings section

/**
 * BackupRestoreSection.swift: backup and restore of the connected radio's whole
 * configuration. Not the same thing as Backup Management, which snapshots this app's own
 * messages and nodes.
 */
@Composable
fun BackupRestoreSection(vm: DeviceProfileViewModel) {
    val connected by vm.connected.collectAsState()
    val managed by vm.isManaged.collectAsState()
    val exportFailed by vm.exportFailed.collectAsState()
    val importFailed by vm.importFailed.collectAsState()
    val preparing by vm.preparingExport.collectAsState()
    var showExportWarning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val enabled = connected && !managed

    // A .cfg has no registered MIME type, so the picker cannot be narrowed to it.
    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> if (uri != null) vm.writeExport(uri) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.openImport(uri)
    }

    SectionHeader("Backup & Restore")
    GroupCard {
        if (managed) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AdminPanelSettings, contentDescription = null, tint = IosOrange)
                Spacer(Modifier.width(12.dp))
                Text(
                    "This radio is managed and can only be changed by a remote admin.",
                    color = IosOrange,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            RowDivider()
        }
        NavRow("Import Configuration", Icons.Outlined.FileDownload, enabled = enabled, chevron = false) {
            importer.launch(arrayOf("*/*"))
        }
        RowDivider()
        NavRow(
            "Export Configuration",
            Icons.Outlined.FileUpload,
            enabled = enabled && !preparing,
            chevron = false,
            trailing = if (preparing) {
                { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
            } else null,
        ) { showExportWarning = true }
        if (!connected) {
            Text(
                "Connect to a node to back up or restore its configuration.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
        }
    }

    if (showExportWarning) {
        AlertDialog(
            onDismissRequest = { showExportWarning = false },
            title = { Text("Export Device Configuration") },
            text = {
                Text(
                    "This backup contains sensitive security material: your node's private key, admin keys, " +
                        "channel keys (PSKs), and Wi-Fi/MQTT passwords. Anyone with this file can join and " +
                        "administer your mesh, so only share it with people you trust."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showExportWarning = false
                    scope.launch { vm.prepareExport()?.let { exporter.launch(it) } }
                }) { Text("Export Configuration") }
            },
            dismissButton = { TextButton(onClick = { showExportWarning = false }) { Text("Cancel") } },
        )
    }
    exportFailed?.let { message ->
        AlertDialog(
            onDismissRequest = { vm.exportFailed.value = null },
            title = { Text("Export Failed") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { vm.exportFailed.value = null }) { Text("OK") } },
        )
    }
    importFailed?.let { message ->
        AlertDialog(
            onDismissRequest = { vm.importFailed.value = null },
            title = { Text("Import Failed") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { vm.importFailed.value = null }) { Text("OK") } },
        )
    }
}

// ====================================================================== Import review

/**
 * ImportDeviceProfileView.swift: what a parsed profile will apply, a switch per section
 * (Security & Identity off by default), the warnings, and after an explicit confirmation
 * the run itself with per-section progress and a final summary.
 */
@Composable
fun ImportDeviceProfileScreen(plan: DeviceProfileImportPlan, vm: DeviceProfileViewModel, onClose: () -> Unit) {
    val phase by vm.phase.collectAsState()
    val progress by vm.progress.collectAsState()
    val canForceDismiss by vm.canForceDismiss.collectAsState()
    val connected by vm.connected.collectAsState()
    val nodeName by vm.nodeName.collectAsState()
    val verification by vm.verification.collectAsState()
    var selection by remember(plan) { mutableStateOf(plan.presentSections.filter { it.defaultsOn }.toSet()) }
    var confirming by remember { mutableStateOf(false) }

    val applying = phase is DeviceProfileViewModel.Phase.Applying
    val done = phase as? DeviceProfileViewModel.Phase.Done
    val canImport = connected && selection.isNotEmpty() && !applying

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (applying) {
                // Always a way out: Cancel stops the run at the next step, and once the
                // grace period has passed the same button also leaves the screen.
                TextButton(onClick = {
                    vm.cancelImport()
                    if (canForceDismiss) onClose()
                }) { Text(if (canForceDismiss) "Dismiss" else "Cancel") }
            } else {
                TextButton(onClick = onClose) { Text(if (done == null) "Cancel" else "Done") }
            }
            Text(
                "Import Configuration",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            if (done == null && !applying) {
                TextButton(onClick = { confirming = true }, enabled = canImport) { Text("Import") }
            }
        }

        val current = progress
        if (applying && current != null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(current.currentItemTitle, style = MaterialTheme.typography.labelLarge)
                LinearProgressIndicator(
                    progress = { current.fractionCompleted },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
                Text(
                    "Sending ${current.currentIndex + 1} of ${current.total}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (done == null) {
                ReviewContent(plan, selection, applying, canForceDismiss, connected, nodeName, progress) { section, on ->
                    selection = if (on) selection + section else selection - section
                }
            } else {
                ResultContent(done.result, verification, vm.readiness(done.result).shouldVerify, vm::runVerification)
            }
        }
    }

    if (confirming) {
        val lines = buildList {
            if (plan.containsSensitive(selection)) add("This overwrites sensitive security material on the connected node.")
            if (ImportSection.SECURITY in selection) {
                add("It replaces this node's identity and admin keys, which can break existing direct messages.")
            }
            // Unconditional: the transaction commit reboots whatever was picked.
            if (selection.isNotEmpty()) add("The radio will reboot to apply these changes.")
        }
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Apply Configuration") },
            text = { Text(lines.joinToString(" ").ifEmpty { "Apply the selected configuration to the connected node?" }) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    vm.startImport(selection)
                }) { Text("Apply Configuration", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ReviewContent(
    plan: DeviceProfileImportPlan,
    selection: Set<ImportSection>,
    applying: Boolean,
    canForceDismiss: Boolean,
    connected: Boolean,
    nodeName: String?,
    progress: ImportApplyProgress?,
    onToggle: (ImportSection, Boolean) -> Unit,
) {
    GroupCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Apply this saved configuration to ${nodeName ?: "the connected node"}.")
            if (!connected) Note(Icons.Outlined.SensorsOff, "Connect to a radio before importing.", IosOrange)
            if (applying && canForceDismiss) {
                Note(Icons.Outlined.Schedule, "This is taking longer than expected. You can cancel or dismiss.", IosOrange)
            }
        }
    }

    if (plan.containsSensitive(selection)) {
        GroupCard {
            Row(Modifier.padding(16.dp)) {
                Icon(Icons.Outlined.GppMaybe, contentDescription = null, tint = IosOrange)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("This import overwrites sensitive material", fontWeight = FontWeight.Bold)
                    val parts = buildList {
                        if (ImportSection.SECURITY in selection) add("your node's private key & admin keys")
                        if (ImportSection.CHANNELS_AND_LORA in selection) add("channel keys (PSKs)")
                        if (ImportSection.NETWORK in selection) add("Wi-Fi password")
                        if (plan.items(selection).any { it.kind == ImportItemKind.MQTT }) add("MQTT password")
                    }
                    Text(
                        "Includes ${parts.joinToString(", ").ifEmpty { "sensitive credentials" }}. " +
                            "Only import files from a source you trust.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    // Every import reboots: it runs inside an edit transaction, and the commit always
    // saves and reboots.
    if (selection.isNotEmpty()) {
        GroupCard {
            Column(Modifier.padding(16.dp)) {
                Note(
                    Icons.Outlined.Autorenew,
                    "Applying these settings reboots the radio; it will briefly disconnect.",
                    IosOrange,
                )
            }
        }
    }

    // The radio acks module configs it has no handler for, so these are reported, not sent.
    if (plan.unsupported.isNotEmpty()) {
        SectionHeader("Not Supported by This Radio")
        GroupCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                plan.unsupported.forEach { (item, support) ->
                    Column {
                        Note(Icons.Outlined.Block, item.kind.displayName, MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            when (support) {
                                is FirmwareSupport.FromVersion -> "Needs firmware ${support.version} or newer."
                                FirmwareSupport.Unimplemented -> "No firmware version supports importing this yet."
                                FirmwareSupport.Always -> ""
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    SectionHeader("Sections to Import")
    GroupCard {
        plan.presentSections.forEachIndexed { index, section ->
            if (index > 0) RowDivider()
            val items = plan.items.filter { it.section == section }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(section.title)
                        if (items.any { it.isSensitive }) {
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.Filled.Key, contentDescription = "Sensitive", tint = IosOrange, modifier = Modifier.size(14.dp))
                        }
                    }
                    Text(
                        items.joinToString(", ") { it.summary },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (section == ImportSection.SECURITY) {
                        Text(
                            "Changes this node's identity (private/public key) and admin keys. Can break " +
                                "existing direct messages and lock local administration.",
                            style = MaterialTheme.typography.labelSmall,
                            color = IosOrange,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                if (applying) {
                    when (progress?.sectionStatus(section) ?: ImportApplyProgress.SectionStatus.PENDING) {
                        ImportApplyProgress.SectionStatus.SENDING ->
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        ImportApplyProgress.SectionStatus.SENT ->
                            Icon(Icons.Filled.CheckCircle, contentDescription = "Sent", tint = IosGreen)
                        ImportApplyProgress.SectionStatus.PENDING -> Unit
                    }
                } else {
                    Switch(checked = section in selection, onCheckedChange = { onToggle(section, it) })
                }
            }
        }
    }
}

@Composable
private fun ResultContent(
    result: DeviceProfileImportResult,
    verification: DeviceProfileVerification?,
    canVerify: Boolean,
    onVerify: () -> Unit,
) {
    fun settings(n: Int) = "$n setting${if (n == 1) "" else "s"}"

    GroupCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val sent = result.applied.size
            when {
                // Sends are not confirmed by the radio, so this says "sent", not "applied".
                result.isCompleteSuccess ->
                    Note(Icons.Filled.CheckCircle, "Sent ${settings(sent)} to the node.", IosGreen)
                result.wasCancelled ->
                    Note(Icons.Outlined.Cancel, "Import cancelled. Sent $sent of ${settings(sent + result.skipped.size)}.", IosOrange)
                else ->
                    Note(Icons.Outlined.WarningAmber, "Import stopped after a failure. Sent ${settings(sent)} first.", IosOrange)
            }
            if (result.rebooting) {
                Caption("Your radio is rebooting to save the imported settings. Reconnect to verify.")
            } else if (result.applied.isNotEmpty()) {
                Caption("Reconnect to the node to confirm the changes were applied.")
            }
            if (result.commitUnconfirmed) {
                Caption(
                    "The node did not confirm saving these settings. Reconnect and check its configuration; " +
                        "you may need to import again.",
                    IosOrange,
                )
            }
            if (result.failed?.first == ImportItemKind.CHANNEL_URL) {
                Caption(
                    "Channel changes may have partially applied. Re-import Channels & LoRa to bring the node " +
                        "to a consistent state.",
                    IosOrange,
                )
            }
        }
    }

    if (result.requiresReconnect.isNotEmpty()) {
        SectionHeader("Needs a Second Pass")
        GroupCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Caption(
                    "Reconnect to the node and import these again. They could not be sent because applying " +
                        "them disconnects the radio."
                )
                result.requiresReconnect.forEach { Note(Icons.Outlined.Autorenew, it.displayName) }
            }
        }
    }

    // "Sent" is not "applied": reading the config back after the reboot is the only way to tell.
    SectionHeader("Check What Applied")
    GroupCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                verification != null -> {
                    val unavailable = verification.unavailable
                    if (unavailable != null) Caption(unavailable)
                    else verification.outcomes.forEach { (kind, outcome) -> VerificationRow(kind, outcome) }
                }
                result.rebooting -> {
                    Note(Icons.Outlined.SensorsOff, "Waiting for the radio to reconnect", MaterialTheme.colorScheme.onSurfaceVariant)
                    Caption("Results will appear here automatically after the radio sends its configuration.")
                    if (canVerify) {
                        TextButton(onClick = onVerify) {
                            Icon(Icons.Outlined.VerifiedUser, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Verify Against the Radio")
                        }
                    }
                }
                else -> {
                    Note(Icons.Outlined.RemoveCircleOutline, "Verification unavailable", MaterialTheme.colorScheme.onSurfaceVariant)
                    Caption("The radio did not reboot, so there is no fresh configuration to check against.")
                }
            }
        }
    }

    result.failed?.let { (kind, message) ->
        SectionHeader("Failed")
        GroupCard {
            Column(Modifier.padding(16.dp)) {
                Text(kind.displayName)
                Caption(message)
            }
        }
    }
    if (result.applied.isNotEmpty()) {
        SectionHeader("Sent")
        GroupCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                result.applied.forEach { Note(Icons.Outlined.Check, it.displayName, MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
    if (result.skipped.isNotEmpty()) {
        SectionHeader("Not Applied")
        GroupCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                result.skipped.forEach { Text(it.displayName, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun VerificationRow(kind: ImportItemKind, outcome: VerificationOutcome) {
    Column {
        when (outcome) {
            VerificationOutcome.Applied -> Note(Icons.Filled.CheckCircle, kind.displayName, IosGreen)
            VerificationOutcome.LikelyDropped -> {
                Note(Icons.Outlined.Cancel, kind.displayName, IosOrange)
                Caption("The radio still reports its previous value. Import this section again.")
            }
            is VerificationOutcome.Inconclusive -> {
                Note(Icons.Outlined.HelpOutline, kind.displayName, MaterialTheme.colorScheme.onSurfaceVariant)
                Caption(outcome.detail)
            }
            is VerificationOutcome.NotComparable -> {
                Note(Icons.Outlined.RemoveCircleOutline, kind.displayName, MaterialTheme.colorScheme.onSurfaceVariant)
                Caption(outcome.reason)
            }
        }
    }
}

/** An icon and a line of text, the SwiftUI `Label`. */
@Composable
private fun Note(icon: ImageVector, text: String, tint: Color = MaterialTheme.colorScheme.onSurface) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, color = tint, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Caption(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}
