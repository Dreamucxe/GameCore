package com.gamecore.domain.display

import com.gamecore.core.common.DataSource
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.common.Observed
import com.gamecore.core.common.valueOrNull
import com.gamecore.core.model.AspectPreset
import com.gamecore.core.model.DisplaySize
import com.gamecore.core.model.DisplaySizeOutcome
import com.gamecore.core.model.DisplaySizeState
import com.gamecore.core.model.ResolutionScale
import com.gamecore.core.shizuku.ElevatedShell
import com.gamecore.core.shizuku.ShellCommand
import com.gamecore.core.system.DisplayReader
import com.gamecore.core.system.DisplaySizeParser
import com.gamecore.data.repository.RestorePointRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stretches the display to a game's aspect ratio, and gives the panel back afterwards.
 *
 * This is the most consequential thing GameCore writes, and the reasons are worth stating before the
 * code. `wm size` prints nothing on success. Its effect survives a reboot, so the usual escape from a
 * bad display setting is not available. And a size small enough, or a shape narrow enough, leaves a
 * screen the user cannot read well enough to undo it with. So three rules are enforced here, and none
 * of them are negotiable per device:
 *
 *  1. **A size is refused before it is written.** [DisplaySize.rejectionFor] checks the request against
 *     the panel it is going onto, and the sentence it returns is the one the user reads.
 *  2. **The previous size is recorded before the write, not after.** The same rule and the same reason
 *     as `ColorCorrectionController.applyOne` and `OptimizationManager.capture`: a restore point taken
 *     after a successful write holds the value GameCore itself wrote. A current size that cannot be
 *     read is therefore not overridden at all — with nothing recorded there is nothing to put back.
 *  3. **Nothing is reported as applied until it has been read back.** `RefreshRateController.verify`
 *     is the template, including its asymmetry between witnesses. `wm size` is the primary one and is
 *     trusted in both directions; the app's own logical size is trusted only when it *confirms*,
 *     because a stale configuration in this process is not evidence the display refused anything.
 *
 * What this is not: a second Shizuku path, or a second way to revert. Every command here goes through
 * [ElevatedShell] like every other elevated read and write in the app, and the undo is a row in
 * [RestorePointRepository] under [RestorePointRepository.KEY_DISPLAY_SIZE] that
 * `OptimizationManager.restoreOne` replays alongside every other pending change. There is no separate
 * bookkeeping for display size, which is what keeps the Home dashboard's count of outstanding changes
 * true.
 *
 * And what the feature is not, which the copy has to say out loud: this stretches the image the game
 * renders across the panel. It is not a wider field of view. A game handed a 4:3 logical display draws
 * a 4:3 frame, and the compositor spreads it over the whole screen.
 */
