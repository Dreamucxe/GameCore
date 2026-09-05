package com.gamecore.domain.optimization

import com.gamecore.core.common.Observed
import com.gamecore.core.common.map
import com.gamecore.core.model.CapabilityStatus
import com.gamecore.core.model.ChangeOrigin
import com.gamecore.core.model.OptimizationAction
import com.gamecore.core.model.OptimizationResult
import com.gamecore.core.model.RestoreReport
import com.gamecore.core.shizuku.WritableSetting
import com.gamecore.core.system.AudioControls
import com.gamecore.core.system.ControlOutcome
import com.gamecore.core.system.DoNotDisturbState
import com.gamecore.core.system.SettingsWriteOutcome
import com.gamecore.core.system.SettingsWriter
import com.gamecore.data.repository.PendingRestore
import com.gamecore.data.repository.RestorePointRepository
import com.gamecore.domain.display.DisplaySizeController
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one entry point for changing a device setting, and the one thing that remembers to change it
 * back.
 *
 * Everything above this class — profiles, the control panel, the performance-mode buttons — asks for
 * an [OptimizationRequest] and gets an [OptimizationResult]. Nothing above it knows which tier ran,
 * whether Shizuku was involved, or what the previous value was. That is the §25 boundary: the UI
 * expresses intent and this layer owns the mechanism.
 *
 * **Tier choice is per action, not per device.** [StandardAndroidOptimizer] and [ShizukuOptimizer]
 * are both asked what they can do with the action in hand and the better answer wins, ranked by how
 * much work the user has left to do: available, then a permission they can grant in Settings, then
 * Shizuku, then nothing. Per-device selection would be wrong in both directions — a phone with
 * WRITE_SETTINGS and no Shizuku can still do five of the eleven actions, and a phone with Shizuku
 * running still cannot set the media volume through the shell.
 *
 * The ranking also keeps a tier's honest "I cannot do this" out of the user's face. [ShizukuOptimizer]
 * reports [CapabilityStatus.UNSUPPORTED] for Do Not Disturb because the shell genuinely has no path
 * to it; that must not become the answer the dashboard shows, because the ordinary notification-policy
 * API handles it perfectly well. Only an action every tier calls unsupported is shown as unsupported.
 *
 * **The restore point is recorded before the write, here, once.** Doing it in the tiers would mean
 * two copies of the capture logic and a hole whenever the manager routed an action to the tier that
 * had not implemented it. Doing it after the write would record the value GameCore just wrote. The
 * repository's insert is first-wins at the primary key, so a second profile touching the same key
 * later in the session cannot overwrite the user's original value.
 *
 * A key whose current value cannot be read is not written at all: a change GameCore cannot undo is
 * not one it makes. That is the strictest rule in this file and it is deliberate — the alternative is
 * a phone left pinned at 60 Hz by an app that has forgotten it did that.
 *
 * **A key the user has changed since GameCore wrote it belongs to the user.** [WriteLedger] holds what
 * the last write left on each key; a [ChangeOrigin.AUTOMATIC] apply reads the key first and skips the
 * action when the two disagree, and the same check runs before every restore. Without it, switching Do
 * Not Disturb off from the notification shade during a game was undone twice over — once by the
 * session's own restore writing the recorded mode back, and again by the next start re-applying the
 * profile over the top.
 */
