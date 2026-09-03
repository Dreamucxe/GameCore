package com.gamecore.domain.optimization

import com.gamecore.core.common.Observed
import com.gamecore.core.model.CapabilityStatus
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
 */
@Singleton
class OptimizationManager @Inject constructor(
    private val standard: StandardAndroidOptimizer,
    private val elevated: ShizukuOptimizer,
    private val settings: SettingsWriter,
    private val audio: AudioControls,
    private val displaySize: DisplaySizeController,
    private val restorePoints: RestorePointRepository,
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
     */
    suspend fun apply(
        request: OptimizationRequest,
        packageName: String? = null,
    ): OptimizationResult {
        val action = request.action
        val choice = best(action)
        if (!choice.status.isUsable) {
            return OptimizationResult.Blocked(action, choice.status, explain(action, choice.status))
        }
        capture(action, packageName)?.let { return it }
        return choice.optimizer.apply(request)
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
    ): List<OptimizationResult> = requests.map { apply(it, packageName) }

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
     */
    suspend fun restoreAll(): RestoreReport {
        val pending = restorePoints.pending()
        if (pending.isEmpty()) return RestoreReport.NOTHING_TO_DO
        var restored = 0
        val failures = mutableListOf<String>()
        for (row in pending) {
            val failure = restoreOne(row)
            if (failure == null) {
                restorePoints.clear(row.namespace, row.key)
                restored++
            } else {
                failures += failure
            }
        }
        return RestoreReport(
            restored = restored,
            outstanding = pending.size - restored,
            failures = failures,
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
     * Records the pre-change state for everything [action] is about to touch.
     *
     * Returns null when the capture is complete and the write may go ahead, or the
     * [OptimizationResult] to report when it could not be — the one case where GameCore refuses a
     * change the user asked for. An unreadable key is rare (reads need no permission and fall back to
     * the shell), and the refusal is the honest response: without a recorded value there is nothing to
     * put back, and a setting silently kept is worse than a change not made.
     *
     * An [Observed.Restricted] read is *not* that case. A key the platform is defaulting reads as
     * absent, which is a real state and restores as [WritableSetting.restoreDefault] — `min_refresh_rate`
     * is unset on most stock builds and that is exactly the state a release should return it to.
     */
    private suspend fun capture(
        action: OptimizationAction,
        packageName: String?,
    ): OptimizationResult? {
        for (setting in settingsTouchedBy(action)) {
            when (val current = settings.read(setting)) {
                is Observed.Value -> restorePoints.record(setting, current.value, packageName)
                is Observed.Restricted -> restorePoints.record(setting, null, packageName)
                is Observed.Failed -> return unreadable(action, setting.userDescription)
            }
        }
        return when (action) {
            OptimizationAction.SET_MEDIA_VOLUME -> {
                val level = audio.mediaVolumePercent()
                if (level is Observed.Value) {
                    restorePoints.record(
                        key = RestorePointRepository.KEY_MEDIA_VOLUME,
                        previousValue = level.value.toString(),
                        packageName = packageName,
                    )
                    null
                } else {
                    unreadable(action, "the current media volume")
                }
            }

            OptimizationAction.ENABLE_DO_NOT_DISTURB -> {
                val mode = audio.doNotDisturbState()
                if (mode is Observed.Value) {
                    restorePoints.record(
                        key = RestorePointRepository.KEY_DO_NOT_DISTURB,
                        previousValue = mode.value.name,
                        packageName = packageName,
                    )
                    null
                } else {
                    unreadable(action, "the current Do Not Disturb mode")
                }
            }

            else -> null
        }
    }

    private fun unreadable(action: OptimizationAction, what: String) = OptimizationResult.Failed(
        action = action,
        detail = "GameCore could not read $what, so it did not change it — a change it cannot " +
            "put back is not one it will make.",
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
     * on; [capture] handles them through the audio API and stores them under the repository's
     * non-setting namespace.
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
        // command, not a row in `settings`, so there is nothing here for [capture] to read before
        // the write or [restoreSetting] to put back after it. `DisplaySizeController` records the
        // size the display had under the repository's non-setting namespace and the restore is
        // dispatched by key below. Like the branch above, nothing routes this action through here.
        OptimizationAction.SET_DISPLAY_SIZE -> emptyList()
    }

    // -------------------------------------------------------------------- restoring

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
