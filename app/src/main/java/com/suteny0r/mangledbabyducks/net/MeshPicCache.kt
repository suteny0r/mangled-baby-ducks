package com.suteny0r.mangledbabyducks.net

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Keeps a permanent local copy of every meshpic picture this device has seen.
 *
 * meshpic.org deletes an image after its retention window, 24 h by default. The link in the
 * message outlives the picture, so without this a conversation quietly rots: yesterday's
 * photographs become dead URLs. The picture is part of the message, so it is stored like
 * the message — in `filesDir`, not in a cache Android may evict, and not in Coil's bounded
 * LRU either.
 *
 * Fetching is idempotent and deduplicated: several bubbles asking for the same short id at
 * once share one download.
 */
object MeshPicCache {

    private const val TAG = "MeshPicCache"
    /** Comfortably above anything the uploader produces, and a guard against a bad URL. */
    private const val MAX_IMAGE_BYTES = 12L * 1024 * 1024
    /** Total on disk before the oldest are dropped. Pictures are message content, so this
     *  is generous; it exists to stop an unbounded directory, not to ration. */
    private const val MAX_STORE_BYTES = 256L * 1024 * 1024
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    private val locks = mutableMapOf<String, Mutex>()
    private val locksGuard = Mutex()

    private fun dir(context: Context) = File(context.filesDir, "meshpic").apply { mkdirs() }

    fun fileFor(context: Context, shortId: String) = File(dir(context), "$shortId.img")

    /** The stored picture if it is already here, without touching the network. */
    fun cached(context: Context, shortId: String): File? =
        fileFor(context, shortId).takeIf { it.isFile && it.length() > 0 }

    /**
     * The stored picture, fetching it once if this device has not seen it before. Returns
     * null only when it is neither stored nor still on the server, which means it expired
     * before anyone here looked at it.
     */
    suspend fun get(context: Context, shortId: String): File? {
        cached(context, shortId)?.let { return it }
        val lock = locksGuard.withLock { locks.getOrPut(shortId) { Mutex() } }
        return lock.withLock {
            // Another caller may have finished while this one waited.
            cached(context, shortId) ?: download(context, shortId)
        }
    }

    private suspend fun download(context: Context, shortId: String): File? =
        withContext(Dispatchers.IO) {
            val target = fileFor(context, shortId)
            // Written aside and renamed, so an interrupted download can never be mistaken
            // for a stored picture on the next launch.
            val partial = File(target.parentFile, "${target.name}.part")
            val conn = (URL("https://meshpic.org/image/$shortId").openConnection()
                as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", MeshPicUploader.USER_AGENT)
                setRequestProperty("Accept", "image/*")
            }
            try {
                if (conn.responseCode !in 200..299) {
                    // 404 is the ordinary end of a picture's life, not a fault.
                    Log.i(TAG, "$shortId unavailable (HTTP ${conn.responseCode})")
                    return@withContext null
                }
                if (conn.contentType?.startsWith("image/") != true) {
                    Log.w(TAG, "$shortId returned ${conn.contentType}, not an image")
                    return@withContext null
                }
                partial.outputStream().use { out ->
                    var total = 0L
                    val buffer = ByteArray(16 * 1024)
                    conn.inputStream.use { input ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_IMAGE_BYTES) {
                                Log.w(TAG, "$shortId exceeded $MAX_IMAGE_BYTES bytes; abandoning")
                                return@withContext null
                            }
                            out.write(buffer, 0, read)
                        }
                    }
                }
                if (partial.length() == 0L) return@withContext null
                if (!partial.renameTo(target)) return@withContext null
                prune(context)
                target
            } catch (e: Exception) {
                Log.w(TAG, "Could not fetch $shortId: ${e.message}")
                null
            } finally {
                runCatching { partial.delete() }
                conn.disconnect()
            }
        }

    /** Oldest first, until the store is back under its ceiling. */
    private fun prune(context: Context) {
        runCatching {
            val files = dir(context).listFiles()?.filter { it.isFile } ?: return
            var total = files.sumOf { it.length() }
            if (total <= MAX_STORE_BYTES) return
            files.sortedBy { it.lastModified() }.forEach { file ->
                if (total <= MAX_STORE_BYTES) return
                total -= file.length()
                file.delete()
            }
        }
    }
}
