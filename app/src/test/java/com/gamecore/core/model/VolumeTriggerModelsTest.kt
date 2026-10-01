package com.gamecore.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The volume-trigger models' clamps and defaults, which are what make a config assembled anywhere — a fresh
 * profile, the editor, or a hand-edited backup file — safe for the dispatcher to act on.
 *
 * Two of these matter more than the rest. The point clamp is the promise the whole feature rests on: a
 * fraction that came back out of storage outside 0..1 would send the synthetic tap off the screen on the next
 * resolve, so `normalised()` pulls it onto the display exactly as the floating button's fractions are pulled
 * on. And the fully-defaulted config being off with nothing bound is the promise to existing users: a profile
 * that predates this feature, and a new one, taps nothing until the user places a marker and turns it on.
 */
class VolumeTriggerModelsTest {

    // --------------------------------------------------------------------------- the point is a fraction

    @Test
    fun `a point outside zero-to-one is pulled back onto the screen`() {
        assertEquals(FractionPoint(1f, 0f), FractionPoint(1.4f, -0.3f).normalised())
        assertEquals(FractionPoint(0f, 1f), FractionPoint(-2f, 5f).normalised())
    }

    @Test
    fun `a point already inside the screen is left exactly where it was placed`() {
        val placed = FractionPoint(0.25f, 0.8f)
        assertEquals(placed, placed.normalised())
    }

    @Test
    fun `normalising a binding pulls its point onto the screen too`() {
        val wild = VolumeTriggerBinding(point = FractionPoint(2f, -1f)).normalised()
        assertEquals(FractionPoint(1f, 0f), wild.point)
    }

    @Test
    fun `a binding with no point placed stays unassigned through normalisation`() {
        val bare = VolumeTriggerBinding(pressMode = VolumeTriggerPressMode.DOUBLE_TAP).normalised()
        assertNull(bare.point)
        assertFalse(bare.isAssigned)
    }

    // ------------------------------------------------------------------------------------ the hold time

    @Test
    fun `a hold time below the floor comes back up to it`() {
        assertEquals(VolumeTriggerBinding.MIN_HOLD_MS, VolumeTriggerBinding(holdMs = 0).normalised().holdMs)
        assertEquals(VolumeTriggerBinding.MIN_HOLD_MS, VolumeTriggerBinding(holdMs = -100).normalised().holdMs)
    }

    @Test
    fun `a hold time above the ceiling comes back down to it`() {
        assertEquals(VolumeTriggerBinding.MAX_HOLD_MS, VolumeTriggerBinding(holdMs = 60_000).normalised().holdMs)
    }

    /** The shipped default has to sit inside its own clamp, or a fresh binding would normalise to a different value. */
    @Test
    fun `the default hold time is one the clamp agrees with`() {
        assertEquals(VolumeTriggerBinding.DEFAULT_HOLD_MS, VolumeTriggerBinding().holdMs)
        assertTrue(VolumeTriggerBinding.DEFAULT_HOLD_MS in VolumeTriggerBinding.MIN_HOLD_MS..VolumeTriggerBinding.MAX_HOLD_MS)
        assertEquals(VolumeTriggerBinding(), VolumeTriggerBinding().normalised())
    }

    /** The hold time is kept even on the tap modes, so a value set on HOLD survives switching away and back. */
    @Test
    fun `the hold time is clamped and kept regardless of the press mode`() {
        val tapMode = VolumeTriggerBinding(pressMode = VolumeTriggerPressMode.SINGLE_TAP, holdMs = 9_999).normalised()
        assertEquals(VolumeTriggerBinding.MAX_HOLD_MS, tapMode.holdMs)
        assertEquals(VolumeTriggerPressMode.SINGLE_TAP, tapMode.pressMode)
    }

    // ---------------------------------------------------------------------------- the press mode by name

    @Test
    fun `an unknown or absent stored press mode falls back to single tap rather than throwing`() {
        assertEquals(VolumeTriggerPressMode.SINGLE_TAP, VolumeTriggerPressMode.of(null))
        assertEquals(VolumeTriggerPressMode.SINGLE_TAP, VolumeTriggerPressMode.of(""))
        assertEquals(VolumeTriggerPressMode.SINGLE_TAP, VolumeTriggerPressMode.of("SOMETHING_A_LATER_VERSION_ADDED"))
    }

    @Test
    fun `each press mode round-trips through its stored name`() {
        for (mode in VolumeTriggerPressMode.entries) {
            assertEquals(mode, VolumeTriggerPressMode.of(mode.name))
        }
    }

    // -------------------------------------------------------------------------------- the default config

    /**
     * The promise to existing users: a profile from before this feature, and a new one, taps nothing.
     *
     * A default of enabled, or a non-null binding, would mean an install that updated over the old app and
     * never opened this setting suddenly had a volume key stealing its normal job, which is the one behaviour
     * this default exists to rule out.
     */
    @Test
    fun `a fresh config is disabled with neither key bound`() {
        val fresh = VolumeTriggerConfig()
        assertFalse(fresh.enabled)
        assertNull(fresh.up)
        assertNull(fresh.down)
        assertEquals(VolumeTriggerConfig.DEFAULT, fresh)
    }

    @Test
    fun `normalising a fresh config leaves it a disabled no-op`() {
        assertEquals(VolumeTriggerConfig.DEFAULT, VolumeTriggerConfig().normalised())
    }

    @Test
    fun `normalising a config carries the clamp into both bindings`() {
        val messy = VolumeTriggerConfig(
            enabled = true,
            up = VolumeTriggerBinding(point = FractionPoint(1.5f, -0.5f), holdMs = 40),
            down = VolumeTriggerBinding(point = FractionPoint(0.4f, 0.6f), holdMs = 99_999),
        ).normalised()
        assertTrue(messy.enabled)
        assertEquals(FractionPoint(1f, 0f), messy.up?.point)
        assertEquals(VolumeTriggerBinding.MIN_HOLD_MS, messy.up?.holdMs)
        assertEquals(FractionPoint(0.4f, 0.6f), messy.down?.point)
        assertEquals(VolumeTriggerBinding.MAX_HOLD_MS, messy.down?.holdMs)
    }

    @Test
    fun `each key reads back the binding written for it, leaving the other alone`() {
        val config = VolumeTriggerConfig(
            up = VolumeTriggerBinding(point = FractionPoint(0.1f, 0.2f)),
        )
        assertEquals(FractionPoint(0.1f, 0.2f), config.binding(VolumeTriggerButton.VOLUME_UP)?.point)
        assertNull(config.binding(VolumeTriggerButton.VOLUME_DOWN))
    }
}
