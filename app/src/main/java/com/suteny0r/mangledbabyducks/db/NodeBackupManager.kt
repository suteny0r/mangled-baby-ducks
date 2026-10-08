package com.suteny0r.mangledbabyducks.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** BackupModels.swift BackupEntry: one radio's database snapshot. */
data class BackupEntry(
    val nodeNum: Long,
    val nodeName: String?,
    val createdAt: Long,
    val fileSize: Long,
    /** SHA-256 hex digest of the snapshot's `mesh.db`. */
    val checksum: String,
    /** Directory name under the backup folder, which is the node number. */
    val backupPath: String,
    /**
     * `my_info.radioAddress` of the snapshot, copied into the index so
     * `resolveNodeNum(forPeripheralId:)` does not have to open every backup the way iOS
     * reads `MyInfoEntity.peripheralId` out of each one.
     */
    val radioAddress: String?,
)

/** BackupModels.swift NodeBackupResult. */
sealed interface BackupResult {
    data class Success(val entry: BackupEntry) : BackupResult
    data class Skipped(val reason: String) : BackupResult
    data object NoBackupFound : BackupResult
}

/**
 * Port of NodeBackupManager.swift (+Import): one SQLite snapshot per radio so switching
 * radios can clear the store without losing the previous radio's nodes and messages, and
 * restore the target's own data before its node dump lands.
 *
 * Backups live in the app's external files directory (`Android/data/<pkg>/files/NodeBackups`,
 * reachable over USB and in Files without an export flow, the counterpart of the iOS
 * Documents folder) with an internal fallback when no external storage is mounted. Each
 * backup directory holds a compacted `mesh.db`; `backup-index.json` is the index.
 */
class NodeBackupManager(private val context: Context, private val database: MeshDatabase) {

    private val baseDir: File = resolveBaseDir(context)
    private val indexFile: File get() = File(baseDir, INDEX_FILE_NAME)
    private var entries: MutableMap<Long, BackupEntry> = loadIndex()
    private val lock = Mutex()

    init {
        baseDir.mkdirs()
        sweepStagedDirectories()
        validateIndexConsistency()
    }

    // MARK: - Queries

    fun hasBackup(nodeNum: Long): Boolean = entries.containsKey(nodeNum)

    /** Most recent first. */
    fun listBackups(): List<BackupEntry> = entries.values.sortedByDescending { it.createdAt }

    val totalBackupSize: Long get() = entries.values.sumOf { it.fileSize }

    /** `resolveNodeNum(forPeripheralId:)`: the node a saved radio address was last backed up as. */
    fun resolveNodeNum(radioAddress: String): Long? =
        entries.values.firstOrNull { it.radioAddress == radioAddress }?.nodeNum

    // MARK: - Create

    /**
     * Retries once, like the Swift original (FR-004). The address the backup is keyed on is
     * read from the store itself: a backup made by the foreign-store guard mid-switch, when
     * the saved radio is already the new one, must still be filed under the radio whose
     * data it holds.
     */
    suspend fun createBackup(nodeNum: Long, nodeName: String?): BackupResult =
        lock.withLock {
            val radioAddress = database.myInfoDao().myInfoOnce()
                ?.takeIf { it.myNodeNum == nodeNum }
                ?.radioAddress
            if (!hasSufficientDiskSpace()) {
                Log.w(TAG, "Insufficient disk space for backup of node $nodeNum")
                return@withLock BackupResult.Skipped("Not enough storage for backup")
            }
            var lastError: Exception? = null
            repeat(2) { attempt ->
                try {
                    val entry = performBackup(nodeNum, nodeName, radioAddress)
                    Log.i(TAG, "Backup created for node $nodeNum: ${entry.fileSize} bytes, checksum ${entry.checksum}")
                    return@withLock BackupResult.Success(entry)
                } catch (e: Exception) {
                    lastError = e
                    Log.w(TAG, "Backup attempt ${attempt + 1} failed for node $nodeNum: ${e.message}")
                }
            }
            BackupResult.Skipped("Backup failed: ${lastError?.message ?: "unknown error"}")
        }

    private suspend fun performBackup(nodeNum: Long, nodeName: String?, radioAddress: String?): BackupEntry =
        withContext(Dispatchers.IO) {
            val dir = File(baseDir, nodeNum.toString())
            if (dir.exists()) dir.deleteRecursively()
            dir.mkdirs()

            // Fold the WAL into the main file first so the copy is a complete snapshot.
            database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }

            val source = context.getDatabasePath(DB_NAME)
            val destination = File(dir, DB_NAME)
            source.copyTo(destination, overwrite = true)
            for (sidecar in SIDECARS) {
                val from = File(source.path + sidecar)
                if (from.exists()) from.copyTo(File(destination.path + sidecar), overwrite = true)
            }
            // iOS compacts on a background task after indexing; doing it inline keeps the
            // index's size and checksum describing the file that is actually on disk.
            compact(destination)

