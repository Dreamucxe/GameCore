package com.gamecore.core.model

/**
 * One group of cores that share a maximum frequency.
 *
 * The unit a preset is expressed in, because "performance cores" is not a fact the kernel publishes
 * anywhere — there is no `is_big_core` file. What it publishes is a maximum frequency per core, and a
 * cluster is what you get when you group cores by it. That is the whole of the inference, and it is
 * stated as one so nothing downstream has to guess how much confidence to place in it.
 */
data class CpuCluster(
    /** Ascending, distinct, and within [CpuClusterLayout.MAX_CORES]. */
    val coreIndices: List<Int>,
    val maxKHz: Long,
) {
    val mask: Int get() = coreIndices.fold(0) { acc, index -> acc or (1 shl index) }
}

/**
 * This device's cores, grouped into clusters, highest frequency first.
 *
 * Derived rather than read. The brief this was built from assumed `Device optimizations` already had a
 * core-layout detection to reuse and it does not: nothing in GameCore before this knew which cores were
 * the fast ones. What existed was [com.gamecore.core.system.ProcFsReader.readCoreFrequencies], which
 * reports a `cpuinfo_max_freq` per core, and that is enough — cores with the same ceiling are the same
 * cluster, and the group with the highest ceiling is the performance cluster. [from] is the only place
 * that inference happens, so there is one thing to be wrong and one thing to test.
 *
 * The inference is refused far more often than it is made, on purpose. `cpufreq` moved out of app reach
 * on most builds around Android 10, and a partial answer is worse than none here: a mask built from the
 * four cores whose files happened to be readable would pin a game to a set chosen by SELinux policy
 * rather than by the user, and it would look exactly like a working feature. So [from] returns null
 * unless every core answered, and the caller turns that into the device's real reason rather than a
 * guess. §31's rule about never inventing a reading applies with more force than usual, because this
 * reading is not displayed — it is acted on.
 */
data class CpuClusterLayout(
    /** Highest [CpuCluster.maxKHz] first. Never empty. */
    val clusters: List<CpuCluster>,
) {

    val coreCount: Int get() = clusters.sumOf { it.coreIndices.size }

    /** Every core, which is what the OS runs a process on when nothing has said otherwise. */
    val allCoresMask: Int get() = clusters.fold(0) { acc, cluster -> acc or cluster.mask }

    /** The cores in the highest-frequency cluster. */
    val performanceCores: List<Int> get() = clusters.first().coreIndices

    /** Everything below the top cluster, ascending. Empty on a device with one cluster. */
    val efficiencyCores: List<Int> get() = clusters.drop(1).flatMap { it.coreIndices }.sorted()

    /**
     * True when every core has the same ceiling, so there is no fast half and no slow half.
     *
     * Not a rare case worth handling badly: it is every device with a uniform CPU, and it is also what
     * a big.LITTLE phone looks like when the kernel reports one shared policy. Both presets are
     * meaningless on such a layout and both say so rather than pinning to something arbitrary.
     */
    val isHomogeneous: Boolean get() = clusters.size == 1

    /** Every preset resolved against this layout, in declaration order, available or not. */
    fun choices(): List<CpuAffinityChoice> = CpuAffinityPreset.entries.map { it.on(this) }

    companion object {

        /**
         * A mask is an `Int`, so this is 32 — and no Android device is near it.
         *
         * A bound rather than a `Long` mask because the bound is the useful thing: a core count outside
         * it means the reading is wrong rather than that the phone is unusual, and refusing is a better
         * answer than a 64-bit mask for a device that does not exist.
         */
        const val MAX_CORES = 32

        /**
         * The layout [cores] describes, or null when it does not describe one.
         *
         * Null in four cases, and the last is the one that matters:
         *
         *  * nothing was read at all;
         *  * more cores than a mask can hold;
         *  * an index repeated or out of range, which means the reading is not what it claims to be;
         *  * **any core with a non-positive `maxKHz`.** [com.gamecore.core.model.CoreFrequency]
         *    coalesces an unreadable frequency to `0L` rather than to null, so a zero here is an
         *    absence wearing a number's clothes. Refusing the whole layout for one of them is
         *    deliberate: the alternative is a cluster split decided by which `cpufreq` files this
         *    vendor kernel happened to expose, and a user pinning a game to "performance cores" on
         *    that basis would get a subset nobody chose.
         *
         * Frequencies are compared for equality rather than bucketed by proximity. Cores in one
         * cluster report the identical ceiling because it is the same policy file behind them; two
         * clusters 100 kHz apart are two clusters, and a tolerance would exist only to merge them.
         */
        fun from(cores: List<CoreFrequency>): CpuClusterLayout? {
            if (cores.isEmpty() || cores.size > MAX_CORES) return null
            val indices = cores.map { it.coreIndex }
            if (indices.distinct().size != indices.size) return null
            if (indices.any { it < 0 || it >= MAX_CORES }) return null
            if (cores.any { it.maxKHz <= 0L }) return null

            val clusters = cores
                .groupBy { it.maxKHz }
                .map { (maxKHz, group) -> CpuCluster(group.map { it.coreIndex }.sorted(), maxKHz) }
                .sortedByDescending { it.maxKHz }
            return CpuClusterLayout(clusters)
        }
    }
}

