package com.gamecore.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The colour model, tested as the vocabulary three separate features read a field through.
 *
 * The keypad asks a field for its range before it accepts a number, the per-field reset asks
 * for its neutral value, and the projection asks what changed. All three go through
 * [ColorCorrection.valueOf] and [ColorCorrection.with], so a field wired to the wrong
 * property would not fail loudly — it would show a saturation slider that quietly edits
 * contrast. That is what the round-trip test here rules out, field by field.
 *
 * The other invariant worth a test is the clamp. §24A.3 keeps a malformed value out of a
 * shell command, but the value reaching the command comes from this class, and a stored
 * correction that came back from disk with 4000 in it has to be pulled inside the range
 * before anything projects it.
 */
class ColorModelsTest {

    @Test
    fun `a new correction asks for nothing at all`() {
        val neutral = ColorCorrection()
        assertTrue(neutral.changesNothing)
        assertEquals(ColorCorrection.NEUTRAL, neutral)
        assertEquals(emptyList<ColorField>(), neutral.changedFields)
        assertEquals("No change", neutral.summary)
        assertEquals(GammaMode.COMBINED, neutral.gammaMode)
        assertEquals(ColorVisionFilter.NONE, neutral.visionFilter)
        assertFalse(neutral.invertColors)
        ColorField.entries.forEach { assertEquals(it.name, 0, neutral.valueOf(it)) }
    }

    @Test
    fun `each field reads back what was written to it and moves nothing else`() {
        ColorField.entries.forEach { field ->
            val written = ColorCorrection().with(field, 42)
            assertEquals(field.name, 42, written.valueOf(field))
            ColorField.entries.filter { it != field }.forEach { other ->
                assertEquals("$field wrote $other", 0, written.valueOf(other))
            }
        }
    }

    @Test
    fun `a value past the end of a field's range is clamped to the range, not stored`() {
        ColorField.entries.forEach { field ->
            val high = ColorCorrection().with(field, 100_000)
            val low = ColorCorrection().with(field, -100_000)
            assertEquals(field.name, field.range.last, high.valueOf(field))
            assertEquals(field.name, field.range.first, low.valueOf(field))
        }
    }

    @Test
    fun `hue turns further than a gain goes, and each stops at its own limit`() {
        // The one place the ranges differ, and the reason a control must not retype them: a
        // slider drawn 0…100 would refuse the left half of every field here, and one drawn
        // −100…100 would refuse half of a hue rotation.
        assertEquals(-180..180, ColorField.HUE.range)
        assertEquals(180, ColorCorrection().with(ColorField.HUE, 400).hueDegrees)
        assertEquals(100, ColorCorrection().with(ColorField.SATURATION, 400).saturation)
        assertEquals(-100, ColorCorrection().with(ColorField.RED_GAIN, -400).redGain)
    }

    @Test
    fun `a correction read back out of range is pulled inside it`() {
        // What normalised() is for: a row written by an older build, or a hand-edited store,
        // must not reach the projection with a value no setting would accept.
        val wild = ColorCorrection(
            redGain = 4_000,
            blueGain = -4_000,
            gamma = 999,
            saturation = -999,
            hueDegrees = 3_600,
            brightnessOffset = -500,
        ).normalised()
        assertEquals(100, wild.redGain)
        assertEquals(-100, wild.blueGain)
        assertEquals(100, wild.gamma)
        assertEquals(-100, wild.saturation)
        assertEquals(180, wild.hueDegrees)
        assertEquals(-100, wild.brightnessOffset)
        assertEquals(wild, wild.normalised())
    }

    @Test
    fun `resetting one channel returns that one and leaves the rest alone`() {
        val set = ColorCorrection(redGain = 40, greenGain = -20, saturation = 60, hueDegrees = 90)
        val reset = set.reset(ColorField.RED_GAIN)
        assertEquals(0, reset.redGain)
        assertEquals(-20, reset.greenGain)
        assertEquals(60, reset.saturation)
        assertEquals(90, reset.hueDegrees)
        assertFalse(reset.changesNothing)

        // "Reset to default" is the neutral value, not a copy() of a blank: doing it to every
        // visible field has to arrive at exactly the same place.
        val cleared = set.visibleFields.fold(set) { acc, field -> acc.reset(field) }
        assertTrue(cleared.changesNothing)
    }

    @Test
    fun `the gamma mode decides which gamma fields exist`() {
        val combined = ColorCorrection(gammaMode = GammaMode.COMBINED)
        assertEquals(listOf(ColorField.GAMMA), combined.gammaFields)
        assertEquals(8, combined.visibleFields.size)

        val perChannel = combined.copy(gammaMode = GammaMode.PER_CHANNEL)
        assertEquals(
            listOf(ColorField.RED_GAMMA, ColorField.GREEN_GAMMA, ColorField.BLUE_GAMMA),
            perChannel.gammaFields,
        )
        assertEquals(10, perChannel.visibleFields.size)
        assertFalse(ColorField.GAMMA in perChannel.visibleFields)
    }

