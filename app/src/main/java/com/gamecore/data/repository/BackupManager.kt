package com.gamecore.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.gamecore.aimlab.AimLabRepository
import com.gamecore.aimlab.engine.PersonalRecord
import com.gamecore.aimlab.engine.SessionSummary
import com.gamecore.core.backup.BackupCodec
import com.gamecore.core.common.Formatters
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.model.ColorPreset
import com.gamecore.core.model.CrosshairPreset
import com.gamecore.core.model.GameProfile
import com.gamecore.core.model.GameSession
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
 * Since v2 the envelope also carries the measured history: finished game sessions, Aim Lab session summaries
 * and the personal records that belong to them. History is **additive in both restore policies** — a session
 * already on the device is matched by its identity (package, start and end for a game; mode, start, end and
 * score for Aim Lab) and skipped, so a second import of the same file changes nothing and neither mode ever
 * deletes a row the file does not mention. Personal records are written only when the file's value beats the
 * record already here, or when none exists. Plugins and the APK never enter this envelope; the screen says so
 * in plain words.
 *
 * The import is deliberately non-destructive by default: [BackupPolicy.MERGE] adds what is missing — profiles,
 * layouts and presets are matched to what is already here by content and only the genuinely new ones are
 * written, so a re-import changes nothing — and folds macros in beside the user's own. [BackupPolicy.REPLACE],
 * reached only from an explicit confirmation on the screen, differs just where a configuration conflict exists:
 * the file's version wins on a profile for a game the device also has, and its macros replace the user's rather
 * than merging. Neither mode deletes anything the file does not mention.
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
    private val sessions: SessionRepository,
    private val aimLab: AimLabRepository,
) {

    // ------------------------------------------------------------------------------------------ export

    /**
     * Gathers every configuration store and the finished session history into one document and writes it to
     * `filesDir/exports`, returning a share intent for it.
     *
     * The profiles are read once and drive two things: the profile layer builds its bundle from them (with the
     * presets they reference embedded), and their referenced layout / crosshair / colour ids are the set this
     * class *excludes* from its own top-level sections — so every referenced preset travels exactly once (via
     * the profile layer) and the standalone remainder travels here. History is read separately: game sessions
     * through [SessionRepository.snapshot] and Aim Lab through [AimLabRepository.allSessions] / [allRecords].
     * Nothing leaves the device; the file sits in the app's own storage and only the user picking a share
     * target moves it.
     */
    suspend fun export(): BackupExport = withContext(io) {
        val overlay = preferences.overlay.value
        val profiles = gameProfiles.profiles.first()
        val gameHistory = sessions.snapshot()
        val aimLabHistory = aimLab.allSessions()
        val personalRecords = aimLab.allRecords()
        val json = BackupCodec.encode(
            settings = preferences.settings.value,
            overlay = overlay,
            button = preferences.floatingButton.value,
            dock = preferences.dock.value,
            colour = preferences.colorCorrection.value,
            layouts = BackupCodec.standaloneLayouts(layoutsWithWidgets(), profiles.referenced { it.hudLayoutId }),
            crosshairs = BackupCodec.standaloneCrosshairs(crosshairs.presets.first(), profiles.referenced { it.crosshairPresetId }),
            colours = BackupCodec.standaloneColours(colours.all(), profiles.referenced { it.colorPresetId }),
            macros = macrosArray(overlay.macrosJson),
            profiles = exportProfiles(profiles),
            sessions = gameHistory,
            aimLabSessions = aimLabHistory,
            personalRecords = personalRecords,
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
     * Reads the file the user picked and reports what it contains **without applying anything**.
     *
     * This is the restore screen's preview: the four figures it asks the user to confirm (profiles, game
     * sessions, Aim Lab sessions and personal records), plus the configuration-section counts so the card can
     * be honest about a file that is settings-only. Decoding is the same gate [restore] uses; a file that is
     * refused here would be refused there, with the same typed reason.
     */
    suspend fun preview(uri: Uri): BackupPreview = withContext(io) {
        val text = readCapped(uri) ?: return@withContext BackupPreview.Failed(BackupImport.Reason.UNREADABLE)
        when (val result = BackupCodec.decode(text)) {
            is BackupCodec.BackupResult.Rejected -> BackupPreview.Failed(reasonOf(result.reason))
            is BackupCodec.BackupResult.Restored -> {
                val data = result.data
                BackupPreview.Ready(
                    version = data.version,
                    profiles = data.profiles?.optJSONArray("profiles")?.length() ?: 0,
                    layouts = data.layouts?.size ?: 0,
                    crosshairs = data.crosshairs?.size ?: 0,
                    colours = data.colours?.size ?: 0,
                    macros = data.macros?.length() ?: 0,
                    gameSessions = data.sessions.size,
                    aimLabSessions = data.aimLabSessions.size,
                    personalRecords = data.personalRecords.size,
                )
            }
        }
    }

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
        // Absent for anything written before §3.7.1, and absence leaves the user's own dock alone.
        data.dock?.let { restored -> preferences.updateDock { restored } }
        data.colour?.let { preferences.setColorCorrection(it) }
        val layoutCount = data.layouts?.let { importLayouts(it, policy) } ?: 0
        val crosshairCount = data.crosshairs?.let { importCrosshairs(it, policy) } ?: 0
        val colourCount = data.colours?.let { importColours(it, policy) } ?: 0
        val profileCount = data.profiles?.let { importProfiles(it, policy) } ?: 0
        // History is additive whatever the policy: a restore must never delete a session the file does not
        // mention. A v1 file carries no history at all, so these three calls are no-ops for it.
        val gameSessionsAdded = importGameSessions(data.sessions)
        val aimLabSessionsAdded = importAimLabSessions(data.aimLabSessions)
        val recordsAdded = importPersonalRecords(data.personalRecords)
        // The retention setting promises a purge after a restore as well as at launch. Run it after history
        // has been added so a restored old session is judged by the same rule as one recorded here.
        val purged = purgeExpired(System.currentTimeMillis()).total
        return BackupImport.Restored(
            layoutsAdded = layoutCount,
            crosshairsAdded = crosshairCount,
            coloursAdded = colourCount,
            profilesImported = profileCount,
            gameSessionsAdded = gameSessionsAdded,
            aimLabSessionsAdded = aimLabSessionsAdded,
            recordsAdded = recordsAdded,
            purgedSessions = purged,
        )
    }

    // ---------------------------------------------------------------------------------- history restore

    /**
     * Adds the file's finished game sessions, skipping any identity the device already has.
     *
     * [BackupMerge] owns the matching rule and is pure, so a re-import is idempotent and the count the screen
     * reports is the number of rows actually written. The policy is deliberately absent: history merges by
     * identity in both modes, because "replace" is about configuration conflicts, not about destroying a log.
     */
    private suspend fun importGameSessions(incoming: List<GameSession>): Int {
        if (incoming.isEmpty()) return 0
        val additions = BackupMerge.newGameSessions(sessions.snapshot(), incoming)
        return sessions.importSessions(additions)
    }

    /** Adds the file's Aim Lab summaries, skipping sessions the device already has. See [importGameSessions]. */
    private suspend fun importAimLabSessions(incoming: List<SessionSummary>): Int {
        if (incoming.isEmpty()) return 0
        val additions = BackupMerge.newAimLabSessions(aimLab.allSessions(), incoming)
        return aimLab.importSessions(additions)
    }

    /**
     * Writes the file's personal records that are missing or better than what the device holds.
     *
     * A record is evidence of a measured result, so a restore never lowers one: [BackupMerge.recordsToWrite]
     * returns only records that fill a gap or beat the current value in the metric's own direction (reaction
     * time is lower-is-better). The returned count is how many *new* records were written, not how many rows
     * were updated, so the screen's "N records" reads as new evidence.
     */
    private suspend fun importPersonalRecords(incoming: List<PersonalRecord>): Int {
        if (incoming.isEmpty()) return 0
        val existing = aimLab.allRecords()
        val writes = BackupMerge.recordsToWrite(existing, incoming)
        if (writes.isEmpty()) return 0
        aimLab.importRecords(writes)
        val existingKeys = existing.mapTo(HashSet(), BackupMerge::recordKey)
        return writes.map(BackupMerge::recordKey).filterNot { it in existingKeys }.toSet().size
    }

    // ------------------------------------------------------------------------------------ retention

    /** The stored retention setting and how many sessions an explicit "Delete now" would remove. */
    suspend fun retentionStatus(nowMillis: Long = System.currentTimeMillis()): RetentionStatus = withContext(io) {
        val days = preferences.settings.value.retentionDays
        val cutoff = RetentionPolicy.purgeBefore(nowMillis, days)
        val eligible = if (cutoff == null) {
            0
        } else {
            sessions.countFinishedBefore(cutoff) + aimLab.countSessionsBefore(cutoff)
        }
        RetentionStatus(retentionDays = days, purgeableSessions = eligible)
    }

    /** Persists the dropdown's choice. Does not purge by itself; the screen's "Delete now" is explicit. */
    suspend fun setRetentionDays(days: Int) = withContext(io) {
        preferences.updateSettings { it.copy(retentionDays = days.coerceAtLeast(0)) }
    }

    /**
     * Deletes game and Aim Lab sessions older than the stored retention window and returns what went.
     *
     * Zero (or a negative value) does nothing. Personal records are never touched: the Aim Lab repository's
     * purge deletes session rows only, and the game repository deletes sessions with their samples only.
     */
    suspend fun purgeExpired(nowMillis: Long = System.currentTimeMillis()): RetentionPurge = withContext(io) {
        val days = preferences.settings.value.retentionDays
        val cutoff = RetentionPolicy.purgeBefore(nowMillis, days)
            ?: return@withContext RetentionPurge(0, 0, days)
        val game = sessions.purgeFinishedBefore(cutoff)
        val aim = aimLab.purgeSessionsBefore(cutoff)
        RetentionPurge(gameSessions = game, aimLabSessions = aim, retentionDays = days)
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
 * [REPLACE], reached behind a confirmation, differs only where a configuration conflict exists: the file's
 * profile wins for a game the device also has, and its macros replace the user's. Neither mode deletes what the
 * file does not carry, and both merge session history by identity rather than deleting it.
 */
enum class BackupPolicy { MERGE, REPLACE }

/** The outcome of [BackupManager.preview]: what a picked file holds, or the same refusal [BackupManager.restore] gives. */
sealed interface BackupPreview {
    data class Ready(
        val version: Int,
        val profiles: Int,
        val layouts: Int,
        val crosshairs: Int,
        val colours: Int,
        val macros: Int,
        val gameSessions: Int,
        val aimLabSessions: Int,
        val personalRecords: Int,
    ) : BackupPreview

    data class Failed(val reason: BackupImport.Reason) : BackupPreview
}

/** The retention card's two numbers: the stored window and how many sessions "Delete now" would remove. */
data class RetentionStatus(
    val retentionDays: Int,
    val purgeableSessions: Int,
)

/** What [BackupManager.purgeExpired] removed, split so the message can name both histories. */
data class RetentionPurge(
    val gameSessions: Int,
    val aimLabSessions: Int,
    val retentionDays: Int,
) {
    val total: Int get() = gameSessions + aimLabSessions
}

/**
 * Pure matching rules for restoring history, kept out of the Android orchestration so they are unit-testable.
 *
 * Game sessions and Aim Lab summaries are identified by what they are, not by their local row id: a file
 * written on another device has ids that mean nothing here. Records are identified by the same key the
 * database's unique index uses, and a record is only replaced by a value that actually beats it in the
 * metric's own direction — restoring an older backup must never lower a personal best.
 */
object BackupMerge {

    /** Identity of a game session: the game, and when the recorded run started and ended. */
    fun gameSessionKey(session: GameSession): String =
        "${session.packageName}|${session.startedAtMillis}|${session.endedAtMillis ?: -1L}"

    /** Identity of an Aim Lab summary. Score is included so two runs starting in one tick stay distinct. */
    fun aimLabSessionKey(summary: SessionSummary): String =
        "${summary.mode.name}|${summary.difficulty.name}|${summary.startedAtMillis}|" +
            "${summary.endedAtMillis}|${summary.weaponName.orEmpty()}|${summary.score}"

    /** Identity of a personal record, matching the unique key on the Aim Lab records table. */
    fun recordKey(record: PersonalRecord): String = record.key

    /** The incoming game sessions the device does not already hold, in file order. */
    fun newGameSessions(existing: List<GameSession>, incoming: List<GameSession>): List<GameSession> {
        val seen = existing.mapTo(HashSet(), ::gameSessionKey)
        return incoming.filter { it.endedAtMillis != null && seen.add(gameSessionKey(it)) }
    }

    /** The incoming Aim Lab summaries the device does not already hold, in file order. */
    fun newAimLabSessions(existing: List<SessionSummary>, incoming: List<SessionSummary>): List<SessionSummary> {
        val seen = existing.mapTo(HashSet(), ::aimLabSessionKey)
        return incoming.filter { seen.add(aimLabSessionKey(it)) }
    }

    /**
     * The records worth writing: a missing key, or a value that beats the current record.
     *
     * A lower value wins for [com.gamecore.aimlab.engine.RecordMetric.FASTEST_REACTION], because that metric
     * is lower-is-better; every other metric is higher-is-better. Equal values are skipped, so importing the
     * same backup twice writes nothing the second time.
     */
    fun recordsToWrite(
        existing: List<PersonalRecord>,
        incoming: List<PersonalRecord>,
    ): List<PersonalRecord> {
        val current = existing.associateByTo(HashMap(), ::recordKey)
        val writes = ArrayList<PersonalRecord>()
        for (record in incoming) {
            val held = current[recordKey(record)]
            if (held == null || beats(record, held)) {
                current[recordKey(record)] = record
                writes += record
            }
        }
        return writes
    }

    private fun beats(candidate: PersonalRecord, current: PersonalRecord): Boolean =
        if (candidate.metric.higherIsBetter) candidate.value > current.value
        else candidate.value < current.value
}

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
        /** New game sessions added; duplicates already on the device are not counted. */
        val gameSessionsAdded: Int = 0,
        /** New Aim Lab sessions added; duplicates already on the device are not counted. */
        val aimLabSessionsAdded: Int = 0,
        /** New personal records added; a record the file did not beat is not counted. */
        val recordsAdded: Int = 0,
        /** Sessions removed by the post-restore retention sweep, when a window is set. */
        val purgedSessions: Int = 0,
    ) : BackupImport

    data class Failed(val reason: Reason) : BackupImport

    /** Why a restore could not proceed — one per case the screen turns into a line for the user. */
    enum class Reason { UNREADABLE, EMPTY, MALFORMED, WRONG_FORMAT, UNSUPPORTED_VERSION }
}




