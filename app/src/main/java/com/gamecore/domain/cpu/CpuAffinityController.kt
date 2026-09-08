package com.gamecore.domain.cpu

import com.gamecore.core.common.DataSource
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.common.Observed
import com.gamecore.core.common.unavailabilityText
import com.gamecore.core.common.valueOrNull
import com.gamecore.core.model.CpuAffinityMask
import com.gamecore.core.model.CpuAffinityOutcome
import com.gamecore.core.model.CpuAffinityPreset
import com.gamecore.core.model.CpuAffinityState
import com.gamecore.core.model.CpuClusterLayout
import com.gamecore.core.shizuku.ElevatedShell
import com.gamecore.core.shizuku.ShellCommand
import com.gamecore.core.system.CpuAffinityParser
import com.gamecore.core.system.ProcFsReader
import com.gamecore.core.system.ProcessControls
import com.gamecore.data.repository.RestorePointRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Restricts a game to a set of CPU cores while it runs, and gives them back afterwards.
 *
 * The most heavily qualified feature in this app, and the qualifications belong at the top of it rather
 * than only in the copy the user reads. What this does is call `sched_setaffinity` on another process
 * through a shell. That is a *permission* to use cores, not a reservation of them and not a clock
 * speed: the scheduler still decides what runs when, the governor still decides how fast, and nothing
 * here makes a device capable of more work than it was capable of a moment ago. The honest claim, and
 * the only one made anywhere in this feature, is [CpuAffinityPreset.HONESTY] — it may reduce stutter by
 * keeping a game off cores it was being moved between, and it may make things worse.
 *
 * Four rules, and each one exists because the alternative was a feature that looked like it worked:
 *
 *  1. **A preset is not offered unless this device's core layout can express it.** The layout is
 *     derived from `cpufreq`, which is unreadable on a good proportion of devices, and where it cannot
 *     be read there is no fallback and no guess — [layout] returns the device's own reason and the
 *     editor shows the presets as unavailable. A mask assembled from a partial reading would pin a game
 *     to a set of cores chosen by SELinux policy.
 *  2. **The previous mask is read and recorded before the write.** The same rule as
 *     [com.gamecore.domain.display.DisplaySizeController] and for the same reason: a restore point
 *     taken afterwards holds the value GameCore itself wrote. A process whose affinity cannot be read
 *     is therefore not pinned at all.
 *  3. **Nothing is reported as applied until every thread has been read back.** `-a` prints one line
 *     per thread and [CpuAffinityState.matches] requires all of them, because a change that moved the
 *     main thread and not the render thread is not the change that was asked for.
 *  4. **Whole process, main process, one pid.** `taskset -a` covers every thread, the pid comes from
 *     [ProcessControls.mainProcessPid] and nowhere else, and `com.game:audio` is left alone. There is
 *     no per-thread pinning here — a per-thread scheme would need to know which thread renders, which
 *     is not a question an outside process can answer, and would guess.
 *
 * What this is not: a second Shizuku path, or a second way to revert. Every command goes through
 * [ElevatedShell] like every other elevated read and write in the app, and the undo is a row in
 * [RestorePointRepository] under [RestorePointRepository.KEY_CPU_AFFINITY] that
 * `OptimizationManager.restoreOne` replays alongside every other pending change.
 *
 * One asymmetry with the rest of the restore ledger, documented at that constant as well: an affinity
 * mask dies with the process that held it. A session GameCore did not get to finish leaves nothing for
 * the user to escape from, which is why [restore] discharges the obligation rather than retrying
 * forever when the game has gone.
 */
