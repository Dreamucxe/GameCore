package com.gamecore.domain.display

import com.gamecore.core.model.DisplaySize
import com.gamecore.core.model.DisplaySizeOutcome
import com.gamecore.core.model.DisplaySizeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The read-back, which is the only part of a display size change that can be checked on this host.
 *
 * Everything else about the feature needs a panel: the shell round trip, the settle delay, the
 * reconfiguration of this process. What is left is the decision all of that exists to serve, and it is
 * the one that matters, because every way this feature could lie to a user runs through it — reporting a
 * stretch that never happened, reporting a reset that left the override in place, or calling a display
 * that is still settling a display that refused.
 */
class DisplaySizeVerdictTest {

    private val panel = DisplaySize(1080, 2400)
    private val stretched = DisplaySize(1080, 1440)

    @Test
    fun `a size that has appeared is applied, and named as the device reported it`() {
        val outcome = DisplaySizeVerdict.of(
            state = DisplaySizeState(physical = panel, override = stretched),
            target = stretched,
            isFinalAttempt = false,
        )
        assertEquals(DisplaySizeOutcome.Applied(stretched, "Shizuku shell"), outcome)
        assertTrue(outcome!!.isSuccess)
    }

    @Test
    fun `a size read back the other way up is the same size`() {
        // `wm size` reports the display upright, and GameCore's own window is not. A rotated read is not
        // a failed one, and the size shown is the one the device gave back.
        val outcome = DisplaySizeVerdict.of(
            state = DisplaySizeState(physical = panel, override = DisplaySize(1440, 1080)),
            target = stretched,
            isFinalAttempt = true,
        )
        assertEquals(DisplaySizeOutcome.Applied(DisplaySize(1440, 1080), "Shizuku shell"), outcome)
    }

    @Test
    fun `a size that has not appeared yet is neither applied nor refused`() {
        // The window manager resizes on its own thread, so the first read after the write catches the old
        // size on a device where everything worked. Null is the loop's instruction to read again.
        assertNull(
            DisplaySizeVerdict.of(
                state = DisplaySizeState(physical = panel, override = null),
                target = stretched,
                isFinalAttempt = false,
            ),
        )
    }

    @Test
    fun `a size that never appears is reported as not honoured, with what the display kept`() {
        val outcome = DisplaySizeVerdict.of(
            state = DisplaySizeState(physical = panel, override = null),
            target = stretched,
            isFinalAttempt = true,
        )
        assertEquals(DisplaySizeOutcome.NotHonoured(stretched, panel), outcome)
        assertFalse(outcome!!.isSuccess)
        assertTrue(outcome.message, outcome.message.contains("stayed at 1080 × 2400"))
    }

    @Test
    fun `a reset is confirmed by the override being gone, not by a size comparison`() {
        // The distinction is the whole reason a reset is verified by its own question. A build that
        // dropped the settings row and kept the stretched size would pass a comparison against the panel.
        val cleared = DisplaySizeVerdict.of(
            state = DisplaySizeState(physical = panel, override = null),
            target = null,
            isFinalAttempt = false,
        )
        assertEquals(DisplaySizeOutcome.Restored(panel, "Shizuku shell"), cleared)
        assertTrue(cleared!!.isSuccess)

        val stillThere = DisplaySizeVerdict.of(
            state = DisplaySizeState(physical = panel, override = stretched),
            target = null,
            isFinalAttempt = true,
        )
        assertEquals(DisplaySizeOutcome.NotHonoured(panel, stretched), stillThere)
        assertFalse(stillThere!!.isSuccess)
    }

    @Test
    fun `a reset that has not landed yet is not reported as a refusal either`() {
        assertNull(
            DisplaySizeVerdict.of(
                state = DisplaySizeState(physical = panel, override = stretched),
                target = null,
                isFinalAttempt = false,
            ),
        )
    }

    @Test
    fun `an override equal to the panel is not a cleared override`() {
        // `wm size 1080x2400` on a 1080x2400 panel leaves a row that outlives GameCore. Reading that as
        // "the override is gone" would clear the restore point that is the only record of it.
        val outcome = DisplaySizeVerdict.of(
            state = DisplaySizeState(physical = panel, override = panel),
            target = null,
            isFinalAttempt = true,
        )
        assertEquals(DisplaySizeOutcome.NotHonoured(panel, panel), outcome)
    }

    @Test
    fun `putting back a size the user had set themselves is confirmed the same way`() {
        // The profile-exit path for a display that was already overridden before GameCore touched it. It
        // goes through the same apply path as a preset, so what is confirmed is a size, not a reset — and
        // "back to its native" would be the wrong sentence for it.
        val outcome = DisplaySizeVerdict.of(
            state = DisplaySizeState(physical = panel, override = DisplaySize(1080, 1700)),
            target = DisplaySize(1080, 1700),
            isFinalAttempt = false,
        )
        assertEquals(DisplaySizeOutcome.Applied(DisplaySize(1080, 1700), "Shizuku shell"), outcome)
        assertTrue(outcome!!.message, outcome.message.contains("1080 × 1700"))
    }
}