    @Test
    fun `both sets of gamma values survive a trip through the other mode`() {
        // The reason both are stored: a user who sets three channels, looks at the combined
        // slider and goes back must find their three values, not three zeros.
        val authored = ColorCorrection(
            gammaMode = GammaMode.PER_CHANNEL,
            redGamma = 30,
            greenGamma = -10,
            blueGamma = 5,
        ).with(ColorField.GAMMA, 20)
        val roundTripped = authored
            .copy(gammaMode = GammaMode.COMBINED)
            .copy(gammaMode = GammaMode.PER_CHANNEL)
        assertEquals(authored, roundTripped)
        assertEquals(20, roundTripped.gamma)
        assertEquals(30, roundTripped.redGamma)
        assertEquals(-10, roundTripped.greenGamma)
        assertEquals(5, roundTripped.blueGamma)
    }

    @Test
    fun `changedFields is what the screen shows and changesNothing is what a profile unwinds`() {
        // Not the same question, and this is the case that separates them: a per-channel gamma
        // value is retained while the combined slider is on screen, so the screen has nothing
        // changed to list while the record still carries a value. It reads as "Custom" and the
        // Reset button stays live, which is the safe direction — there is something to clear.
        val hidden = ColorCorrection(gammaMode = GammaMode.COMBINED, redGamma = 40)
        assertEquals(emptyList<ColorField>(), hidden.changedFields)
        assertFalse(hidden.changesNothing)

        // A vision filter or an inversion is not a numeric field, so neither shows up in
        // changedFields either — and both are things the device has to be put back from.
        assertFalse(ColorCorrection(visionFilter = ColorVisionFilter.PROTANOPIA).changesNothing)
        assertFalse(ColorCorrection(invertColors = true).changesNothing)

        // And the view preference on its own is not a change to anything at all.
        assertTrue(ColorCorrection(gammaMode = GammaMode.PER_CHANNEL).changesNothing)
    }

    @Test
    fun `the summary names every changed value and says so when there are none`() {
        val correction = ColorCorrection(
            saturation = 45,
            hueDegrees = -30,
            visionFilter = ColorVisionFilter.TRITANOPIA,
            invertColors = true,
        )
        assertEquals(
            "Saturation +45%, Hue rotation -30°, Tritanopia, Inverted",
            correction.summary,
        )
        assertEquals(
            listOf(ColorField.SATURATION, ColorField.HUE),
            correction.changedFields,
        )
        // A saved preset that asks for nothing is legitimate, and a blank subtitle would read
        // as a rendering fault rather than as an answer.
        assertEquals("No change", ColorPreset(name = "Empty").correction.summary)
    }

    @Test
    fun `a value is signed on the positive side only and carries its own unit`() {
        assertEquals("+45%", ColorField.SATURATION.format(45))
        assertEquals("-45%", ColorField.SATURATION.format(-45))
        assertEquals("0%", ColorField.SATURATION.format(0))
        assertEquals("+90°", ColorField.HUE.format(90))
        assertEquals("-180°", ColorField.HUE.format(-180))
        assertEquals("+20", ColorField.GAMMA.format(20))
        assertEquals("", ColorField.GAMMA.unit)
    }

    @Test
    fun `the seven shipped presets are these seven, and every one of them asks for something`() {
        val builtIns = ColorPreset.builtIns()
        assertEquals(
            listOf("Vibrant", "Warm", "Cool", "Night", "Protanopia", "Deuteranopia", "Tritanopia"),
            builtIns.map { it.name },
        )
        builtIns.forEach { preset ->
            // §32 in the preset list: a shipped starting point that changed nothing would be a
            // row the user can tap to no effect.
            assertFalse(preset.name, preset.correction.changesNothing)
            assertTrue(preset.name, preset.correction.summary != "No change")
            // Already inside every range, so seeding cannot alter what the app ships.
            assertEquals(preset.name, preset, preset.normalised())
            assertTrue(preset.name, preset.name.length <= ColorPreset.MAX_NAME_LENGTH)
        }
    }

    @Test
    fun `no two shipped presets collide under the name match that reseeds them`() {
        // ColorPresetRepository.restoreMissingDefaults() decides what is missing by comparing
        // trimmed lowercase names. Two built-ins that matched there would mean one of them could
        // never come back after being deleted.
        val keys = ColorPreset.builtIns().map { it.name.trim().lowercase() }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(7, keys.size)
    }

    @Test
    fun `a preset name is trimmed, capped and never left blank`() {
        assertEquals("Night", ColorPreset(name = "  Night  ").normalised().name)
        assertEquals(ColorPreset.DEFAULT_NAME, ColorPreset(name = "   ").normalised().name)
        assertEquals(ColorPreset.DEFAULT_NAME, ColorPreset(name = "").normalised().name)
        val long = ColorPreset(name = "N".repeat(200)).normalised().name
        assertEquals(ColorPreset.MAX_NAME_LENGTH, long.length)

        // The values a preset carries are normalised with it, so a stored preset cannot smuggle
        // an out-of-range value past the screen that reads it.
        val wild = ColorPreset(name = "Wild", correction = ColorCorrection(redGain = 900))
        assertEquals(100, wild.normalised().correction.redGain)
    }
}
