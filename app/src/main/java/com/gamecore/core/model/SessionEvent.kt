package com.gamecore.core.model

/**
 * Something that happened during a recorded session, placed on the session's own clock.
 *
 * The session report is built from samples, and samples answer "what was the device doing" but not
 * "what changed". A thermal warning, a refresh cap, a profile that only half landed and a frame-time
 * spike are events: they happen at a moment, they are not readings that can be averaged, and the
 * difference between a session that ran hot from the first second and one that was fine until minute
 * forty is exactly which of these happened and when.
 *
 * [elapsedMillis] is the offset from the session's start, not wall time, for the same reason
 * [SessionSample.elapsedMillis] is: a device whose clock is changed mid-session should not produce a
 * timeline that jumps backwards, and a report only ever needs to place an event relative to the game.
 *
 * [value] carries one number when the event has one — the temperature level for a thermal transition,
 * the p99 frame time for a spike, the round trip for a probe — and is null when the event is only its
 * kind and [detail]. Nothing here is a verdict: the attribution engine decides what an event means,
 * and it is allowed to say that it does not know.
 */
data class SessionEvent(
    val sessionId: Long,
    val elapsedMillis: Long,
    val kind: SessionEventKind,
    val detail: String? = null,
    val value: Double? = null,
)

/**
 * The kinds of event a session records.
 *
 * Deliberately a closed set rather than free-form text: the timeline, the stutter attribution and the
 * backup all branch on these, and a string that only one caller knows how to spell is a string that
 * silently stops matching after a rename. The entity stores the name, and an unknown name read back
 * from a newer build is dropped rather than guessed at.
 *
 * The labels are the user-facing short form. A screen that needs a longer explanation writes one;
 * these are what a timeline row and a receipt line can use without a second vocabulary.
 */
enum class SessionEventKind(val label: String) {
    /** The profile's changes were applied and at least one was confirmed. */
    PROFILE_APPLIED("Profile applied"),

    /** The profile was applied only in part; [SessionEvent.detail] names what did not land. */
    PROFILE_APPLY_FAILED("Profile not fully applied"),

    /** The platform's thermal status crossed into a level that is actually clamping performance. */
    THERMAL_WARNING("Device started throttling"),

    /** The platform's thermal status came back below the warning levels. */
    THERMAL_RECOVERED("Thermal pressure eased"),

    /** The display's active rate moved below the peak the panel reports. */
    REFRESH_CAPPED("Display below its peak rate"),

    /** The display's active rate came back to the panel's peak. */
    REFRESH_RESTORED("Display back at its peak rate"),

    /** The system's battery saver was switched on during the session. */
    POWER_SAVER_ON("Battery saver on"),

    /** The system's battery saver was switched off during the session. */
    POWER_SAVER_OFF("Battery saver off"),

    /** A frame-timing window had a tail bad enough to be worth attributing. */
    FRAME_OUTLIER("Frame-time spike"),

    /**
     * One network probe's result.
     *
     * [SessionEvent.detail] names the target ("gateway" or "upstream"), and [SessionEvent.value] is
     * the round trip in milliseconds or null when the probe did not complete. These are kept as
     * events rather than as a second sample table because attribution needs the probe's time, not an
     * average repeated on every tick between probes.
     */
    NETWORK_PROBE("Network probe"),

    /** A network probe crossed the latency threshold the session treats as a spike. */
    NETWORK_SPIKE("Network latency spike"),

    /** The network came back below the spike threshold after a run of spikes. */
    NETWORK_CLEAR("Network latency recovered"),
}
