package com.suteny0r.mangledbabyducks.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
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
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** BackupModels.swift BackupEntry: one radio's database snapshot. */
data class BackupEntry(
    val nodeNum: Long,
    val nodeName: String?,
    val createdAt: Long,
    val fileSize: Long,
    /** SHA-256 hex digest of the snapshot's `mesh.db`. */
    val checksum: String,
    /** Directory name under the backup folder: the entry's [key]. */
    val backupPath: String,
    /**
     * Lowercase hex of the radio's `MyNodeInfo.device_id`, once a connection has learned
     * one. Null for backups taken before this was recorded and for radios that report
     * none; those stay keyed by node number.
     */
    val deviceId: String? = null,
    /**
     * `my_info.radioAddress` of the snapshot, copied into the index so
     * `resolveNodeNum(forPeripheralId:)` does not have to open every backup the way iOS
     * reads `MyInfoEntity.peripheralId` out of each one.
     */
    val radioAddress: String?,
) {
    /**
     * Where the entry sits in the index. Node numbers change on the 2.8 upgrade; the
     * device id does not, so a backup keyed on it survives the renumbering.
     */
    val key: String get() = deviceId ?: nodeNum.toString()
}

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
    private var entries: MutableMap<String, BackupEntry> = loadIndex()
    private val lock = Mutex()

    init {
        baseDir.mkdirs()
        sweepStagedDirectories()
        validateIndexConsistency()
    }

    // MARK: - Queries

    fun hasBackup(nodeNum: Long): Boolean = entryFor(nodeNum) != null

    /** The newest snapshot of this node, whichever key it sits under. */
    fun entryFor(nodeNum: Long): BackupEntry? =
        entries.values.filter { it.nodeNum == nodeNum }.maxByOrNull { it.createdAt }

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
    suspend fun createBackup(nodeNum: Long, deviceId: String?, nodeName: String?): BackupResult =
        lock.withLock {
            // The snapshot is filed under [nodeNum], so the store must actually be that
            // radio's. A cleared store (switch to a radio with no backup, before its dump
            // landed) was once backed up under the previous radio's number, because the
            // caller still held that number: a 100 kB empty snapshot replaced 845 messages.
            val owner = database.myInfoDao().myInfoOnce()
            if (owner == null || owner.myNodeNum != nodeNum) {
                Log.w(TAG, "Refusing to back up node $nodeNum: the store belongs to ${owner?.myNodeNum ?: "nobody"}")
                return@withLock BackupResult.Skipped("The database does not belong to node $nodeNum")
            }
            val radioAddress = owner.radioAddress
            if (!hasSufficientDiskSpace()) {
                Log.w(TAG, "Insufficient disk space for backup of node $nodeNum")
                return@withLock BackupResult.Skipped("Not enough storage for backup")
            }
            var lastError: Exception? = null
            repeat(2) { attempt ->
                try {
                    val entry = performBackup(nodeNum, deviceId, nodeName, radioAddress)
                    Log.i(TAG, "Backup created for node $nodeNum: ${entry.fileSize} bytes, checksum ${entry.checksum}")
                    return@withLock BackupResult.Success(entry)
                } catch (e: Exception) {
                    lastError = e
                    Log.w(TAG, "Backup attempt ${attempt + 1} failed for node $nodeNum: ${e.message}")
                }
            }
            BackupResult.Skipped("Backup failed: ${lastError?.message ?: "unknown error"}")
        }

    private suspend fun performBackup(nodeNum: Long, deviceId: String?, nodeName: String?, radioAddress: String?): BackupEntry =
        withContext(Dispatchers.IO) {
            // Key by device id when one is known, else by whatever this node was last filed
            // under; a node-number key beside a device-keyed one would be a second backup
            // of the same radio.
            val deviceKey = deviceId ?: entries.values.firstOrNull { it.nodeNum == nodeNum }?.deviceId
            val key = deviceKey ?: nodeNum.toString()
            val dir = File(baseDir, key)
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
                deviceId = deviceKey,
                radioAddress = radioAddress,
            )
            // A legacy node-number entry for this same radio is superseded, not kept.
            entries.values.filter { it.key != key && it.nodeNum == nodeNum }.forEach { stale ->
                File(baseDir, stale.backupPath).deleteRecursively()
                entries.remove(stale.key)
            }
            entries[key] = entry
            enforceBackupLimit(keeping = key)
            saveIndex()
            entry
        }

    /**
     * `adoptLegacyBackups`: on connect, move this radio's node-number-keyed backup onto its
     * device id so the next renumbering does not orphan it. Candidates are the entries
     * with no device id that carry this node number or this radio address; the newest
     * survives under the device key and the rest go.
     */
    suspend fun adoptLegacyBackups(deviceId: String?, nodeNum: Long, radioAddress: String?) {
        if (deviceId == null) return
        lock.withLock {
            withContext(Dispatchers.IO) {
                val legacy = entries.values.filter { e ->
                    e.deviceId == null && (e.nodeNum == nodeNum || (radioAddress != null && e.radioAddress == radioAddress))
                }
                if (legacy.isEmpty()) return@withContext
                val candidates = legacy + listOfNotNull(entries[deviceId])
                val survivor = candidates.maxByOrNull { it.createdAt } ?: return@withContext
                val target = File(baseDir, deviceId)
                if (survivor.key != deviceId) {
                    target.deleteRecursively()
                    val moved = File(baseDir, survivor.backupPath).renameTo(target)
                    if (!moved) {
                        Log.w(TAG, "Could not move backup ${survivor.key} onto device key $deviceId")
                        return@withContext
                    }
                    entries.remove(survivor.key)
                    entries[deviceId] = survivor.copy(deviceId = deviceId, backupPath = deviceId)
                }
                for (other in candidates) {
                    if (other.key == survivor.key) continue
                    File(baseDir, other.backupPath).deleteRecursively()
                    entries.remove(other.key)
                    Log.i(TAG, "Removed backup ${other.key}: duplicate of $deviceId from an earlier node number")
                }
                saveIndex()
                Log.i(TAG, "Backup for node $nodeNum now keyed by device $deviceId")
            }
        }
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

    private fun enforceBackupLimit(keeping: String) {
        if (entries.size <= MAX_BACKUPS) return
        entries.values
            .filter { it.key != keeping }
            .sortedBy { it.createdAt }
            .take(entries.size - MAX_BACKUPS)
            .forEach { entry ->
                File(baseDir, entry.backupPath).deleteRecursively()
                entries.remove(entry.key)
                Log.i(TAG, "Pruned oldest backup ${entry.key} to enforce limit of $MAX_BACKUPS")
            }
    }

    // MARK: - Delete

    fun deleteBackup(key: String): Boolean {
        val entry = entries[key] ?: return false
        File(baseDir, entry.backupPath).deleteRecursively()
        entries.remove(key)
        saveIndex()
        Log.i(TAG, "Deleted backup $key")
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
        // By node number rather than key: this radio's backup may still sit under its
        // node number, or under its device id after an adoption.
        val entry = entryFor(nodeNum) ?: return@withLock BackupResult.NoBackupFound
        val store = File(File(baseDir, entry.backupPath), DB_NAME)
        if (!store.exists()) {
            Log.e(TAG, "Backup store file missing for node $nodeNum")
            return@withLock BackupResult.Skipped("Backup file not found")
        }
        withContext(Dispatchers.IO) {
            if (sha256(store) != entry.checksum) {
                Log.e(TAG, "Checksum mismatch for node $nodeNum; backup is corrupt, deleting")
                deleteBackup(entry.key)
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

    /**
     * Copy every table of the staged snapshot into the live store, row by row, through
     * Room's own connection.
     *
     * Not `ATTACH DATABASE`: Android's SQLiteDatabase answers an ATTACH by calling
     * `disableWriteAheadLogging()`, and leaving WAL makes the connection pool close and
     * reopen its connections. Room's invalidation tracker lives in TEMP objects on that
     * connection (`room_table_modification_log` and one trigger per table), so the first
     * restore silently killed every observer in the process: "Cannot run invalidation
     * tracker" on each refresh, and a sent message only appeared after the thread was
     * reopened. Inserts through Room's connection fire the triggers like any other write.
     */
    private fun importAllTables(staged: File) {
        val live = database.openHelper.writableDatabase
        SQLiteDatabase.openDatabase(staged.path, null, SQLiteDatabase.OPEN_READONLY).use { backup ->
            database.runInTransaction {
                for (table in MeshDatabase.TABLES) {
                    // Name the columns: a fresh install and a store that reached this version
                    // through ALTER TABLE migrations need not order them the same way.
                    val liveColumns = mutableSetOf<String>()
                    live.query("PRAGMA table_info(`$table`)").use { cursor ->
                        val nameIndex = cursor.getColumnIndexOrThrow("name")
                        while (cursor.moveToNext()) liveColumns.add(cursor.getString(nameIndex))
                    }
                    val backupColumns = mutableListOf<String>()
                    backup.rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
                        val nameIndex = cursor.getColumnIndexOrThrow("name")
                        while (cursor.moveToNext()) backupColumns.add(cursor.getString(nameIndex))
                    }
                    val shared = backupColumns.filter { it in liveColumns }
                    if (shared.isEmpty()) continue
                    val select = shared.joinToString(", ") { "`$it`" }
                    // ContentValues keys go into the INSERT verbatim, so they carry their
                    // own quotes: `channels.index` is a reserved word.
                    val keys = shared.map { "`$it`" }
                    backup.rawQuery("SELECT $select FROM `$table`", null).use { cursor ->
                        while (cursor.moveToNext()) {
                            val values = ContentValues(shared.size)
                            for (i in shared.indices) {
                                when (cursor.getType(i)) {
                                    Cursor.FIELD_TYPE_NULL -> values.putNull(keys[i])
                                    Cursor.FIELD_TYPE_INTEGER -> values.put(keys[i], cursor.getLong(i))
                                    Cursor.FIELD_TYPE_FLOAT -> values.put(keys[i], cursor.getDouble(i))
                                    Cursor.FIELD_TYPE_BLOB -> values.put(keys[i], cursor.getBlob(i))
                                    else -> values.put(keys[i], cursor.getString(i))
                                }
                            }
                            // INSERT OR IGNORE, as the iOS import does per entity.
                            live.insert("`$table`", SQLiteDatabase.CONFLICT_IGNORE, values)
                        }
                    }
                }
            }
        }
        // Belt and braces for anything the triggers did not cover: the counterpart of the
        // iOS `databaseResetID` remount that makes every @Query re-fetch after a restore.
        database.invalidationTracker.notifyObserversByTableNames(*MeshDatabase.TABLES.toTypedArray())
    }

    // MARK: - Export / import

    /**
     * Not in the Swift original: on iOS the backup folder is visible in the Files app, so
     * the user can copy it out before deleting the app. Android hides `Android/data`, and
     * an uninstall deletes it, so this is the way to carry snapshots across a reinstall. One
     * zip: `backup-index.json` plus `<nodeNum>/mesh.db` per entry. Returns the entry count.
     */
    suspend fun exportArchive(out: OutputStream): Int = lock.withLock {
        withContext(Dispatchers.IO) {
            ZipOutputStream(out.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(INDEX_FILE_NAME))
                indexFile.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                entries.values.forEach { entry ->
                    val db = File(File(baseDir, entry.backupPath), DB_NAME)
                    if (!db.exists()) return@forEach
                    zip.putNextEntry(ZipEntry("${entry.backupPath}/$DB_NAME"))
                    db.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            entries.size
        }
    }

    data class ImportSummary(val imported: Int, val skipped: Int)

    /**
     * The reverse of [exportArchive]: unpack into a staged folder, verify each entry's
     * checksum, then move the verified snapshots in. An entry for a node that already has
     * a backup replaces it only when the archive's copy is newer. Nothing in the live
     * database changes; restoring stays a separate, explicit step.
     */
    suspend fun importArchive(input: InputStream): ImportSummary = lock.withLock {
        withContext(Dispatchers.IO) {
            val stage = File(context.cacheDir, STAGE_PREFIX + UUID.randomUUID()).apply { mkdirs() }
            try {
                ZipInputStream(input.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val name = entry.name.replace('\\', '/')
                        // Only the two shapes we wrote; anything else (including path
                        // escapes) is ignored rather than written.
                        val ok = name == INDEX_FILE_NAME ||
                            (name.endsWith("/$DB_NAME") && name.count { it == '/' } == 1 &&
                                name.substringBefore('/').all { it.isLetterOrDigit() })
                        if (ok && !entry.isDirectory) {
                            val target = File(stage, name)
                            target.parentFile?.mkdirs()
                            target.outputStream().use { zip.copyTo(it) }
                        }
                        zip.closeEntry()
                    }
                }
                val indexIn = File(stage, INDEX_FILE_NAME)
                if (!indexIn.exists()) throw IllegalArgumentException("The file is not a backup archive (no $INDEX_FILE_NAME)")
                val root = JSONObject(indexIn.readText())
                val array = root.optJSONArray("entries") ?: JSONArray()
                var imported = 0
                var skipped = 0
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    val nodeNum = o.getLong("nodeNum")
                    val backupPath = o.getString("backupPath")
                    val createdAt = o.getLong("createdAt")
                    val checksum = o.getString("checksum")
                    val deviceId = o.optString("deviceId").ifEmpty { null }
                    val key = deviceId ?: nodeNum.toString()
                    val db = File(File(stage, backupPath), DB_NAME)
                    if (!db.exists() || sha256(db) != checksum) {
                        Log.w(TAG, "Import: snapshot $key missing or checksum mismatch, skipped")
                        skipped++
                        continue
                    }
                    val existing = entries[key] ?: entryFor(nodeNum)
                    if (existing != null && existing.createdAt >= createdAt) {
                        Log.i(TAG, "Import: existing backup for node $nodeNum is as new or newer, skipped")
                        skipped++
                        continue
                    }
                    val dir = File(baseDir, key)
                    dir.deleteRecursively()
                    dir.mkdirs()
                    db.copyTo(File(dir, DB_NAME), overwrite = true)
                    entries.values.filter { it.key != key && it.nodeNum == nodeNum }.forEach { stale ->
                        File(baseDir, stale.backupPath).deleteRecursively()
                        entries.remove(stale.key)
                    }
                    entries[key] = BackupEntry(
                        nodeNum = nodeNum,
                        nodeName = o.optString("nodeName").ifEmpty { null },
                        createdAt = createdAt,
                        fileSize = db.length(),
                        checksum = checksum,
                        backupPath = key,
                        deviceId = deviceId,
                        radioAddress = o.optString("radioAddress").ifEmpty { null },
                    )
                    imported++
                }
                if (imported > 0) saveIndex()
                ImportSummary(imported, skipped)
            } finally {
                stage.deleteRecursively()
            }
        }
    }

    // MARK: - Index

    private fun loadIndex(): MutableMap<String, BackupEntry> {
        val file = File(resolveBaseDir(context), INDEX_FILE_NAME)
        if (!file.exists()) return mutableMapOf()
        return runCatching {
            val root = JSONObject(file.readText())
            val array = root.optJSONArray("entries") ?: JSONArray()
            val map = mutableMapOf<String, BackupEntry>()
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                // Version 1 indexes carry no deviceId; their entries keep their node-number
                // keys until the radio reconnects and adoptLegacyBackups moves them.
                val entry = BackupEntry(
                    nodeNum = o.getLong("nodeNum"),
                    nodeName = o.optString("nodeName").ifEmpty { null },
                    createdAt = o.getLong("createdAt"),
                    fileSize = o.getLong("fileSize"),
                    checksum = o.getString("checksum"),
                    backupPath = o.getString("backupPath"),
                    deviceId = o.optString("deviceId").ifEmpty { null },
                    radioAddress = o.optString("radioAddress").ifEmpty { null },
                )
                map[entry.key] = entry
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
                    e.deviceId?.let { put("deviceId", it) }
                    e.radioAddress?.let { put("radioAddress", it) }
                }
            )
        }
        val root = JSONObject().apply {
            put("version", 2)
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
                Log.w(TAG, "Orphaned index entry ${entry.key}: backup file missing, removing entry")
                entries.remove(entry.key)
                modified = true
            }
        }
        val referenced = entries.values.map { it.backupPath }.toSet()
        baseDir.listFiles()?.forEach { item ->
            if (item.name == INDEX_FILE_NAME || item.name == "$INDEX_FILE_NAME.tmp") return@forEach
            if (item.isDirectory && item.name !in referenced) {
                Log.w(TAG, "Orphaned backup directory ${item.name}, removing")
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
