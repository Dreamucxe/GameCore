package com.gamecore.domain.gaming

import android.os.Build
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.model.CapabilityStatus
import com.gamecore.core.model.GameMode
import com.gamecore.core.model.GameModeMode
import com.gamecore.core.model.GameProfile
import com.gamecore.core.model.OptimizationAction
import com.gamecore.core.model.OptimizationResult
import com.gamecore.core.model.ResolutionScale
import com.gamecore.core.shizuku.ElevatedShell
import com.gamecore.core.shizuku.ShellCommand
import com.gamecore.data.repository.RestorePointRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android's per-game Game Mode: the transport behind the render-scale, frame-cap and mode features.
 *
 * This is the clean version of all three. `cmd game` changes one package, leaves the rest of the
 * phone at its own resolution and refresh rate, and Android applies the values when the game next
 * launches. It is not the same mechanism as [com.gamecore.domain.display.DisplaySizeController],
 * which writes a whole-display `wm size` override, and this class exists so the two are not confused
 * in the report or in the restore ledger.
 *
 * **One command, not three.** Android 14's `cmd game set` writes the whole intervention config for a
 * mode: a second `set` that mentions only `--fps` resets the scale the first one wrote. So a profile
 * that asks for a scale, a cap and a mode is sent as one argv, and this class returns one result per
 * requested intervention from that single write. Android 13's grammar differs — `--mode` is mandatory
 * and spelled as a word — and [com.gamecore.core.model.GameMode] plus [ShellCommand] carry the
 * difference.
 *
 * **Applies on the next launch.** Android reads the values when the game starts, so changing them
 * while a game is already open does nothing to that session. Every result says so rather than
 * claiming an effect the user cannot see yet.
 *
 * **No read-back, and every result says so.** The shell surface GameCore uses has no command that
 * reports a package's current overrides, so all three report [OptimizationResult.Unverified] —
 * requested, not confirmed. That is the same rule the rest of the app follows for a write it cannot
 * verify, and it is why the restore is `cmd game reset` rather than a recorded previous value: the
 * previous value was never readable in the first place.
 *
 * **The row is recorded before the first write**, under the game's package, exactly as the display
 * size and charge-bypass controllers record theirs. One row covers all three interventions because
 * one `cmd game reset` removes all of them; a failed command does not clear the row, because the
 * reset is neutral when there is nothing to reset and a row that survives is safer than a write that
 * does not.
 */
