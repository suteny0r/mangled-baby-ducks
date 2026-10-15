package com.suteny0r.mangledbabyducks.diagnostics

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Bundles this app's own log into a zip the user can send from the share sheet.
 *
 * No Swift counterpart: iOS has sysdiagnose and Android has nothing a tester can reach
 * without a cable. Every BLE fault written up in HANDOFF.md was diagnosed from `adb logcat`
 * on a phone that happened to be plugged into this machine; this is the same evidence from
 * a phone that is not.
 *
 * Scope is the caller's own UID. Android has restricted logcat that way since 4.1, so this
 * cannot read the system log or any other app's, and no READ_LOGS permission is requested.
 * The UI must say so rather than promising a full device log.
 */
object LogExport {
    private const val TAG = "LogExport"

    /** Enough history to cover a connect/drop/retry cycle without producing a huge file. */
    private const val MAIN_LINES = "20000"
    private const val CRASH_LINES = "2000"

    /**
     * Write a fresh diagnostic zip and return it. Call off the main thread; it shells out
     * to logcat and does file IO.
     */
    suspend fun writeZip(context: Context): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "logs").apply { mkdirs() }
        // Keep exactly one: these are a few MB each and a tester may export repeatedly
        // while chasing one fault. The share grant only ever points at the newest.
        dir.listFiles()?.forEach { it.delete() }

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val zip = File(dir, "mangled-baby-ducks-log-$stamp.zip")

        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            out.putNextEntry(ZipEntry("device.txt"))
            out.write(deviceReport(context).toByteArray())
            out.closeEntry()

            out.putNextEntry(ZipEntry("logcat.txt"))
            copyLogcat(out, "-d", "-v", "threadtime", "-t", MAIN_LINES)
            out.closeEntry()

            out.putNextEntry(ZipEntry("crash.txt"))
            copyLogcat(out, "-d", "-b", "crash", "-v", "threadtime", "-t", CRASH_LINES)
            out.closeEntry()
        }
        Log.i(TAG, "Wrote ${zip.name} (${zip.length()} bytes)")
        zip
    }

    /**
     * The share sheet intent for [file]. The read grant rides on the Intent and expires
     * with it, so the receiving app never gets standing access to the cache directory.
     */
    fun shareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Mangled Baby Ducks diagnostic log")
            putExtra(
                Intent.EXTRA_TEXT,
                "Diagnostic log from Mangled Baby Ducks ${versionName(context)}.\n" +
                    "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}.",
            )
            // ClipData as well as EXTRA_STREAM: the grant rides on the clip, and some OEM
            // share sheets hand the target an Intent whose extras survived but whose
            // permission did not, which the receiving app sees as a file it cannot open.
            clipData = ClipData.newUri(context.contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // The chooser is the Activity that is actually started, so it needs the flag too.
        return Intent.createChooser(send, "Send diagnostic log")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /**
     * Stream logcat straight into the zip. The process output has to be drained as it is
     * produced; buffering it into a String first is what turns a long log into an OOM.
     */
    private fun copyLogcat(out: ZipOutputStream, vararg args: String) {
        val process = try {
            ProcessBuilder(listOf("logcat", *args)).redirectErrorStream(true).start()
        } catch (e: Exception) {
            // Some OEM builds refuse to exec logcat at all. Say so in the file rather than
            // failing the whole export, so the rest of the bundle still reaches the dev.
            Log.w(TAG, "Could not run logcat", e)
            out.write("logcat could not be run on this device: ${e.message}\n".toByteArray())
            return
        }
        try {
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                val buffer = CharArray(8192)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    out.write(String(buffer, 0, read).toByteArray())
                }
            }
            process.waitFor()
        } finally {
            process.destroy()
        }
    }

    private fun deviceReport(context: Context): String = buildString {
        appendLine("Mangled Baby Ducks diagnostic report")
        appendLine("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
        appendLine()
        appendLine("App version:   ${versionName(context)} (${versionCode(context)})")
        appendLine("Package:       ${context.packageName}")
        appendLine()
        appendLine("Manufacturer:  ${Build.MANUFACTURER}")
        appendLine("Model:         ${Build.MODEL}")
        appendLine("Device:        ${Build.DEVICE}")
        appendLine("Android:       ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Build:         ${Build.DISPLAY}")
        appendLine("ABIs:          ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine()
        appendLine("logcat.txt holds this app's own log only; Android does not let an app")
        appendLine("read the system log or another app's.")
    }

    private fun versionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    private fun versionCode(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toString()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toString()
        }
    }.getOrDefault("?")
}
