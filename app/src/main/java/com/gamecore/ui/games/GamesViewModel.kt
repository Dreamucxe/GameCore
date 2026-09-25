package com.gamecore.ui.games

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.common.Formatters
import com.gamecore.core.model.DetectionAvailability
import com.gamecore.core.model.GameProfile
import com.gamecore.core.model.profileChips
import com.gamecore.core.overlay.ApplyDiff
import com.gamecore.core.system.AppLauncher
import com.gamecore.core.system.InstalledAppLister
import com.gamecore.data.preferences.SecurePreferenceStore
import com.gamecore.data.repository.GameProfileRepository
import com.gamecore.data.repository.ProfileCollisionPolicy
import com.gamecore.data.repository.ProfileExportResult
import com.gamecore.data.repository.ProfileImportOutcome
import com.gamecore.data.repository.ProfileImportStaging
import com.gamecore.data.repository.ProfileTransfer
import com.gamecore.domain.gaming.GameDetector
import com.gamecore.domain.gaming.GamingCoordinator
import com.gamecore.domain.gaming.ProfileApplier
import com.gamecore.domain.network.PreLaunchNetworkCheck
import com.gamecore.ui.components.PendingLaunch
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject

/**
 * The Games screen's state and the five things it can do: toggle a profile, start its game, apply one by
 * hand, delete one, and turn automatic application on or off.
 *
 * The row list is derived in its own `map` over the repository flow rather than inside the [combine],
 * because building it queries the package manager once per profile to find out whether the game is still
 * installed. Folding that into the combine would re-query every package every time the gaming state
 * changed — which it does at the start and end of every session.
 */
