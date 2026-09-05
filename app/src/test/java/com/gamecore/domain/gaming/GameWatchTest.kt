package com.gamecore.domain.gaming

import com.gamecore.core.model.StopReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The state machine that decides when a game started and when it stopped, driven one sighting at a time
 * against a fake clock.
 *
 * Everything downstream hangs off the two events this class produces. A [GameEvent.Started] is the only
 * thing that re-reads a profile and applies it; a [GameEvent.Stopped] is the only thing that closes a
 * session row and puts the device back. So a missing event is not a cosmetic problem — it is a profile
 * the user edited that never takes effect, or a phone left pinned at 120 Hz on the launcher — and none of
 * it is reachable through the real poll loop on a host with no `UsageStatsManager`.
 *
 * The cases below are the interesting ones rather than the enumerable ones: the interruptions that must
 * not end a session, the absences that must, and the two clocks that deliberately do not ring on their
 * own — a user reading their live graph in GameCore, and a foreground that cannot be read at all.
 */
class GameWatchTest {

    @Test
    fun `a tracked game in front starts a session, and staying in it does not start a second`() {
        val driver = Driver()
        assertEquals(listOf(GameEvent.Started(GAME, T0)), driver.saw(Sighting.Tracked(GAME), T0))
        assertTrue(driver.watch.isTracking)
        assertEquals(NONE, driver.saw(Sighting.Tracked(GAME), T0 + 2_000))
        assertEquals(NONE, driver.saw(Sighting.Tracked(GAME), T0 + 600_000))
    }

    /** The shade, a quick reply, a permission dialog: gone and back inside the window, so nothing. */
    @Test
    fun `an interruption shorter than the grace window is not an event`() {
        val driver = Driver()
        driver.saw(Sighting.Tracked(GAME), T0)
        assertEquals(NONE, driver.saw(Sighting.Other, T0 + 2_000))
        assertTrue(driver.watch.isInGracePeriod)
        assertEquals(NONE, driver.saw(Sighting.Tracked(GAME), T0 + 5_000))
        assertFalse(driver.watch.isInGracePeriod)
        assertTrue(driver.watch.isTracking)
    }

    @Test
    fun `a game gone for the whole grace window stops, dated where it left`() {
        val driver = Driver()
        driver.saw(Sighting.Tracked(GAME), T0)
        val left = T0 + 2_000
        driver.saw(Sighting.Other, left)
        assertEquals(
            listOf(GameEvent.Stopped(GAME, left, StopReason.LEFT_FOREGROUND)),
            driver.saw(Sighting.Other, left + GameWatch.DEFAULT_GRACE_MILLIS),
        )
        assertFalse(driver.watch.isTracking)
    }

    // ------------------------------------------------------------------- the game comes back

    /**
     * The bug reported as "changing a game's profile after leaving the game and pressing play again
     * doesn't really change anything unless you quit the app itself and open it back".
     *
     * Nothing was cached. The profile is re-read from the database on every [GameEvent.Started] — and
     * only there — so the whole of the bug is the event that was never emitted: [Sighting.Inspecting]
     * keeps the session alive for as long as the user stays in GameCore, which is deliberate, and the
     * game returning afterwards used to be filed as "still the game I was already tracking".
     *
     * The workaround the user found is the tell. Quitting GameCore puts the launcher in front, and the
     * absence clock has by then been running since the game left, so that single sighting expires it
     * immediately and the next launch is a real start.
     */
    @Test
    fun `a game coming back from a long look at GameCore is a new sitting`() {
        val driver = Driver()
        driver.saw(Sighting.Tracked(GAME), T0)
        val left = T0 + 3_000
        driver.saw(Sighting.Other, left)
        assertEquals(NONE, driver.saw(Sighting.Inspecting, left + 4_000))
        // Five grace windows in GameCore, and the session is still the user's to read about.
        assertEquals(NONE, driver.saw(Sighting.Inspecting, left + 60_000))
        assertTrue(driver.watch.isTracking)

        val back = left + 90_000
        assertEquals(
            listOf(
                GameEvent.Stopped(GAME, left, StopReason.LEFT_FOREGROUND),
                GameEvent.Started(GAME, back),
            ),
            driver.saw(Sighting.Tracked(GAME), back),
        )
        assertTrue(driver.watch.isTracking)
        assertFalse(driver.watch.isInGracePeriod)
    }

    /** The other half of the same rule: a glance at the graph is an interruption, not a sitting. */
    @Test
    fun `a game coming back from a glance at GameCore is the same sitting`() {
        val driver = Driver()
        driver.saw(Sighting.Tracked(GAME), T0)
        driver.saw(Sighting.Inspecting, T0 + 1_000)
        assertEquals(NONE, driver.saw(Sighting.Tracked(GAME), T0 + 6_000))
        assertTrue(driver.watch.isTracking)
    }

    // ------------------------------------------------------------------------ nothing to see

