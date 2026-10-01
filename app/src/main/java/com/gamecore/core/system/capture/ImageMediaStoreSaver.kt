package com.gamecore.core.system.capture

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.common.TextSanitizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes one extracted-and-cropped [Bitmap] to the gallery, and hands back a shareable `content://` URI.
 *
 * This is the image sibling of [com.gamecore.core.system.replay.save.ReplayClipSaver], built on exactly the
 * same two-path model so screen extraction stores its output the way replay stores clips:
 *
 *  * **API 29+** — insert a `MediaStore.Images.Media` row under `Pictures/GameCore` with `IS_PENDING = 1`,
 *    stream the PNG in, clear the flag, confirm a non-zero size, and delete the row on any failure so no
 *    half-written ghost is left in the gallery. No storage permission is needed for this.
 *  * **API 26–28** — where scoped MediaStore writes are unavailable, write the PNG into the app's own
 *    `files/captures/` (the directory screenshots already use, so it is already `FileProvider`-mapped in
 *    `res/xml/file_paths.xml`) and vend a `FileProvider` grant. Still no storage permission.
 *
 * Returns the URI on success or `null` on any failure — never a fabricated success. Nothing is logged: a
 * failure surfaces only as `null`, so no path or pixel data can reach a release logcat.
 *
 * The caller crops: it hands in the already-cropped bitmap (the view-model resolves the crop through
 * [CropMath] and the controller applies it with `Bitmap.createBitmap`), and this class only persists it.
 */
@Singleton
class ImageMediaStoreSaver @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * Persists [bitmap] as a PNG named after [displayName]. Safe to call from any coroutine — the whole
     * write runs on the injected IO dispatcher.
     */
    suspend fun save(bitmap: Bitmap, displayName: String): Uri? = withContext(io) {
        val fileName = buildFileName(displayName)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                publishToMediaStore(bitmap, fileName)
            } else {
                publishToPrivateFile(bitmap, fileName)
            }
        } catch (error: Throwable) {
            null
        }
    }
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun publishToMediaStore(bitmap: Bitmap, fileName: String): Uri? {
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val pending = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, MIME_PNG)
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/$GALLERY_SUBDIR",
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, pending) ?: return null
        return try {
            val wrote = resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
            } ?: false
            if (!wrote) {
                runCatching { resolver.delete(uri, null, null) }
                return null
            }
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null,
            )
            if (mediaStoreSize(uri) < MIN_OUTPUT_BYTES) {
                runCatching { resolver.delete(uri, null, null) }
                null
            } else {
                uri
            }
        } catch (error: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    private fun mediaStoreSize(uri: Uri): Long {
        val fromFd = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
        }.getOrNull() ?: -1L
        if (fromFd > 0L) return fromFd
        return runCatching {
            context.contentResolver
                .query(uri, arrayOf(MediaStore.Images.Media.SIZE), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
                ?: 0L
        }.getOrDefault(0L)
    }

    /**
     * Pre-Q fallback: write into the app's own `files/captures/` and hand back a `FileProvider` grant.
     *
     * Reuses the existing `captures` files-path mapping and the `.fileprovider` authority screenshots already
     * use, so pre-29 devices get a shareable image with no new manifest / res-xml entry and no storage grant.
     */
    private fun publishToPrivateFile(bitmap: Bitmap, fileName: String): Uri? {
        val dir = File(context.filesDir, CAPTURES_SUBDIR).apply { mkdirs() }
        val target = File(dir, fileName)
        return try {
            val wrote = target.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
            }
            if (!wrote || !target.isFile || target.length() < MIN_OUTPUT_BYTES) {
                runCatching { target.delete() }
                null
            } else {
                shareUri(target)
            }
        } catch (error: Throwable) {
            runCatching { target.delete() }
            null
        }
    }

    private fun shareUri(file: File): Uri? = try {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    } catch (error: Throwable) {
        null
    }

    /**
     * A filesystem- and MediaStore-safe image name.
     *
     * Runs the caller's name through [TextSanitizer] (control chars, bidi overrides, length), keeps only
     * characters safe as both a private file name and a MediaStore `DISPLAY_NAME`, and appends a timestamp so
     * two saves in the same second cannot collide.
     */
    private fun buildFileName(displayName: String): String {
        val base = TextSanitizer.sanitizeName(displayName, maxLength = 40)
            .replace(Regex("[^A-Za-z0-9 _-]"), "_")
            .replace(Regex("\\s+"), "_")
            .trim('_', '.', '-')
            .take(40)
            .ifBlank { "screen" }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "GameCore-Screen-$base-$stamp.png"
    }

    private companion object {
        const val MIME_PNG = "image/png"
        const val GALLERY_SUBDIR = "GameCore"
        const val CAPTURES_SUBDIR = "captures"
        const val PNG_QUALITY = 100

        /** PNG is lossless, so any real crop is well over this; under it is a failed or empty write. */
        const val MIN_OUTPUT_BYTES = 100L
    }
}
