package com.gamecore.domain.charge

import com.gamecore.core.common.DataSource
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.common.Observed
import com.gamecore.core.common.valueOrNull
import com.gamecore.core.shizuku.ElevatedShell
import com.gamecore.core.shizuku.ShellCommand
import com.gamecore.core.shizuku.ShellResult
import com.gamecore.data.repository.RestorePointRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Powers the phone from the charger directly during a session by asking the battery to stop charging,
 * and gives charging back afterwards.
 *
 * A charger delivers more current than a phone at rest consumes, and the surplus goes into the cell —
 * which is where the heat and the wear of a long charging-while-gaming session come from. On a good
 * number of devices the kernel exposes a power-supply node that decouples the two: told to suspend
 * charging, the charger still supplies the system rail, so the phone runs on wall power while the cell
 * is left alone. That is the whole of the honest claim. This does *not* make the device faster, does
 * *not* charge it more quickly, does *not* cap the charge level, and is not a battery-health regime —
 * it is one runtime bit that, on the hardware that has it, keeps a hot game from also cooking the cell.
 *
 * Four rules, each here because the alternative was a feature that looked like it worked:
 *
 *  1. **No node is used unless it was probed and found writable at runtime.** The paths in
 *     [KNOWN_NODES] are candidates, not assumptions: vendors put this control in different places and
 *     most devices have none of them, so [support] walks the list and asks the shell `test -w` on each,
 *     taking the first that both exists and accepts a write. A path is never `echo`'d at blind — a
 *     guessed write either errors or, worse, lands on an unrelated power-supply attribute.
 *  2. **The node's current value is read and recorded before the write.** The same rule as
 *     [com.gamecore.domain.cpu.CpuAffinityController] and for the same reason: a restore point taken
 *     afterwards would hold the value GameCore itself wrote. The reading goes into
 *     [RestorePointRepository] under [RestorePointRepository.KEY_CHARGE_BYPASS] before a single bit is
 *     changed, so a session GameCore does not get to finish still has a row that the restore pass will
 *     replay. The one refinement over a literal restore, in rule 3's spirit, is that this never writes a
 *     charge-*disabled* value back: the obligation this row records is to return charging, and a value
 *     that would leave it off is treated as the normal value instead.
 *  3. **Nothing is reported as bypassed until the node has been read back at the stop value, and an
 *     unconfirmed write is undone rather than left.** This is where the feature deliberately parts from
 *     [com.gamecore.domain.cpu.CpuAffinityController]. An affinity write that cannot be read back is
 *     reported `AppliedUnverified` and left in place, because an unverified core pin is harmless either
 *     way. A charge-stop write that cannot be read back is the opposite: if it silently took and nothing
 *     manages it, the cell never charges. So every error path here — a write that failed, a read-back
 *     that disagreed, a read-back that could not be taken at all — attempts an immediate restore to the
 *     value that was captured and reports failure, and the recorded row remains as the durable net if
 *     even that attempt cannot be confirmed.
 *  4. **One mechanism, whole device.** A power-supply node is a property of the phone, not of a
 *     process, so there is no per-app charging and nothing about the package name selects a node. The
 *     package is carried for the ledger row and for the sentence a report shows, exactly as far as it is
 *     carried and no further.
 *
 * One asymmetry with the affinity ledger, and it runs the other way, which is worth stating rather than
 * leaving to be inferred. An affinity mask dies with the process that held it, so a row left behind by a
 * crashed session owes nothing — the game exited and took the mask with it. A suspended charge does not
 * die with anything: it persists until the node is written back or the device reboots (these nodes are
 * runtime kernel state and reset to charging on boot). A row left behind here is therefore a *stronger*
 * obligation than [RestorePointRepository.KEY_CPU_AFFINITY]'s, not a weaker one, which is the whole
 * reason the value is recorded before the write and every error path backs the change out.
 *
 * What this is not: a second Shizuku path, a charge-limit feature, or a fast-charge trick. Every command
 * goes through [ElevatedShell] like every other elevated read and write in the app — stopping the
 * charger needs the elevated shell because Android exposes no API to an ordinary app for it — and the
 * undo is the same [RestorePointRepository] row that `OptimizationManager.restoreOne` replays alongside
 * every other pending change, not a second mechanism of its own.
 */
