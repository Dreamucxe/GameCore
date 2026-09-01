package com.gamecore.domain.gaming

import com.gamecore.core.model.StopReason

/**
 * One reading of what is in front, reduced to the only three answers the state machine can act on.
 *
 * [Unreadable] is the case that earns this type. `String?` would collapse it into [Other] — a null
 * package meaning both "the launcher is in front" and "usage access was revoked ten seconds ago" —
 * and the second of those must not end a session the way the first does.
 */
sealed interface Sighting {

    /** A game with an enabled profile is in the foreground. */
    data class Tracked(val packageName: String) : Sighting

    /** Something is in the foreground and it is not a tracked game. */
    data object Other : Sighting

    /**
     * GameCore's own UI is in front.
     *
     * Separate from [Other] because the user tabbing out of a game *into GameCore* — to read the
     * live graph, to change the profile they are in the middle of testing — is the one departure
     * that must not end the session being read about.
     */
    data object Inspecting : Sighting

    /** The foreground could not be determined. [detail] is what to record if this persists. */
    data class Unreadable(val detail: String) : Sighting
}

/**
 * The detector's belief about what is being played, as an immutable value.
 *
 * A value type rather than fields on the poller for the same reason [com.gamecore.domain.monitoring.ThermalWatch]
 * is one: §31 has to be able to drive "game leaves the foreground for eight seconds and comes back"
 * and "usage access is revoked mid-session" with a fake clock and a list of sightings, and neither is
 * reachable through a real poll loop in a JVM test.
 *
 * Four behaviours are deliberate and are the whole substance of the class:
 *
 *  1. **A game that leaves the foreground has not necessarily stopped.** The notification shade, a
 *     quick reply, an incoming call and the share sheet all take the foreground for a few seconds.
 *     Ending the session — and so restoring the refresh rate, dropping the overlay and closing the
 *     session row — for any of those would make GameCore useless during exactly the moments a player
 *     alt-tabs. So absence starts a clock and only [graceMillis] of it declares a stop.
 *
 *  2. **A stop is dated when the game left, not when the grace expired.** [absentSinceMillis] is
 *     carried for that reason. Dating it at expiry would add the whole grace window to every recorded
 *     session, which is inventing playtime.
 *
 *  3. **Blindness is not absence.** An [Sighting.Unreadable] reading does not advance the absence
 *     clock, because there is no evidence the game went anywhere. It advances a separate clock, and
 *     when that one runs out the session ends with [StopReason.DETECTION_LOST] rather than
 *     [StopReason.LEFT_FOREGROUND]. It does end: a watcher that has gone blind and keeps a device
 *     pinned indefinitely is worse than one that admits it lost track.
 *
 *  4. **Reading about a session does not end it.** [Sighting.Inspecting] runs the absence clock but
 *     never expires it, so a user who opens GameCore mid-game keeps their session and their overlay
 *     for as long as they stay, and the stop they eventually get is still dated from the moment the
 *     game actually left the screen rather than from when they put the phone down.
 *
 * [events] is what the last [saw] produced, and it is a list because one reading can mean two things:
 * switching straight from one tracked game to another is a stop and a start, in that order.
 */
