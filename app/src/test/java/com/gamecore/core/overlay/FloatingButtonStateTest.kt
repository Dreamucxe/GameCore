package com.gamecore.core.overlay

import com.gamecore.core.model.FloatingButtonConfig
import com.gamecore.core.model.ThermalClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The floating button's two state axes (spec §2), proved on a fake clock.
 *
 * The dot mapping is a table with one deliberate surprise — UNAVAILABLE draws nothing, not a grey dot — and
 * the idle dimmer is a timestamp comparison, so §10's "idle-dim state machine with a fake clock" is these
 * assertions rather than a wait. No `Thread.sleep`, no Android.
 */
class FloatingButtonStateTest {

    // ---------------------------------------------------------------------- the thermal dot

    @Test
    fun `only hot and critical earn a dot`() {
        assertEquals(ThermalDot.CRITICAL, thermalDot(ThermalClass.CRITICAL))
        assertEquals(ThermalDot.HOT, thermalDot(ThermalClass.HOT))
        assertEquals(ThermalDot.NONE, thermalDot(ThermalClass.WARM))
        assertEquals(ThermalDot.NONE, thermalDot(ThermalClass.OK))
    }

    @Test
    fun `an unavailable temperature draws no dot, because absent is not safe`() {
        // The whole point of the spec's wording: a temperature the platform will not report must not be
        // shown as a reassuring grey dot. It is the same "no fake data" rule the pill follows.
        assertEquals(ThermalDot.NONE, thermalDot(ThermalClass.UNAVAILABLE))
    }

    @Test
    fun `every thermal class maps to a dot, so a new class cannot be silently dropped`() {
        // The `when` in thermalDot is exhaustive; this pins that each class has an intended answer rather
        // than falling through, and that only two are non-empty.
        val dots = ThermalClass.entries.map { thermalDot(it) }
        assertEquals(2, dots.count { it != ThermalDot.NONE })
    }

    // ---------------------------------------------------------------------- the idle dimmer

    private val dimmer = IdleDimmer(idleAfterMillis = 3_000L)

    @Test
    fun `a fresh touch is active`() {
        assertEquals(ButtonDim.ACTIVE, dimmer.dimAt(lastInteractionMillis = 10_000L, nowMillis = 10_000L))
    }

    @Test
    fun `still active one millisecond before the idle window closes`() {
        assertEquals(ButtonDim.ACTIVE, dimmer.dimAt(lastInteractionMillis = 10_000L, nowMillis = 12_999L))
    }

    @Test
    fun `idle exactly at the window and after`() {
        assertEquals(ButtonDim.IDLE, dimmer.dimAt(lastInteractionMillis = 10_000L, nowMillis = 13_000L))
        assertEquals(ButtonDim.IDLE, dimmer.dimAt(lastInteractionMillis = 10_000L, nowMillis = 99_000L))
    }

    @Test
    fun `a touch while idle returns to active`() {
        // Idle at t=13000; the touch resets lastInteraction to now, and the state is active again.
        assertEquals(ButtonDim.IDLE, dimmer.dimAt(10_000L, 13_000L))
        assertEquals(ButtonDim.ACTIVE, dimmer.dimAt(lastInteractionMillis = 13_000L, nowMillis = 13_000L))
    }

    @Test
    fun `the view is told exactly when to repaint, once`() {
        assertEquals(3_000L, dimmer.nextChangeAfterMillis(lastInteractionMillis = 10_000L, nowMillis = 10_000L))
        assertEquals(1L, dimmer.nextChangeAfterMillis(lastInteractionMillis = 10_000L, nowMillis = 12_999L))
    }

    @Test
    fun `once idle there is nothing more to schedule`() {
        assertNull(dimmer.nextChangeAfterMillis(lastInteractionMillis = 10_000L, nowMillis = 13_000L))
        assertNull(dimmer.nextChangeAfterMillis(lastInteractionMillis = 10_000L, nowMillis = 20_000L))
    }

    @Test
    fun `the default idle window matches the button's old three seconds`() {
        assertEquals(3_000L, IdleDimmer.DEFAULT_IDLE_AFTER_MILLIS)
        assertEquals(3_000L, IdleDimmer().idleAfterMillis)
    }

    /**
     * The corner bridge (spec §2's named Snap positions): pinning a corner writes it to both orientations,
     * a fresh or free-dragged config is pinned to no corner, and a button dragged in one orientation only
     * stops being "pinned" because the two orientations no longer name the same corner.
     */
    @Test
    fun `a fresh button is pinned to no corner`() {
        assertNull(FloatingButtonConfig().pinnedCorner())
    }

    @Test
    fun `pinning a corner reads back as that corner in both orientations`() {
        for (corner in Corner.entries) {
            val pinned = FloatingButtonConfig().withCorner(corner)
            assertEquals(corner, pinned.pinnedCorner())
            assertEquals(corner.fraction.xFraction to corner.fraction.yFraction, pinned.positionFraction(portrait = true))
            assertEquals(corner.fraction.xFraction to corner.fraction.yFraction, pinned.positionFraction(portrait = false))
        }
    }

    @Test
    fun `a corner in one orientation but a free spot in the other is pinned to no corner`() {
        val split = FloatingButtonConfig()
            .withCorner(Corner.TOP_RIGHT)
            .withPositionFraction(portrait = false, xFraction = 0.4f, yFraction = 0.7f)
        assertNull(split.pinnedCorner())
        // The portrait corner is still readable as itself; it is only the *pin* that needs both to agree.
        assertEquals(0.4f to 0.7f, split.positionFraction(portrait = false))
    }

    @Test
    fun `the default anchor is not mistaken for a corner`() {
        val defaultAnchor = FloatingButtonConfig()
            .withPositionFraction(portrait = true, xFraction = 1f, yFraction = 0.75f)
            .withPositionFraction(portrait = false, xFraction = 1f, yFraction = 0.75f)
        assertNull(defaultAnchor.pinnedCorner())
    }
}
