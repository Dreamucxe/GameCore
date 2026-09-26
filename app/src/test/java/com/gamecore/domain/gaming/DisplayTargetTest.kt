package com.gamecore.domain.gaming

import com.gamecore.core.model.DisplaySize
import com.gamecore.core.model.GameProfile
import com.gamecore.core.model.ResolutionScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one decision in the display path a profile is not allowed to make twice (§B2).
 *
 * A profile carries two fields that are the same `wm size` write, and a build that honoured both would
 * write the second over the first, keep one restore row for two changes, and report both as applied. What
 * is pinned here is that exactly one target comes out, that the resolution override is the one, and that
 * "neither field set" stays an absence rather than becoming some default target.
 */
class DisplayTargetTest {

    private fun profile() = GameProfile.forGame("com.example.game", "Example")

    @Test
    fun `a profile that sets neither field has no display target`() {
        // Null and not a Stretch-to-native or a Scale-FULL: with no field set the profile has not asked
        // for a display change at all, and the plan turns that into a skip rather than a write.
        assertNull(DisplayTarget.of(profile()))
    }

    @Test
    fun `a display size alone becomes a stretch of exactly that size`() {
        val size = DisplaySize(1600, 900)
        val target = DisplayTarget.of(profile().copy(displaySize = size))
        assertEquals(DisplayTarget.Stretch(size), target)
    }

    @Test
    fun `a resolution override alone becomes a scale, and every preset qualifies`() {
        ResolutionScale.entries.forEach { scale ->
            val target = DisplayTarget.of(profile().copy(resolutionOverride = scale))
            assertEquals(scale.name, DisplayTarget.Scale(scale), target)
        }
    }

    @Test
    fun `FULL is a scale and not an absence, because it is a real reset-to-native request`() {
        // The mirror of the rule the profile model states: null is the only spelling of "leave the
        // resolution alone", so FULL has to arrive as a target rather than falling through to null and
        // letting the display stay on whatever the last game left it at.
        assertEquals(
            DisplayTarget.Scale(ResolutionScale.FULL),
            DisplayTarget.of(profile().copy(resolutionOverride = ResolutionScale.FULL)),
        )
    }

    @Test
    fun `a profile holding both resolves to the scale, and to exactly one target`() {
        // Reachable only through an imported file — the editor does not offer both — which is the case
        // this exists for. The resolution override wins: it is the statement about *this* panel, and the
        // size is the leftover from the device the profile was exported from.
        val both = profile().copy(
            displaySize = DisplaySize(1280, 720),
            resolutionOverride = ResolutionScale.MEDIUM,
        )
        val target = DisplayTarget.of(both)
        assertEquals(DisplayTarget.Scale(ResolutionScale.MEDIUM), target)
        // And the losing field is not smuggled along: nothing in the target mentions the size.
        assertTrue(target !is DisplayTarget.Stretch)
    }

    @Test
    fun `the losing size is chosen against, not merged with, the winning scale`() {
        // A single target — not a pair, and not a scale computed from the size that lost. Two entries
        // would mean two `wm size` writes and one restore row for both.
        val both = profile().copy(
            displaySize = DisplaySize(1280, 720),
            resolutionOverride = ResolutionScale.HIGH,
        )
        val target = DisplayTarget.of(both)
        assertEquals(ResolutionScale.HIGH, (target as DisplayTarget.Scale).scale)
    }
}
