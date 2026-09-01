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
    ;

    companion object {
        fun from(raw: String?): CapturePurpose? = entries.firstOrNull { it.name == raw }
    }
}
