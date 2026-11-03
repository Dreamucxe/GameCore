package com.gamecore.studio.core

import com.gamecore.studio.model.GlassDraft
import com.gamecore.studio.model.GlitchDraft
import com.gamecore.studio.model.PluginDraft
import com.gamecore.studio.model.ThemeDraft
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The studio's own storage form is private, but it still has to be lossless.
 *
 * A draft that comes back from [DraftCodec] must be the same draft the editor had, including
 * the optional material and motion blocks and all thirteen colours. Malformed storage is a
 * `null`, never an exception, so one damaged project cannot stop the list from opening.
 */
class DraftCodecTest {

    private val draft = PluginDraft(
        id = "com.example.roundtrip",
        name = "Round Trip",
        version = 9,
        versionName = "9.0.1",
        developer = "Quote \" and backslash \\",
        description = "First line\nSecond line\twith a tab.",
        changelog = "9.0.1 — kept every field.",
        api = PluginDraft.CURRENT_API,
        minHostVersion = 73,
        permissions = setOf("change_ui", "add_pill_modules", "system_actions"),
        theme = ThemeDraft(
            name = "Round Trip",
            schema = ThemeDraft.CURRENT_SCHEMA,
            dark = false,
            amoled = true,
            primary = 0x80336699L,
            onPrimary = 0xFF112233L,
            background = 0xFF08090AL,
            surface = 0xFF202122L,
            onSurface = 0xFFF4F5F6L,
            accent = 0xFF55DD99L,
            secondary = 0xFF667788L,
            tertiary = 0xFF8899AAL,
            surfaceVariant = 0xFF313233L,
            onSurfaceVariant = 0xFFD4D5D6L,
            outline = 0xFF515253L,
            error = 0xFFDD3344L,
            onError = 0xFF220011L,
            cornerRadiusDp = 31,
            uiScale = 0.93f,
            font = "jetbrains-mono",
            typographyWeight = "light",
            typographyScale = 1.21f,
            glass = GlassDraft(
                intensity = 0.57f,
                transparency = 0.68f,
                gloss = 0.79f,
                blurRadiusDp = 24,
                sheen = true,
                sheenPeriodMs = 7200,
                spring = "bouncy",
                hardwareReactive = false,
            ),
            glitch = GlitchDraft(
                intensity = 0.84f,
                slices = 6,
                sliceOffsetDp = 42,
                ghostOffsetDp = 9,
                fractureLines = true,
                noise = false,
                microJitter = true,
                hardwareReactive = true,
                burstMinMs = 500,
                burstMaxMs = 3100,
            ),
        ),
    )

    @Test
    fun `every field survives a round trip including the effect blocks`() {
        val decoded = DraftCodec.decode(DraftCodec.encode(draft))

        assertNotNull(decoded)
        assertEquals(draft, decoded)
        assertEquals(13, ThemeDraft.COLOR_FIELDS.size)
        assertEquals(0x80336699L, decoded?.theme?.primary)
        assertEquals(0.57f, decoded?.theme?.glass?.intensity)
        assertEquals(3100, decoded?.theme?.glitch?.burstMaxMs)
    }

    @Test
    fun `a draft with no optional blocks round trips too`() {
        val plain = PluginDraft()
        assertEquals(plain, DraftCodec.decode(DraftCodec.encode(plain)))
    }

    @Test
    fun `malformed storage decodes to null`() {
        listOf("", "{", "not json", "[]", "\"a string\"", "null").forEach { text ->
            assertNull("decode(\"$text\") was not null", DraftCodec.decode(text))
        }
    }

    @Test
    fun `unknown keys are ignored on decode`() {
        val root = JSONObject(DraftCodec.encode(draft))
            .put("writtenByANewerStudio", 3)
            .put("unused", true)
        val theme = root.getJSONObject("theme")
            .put("futureColor", "#123456")
        theme.getJSONObject("glass").put("futureGlassKey", 1)
        theme.getJSONObject("glitch").put("futureGlitchKey", 2)

        assertEquals(draft, DraftCodec.decode(root.toString()))
    }
}