/**
 * What one preset means on one device, resolved.
 *
 * A single type carrying both the answer and the refusal, because the two are one decision and
 * splitting them into a `maskFor()` and an `isAvailableOn()` would be two copies of the same
 * conditions kept in step by hand. The UI needs the reason to print, the controller needs the mask to
 * write, and neither can be computed without the other having been decided.
 */
data class CpuAffinityChoice(
    val preset: CpuAffinityPreset,
    /** The cores this pins to, or empty when [unavailableBecause] is set. */
    val coreIndices: List<Int>,
    /** Why this device cannot express the preset, or null when it can. */
    val unavailableBecause: String?,
) {
    val isAvailable: Boolean get() = unavailableBecause == null

    /** The affinity mask, or 0 when unavailable — which is not a mask any caller may write. */
    val mask: Int get() = coreIndices.fold(0) { acc, index -> acc or (1 shl index) }
}

/**
 * The core groupings a profile may ask for. Presets only, and deliberately two.
 *
 * There is no raw mask entry and there will not be one. A mask is a set of cores a user cannot see the
 * consequences of choosing, on a scheduler whose behaviour they cannot observe, and the failure mode is
 * a game pinned to one little core that runs at a third of its normal frame rate with nothing on screen
 * explaining why. Two named groupings, each of which the device either supports or does not, is the
 * whole of what can be offered honestly.
 *
 * "Leave to OS" is not a member here. It is `null` on
 * [com.gamecore.core.model.GameProfile.cpuAffinity], the same as leaving refresh rate or orientation
 * alone, which is what that class's own rule requires — null means *leave it alone*, and a `LEAVE_TO_OS`
 * entry beside it would give one state two spellings and a database column that could hold either. The
 * user still sees it as a chip labelled "Leave to OS", selected by default, because the editor already
 * renders a null option that way for every other field it can leave alone.
 *
 * [explanation] is rendered verbatim wherever a preset is shown, as [PerformanceMode]'s is, and every
 * one of them says what it does not do. Neither of these makes a device faster. They change which cores
 * the scheduler is allowed to use, and the scheduler was already choosing well most of the time.
 */
enum class CpuAffinityPreset(
    val label: String,
    val explanation: String,
) {

    PERFORMANCE_ONLY(
        label = "Performance cores only",
        explanation = "Keeps the game on the cores with the highest maximum frequency, so the " +
            "scheduler cannot move a busy thread onto a slower core mid-frame. It gives the game " +
            "fewer cores than it had: a game that genuinely uses all of them will run worse, and " +
            "the cores it keeps are also the hottest, so a long session may throttle sooner.",
    ),

    AVOID_ONE_EFFICIENCY_CORE(
        label = "All cores, minus one",
        explanation = "Leaves the game every core except the slowest one, which is where the " +
            "kernel does most of its own work and handles most interrupts. Nothing is gained in " +
            "raw capacity — the point is that one core stops being shared between the game and " +
            "everything else on the device.",
    ),
    ;

    /**
     * This preset resolved against [layout].
     *
     * Both refusals below exist to stop a preset meaning something it does not say. On a layout with
     * one cluster there is no fast half to keep to and no slow core to give up, so both would silently
     * become "every core" — which is what leaving the field alone already does, expressed with a chip
     * that claims to have changed something. And where the slow cluster holds a single core,
     * [AVOID_ONE_EFFICIENCY_CORE] and [PERFORMANCE_ONLY] compute the identical mask, so offering both
     * would put two chips on screen that do one thing and disagree about what it is called.
     */
    fun on(layout: CpuClusterLayout): CpuAffinityChoice {
        if (layout.isHomogeneous) {
            return unavailable(
                "Every core on this device reports the same maximum frequency, so there is no " +
                    "faster group to keep the game on.",
            )
        }
        return when (this) {
            PERFORMANCE_ONLY -> CpuAffinityChoice(this, layout.performanceCores, null)

            AVOID_ONE_EFFICIENCY_CORE -> {
                val efficiency = layout.efficiencyCores
                if (efficiency.size < 2) {
                    unavailable(
                        "This device has one slower core, so leaving it out would be the same as " +
                            "\"${PERFORMANCE_ONLY.label.lowercase()}\".",
                    )
                } else {
                    // The lowest-numbered slow core rather than any other. On every ARM layout this app
                    // will meet, cpu0 is in the little cluster and is where the kernel lands its timers
                    // and its interrupt work, so it is both the core the game benefits least from and
                    // the one that benefits most from not being shared.
                    CpuAffinityChoice(this, layout.allCores() - efficiency.first(), null)
                }
            }
        }
    }

    private fun unavailable(reason: String) = CpuAffinityChoice(this, emptyList(), reason)

    companion object {
        /**
         * The sentence that must appear wherever a preset is offered, and the only copy in this app
         * held as a constant because two places have to say the same thing rather than similar things.
         *
         * It is on [OptimizationAction.SET_CPU_AFFINITY] because that is what a session report prints,
         * and in the profile editor because that is where the choice is made. A paraphrase in either
         * would be the beginning of the claim this feature is not allowed to make.
         */
        const val HONESTY: String =
            "May reduce stutter by controlling which cores the game runs on. Does not make the " +
                "device faster, and may occasionally do more harm than good depending on the game."
    }
}