@Singleton
class GameModeController @Inject constructor(
    private val shell: ElevatedShell,
    private val restorePoints: RestorePointRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** Whether the platform and the current access level can do this at all. */
    suspend fun isSupported(): Boolean =
        GameMode.supportedOn(Build.VERSION.SDK_INT) && shell.isAvailable()

    /**
     * True on Android 13, where `cmd game set` requires a `--mode` before any intervention.
     *
     * Exposed rather than kept private so [ProfileApplier] can tell whether a render scale or a frame
     * cap may take the Game Mode route on a profile that chooses no mode. On Android 13 they may not:
     * the only way to make `set` accept them would be to change the game's mode behind the user's back.
     */
    fun requiresMode(): Boolean = !GameMode.modeNeutralOn(Build.VERSION.SDK_INT)

    /**
     * Requests everything [profile] asks of Game Mode in one command, and reports one result per
     * requested intervention.
     *
     * [scale] and [frameRateCapHz] are supplied by the applier rather than read from the profile,
     * because it has already decided which transport each one gets: a scale on Android 12 belongs to
     * `wm size`, and a frame rate the panel actually has belongs to the refresh-rate pin. Null means
     * that decision was made elsewhere.
     *
     * The returned list is empty only when nothing was asked of Game Mode. Otherwise it has exactly
     * one entry for each of [scale], a usable [frameRateCapHz] and [mode], so the report never shows
     * a success for an intervention the command did not contain.
     */
    suspend fun apply(
        profile: GameProfile,
        scale: ResolutionScale?,
        frameRateCapHz: Int?,
        mode: GameModeMode?,
    ): List<OptimizationResult> = withContext(io) {
        val requested = buildList {
            if (scale != null) add(OptimizationAction.SET_DISPLAY_SIZE)
            if (frameRateCapHz != null) add(OptimizationAction.CAP_FRAME_RATE)
            if (mode != null) add(OptimizationAction.SET_GAME_MODE)
        }
        if (requested.isEmpty()) return@withContext emptyList()

        if (!GameMode.supportedOn(Build.VERSION.SDK_INT)) {
            return@withContext requested.map {
                OptimizationResult.Skipped(
                    it,
                    "Android's Game Mode starts at Android 13, so this device has nothing to set.",
                )
            }
        }

        if (frameRateCapHz != null && frameRateCapHz !in GameMode.FPS_CHOICES) {
            return@withContext requested.map {
                if (it == OptimizationAction.CAP_FRAME_RATE) {
                    OptimizationResult.Skipped(
                        it,
                        "Android's Game Mode does not accept a $frameRateCapHz FPS cap, so the " +
                            "profile's frame rate was left alone.",
                    )
                } else {
                    OptimizationResult.Skipped(
                        it,
                        "The frame cap in this profile cannot be expressed, so the whole Game Mode " +
                            "request was left alone rather than applied in part.",
                    )
                }
            }
        }
        val fps = frameRateCapHz

        if (requiresMode() && mode == null && (scale != null || fps != null)) {
            return@withContext requested.map {
                OptimizationResult.Blocked(
                    it,
                    CapabilityStatus.UNSUPPORTED,
                    "This Android release can change one game's render scale or frame rate through " +
                        "Game Mode only while a Game Mode is selected, and this profile does not " +
                        "select Performance or Battery mode. Nothing was changed.",
                )
            }
        }

        val command = when {
            // A bare 100% request with nothing else to carry is a reset, not a write: the platform's
            // `disable` would also work, but reset is the same instruction and is what the restore
            // path uses, so the two cannot drift.
            scale != null && scale.percent >= 100 && fps == null && mode == null ->
                ShellCommand.resetGameMode(profile.packageName)

            // Mode only: `cmd game mode` exists on both grammars and does not disturb interventions.
            scale == null && fps == null -> mode?.let { ShellCommand.setGameMode(profile.packageName, it) }

            else -> ShellCommand.setGameModeInterventions(
                packageName = profile.packageName,
                fps = fps,
                downscalePercent = scale?.percent,
                mode = mode,
                sdkInt = Build.VERSION.SDK_INT,
            )
        } ?: return@withContext requested.map {
            OptimizationResult.Failed(
                it,
                "GameCore will not ask Android for a Game Mode change on " +
                    "\"${profile.packageName}\": the package name or one of the values is not " +
                    "something the command accepts.",
            )
        }

        finishStrandedRows(profile.packageName)?.let { problem ->
            return@withContext requested.map { OptimizationResult.Failed(it, problem) }
        }

        record(profile.packageName)
        val result = shell.execute(command)
        if (!result.isSuccess) {
            return@withContext requested.map {
                if (it == OptimizationAction.SET_GAME_MODE) {
                    OptimizationResult.Blocked(
                        it,
                        CapabilityStatus.UNSUPPORTED,
                        "\"${profile.packageName}\" did not accept Android's ${mode?.label ?: ""} " +
                            "Game Mode. A game must declare support for a mode; GameCore's other " +
                            "changes were unaffected. ${result.failureReason()}",
                    )
                } else {
                    OptimizationResult.Failed(
                        it,
                        "Android refused the Game Mode change for \"${profile.packageName}\": " +
                            result.failureReason(),
                    )
                }
            }
        }

        requested.map { action ->
            OptimizationResult.Unverified(
                action = action,
                detail = when (action) {
                    OptimizationAction.SET_DISPLAY_SIZE -> scaleDetail(profile, scale)
                    OptimizationAction.CAP_FRAME_RATE -> capDetail(profile, fps ?: 0)
                    OptimizationAction.SET_GAME_MODE -> modeDetail(profile, mode)
                    else -> "Android's Game Mode was requested for \"${profile.packageName}\"."
                },
                readbackUnavailable = true,
            )
        }
    }

    /**
     * Replays a pending Game Mode row for `OptimizationManager`'s restore pass.
     *
     * Null means the row is done. [packageName] is the reason this row exists at all: Game Mode
     * overrides cannot be cleared without naming the package they were set on. A row from an older
     * build that somehow lost its package stays pending with a sentence saying so rather than being
     * silently dropped.
     */
    suspend fun restore(packageName: String?): String? = withContext(io) {
        if (packageName.isNullOrBlank()) {
            return@withContext "No game was recorded for this Game Mode change, so it cannot be reset."
        }
        val command = ShellCommand.resetGameMode(packageName)
            ?: return@withContext "The recorded game name is not one Android's Game Mode accepts."
        val result = shell.execute(command)
        if (result.isSuccess) {
            clear()
            null
        } else {
            "The Game Mode overrides for \"$packageName\" are still in place: ${result.failureReason()}"
        }
    }

    private fun scaleDetail(profile: GameProfile, scale: ResolutionScale?): String {
        val name = "\"${profile.packageName}\""
        return if (scale == null || scale.percent >= 100) {
            "Render scale for $name reset to the display's own resolution through Android's Game " +
                "Mode. Android applies it the next time the game launches; GameCore cannot read " +
                "the per-game value back."
        } else {
            "Render scale for $name set to ${scale.label} through Android's Game Mode. Android " +
                "applies it the next time the game launches; GameCore cannot read the per-game " +
                "value back."
        }
    }

    private fun capDetail(profile: GameProfile, fps: Int): String =
        "Frame rate for \"${profile.packageName}\" capped at $fps FPS through Android's Game Mode. " +
            "This is a per-game frame cap, not a $fps Hz display mode; Android applies it the next " +
            "time the game launches, and GameCore cannot read the value back."

    private fun modeDetail(profile: GameProfile, mode: GameModeMode?): String =
        "Android's ${mode?.label ?: "selected"} Game Mode was requested for " +
            "\"${profile.packageName}\". The game must declare support for it, and GameCore cannot " +
            "read the mode back on this Android release."

    /**
     * Finishes Game Mode rows left behind by another game, or explains why it cannot.
     *
     * `restore_points` is keyed by `(namespace, key)`, so the ledger holds one Game Mode row in
     * total — not one per package. That is enough for the ordinary case, because a session restores
     * before the next game applies, but not for the case where that restore failed and the user
     * started a different game afterwards. Recording the new game would then be silently ignored
     * by `insertIfAbsent`, leaving its overrides with no row and resetting the *old* game at the
     * next restore. So the abandoned row is settled first: reset its package, and only then claim
     * the key. A reset that cannot be completed stops the apply rather than stacking overrides
     * GameCore has no way to unwind.
     *
     * A row with no package at all is the same refusal: `cmd game reset` cannot name a game without
     * one, and the restore path already keeps such a row pending forever.
     */
    private suspend fun finishStrandedRows(packageName: String): String? {
        val rows = restorePoints.pending().filter {
            it.key == RestorePointRepository.KEY_GAME_MODE ||
                it.key == RestorePointRepository.LEGACY_GAME_DOWNSCALE
        }
        for (row in rows) {
            val previous = row.packageName
            if (previous.isNullOrBlank()) {
                return "A Game Mode change from an earlier session has no game recorded, so " +
                    "GameCore cannot reset it and will not stack another game's overrides on top " +
                    "of a row it cannot unwind."
            }
            if (previous == packageName) continue
            val reset = ShellCommand.resetGameMode(previous)
                ?: return "A Game Mode change from an earlier session names \"$previous\", which " +
                    "Android's Game Mode command does not accept, so it cannot be reset."
            if (!shell.execute(reset).isSuccess) {
                return "GameCore still owes \"$previous\" a Game Mode reset and could not " +
                    "complete it, so it will not leave \"$packageName\" with overrides it has no " +
                    "row to undo. Nothing was changed; the next session will try the reset again."
            }
        }
        for (row in rows) {
            if (row.packageName != packageName) restorePoints.clear(row.namespace, row.key)
        }
        return null
    }

    /**
     * Records that GameCore owns this package's Game Mode overrides.
     *
     * The first value recorded for the key wins, so three interventions in one apply produce one row
     * and a later one does not overwrite the ownership marker. The marker is not a previous value —
     * none is readable — it is a note that the way back is `cmd game reset`.
     */
    private suspend fun record(packageName: String) {
        restorePoints.record(
            key = RestorePointRepository.KEY_GAME_MODE,
            previousValue = RESTORE_MARKER,
            packageName = packageName,
        )
    }

    private suspend fun clear() {
        restorePoints.clear(
            RestorePointRepository.NON_SETTING_NAMESPACE,
            RestorePointRepository.KEY_GAME_MODE,
        )
    }

    private companion object {
        /**
         * Stored in the row's previous-value column.
         *
         * It is not a previous mode, frame cap or scale — none of them is readable — it is a note
         * that this row is GameCore's ownership record and that the way back is `cmd game reset`.
         */
        const val RESTORE_MARKER = "reset_game_mode"
    }
}