@Singleton
class DisplaySizeController @Inject constructor(
    private val shell: ElevatedShell,
    private val display: DisplayReader,
    private val restorePoints: RestorePointRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * The panel's own size and whatever is currently sitting on top of it.
     *
     * Needs the shell, and says so rather than approximating. The app's own logical size is available
     * without it — see [DisplayReader.logicalSize] — but that reports the *active* size, so on a device
     * with an override already set it would report the stretched size as the panel's own and every
     * preset would be computed from the wrong number. "Nothing is overridden" is not a safe default for
     * the one setting that outlives a reboot.
     */
    suspend fun state(): Observed<DisplaySizeState> = withContext(io) { readState() }

    /**
     * Whether this device can change its display size at all right now.
     *
     * Separate from [state] because the answer is useful before anything has been read: the tile can be
     * drawn disabled with the reason on it, rather than appearing to work and failing on tap.
     */
    suspend fun access(): Observed<Unit> = withContext(io) {
        if (shell.isAvailable()) {
            Observed.of(Unit, DataSource.SHELL_SHIZUKU)
        } else {
            Observed.needsElevation(SHIZUKU_REQUIRED)
        }
    }

    /**
     * Stretches the display to [preset].
     *
     * [AspectPreset.NATIVE] is routed to [resetToNative] rather than written as an override that happens
     * to equal the panel. The two are different states: an override equal to the physical size is still
     * a row in the window manager's settings that outlives GameCore, and only `wm size reset` removes it.
     */
    suspend fun apply(preset: AspectPreset, packageName: String? = null): DisplaySizeOutcome =
        withContext(io) {
            if (preset == AspectPreset.NATIVE) return@withContext resetToNative()
            val physical = readState().valueOrNull?.physical ?: return@withContext unreadable()
            val size = preset.sizeFor(physical)
                ?: return@withContext DisplaySizeOutcome.SizeUnsupported(
                    requested = physical,
                    detail = "${preset.label} is not a shape a ${physical.aspectLabel} display can be " +
                        "stretched to. GameCore only offers ratios shorter than the panel's own.",
                )
            apply(size, packageName)
        }

    /**
     * Runs the display at [scale] of its own resolution (§B2).
     *
     * The sibling of the [AspectPreset] overload above and the opposite kind of change. A preset
     * *stretches*: it keeps the short side, cuts the long one and hands the game a different shape. This
     * *downscales*: both axes move by the same factor, so the panel's aspect ratio is preserved exactly
     * and only the number of pixels drops. The presets are computed from the panel's real physical size —
     * [ResolutionScale.sizeFor] of `physical`, never of the active size — for the reason [state]'s own
     * note gives: on a display that already carries an override, the active size is GameCore's own
     * leftover, and "80% of what the last game asked for" is not a resolution anyone chose.
     *
     * [ResolutionScale.FULL] is routed to [resetToNative] rather than written as an override equal to the
     * panel, exactly as [AspectPreset.NATIVE] is and for exactly the same reason: an override that happens
     * to equal the physical size is still a row in the window manager's settings that outlives GameCore,
     * and only `wm size reset` removes it. So "100%" means *the panel's own resolution, nothing of mine
     * left behind*, not "an override I happen to have set to the panel's numbers".
     *
     * Everything after the size is derived is the existing [apply]: the previous size is recorded under
     * [RestorePointRepository.KEY_DISPLAY_SIZE] before the write, the rejection check runs against the real
     * panel, and the result is read back through [verify] — so a resolution override and a stretch share
     * one restore row and one undo, which is what keeps the "Reset to native" affordance honest for both.
     *
     * What this is not, and the copy has to say so (§B5): a smaller logical display does not oblige the
     * game to render fewer pixels. It changes the surface size the window manager reports; whether the
     * game's own render target follows is the game's decision.
     */
    suspend fun apply(scale: ResolutionScale, packageName: String? = null): DisplaySizeOutcome =
        withContext(io) {
            if (scale.percent >= 100) return@withContext resetToNative()
            val physical = readState().valueOrNull?.physical ?: return@withContext unreadable()
            val size = scale.sizeFor(physical)
            size.rejectionFor(physical)?.let { reason ->
                return@withContext DisplaySizeOutcome.SizeUnsupported(size, reason)
            }
            apply(size, packageName)
        }

    /**
     * Sets the display to [requested], having first checked that it is a size worth setting and recorded
     * what the display was doing before.
     *
     * [requested] is oriented to match the panel before anything else happens, because `wm size` reads
     * its argument in the display's natural orientation: `1920x1080` on a portrait phone is a request for
     * a landscape desktop, and a user who typed the two numbers the other way round meant the same
     * display either way.
     *
     * [packageName] is the game the change was made for, stored with the restore point so session
     * history can attribute a display that is still stretched.
     */
    suspend fun apply(requested: DisplaySize, packageName: String? = null): DisplaySizeOutcome =
        withContext(io) {
            val current = readState().valueOrNull ?: return@withContext unreadable()
            val size = requested.orientedLike(current.physical)

            size.rejectionFor(current.physical)?.let { reason ->
                return@withContext DisplaySizeOutcome.SizeUnsupported(size, reason)
            }
            val command = ShellCommand.setDisplaySize(size)
                ?: return@withContext DisplaySizeOutcome.SizeUnsupported(
                    requested = size,
                    detail = "${size.label} is not a size GameCore will ask this device for.",
                )

            // Recorded before the write, and recorded even when the display already has this size. The
            // row is the ledger of what GameCore owes the device back; first-value-wins at the DAO means
            // an override the user set by hand survives however many presets are tapped after it.
            restorePoints.record(
                key = KEY,
                previousValue = current.override?.argument,
                packageName = packageName,
            )

            // Already there. Reported as applied without spending a write, because the panel's preset
            // chips apply on tap and re-issuing an override the display is already running is a window
            // manager round trip and a configuration change for no change at all.
            if (current.active.matches(size)) {
                return@withContext DisplaySizeOutcome.Applied(size, DataSource.SHELL_SHIZUKU.label)
            }

            val result = shell.execute(command)
            if (!result.isSuccess) {
                return@withContext DisplaySizeOutcome.Failed(
                    "The display size could not be set: ${result.failureReason()}",
                )
            }
            verify(target = size)
        }

    /**
     * Puts the panel back to its own size, whatever GameCore or anything else had set.
     *
     * The one-tap escape, reachable without a profile and without having applied anything first — which
     * is the whole point of it. A user who finds their display stretched by a session that was killed
     * needs a way out that does not depend on GameCore remembering what it did, so this reads nothing
     * first and asks for the reset directly.
     *
     * Clears GameCore's restore row on a confirmed reset, and does so even when the recorded value was
     * an override the user had set by hand. The user asking for the panel's own size is a decision that
     * supersedes the recorded one, and leaving the row pending would mean the next restore silently
     * re-applying the very override they just asked to be rid of.
     */
    suspend fun resetToNative(): DisplaySizeOutcome = withContext(io) {
        val outcome = reset()
        if (outcome.isSuccess) restorePoints.clear(RestorePointRepository.NON_SETTING_NAMESPACE, KEY)
        outcome
    }

    /**
     * Replays one recorded display size, for `OptimizationManager`'s restore pass.
     *
     * [previousValue] is the `WxH` that was overridden before GameCore touched the display, or null when
     * nothing was — which is the ordinary case and the one that has to be right, because null means the
     * row restores to *unset*, and the only thing that unsets a display override is `wm size reset`.
     *
     * Deliberately does not clear the row. The manager owns that, and it clears on a confirmed outcome
     * only, so a restore that could not be verified stays pending and is retried on the next launch
     * instead of being quietly forgotten. Re-recording inside [apply] on the way through is a no-op for
     * the same reason it is safe everywhere else: the insert is first-value-wins, and this row exists —
     * it is the one being restored.
     */
    suspend fun restore(previousValue: String?): DisplaySizeOutcome = withContext(io) {
        if (previousValue == null) return@withContext reset()
        val recorded = DisplaySize.parse(previousValue)
            ?: return@withContext DisplaySizeOutcome.Failed(
                "GameCore recorded a display size it cannot read back, so it left the display alone. " +
                    "Clearing the override by hand is `wm size reset`.",
            )
        apply(recorded)
    }

    /**
     * Whether GameCore has a display override outstanding, cheaply.
     *
     * One indexed query and no shell round trip, which is what makes it callable from the panel. It
     * answers the narrow question a "Reset to native" affordance needs — *is there something of mine to
     * undo* — and deliberately says nothing about an override set over adb or by another app. [state] is
     * the richer and more expensive answer.
     */
    suspend fun isOverriddenByGameCore(): Boolean = withContext(io) {
        restorePoints.pending().any { it.setting == null && it.key == KEY }
    }

    // --------------------------------------------------------------------- internals

    private suspend fun readState(): Observed<DisplaySizeState> {
        if (!shell.isAvailable()) return Observed.needsElevation(SHIZUKU_REQUIRED)
        val result = shell.execute(ShellCommand.GetDisplaySize)
        if (!result.isSuccess) {
            return Observed.Failed("This display's size could not be read", result.failureReason())
        }
        return DisplaySizeParser.parse(result.stdout)
    }

    /** `wm size reset`, verified. Touches no restore row — the two callers differ on exactly that. */
    private suspend fun reset(): DisplaySizeOutcome {
        if (!shell.isAvailable()) return DisplaySizeOutcome.RequiresAccess(SHIZUKU_REQUIRED)
        val result = shell.execute(ShellCommand.ResetDisplaySize)
        if (!result.isSuccess) {
            return DisplaySizeOutcome.Failed(
                "The display size override could not be cleared: ${result.failureReason()}",
            )
        }
        return verify(target = null)
    }

    private fun unreadable(): DisplaySizeOutcome = DisplaySizeOutcome.Failed(
        "GameCore could not read this display's current size, so it did not change it — a change it " +
            "cannot put back is not one it will make.",
    )

    /**
     * Reads the change back before calling it done.
     *
     * [target] is the size the display should now be, or null when the request was a reset and the answer
     * is therefore whatever the panel's own size turns out to be. [DisplaySizeVerdict] holds the decision
     * this loop is built around; what is here is the part that needs a device — the settle delay, the
     * retries, and the choice of witness.
     *
     * Two witnesses, and the asymmetry between them is deliberate. `wm size` is the window manager's own
     * account and is trusted in both directions — a change it does not report is
     * [DisplaySizeOutcome.NotHonoured], which is what happens on builds that accept a size override for
     * the built-in panel and ignore it. The app's own logical size is consulted *only* when the shell
     * could not be read at all, and then only when it confirms: this process is reconfigured
     * asynchronously when the display resizes and its window is not in the display's natural orientation,
     * so a reading that disagrees is evidence of nothing, and one that disagrees with `wm size` is not
     * entitled to overrule it.
     */
    private suspend fun verify(target: DisplaySize?): DisplaySizeOutcome {
        repeat(VERIFY_ATTEMPTS) { attempt ->
            // The window manager applies a resize on its own thread, so a read taken immediately after
            // the write catches the old size on a device where everything worked.
            delay(SETTLE_MILLIS)

            val state = readState().valueOrNull
            if (state != null) {
                DisplaySizeVerdict.of(
                    state = state,
                    target = target,
                    isFinalAttempt = attempt == VERIFY_ATTEMPTS - 1,
                )?.let { return it }
            } else if (target != null) {
                val seen = display.logicalSize().valueOrNull
                if (seen != null && seen.matches(target)) {
                    return DisplaySizeOutcome.Applied(target, DataSource.WINDOW_MANAGER.label)
                }
            }
        }

        // The command was accepted and nothing could confirm what the display did with it. Reported as
        // exactly that, which is not success.
        return DisplaySizeOutcome.AppliedUnverified(
            requested = target,
            reason = if (shell.isAvailable()) {
                "this device's window manager did not report the new size"
            } else {
                "the shell stopped answering before the change could be read back"
            },
        )
    }

    private companion object {
        /** As in `RefreshRateController`: a display change is applied on another thread. */
        const val SETTLE_MILLIS = 400L
        const val VERIFY_ATTEMPTS = 3

        const val KEY = RestorePointRepository.KEY_DISPLAY_SIZE

        const val SHIZUKU_REQUIRED = "Changing the display size needs the elevated shell. Android only " +
            "allows a size override from a shell with ADB-level authority, which on this device means " +
            "Shizuku."
    }
}

