package com.gamecore.data.repository

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Imports the user's chosen image as GameCore's wallpaper: decoded, downscaled and re-encoded into
 * app-private storage, never copied.
 *
 * ## Why this decodes in two passes when [CrosshairImageDirectory] does not
 *
 * The crosshair importer decodes the whole source and *then* downscales, which is safe there because
 * a crosshair is 512 px and the sources are small. A wallpaper is picked from a camera roll, and a
 * modern phone photo is 50–108 MP — at `ARGB_8888` a 108 MP image is roughly 432 MB of heap, so
 * decoding it in full to produce a 2048 px wallpaper would OOM before the downscale ever ran. So the
 * import does a bounds-only pass first ([BitmapFactory.Options.inJustDecodeBounds]), picks an
 * `inSampleSize`, and decodes already-subsampled. Peak memory is then bounded by
 * [MAX_DIMENSION] rather than by whatever the user happened to photograph.
 *
 * ## Why each import gets a new file name
 *
 * The stored path is what lands in `AppSettings.wallpaperPath`, and Compose decides whether to redraw
 * by comparing that value. A fixed name like `wallpaper.webp` would mean picking a *different* image
 * produced an identical path, so the setting would not change, the recomposition would not happen,
 * and the user would be told their new wallpaper was applied while still looking at the old one. The
 * name therefore carries an import token, and [store] prunes every other file in its own directory
 * afterwards, so exactly one wallpaper is ever on disk.
 *
 * Every failure returns null. There is no partially-applied state: the bytes are written to a
 * temporary file and renamed only once the encode has succeeded, so an interrupted import leaves the
 * previous wallpaper intact rather than a half-written image that renders as garbage.
 */
@Singleton
class WallpaperImageStore @Inject constructor(
    private val root: WallpaperImageRoot,
) {

    /**
     * Imports [uri], returning the absolute path of the stored wallpaper, or null.
     *
     * Null is the single honest answer to every way this can fail — an unreadable provider, a file
     * that is not an image, a decode that runs out of memory, a full disk — because none of them is
     * something the user can act on differently. The caller surfaces one message.
     */
    fun store(uri: Uri): String? {
        val bounds = readBounds(uri) ?: return null
        if (bounds.first <= 0 || bounds.second <= 0) return null

        val decoded = decodeSampled(uri, bounds) ?: return null
        val scaled = runCatching { downscale(decoded) }.getOrNull() ?: run {
            decoded.recycle()
            return null
        }

        val directory = root.directory()
        val target = File(directory, fileName(System.currentTimeMillis()))
        val temporary = File(directory, "${target.name}$TEMP_SUFFIX")

        val written = runCatching {
            temporary.outputStream().use { out -> scaled.compress(compressFormat(), QUALITY, out) }
        }.getOrDefault(false)

        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()

        if (!written || !runCatching { temporary.renameTo(target) }.getOrDefault(false)) {
            runCatching { temporary.delete() }
            return null
        }

        // Exactly one wallpaper on disk: drop the previous import (and any stray temporary) now that
        // the new one is committed. A failure to prune is not a failure to import.
        runCatching {
            directory.listFiles()?.forEach { if (it != target) it.delete() }
        }
        return target.absolutePath
    }

    /** Deletes an imported wallpaper, refusing any path outside GameCore's own wallpaper directory. */
    fun delete(path: String): Boolean = runCatching {
        val file = File(path)
        if (!isInside(file, root.directory())) return false
        file.exists() && file.delete()
    }.getOrDefault(false)

    /** The source's pixel dimensions, read without allocating a bitmap. */
    private fun readBounds(uri: Uri): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val opened = root.openSource(uri) ?: return null
        runCatching { opened.use { BitmapFactory.decodeStream(it, null, options) } }
        val width = options.outWidth
        val height = options.outHeight
        return if (width > 0 && height > 0) width to height else null
    }

    /** Decodes the source already subsampled, so peak heap is bounded by [MAX_DIMENSION]. */
    private fun decodeSampled(uri: Uri, bounds: Pair<Int, Int>): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.first, bounds.second, MAX_DIMENSION)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val opened = root.openSource(uri) ?: return null
        return runCatching { opened.use { BitmapFactory.decodeStream(it, null, options) } }.getOrNull()
    }

    /** Scales a subsampled bitmap down to exactly [MAX_DIMENSION] on its long edge, if it is still over. */
    private fun downscale(source: Bitmap): Bitmap {
        val target = scaleTargetFor(source.width, source.height, MAX_DIMENSION) ?: return source
        return Bitmap.createScaledBitmap(source, target.first, target.second, true)
    }

    private fun compressFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }

    private fun fileName(token: Long) = "wallpaper-$token.webp"

    companion object {
        /**
         * The long edge of the stored wallpaper, in pixels.
         *
         * Sized for the panel rather than for the source: the image is drawn full-screen behind the
         * UI, and 2048 covers the long edge of essentially every phone panel at native density. Above
         * it, every extra pixel costs heap at render time to show detail no display can resolve.
         */
        const val MAX_DIMENSION = 2048

        /** Lossy is right for a photograph behind a scrim; the artefacts are invisible under it. */
        const val QUALITY = 85

        private const val TEMP_SUFFIX = ".part"

        /**
         * The largest power-of-two `inSampleSize` that still leaves the long edge at or above [maxDim].
         *
         * Powers of two because `BitmapFactory` rounds anything else down to one anyway, and
         * deliberately *not* past the target: subsampling is cheap but lossy in a way scaling is not,
         * so the final step down to the exact size is [scaleTargetFor]'s job.
         */
        internal fun sampleSizeFor(width: Int, height: Int, maxDim: Int): Int {
            if (width <= 0 || height <= 0 || maxDim <= 0) return 1
            var sample = 1
            var longest = maxOf(width, height)
            while (longest / 2 >= maxDim) {
                longest /= 2
                sample *= 2
            }
            return sample
        }

        /**
         * The exact size to scale to, or null when the bitmap is already within [maxDim].
         *
         * Aspect ratio is preserved, and the short edge is floored at 1 so a panoramic source cannot
         * round to a zero-height bitmap, which `createScaledBitmap` throws on.
         */
        internal fun scaleTargetFor(width: Int, height: Int, maxDim: Int): Pair<Int, Int>? {
            if (width <= 0 || height <= 0 || maxDim <= 0) return null
            val longest = maxOf(width, height)
            if (longest <= maxDim) return null
            val factor = maxDim.toFloat() / longest
            return maxOf(1, (width * factor).toInt()) to maxOf(1, (height * factor).toInt())
        }

        /**
         * Whether [file] sits directly inside [directory], compared canonically.
         *
         * Canonical rather than textual, because `filesDir` is routinely a symlink on Android and a
         * `..` segment in a stored path would otherwise compare as "inside" while resolving outside.
         */
        internal fun isInside(file: File, directory: File): Boolean = runCatching {
            file.canonicalFile.parentFile?.canonicalPath == directory.canonicalPath
        }.getOrDefault(false)
    }
}