@Singleton
class CpuAffinityController @Inject constructor(
    private val shell: ElevatedShell,
    private val procFs: ProcFsReader,
    private val processes: ProcessControls,
    private val restorePoints: RestorePointRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * This device's cores grouped into clusters, or the reason they could not be.
     *
     * Needs no shell — `cpuinfo_max_freq` is readable without one on the devices where it is readable
     * at all — and deliberately does not fall back to one. The elevated path could `cat` the same files
     * and would then make the feature's availability depend on Shizuku for a reading that has nothing
     * to do with privilege on most devices; and where SELinux denies the app, it commonly denies the
     * shell's domain the same node. An unavailable layout is reported as unavailable.
     *
     * The core *count* comes from the JVM rather than from `/sys`, matching every other core count in
     * this app: `availableProcessors` is what the runtime is willing to say, and a device that parks
     * cores reports fewer of them, which is the honest number to enumerate `cpufreq` nodes against.
     */
    suspend fun layout(): Observed<CpuClusterLayout> = withContext(io) { readLayout() }

    /**
     * Whether this device can change a process's affinity at all right now.
     *
     * Two conditions rather than one, which is what makes the editor able to say something useful
     * before anything is applied: the shell has to exist, *and* the core layout has to be readable. A
     * device with Shizuku set up and no readable `cpufreq` cannot offer these presets, and saying so up
     * front is better than a chip that applies and reports a failure every launch.
     */
    suspend fun access(): Observed<Unit> = withContext(io) {
        if (!shell.isAvailable()) return@withContext Observed.needsElevation(SHIZUKU_REQUIRED)
        when (val layout = readLayout()) {
            is Observed.Value -> Observed.of(Unit, DataSource.SHELL_SHIZUKU)
            is Observed.Restricted -> layout
            is Observed.Failed -> layout
        }
    }

    /**
     * Pins [packageName]'s main process to the cores [preset] names.
     *
     * The order of what happens here is the whole of rule 2 and rule 3: read the layout, resolve the
     * preset against it, find the process, read what its affinity *is*, record that, and only then
     * write. Every early return before the write leaves the device exactly as it was and says why.
     */
    suspend fun apply(preset: CpuAffinityPreset, packageName: String): CpuAffinityOutcome =
        withContext(io) {
            if (!shell.isAvailable()) {
                return@withContext CpuAffinityOutcome.RequiresAccess(SHIZUKU_REQUIRED)
            }

            val layout = readLayout()
            val cores = layout.valueOrNull
                ?: return@withContext CpuAffinityOutcome.Failed(
                    layout.unavailabilityText() ?: LAYOUT_UNREADABLE,
                )

            val choice = preset.on(cores)
            val reason = choice.unavailableBecause
            if (reason != null) {
                return@withContext CpuAffinityOutcome.PresetUnsupported(preset, reason)
            }

            val pid = processes.mainProcessPid(packageName).valueOrNull
                ?: return@withContext CpuAffinityOutcome.ProcessNotFound(
                    packageName = packageName,
                    detail = "GameCore could not find a running process for $packageName, so it did " +
                        "not change any core assignment. A game that is still loading usually has one " +
                        "a moment later.",
                )

            val before = readAffinity(pid).valueOrNull
                ?: return@withContext CpuAffinityOutcome.Failed(
                    "GameCore could not read which cores that process is on, so it did not change " +
                        "them — a change it cannot put back is not one it will make.",
                )

            // The main thread's mask, falling back to the one every thread shares. If the threads
            // already disagree and the main thread's own line is missing, there is no single value to
            // record and therefore nothing that could be put back, so nothing is written.
            val previous = before.mainThreadMask(pid) ?: before.uniformMask
                ?: return@withContext CpuAffinityOutcome.Failed(
                    "That process's threads are already on ${before.masks.size} different core sets, " +
                        "so GameCore has no single assignment it could restore afterwards.",
                )

            // Recorded before the write, and recorded even when the process is already on this mask.
            // First-value-wins at the DAO means a second apply in the same session cannot overwrite
            // the mask the process was found on.
            restorePoints.record(
                key = KEY,
                previousValue = CpuAffinityMask.hex(previous),
                packageName = packageName,
            )

            if (before.matches(choice.mask)) {
                return@withContext CpuAffinityOutcome.Applied(
                    preset = preset,
                    coreIndices = choice.coreIndices,
                    verifiedBy = DataSource.SHELL_SHIZUKU.label,
                )
            }

            write(pid = pid, mask = choice.mask, preset = preset, coreIndices = choice.coreIndices)
        }

    /**
     * Puts one recorded affinity back, for `OptimizationManager`'s restore pass.
     *
     * [previousValue] is the hex mask the process was on before GameCore touched it and [packageName]
     * is the game it belonged to, both from the restore row. The package is what makes this possible:
     * a pid could not be stored, because pids are reused and a stored one would eventually name some
     * other process entirely.
     *
     * Returns [CpuAffinityOutcome.NothingToRestore] — a success — in every case where the process
     * cannot be identified while the shell is answering. That is not a shrug. A mask lives in the
     * kernel's task struct, so a game that has exited took it with it and there is genuinely nothing
     * owed; whereas keeping the row pending would mean retrying on every launch for the rest of the
     * install, which is the one failure mode this ledger is built to avoid. A shell that is *not*
     * answering is the retryable case and is reported as such, so the ordinary "Shizuku is not
     * connected yet" path still waits.
     *
     * What no restore can put back is a per-thread mask the game set for itself before GameCore
     * arrived: `taskset -a` overwrote it and nothing recorded it. The sentence this returns says the
     * process is back on the cores it was found on, which is true, rather than that it is as it was.
     *
     * Deliberately does not clear the row. The manager owns that, and clears on a successful outcome
     * only.
     */
    suspend fun restore(previousValue: String?, packageName: String?): CpuAffinityOutcome =
        withContext(io) {
            if (!shell.isAvailable()) {
                return@withContext CpuAffinityOutcome.RequiresAccess(SHIZUKU_REQUIRED)
            }
            if (packageName == null) {
                return@withContext CpuAffinityOutcome.NothingToRestore(
                    "GameCore recorded a core assignment without the game it belonged to, so there " +
                        "is no process to put back. Nothing is left pinned once a game exits.",
                )
            }
            val mask = CpuAffinityMask.parse(previousValue)
                ?: return@withContext CpuAffinityOutcome.NothingToRestore(
                    "GameCore did not record a core assignment it can read back, so it left that " +
                        "process alone rather than writing a mask it invented.",
                )

            val resolved = processes.mainProcessPid(packageName)
            val pid = resolved.valueOrNull
                ?: return@withContext CpuAffinityOutcome.NothingToRestore(
                    "$packageName is not running, so the cores it was restricted to went with the " +
                        "process. Nothing was written.",
                )

            val current = readAffinity(pid).valueOrNull
            if (current != null && current.matches(mask)) {
                return@withContext CpuAffinityOutcome.Restored(
                    coreIndices = CpuAffinityMask.cores(mask),
                    verifiedBy = DataSource.SHELL_SHIZUKU.label,
                )
            }
            write(pid = pid, mask = mask, preset = null, coreIndices = CpuAffinityMask.cores(mask))
        }

    /**
     * Whether GameCore has an affinity change outstanding, cheaply.
     *
     * One indexed query and no shell round trip. Answers the narrow question a panel affordance needs —
     * *is there something of mine to undo* — and says nothing about a mask another tool set.
     */
    suspend fun isPinnedByGameCore(): Boolean = withContext(io) {
        restorePoints.pending().any { it.setting == null && it.key == KEY }
    }

    // --------------------------------------------------------------------- internals

    private fun readLayout(): Observed<CpuClusterLayout> {
        val cores = procFs.readCoreFrequencies(coreCount())
        return when (cores) {
            is Observed.Value -> CpuClusterLayout.from(cores.value)
                ?.let { Observed.of(it, cores.source, cores.precision) }
                ?: Observed.platform(LAYOUT_UNREADABLE)
            is Observed.Restricted -> cores
            is Observed.Failed -> cores
        }
    }

    private suspend fun readAffinity(pid: Int): Observed<CpuAffinityState> {
        val command = ShellCommand.readCpuAffinity(pid)
            ?: return Observed.Failed("$pid is not a process id GameCore will ask about")
        val result = shell.execute(command)
        if (!result.isSuccess) {
            return Observed.Failed(
                "The cores that process may run on could not be read",
                result.failureReason(),
            )
        }
        return CpuAffinityParser.parse(result.stdout)
    }

    /**
     * Writes one mask and reads it back.
     *
     * [preset] is null when the caller is [restore], which is asking for a mask rather than for a named
     * grouping — the outcomes carry it so the message can name the preset when there is one and not
     * invent a name when there is not.
     */
    private suspend fun write(
        pid: Int,
        mask: Int,
        preset: CpuAffinityPreset?,
        coreIndices: List<Int>,
    ): CpuAffinityOutcome {
        val command = ShellCommand.setCpuAffinity(pid, mask)
            ?: return CpuAffinityOutcome.Failed(
                "GameCore will not ask this device for that combination of process and cores.",
            )
        val result = shell.execute(command)
        if (!result.isSuccess) {
            return CpuAffinityOutcome.Failed(
                "The core assignment could not be changed: ${result.failureReason()}",
            )
        }
        return verify(pid = pid, mask = mask, preset = preset, coreIndices = coreIndices)
    }

    /**
     * Reads every thread back before calling the change done.
     *
     * The retry is not here because the kernel is asynchronous — `sched_setaffinity` returns having
     * done the work — but because `taskset -a` walks `/proc/<pid>/task` while the process it is walking
     * creates threads. A game in its first seconds does that constantly, and a thread that appeared
     * after the walk passed its slot has whatever mask it inherited. A second read a moment later sees
     * the settled set.
     *
     * A read that fails outright is [CpuAffinityOutcome.AppliedUnverified], not a failure and not a
     * success: the write was accepted and nothing could confirm what came of it, which is exactly what
     * that variant says.
     */
    private suspend fun verify(
        pid: Int,
        mask: Int,
        preset: CpuAffinityPreset?,
        coreIndices: List<Int>,
    ): CpuAffinityOutcome {
        var seen: CpuAffinityState? = null
        repeat(VERIFY_ATTEMPTS) {
            delay(SETTLE_MILLIS)
            val state = readAffinity(pid).valueOrNull
            if (state != null) {
                seen = state
                if (state.matches(mask)) {
                    return if (preset == null) {
                        CpuAffinityOutcome.Restored(coreIndices, DataSource.SHELL_SHIZUKU.label)
                    } else {
                        CpuAffinityOutcome.Applied(
                            preset = preset,
                            coreIndices = coreIndices,
                            verifiedBy = DataSource.SHELL_SHIZUKU.label,
                        )
                    }
                }
            }
        }

        val last = seen
        return if (last != null) {
            CpuAffinityOutcome.NotHonoured(preset, last.masks)
        } else {
            CpuAffinityOutcome.AppliedUnverified(
                preset = preset,
                reason = if (shell.isAvailable()) {
                    "that process's threads could not be read back"
                } else {
                    "the shell stopped answering before the change could be read back"
                },
            )
        }
    }

    private fun coreCount(): Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    private companion object {
        /** One walk of `/proc/<pid>/task`, twice, with enough of a gap for a starting game to settle. */
        const val SETTLE_MILLIS = 150L
        const val VERIFY_ATTEMPTS = 2

        const val KEY = RestorePointRepository.KEY_CPU_AFFINITY

        const val SHIZUKU_REQUIRED = "Choosing which cores a game runs on needs the elevated shell. " +
            "Android gives an app no way to change another process's core assignment, which on this " +
            "device means Shizuku."

        const val LAYOUT_UNREADABLE = "This device does not let apps read its per-core maximum " +
            "frequencies, so GameCore cannot tell its performance cores from its efficiency cores."
    }
}
