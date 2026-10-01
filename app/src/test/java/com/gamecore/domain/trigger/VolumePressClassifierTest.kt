package com.gamecore.domain.trigger

import com.gamecore.core.model.VolumeTriggerButton
import com.gamecore.core.model.VolumeTriggerPressMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [VolumePressClassifier] — the pure single / double / long-press decision behind the volume-button
 * point trigger (feature 4). Time is a plain `Long`; every test hand-feeds [KeyPhase]s and ticks.
 *
 * The load-bearing cases mirror the honest tradeoff in the class KDoc: a lone tap becomes a
 * [PressDecision.Single] only after the double-press window, resolved by a tick; two presses inside the
 * window are one [PressDecision.Double] and never two singles; a held key is a long press; and the two
 * buttons are independent.
 */
class VolumePressClassifierTest {

    private val config = VolumePressConfig(doublePressWindowMs = 320L, longPressThresholdMs = 500L)

    private fun up() = VolumePressState.initial(VolumeTriggerButton.VOLUME_UP)
    private fun down() = VolumePressState.initial(VolumeTriggerButton.VOLUME_DOWN)

    private fun VolumePressState.press(now: Long, phase: KeyPhase): Pair<VolumePressState, PressDecision> =
        VolumePressClassifier.evaluate(this, config, now, phase)

    private fun VolumePressState.tickAt(now: Long): Pair<VolumePressState, PressDecision> =
        VolumePressClassifier.tick(this, config, now)

    // --- alternating presses never fire faster than the window --------------------------------------

    @Test
    fun `alternating taps never emit Singles faster than the window`() {
        var s = up()
        val singleTimes = mutableListOf<Long>()
        for (i in 0 until 3) {
            val base = i * 1_000L
            s = s.press(base, KeyPhase.DOWN).first
            s = s.press(base + 20L, KeyPhase.UP).first
            // Before the window closes: no Single yet.
            val early = s.tickAt(base + 100L)
            s = early.first
            assertEquals(PressDecision.None, early.second)
            // After the window: exactly one Single.
            val done = s.tickAt(base + config.doublePressWindowMs)
            s = done.first
            assertEquals(PressDecision.Single, done.second)
            singleTimes += base + config.doublePressWindowMs
        }
        // Consecutive singles are at least a full window apart — never faster.
        for (i in 1 until singleTimes.size) {
            assertTrue(singleTimes[i] - singleTimes[i - 1] >= config.doublePressWindowMs)
        }
    }

    // --- per-button independence --------------------------------------------------------------------

    @Test
    fun `each button keeps its own independent state`() {
        var upState = up()
        var downState = down()

        // A full double on VOLUME_UP, interleaved with one lone tap on VOLUME_DOWN.
        upState = upState.press(0L, KeyPhase.DOWN).first
        downState = downState.press(5L, KeyPhase.DOWN).first
        upState = upState.press(30L, KeyPhase.UP).first
        downState = downState.press(35L, KeyPhase.UP).first

        val upDouble = upState.press(200L, KeyPhase.DOWN)
        upState = upDouble.first
        assertEquals(PressDecision.Double, upDouble.second)

        // VOLUME_DOWN, untouched since its single tap, still resolves as a Single on its own tick.
        val downSingle = downState.tickAt(500L)
        downState = downSingle.first
        assertEquals(PressDecision.Single, downSingle.second)

        assertEquals(VolumeTriggerButton.VOLUME_UP, upState.button)
        assertEquals(VolumeTriggerButton.VOLUME_DOWN, downState.button)
    }

    // --- decision -> press-mode mapping -------------------------------------------------------------

    @Test
    fun `decisions map to the matching press modes`() {
        assertEquals(VolumeTriggerPressMode.SINGLE_TAP, PressDecision.Single.pressMode)
        assertEquals(VolumeTriggerPressMode.DOUBLE_TAP, PressDecision.Double.pressMode)
        assertEquals(VolumeTriggerPressMode.HOLD, PressDecision.LongPressStart.pressMode)
        assertEquals(VolumeTriggerPressMode.HOLD, PressDecision.LongPressEnd(500L).pressMode)
        assertNull(PressDecision.None.pressMode)
    }

    // --- long hold ----------------------------------------------------------------------------------

    @Test
    fun `holding past the threshold is a long press start then end`() {
        var s = up()
        s = s.press(0L, KeyPhase.DOWN).first

        val early = s.tickAt(499L)
        s = early.first
        assertEquals(PressDecision.None, early.second)

        val start = s.tickAt(500L)
        s = start.first
        assertEquals(PressDecision.LongPressStart, start.second)

        val end = s.press(900L, KeyPhase.UP)
        s = end.first
        assertTrue(end.second is PressDecision.LongPressEnd)
        assertEquals(900L, (end.second as PressDecision.LongPressEnd).durationMs)
        assertEquals(VolumeTriggerPressMode.HOLD, end.second.pressMode)
    }

    @Test
    fun `a hold is still recognised on release when no ticks are fed`() {
        var s = up()
        s = s.press(0L, KeyPhase.DOWN).first
        val end = s.press(700L, KeyPhase.UP)
        assertTrue(end.second is PressDecision.LongPressEnd)
        assertEquals(700L, (end.second as PressDecision.LongPressEnd).durationMs)
    }

    // --- double within the window -------------------------------------------------------------------

    @Test
    fun `two taps within the window are one Double and zero Singles`() {
        var s = up()
        val seen = mutableListOf<PressDecision>()
        s = s.press(0L, KeyPhase.DOWN).also { seen += it.second }.first
        s = s.press(40L, KeyPhase.UP).also { seen += it.second }.first
        s = s.press(200L, KeyPhase.DOWN).also { seen += it.second }.first
        s = s.press(240L, KeyPhase.UP).also { seen += it.second }.first
        // Advancing well past the window must NOT now also produce a Single.
        s = s.tickAt(2_000L).also { seen += it.second }.first

        assertEquals(1, seen.count { it is PressDecision.Double })
        assertEquals(0, seen.count { it is PressDecision.Single })
    }

    // --- single resolves only after the window, via tick --------------------------------------------

    @Test
    fun `a lone tap resolves as Single only after the window via tick`() {
        var s = up()
        s = s.press(0L, KeyPhase.DOWN).first
        val afterUp = s.press(30L, KeyPhase.UP)
        s = afterUp.first
        assertEquals(PressDecision.None, afterUp.second)

        val early = s.tickAt(319L) // still inside the window
        s = early.first
        assertEquals(PressDecision.None, early.second)

        val confirmed = s.tickAt(320L) // window elapsed
        s = confirmed.first
        assertEquals(PressDecision.Single, confirmed.second)

        // ...and it never fires a second time.
        assertEquals(PressDecision.None, s.tickAt(5_000L).second)
    }
}
