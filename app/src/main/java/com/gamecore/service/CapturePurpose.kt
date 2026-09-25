package com.gamecore.service

/**
 * Why a capture is being started.
 *
 * A closed set because both the consent Activity and [ScreenRecordingService] are reached by `Intent`,
 * and an Intent extra is untrusted input even for a component that is not exported — §24A.3 and §24A.8.
 * [from] returns null for anything unrecognised, which the receivers treat as "do nothing and stop"
 * rather than guessing at an intent, and the `when` that dispatches a valid purpose has no `else`.
 *
 * Screenshots are in here alongside recording because of an Android 14 rule that surprises everyone:
 * `createVirtualDisplay` is only permitted while a foreground service of type `mediaProjection` is
 * running, so a single frame of the screen has to be taken by the same service that records video.
 */
enum class CapturePurpose {
    SCREENSHOT,
    START_RECORDING,
    STOP_RECORDING,

    /**
     * Start and stop the pinned magnifier's frame feed (§13).
     *
     * Here for the same reason screenshots are: the feed is a `createVirtualDisplay` call, which Android 14
     * only permits while a `mediaProjection` foreground service is running, so it has to be driven through
     * the same service. [START_FRAME_FEED] needs a live projection exactly as the others do — no projection
     * means the consent flow first. [STOP_FRAME_FEED] is the one purpose that must *not* open consent when
     * there is no projection: stopping a feed that is already down is a no-op, not a reason to prompt.
     */
    START_FRAME_FEED,
    STOP_FRAME_FEED,
    ;

    companion object {
        fun from(raw: String?): CapturePurpose? = entries.firstOrNull { it.name == raw }
    }
}
