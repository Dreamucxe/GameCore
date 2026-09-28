package com.gamecore.domain.gaming.replay

/**
 * Why the Instant Replay buffer is stopping. Thermal is deliberately absent — heat only *pauses* the buffer
 * (see [ReplayThermalMachine]) and never tears it down, so there is no thermal stop trigger.
 */
enum class StopTrigger {
    /** The game session ended; the whole capture goes with it. */
    SessionEnd,

    /** The user turned Instant Replay off. */
    ToggleOff,

    /** The foreground service is being destroyed (system reclaim, app stop). */
    ServiceStop,

    /** The storage guard fired mid-session; there is no longer room to keep buffering. */
    StorageExhausted,

    /** Blocked-capture detection tripped ([BlackFrameDetector]); the feed is unusable this session. */
    BlockedCapture,
}

/**
 * What tearing the buffer down entails. Each flag is one resource the caller owns.
 *
 * @param stopEncoder stop the MediaRecorder/encoder feeding the buffer.
 * @param discardBuffer delete the rolling private-cache segments (they are only useful live).
 * @param removeNotification take down the ongoing FGS notification for the buffer.
 * @param releaseProjection release the shared MediaProjection token. Only safe when nothing else in the
 *   session still needs the projection — releasing it forces a fresh consent prompt to re-acquire.
 */
data class CleanupPlan(
    val stopEncoder: Boolean,
    val discardBuffer: Boolean,
    val removeNotification: Boolean,
    val releaseProjection: Boolean,
)

/**
 * The teardown plan for a stop, per trigger.
 *
 * The encoder is always stopped, the buffer always discarded, and the notification always removed — a buffer
 * that has stopped for any reason keeps none of its resources and shows no lingering ongoing notification.
 * The one flag that varies is [CleanupPlan.releaseProjection]:
 *
 *  - [StopTrigger.SessionEnd], [StopTrigger.ToggleOff], [StopTrigger.ServiceStop] end the *capture* itself,
 *    so the shared MediaProjection is released. Nothing else needs it: the session is over, the user opted
 *    out, or the service is dying.
 *  - [StopTrigger.StorageExhausted] and [StopTrigger.BlockedCapture] stop only Instant Replay, mid-session.
 *    The projection may still back other captures this session (a manual recording, the magnifier feed), and
 *    releasing it would drop those and force a new consent prompt. So the token is kept — Instant Replay
 *    lets go of its own resources but not the session-shared one.
 *
 * The `when` has no `else`, so adding a [StopTrigger] is a compile error until its plan is stated here.
 */
object StopCleanup {
    fun plan(trigger: StopTrigger): CleanupPlan = when (trigger) {
        StopTrigger.SessionEnd,
        StopTrigger.ToggleOff,
        StopTrigger.ServiceStop -> CleanupPlan(
            stopEncoder = true,
            discardBuffer = true,
            removeNotification = true,
            releaseProjection = true,
        )

        StopTrigger.StorageExhausted,
        StopTrigger.BlockedCapture -> CleanupPlan(
            stopEncoder = true,
            discardBuffer = true,
            removeNotification = true,
            releaseProjection = false,
        )
    }
}
