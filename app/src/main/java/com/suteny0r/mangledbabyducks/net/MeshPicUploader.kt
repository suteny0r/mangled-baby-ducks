package com.suteny0r.mangledbabyducks.net

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Uploads a picture to meshpic.org and returns the short link to paste into a message.
 *
 * meshpic.org is a third-party service for sharing images over Meshtastic: an image goes up,
 * a compact link comes back, and the image is deleted after a retention window (24 h by
 * default). The link is what travels over the mesh, because a 200-byte text packet can
 * obviously not carry a photograph.
 *
 * **The service publishes no API.** Everything here was read off the site's own uploader:
 * `POST /upload`, multipart field `file`, with an `X-CSRF-Token` taken from the `<meta>` tag
 * on the landing page and the session cookie that request sets. It can change without
 * notice, so every failure path here ends in a plain message to the user rather than a
 * crash, and the caller must treat an upload as something that routinely does not work.
 *
 * The site rate-limits uploads. One upload per deliberate user action is the only use here;
 * nothing retries automatically. The User-Agent names this app so the operator can see who
 * is calling and get in touch if they would rather we did not.
 */
object MeshPicUploader {

    private const val TAG = "MeshPic"
    private const val BASE = "https://meshpic.org"
    private const val UA = "MangledBabyDucks/1.0 (+https://github.com/suteny0r/mangled-baby-ducks)"

    /** The service's own ceiling. We aim well under it; see [encode]. */
    private const val SERVICE_MAX_BYTES = 10 * 1024 * 1024
    private const val TARGET_MAX_BYTES = 4 * 1024 * 1024
    /** Longest edge after downscaling. Plenty for a phone screen, far off the size limit. */
    private const val MAX_EDGE = 2048

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000

    sealed interface Result {
        data class Success(val link: String) : Result
        data class Failure(val message: String) : Result
    }