@Singleton
class ChargeBypassController @Inject constructor(
    private val shell: ElevatedShell,
    private val restorePoints: RestorePointRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * Whether this device can be run from the charger at all right now, and through which node.
     *
     * Two conditions rather than one, the same shape as
     * [com.gamecore.domain.cpu.CpuAffinityController.access]: the shell has to exist, *and* a node has
     * to be found that both exists and is writable by it. Neither is guessable ahead of time — most
     * devices expose none of [KNOWN_NODES], and several expose one that a uid-2000 shell may read but
     * not write — so the check is a real probe against the hardware, not a table lookup. A device with
     * Shizuku running and no writable node reports [Observed.notPresent], which is the honest answer and
     * the one the editor needs to grey the switch out before anything is applied rather than after a
     * launch that failed.
     */
    suspend fun support(): Observed<ChargeControlNode> = withContext(io) {
        if (!shell.isAvailable()) return@withContext Observed.needsElevation(SHIZUKU_REQUIRED)
        findUsableNode()
    }

    /**
     * Suspends charging for [packageName]'s session, capturing what the node was set to first.
     *
     * The order is the whole of rule 2 and rule 3: find the node, read what it *is*, record that, and
     * only then write the stop value and read it back. Every early return before the write leaves the
     * device exactly as it was and says why. Every failure *after* the write attempts to put the node
     * back before it reports, because an unmanaged suspended charge is the one outcome this feature must
     * never leave behind (see the class note on why that differs from an unverified affinity pin).
     *
     * The captured value is recorded even when the node is already at the stop value — first-value-wins
     * at the DAO means a session that crashed mid-bypass keeps its original reading rather than having
     * this call overwrite it with the stopped state.
     */
    suspend fun enable(packageName: String): Observed<ChargeControlNode> = withContext(io) {
        if (!shell.isAvailable()) return@withContext Observed.needsElevation(SHIZUKU_REQUIRED)

        val node = when (val found = findUsableNode()) {
            is Observed.Value -> found.value
            is Observed.Restricted -> return@withContext found
            is Observed.Failed -> return@withContext found
        }

        val current = readValue(node).valueOrNull
            ?: return@withContext Observed.Failed(
                "GameCore could not read whether $BATTERY is charging, so it did not stop it — a change " +
                    "it cannot put back is not one it will make.",
            )

        // Recorded before the write, and recorded even when charging is already suspended. First-value-
        // wins at the DAO protects the original reading against a second apply in the same session.
        restorePoints.record(key = KEY, previousValue = current, packageName = packageName)

        if (current == node.stopValue) {
            return@withContext Observed.of(node, DataSource.SHELL_SHIZUKU)
        }

        val wrote = writeNode(node, node.stopValue)
        if (!wrote.isSuccess) {
            val recovered = attemptRestore(node, current)
            return@withContext Observed.Failed(writeFailure(wrote.failureReason(), recovered))
        }

        when (readValue(node).valueOrNull) {
            node.stopValue -> Observed.of(node, DataSource.SHELL_SHIZUKU)
            else -> {
                val recovered = attemptRestore(node, current)
                Observed.Failed(unconfirmed(recovered))
            }
        }
    }

    /**
     * Returns charging, for `OptimizationManager`'s restore pass.
     *
     * [previousValue] is the value the node was on before GameCore touched it and [packageName] the game
     * it belonged to, both from the restore row. Unlike [com.gamecore.domain.cpu.CpuAffinityController.restore]
     * the package does not identify what to write back — the node is the device's, not the process's —
     * so it re-runs the same probe [enable] did. That probe is deterministic (the first writable node in
     * [KNOWN_NODES], in order) and the hardware does not change under a session, so it resolves to the
     * node [enable] wrote, and re-confirming it is writable now is a strictly better guarantee than
     * trusting a stored path.
     *
     * The value written back is [previousValue], with one fail-safe exception that is the point of the
     * whole feature: a captured value that would itself leave charging *off* is refused, and the node's
     * normal value is written instead. Restoring is an obligation to give charging back, never to
     * reinstate a suspended state.
     *
     * A node already reading the target is reported [Observed.Value] with nothing written — the common
     * case after a reboot, which resets these nodes to charging on its own. A shell that is not answering
     * is the retryable case and is reported as [Observed.needsElevation], so the ordinary "Shizuku is not
     * connected yet" wait still applies. Deliberately does not clear the row; the manager owns that and
     * clears on a confirmed restore only.
     */
    suspend fun restore(previousValue: String?, packageName: String?): Observed<ChargeControlNode> =
        withContext(io) {
            if (!shell.isAvailable()) return@withContext Observed.needsElevation(SHIZUKU_REQUIRED)

            val node = when (val found = findUsableNode()) {
                is Observed.Value -> found.value
                is Observed.Restricted -> return@withContext found
                is Observed.Failed -> return@withContext found
            }

            val target = previousValue?.takeUnless { it == node.stopValue } ?: node.normalValue

            if (readValue(node).valueOrNull == target) {
                return@withContext Observed.of(node, DataSource.SHELL_SHIZUKU)
            }

            writeAndVerify(node, target)
        }

    /**
     * Whether GameCore has a charge bypass outstanding, cheaply.
     *
     * One indexed query and no shell round trip, mirroring
     * [com.gamecore.domain.cpu.CpuAffinityController.isPinnedByGameCore]. Answers the narrow question a
     * panel affordance needs — *is there something of mine to undo* — and says nothing about a node some
     * other tool set.
     */
    suspend fun isBypassedByGameCore(): Boolean = withContext(io) {
        restorePoints.pending().any { it.setting == null && it.key == KEY }
    }

    /**
     * The first node that both exists and is writable by this shell, or the reason there is none.
     *
     * Walks [KNOWN_NODES] in order and asks the shell `test -w` on each — one predicate that is true only
     * when the file is present *and* the current process may write it, which is exactly the pair rule 1
     * demands and neither a bare `cat` (readable is not writable) nor an existence check alone would
     * establish. Assumes the shell is already known available; both callers check that first so a dead
     * shell is reported as [Observed.needsElevation] rather than mistaken here for an absent node.
     */
    private suspend fun findUsableNode(): Observed<ChargeControlNode> {
        for (node in KNOWN_NODES) {
            if (probeWritable(node)) return Observed.of(node, DataSource.SHELL_SHIZUKU)
        }
        return Observed.notPresent(NO_NODE)
    }

    /** True when `test -w` on the node exits zero. A rejected path or a non-zero exit is not usable. */
    private suspend fun probeWritable(node: ChargeControlNode): Boolean {
        val command = ShellCommand.probeChargeControlNode(node.path) ?: return false
        return shell.execute(command).isSuccess
    }

    /**
     * The node's current value with the trailing newline stripped, or the read's failure.
     *
     * A blank read is treated as a failure rather than as a value: a charge-control node prints a single
     * small integer, and an empty body means the `cat` did not reach one even though the shell exited —
     * a vendor node that stats but does not read, for instance — which is not a value to record or verify
     * against.
     */
    private suspend fun readValue(node: ChargeControlNode): Observed<String> {
        val command = ShellCommand.readChargeControlNode(node.path)
            ?: return Observed.Failed("${node.label} is not a node GameCore will read.")
        val result = shell.execute(command)
        if (!result.isSuccess) {
            return Observed.Failed("Whether $BATTERY is charging could not be read.", result.failureReason())
        }
        val value = result.stdout.trim()
        return if (value.isEmpty()) {
            Observed.Failed("The charge-control node returned nothing to read.")
        } else {
            Observed.of(value, DataSource.SHELL_SHIZUKU)
        }
    }

    /** Writes one value to a node, or a synthetic failure when the factory rejects the pair. */
    private suspend fun writeNode(node: ChargeControlNode, value: String): ShellResult {
        val command = ShellCommand.writeChargeControlNode(node.path, value)
            ?: return ShellResult.failure(
                "GameCore will not ask this device to write \"$value\" to ${node.label}.",
                shell.accessLevel,
            )
        return shell.execute(command)
    }

    /**
     * Writes one value and reads it back, for [restore].
     *
     * No retry loop and no settle delay, unlike the affinity verifier: a write to a sysfs attribute is
     * synchronous — the value is set when the write returns — so a single read-back reflects it, and
     * there is no live task tree being walked underneath as `taskset -a` has. A read-back that disagrees
     * or cannot be taken is [Observed.Failed], which keeps the restore row pending for the next pass
     * rather than clearing an obligation that was not met.
     */
    private suspend fun writeAndVerify(node: ChargeControlNode, value: String): Observed<ChargeControlNode> {
        val wrote = writeNode(node, value)
        if (!wrote.isSuccess) {
            return Observed.Failed("Charging could not be returned: ${wrote.failureReason()}")
        }
        return if (readValue(node).valueOrNull == value) {
            Observed.of(node, DataSource.SHELL_SHIZUKU)
        } else {
            Observed.Failed("Charging was written back but could not be confirmed on this device.")
        }
    }

    /**
     * The fail-safe backout [enable] runs on every error path: write charging back and confirm it.
     *
     * The value it aims for is the captured one, with the same refusal [restore] makes — a captured value
     * that is itself the stop value would re-suspend charging, so the normal value is used instead.
     * Returns whether charging was confirmed back on, which the failure message reflects honestly; either
     * way the recorded row is the durable net, so a backout that cannot be confirmed is retried by the
     * restore pass rather than lost.
     */
    private suspend fun attemptRestore(node: ChargeControlNode, previousValue: String): Boolean {
        val safe = previousValue.takeUnless { it == node.stopValue } ?: node.normalValue
        if (!writeNode(node, safe).isSuccess) return false
        return readValue(node).valueOrNull == safe
    }

    private fun writeFailure(reason: String, recovered: Boolean): String =
        "Charging could not be stopped: $reason${recoveryClause(recovered)}"

    private fun unconfirmed(recovered: Boolean): String =
        "GameCore could not confirm charging was stopped, so it did not leave it in an unknown " +
            "state.${recoveryClause(recovered)}"

    private fun recoveryClause(recovered: Boolean): String = if (recovered) {
        " Charging was returned to normal."
    } else {
        " GameCore could not confirm charging was returned to normal and will try again when the game exits."
    }

    private companion object {
        /**
         * The charge-control nodes GameCore knows how to drive, in probe order.
         *
         * Every one is a power-supply attribute a vendor kernel *may* expose; none is guaranteed, and a
         * device commonly has exactly zero of them, which is why [findUsableNode] proves each rather than
         * assuming. Two spellings of the same idea appear because vendors disagree on the sign: an
         * `input_suspend`/`charge_disable` node stops charging when set to `1`, while a
         * `charging_enabled` node stops it when set to `0`. Each carries both its stop value and its
         * normal value so the sign is written down once, next to the path, and never inferred at a call
         * site. Order is deliberate — the Qualcomm `battery/input_suspend` is the most widely present and
         * the most surgical, so it is tried first, and the `bms/charging_enabled` fallback last.
         */
        val KNOWN_NODES: List<ChargeControlNode> = listOf(
            ChargeControlNode(
                path = "/sys/class/power_supply/battery/input_suspend",
                stopValue = "1", normalValue = "0", label = "battery/input_suspend",
            ),
            ChargeControlNode(
                path = "/sys/class/power_supply/battery/charging_enabled",
                stopValue = "0", normalValue = "1", label = "battery/charging_enabled",
            ),
            ChargeControlNode(
                path = "/sys/class/power_supply/battery/battery_charging_enabled",
                stopValue = "0", normalValue = "1", label = "battery/battery_charging_enabled",
            ),
            ChargeControlNode(
                path = "/sys/class/power_supply/battery/charge_disable",
                stopValue = "1", normalValue = "0", label = "battery/charge_disable",
            ),
            ChargeControlNode(
                path = "/sys/class/power_supply/bms/charging_enabled",
                stopValue = "0", normalValue = "1", label = "bms/charging_enabled",
            ),
        )

        /** The subject of the user-facing sentences, kept in one place so they read alike. */
        const val BATTERY = "the battery"

        const val KEY = RestorePointRepository.KEY_CHARGE_BYPASS

        const val SHIZUKU_REQUIRED = "Running the phone from the charger needs the elevated shell. " +
            "Android gives an app no way to tell the battery to stop charging, which on this device " +
            "means Shizuku."

        const val NO_NODE = "This device does not expose a charge-control node GameCore can write, so it " +
            "cannot run the phone from the charger without also charging the battery."
    }
}

/**
 * One writable charge-control node: where it is, and the two values that mean stopped and normal.
 *
 * A small value type rather than a bare path so the sign of a given node — whether stopping charging
 * means writing `1` or writing `0` — travels with the path instead of being remembered separately at
 * each place the node is read or written. [label] is the short `subsystem/attribute` form shown in a
 * report, never the full `/sys` path, which is not a sentence a user reads.
 */
data class ChargeControlNode(
    val path: String,
    val stopValue: String,
    val normalValue: String,
    val label: String,
)