            val entry = BackupEntry(
                nodeNum = nodeNum,
                nodeName = nodeName,
                createdAt = System.currentTimeMillis(),
                fileSize = destination.length(),
                checksum = sha256(destination),
                backupPath = dir.name,
                radioAddress = radioAddress,
            )
            entries[nodeNum] = entry
            enforceBackupLimit(keeping = nodeNum)
            saveIndex()
            entry
        }

    /** `runSQLiteCompaction`: checkpoint, leave WAL mode, VACUUM, drop the sidecars. */
    private fun compact(store: File) {
        SQLiteDatabase.openDatabase(store.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            db.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() }
            db.execSQL("VACUUM")
        }
        for (sidecar in SIDECARS) File(store.path + sidecar).delete()
    }

    private fun enforceBackupLimit(keeping: Long) {
        if (entries.size <= MAX_BACKUPS) return
        entries.values
            .filter { it.nodeNum != keeping }
            .sortedBy { it.createdAt }
            .take(entries.size - MAX_BACKUPS)
            .forEach { entry ->
                File(baseDir, entry.backupPath).deleteRecursively()
                entries.remove(entry.nodeNum)
                Log.i(TAG, "Pruned oldest backup for node ${entry.nodeNum} to enforce limit of $MAX_BACKUPS")
            }
    }

    // MARK: - Delete

    fun deleteBackup(nodeNum: Long): Boolean {
        val entry = entries[nodeNum] ?: return false
        File(baseDir, entry.backupPath).deleteRecursively()
        entries.remove(nodeNum)
        saveIndex()
        Log.i(TAG, "Deleted backup for node $nodeNum")
        return true
    }

    // MARK: - Restore

    /**
     * `restoreFromBackup(forNode:into:)`: call after the live database has been cleared.
     * The snapshot is staged into a scratch copy that Room opens, so a pending schema
     * migration runs against the copy and the backup itself is never written; the copy is
     * then attached to the live database and every table is copied row for row, which is
     * what the per-entity import helpers do on iOS.
     */
    suspend fun restoreFromBackup(nodeNum: Long): BackupResult = lock.withLock {
        val entry = entries[nodeNum] ?: return@withLock BackupResult.NoBackupFound
        val store = File(File(baseDir, entry.backupPath), DB_NAME)
        if (!store.exists()) {
            Log.e(TAG, "Backup store file missing for node $nodeNum")
            return@withLock BackupResult.Skipped("Backup file not found")
        }
        withContext(Dispatchers.IO) {
            if (sha256(store) != entry.checksum) {
                Log.e(TAG, "Checksum mismatch for node $nodeNum; backup is corrupt, deleting")
                deleteBackup(nodeNum)
                return@withContext BackupResult.Skipped("Restore failed: Backup file integrity check failed")
            }
            val stage = File(context.cacheDir, "$STAGE_PREFIX${UUID.randomUUID()}").apply { mkdirs() }
            try {
                val staged = File(stage, DB_NAME)
                store.copyTo(staged, overwrite = true)
                // Opening through Room migrates the copy to the live schema, then closing
                // folds its WAL so the attach below reads a plain file.
                MeshDatabase.build(context, staged.absolutePath).apply {
                    openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
                    close()
                }
                importAllTables(staged)
                Log.i(TAG, "Full restore complete for node $nodeNum")
                BackupResult.Success(entry)
            } catch (e: Exception) {
                Log.e(TAG, "Full restore failed for node $nodeNum", e)
                BackupResult.Skipped("Restore failed: ${e.message}")
            } finally {
                stage.deleteRecursively()
            }
        }
    }

    private fun importAllTables(staged: File) {
        val live = database.openHelper.writableDatabase
        live.execSQL("ATTACH DATABASE ? AS backup", arrayOf(staged.absolutePath))
        try {
            for (table in MeshDatabase.TABLES) {
                // Name the columns: a live database that reached this version through ALTER
                // TABLE migrations and a copy that did the same have matching order, but a
                // fresh install does not have to, and SELECT * would misalign them.
                val columns = mutableListOf<String>()
                live.query("PRAGMA table_info(`$table`)").use { cursor ->
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    while (cursor.moveToNext()) columns.add("`${cursor.getString(nameIndex)}`")
                }
                val backupColumns = mutableSetOf<String>()
                live.query("PRAGMA backup.table_info(`$table`)").use { cursor ->
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    while (cursor.moveToNext()) backupColumns.add("`${cursor.getString(nameIndex)}`")
                }
                val shared = columns.filter { it in backupColumns }
                if (shared.isEmpty()) continue
                val list = shared.joinToString(", ")
                live.execSQL("INSERT OR IGNORE INTO `$table` ($list) SELECT $list FROM backup.`$table`")
            }
        } finally {
            live.execSQL("DETACH DATABASE backup")
        }
        // The copy went around Room, so its observers never heard about it: the unread
        // badge stayed at the post-clear zero while the table held one. This is Room's
        // own hook for writes made outside it, and the counterpart of the iOS
        // `databaseResetID` remount that makes every @Query re-fetch after a restore.
        database.invalidationTracker.notifyObserversByTableNames(*MeshDatabase.TABLES.toTypedArray())
    }

    // MARK: - Index

    private fun loadIndex(): MutableMap<Long, BackupEntry> {
        val file = File(resolveBaseDir(context), INDEX_FILE_NAME)
        if (!file.exists()) return mutableMapOf()
        return runCatching {
            val root = JSONObject(file.readText())
            val array = root.optJSONArray("entries") ?: JSONArray()
            val map = mutableMapOf<Long, BackupEntry>()
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val entry = BackupEntry(
                    nodeNum = o.getLong("nodeNum"),
                    nodeName = o.optString("nodeName").ifEmpty { null },
                    createdAt = o.getLong("createdAt"),
                    fileSize = o.getLong("fileSize"),
                    checksum = o.getString("checksum"),
                    backupPath = o.getString("backupPath"),
                    radioAddress = o.optString("radioAddress").ifEmpty { null },
                )
                map[entry.nodeNum] = entry
            }
            map
        }.getOrElse {
            Log.w(TAG, "Backup index unreadable, starting empty", it)
            mutableMapOf()
        }
    }

    private fun saveIndex() {
        val array = JSONArray()
        entries.values.forEach { e ->
            array.put(
                JSONObject().apply {
                    put("nodeNum", e.nodeNum)
                    e.nodeName?.let { put("nodeName", it) }
                    put("createdAt", e.createdAt)
                    put("fileSize", e.fileSize)
                    put("checksum", e.checksum)
                    put("backupPath", e.backupPath)
                    e.radioAddress?.let { put("radioAddress", it) }
                }
            )
        }
        val root = JSONObject().apply {
            put("version", 1)
            put("entries", array)
            put("lastModified", System.currentTimeMillis())
        }
        // Atomic like the Swift `.atomic` write: temp file, then rename over the index.
        val tmp = File(baseDir, "$INDEX_FILE_NAME.tmp")
        runCatching {
            tmp.writeText(root.toString())
            if (!tmp.renameTo(indexFile)) {
                indexFile.delete()
                tmp.renameTo(indexFile)
            }
        }.onFailure { Log.e(TAG, "Failed to save backup index", it) }
    }

    /** T029: drop index entries whose files are gone, and directories the index forgot. */
    private fun validateIndexConsistency() {
        var modified = false
        entries.values.toList().forEach { entry ->
            if (!File(File(baseDir, entry.backupPath), DB_NAME).exists()) {
                Log.w(TAG, "Orphaned index entry for node ${entry.nodeNum}: backup file missing, removing entry")
                entries.remove(entry.nodeNum)
                modified = true
            }
        }
        baseDir.listFiles()?.forEach { item ->
            if (item.name == INDEX_FILE_NAME || item.name == "$INDEX_FILE_NAME.tmp") return@forEach
            val num = item.name.toLongOrNull()
            if (item.isDirectory && num != null && !entries.containsKey(num)) {
                Log.w(TAG, "Orphaned backup directory for node $num, removing")
                item.deleteRecursively()
                modified = true
            }
        }
        if (modified) saveIndex()
    }

    /** `sweepStagedBackupDirectories`: scratch copies an interrupted restore left behind. */
    private fun sweepStagedDirectories() {
        context.cacheDir.listFiles()?.forEach {
            if (it.isDirectory && it.name.startsWith(STAGE_PREFIX)) it.deleteRecursively()
        }
    }

    // MARK: - Helpers

    private fun hasSufficientDiskSpace(): Boolean = runCatching {
        StatFs(baseDir.path).availableBytes > MIN_FREE_BYTES
    }.getOrDefault(true)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "NodeBackup"
        private const val DB_NAME = "mesh.db"
        private const val INDEX_FILE_NAME = "backup-index.json"
        private const val FOLDER_NAME = "NodeBackups"
        private const val STAGE_PREFIX = "staged-backup-"
        private const val MAX_BACKUPS = 50
        /** Minimum free space before a backup is attempted (50 MB, as on iOS). */
        private const val MIN_FREE_BYTES = 50L * 1024 * 1024
        private val SIDECARS = listOf("-wal", "-shm")

        /**
         * The user-reachable folder when external storage is mounted, else app-internal.
         * Mirrors `resolveBackupBaseURL`'s preference for the Files-visible location.
         */
        private fun resolveBaseDir(context: Context): File =
            context.getExternalFilesDir(FOLDER_NAME) ?: File(context.filesDir, FOLDER_NAME)
    }
}
