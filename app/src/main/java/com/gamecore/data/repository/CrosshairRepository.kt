package com.gamecore.data.repository

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.model.CrosshairDesign
import com.gamecore.core.model.CrosshairPreset
import com.gamecore.data.database.CrosshairPresetDao
import com.gamecore.data.database.Mappers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Crosshair presets, and the imported images some of them use.
 *
 * The image handling is the reason this repository is more than DAO plumbing. A custom crosshair is
 * a file the user picked from anywhere on the device, and two things have to be true before the
 * overlay renderer sees it: it has to still exist when the overlay starts (a content URI the user
 * granted for one picker session will not), and it has to be an image GameCore has already decoded
 * successfully (§24A.4 — external input validated before use, and a malformed PNG must fail on the
 * settings screen, not inside a window drawn over a game).
 *
 * So imports are **decoded and re-encoded**, not copied. `BitmapFactory` returning null is the
 * validation; the PNG that lands in app storage is one this process produced. A byte-for-byte copy
 * would carry whatever the source file contained — including a file that is not an image at all —
 * to a code path where a throw takes the overlay service down mid-game.
 */
@Singleton
class CrosshairRepository @Inject constructor(
    private val dao: CrosshairPresetDao,
    private val profiles: GameProfileRepository,
    private val imageDirectory: CrosshairImageDirectory,
    private val root: CrosshairImageRoot,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    val presets: Flow<List<CrosshairPreset>> =
        dao.observeAll().map { rows -> rows.map(Mappers::toModel) }

    suspend fun preset(id: Long): CrosshairPreset? = withContext(io) {
        dao.byId(id)?.let(Mappers::toModel)
    }

    /** Returns the stored id, which differs from the argument's when it was 0 (a new preset). */
    suspend fun save(preset: CrosshairPreset): Long = withContext(io) {
        dao.insert(Mappers.toEntity(preset))
    }

    /**
     * Deletes a preset, its image, and every profile's reference to it.
     *
     * The image goes with the preset because nothing else can reach it — the filename is derived
     * from the preset id, so an orphaned file would sit in app storage forever with no UI able to
     * show or remove it.
     */
    suspend fun delete(id: Long) {
        withContext(io) {
            dao.byId(id)?.let { entity ->
                entity.imagePath?.let { path -> imageDirectory.delete(path) }
            }
            dao.delete(id)
        }
        profiles.clearCrosshairReferences(id)
    }

    /**
     * Creates the starting presets if the table is empty.
     *
     * Called once from the crosshair screen rather than at process start. A first launch showing an
     * empty list with an "add" button is worse than showing three presets to adjust, and seeding on
     * demand keeps it out of `Application.onCreate` where it would cost launch latency for a user
     * who never opens the screen.
     *
     * Returns true if it seeded, so the caller can select the first one.
     */
    suspend fun seedDefaultsIfEmpty(): Boolean = withContext(io) {
        if (dao.count() > 0) return@withContext false
        DEFAULTS.forEach { preset -> dao.insert(Mappers.toEntity(preset)) }
        true
    }

    /**
     * Imports an image for a preset, returning the stored path or null.
     *
     * Null means the stream was not a decodable image, or was too large, and the caller shows that
     * rather than storing a path to a file the renderer will fail on. The preset is saved with the
     * returned path by the caller — this method does not write to the database, because an import
     * that succeeded and a preset that was saved are two different things the UI reports separately.
     */
    suspend fun importImage(presetId: Long, source: InputStream): String? = withContext(io) {
        imageDirectory.store(presetId, source)
    }

    /**
     * The same import, from what a document picker returned.
     *
     * The overload exists so the crosshair screen can hand over the `Uri` the picker gave it without
     * a `ContentResolver` appearing in a composable (§25). A URI that cannot be opened is null, the
     * same as a file that is not an image: from the user's side both are "that picture could not be
     * used", and splitting them would mean two error messages for one dead end.
     */
    suspend fun importImage(presetId: Long, uri: Uri): String? = withContext(io) {
        val stream = root.openSource(uri) ?: return@withContext null
        stream.use { imageDirectory.store(presetId, it) }
    }

    private companion object {
        /**
         * Three presets covering the shapes people actually ask for, sized for a phone screen.
         *
         * Three rather than one per design: a first-run list long enough to scroll is a list the user
         * has to read before they can do anything. Every other design is one dropdown away on any of
         * these, so nothing is unreachable — only unlisted.
         */
        val DEFAULTS = listOf(
            CrosshairPreset(name = "Cross", design = CrosshairDesign.CROSS, sizeDp = 28),
            CrosshairPreset(name = "Dot", design = CrosshairDesign.DOT, sizeDp = 10),
            CrosshairPreset(
                name = "Ring and dot",
                design = CrosshairDesign.CIRCLE_DOT,
                sizeDp = 32,
                showDot = true,
            ),
        )
    }
}

/**
 * Where imported crosshair images live, and the only class that writes them.
 *
 * Separate from the repository so the decode-and-re-encode rule has one implementation and the
 * overlay renderer can be handed a path it knows came from here. Kept in `data` rather than
 * `core.system` because the lifetime of these files is the lifetime of a database row.
 */
@Singleton
class CrosshairImageDirectory @Inject constructor(
    private val root: CrosshairImageRoot,
) {

    /**
     * Decodes, downscales if needed, and writes a PNG. Returns the absolute path, or null.
     *
     * [MAX_DIMENSION] is a real limit rather than a guess: the image is drawn into an overlay
     * window at a size the user set in dp, and a 4000-pixel-wide source would hold ~64 MB of
     * bitmap in the overlay service's heap to render something 30 dp across. Downscaling at import
     * means the renderer's memory cost is bounded by this constant instead of by what the user
     * happened to pick.
     */
    fun store(presetId: Long, source: InputStream): String? {
        val decoded = runCatching { BitmapFactory.decodeStream(source) }.getOrNull() ?: return null
        val scaled = runCatching { downscale(decoded) }.getOrNull() ?: run {
            decoded.recycle()
            return null
        }
        val target = File(root.directory(), fileName(presetId))
        val written = runCatching {
            target.outputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
            }
        }.getOrDefault(false)

        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()

        if (!written) {
            runCatching { target.delete() }
            return null
        }
        return target.absolutePath
    }

    /** Deletes an imported image, refusing paths outside GameCore's own crosshair directory. */
    fun delete(path: String): Boolean = runCatching {
        val file = File(path)
        val directory = root.directory()
        if (file.parentFile?.canonicalPath != directory.canonicalPath) return false
        file.exists() && file.delete()
    }.getOrDefault(false)

    private fun downscale(source: Bitmap): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= MAX_DIMENSION) return source
        val factor = MAX_DIMENSION.toFloat() / longest
        val width = (source.width * factor).toInt().coerceAtLeast(1)
        val height = (source.height * factor).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    private fun fileName(presetId: Long) = "crosshair-$presetId.png"

    private companion object {
        const val MAX_DIMENSION = 512

        /** Ignored for PNG, which is lossless, but the parameter is not optional. */
        const val PNG_QUALITY = 100
    }
}
