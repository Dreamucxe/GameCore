package com.gamecore.domain.gaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The detection allowlist and pin, as a table of readings.
 *
 * These cases are the ones that were previously impossible to test without a device: whether an
 * excluded game stays silent even though it has an enabled profile, whether a pin can override that
 * silence (it cannot), and whether the screen's explanation matches what the detector will do.
 */
class DetectionPolicyTest {

    @Test
    fun `a blank reading is never a package`() {
        val decision = DetectionPolicy.decide(
            packageName = "   ",
            enabledProfilePackages = setOf(GAME),
            excluded = emptySet(),
            pinned = GAME,
        )

        assertFalse(decision.shouldTrack)
        assertEquals(DetectionPolicy.REASON_NO_PACKAGE, decision.reason)
    }

    @Test
    fun `an excluded game is not tracked even with an enabled profile and a pin`() {
        val decision = DetectionPolicy.decide(
            packageName = GAME,
            enabledProfilePackages = setOf(GAME),
            excluded = setOf(GAME),
            pinned = GAME,
        )

        assertFalse(decision.shouldTrack)
        assertEquals(DetectionPolicy.REASON_EXCLUDED, decision.reason)
        assertEquals(GAME, decision.packageName)
    }

    @Test
    fun `a pinned game is tracked and says so`() {
        val decision = DetectionPolicy.decide(
            packageName = GAME,
            enabledProfilePackages = setOf(GAME),
            excluded = emptySet(),
            pinned = GAME,
        )

        assertTrue(decision.shouldTrack)
        assertEquals(DetectionPolicy.REASON_PINNED, decision.reason)
    }

    @Test
    fun `an enabled game is tracked without a pin`() {
        val decision = DetectionPolicy.decide(
            packageName = GAME,
            enabledProfilePackages = setOf(GAME),
            excluded = emptySet(),
            pinned = null,
        )

        assertTrue(decision.shouldTrack)
        assertEquals(DetectionPolicy.REASON_ENABLED, decision.reason)
    }

    @Test
    fun `a game with no enabled profile is not tracked`() {
        val decision = DetectionPolicy.decide(
            packageName = GAME,
            enabledProfilePackages = emptySet(),
            excluded = emptySet(),
            pinned = null,
        )

        assertFalse(decision.shouldTrack)
        assertEquals(DetectionPolicy.REASON_NO_ENABLED_PROFILE, decision.reason)
    }

    @Test
    fun `a pin is reported even when the enabled profile set is empty`() {
        // The detector's tracked set is still built from enabled profiles, so a stale pin on a profile
        // the user has since disabled must not start a session. The policy can still say the package is
        // pinned — that is what makes the Games screen able to explain the badge — while the detector's
        // set intersection keeps the pin from inventing a running game.
        val decision = DetectionPolicy.decide(
            packageName = GAME,
            enabledProfilePackages = emptySet(),
            excluded = emptySet(),
            pinned = GAME,
        )

        assertTrue(decision.shouldTrack)
        assertEquals(DetectionPolicy.REASON_PINNED, decision.reason)
    }

    @Test
    fun `exclusion toggle adds and removes one package`() {
        val empty = emptySet<String>()

        val added = DetectionPolicy.excludedAfterToggle(empty, GAME, exclude = true)
        assertEquals(setOf(GAME), added)

        val removed = DetectionPolicy.excludedAfterToggle(added, GAME, exclude = false)
        assertTrue(removed.isEmpty())
    }

    @Test
    fun `exclusion toggle is idempotent`() {
        val current = setOf(GAME)

        assertEquals(current, DetectionPolicy.excludedAfterToggle(current, GAME, exclude = true))
        assertEquals(current, DetectionPolicy.excludedAfterToggle(current, OTHER, exclude = false))
    }

    @Test
    fun `a blank exclusion is ignored`() {
        val current = setOf(GAME)

        assertEquals(current, DetectionPolicy.excludedAfterToggle(current, "   ", exclude = true))
        assertEquals(current, DetectionPolicy.excludedAfterToggle(current, "", exclude = false))
    }

    private companion object {
        const val GAME = "com.example.game"
        const val OTHER = "com.example.other"
    }
}
