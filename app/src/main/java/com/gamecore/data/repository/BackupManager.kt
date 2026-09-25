package com.gamecore.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.gamecore.core.backup.BackupCodec
import com.gamecore.core.common.Formatters
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.model.ColorPreset
import com.gamecore.core.model.CrosshairPreset
import com.gamecore.core.model.GameProfile
import com.gamecore.core.model.HudLayout
import com.gamecore.core.overlay.MacroCodec
import com.gamecore.data.preferences.SecurePreferenceStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The §20 config backup's one moving part: it reads every configuration store, hands the bytes to the pure
 * [BackupCodec], writes the file through the shared [FileShareStore], and — on the way back — reads a file
 * the user picked with the Storage Access Framework and applies it.
 *
 * It touches configuration only. Sessions and Aim Lab runs are never read here and never written back, so a
 * restore cannot overwrite the history the user recorded; the screen says as much in plain words. Everything
 * a foreign file carries is validated by [BackupCodec] before it reaches a store, and the profile half of it
 * is neither built nor parsed here — that is [profileTransfer]'s job, isolated to [exportProfiles] and
 * [importProfiles] so the whole cross-feature seam is two call sites rather than a thread through this class.
 *
 * The import is deliberately non-destructive by default: [BackupPolicy.MERGE] adds what is missing — profiles,
 * layouts and presets are matched to what is already here by content and only the genuinely new ones are
 * written, so a re-import changes nothing — and folds macros in beside the user's own. [BackupPolicy.REPLACE],
 * reached only from an explicit confirmation on the screen, differs just where a conflict exists: the file's
 * version wins on a profile for a game the device also has, and its macros replace the user's rather than
 * merging. Neither mode deletes anything the file does not mention, and neither touches session history.
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
    private val preferences: SecurePreferenceStore,
    private val gameProfiles: GameProfileRepository,
    private val hudLayouts: HudLayoutRepository,
    private val crosshairs: CrosshairRepository,
    private val colours: ColorPresetRepository,
    private val fileShareStore: FileShareStore,
    private val profileTransfer: ProfileTransfer,
) {

    // ------------------------------------------------------------------------------------------ export

    /**
     * Gathers every configuration store into one document and writes it to `filesDir/exports`, returning a
     * share intent for it.
     *
     * The profiles are read once and drive two things: the profile layer builds its bundle from them (with the
     * presets they reference embedded), and their referenced layout / crosshair / colour ids are the set this
     * class *excludes* from its own top-level sections — so every referenced preset travels exactly once (via
     * the profile layer) and the standalone remainder travels here. Nothing leaves the device; the file sits in
     * the app's own storage and only the user picking a share target moves it.
     */
    suspend fun export(): BackupExport = withContext(io) {
        val overlay = preferences.overlay.value
        val profiles = gameProfiles.profiles.first()
        val json = BackupCodec.encode(
            settings = preferences.settings.value,
            overlay = overlay,
            button = preferences.floatingButton.value,
            colour = preferences.colorCorrection.value,
            layouts = BackupCodec.standaloneLayouts(layoutsWithWidgets(), profiles.referenced { it.hudLayoutId }),
            crosshairs = BackupCodec.standaloneCrosshairs(crosshairs.presets.first(), profiles.referenced { it.crosshairPresetId }),
            colours = BackupCodec.standaloneColours(colours.all(), profiles.referenced { it.colorPresetId }),
            macros = macrosArray(overlay.macrosJson),
            profiles = exportProfiles(profiles),
        )
        when (val written = fileShareStore.writeText(
            subdir = EXPORT_SUBDIR,
            fileName = "gamecore-backup-${Formatters.fileTimestamp(System.currentTimeMillis())}.json",
            text = json,
            accept = FileShareStore.byExtension(".json"),
        )) {
            is FileWriteResult.Stored -> BackupExport.Written(
                fileName = written.file.name,
                sizeBytes = written.file.length(),
                uri = written.uri,
                shareIntent = written.uri?.let { fileShareStore.buildShareIntent(it, MIME_TYPE) },
            )
            is FileWriteResult.Failed -> BackupExport.Failed(written.detail)
        }
    }

    /** The non-null preset ids [select] picks off the profiles — the ones already embedded with a profile. */
    private inline fun List<GameProfile>.referenced(select: (GameProfile) -> Long?): Set<Long> =
        mapNotNullTo(HashSet(), select)

    /** Every layout with its widgets loaded — the list flow returns them empty, so each is fetched by id. */
    private suspend fun layoutsWithWidgets(): List<HudLayout> =
        hudLayouts.layouts.first().mapNotNull { hudLayouts.layout(it.id) }

    /** The stored macro string as a JSON array, safely — a corrupt key exports as no macros, never a throw. */
    private fun macrosArray(macrosJson: String): JSONArray =
        runCatching { JSONArray(macrosJson.ifBlank { "[]" }) }.getOrDefault(JSONArray())

    // ------------------------------------------------------------------------------------------ restore

    /**
     * Reads the file the user picked, validates it, and applies it under [policy].
     *
     * The read is capped at [MAX_IMPORT_BYTES] before a byte is parsed — a hostile file cannot make the app
     * allocate the world — and [BackupCodec.decode] does the rest of the gatekeeping, so this method only
     * ever hands validated models to the stores. A rejection is surfaced with its reason rather than thrown;
     * an accepted file reports what it wrote.
     */
    suspend fun restore(uri: Uri, policy: BackupPolicy): BackupImport = withContext(io) {
        val text = readCapped(uri) ?: return@withContext BackupImport.Failed(BackupImport.Reason.UNREADABLE)
        when (val result = BackupCodec.decode(text)) {
            is BackupCodec.BackupResult.Rejected -> BackupImport.Failed(reasonOf(result.reason))
            is BackupCodec.BackupResult.Restored -> apply(result.data, policy)
        }
    }

    private suspend fun apply(data: BackupCodec.BackupData, policy: BackupPolicy): BackupImport {
        data.settings?.let { restored -> preferences.updateSettings { restored } }
        applyOverlayAndMacros(data, policy)
        data.button?.let { restored -> preferences.updateFloatingButton { restored } }
        data.colour?.let { preferences.setColorCorrection(it) }
        val layoutCount = data.layouts?.let { importLayouts(it, policy) } ?: 0
        val crosshairCount = data.crosshairs?.let { importCrosshairs(it, policy) } ?: 0
        val colourCount = data.colours?.let { importColours(it, policy) } ?: 0
        val profileCount = data.profiles?.let { importProfiles(it, policy) } ?: 0
        return BackupImport.Restored(
            layoutsAdded = layoutCount,
            crosshairsAdded = crosshairCount,
            coloursAdded = colourCount,
            profilesImported = profileCount,
        )
    }

    /**
     * Writes the overlay config and the macros together, because the macros live inside the config's one
     * string field even though they travel at the envelope's top level.
     *
     * When the file carried no macros the current ones are kept untouched (a restored overlay config arrives
     * with an empty macro string, which must not blank them); when it did, they are merged or replaced per
     * [policy]. Done in a single [SecurePreferenceStore.updateOverlay] so the config the store hands the
     * transform is the one both edits compose onto.
     */
    private fun applyOverlayAndMacros(data: BackupCodec.BackupData, policy: BackupPolicy) {
        if (data.overlay == null && data.macros == null) return
        preferences.updateOverlay { current ->
            val base = data.overlay ?: current
            val macrosJson = if (data.macros != null) {
                mergeMacros(current.macrosJson, data.macros, policy)
            } else {
                current.macrosJson
            }
            base.copy(macrosJson = macrosJson)
        }
    }

    /**
     * Folds the file's macros into the stored ones under [policy], then round-trips through [MacroCodec] so
     * the result is a clean, capped, unique-id list whatever the two inputs were.
     *
     * [BackupPolicy.REPLACE] takes the file's macros alone; [BackupPolicy.MERGE] keeps the user's and appends
     * only the file's that are not already present by name and action list, so re-importing the same file is
     * idempotent rather than doubling every macro. The final encode-of-decode is what reassigns ids in order
     * and applies [com.gamecore.core.overlay.MacroLibrary]'s cap, exactly as a load from the store would.
     */
    private fun mergeMacros(currentJson: String, fileMacros: JSONArray, policy: BackupPolicy): String {
        val incoming = MacroCodec.decode(fileMacros.toString())
        val merged = when (policy) {
            BackupPolicy.REPLACE -> incoming
            BackupPolicy.MERGE -> {
                val current = MacroCodec.decode(currentJson)
                current + incoming.filterNot { new ->
                    current.any { it.name == new.name && it.actions == new.actions }
                }
            }
        }
        return MacroCodec.encode(MacroCodec.decode(MacroCodec.encode(merged)))
    }

    /**
     * Restores the file's standalone layouts by create-or-match, returning how many rows were newly created.
     *
     * Each imported layout is matched against the device's own by [BackupCodec.hudSignature] — the same
     * content signature the profile layer uses, ignoring id and timestamps — so a layout already here is
     * reused rather than duplicated, and a fresh one is created with a zero id (§2's id-locality rule). This
     * is what makes a re-import idempotent: a [BackupPolicy.MERGE] leaves every match alone, so running it
     * twice writes nothing the second time. [BackupPolicy.REPLACE] overwrites the matched row in place — the
     * content is already identical, so this only refreshes it — and never deletes a layout the file omits.
     */
    private suspend fun importLayouts(layouts: List<HudLayout>, policy: BackupPolicy): Int {
        val bySignature = HashMap<String, Long>()
        hudLayouts.layouts.first().forEach { row ->
            hudLayouts.layout(row.id)?.let { bySignature.putIfAbsent(BackupCodec.hudSignature(it), it.id) }
        }
        var created = 0
        for (layout in layouts) {
            val signature = BackupCodec.hudSignature(layout)
            val match = bySignature[signature]
            when {
                match == null -> {
                    val id = hudLayouts.save(layout.copy(id = 0L))
                    bySignature[signature] = id
                    created++
                }
                policy == BackupPolicy.REPLACE -> hudLayouts.save(layout.copy(id = match))
                // MERGE: an identical layout is already here — leave it, so a second merge adds nothing.
            }
        }
        return created
    }

    /**
     * Restores the file's standalone crosshair presets by create-or-match, returning how many were created.
     *
     * Mirrors [importLayouts]: matched against the device's presets by [BackupCodec.crosshairSignature] (which
     * ignores the local id and the device-local image path), reused where one is already here, created with a
     * zero id otherwise. A custom-image preset arrives without its picture — the bytes never travelled — as it
     * does through the profile layer.
     */
    private suspend fun importCrosshairs(presets: List<CrosshairPreset>, policy: BackupPolicy): Int {
        val bySignature = HashMap<CrosshairPreset, Long>()
        crosshairs.presets.first().forEach { bySignature.putIfAbsent(BackupCodec.crosshairSignature(it), it.id) }
        var created = 0
        for (preset in presets) {
            val signature = BackupCodec.crosshairSignature(preset)
            val match = bySignature[signature]
            when {
                match == null -> {
                    val id = crosshairs.save(preset.copy(id = 0L))
                    bySignature[signature] = id
                    created++
                }
                policy == BackupPolicy.REPLACE -> crosshairs.save(preset.copy(id = match))
            }
        }
        return created
    }

    /** Restores the file's standalone colour presets by create-or-match. See [importCrosshairs]. */
    private suspend fun importColours(presets: List<ColorPreset>, policy: BackupPolicy): Int {
        val bySignature = HashMap<ColorPreset, Long>()
        colours.all().forEach { bySignature.putIfAbsent(BackupCodec.colourSignature(it), it.id) }
        var created = 0
        for (preset in presets) {
            val signature = BackupCodec.colourSignature(preset)
            val match = bySignature[signature]
            when {
                match == null -> {
                    val id = colours.save(preset.copy(id = 0L))
                    bySignature[signature] = id
                    created++
                }
                policy == BackupPolicy.REPLACE -> colours.save(preset.copy(id = match))
            }
        }
        return created
    }

    // ------------------------------------------------------------- the profile-layer seam (feature #5)

    /**
     * The one place the profiles/presets object is *built*. Delegates wholly to [ProfileTransfer], which
     * owns the id-locality remap and the preset embedding this class must not reimplement. Takes the already
     * read [profiles] so the caller can, from the same list, exclude the referenced presets from its own
     * top-level sections.
     */
    private suspend fun exportProfiles(profiles: List<GameProfile>): JSONObject =
        profileTransfer.buildProfilesObject(profiles)

    /**
     * The one place the profiles/presets object is *imported*. [ProfileTransfer] recreates or matches its
     * presets and remaps every reference; this class only forwards the object and the mapped policy, then
     * reports the number of profiles actually written.
     *
     * The backup's two-way [BackupPolicy] maps onto #5's three-way [ProfileCollisionPolicy]: a REPLACE
     * restore overwrites a colliding profile ([ProfileCollisionPolicy.REPLACE]); a MERGE restore keeps the
     * profile already on the device and adds only the ones missing ([ProfileCollisionPolicy.SKIP]), which
     * makes re-importing the same backup idempotent — the same guarantee the macro merge gives.
     */
    private suspend fun importProfiles(bundle: JSONObject, policy: BackupPolicy): Int {
        val collision = when (policy) {
            BackupPolicy.REPLACE -> ProfileCollisionPolicy.REPLACE
            BackupPolicy.MERGE -> ProfileCollisionPolicy.SKIP
        }
        return profileTransfer.importProfilesObject(bundle, collision).written
    }

    // ------------------------------------------------------------------------------------------ file IO

    /** Reads the picked document as UTF-8 text, or null if it is unreadable or larger than the byte cap. */
    private fun readCapped(uri: Uri): String? = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            readCapped(input, MAX_IMPORT_BYTES)?.toString(Charsets.UTF_8)
        }
    } catch (_: Throwable) {
        null
    }

    /** Streams up to [maxBytes], returning null the moment it is exceeded so an outsized file is not held. */
    private fun readCapped(input: InputStream, maxBytes: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(READ_CHUNK_BYTES)
        var total = 0
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            total += read
            if (total > maxBytes) return null
            out.write(chunk, 0, read)
        }
        return out.toByteArray()
    }

    private fun reasonOf(reason: BackupCodec.RejectReason): BackupImport.Reason = when (reason) {
        BackupCodec.RejectReason.EMPTY -> BackupImport.Reason.EMPTY
        BackupCodec.RejectReason.MALFORMED -> BackupImport.Reason.MALFORMED
        BackupCodec.RejectReason.WRONG_FORMAT -> BackupImport.Reason.WRONG_FORMAT
        BackupCodec.RejectReason.UNSUPPORTED_VERSION -> BackupImport.Reason.UNSUPPORTED_VERSION
    }

    private companion object {
        const val EXPORT_SUBDIR = "exports"
        const val MIME_TYPE = "application/json"

        /** A configuration backup is kilobytes; a few megabytes is far past any real one and the hostile cap. */
        const val MAX_IMPORT_BYTES = 4 * 1024 * 1024
        const val READ_CHUNK_BYTES = 8 * 1024
    }
}