/** Every core index in the layout, ascending. */
private fun CpuClusterLayout.allCores(): List<Int> =
    clusters.flatMap { it.coreIndices }.sorted()

/**
 * What every thread in one process is currently allowed to run on, as `taskset -ap` reported it.
 *
 * Keyed by thread id because `-a` prints one line per thread and the disagreements between those lines
 * are the whole reason this type exists rather than a bare `Int`. A game engine that pins its own
 * worker threads has already set masks GameCore did not, so "the process's affinity" is not always a
 * single value — and a verify step that compared one line to the target would report success for a
 * process where only the main thread moved.
 */
data class CpuAffinityState(
    /** Thread id to mask, in the order the lines were printed. Never empty. */
    val byThread: Map<Int, Int>,
) {
    /** The distinct masks in force. More than one means the threads disagree. */
    val masks: Set<Int> get() = byThread.values.toSet()

    val threadCount: Int get() = byThread.size

    /** The one mask every thread shares, or null when they do not share one. */
    val uniformMask: Int? get() = masks.singleOrNull()

    /**
     * True when [mask] is what every thread is on.
     *
     * The verification predicate, and it is deliberately all-or-nothing. A write that moved most
     * threads is not a write that did what the user asked, and reporting it as applied would put the
     * one honest claim this feature can make — that the game is on these cores — behind a value that
     * is only mostly true.
     */
    fun matches(mask: Int): Boolean = byThread.isNotEmpty() && masks == setOf(mask)

    /**
     * The mask of the main thread, whose tid equals the process's own pid on Linux.
     *
     * What goes into the restore ledger. The alternative — recording every thread's mask so each could
     * be put back individually — is not available, because `-a` has already overwritten them by the
     * time anything could be restored. That loss is documented where the restore happens rather than
     * papered over here.
     */
    fun mainThreadMask(pid: Int): Int? = byThread[pid]
}

/**
 * An affinity mask as `taskset` writes and prints it: bare lowercase hex, no `0x`.
 *
 * One object for both directions because the same string is the argument to a command, the value in a
 * restore row and the thing read back out of the command's own output, and a mask that round-trips
 * through three of those and not the fourth is a bug nobody sees until a restore does nothing.
 *
 * Unsigned throughout. A 32-core device's mask has the top bit set, which `Int.toString(16)` renders
 * as a minus sign and `toInt(16)` refuses to read back — so the conversion goes through `UInt` in both
 * directions and the 32-core case is asserted rather than assumed.
 */
object CpuAffinityMask {

    fun hex(mask: Int): String = mask.toUInt().toString(radix = 16)

    /**
     * [text] as a mask, or null if it is not one.
     *
     * A `0x` prefix is accepted because a shell on some builds prints one, and the empty mask is
     * refused: a process pinned to no cores cannot run, so a zero here is a parse that went wrong
     * rather than a value to write.
     */
    fun parse(text: String?): Int? {
        val trimmed = text?.trim()?.removePrefix("0x")?.removePrefix("0X") ?: return null
        if (trimmed.isEmpty() || trimmed.length > MAX_HEX_DIGITS) return null
        val mask = trimmed.toUIntOrNull(radix = 16)?.toInt() ?: return null
        return if (mask == 0) null else mask
    }

