package com.gamecore.domain.gaming.replay

/**
 * The one word the Instant Replay chip shows, settled from every input at once (mirrors
 * [com.gamecore.core.model.overlayWindowState]). Six states rather than a boolean because the five
 * not-buffering cases each have a different cause and a different remedy, and §10 forbids conveying them
 * with a greyed control alone.
 */
enum class ReplayChip(val label: String) {
    /** On and healthy: the rolling buffer is capturing. */
    Buffering("Buffering"),

    /** Paused by [ReplayThermalMachine] because the device is critically hot. Resumes on its own. */
    PausedOverheating("Paused: overheating"),

    /** Blocked-capture detection tripped ([BlackFrameDetector]); this game will not capture. */
    UnavailableBlocked("Unavailable: can't capture this app"),

    /** The storage guard says there is not enough room ([StorageBudget]). */
    UnavailableStorage("Unavailable: not enough storage"),

    /** Screen-capture consent has not been granted, so nothing can be captured. */
    NeedsPermission("Needs permission"),

    /** The user has Instant Replay switched off. */
    Off("Off"),
}

/**
 * Classify the chip. Precedence, highest first:
 *
 *  1. `!enabled` → [ReplayChip.Off]. The user's own switch outranks everything; a feature turned off has no
 *     business reporting a storage or heat problem it is not even trying to run into.
 *  2. `!hasPermission` → [ReplayChip.NeedsPermission]. Without consent nothing captures whatever else is
 *     true, and it is the one blocker the user resolves directly.
 *  3. `storage` Unavailable → [ReplayChip.UnavailableStorage]. A hard precondition checked before the
 *     runtime states, because it holds even before the encoder starts.
 *  4. `blocked` → [ReplayChip.UnavailableBlocked]. A capture that produces only black frames is reported
 *     ahead of a thermal pause: it will not become useful when the device cools, so it is the more final
 *     answer.
 *  5. `thermalPaused` → [ReplayChip.PausedOverheating]. A transient, self-clearing state, reported last of
 *     the problems.
 *  6. otherwise → [ReplayChip.Buffering].
 */
fun replayChip(
    enabled: Boolean,
    hasPermission: Boolean,
    storage: StorageVerdict,
    blocked: Boolean,
    thermalPaused: Boolean,
): ReplayChip = when {
    !enabled -> ReplayChip.Off
    !hasPermission -> ReplayChip.NeedsPermission
    storage is StorageVerdict.Unavailable -> ReplayChip.UnavailableStorage
    blocked -> ReplayChip.UnavailableBlocked
    thermalPaused -> ReplayChip.PausedOverheating
    else -> ReplayChip.Buffering
}
