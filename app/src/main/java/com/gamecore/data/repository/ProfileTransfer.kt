package com.gamecore.data.repository

import android.content.Context
import android.net.Uri
import com.gamecore.core.common.Formatters
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.common.TextSanitizer
import com.gamecore.core.model.ColorPreset
import com.gamecore.core.model.CrosshairPreset
import com.gamecore.core.model.GameProfile
import com.gamecore.core.model.HudLayout
import com.gamecore.data.repository.ProfileTransferCodec.PresetBundle
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Android half of profile transfer (§5): it reads the repositories, writes the share file, reads a
 * picked file back, and does the recreate-or-match-and-remap that the pure [ProfileTransferCodec] cannot.
 *
 * ── REUSABLE JSON-LEVEL CONTRACT FOR #20 (config backup & restore) ────────────────────────────────────
 * Two methods, stable and self-contained, exchanging only `org.json` — no shared custom types:
 *
 *   suspend fun buildProfilesObject(profiles: List<GameProfile>): org.json.JSONObject
 *   suspend fun importProfilesObject(obj: org.json.JSONObject, policy: ProfileCollisionPolicy): ProfileImportOutcome
 *
 * • [buildProfilesObject] gathers the referenced HUD / crosshair / colour presets from the repositories
 *   itself and returns the **unwrapped** body `{ "profiles": [...], "presets": {...} }` — NO format/version
 *   wrapper. The #20 backup writer embeds this object under its own key; #5's own [export] wraps the very
 *   same body in the `{ "format": "gamecore.profile", "version": 1, ... }` envelope via the codec.
 * • [importProfilesObject] decodes that body, recreates-or-matches every embedded preset on this device,
 *   remaps the profiles' local ids to the new ones, and writes them through [GameProfileRepository] under
 *   the given [ProfileCollisionPolicy]. It is total: a hostile object yields a [ProfileImportOutcome] with
 *   `rejected` counted, never a throw.
 * ──────────────────────────────────────────────────────────────────────────────────────────────────────
 *
 * The preset-id-locality trap (§2) is handled entirely here: a profile's `hudLayoutId` / `crosshairPresetId`
 * / `colorPresetId` are looked up in the embedded definitions the codec returned, each definition is matched
 * against an existing device preset by content (ignoring local ids, and for crosshairs the device-local
 * image path) or created fresh, and the profile is rewritten to point at the resulting **new local** id — so
 * no profile is ever written carrying an id from another device.
 */
