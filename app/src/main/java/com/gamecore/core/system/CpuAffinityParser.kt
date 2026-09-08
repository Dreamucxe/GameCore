package com.gamecore.core.system

import com.gamecore.core.common.DataSource
import com.gamecore.core.common.Observed
import com.gamecore.core.model.CpuAffinityMask
import com.gamecore.core.model.CpuAffinityState

/**
 * The lines `taskset -ap` prints.
 *
 * Kept out of [DumpsysParsers] for the reason [DisplaySizeParser] is — this is one shell command with a
 * small fixed output rather than a `dumpsys` section — and it follows the same rule, which is the one
 * that matters here more than anywhere: a parse that does not match returns [Observed.Failed], never a
 * guess. A guessed mask is not a wrong number on a screen. It is the value that goes into the restore
 * ledger, so a fabricated one would be written back to the game's process on exit and would put it on
 * cores it was never on.
 *
 * ```
 * pid 4021's current affinity mask: ff
 * pid 4088's current affinity mask: ff
 * pid 4091's current affinity mask: f0
 * ```
 *
 * One line per thread, which is what `-a` is for. `current` becomes `new` on the lines printed after a
 * write, so the word between the pid and `affinity mask` is matched loosely rather than spelled out —
 * it carries no information this app needs, and pinning the parse to one release's choice of adjective
 * would break it on the next.
 *
 * Three shapes are deliberately not accepted. An affinity *list* (`0-3,7`) is what `taskset -c` prints
 * and no command in this app passes `-c`, so a list arriving here means the output is not from the
 * command that was run. A mask of zero is refused by [CpuAffinityMask.parse], because a process allowed
 * to run on no core does not run. And output with no matching line at all is a failure even when the
 * command exited zero: `taskset` prints nothing for a pid that has gone, and an empty parse read as
 * "no cores restricted" would be the app inventing the most reassuring of the possible answers.
 */
internal object CpuAffinityParser {

    fun parse(text: String): Observed<CpuAffinityState> {
        val byThread = LinkedHashMap<Int, Int>()
        MASK_LINE.findAll(text).forEach { match ->
            val tid = match.groupValues[1].toIntOrNull() ?: return@forEach
            val mask = CpuAffinityMask.parse(match.groupValues[2]) ?: return@forEach
            if (tid > 0) byThread[tid] = mask
        }
        return if (byThread.isEmpty()) {
            Observed.Failed("taskset did not report an affinity mask for that process")
        } else {
            Observed.of(CpuAffinityState(byThread), DataSource.SHELL_SHIZUKU)
        }
    }

    /**
     * `pid 4021's current affinity mask: ff`.
     *
     * Anchored on the apostrophe-s and on the words `affinity mask`, which are the two parts of the
     * line that have been the same in every implementation of this tool. The `0x` prefix is optional
     * because some builds print one; [CpuAffinityMask.parse] removes it again either way, so there is
     * one place that decides what a mask string is.
     */
    private val MASK_LINE = Regex(
        """(\d+)'s\s+\S+\s+affinity\s+mask:\s*(?:0x)?([0-9a-fA-F]+)""",
        RegexOption.IGNORE_CASE,
    )
}
