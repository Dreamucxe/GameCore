package com.gamecore.core.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [OverlaySamplingGate] — the rule that decides when the overlay is allowed to read `/proc`, `/sys`,
 * the battery and the network counters once a second.
 *
 * This is a battery test, not a formatting one. Every `true` below is a sampling loop the device pays for
 * and every `false` is one it does not, so the interesting cases are the ones where something is *asked*
 * for and the answer is still no: a display that is off, and a floating button on its own.
 */
class OverlaySamplingGateTest {

    // --- the screen term: the §9 leak -------------------------------------------------------------

    @Test
    fun `a pill on a dark screen does not sample`() {
        // The leak this gate exists to close. The request survives the screen going off — the user did
        // not dismiss anything — so before §9 the loop kept sampling into a window nobody could see, for
        // as long as the phone sat in a pocket.
        assertFalse(shouldSample(pill = true, screenOn = false))
    }

    @Test
    fun `a HUD on a dark screen does not sample either`() {
        assertFalse(shouldSample(hud = true, screenOn = false))
    }

    @Test
    fun `a panel left open on a dark screen does not sample`() {
        assertFalse(shouldSample(panel = true, screenOn = false))
    }

    @Test
    fun `everything at once on a dark screen still does not sample`() {
        // The screen is an AND over the whole group, not a tie-breaker between its members: no amount of
        // requested surface outvotes a display that is off.
        assertFalse(
            OverlaySamplingGate.shouldSample(
                pillVisible = true,
                hudVisible = true,
                panelOpen = true,
                screenInteractive = false,
            ),
        )
    }

    @Test
    fun `the screen coming back resumes sampling`() {
        // The flip has to work in both directions — a gate that latched off would leave a pill frozen on
        // its last reading for the rest of the session.
        assertFalse(shouldSample(pill = true, screenOn = false))
        assertTrue(shouldSample(pill = true, screenOn = true))
    }

    // --- the surface term: what counts as needing a reading ---------------------------------------

    @Test
    fun `each surface that draws a reading is reason enough on its own`() {
        assertTrue(shouldSample(pill = true))
        assertTrue(shouldSample(hud = true))
        assertTrue(shouldSample(panel = true))
    }

    @Test
    fun `a floating button alone costs nothing`() {
        // Deliberate, and the reason the button is not a term: its thermal dot is defined to be absent
        // until a sample lands, so a button sitting on screen by itself never starts the loop. Someone
        // "fixing" this would turn the cheapest overlay into the one that samples forever.
        assertFalse(shouldSample())
    }

    @Test
    fun `a lit screen with nothing on it does not sample`() {
        // The other half of the AND. Being visible is permission to sample, never a request for it.
        assertFalse(shouldSample(screenOn = true))
    }

    @Test
    fun `nothing requested on a dark screen is the cheapest case`() {
        assertFalse(shouldSample(screenOn = false))
    }

    private fun shouldSample(
        pill: Boolean = false,
        hud: Boolean = false,
        panel: Boolean = false,
        screenOn: Boolean = true,
    ): Boolean = OverlaySamplingGate.shouldSample(
        pillVisible = pill,
        hudVisible = hud,
        panelOpen = panel,
        screenInteractive = screenOn,
    )
}
