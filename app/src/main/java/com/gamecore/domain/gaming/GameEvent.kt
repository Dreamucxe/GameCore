package com.gamecore.domain.gaming

import com.gamecore.core.model.StopReason

/**
 * Something that happened to a tracked game, as the detector saw it.
 *
 * Deliberately a small closed set. Everything downstream — applying a profile, raising the overlay,
 * opening a session row — hangs off these two cases, and a third "maybe" case would end up being
 * handled as one of them anyway by whichever consumer forgot about it.
 */
sealed interface GameEvent {

    val packageName: String

    /** When it happened, in wall-clock millis. Not "when it was noticed" — see [GameWatch]. */
    val atMillis: Long

    /** A game with an enabled profile came to the foreground. */
    data class Started(
        override val packageName: String,
        override val atMillis: Long,
    ) : GameEvent

    /**
     * A game GameCore was tracking is no longer being played, and why it concluded that.
     *
     * [reason] is stored with the session rather than being just a log line, because "you closed the
     * game" and "GameCore lost the ability to see which app is in front" produce identical-looking
     * session rows and only one of them means the recorded duration is complete.
     */
    data class Stopped(
        override val packageName: String,
        override val atMillis: Long,
        val reason: StopReason,
    ) : GameEvent
}