/**
 * How a restore treats what is already on the device.
 *
 * [MERGE] is the default and adds rather than overwrites: layouts and presets are matched to what is already
 * here by content and only the genuinely new ones are written, macros are folded in beside the user's, and the
 * profile layer keeps the device's profile where one for the same game exists. Appearance and settings — single
 * records that always exist and so have nothing to "add" — are applied in both modes, and the screen says so.
 * [REPLACE], reached behind a confirmation, differs only where a conflict exists: the file's profile wins for a
 * game the device also has, and its macros replace the user's. Neither mode deletes what the file does not carry.
 */
enum class BackupPolicy { MERGE, REPLACE }

/** The outcome of [BackupManager.export]. Mirrors the sibling exporters' Written / Failed shape. */
sealed interface BackupExport {
    data class Written(
        val fileName: String,
        val sizeBytes: Long,
        val uri: Uri?,
        /** Non-null only when a share grant could be minted; the file exists and is written either way. */
        val shareIntent: Intent?,
    ) : BackupExport

    /** [detail] is an exception's simple name only, never a path or message. */
    data class Failed(val detail: String) : BackupExport
}

/** The outcome of [BackupManager.restore]: what was written, or the plain reason a file was refused. */
sealed interface BackupImport {
    data class Restored(
        val layoutsAdded: Int,
        val crosshairsAdded: Int,
        val coloursAdded: Int,
        val profilesImported: Int,
    ) : BackupImport

    data class Failed(val reason: Reason) : BackupImport

    /** Why a restore could not proceed — one per case the screen turns into a line for the user. */
    enum class Reason { UNREADABLE, EMPTY, MALFORMED, WRONG_FORMAT, UNSUPPORTED_VERSION }
}