@Singleton
class ProfileTransfer @Inject constructor(
    private val profileRepo: GameProfileRepository,
    private val hudRepo: HudLayoutRepository,
    private val crosshairRepo: CrosshairRepository,
    private val colorRepo: ColorPresetRepository,
    private val files: FileShareStore,
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    // ------------------------------------------------------------------------ reusable #20 contract

    /** See the class header. Returns the unwrapped `{ "profiles", "presets" }` body — no envelope. */
    suspend fun buildProfilesObject(profiles: List<GameProfile>): JSONObject = withContext(io) {
        ProfileTransferCodec.encodeBody(profiles, gatherPresets(profiles))
    }

    /** See the class header. Decodes, recreates-or-matches presets, remaps ids, writes. Never throws. */
    suspend fun importProfilesObject(obj: JSONObject, policy: ProfileCollisionPolicy): ProfileImportOutcome =
        withContext(io) { commit(ProfileTransferCodec.decodeBody(obj), policy) }

    // ------------------------------------------------------------------------------------ #5 export

    /**
     * Writes [profiles] as a versioned envelope into `files/exports` and hands back a `content://` URI.
     *
     * Pruned independently of the other JSON exports in that directory (the backup file, most importantly)
     * by matching this feature's own filename prefix, so a run of profile exports cannot evict a backup.
     */
    suspend fun export(profiles: List<GameProfile>): ProfileExportResult = withContext(io) {
        if (profiles.isEmpty()) return@withContext ProfileExportResult.Empty
        val text = ProfileTransferCodec.encodeEnvelope(profiles, gatherPresets(profiles))
        val label = if (profiles.size == 1) slug(profiles.first().label) else "all"
        val fileName = "$FILE_PREFIX$label-${Formatters.fileTimestamp(now())}$EXTENSION"
        when (
            val result = files.writeText(
                subdir = DIRECTORY,
                fileName = fileName,
                text = text,
                keep = KEEP,
                accept = ::isProfileExport,
            )
        ) {
            is FileWriteResult.Stored -> ProfileExportResult.Written(
                fileName = result.file.name,
                sizeBytes = result.file.length(),
                profileCount = profiles.size,
                uri = result.uri,
            )
            is FileWriteResult.Failed -> ProfileExportResult.Failed(result.detail)
        }
    }

    // ------------------------------------------------------------------------------------ #5 import

    /**
     * Reads a picked document, decodes it far enough to list what is inside and which entries would
     * collide with a profile already saved, and hands that back for the screen to confirm a policy against.
     *
     * Nothing is written here: this is the "what am I about to import" step. The write is [commit], reached
     * through [importProfilesObject] once the user has chosen how collisions should be handled.
     */
    suspend fun stageImport(uri: Uri): ProfileImportStaging = withContext(io) {
        val text = readText(uri) ?: return@withContext ProfileImportStaging.Unreadable
        val obj = try {
            JSONObject(text)
        } catch (_: Throwable) {
            return@withContext ProfileImportStaging.Malformed
        }
        val decoded = ProfileTransferCodec.decodeBody(obj)
        if (decoded.profiles.isEmpty()) return@withContext ProfileImportStaging.Empty
        val existing = profileRepo.profiles.first().mapTo(HashSet()) { it.packageName }
        val incoming = decoded.profiles.map {
            IncomingProfile(it.packageName, it.label, collides = it.packageName in existing)
        }
        ProfileImportStaging.Ready(obj, incoming)
    }

    // ------------------------------------------------------------------------------- private helpers

    /** The referenced HUD / crosshair / colour presets, loaded whole (widgets included) for embedding. */
    private suspend fun gatherPresets(profiles: List<GameProfile>): PresetBundle = PresetBundle(
        hud = profiles.mapNotNull { it.hudLayoutId }.toSet().mapNotNull { hudRepo.layout(it) },
        crosshair = profiles.mapNotNull { it.crosshairPresetId }.toSet().mapNotNull { crosshairRepo.preset(it) },
        color = profiles.mapNotNull { it.colorPresetId }.toSet().mapNotNull { colorRepo.preset(it) },
    )

    /**
     * Reads [uri] as UTF-8 text, refusing anything past [MAX_IMPORT_BYTES] so a hostile or accidental
     * huge pick cannot be pulled into memory. Null for a stream that would not open or ran over the cap.
     */
    private fun readText(uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(READ_CHUNK_BYTES)
            var total = 0L
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                total += read
                if (total > MAX_IMPORT_BYTES) return@runCatching null
                buffer.write(chunk, 0, read)
            }
            String(buffer.toByteArray(), Charsets.UTF_8)
        }
    }.getOrNull()

    /**
     * Recreates-or-matches every embedded preset, remaps each profile onto the resulting **local** ids, and
     * writes the profiles under [policy]. Total: every branch is a counted outcome, never a throw.
     */
    private suspend fun commit(
        decoded: ProfileTransferCodec.Decoded,
        policy: ProfileCollisionPolicy,
    ): ProfileImportOutcome {
        // Existing device presets, keyed by a content signature that ignores the local id (and, for a
        // crosshair, the device-local image path) so an import reuses what is already here rather than
        // duplicating it. A preset the resolver creates is registered back into these maps, so two profiles
        // that shared one preset in the file share one on the device too.
        val hudBySig = HashMap<String, Long>()
        for (layout in hudRepo.layouts.first()) {
            hudRepo.layout(layout.id)?.let { hudBySig.putIfAbsent(hudSignature(it), it.id) }
        }
        val crosshairBySig = HashMap<CrosshairPreset, Long>()
        for (preset in crosshairRepo.presets.first()) crosshairBySig.putIfAbsent(crosshairKey(preset), preset.id)
        val colorBySig = HashMap<ColorPreset, Long>()
        for (preset in colorRepo.all()) colorBySig.putIfAbsent(colorKey(preset), preset.id)

        val resolver = PresetResolver(decoded, hudBySig, crosshairBySig, colorBySig)
        // `used` grows as profiles land; `existing` is frozen, so a package that was already saved is a
        // collision and one this same import just wrote under a synthetic name is not counted twice.
        val used = profileRepo.profiles.first().mapTo(HashSet()) { it.packageName }
        val existing = HashSet(used)
        var imported = 0
        var replaced = 0
        var keptBoth = 0
        var skipped = 0
        val collisions = ArrayList<String>()

        for (profile in decoded.profiles) {
            val remapped = profile.copy(
                hudLayoutId = resolver.hud(profile.hudLayoutId),
                crosshairPresetId = resolver.crosshair(profile.crosshairPresetId),
                colorPresetId = resolver.color(profile.colorPresetId),
            )
            val collides = remapped.packageName in existing
            when (policy) {
                ProfileCollisionPolicy.REPLACE -> {
                    profileRepo.save(remapped)
                    if (collides) replaced++ else { imported++; used += remapped.packageName }
                }
                ProfileCollisionPolicy.SKIP ->
                    if (collides) { skipped++; collisions += remapped.packageName }
                    else { profileRepo.save(remapped); imported++; used += remapped.packageName }
                ProfileCollisionPolicy.KEEP_BOTH ->
                    if (collides) {
                        val pkg = uniquePackage(remapped.packageName, used)
                        if (pkg == null) skipped++ else {
                            profileRepo.save(remapped.copy(packageName = pkg, label = "${remapped.label} (imported)"))
                            used += pkg
                            keptBoth++
                            collisions += remapped.packageName
                        }
                    } else { profileRepo.save(remapped); imported++; used += remapped.packageName }
            }
        }
        return ProfileImportOutcome(
            imported = imported,
            replaced = replaced,
            keptBoth = keptBoth,
            skipped = skipped,
            rejected = decoded.rejectedProfiles,
            presetsCreated = resolver.created,
            presetsMatched = resolver.matched,
            collisions = collisions,
        )
    }

    /**
     * The recreate-or-match-and-remap state for one import: the preset signature maps (shared with
     * [commit], and grown as presets are created), the exported-id → new-local-id caches, and the running
     * counts. An `inner class` so it can reach the repositories; each resolver is total and never throws.
     */
    private inner class PresetResolver(
        private val decoded: ProfileTransferCodec.Decoded,
        private val hudBySig: MutableMap<String, Long>,
        private val crosshairBySig: MutableMap<CrosshairPreset, Long>,
        private val colorBySig: MutableMap<ColorPreset, Long>,
    ) {
        private val hudRemap = HashMap<Long, Long>()
        private val crosshairRemap = HashMap<Long, Long>()
        private val colorRemap = HashMap<Long, Long>()
        var created = 0
            private set
        var matched = 0
            private set

        /** null in → null out (no preset); a dangling reference (no embedded definition) also → null. */
        suspend fun hud(exportedId: Long?): Long? {
            if (exportedId == null) return null
            hudRemap[exportedId]?.let { return it }
            val def = decoded.hud[exportedId] ?: return null
            val sig = hudSignature(def)
            val id = hudBySig[sig]?.also { matched++ }
                ?: hudRepo.save(def.copy(id = 0L)).also { created++; hudBySig[sig] = it }
            hudRemap[exportedId] = id
            return id
        }

        suspend fun crosshair(exportedId: Long?): Long? {
            if (exportedId == null) return null
            crosshairRemap[exportedId]?.let { return it }
            val def = decoded.crosshair[exportedId] ?: return null
            val key = crosshairKey(def)
            val id = crosshairBySig[key]?.also { matched++ }
                ?: crosshairRepo.save(def.copy(id = 0L)).also { created++; crosshairBySig[key] = it }
            crosshairRemap[exportedId] = id
            return id
        }

        suspend fun color(exportedId: Long?): Long? {
            if (exportedId == null) return null
            colorRemap[exportedId]?.let { return it }
            val def = decoded.color[exportedId] ?: return null
            val key = colorKey(def)
            val id = colorBySig[key]?.also { matched++ }
                ?: colorRepo.save(def.copy(id = 0L)).also { created++; colorBySig[key] = it }
            colorRemap[exportedId] = id
            return id
        }
    }

    /**
     * A crosshair's content, ignoring its local id and its device-local image path (the bytes do not
     * travel — see the codec), so an imported geometry matches an existing preset by everything that crossed.
     */
    private fun crosshairKey(preset: CrosshairPreset): CrosshairPreset =
        preset.normalised().copy(id = 0L, imagePath = null)

    /** A colour preset's content, ignoring its local id. */
    private fun colorKey(preset: ColorPreset): ColorPreset = preset.normalised().copy(id = 0L)

    /**
     * A HUD layout's content as a string, ignoring the layout id, its timestamps and each widget's client
     * id, and order-independent over the widgets — two layouts draw the same HUD when this matches.
     */
    private fun hudSignature(layout: HudLayout): String {
        val widgets = layout.widgets.map { it.normalised() }.map { w ->
            "${w.stat.name}:${w.xFraction}:${w.yFraction}:${w.textSizeSp}:" +
                "${w.opacityPercent}:${w.showLabel}:${w.showBackground}:${w.colorArgb}"
        }.sorted()
        return layout.name.trim() + " " + widgets.joinToString("")
    }

    /** The next free `<pkg>.imported[N]` for KEEP_BOTH, or null if the grammar or the ceiling refuses one. */
    private fun uniquePackage(base: String, used: Set<String>): String? {
        for (n in 1..MAX_KEEP_BOTH) {
            val candidate = if (n == 1) "$base.imported" else "$base.imported$n"
            val valid = TextSanitizer.validatePackageName(candidate) ?: continue
            if (valid !in used) return valid
        }
        return null
    }

    private fun isProfileExport(file: java.io.File): Boolean =
        file.name.startsWith(FILE_PREFIX) && file.name.endsWith(EXTENSION)

    private fun slug(label: String): String = label.lowercase()
        .map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }
        .joinToString("")
        .trim('-')
        .replace(Regex("-{2,}"), "-")
        .take(SLUG_MAX)
        .ifBlank { "profile" }

    private fun now(): Long = System.currentTimeMillis()

    private companion object {
        const val DIRECTORY = "exports"
        const val FILE_PREFIX = "gamecore-profile-"
        const val EXTENSION = ".json"

        /** Kept independently of the session CSV and the #20 backup, which share this directory. */
        const val KEEP = 5

        /** A picked file past this is refused rather than read into memory — a profile file is tiny. */
        const val MAX_IMPORT_BYTES = 4L * 1024L * 1024L
        const val READ_CHUNK_BYTES = 8 * 1024

        /** A ceiling on the `.importedN` search, so a pathological set of collisions cannot spin. */
        const val MAX_KEEP_BOTH = 999

        const val SLUG_MAX = 32
    }
}