@Singleton
class OptimizationManager @Inject constructor(
    private val standard: StandardAndroidOptimizer,
    private val elevated: ShizukuOptimizer,
    private val settings: SettingsWriter,
    private val audio: AudioControls,
    private val displaySize: DisplaySizeController,
    private val restorePoints: RestorePointRepository,
    private val writeLog: DeviceWriteLog,
) {

    /** Both tiers, in tie-break order: the permission path is preferred when both can do it. */
    private val tiers: List<Optimizer> = listOf(standard, elevated)

    /**
     * The best any tier can offer for [action] right now.
     *
     * Asked of the device every time rather than cached here; [DeviceCapabilityChecker] owns the
     * cached view for the screens that render a whole list at once.
     */
    suspend fun statusFor(action: OptimizationAction): CapabilityStatus = best(action).status

    /**
     * The same for every action, as one map.
     *
     * One call rather than a dozen from the UI, because each status can involve a settings read and a
     * shell liveness check, and a screen that resolves them one at a time renders in stages.
     *
     * The default list is the actions the tiers actually carry out. Asking a tier about
     * [OptimizationAction.APPLY_COLOR_CORRECTION] is meaningless — neither claims it, and whether
     * the display's colour keys are writable is [com.gamecore.domain.color.ColorCorrectionController]'s
     * question, answered by its own `access()`.
     */
    suspend fun statuses(
        actions: List<OptimizationAction> = OptimizationAction.entries.filter { it.isEngineAction },
    ): Map<OptimizationAction, CapabilityStatus> =
        actions.associateWith { statusFor(it) }

    /**
     * Records what the device is about to lose, then makes one change.
     *
     * [packageName] is the game the change was made for, stored with the restore point so session
     * history can say which profile is responsible for a setting that is still pending.
     *
     * [origin] settles what happens when the user has moved this setting by hand since GameCore last
     * wrote it: an automatic change gives way and reports [OptimizationResult.Skipped], a change the
     * user asked for goes ahead. It defaults to the user's, because everything except the profile
     * applier is a button somebody just pressed — and a Performance screen that refused to pin a
     * refresh rate on the grounds that the user had changed it in Android Settings would be a worse
     * bug than the one this guard fixes.
     */
    suspend fun apply(
        request: OptimizationRequest,
        packageName: String? = null,
        origin: ChangeOrigin = ChangeOrigin.USER,
    ): OptimizationResult {
        val action = request.action
        val choice = best(action)
        if (!choice.status.isUsable) {
            return OptimizationResult.Blocked(action, choice.status, explain(action, choice.status))
        }
        capture(action, packageName, origin)?.let { return it }
        val outcome = choice.optimizer.apply(request)
        remember(action)
        return outcome
    }

    /**
     * Applies several changes in the order given, and reports on each.
     *
     * Sequential on purpose. Two settings writes racing through the same provider is how a device
     * ends up with `min_refresh_rate` above `peak_refresh_rate`, and the ordering a profile chose —
     * pin the rate, then dim the screen — is the ordering the user sees happen.
     *
     * Never short-circuits. A blocked Do Not Disturb does not stop the brightness change, because a
     * profile that half-applied and said so is more use than one that stopped at the first obstacle.
     */
    suspend fun applyAll(
        requests: List<OptimizationRequest>,
        packageName: String? = null,
        origin: ChangeOrigin = ChangeOrigin.USER,
    ): List<OptimizationResult> = requests.map { apply(it, packageName, origin) }

    /** For the dashboard's "n settings still changed" line. A count, not a list. */
    suspend fun pendingRestoreCount(): Int = restorePoints.pendingCount()

    /**
     * Puts every recorded setting back, oldest first.
     *
     * Called when a session ends, when the user asks, and on launch — because a process killed
     * mid-session leaves rows behind and the device should not stay changed until the user happens to
     * open the app again.
     *
     * A row is cleared only when the value has gone back and been read back. Anything else stays in
     * the table and lands in [RestoreReport.outstanding], so the dashboard can offer to retry rather
     * than the app quietly forgetting. That is why [RestoreReport.failures] exists: "3 settings still
     * changed" with the reasons underneath is actionable, and a silent partial restore is not.
     *
     * A key the user has taken over since GameCore wrote it is the exception: its row is cleared
     * without being written and counted in [RestoreReport.keptByUser]. See [claimedByUser] for why
     * that is not a shortcut.
     */
    suspend fun restoreAll(): RestoreReport {
        val pending = restorePoints.pending()
        if (pending.isEmpty()) return RestoreReport.NOTHING_TO_DO
        var restored = 0
        var keptByUser = 0
        val failures = mutableListOf<String>()
        for (row in pending) {
            val key = DeviceKey(row.namespace, row.key)
            if (claimedByUser(row, key)) {
                // The row goes and the claim stays. That asymmetry is the point: the user owns this key
                // now, so the next profile that wants it has to leave it alone as well.
                restorePoints.clear(row.namespace, row.key)
                keptByUser++
                continue
            }
            val failure = restoreOne(row)
            if (failure == null) {
                restorePoints.clear(row.namespace, row.key)
                // Back at the user's own value, so GameCore is no longer the last thing to have written
                // this key. Holding the claim would have every later apply refuse a setting the user
                // never touched.
                writeLog.release(key)
                restored++
            } else {
                // Kept on purpose: the device still holds GameCore's value, so the next apply may
                // still write over it, and the retry this row represents has to know that too.
                failures += failure
            }
        }
        return RestoreReport(
            restored = restored,
            outstanding = pending.size - restored - keptByUser,
            failures = failures,
            keptByUser = keptByUser,
        )
    }

    /**
     * Drops the restore table without putting anything back.
     *
     * The user's own "forget pending changes", offered next to a plain statement of what it means.
     * Nothing calls this automatically: a restore that failed is retried on the next launch, not
     * discarded, because the device is genuinely still changed.
     */
    suspend fun forgetPending() = restorePoints.forgetAll()

    // ------------------------------------------------------------------- tier choice

    private data class Choice(val optimizer: Optimizer, val status: CapabilityStatus)

    /**
     * Asks every tier and keeps the most useful answer.
     *
     * [maxByOrNull] keeps the first maximum, which is why [tiers] is ordered: when both tiers say
     * AVAILABLE the standard one runs, so a device with Shizuku still writes brightness through the
     * ordinary permission it already holds rather than through the shell.
     */
    private suspend fun best(action: OptimizationAction): Choice =
        tiers
            .map { Choice(it, it.statusFor(action)) }
            .maxByOrNull { rank(it.status) }
            ?: Choice(standard, CapabilityStatus.UNSUPPORTED)

    /** How close a status is to being usable, which is what "best" means here. */
    private fun rank(status: CapabilityStatus): Int = when (status) {
        CapabilityStatus.AVAILABLE -> 3
        // Above Shizuku deliberately: granting a permission in Settings is two taps, and setting up
        // Shizuku is an app install and a reboot-surviving service.
        CapabilityStatus.REQUIRES_PERMISSION -> 2
        CapabilityStatus.REQUIRES_SHIZUKU -> 1
        CapabilityStatus.UNSUPPORTED -> 0
    }

    /** The sentence for a change that cannot be attempted, in the user's terms rather than the API's. */
    private fun explain(action: OptimizationAction, status: CapabilityStatus): String = when (status) {
        CapabilityStatus.REQUIRES_PERMISSION ->
            "${action.label} needs a permission GameCore has not been granted yet."
        CapabilityStatus.REQUIRES_SHIZUKU ->
            "${action.label} needs the elevated shell on this device. Everything else keeps working " +
                "without it."
        CapabilityStatus.UNSUPPORTED ->
            "${action.label} is not supported on this device."
        CapabilityStatus.AVAILABLE -> action.label
    }

    // --------------------------------------------------------------- restore capture

    /**
     * Records the pre-change state for everything [action] is about to touch, and settles whether the
     * change may be made at all.
     *
     * Returns null when the write may go ahead, or the [OptimizationResult] to report instead. Two
     * things stop it, and they are the only cases where GameCore declines to do as it is told:
     *
     *  - **A key that cannot be read.** Rare — reads need no permission and fall back to the shell —
     *    and the refusal is the honest response: without a recorded value there is nothing to put
     *    back, and a setting silently kept is worse than a change not made.
     *  - **A key the user has changed since GameCore wrote it**, for a [ChangeOrigin.AUTOMATIC] change
     *    only. One contradicted key skips the whole action rather than that key alone: brightness is a
     *    level *and* a mode, and honouring half of a pair leaves the device in a state no profile ever
     *    described.
     *
     * An [Observed.Restricted] read is neither of those. A key the platform is defaulting reads as
     * absent, which is a real state and restores as [WritableSetting.restoreDefault] — `min_refresh_rate`
     * is unset on most stock builds and that is exactly the state a release should return it to.
     *
     * Every key is read before any row is recorded. A refusal on the second key of a pair would
     * otherwise leave a restore row for the first, and a row for a key nothing ever wrote has the
     * dashboard reporting a setting as changed when it is not.
     */
    private suspend fun capture(
        action: OptimizationAction,
        packageName: String?,
        origin: ChangeOrigin,
    ): OptimizationResult? {
        val readings = readKeys(action)
        readings.firstOrNull { it.unreadable }?.let { return unreadable(action, it.what) }
        if (origin == ChangeOrigin.AUTOMATIC) {
            val claimed = writeLog.current()
            if (readings.any { claimed.userChanged(it.key, it.value) }) return userOwns(action)
        }
        for (reading in readings) {
            if (reading.setting != null) {
                restorePoints.record(
                    setting = reading.setting,
                    previousValue = reading.value,
                    packageName = packageName,
                )
            } else {
                restorePoints.record(
                    key = reading.key.name,
                    previousValue = reading.value,
                    packageName = packageName,
                )
            }
        }
        return null
    }

    /**
     * Records what the device is left holding, so the next automatic apply and the session's restore
     * can tell GameCore's own value from one the user chose afterwards.
     *
     * Called after the write whatever the write returned. A write that failed left the user's value in
     * place, and claiming that value would cost them the setting on every later attempt.
     *
     * Reads the keys again rather than trusting what was asked for, because the device is what decides:
     * 50% of a fifteen-step volume stream is 53%, and `"120"` written to a float key reads back
     * `"120.0"`. A baseline that disagreed with what a read returns would call every key the user's on
     * the next poll. It is also cheap — [SettingsWriter] answers from the settings provider without a
     * shell round trip whenever the key has a value, which is exactly the case here.
     */
    private suspend fun remember(action: OptimizationAction) {
        val readings = readKeys(action)
        if (readings.isEmpty()) return
        writeLog.update { current ->
            readings.fold(current) { soFar, reading ->
                // A key that has become unreadable has no baseline worth keeping, and dropping the
                // claim lets the next apply proceed. That is the right way round: acting on a setting
                // GameCore cannot read is a smaller fault than refusing to act because of a failed read.
                if (reading.unreadable) soFar.released(reading.key)
                else soFar.wrote(reading.key, reading.value)
            }
        }
    }

    /** One key an action touches, with what the device says about it right now. */
    private data class Reading(
        val key: DeviceKey,
        /** Null for the two keys that live outside the settings provider. */
        val setting: WritableSetting?,
        /** How to name this key in a sentence to the user. */
        val what: String,
        val observed: Observed<String>,
    ) {
        /** The value as the restore table stores it: null for a key the platform is defaulting. */
        val value: String? get() = (observed as? Observed.Value)?.value

        val unreadable: Boolean get() = observed is Observed.Failed
    }

    /**
     * Reads every key [action] writes, in the order it writes them.
     *
     * One function for both sides of the write — [capture] before it, [remember] after — because the
     * two have to agree exactly on which keys an action owns. Two lists would drift, and the failure
     * would be silent: a key captured but never claimed is a key the user can never take over.
     */
    private suspend fun readKeys(action: OptimizationAction): List<Reading> =
        settingsTouchedBy(action).map { setting ->
            Reading(
                key = DeviceKey.of(setting),
                setting = setting,
                what = setting.userDescription,
                observed = settings.read(setting),
            )
        } + when (action) {
            OptimizationAction.SET_MEDIA_VOLUME -> listOf(
                Reading(
                    key = DeviceKey.nonSetting(RestorePointRepository.KEY_MEDIA_VOLUME),
                    setting = null,
                    what = "the media volume",
                    observed = audio.mediaVolumePercent().map { it.toString() },
                ),
            )

            OptimizationAction.ENABLE_DO_NOT_DISTURB -> listOf(
                Reading(
                    key = DeviceKey.nonSetting(RestorePointRepository.KEY_DO_NOT_DISTURB),
                    setting = null,
                    what = "the Do Not Disturb mode",
                    observed = audio.doNotDisturbState().map { it.name },
                ),
            )

            else -> emptyList()
        }

    private fun unreadable(action: OptimizationAction, what: String) = OptimizationResult.Failed(
        action = action,
        detail = "GameCore could not read $what, so it did not change it — a change it cannot " +
            "put back is not one it will make.",
    )

    /**
     * The report for a change GameCore chose not to make because the user had already made their own.
     *
     * [OptimizationResult.Skipped] rather than a failure, because nothing went wrong: the device is in
     * the state the most recent instruction asked for, and that instruction was the user's. Skips are
     * counted out of [com.gamecore.core.model.ProfileApplication.attemptedCount] for the same reason,
     * so a profile that leaves one setting to the user still reports the rest honestly.
     */
    private fun userOwns(action: OptimizationAction) = OptimizationResult.Skipped(
        action = action,
        reason = "You changed this yourself after GameCore set it, so it has been left alone.",
    )

    /**
     * Every settings key an action writes, in the order it writes them.
     *
     * The order is the restore order — the repository hands rows back oldest first — and it matters
     * twice. Brightness is recorded value-then-mode so the restore puts the level back and *then*
     * hands control to the light sensor; rotation the same way round. The refresh-rate pair is
     * recorded min-then-peak because some builds reject a peak below the current min, and replaying
     * min first can never produce that.
     *
     * Media volume and Do Not Disturb are absent because neither has a settings key an app can rely
     * on; [readKeys] reads them through the audio API instead and [capture] stores them under the
     * repository's non-setting namespace.
     */
    private fun settingsTouchedBy(action: OptimizationAction): List<WritableSetting> = when (action) {
        OptimizationAction.SET_BRIGHTNESS -> listOf(
            WritableSetting.SCREEN_BRIGHTNESS,
            WritableSetting.SCREEN_BRIGHTNESS_MODE,
        )

        OptimizationAction.LOCK_ROTATION -> listOf(
            WritableSetting.USER_ROTATION,
            WritableSetting.ACCELEROMETER_ROTATION,
        )

        OptimizationAction.EXTEND_SCREEN_TIMEOUT -> listOf(WritableSetting.SCREEN_OFF_TIMEOUT)

        // Including the release action: a user releasing a rate GameCore never pinned still has a
        // previous value worth keeping, and first-wins means a genuine pin already recorded is safe.
        OptimizationAction.PIN_PEAK_REFRESH_RATE,
        OptimizationAction.PIN_LOWEST_REFRESH_RATE,
        OptimizationAction.RELEASE_REFRESH_RATE,
        -> listOf(WritableSetting.MIN_REFRESH_RATE, WritableSetting.PEAK_REFRESH_RATE)

        OptimizationAction.DISABLE_ANIMATIONS -> listOf(
            WritableSetting.WINDOW_ANIMATION_SCALE,
            WritableSetting.TRANSITION_ANIMATION_SCALE,
            WritableSetting.ANIMATOR_DURATION_SCALE,
        )

        OptimizationAction.ENABLE_BATTERY_SAVER,
        OptimizationAction.DISABLE_BATTERY_SAVER,
        -> listOf(WritableSetting.LOW_POWER)

        OptimizationAction.SET_MEDIA_VOLUME,
        OptimizationAction.ENABLE_DO_NOT_DISTURB,
        -> emptyList()

        // Empty, and not because the action writes nothing. It writes between one and eight of the
        // display's colour keys depending on what the preset asks for, and `ColorCorrectionController`
        // records each one immediately before writing it. Listing all eight here would record rows
        // for keys this correction never touches, and the restore at session end would hand back a
        // colour-vision filter the user set themselves. Nothing routes this action through here —
        // `OptimizationAction.isEngineAction` is false for it and both tiers decline it — so this
        // branch exists to keep the `when` exhaustive and to say why.
        OptimizationAction.APPLY_COLOR_CORRECTION -> emptyList()

        // Empty because there is no settings key involved at all. `wm size` is a window-manager
        // command, not a row in `settings`, so there is nothing here for [readKeys] to read before
        // the write or [restoreSetting] to put back after it. `DisplaySizeController` records the
        // size the display had under the repository's non-setting namespace and the restore is
        // dispatched by key below. Like the branch above, nothing routes this action through here.
        OptimizationAction.SET_DISPLAY_SIZE -> emptyList()
    }

    // -------------------------------------------------------------------- restoring

    /**
     * Whether [row]'s key has been changed by the user since GameCore wrote it, in which case the
     * recorded value is not to be put back.
     *
     * This is the restore side of the rule [capture] applies, and it is not optional extra care. A user
     * whose normal state is "priority only", given total silence by a profile and switching Do Not
     * Disturb off by hand during the game, would otherwise have "priority only" written back at session
     * end — which from the notification shade is indistinguishable from GameCore turning Do Not Disturb
     * on again, and is the second half of the bug that was reported.
     *
     * False for anything [DeviceWriteLog] does not track. The colour keys are tracked even though this
     * class never writes them — [com.gamecore.domain.color.ColorCorrectionController] claims each sink
     * as it writes it, into the same log, precisely so that they are guarded here — while
     * [DisplaySizeController]'s row is not, and restores exactly as it always did: `wm size` is a
     * window-manager override with no tile and no settings key, so there is no way for a user to change
     * it by hand and nothing for [liveValue] to read.
     */
    private suspend fun claimedByUser(row: PendingRestore, key: DeviceKey): Boolean {
        val claimed = writeLog.current()
        if (!claimed.tracks(key)) return false
        return when (val live = liveValue(row)) {
            is Observed.Value -> claimed.userChanged(key, live.value)
            // Unset is a state, not a missing answer: a key GameCore wrote and something has since
            // cleared is a key it no longer owns.
            is Observed.Restricted -> claimed.userChanged(key, null)
            // A failed read, or a key with no way to read it: no evidence either way, so the recorded
            // value goes back. A setting left changed is the failure this table exists to prevent, and
            // between the two mistakes it is the worse one.
            else -> false
        }
    }

    /** What the device holds for [row]'s key now, or null when there is no way to ask. */
    private suspend fun liveValue(row: PendingRestore): Observed<String>? {
        val setting = row.setting
        if (setting != null) return settings.read(setting)
        return when (row.key) {
            RestorePointRepository.KEY_MEDIA_VOLUME ->
                audio.mediaVolumePercent().map { it.toString() }

            RestorePointRepository.KEY_DO_NOT_DISTURB ->
                audio.doNotDisturbState().map { it.name }

            else -> null
        }
    }

    /**
     * Puts one row back. Null when it is genuinely back, otherwise the sentence to show.
     *
     * [PendingRestore.setting] is null for the rows stored under the non-setting namespace and for a
     * key a future build has dropped from its allow-list; the first is dispatched by key below and the
     * second is why the fall-through says so rather than pretending.
     */
    private suspend fun restoreOne(row: PendingRestore): String? {
        val setting = row.setting
        if (setting != null) return restoreSetting(row, setting)
        return when (row.key) {
            RestorePointRepository.KEY_MEDIA_VOLUME -> {
                val percent = row.previousValue?.toIntOrNull()
                    ?: return "No media volume was recorded to go back to."
                audio.setMediaVolumePercent(percent).restoreFailure("Media volume")
            }

            RestorePointRepository.KEY_DO_NOT_DISTURB -> {
                val mode = row.previousValue
                    ?.let { name -> DoNotDisturbState.entries.firstOrNull { it.name == name } }
                    ?: return "No Do Not Disturb mode was recorded to go back to."
                audio.setDoNotDisturbState(mode).restoreFailure("Do Not Disturb")
            }

            // A null previous value is the ordinary case rather than a missing recording, so unlike the
            // two above there is nothing to refuse here: it means the display had no override before
            // GameCore set one, and the way back is `wm size reset`. The controller reads the result back
            // before reporting it, so a row only clears once the display has actually returned.
            RestorePointRepository.KEY_DISPLAY_SIZE -> {
                val outcome = displaySize.restore(row.previousValue)
                if (outcome.isSuccess) null else "The display size is still changed: ${outcome.message}"
            }

            else -> "This version of GameCore does not know how to restore \"${row.key}\"."
        }
    }

    private suspend fun restoreSetting(row: PendingRestore, setting: WritableSetting): String? =
        when (val outcome = settings.restore(setting, row.previousValue)) {
            is SettingsWriteOutcome.Applied -> null

            // A key that was unset before reads as unset again, so there is nothing to compare the
            // write against and the writer can only call it unverified. That is the state the restore
            // was aiming at, so the row is done. Any other unverified restore stays pending.
            is SettingsWriteOutcome.AppliedUnverified ->
                if (row.restoresToUnset) {
                    null
                } else {
                    "${row.describe()} was written back but could not be confirmed."
                }

            is SettingsWriteOutcome.NotHonoured ->
                "${row.describe()} stayed at ${outcome.actual}."

            is SettingsWriteOutcome.RequiresAccess -> outcome.detail
            is SettingsWriteOutcome.Rejected -> outcome.detail
            is SettingsWriteOutcome.Failed -> outcome.detail
        }

    /**
     * Whether a control-layer restore counts as done.
     *
     * [ControlOutcome.Applied] is taken at face value here, including the variant whose detail says
     * the value could not be read back. It is the one place this app accepts an unconfirmed write as
     * finished, and the reason is that the alternative is worse: a row that can never clear makes the
     * dashboard claim the device is still changed on every launch, and the user has no way to settle
     * it. Settings-backed rows, which are the ones that pin a display or dim a screen, have the full
     * six-case treatment in [restoreSetting].
     */
    private fun ControlOutcome.restoreFailure(what: String): String? = when (this) {
        is ControlOutcome.Applied -> null
        is ControlOutcome.Unsupported -> "$what could not be put back: $detail"
        is ControlOutcome.RequiresAccess -> "$what could not be put back: $detail"
        is ControlOutcome.Failed -> "$what could not be put back: $detail"
    }
}
