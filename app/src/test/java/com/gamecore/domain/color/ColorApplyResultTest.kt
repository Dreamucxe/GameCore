package com.gamecore.domain.color

import com.gamecore.core.common.DataSource
import com.gamecore.core.common.Observed
import com.gamecore.core.shizuku.WritableSetting
import com.gamecore.core.system.ColorPlan
import com.gamecore.core.system.ColorWrite
import com.gamecore.core.system.SettingsWriteOutcome
import com.gamecore.core.system.WriteMechanism
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the colour feature says about the sinks it deliberately did not write.
 *
 * The second half of the reported Do Not Disturb bug lives here. Night light, greyscale and extra dim
 * are one tap from the notification shade, so a preset applied automatically at the start of every
 * session has the same opportunity to write over a manual change that [com.gamecore.domain.optimization.WriteLedger]
 * exists to stop — and [ColorCorrectionController] writes these keys itself rather than through
 * [com.gamecore.domain.optimization.OptimizationManager], so the guard had to be threaded through here
 * too.
 *
 * These tests are about the *sentence*, which is the part a guard is easy to get wrong in a way nobody
 * notices. A correction that passed over every sink it was asked to write has no failures, and every
 * question this type answers about failures says so — an implementation that stopped at "no failures,
 * therefore applied" would report "Applied." for a correction that wrote nothing at all.
 */
class ColorApplyResultTest {

    @Test
    fun `a correction that wrote nothing because the user owns every sink does not claim to have applied`() {
        val result = resultOf(
            plan = planOf(NIGHT_LIGHT, GREYSCALE),
            results = emptyList(),
            keptByUser = listOf(NIGHT_LIGHT, GREYSCALE),
        )
        assertEquals(
            "You changed the screen's colour yourself after GameCore set it, so it has been left alone.",
            result.message,
        )
    }

    @Test
    fun `a correction that wrote one sink and left another says both`() {
        val result = resultOf(
            plan = planOf(NIGHT_LIGHT, GREYSCALE),
            results = listOf(applied(NIGHT_LIGHT)),
            keptByUser = listOf(GREYSCALE),
        )
        assertEquals("Applied. One value was left as you set it.", result.message)
    }

    @Test
    fun `two sinks left to the user are counted rather than named`() {
        val result = resultOf(
            plan = planOf(NIGHT_LIGHT, GREYSCALE, EXTRA_DIM),
            results = listOf(applied(NIGHT_LIGHT)),
            keptByUser = listOf(GREYSCALE, EXTRA_DIM),
        )
        assertEquals("Applied. 2 values were left as you set them.", result.message)
    }

    /**
     * The regression guard. Every correction the user asks for keeps its claim on every sink, so this
     * is the sentence the colour screen and the panel over the game have always shown, and the guard
     * must not have changed it.
     */
    @Test
    fun `a correction with nothing left to the user reads exactly as it did before`() {
        val result = resultOf(
            plan = planOf(NIGHT_LIGHT),
            results = listOf(applied(NIGHT_LIGHT)),
            keptByUser = emptyList(),
        )
        assertEquals("Applied.", result.message)
    }

    /** A refusal still leads: what went wrong is what the user needs first, and the rest follows it. */
    @Test
    fun `a sink the device refused leads the sentence even when another was left to the user`() {
        val result = resultOf(
            plan = planOf(NIGHT_LIGHT, GREYSCALE),
            results = listOf(failed(NIGHT_LIGHT)),
            keptByUser = listOf(GREYSCALE),
        )
        assertEquals(
            "${NIGHT_LIGHT.userDescription} could not be changed. One value was left as you set it.",
            result.message,
        )
    }

    /** Nothing was written, so nothing can be counted as applied — including the sinks passed over. */
    @Test
    fun `sinks left to the user are not counted as applied`() {
        val result = resultOf(
            plan = planOf(NIGHT_LIGHT, GREYSCALE),
            results = emptyList(),
            keptByUser = listOf(NIGHT_LIGHT, GREYSCALE),
        )
        assertEquals(0, result.appliedCount)
        assertEquals(emptyList<ColorSinkResult>(), result.failures)
    }

    private companion object {
        val NIGHT_LIGHT = WritableSetting.NIGHT_DISPLAY_ACTIVATED
        val GREYSCALE = WritableSetting.DALTONIZER_ENABLED
        val EXTRA_DIM = WritableSetting.REDUCE_BRIGHT_COLORS_ACTIVATED

        val ACCESS: Observed<WriteMechanism> =
            Observed.of(WriteMechanism.WRITE_SECURE_SETTINGS, DataSource.SETTINGS_PROVIDER)

        fun planOf(vararg sinks: WritableSetting) = ColorPlan(
            writes = sinks.map { ColorWrite(setting = it, value = "1", because = "the test asked") },
            limits = emptyList(),
        )

        fun applied(setting: WritableSetting) = ColorSinkResult(
            write = ColorWrite(setting = setting, value = "1", because = "the test asked"),
            outcome = SettingsWriteOutcome.Applied(
                value = "1",
                previousValue = "0",
                mechanism = WriteMechanism.WRITE_SECURE_SETTINGS,
            ),
        )

        fun failed(setting: WritableSetting) = ColorSinkResult(
            write = ColorWrite(setting = setting, value = "1", because = "the test asked"),
            outcome = SettingsWriteOutcome.Failed("the test refused it"),
        )

        fun resultOf(
            plan: ColorPlan,
            results: List<ColorSinkResult>,
            keptByUser: List<WritableSetting>,
        ) = ColorApplyResult(
            plan = plan,
            results = results,
            restored = emptyList(),
            access = ACCESS,
            keptByUser = keptByUser,
        )
    }
}
