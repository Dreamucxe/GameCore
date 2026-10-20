package com.gamecore.themeengine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The on-disk form of a save point.
 *
 * A preference file is the one input to this app that a half-finished write, an older build or a user
 * with a text editor can all produce, so the important cases here are the bad ones: a malformed entry
 * must not cost the user the rest of the file, a duplicated id must not produce two chips that select
 * the same thing, and an out-of-range velocity must never reach the renderer.
 */
class AuroraPresetCodecTest {

    private val balanced = AuroraDefaults.preset()

    @Test
    fun `a saved preset round-trips unchanged`() {
        val saved = balanced.copy(
            id = "user-1",
            name = "Hot chip",
            vectorMode = ColorVectorMode.CUSTOM,
            fluidVelocity = 2.25f,
            cpuThreshold = 42f,
            ramThreshold = 61f,
            tempThreshold = 77f,
            customColorRgb = Triple(12, 203, 144),
        )

        val decoded = AuroraPresetCodec.decode(AuroraPresetCodec.encode(listOf(saved)))

        assertEquals(1, decoded.size)
        assertEquals(saved.normalised(), decoded.single())
    }

    @Test
    fun `a malformed entry is dropped and the rest survive`() {
        val raw = JSONObject()
            .put("presets", JSONArray().apply {
                put(
                    JSONObject()
                        .put("id", "good")
                        .put("name", "Kept")
                        .put("mode", "ICE_GLACIER")
                        .put("velocity", 1.0)
                        .put("cpu", 55.0)
                        .put("ram", 70.0)
                        .put("temp", 90.0),
                )
                put("not an object")
                // No mode at all: nothing can say what this preset would paint.
                put(JSONObject().put("id", "broken").put("name", "Broken"))
            })
            .toString()

        val decoded = AuroraPresetCodec.decode(raw)

        assertEquals(listOf("good"), decoded.map { it.id })
        assertEquals("Kept", decoded.single().name)
    }

    @Test
    fun `out-of-range values are clamped on the way in`() {
        val raw = JSONObject()
            .put("presets", JSONArray().apply {
                put(
                    JSONObject()
                        .put("id", "wild")
                        .put("name", "   ")
                        .put("mode", "AURORA_VIOLET")
                        .put("velocity", 99.0)
                        .put("cpu", -20.0)
                        .put("ram", 400.0)
                        .put("temp", 900.0),
                )
            })
            .toString()

        val preset = AuroraPresetCodec.decode(raw).single()

        assertEquals(AuroraBounds.MAX_VELOCITY, preset.fluidVelocity, 0.0001f)
        assertEquals(0f, preset.cpuThreshold, 0.0001f)
        assertEquals(AuroraBounds.MAX_RAM_THRESHOLD, preset.ramThreshold, 0.0001f)
        assertEquals(AuroraBounds.MAX_TEMP_THRESHOLD, preset.tempThreshold, 0.0001f)
        assertEquals(AuroraBounds.DEFAULT_PRESET_NAME, preset.name)
    }

    @Test
    fun `a duplicated id collapses to the first entry`() {
        val first = balanced.copy(id = "same", name = "First")
        val second = balanced.copy(id = "same", name = "Second")

        val decoded = AuroraPresetCodec.decode(AuroraPresetCodec.encode(listOf(first, second)))

        assertEquals(1, decoded.size)
        assertEquals("First", decoded.single().name)
    }

    @Test
    fun `an unreadable file decodes to nothing rather than throwing`() {
        assertTrue(AuroraPresetCodec.decode("{not json").isEmpty())
        assertTrue(AuroraPresetCodec.decode(null).isEmpty())
        assertTrue(AuroraPresetCodec.decode("").isEmpty())
        assertTrue(AuroraPresetCodec.decode("{}").isEmpty())
    }

    @Test
    fun `a custom colour survives the round trip`() {
        val saved = balanced.copy(
            id = "user-colour",
            vectorMode = ColorVectorMode.CUSTOM,
            customColorRgb = Triple(1, 2, 3),
        )

        val decoded = AuroraPresetCodec.decode(AuroraPresetCodec.encode(listOf(saved))).single()

        assertEquals(Triple(1, 2, 3), decoded.customColorRgb)
    }

    @Test
    fun `a preset without a custom colour decodes with none`() {
        val decoded = AuroraPresetCodec.decode(AuroraPresetCodec.encode(listOf(balanced))).single()

        assertNull(decoded.customColorRgb)
    }

    @Test
    fun `encoding keeps at most the documented number of presets`() {
        val many = (0 until AuroraBounds.MAX_PRESETS + 8).map { index ->
            balanced.copy(id = "user-$index", name = "Preset $index")
        }

        val decoded = AuroraPresetCodec.decode(AuroraPresetCodec.encode(many))

        assertEquals(AuroraBounds.MAX_PRESETS, decoded.size)
    }
}
