package com.suteny0r.mangledbabyducks.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.suteny0r.mangledbabyducks.container
import com.suteny0r.mangledbabyducks.db.BackupEntry
import com.suteny0r.mangledbabyducks.db.BackupResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Date
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.ui.text.font.FontFamily

/** State and actions behind BackupManagement.swift. */
class BackupViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app.container

    private val _backups = MutableStateFlow(container.backups.listBackups())
    val backups: StateFlow<List<BackupEntry>> = _backups.asStateFlow()
    private val _totalSize = MutableStateFlow(container.backups.totalBackupSize)
    val totalSize: StateFlow<Long> = _totalSize.asStateFlow()
    val restoring = MutableStateFlow(false)
    val backingUp = MutableStateFlow(false)
    /** Title to message; null when no alert is up. */
    val error = MutableStateFlow<Pair<String, String>?>(null)

    fun refresh() {
        _backups.value = container.backups.listBackups()
        _totalSize.value = container.backups.totalBackupSize
    }

    /** `backupNow`: snapshot whatever radio the store currently belongs to. */
    fun backupNow() {
        viewModelScope.launch {
            val nodeNum = container.currentNodeNum()
            if (nodeNum == null) {
                error.value = "Backup Failed" to "No connected node found to back up."
                return@launch
            }
            val name = container.database.userDao().get(nodeNum)?.longName
            backingUp.value = true
            try {
                when (val result = container.backups.createBackup(nodeNum, container.currentDeviceId(nodeNum), name)) {
                    is BackupResult.Success -> refresh()
                    is BackupResult.Skipped -> error.value = "Backup Failed" to result.reason
                    BackupResult.NoBackupFound -> error.value = "Backup Failed" to "Backup could not be created."
                }
            } finally {
                backingUp.value = false
            }
        }
    }

    /**
     * `restoreBackup`: `backupCurrentAndRestoreDatabase(forNode:disconnectCurrentDevice: true)`.
     * Backs the live radio up, drops the link, clears, imports the chosen snapshot. The
     * Connect tab is where the user reconnects from, as on iOS.
     */
    fun restore(entry: BackupEntry) {
        viewModelScope.launch {
            restoring.value = true
            try {
                when (val result = container.backupCurrentAndRestore(getApplication(), entry.nodeNum, disconnectCurrentDevice = true)) {
                    is BackupResult.Success -> refresh()
                    is BackupResult.Skipped -> error.value = "Restore Failed" to result.reason
                    BackupResult.NoBackupFound -> error.value = "Restore Failed" to "No backup was found for this node."
                }
            } finally {
                restoring.value = false
            }
        }
    }

    fun delete(entry: BackupEntry) {
        container.backups.deleteBackup(entry.key)
        refresh()
    }

    val transferring = MutableStateFlow(false)

    /** Write every snapshot into the zip the user chose through the system file picker. */
    fun exportTo(uri: Uri) {
        viewModelScope.launch {
            transferring.value = true
            try {
                val resolver = getApplication<Application>().contentResolver
                val count = resolver.openOutputStream(uri, "wt")?.use { container.backups.exportArchive(it) }
                    ?: throw IllegalStateException("Could not open the chosen file for writing")
                error.value = "Export Complete" to "$count backup${if (count == 1) "" else "s"} written to the archive."
            } catch (e: Exception) {
                error.value = "Export Failed" to (e.message ?: "unknown error")
            } finally {
                transferring.value = false
            }
        }
    }

    /** Bring snapshots back from an archive made by [exportTo]; the live database is untouched. */
    fun importFrom(uri: Uri) {
        viewModelScope.launch {
            transferring.value = true
            try {
                val resolver = getApplication<Application>().contentResolver
                val summary = resolver.openInputStream(uri)?.use { container.backups.importArchive(it) }
                    ?: throw IllegalStateException("Could not open the chosen file")
                refresh()
                error.value = "Import Complete" to
                    "${summary.imported} imported, ${summary.skipped} skipped (already present and as new, or damaged)."
            } catch (e: Exception) {
                error.value = "Import Failed" to (e.message ?: "unknown error")
            } finally {
                transferring.value = false
            }
        }
    }
}

/**
 * Port of BackupManagement.swift: total storage, the per-radio snapshots with restore and
 * delete, and Backup Now. iOS exposes restore/delete as swipe actions and a context menu;
 * here a tap on the row opens the same two choices.
 */