    /**
     * Read [uri], shrink it, upload it, and return the link. Safe to call from a ViewModel
     * scope; it does its own thread switching and never throws.
     */
    suspend fun upload(context: Context, uri: Uri): Result = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = encode(context, uri)
                ?: return@withContext Result.Failure("That image could not be read.")
            if (bytes.size > SERVICE_MAX_BYTES) {
                return@withContext Result.Failure("That image is too large to share.")
            }
            val session = openSession()
                ?: return@withContext Result.Failure("Could not reach meshpic.org.")
            post(session, bytes)
        }.getOrElse { e ->
            Log.w(TAG, "Upload failed", e)
            Result.Failure(friendly(e))
        }
    }

    private data class Session(val csrf: String, val cookies: String)

    /**
     * Establish a session and take a CSRF token that validates against it.
     *
     * **Two requests, and it does not work with one.** The token is bound to the session,
     * and the first GET mints the session in the very response that carries the token, so
     * that pair is always rejected with 403. Fetching the page a second time while sending
     * the cookie yields a token the server will accept. Verified against the live service:
     * one GET gives 403 on upload, two gives 201.
     */
    private fun openSession(): Session? {
        val cookies = get(null)?.second ?: return null
        if (cookies.isEmpty()) {
            Log.w(TAG, "No session cookie from meshpic.org")
            return null
        }
        val csrf = get(cookies)?.first ?: return null
        if (csrf.isEmpty()) {
            Log.w(TAG, "No csrf-token in the page; the site has probably changed")
            return null
        }
        return Session(csrf, cookies)
    }

    /** One GET of the landing page: returns its CSRF token and any cookie it set. */
    private fun get(cookies: String?): Pair<String, String>? {
        val conn = (URL("$BASE/").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            browserHeaders()
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
            if (!cookies.isNullOrEmpty()) setRequestProperty("Cookie", cookies)
        }
        try {
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "Landing page returned ${conn.responseCode}")
                return null
            }
            val html = conn.inputStream.bufferedReader().use { it.readText() }
            // Only the name=value pairs; attributes such as Path and HttpOnly are the
            // browser's business and must not be echoed back.
            val set = conn.headerFields["Set-Cookie"].orEmpty()
                .mapNotNull { it.substringBefore(';').trim().takeIf(String::isNotEmpty) }
                .joinToString("; ")
            return (CSRF.find(html)?.groupValues?.get(1) ?: "") to set.ifEmpty { cookies.orEmpty() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * The service is a browser form and filters on looking like one. The combination below
     * is what actually completed an upload during development; a bare request with only the
     * cookie and token was refused. The User-Agent still names this app, so the operator can
     * see who is calling and ask us to stop if they would rather we did not.
     */
    private fun HttpURLConnection.browserHeaders() {
        setRequestProperty("User-Agent", UA)
        setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        setRequestProperty("Referer", "$BASE/")
        setRequestProperty("Origin", BASE)
        setRequestProperty("Sec-Fetch-Site", "same-origin")
        setRequestProperty("Sec-Fetch-Mode", "cors")
        setRequestProperty("Sec-Fetch-Dest", "empty")
    }

    private fun post(session: Session, image: ByteArray): Result {
        val boundary = "----MangledBabyDucks${UUID.randomUUID().toString().replace("-", "")}"
        val conn = (URL("$BASE/upload").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            browserHeaders()
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("X-CSRF-Token", session.csrf)
            // Both headers come straight from the site's own uploader.
            setRequestProperty("X-Meshpic-UI", "1")
            if (session.cookies.isNotEmpty()) setRequestProperty("Cookie", session.cookies)
            setFixedLengthStreamingMode(multipartLength(boundary, image.size))
        }
        try {
            DataOutputStream(conn.outputStream.buffered()).use { out ->
                out.writeBytes(partHeader(boundary))
                out.write(image)
                out.writeBytes(partFooter(boundary))
                out.flush()
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val body = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }
                    .getOrNull()
                Log.w(TAG, "Upload returned $code: ${body?.take(200)}")
                return Result.Failure(httpMessage(code))
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val shortId = json.optString("short_id").takeIf { it.isNotEmpty() }
                ?: return Result.Failure("meshpic.org did not return a link.")
            return Result.Success("$BASE/$shortId")
        } finally {
            conn.disconnect()
        }
    }

    private fun partHeader(boundary: String) = buildString {
        append("--").append(boundary).append("\r\n")
        append("Content-Disposition: form-data; name=\"file\"; filename=\"image.jpg\"\r\n")
        append("Content-Type: image/jpeg\r\n\r\n")
    }

    private fun partFooter(boundary: String) = "\r\n--$boundary--\r\n"

    private fun multipartLength(boundary: String, imageSize: Int): Long =
        partHeader(boundary).length.toLong() + imageSize + partFooter(boundary).length

    /**
     * Decode, downscale and re-encode as JPEG.
     *
     * Phone photographs routinely exceed the service's 10 MB limit, and a mesh user has no
     * use for a 12-megapixel original behind a link they will open on a phone. Decoding is
     * subsampled rather than full-size-then-scaled so a large image never has to fit in
     * memory whole. EXIF rotation is applied, because stripping it silently is how photos
     * end up sideways.
     */
    private fun encode(context: Context, uri: Uri): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
        }
        val decoded = context.contentResolver.openInputStream(uri)
            ?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return null

        val rotated = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                orient(decoded, ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                ))
            }
        }.getOrNull() ?: decoded

        // Step the quality down rather than guessing one: a photograph and a screenshot of
        // flat colour compress nothing alike.
        for (quality in intArrayOf(85, 70, 55, 40)) {
            val out = ByteArrayOutputStream()
            rotated.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (out.size() <= TARGET_MAX_BYTES) return out.toByteArray()
        }
        val out = ByteArrayOutputStream()
        rotated.compress(Bitmap.CompressFormat.JPEG, 30, out)
        return out.toByteArray()
    }

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (maxOf(width, height) / sample > MAX_EDGE) sample *= 2
        return sample
    }

    private fun orient(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = android.graphics.Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }

    private fun httpMessage(code: Int) = when (code) {
        400 -> "meshpic.org rejected that file."
        // Observed as the response to repeated uploads from one address, so it reads as
        // their throttle rather than a permission problem the user can do anything about.
        403 -> "meshpic.org refused the upload. Try again in a few minutes."
        413 -> "That image is too large to share."
        429 -> "meshpic.org is rate limiting uploads. Try again shortly."
        in 500..599 -> "meshpic.org is having trouble. Try again later."
        else -> "Upload failed (HTTP $code)."
    }

    private fun friendly(e: Throwable) = when (e) {
        is java.net.UnknownHostException -> "No connection to meshpic.org."
        is java.net.SocketTimeoutException -> "meshpic.org did not respond in time."
        is OutOfMemoryError -> "That image is too large to process."
        else -> "Could not share that image."
    }

    private val CSRF = Regex("""name=["']csrf-token["']\s+content=["']([^"']+)["']""")
}
