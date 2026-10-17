package com.suteny0r.mangledbabyducks.net

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Copies a picture out of the app and into the device's photo library.
 *
 * The app's own copy lives in `filesDir` and disappears with the app, which is right for
 * message content and wrong for a photograph someone wants to keep. This is the way out.
 *
 * On Android 10 and later this needs no permission at all: `MediaStore` grants an app
 * write access to the collection entry it created. Below that it needs
 * `WRITE_EXTERNAL_STORAGE`, which is why the manifest declares it with `maxSdkVersion=28`
 * and the caller asks for it only on those versions.
 */
object PictureSaver {

    private const val TAG = "PictureSaver"
    private const val ALBUM = "Mangled Baby Ducks"

    sealed interface Result {
        data object Saved : Result
        data class Failed(val message: String) : Result
    }

    /** True when the caller must hold WRITE_EXTERNAL_STORAGE before [save] will work. */
    val needsLegacyPermission: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    suspend fun save(context: Context, source: File, name: String): Result =
        withContext(Dispatchers.IO) {
            if (!source.isFile || source.length() == 0L) {
                return@withContext Result.Failed("That picture is no longer stored.")
            }
            runCatching {
                // The stored bytes carry no extension, so ask the decoder what they are
                // rather than asserting JPEG and writing a file that lies about itself.
                val type = sniffMimeType(source) ?: "image/jpeg"
                val extension = type.substringAfter('/').let { if (it == "jpeg") "jpg" else it }
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "$name.$extension")
                    put(MediaStore.Images.Media.MIME_TYPE, type)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(
                            MediaStore.Images.Media.RELATIVE_PATH,
                            "${Environment.DIRECTORY_PICTURES}/$ALBUM",
                        )
                        // Hidden from the gallery until the bytes are all there, so a
                        // half-written picture never shows up.
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return@withContext Result.Failed("Could not add it to your photos.")
                resolver.openOutputStream(uri).use { out ->
                    if (out == null) return@withContext Result.Failed("Could not write the picture.")
                    source.inputStream().use { it.copyTo(out) }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                Result.Saved
            }.getOrElse { e ->
                Log.w(TAG, "Save failed", e)
                Result.Failed("Could not save that picture.")
            }
        }

    private fun sniffMimeType(file: File): String? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        file.inputStream().use { BitmapFactory.decodeStream(it, null, options) }
        return options.outMimeType
    }
}
