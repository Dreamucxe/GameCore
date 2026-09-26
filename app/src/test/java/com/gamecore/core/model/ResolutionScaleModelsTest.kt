package com.gamecore.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The resolution-scale presets (spec §B2). The property that separates these from [AspectPreset] is that they
 * PRESERVE the native aspect ratio — both axes scale by the same factor — while still landing on even pixel
 * dimensions. So the tests pin three things: the concrete pixel sizes on real panels, that the aspect ratio is
 * unchanged, and that both dimensions are always even even when the native size does not divide cleanly.
 */
class ResolutionScaleModelsTest {

    private val portrait = DisplaySize(1080, 2400)
    private val landscape = DisplaySize(2400, 1080)

    // ---- concrete sizes ----

    @Test
    fun `full is the native size unchanged`() {
        assertEquals(portrait, ResolutionScale.FULL.sizeFor(portrait))
    }

    @Test
    fun `high scales both dimensions to eighty percent`() {
        assertEquals(DisplaySize(864, 1920), ResolutionScale.HIGH.sizeFor(portrait))
    }

    @Test
    fun `medium scales both dimensions to sixty percent`() {
        assertEquals(DisplaySize(648, 1440), ResolutionScale.MEDIUM.sizeFor(portrait))
    }

    @Test
    fun `scaling is orientation-agnostic, both axes shrink the same way`() {
        assertEquals(DisplaySize(1920, 864), ResolutionScale.HIGH.sizeFor(landscape))
    }

    // ---- aspect preserved, dimensions even ----

    @Test
    fun `the scaled aspect ratio equals the native one`() {
        val native = DisplaySize(1440, 3200)
        assertEquals(native.aspect, ResolutionScale.HIGH.sizeFor(native).aspect, 0.0001f)
        assertEquals(native.aspect, ResolutionScale.MEDIUM.sizeFor(native).aspect, 0.0001f)
    }

    @Test
    fun `both dimensions are even even when the native size does not divide cleanly`() {
        // 2532 * 0.8 = 2025.6 and 2532 * 0.6 = 1519.2 — neither lands on an even number on its own.
        val awkward = DisplaySize(1170, 2532)
        for (scale in listOf(ResolutionScale.HIGH, ResolutionScale.MEDIUM)) {
            val size = scale.sizeFor(awkward)
            assertTrue("$scale width ${size.widthPixels} must be even", size.widthPixels % 2 == 0)
            assertTrue("$scale height ${size.heightPixels} must be even", size.heightPixels % 2 == 0)
        }
    }

    // ---- options offered / identified ----

    @Test
    fun `all three presets are offered on a roomy panel, in enum order`() {
        val options = ResolutionScale.optionsFor(portrait)
        assertEquals(listOf(ResolutionScale.FULL, ResolutionScale.HIGH, ResolutionScale.MEDIUM), options.map { it.scale })
        assertEquals(DisplaySize(648, 1440), options.last().size)
    }

    @Test
    fun `a preset that would fall below the usable minimum is dropped`() {
        // 520 short side: 80% = 416 (fine), 60% = 312 which is under DisplaySize.MIN_SIDE (320), so MEDIUM goes.
        val narrow = DisplaySize(520, 1140)
        assertEquals(listOf(ResolutionScale.FULL, ResolutionScale.HIGH), ResolutionScale.optionsFor(narrow).map { it.scale })
    }

    @Test
    fun `of maps a size back to its preset, native to full, and a custom size to null`() {
        assertEquals(ResolutionScale.FULL, ResolutionScale.of(portrait, portrait))
        assertEquals(ResolutionScale.MEDIUM, ResolutionScale.of(DisplaySize(648, 1440), portrait))
        assertNull(ResolutionScale.of(DisplaySize(900, 2000), portrait))
    }

    @Test
    fun `a choice reports the preset label and the concrete pixel size`() {
        val medium = ResolutionScale.optionsFor(portrait).first { it.scale == ResolutionScale.MEDIUM }
        assertEquals("60%", medium.label)
        assertEquals("648 × 1440", medium.detail)
    }
}