/**
 * What to do when an imported profile's package already has a saved profile on this device.
 *
 * Chosen by the user in a dialog before anything is written — [ProfileTransfer] never picks for them, so
 * an import can never silently overwrite work. [REPLACE] overwrites the saved profile; [KEEP_BOTH] saves
 * the incoming one under a synthetic `<pkg>.imported` package (the package is the primary key, so the two
 * cannot share one) with an "(imported)" label; [SKIP] leaves the saved profile and drops the incoming one.
 */
enum class ProfileCollisionPolicy { REPLACE, KEEP_BOTH, SKIP }

/**
 * The tally an import produced. Every incoming record is accounted for exactly once across [imported],
 * [replaced], [keptBoth], [skipped] and [rejected] — the last being records the codec could not make valid
 * (a package name that was not one). [presetsCreated] / [presetsMatched] count the embedded presets recreated
 * versus reused by content; [collisions] names the packages that already existed, for the screen's summary.
 */
data class ProfileImportOutcome(
    val imported: Int = 0,
    val replaced: Int = 0,
    val keptBoth: Int = 0,
    val skipped: Int = 0,
    val rejected: Int = 0,
    val presetsCreated: Int = 0,
    val presetsMatched: Int = 0,
    val collisions: List<String> = emptyList(),
) {
    /** Profiles actually written to the database, however they got there. */
    val written: Int get() = imported + replaced + keptBoth

    val wroteAnything: Boolean get() = written > 0
}