/**
 * What one read-back means, with the device taken out of it.
 *
 * Split from [DisplaySizeController.verify] because this is the decision the whole feature's honesty
 * rests on and it is the one part of the feature that can be checked on a machine with no display: given
 * what `wm size` now reports and what was asked for, is the change confirmed, refused, or not yet
 * settled? The loop around it owns the delays and the witnesses; this owns the verdict.
 *
 * A reset is confirmed by the override being *gone*, not by the active size matching the panel's. Those
 * are different claims, and a build that dropped the settings row while keeping the old size would
 * satisfy the second one while leaving the display stretched.
 */
internal object DisplaySizeVerdict {

    /**
     * The outcome, or null for "not yet" — read again.
     *
     * [target] is the size that was asked for, or null for a reset; the two are confirmed by different
     * questions and reported as different outcomes, which is why one nullable parameter says everything
     * this needs to know about the request. [isFinalAttempt] is what turns a size that has not appeared
     * yet into [DisplaySizeOutcome.NotHonoured]: until the last read there is no way to distinguish a
     * display still settling from one that declined, and guessing early in either direction is how a
     * utility app comes to report a resolution the device is not running.
     *
     * A confirmed size is reported as the one that was *read back*, not the one that was asked for. On a
     * display reporting itself the other way up those differ, and the one worth showing the user is the
     * one that came from the device.
     */
    fun of(
        state: DisplaySizeState,
        target: DisplaySize?,
        isFinalAttempt: Boolean,
    ): DisplaySizeOutcome? = when {
        target == null && !state.isOverridden ->
            DisplaySizeOutcome.Restored(state.physical, DataSource.SHELL_SHIZUKU.label)
        target != null && state.active.matches(target) ->
            DisplaySizeOutcome.Applied(state.active, DataSource.SHELL_SHIZUKU.label)
        isFinalAttempt -> DisplaySizeOutcome.NotHonoured(
            requested = target ?: state.physical,
            actual = state.active,
        )
        else -> null
    }
}
