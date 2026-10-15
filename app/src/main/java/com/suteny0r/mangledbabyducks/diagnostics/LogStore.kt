package com.suteny0r.mangledbabyducks.diagnostics

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps a rolling copy of this app's log on disk.
 *
 * [LogExport] on its own reads the device ring buffer, and that is not good enough. The
 * buffer is 5 MiB shared by every app on the phone, and on the test Note 20 Ultra it held
 * about twenty minutes one morning and barely two minutes the same afternoon. A user
 * notices a fault, opens Settings and exports, and by then the evidence has rolled off:
 * the first out-of-range report arrived with no BleConnection or RadioManager lines in it
 * at all. This writes the interesting lines to our own files instead, so history survives
 * both buffer churn and the reporting delay.
 *
 * Two capped files are kept and rotated, so the export carries roughly [MAX_FILE_BYTES]
 * to twice that, oldest first.
 */
class LogStore(private val context: Context) {

    private val dir = File(context.filesDir, "diag")
    private val current = File(dir, "log-current.txt")
    private val previous = File(dir, "log-previous.txt")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)

    /** Oldest first, for writing into the export in reading order. */
    fun files(): List<File> = listOf(previous, current).filter { it.exists() && it.length() > 0 }

    /** Idempotent; the capture process lives as long as the app process does. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { capture() }
    }

    private fun capture() {
        runCatching { dir.mkdirs() }
        val process = try {
            // Our own tags at verbose plus everything at warning or above. Without the
            // filter the file fills with ViewRootImpl and BLASTBufferQueue chatter: in one
            // two-minute sample those were 125 of 229 lines, which is what makes an
            // unfiltered capture useless long before it is big.
            ProcessBuilder(
                listOf("logcat", "-v", "threadtime") + TAGS.map { "$it:V" } + "*:W"
            ).redirectErrorStream(true).start()
        } catch (e: Exception) {
            // Not fatal: the export still carries the live dump and the device report.
            Log.w(TAG, "Could not start the rolling log capture", e)
            return
        }

        var out = BufferedOutputStream(FileOutputStream(current, true))
        var size = current.length()
        var sinceFlush = 0
        try {
            process.inputStream.bufferedReader().forEachLine { line ->
                val bytes = (line + "\n").toByteArray()
                out.write(bytes)
                size += bytes.size
                // Flushed often on purpose. The process can be killed without warning, and
                // the lines worth having are the last ones before whatever went wrong.
                if (++sinceFlush >= FLUSH_EVERY_LINES) {
                    out.flush()
                    sinceFlush = 0
                }
                if (size >= MAX_FILE_BYTES) {
                    out.flush()
                    out.close()
                    rotate()
                    out = BufferedOutputStream(FileOutputStream(current, false))
                    size = 0
                    sinceFlush = 0
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Rolling log capture stopped: ${e.message}")
        } finally {
            runCatching { out.flush() }
            runCatching { out.close() }
            runCatching { process.destroy() }
        }
    }

    private fun rotate() {
        runCatching {
            if (previous.exists()) previous.delete()
            current.renameTo(previous)
        }
    }

    companion object {
        private const val TAG = "LogStore"
        private const val MAX_FILE_BYTES = 2L * 1024 * 1024
        private const val FLUSH_EVERY_LINES = 20

        /**
         * The app's own tags. Anything not listed is still captured at warning and above,
         * which is where the Bluetooth stack's own complaints land.
         */
        private val TAGS = listOf(
            "AppContainer",
            "BleConnection",
            "HardwareCatalog",
            "LanScanner",
            "LocationSharer",
            "LogExport",
            "LogStore",
            "MainWatchdog",
            "NodeBackup",
            "PacketIngest",
            "RadioManager",
        )
    }
}