@Composable
fun BackupManagementScreen(onBack: () -> Unit, vm: BackupViewModel = viewModel()) {
    val context = LocalContext.current
    val backups by vm.backups.collectAsState()
    val totalSize by vm.totalSize.collectAsState()
    val restoring by vm.restoring.collectAsState()
    val backingUp by vm.backingUp.collectAsState()
    val error by vm.error.collectAsState()
    val transferring by vm.transferring.collectAsState()
    var pendingDelete by remember { mutableStateOf<BackupEntry?>(null) }
    LaunchedEffect(Unit) { vm.refresh() }
    // System file picker both ways: no storage permission, and the user decides where the
    // archive lives. The picker opens on the Documents folder, not Downloads: a file saved
    // through the picker's "Downloads" root is registered with the downloads provider
    // under this app, and on the Galaxy Note 20 Ultra that registration took the zip with
    // it when the app was uninstalled, which defeats the purpose. The Documents folder is
    // plain storage and survives.
    val exportLauncher = rememberLauncherForActivityResult(CreateDocumentInDocuments) { uri ->
        uri?.let { vm.exportTo(it) }
    }
    val importLauncher = rememberLauncherForActivityResult(OpenDocumentInDocuments) { uri ->
        uri?.let { vm.importFrom(it) }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundBackButton(onBack)
                Spacer(Modifier.weight(1f))
                // The toolbar's primary action, "Backup Now".
                IconButton(onClick = { vm.backupNow() }, enabled = !backingUp && !restoring) {
                    Icon(Icons.Outlined.Backup, contentDescription = "Backup Now")
                }
            }
            Text(
                "Backup Management",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
            )

            GroupCard(Modifier.padding(horizontal = 16.dp)) {
                NavRow(
                    "Total Backup Storage",
                    Icons.Outlined.Storage,
                    chevron = false,
                    trailing = {
                        Text(
                            Formatter.formatFileSize(context, totalSize),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }

            SectionHeader("Node Backups", Modifier.padding(start = 16.dp, top = 16.dp))
            GroupCard(Modifier.padding(horizontal = 16.dp)) {
                if (backups.isEmpty()) {
                    Text(
                        "No backups available",
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                backups.forEachIndexed { index, entry ->
                    if (index > 0) RowDivider()
                    BackupRow(
                        entry = entry,
                        enabled = !restoring && !backingUp,
                        onRestore = { vm.restore(entry) },
                        onDelete = { pendingDelete = entry },
                    )
                }
            }
            // Not on iOS, where the backup folder is visible in Files. Android hides the
            // app's data folder and an uninstall deletes it, so the snapshots need a way out
            // and back in.
            SectionHeader("Transfer", Modifier.padding(start = 16.dp, top = 16.dp))
            GroupCard(Modifier.padding(horizontal = 16.dp)) {
                NavRow(
                    "Export Backups to File",
                    Icons.Outlined.FileUpload,
                    subtitle = "All snapshots as one zip in Documents (not Downloads: Android removes an app's downloads with the app)",
                    enabled = backups.isNotEmpty() && !transferring && !restoring && !backingUp,
                    chevron = false,
                    onClick = {
                        val stamp = DateFormat.format("yyyyMMdd-HHmm", Date())
                        exportLauncher.launch("mangled-baby-ducks-backups-$stamp.zip")
                    },
                )
                RowDivider()
                NavRow(
                    "Import Backups from File",
                    Icons.Outlined.FileDownload,
                    subtitle = "Restore the snapshot list from an exported zip",
                    enabled = !transferring && !restoring && !backingUp,
                    chevron = false,
                    onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                )
            }
            Spacer(Modifier.padding(bottom = 24.dp))
        }

        if (restoring) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                Surface(shape = RoundedCornerShape(18.dp), tonalElevation = 6.dp) {
                    Column(
                        Modifier.padding(horizontal = 28.dp, vertical = 22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        CircularProgressIndicator()
                        Text("Restoring Backup", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete Backup?") },
            text = {
                Text(
                    "This will permanently delete the backup for ${entry.displayName} and free " +
                        "${Formatter.formatFileSize(context, entry.fileSize)} of storage."
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.delete(entry); pendingDelete = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }

    error?.let { (title, message) ->
        AlertDialog(
            onDismissRequest = { vm.error.value = null },
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { vm.error.value = null }) { Text("OK") } },
        )
    }
}

/** The primary storage's Documents folder as the picker's starting point. */
private val documentsFolderUri: Uri =
    DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents")

private object CreateDocumentInDocuments : ActivityResultContracts.CreateDocument("application/zip") {
    override fun createIntent(context: Context, input: String): Intent =
        super.createIntent(context, input).putExtra(DocumentsContract.EXTRA_INITIAL_URI, documentsFolderUri)
}

private object OpenDocumentInDocuments : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).putExtra(DocumentsContract.EXTRA_INITIAL_URI, documentsFolderUri)
}

/** `!%08x` when the backup carried no name, as BackupRowView falls back to `nodeNum.toHex()`. */
private val BackupEntry.displayName: String
    get() = nodeName?.takeIf { it.isNotBlank() } ?: "!%08x".format(nodeNum)

/** BackupRowView.swift: icon, name, "date • time", size; tap for Restore / Delete. */
@Composable
private fun BackupRow(entry: BackupEntry, enabled: Boolean, onRestore: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    val created = Date(entry.createdAt)
    val date = DateFormat.getMediumDateFormat(context).format(created)
    val time = DateFormat.getTimeFormat(context).format(created)
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { menuOpen = true }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Dns,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                // BackupRowView: the two identifiers answer different questions. The node
                // number is what the radio reports now and changes on the 2.8 upgrade; the
                // device id is what the backup is filed under and does not.
                if (entry.nodeName != null) {
                    Text(
                        "!%08x".format(entry.nodeNum),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                entry.deviceId?.let { id ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Verified, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(12.dp))
                        Text(
                            id,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
                Text(
                    "$date \u2022 $time",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Text(
                Formatter.formatFileSize(context, entry.fileSize),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Restore") },
                leadingIcon = { Icon(Icons.Outlined.Restore, contentDescription = null) },
                onClick = { menuOpen = false; onRestore() },
            )
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                onClick = { menuOpen = false; onDelete() },
            )
        }
    }
}
