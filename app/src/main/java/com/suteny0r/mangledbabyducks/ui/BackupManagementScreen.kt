package com.suteny0r.mangledbabyducks.ui

import android.app.Application
import android.text.format.DateFormat
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
                when (val result = container.backups.createBackup(nodeNum, name, container.rememberedRadio()?.address)) {
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
        container.backups.deleteBackup(entry.nodeNum)
        refresh()
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
    var pendingDelete by remember { mutableStateOf<BackupEntry?>(null) }
    LaunchedEffect(Unit) { vm.refresh() }

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
                Text(entry.displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    "$date • $time",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