    /** The core indices [mask] names, ascending. */
    fun cores(mask: Int): List<Int> =
        (0 until CpuClusterLayout.MAX_CORES).filter { (mask shr it) and 1 == 1 }

    /** Eight hex digits is a 32-bit mask. More is not a longer mask, it is not a mask. */
    private const val MAX_HEX_DIGITS = 8
}

/**
 * What came of one attempt to pin a game, or to let it go again.
 *
 * The same shape as [DisplaySizeOutcome] and for the same reason: there is no case here that means
 * "the command did not error". [Applied] requires that every thread's mask was read back and matched,
 * and the two cases that would otherwise be quietly folded into it — the layout could not express the
 * preset, and the game has no process to pin — are their own variants because the sentence the user
 * needs is different in each.
 */
sealed interface CpuAffinityOutcome {

    /** Confirmed: every thread in the process is on [coreIndices], read back after the write. */
    data class Applied(
        val preset: CpuAffinityPreset,
        val coreIndices: List<Int>,
        val verifiedBy: String,
    ) : CpuAffinityOutcome

    /** Confirmed: the process is back on the cores it was found on. */
    data class Restored(val coreIndices: List<Int>, val verifiedBy: String) : CpuAffinityOutcome

    /**
     * There was nothing left to put back, so the obligation is discharged.
     *
     * Success, and the distinction from [Restored] is the point: this says GameCore did *not* write
     * anything and does not need to. It is the ordinary end of an affinity change — the game exited, and
     * a mask lives in a task struct, so it went with the process. Reporting it as [Restored] would claim
     * a write that never happened, and reporting it as a failure would leave a row pending that no
     * future attempt could ever clear.
     */
    data class NothingToRestore(val detail: String) : CpuAffinityOutcome

    /** The write went through and the threads are not all on the mask that was asked for. */
    data class NotHonoured(
        val preset: CpuAffinityPreset?,
        val actual: Set<Int>,
    ) : CpuAffinityOutcome

    /** Written, and not confirmable. Shown as "asked for, not confirmed", never as success. */
    data class AppliedUnverified(
        val preset: CpuAffinityPreset?,
        val reason: String,
    ) : CpuAffinityOutcome

    /** This device's core layout cannot express the preset. [CpuAffinityPreset.on] says why. */
    data class PresetUnsupported(
        val preset: CpuAffinityPreset,
        val detail: String,
    ) : CpuAffinityOutcome

    /** The game has no process to pin — usually because it has not finished starting. */
    data class ProcessNotFound(val packageName: String, val detail: String) : CpuAffinityOutcome

    /** Needs Shizuku. There is no non-elevated way to change another process's affinity. */
    data class RequiresAccess(val detail: String) : CpuAffinityOutcome

    /** A genuine failure: the shell errored, the core layout could not be read, or the mask did not parse. */
    data class Failed(val detail: String) : CpuAffinityOutcome

    val isSuccess: Boolean
        get() = this is Applied || this is Restored || this is NothingToRestore

    /** What the user is told. Every case has its own sentence, and none of them promises a frame rate. */
    val message: String
        get() = when (this) {
            is Applied -> "${preset.label}: the game is on ${describeCores(coreIndices)}."
            is Restored -> "The game is back on ${describeCores(coreIndices)}."
            is NothingToRestore -> detail
            is NotHonoured -> if (actual.isEmpty()) {
                "This device accepted the change and reported no cores back, so GameCore is not " +
                    "claiming the game moved."
            } else {
                "This device accepted the change and the game's threads are still spread across " +
                    "${actual.size} different core sets. Some kernels refuse an affinity change for " +
                    "another app's process, and a game that pins its own threads overrides it."
            }
            is AppliedUnverified ->
                "Asked for ${preset?.label ?: "the previous cores"}. The change could not be " +
                    "confirmed: $reason"
            is PresetUnsupported -> detail
            is ProcessNotFound -> detail
            is RequiresAccess -> detail
            is Failed -> detail
        }
}

/** `cores 4–7`, or `core 3`, as a phrase that fits inside a sentence. */
private fun describeCores(indices: List<Int>): String = when {
    indices.isEmpty() -> "no cores"
    indices.size == 1 -> "core ${indices.first()}"
    indices == (indices.first()..indices.last()).toList() ->
        "cores ${indices.first()}–${indices.last()}"
    else -> "cores ${indices.joinToString(", ")}"
}