    /**
     * Blindness is not absence, and it does not become absence by lasting: the clock is carried through
     * untouched, so a game that was three seconds into its grace window when detection failed is three
     * seconds into it when detection comes back.
     */
    @Test
    fun `an absence carried through blindness is still an absence when the game returns`() {
        val driver = Driver()
        driver.saw(Sighting.Tracked(GAME), T0)
        val left = T0 + 1_000
        driver.saw(Sighting.Other, left)
        driver.saw(Sighting.Unreadable(REVOKED), left + 3_000)
        assertTrue(driver.watch.isBlind)
        assertEquals(left, driver.watch.absentSinceMillis)

        val back = left + 30_000
        assertEquals(
            listOf(
                GameEvent.Stopped(GAME, left, StopReason.LEFT_FOREGROUND),
                GameEvent.Started(GAME, back),
            ),
            driver.saw(Sighting.Tracked(GAME), back),
        )
        assertFalse(driver.watch.isBlind)
    }

    @Test
    fun `a foreground that stays unreadable ends the session as detection lost`() {
        val driver = Driver()
        driver.saw(Sighting.Tracked(GAME), T0)
        val blind = T0 + 1_000
        assertEquals(NONE, driver.saw(Sighting.Unreadable(REVOKED), blind))
        assertEquals(
            listOf(
                GameEvent.Stopped(
                    GAME,
                    blind + GameWatch.DEFAULT_BLIND_MILLIS,
                    StopReason.DETECTION_LOST,
                ),
            ),
            driver.saw(Sighting.Unreadable(REVOKED), blind + GameWatch.DEFAULT_BLIND_MILLIS),
        )
        assertFalse(driver.watch.isTracking)
        assertEquals(REVOKED, driver.watch.blindDetail)
    }

    // ---------------------------------------------------------------- switches and manual stops

    @Test
    fun `a straight switch between two games stops one before starting the other`() {
        val driver = Driver()
        driver.saw(Sighting.Tracked(GAME), T0)
        val switch = T0 + 5_000
        assertEquals(
            listOf(
                GameEvent.Stopped(GAME, switch, StopReason.SWITCHED_GAME),
                GameEvent.Started(OTHER_GAME, switch),
            ),
            driver.saw(Sighting.Tracked(OTHER_GAME), switch),
        )
        assertEquals(OTHER_GAME, driver.watch.runningPackage)
    }

    /**
     * A stop the user asked for, with the game still on screen — which is every manual stop, because
     * the button is in an overlay drawn over the game.
     */
    @Test
    fun `a game stopped by hand does not start again while it is still in front`() {
        val driver = Driver()
        driver.saw(Sighting.Tracked(GAME), T0)
        val stop = T0 + 10_000
        assertEquals(
            listOf(GameEvent.Stopped(GAME, stop, StopReason.STOPPED_BY_USER)),
            driver.stopped(StopReason.STOPPED_BY_USER, stop),
        )
        assertEquals(NONE, driver.saw(Sighting.Tracked(GAME), stop + 2_000))
        assertEquals(NONE, driver.saw(Sighting.Tracked(GAME), stop + 600_000))
        assertFalse(driver.watch.isTracking)
    }

    /** GameCore is not "somewhere else", so a user who stops a session and checks it keeps it stopped. */
    @Test
    fun `looking at GameCore after a manual stop does not lift the suppression`() {
        val driver = Driver()
        driver.saw(Sighting.Tracked(GAME), T0)
        driver.stopped(StopReason.STOPPED_BY_USER, T0 + 10_000)
        driver.saw(Sighting.Inspecting, T0 + 20_000)
        assertEquals(GAME, driver.watch.suppressedPackage)
        assertEquals(NONE, driver.saw(Sighting.Tracked(GAME), T0 + 25_000))
    }

    @Test
    fun `a stopped game is tracked again once the user has been somewhere else`() {
        val driver = Driver()
        driver.saw(Sighting.Tracked(GAME), T0)
        driver.stopped(StopReason.STOPPED_BY_USER, T0 + 10_000)
        driver.saw(Sighting.Other, T0 + 20_000)
        val again = T0 + 30_000
        assertEquals(listOf(GameEvent.Started(GAME, again)), driver.saw(Sighting.Tracked(GAME), again))
    }

    // --------------------------------------------------------------------------------- the loop

    /**
     * [GameDetector]'s poll loop with the polling taken out: one reading in, whatever it meant out, and
     * the events cleared before the next one — which is the part a test that folded [GameWatch.saw]
     * directly would get wrong, because an unconsumed event list is carried into the next state.
     */
    private class Driver {
        var watch: GameWatch = GameWatch.INITIAL
            private set

        fun saw(sighting: Sighting, atMillis: Long): List<GameEvent> = record(watch.saw(sighting, atMillis))

        fun stopped(reason: StopReason, atMillis: Long): List<GameEvent> =
            record(watch.stopped(reason, atMillis))

        private fun record(next: GameWatch): List<GameEvent> {
            watch = next.consumed()
            return next.events
        }
    }

    private companion object {
        const val GAME = "com.example.shooter"
        const val OTHER_GAME = "com.example.racer"
        const val REVOKED = "Usage access was turned off."

        /** An arbitrary wall clock, well away from zero so a dropped offset shows up as one. */
        const val T0 = 1_700_000_000_000L

        val NONE = emptyList<GameEvent>()
    }
}