data class GameWatch(
    /** The tracked game believed to be running, or null if none is. */
    val runningPackage: String? = null,
    /** When [runningPackage] was first seen *not* in front, or null if it is in front. */
    val absentSinceMillis: Long? = null,
    /** When the foreground first became unreadable, or null if it is readable. */
    val blindSinceMillis: Long? = null,
    /** Why it became unreadable, kept for the session record. */
    val blindDetail: String? = null,
    /**
     * A game the user stopped tracking by hand, which is not to be started again while it is still
     * the app in front.
     *
     * Without this, a manual stop lasts exactly one poll: the game is still in the foreground, so the
     * next reading is a tracked game with nothing running, which is the definition of a start. The
     * suppression is dropped as soon as the user goes somewhere that is neither the game nor GameCore
     * itself, because coming back after that is a new sitting rather than a continuation of the one
     * they ended.
     */
    val suppressedPackage: String? = null,
    /** What the most recent [saw] concluded. Cleared by [consumed]. */
    val events: List<GameEvent> = emptyList(),
) {

    /** True while a game is being tracked, including while it is inside its grace window. */
    val isTracking: Boolean get() = runningPackage != null

    /** True when a tracked game is not currently in front but has not been given up on yet. */
    val isInGracePeriod: Boolean get() = runningPackage != null && absentSinceMillis != null

    /** True when GameCore cannot see the foreground at all. */
    val isBlind: Boolean get() = blindSinceMillis != null

    /**
     * Folds one reading in, returning the next belief and whatever it means.
     *
     * Pure and total: every combination of previous state and [sighting] produces a state, and the
     * only clock involved is [nowMillis].
     */
    fun saw(
        sighting: Sighting,
        nowMillis: Long,
        graceMillis: Long = DEFAULT_GRACE_MILLIS,
        blindMillis: Long = DEFAULT_BLIND_MILLIS,
    ): GameWatch = when (sighting) {
        is Sighting.Tracked -> sawTracked(sighting.packageName, nowMillis)
        is Sighting.Other -> sawSomethingElse(nowMillis, graceMillis)
        is Sighting.Inspecting -> sawInspecting(nowMillis)
        is Sighting.Unreadable -> sawNothing(sighting.detail, nowMillis, blindMillis)
    }

    /** Drops the emitted events once a consumer has acted on them. */
    fun consumed(): GameWatch = if (events.isEmpty()) this else copy(events = emptyList())

    /**
     * Ends tracking on someone else's say-so — the user's stop button, or the service shutting down.
     *
     * Produces the [GameEvent.Stopped] so the session closes through the same path as a detected
     * stop; a caller that ended sessions directly would be a second copy of that logic and would
     * drift from this one. The game is then suppressed: see [suppressedPackage].
     */
    fun stopped(reason: StopReason, nowMillis: Long): GameWatch {
        val running = runningPackage ?: return consumed()
        return GameWatch(
            suppressedPackage = running,
            events = listOf(GameEvent.Stopped(running, nowMillis, reason)),
        )
    }

    // ------------------------------------------------------------------------ transitions

    private fun sawTracked(packageName: String, nowMillis: Long): GameWatch = when {
        // Stopped by hand and still in front. Nothing to report and nothing to restart.
        packageName == suppressedPackage -> GameWatch(suppressedPackage = packageName)

        // Still there, or back inside the grace window. The absence clock is cleared either way,
        // which is what makes a shade pull followed by a return a non-event.
        packageName == runningPackage -> GameWatch(runningPackage = packageName)

        runningPackage == null -> GameWatch(
            runningPackage = packageName,
            events = listOf(GameEvent.Started(packageName, nowMillis)),
        )

        // A different tracked game. Two events, stop first: the profile for the old game has to be
        // restored before the new one is applied or the second write would be undone by the first
        // game's restore point.
        else -> GameWatch(
            runningPackage = packageName,
            events = listOf(
                GameEvent.Stopped(runningPackage, nowMillis, StopReason.SWITCHED_GAME),
                GameEvent.Started(packageName, nowMillis),
            ),
        )
    }

    private fun sawSomethingElse(nowMillis: Long, graceMillis: Long): GameWatch {
        val running = runningPackage ?: return GameWatch()
        val since = absentSinceMillis ?: nowMillis
        // `>=` rather than `>` so a grace of zero — which a test may well pass — means "no grace"
        // instead of "one extra poll".
        if (nowMillis - since >= graceMillis) {
            return GameWatch(
                events = listOf(GameEvent.Stopped(running, since, StopReason.LEFT_FOREGROUND)),
            )
        }
        return GameWatch(runningPackage = running, absentSinceMillis = since)
    }

    /**
     * The user is in GameCore. The clock runs; it does not ring.
     *
     * Running it matters — the eventual stop is dated from the first reading that was not the game,
     * so a player who checks the graph for two minutes and then closes both apps gets a session
     * ending where the game ended. Not ringing matters for the obvious reason: the session summary
     * being read would close under the reader.
     */
    private fun sawInspecting(nowMillis: Long): GameWatch {
        val running = runningPackage
            ?: return GameWatch(suppressedPackage = suppressedPackage)
        return GameWatch(
            runningPackage = running,
            absentSinceMillis = absentSinceMillis ?: nowMillis,
        )
    }

    private fun sawNothing(detail: String, nowMillis: Long, blindMillis: Long): GameWatch {
        val since = blindSinceMillis ?: nowMillis
        val running = runningPackage ?: return GameWatch(
            blindSinceMillis = since,
            blindDetail = detail,
            suppressedPackage = suppressedPackage,
        )

        if (nowMillis - since >= blindMillis) {
            return GameWatch(
                blindSinceMillis = since,
                blindDetail = detail,
                events = listOf(GameEvent.Stopped(running, nowMillis, StopReason.DETECTION_LOST)),
            )
        }
        // The absence clock is passed through untouched rather than cleared or advanced: a game that
        // was already two seconds into its grace window when detection failed has not gained or lost
        // any of it, because nothing has been observed either way.
        return GameWatch(
            runningPackage = running,
            absentSinceMillis = absentSinceMillis,
            blindSinceMillis = since,
            blindDetail = detail,
        )
    }

    companion object {
        val INITIAL = GameWatch()

        /**
         * How long a tracked game may be off-screen before the session ends.
         *
         * Twelve seconds, which is long enough for the shade, a quick reply and a permission dialog,
         * and short enough that a session ended by the user closing the game is not credited with a
         * suspiciously round extra chunk of time. The stop is dated from the start of the window
         * regardless, so this length affects how *quickly* a real stop is noticed, never the recorded
         * duration.
         */
        const val DEFAULT_GRACE_MILLIS = 12_000L

        /**
         * How long GameCore may be unable to read the foreground before it closes the session.
         *
         * Longer than the grace window, because the recovery is plausible: Shizuku restarting or a
         * usage-stats query failing once are both transient, and a session that survives them is
         * worth more than one that ends at the first hiccup.
         */
        const val DEFAULT_BLIND_MILLIS = 45_000L
    }
}