@HiltViewModel
class GamesViewModel @Inject constructor(
    private val profiles: GameProfileRepository,
    private val installedApps: InstalledAppLister,
    private val launcher: AppLauncher,
    private val detector: GameDetector,
    private val applier: ProfileApplier,
    private val preLaunchCheck: PreLaunchNetworkCheck,
    private val preferences: SecurePreferenceStore,
    private val transfer: ProfileTransfer,
    coordinator: GamingCoordinator,
) : ViewModel() {

    private val local = MutableStateFlow(LocalState())

    /**
     * §5 import / export, kept in its own flow rather than folded into [GamesUiState].
     *
     * Two things live here that the main state has no field for and should not grow one: a one-shot share
     * request the screen fires exactly once, and the collision prompt an import raises before it writes
     * anything. Everything an import or export has to *say* still goes through the same message banner the
     * rest of the screen uses ([LocalState.message]); only these two pieces of transient machinery are here.
     */
    private val transferLocal = MutableStateFlow(TransferUiState())
    val transferState: StateFlow<TransferUiState> = transferLocal.asStateFlow()

    /**
     * The parsed body of a staged import that collided, held until the user picks a policy. Kept off the
     * exposed state on purpose: the raw `org.json` document and whatever it carries never reach a composable.
     */
    private var stagedImport: JSONObject? = null

    /** Bumped per share request so a repeated export of the same file is still a fresh one-shot to fire. */
    private var shareCounter = 0

    private val rows = profiles.profiles.map { saved -> saved.map { rowFor(it) } }

    val state: StateFlow<GamesUiState> = combine(
        rows,
        coordinator.gaming,
        preferences.settings,
        local,
    ) { list, gaming, settings, own ->
        GamesUiState(
            profiles = list.map { it.copy(isPlaying = it.packageName == gaming.playing) },
            isLoaded = true,
            detectionAvailable = own.detection?.isAvailable ?: false,
            detectionNote = (own.detection as? DetectionAvailability.Unavailable)?.reason,
            detectionRemedy = (own.detection as? DetectionAvailability.Unavailable)?.remedy,
            autoApply = settings.autoApplyProfiles,
            isCompact = settings.compactDensity,
            busyPackage = own.busyPackage,
            pendingLaunch = own.pendingLaunch,
            message = own.message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MILLIS),
        initialValue = GamesUiState(),
    )

    init {
        refreshDetection()
    }

    /**
     * Re-asks whether foreground detection is possible.
     *
     * Called on every resume, not just on entry. The remedy this screen offers is a trip to a system
     * settings page, and the user comes back to a composable that never left composition — so without
     * this the screen would keep saying "no access" after the access was granted.
     */
    fun refreshDetection() {
        viewModelScope.launch {
            // Read after the call, never as an argument inside the `copy(...)`: the receiver is evaluated
            // before the argument, so the inline form would capture the state from before the suspend and
            // write it back over anything an action landed in the meantime — a toast from `play`, or the
            // busy marker on a row.
            val detection = detector.availability()
            local.value = local.value.copy(detection = detection)
        }
    }

    private suspend fun rowFor(profile: GameProfile) = GameRow(
        packageName = profile.packageName,
        label = profile.label,
        isEnabled = profile.isEnabled,
        isInstalled = installedApps.isInstalled(profile.packageName),
        // The same generator the Home hero uses, so one profile cannot describe itself two ways on two
        // screens. `changesNothing` is read off the profile beside it rather than inferred from the chip
        // list: the two deliberately disagree on a fresh profile, and the card needs both to be honest.
        chips = profileChips(profile),
        changesNothing = profile.changesNothing,
        isPlaying = false,
    )

    // -------------------------------------------------------------------------------------- actions

    /**
     * Turns one profile on or off.
     *
     * Disabling leaves everything else about the profile alone, which is the point: the user who has
     * spent five minutes on a game's settings and wants GameCore to stop touching it for a week should
     * not have to delete their work to get that.
     */
    fun setEnabled(packageName: String, isEnabled: Boolean) {
        viewModelScope.launch {
            val profile = profiles.profileFor(packageName) ?: return@launch
            profiles.save(profile.copy(isEnabled = isEnabled))
        }
    }

    /**
     * Starts the game this profile is for.
     *
     * Starting it and nothing else. Applying the profile is the button next to this one, and the two are
     * kept apart deliberately: with automatic application on, the detector applies the profile a moment
     * after the game reaches the front, and doing it here as well would mean one tap that changed the
     * refresh rate on some devices and not others. A user who wants both taps Apply now, then Play.
     *
     * Only a failure is reported. A launch that worked is already obvious — the game is on screen — and a
     * message posted behind it would be read on the way back out, minutes later, as news.
     *
     * The §C4 network check runs in front of this when the profile asked for it. A warning holds the
     * launch for the user to decide rather than cancelling it — see [PreLaunchNetworkCheck].
     */
    fun play(packageName: String) {
        if (local.value.busyPackage != null) return
        viewModelScope.launch {
            val profile = profiles.profileFor(packageName)
            val warning = profile?.let { preLaunchCheck.evaluate(it) }
            if (warning != null) {
                local.value = local.value.copy(
                    pendingLaunch = PendingLaunch(packageName, warning.reason),
                )
                return@launch
            }
            start(packageName)
        }
    }

    /**
     * Starts the game, reporting only a refusal.
     *
     * Split out of [play] so the §C4 dialog's two launching answers reach the same call this does — a
     * second call site would be a second chance to diverge.
     */
    private suspend fun start(packageName: String) {
        val outcome = launcher.launch(packageName)
        if (!outcome.isApplied) {
            local.value = local.value.copy(message = outcome.message)
        }
    }

    /** Launches the game the §C4 warning is holding, leaving the profile's setting alone. */
    fun confirmPendingLaunch() {
        val pending = local.value.pendingLaunch ?: return
        local.value = local.value.copy(pendingLaunch = null)
        viewModelScope.launch { start(pending.packageName) }
    }

    /**
     * Launches the game and stops this profile asking again.
     *
     * Only the pre-launch warning is turned off. The check itself and the in-session alert are separate
     * switches and are left exactly as the user set them.
     */
    fun dontWarnPendingLaunch() {
        val pending = local.value.pendingLaunch ?: return
        local.value = local.value.copy(pendingLaunch = null)
        viewModelScope.launch {
            profiles.profileFor(pending.packageName)?.let { profile ->
                profiles.save(profile.copy(networkPreLaunchWarn = false))
            }
            start(pending.packageName)
        }
    }

    /** Drops the held launch. Nothing starts and nothing is saved. */
    fun dismissPendingLaunch() {
        local.value = local.value.copy(pendingLaunch = null)
    }

    fun setAutoApply(enabled: Boolean) {
        preferences.updateSettings { it.copy(autoApplyProfiles = enabled) }
        local.value = local.value.copy(
            message = if (enabled) {
                null
            } else {
                "Profiles will only be applied when you tap Apply."
            },
        )
    }

    /**
     * Applies one profile now, without waiting for the game to start.
     *
     * Every settings write it attempts is reported back verbatim through
     * [com.gamecore.core.model.ProfileApplication.summary] — including the ones that were refused. A
     * profile that could not change the refresh rate says so here rather than flashing "Applied" and
     * leaving the user to notice the panel is still at 60 Hz.
     */
    fun applyNow(packageName: String) {
        if (local.value.busyPackage != null) return
        local.value = local.value.copy(busyPackage = packageName, message = null)
        viewModelScope.launch {
            val profile = profiles.profileFor(packageName)
            if (profile == null) {
                local.value = local.value.copy(
                    busyPackage = null,
                    message = "That profile is no longer saved.",
                )
                return@launch
            }
            val application = applier.apply(profile)
            local.value = local.value.copy(
                busyPackage = null,
                message = when {
                    application.changedNothing -> "This profile has nothing to apply yet."
                    else -> ApplyDiff.summarize(application.results) ?: application.summary()
                },
            )
        }
    }

    /** Puts back whatever the last manual apply changed. Paired with [applyNow], not with a session. */
    fun restoreNow() {
        if (local.value.busyPackage != null) return
        local.value = local.value.copy(busyPackage = RESTORE_MARKER, message = null)
        viewModelScope.launch {
            val report = applier.restore()
            local.value = local.value.copy(
                busyPackage = null,
                message = listOfNotNull(
                    when {
                        report.didNothing -> "There was nothing to put back."
                        // Nothing to claim credit for: everything pending turned out to be the user's.
                        report.restored == 0 && report.isComplete -> null
                        report.isComplete -> "Put back ${Formatters.count(report.restored, "setting")}."
                        else -> "Put back ${report.restored}. ${report.outstanding} still need an access " +
                            "GameCore does not currently have."
                    },
                    report.keptNote,
                ).joinToString(" "),
            )
        }
    }

    fun delete(packageName: String) {
        viewModelScope.launch {
            profiles.delete(packageName)
            local.value = local.value.copy(message = "Profile deleted.")
        }
    }

    fun dismissMessage() {
        local.value = local.value.copy(message = null)
    }

    // ---------------------------------------------------------------------------- import / export (§5)

    /**
     * Writes one profile — with every preset it references embedded — to a file and offers the share sheet.
     *
     * A single profile, not "all": this is reached from a per-card overflow, and a user who wants everything
     * at once has the backup screen for that. The file is written first and only then offered, as a one-shot
     * the screen fires through [shareHandled], so the content:// grant never outlives the launch it is for.
     */
    fun exportProfile(packageName: String) {
        viewModelScope.launch {
            val profile = profiles.profileFor(packageName)
            if (profile == null) {
                local.value = local.value.copy(message = "That profile is no longer saved.")
                return@launch
            }
            when (val result = transfer.export(listOf(profile))) {
                is ProfileExportResult.Written -> {
                    val intent = result.uri?.let(::shareIntent)
                    transferLocal.value = transferLocal.value.copy(
                        share = intent?.let { ProfileShareRequest(++shareCounter, it) },
                    )
                    if (intent == null) {
                        local.value = local.value.copy(
                            message = "Saved ${result.fileName}, but this device would not share it.",
                        )
                    }
                }
                ProfileExportResult.Empty ->
                    local.value = local.value.copy(message = "There was nothing to export.")
                is ProfileExportResult.Failed ->
                    local.value = local.value.copy(
                        message = "That profile could not be exported. Storage may be full.",
                    )
            }
        }
    }

    /**
     * The share-sheet intent for a written export. Built here rather than in the composable so the content://
     * URI and its read grant stay out of the presentation layer — the same split [SessionReportViewModel] makes.
     */
    private fun shareIntent(uri: Uri): Intent =
        Intent(Intent.ACTION_SEND)
            .setType(SHARE_MIME)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    /** Consumes the one-shot share, and says so when nothing on the device would accept the file. */
    fun shareHandled(shared: Boolean) {
        transferLocal.value = transferLocal.value.copy(share = null)
        if (!shared) {
            local.value = local.value.copy(message = "No app on this device can accept that file.")
        }
    }

    /**
     * Reads a picked document and either imports it at once or asks the user how to resolve a collision.
     *
     * No collision → written immediately (the policy is moot when nothing clashes, so SKIP imports them all).
     * Any collision → the parsed body is held and a prompt raised, because §5 forbids an import silently
     * overwriting a profile already saved.
     */
    fun importFrom(uri: Uri) {
        viewModelScope.launch {
            when (val staged = transfer.stageImport(uri)) {
                is ProfileImportStaging.Ready ->
                    if (!staged.hasCollision) {
                        finishImport(transfer.importProfilesObject(staged.body, ProfileCollisionPolicy.SKIP))
                    } else {
                        stagedImport = staged.body
                        transferLocal.value = transferLocal.value.copy(
                            collision = ImportCollisionPrompt(
                                total = staged.incoming.size,
                                collidingLabels = staged.incoming.filter { it.collides }.map { it.label },
                            ),
                        )
                    }
                ProfileImportStaging.Empty ->
                    local.value = local.value.copy(message = "That file had no profiles to import.")
                ProfileImportStaging.Unreadable ->
                    local.value = local.value.copy(message = "That file could not be read.")
                ProfileImportStaging.Malformed ->
                    local.value = local.value.copy(message = "That file is not a GameCore profile export.")
            }
        }
    }

    /** Commits the held import under the policy the user chose, then clears the prompt. */
    fun resolveImport(policy: ProfileCollisionPolicy) {
        val body = stagedImport ?: return
        stagedImport = null
        transferLocal.value = transferLocal.value.copy(collision = null)
        viewModelScope.launch { finishImport(transfer.importProfilesObject(body, policy)) }
    }

    /** Drops the held import. Nothing is written and nothing is changed. */
    fun cancelImport() {
        stagedImport = null
        transferLocal.value = transferLocal.value.copy(collision = null)
    }

    private fun finishImport(outcome: ProfileImportOutcome) {
        local.value = local.value.copy(message = summarise(outcome))
    }

    /**
     * The one line an import leaves in the banner. Every incoming record is accounted for — written, skipped
     * or discarded — because an import that reports only what it added while silently dropping the rest is
     * exactly the quiet loss §5 is trying to avoid.
     */
    private fun summarise(outcome: ProfileImportOutcome): String {
        val done = buildList {
            if (outcome.imported > 0) add("added ${Formatters.count(outcome.imported, "profile")}")
            if (outcome.replaced > 0) add("replaced ${Formatters.count(outcome.replaced, "profile")}")
            if (outcome.keptBoth > 0) add("kept ${Formatters.count(outcome.keptBoth, "copy", "copies")}")
            if (outcome.skipped > 0) add("skipped ${Formatters.count(outcome.skipped, "profile")}")
            if (outcome.rejected > 0) {
                add("discarded ${Formatters.count(outcome.rejected, "unreadable entry", "unreadable entries")}")
            }
        }
        if (done.isEmpty()) return "Nothing was imported."
        val sentence = done.joinToString(", ").replaceFirstChar { it.uppercase() } + "."
        return if (outcome.presetsCreated > 0) {
            "$sentence Created ${Formatters.count(outcome.presetsCreated, "preset")}."
        } else {
            sentence
        }
    }

    private companion object {
        const val SUBSCRIPTION_GRACE_MILLIS = 5_000L

        /** A `busyPackage` value no package can equal, so the restore button disables with the rest. */
        const val RESTORE_MARKER = " restore"

        /** The one type a profile export is: a JSON document, offered to whatever reads them. */
        const val SHARE_MIME = "application/json"
    }
}

private data class LocalState(
    val detection: DetectionAvailability? = null,
    val busyPackage: String? = null,
    val pendingLaunch: PendingLaunch? = null,
    val message: String? = null,
)

/**
 * The transient state of §5 transfer: a one-shot share request to fire, and a collision prompt to answer.
 *
 * What an import or export has to *say* is not here — it goes through the same message banner the rest of
 * the screen uses. Only the machinery a composable has to react to structurally lives here: an intent that
 * must be launched exactly once, and a dialog that must be shown before anything is written.
 */
data class TransferUiState(
    val share: ProfileShareRequest? = null,
    val collision: ImportCollisionPrompt? = null,
)

/** A written export waiting to be handed to the share sheet once; [id] makes a repeat export a fresh shot. */
data class ProfileShareRequest(val id: Int, val intent: Intent)

/**
 * What a staged import found already saved, raised before anything is written so the user chooses
 * replace / keep both / cancel. [collidingLabels] names the clashes; [total] is every incoming profile,
 * so the dialog can say "3 of 5 are already saved" rather than only naming the three.
 */
data class ImportCollisionPrompt(
    val total: Int,
    val collidingLabels: List<String>,
)