/**
 * What an export attempt did. [Written.uri] is nullable for the reason [FileShareStore.shareUri] gives —
 * the file exists and is listed either way — and [Failed.detail] is an exception class name only, never a
 * path or a message, both of which could carry the user data the export is about.
 */
sealed interface ProfileExportResult {
    data class Written(
        val fileName: String,
        val sizeBytes: Long,
        val profileCount: Int,
        val uri: Uri?,
    ) : ProfileExportResult

    /** Nothing to export — the caller passed no profiles. Not an error. */
    data object Empty : ProfileExportResult

    data class Failed(val detail: String) : ProfileExportResult
}

/**
 * The result of staging a picked file for import: the decode succeeded and there is something to import
 * ([Ready], carrying the parsed body to hand back to [ProfileTransfer.importProfilesObject] and the list of
 * incoming profiles with their collision flags), or one of the three ways it did not.
 */
sealed interface ProfileImportStaging {
    data class Ready(val body: JSONObject, val incoming: List<IncomingProfile>) : ProfileImportStaging {
        val hasCollision: Boolean get() = incoming.any { it.collides }
    }

    /** Parsed and valid, but held no importable profile. */
    data object Empty : ProfileImportStaging

    /** The picked document could not be opened or read (revoked grant, gone, or over the size cap). */
    data object Unreadable : ProfileImportStaging

    /** Opened and read, but not JSON. */
    data object Malformed : ProfileImportStaging
}

/** One profile a staged import would create, and whether its package already has a saved profile. */
data class IncomingProfile(
    val packageName: String,
    val label: String,
    val collides: Boolean,
)

