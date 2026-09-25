package com.gamecore.core.overlay

import com.gamecore.core.model.OptimizationResult

/**
 * Turns the applier's per-setting result list into the one short line the apply-diff overlay shows.
 *
 * §2 of the feature batch: the diff already exists — [com.gamecore.core.model.ProfileApplier] returns
 * a `List<OptimizationResult>` and records every previous value before writing — so this is
 * presentation only. It recomputes nothing; it renders what the applier already decided, honestly:
 *
 * - A setting written and confirmed by reading it back ([OptimizationResult.Applied]) reads as its
 *   plain label ("Set brightness").
 * - A setting written but not confirmed ([OptimizationResult.Unverified]) carries "(not confirmed)",
 *   the exact word the applier's own [OptimizationResult.message] uses, so the overlay never claims a
 *   change the device did not report back — the §24 honesty rule as a string.
 * - A setting attempted that did not take ([OptimizationResult.NotHonoured]/[OptimizationResult.Failed])
 *   says "didn't apply".
 * - Settings the profile never asked for ([OptimizationResult.Skipped]) and settings this device
 *   cannot attempt ([OptimizationResult.Blocked]) are not *changes*; they are left out. A profile that
 *   only skips therefore produces no line at all — [summarize] returns null and the caller shows
 *   nothing, never an empty toast.
 *
 * Pure and android-free so the mapping — labels, ordering, skip collapsing, the "not confirmed"
 * wording — is unit-tested directly on the JVM, the same shape [RefreshRateFeedback] follows.
 */
object ApplyDiff {

    /** What the confirmed/attempted segments are joined by, matching the panel's own dot separator. */
    private const val SEPARATOR = " · "

    /**
     * The ~2s "what changed" line, or null when nothing worth interrupting the user for happened.
     *
     * Changes come first in the applier's own plan order, then the failures, so the line reads
     * "here is what I set … and these did not apply".
     */
    fun summarize(results: List<OptimizationResult>): String? {
        val changed = results.mapNotNull { result ->
            when (result) {
                is OptimizationResult.Applied -> result.action.label
                is OptimizationResult.Unverified -> "${result.action.label} (not confirmed)"
                else -> null
            }
        }
        val problems = results.mapNotNull { result ->
            when (result) {
                is OptimizationResult.NotHonoured, is OptimizationResult.Failed ->
                    "${result.action.label} didn't apply"
                else -> null
            }
        }
        val segments = changed + problems
        return segments.ifEmpty { null }?.joinToString(SEPARATOR)
    }
}
