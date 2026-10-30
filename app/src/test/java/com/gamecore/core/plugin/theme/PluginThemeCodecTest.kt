package com.gamecore.core.plugin.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Part F/M gate: the plugin theme codec — strict colours, clamped numbers, bundled fonts. */
class PluginThemeCodecTest {

    private val minimal = """
        {"name":"Minimal","dark":true,
         "primary":"#4F8CFF","onPrimary":"#FFFFFF","background":"#000000",
         "surface":"#101216","onSurface":"#E6E8EC","accent":"#4F8CFF",
         "cornerRadiusDp":12,"font":"system","uiScale":1.0}
    """.trimIndent()

    private val v2 = """
        {"schema":2,"name":"Night2","dark":true,
         "primary":"#4F8CFF","onPrimary":"#FFFFFF","background":"#000000",
         "surface":"#101216","onSurface":"#E6E8EC","accent":"#4F8CFF",
         "cornerRadiusDp":12,"font":"system","uiScale":1.0,
         "amoled":true,
         "roles":{"secondary":"#AA00FF","error":"#B00020","onError":"#FFFFFF"},
         "typography":{"scale":1.2,"weight":"bold"}}
    """.trimIndent()

    private val v3 = """
        {"schema":3,"name":"Torn","dark":true,
         "primary":"#00F0FF","onPrimary":"#000A0C","background":"#050505",
         "surface":"#0A0A0C","onSurface":"#E8FBFF","accent":"#FF0055",
         "cornerRadiusDp":2,"font":"mono","uiScale":1.0,
         "effects":{"glitch":{"intensity":0.85,"slices":3,"sliceOffsetDp":24,
           "ghostOffsetDp":6,"fractureLines":true,"noise":true,
           "microJitter":true,"hardwareReactive":true,
           "burstMinMs":150,"burstMaxMs":800}}}
    """.trimIndent()

    @Test
    fun `a well-formed theme parses`() {
        val r = PluginThemeCodec.decode(minimal) as PluginThemeCodec.Result.Parsed
        assertEquals("Minimal", r.theme.name)
        assertEquals(0xFF4F8CFF.toInt(), r.theme.primary)
        assertEquals(PluginTheme.Font.SYSTEM, r.theme.font)
    }

    @Test
    fun `a 6-digit colour is forced opaque`() {
        assertEquals(0xFFFF0000.toInt(), PluginThemeCodec.parseColor("#FF0000"))
    }

    @Test
    fun `an 8-digit colour keeps its alpha`() {
        assertEquals(0x80FF0000.toInt(), PluginThemeCodec.parseColor("#80FF0000"))
    }

    @Test
    fun `a non-hex colour is rejected, not defaulted`() {
        assertEquals(null, PluginThemeCodec.parseColor("red"))
        assertEquals(null, PluginThemeCodec.parseColor("#12"))
        val bad = minimal.replace("\"#4F8CFF\"", "\"not-a-color\"")
        assertTrue(PluginThemeCodec.decode(bad) is PluginThemeCodec.Result.Rejected)
    }

    @Test
    fun `out-of-range numbers are clamped`() {
        val big = minimal.replace("\"cornerRadiusDp\":12", "\"cornerRadiusDp\":999")
            .replace("\"uiScale\":1.0", "\"uiScale\":9.0")
        val r = PluginThemeCodec.decode(big) as PluginThemeCodec.Result.Parsed
        assertEquals(PluginTheme.MAX_CORNER_DP, r.theme.cornerRadiusDp)
        assertEquals(PluginTheme.MAX_UI_SCALE, r.theme.uiScale, 0.0001f)
    }

    @Test
    fun `the added font ids parse as their enum values`() {
        val cursive = PluginThemeCodec.decode(
            minimal.replace("\"font\":\"system\"", "\"font\":\"cursive\""),
        ) as PluginThemeCodec.Result.Parsed
        val minecraft = PluginThemeCodec.decode(
            minimal.replace("\"font\":\"system\"", "\"font\":\"minecraft\""),
        ) as PluginThemeCodec.Result.Parsed
        assertEquals(PluginTheme.Font.CURSIVE, cursive.theme.font)
        assertEquals(PluginTheme.Font.MINECRAFT, minecraft.theme.font)
    }

    @Test
    fun `the bundled Liquid Glass font ids parse as their enum values`() {
        val expected = mapOf(
            "cinzel" to PluginTheme.Font.CINZEL,
            "comic-neue" to PluginTheme.Font.COMIC_NEUE,
            "inter" to PluginTheme.Font.INTER,
            "jetbrains-mono" to PluginTheme.Font.JETBRAINS_MONO,
            "orbitron" to PluginTheme.Font.ORBITRON,
            "press-start-2p" to PluginTheme.Font.PRESS_START_2P,
            "rubik" to PluginTheme.Font.RUBIK,
            "vt323" to PluginTheme.Font.VT323,
        )
        for ((id, font) in expected) {
            val r = PluginThemeCodec.decode(
                minimal.replace("\"font\":\"system\"", "\"font\":\"$id\""),
            ) as PluginThemeCodec.Result.Parsed
            assertEquals(id, font, r.theme.font)
        }
    }

    @Test
    fun `the bundled faces print the name a person would use`() {
        assertEquals("Cinzel", PluginTheme.Font.CINZEL.displayName)
        assertEquals("Comic Neue", PluginTheme.Font.COMIC_NEUE.displayName)
        assertEquals("Inter", PluginTheme.Font.INTER.displayName)
        assertEquals("JetBrains Mono", PluginTheme.Font.JETBRAINS_MONO.displayName)
        assertEquals("Orbitron", PluginTheme.Font.ORBITRON.displayName)
        assertEquals("Press Start 2P", PluginTheme.Font.PRESS_START_2P.displayName)
        assertEquals("Rubik", PluginTheme.Font.RUBIK.displayName)
        assertEquals("VT323", PluginTheme.Font.VT323.displayName)
        // The platform families keep the plain names they have always had.
        assertEquals("System", PluginTheme.Font.SYSTEM.displayName)
        assertEquals("Minecraft", PluginTheme.Font.MINECRAFT.displayName)
    }

    @Test
    fun `an unknown font is rejected`() {
        val bad = minimal.replace("\"font\":\"system\"", "\"font\":\"comic-sans-from-url\"")
        val r = PluginThemeCodec.decode(bad) as PluginThemeCodec.Result.Rejected
        assertEquals(PluginThemeCodec.Reason.BAD_FONT, r.reason)
    }

    @Test
    fun `an unknown key is rejected`() {
        val bad = minimal.replaceFirst("{", "{\"css\":\"body{}\",")
        val r = PluginThemeCodec.decode(bad) as PluginThemeCodec.Result.Rejected
        assertEquals(PluginThemeCodec.Reason.UNKNOWN_KEY, r.reason)
    }

    @Test
    fun `a missing required colour is rejected`() {
        val bad = minimal.replace("\"accent\":\"#4F8CFF\",", "")
        val r = PluginThemeCodec.decode(bad) as PluginThemeCodec.Result.Rejected
        assertEquals(PluginThemeCodec.Reason.MISSING_FIELD, r.reason)
    }

    @Test
    fun `empty and malformed input are rejected`() {
        assertTrue(PluginThemeCodec.decode(null) is PluginThemeCodec.Result.Rejected)
        assertTrue(PluginThemeCodec.decode("{not json") is PluginThemeCodec.Result.Rejected)
    }

    @Test
    fun `a v2 theme parses its schema, amoled, roles and typography`() {
        val r = PluginThemeCodec.decode(v2) as PluginThemeCodec.Result.Parsed
        assertEquals(2, r.theme.schema)
        assertEquals(true, r.theme.amoled)
        val roles = r.theme.roles!!
        assertEquals(0xFFAA00FF.toInt(), roles.secondary)
        assertEquals(0xFFB00020.toInt(), roles.error)
        assertEquals(0xFFFFFFFF.toInt(), roles.onError)
        assertEquals(null, roles.tertiary)
        assertEquals(1.2f, r.theme.typography!!.scale, 0.0001f)
        assertEquals(PluginThemeTypography.Weight.BOLD, r.theme.typography!!.weight)
    }

    @Test
    fun `a theme with no schema is v1 with no v2 extras`() {
        val r = PluginThemeCodec.decode(minimal) as PluginThemeCodec.Result.Parsed
        assertEquals(PluginTheme.SCHEMA_V1, r.theme.schema)
        assertEquals(false, r.theme.amoled)
        assertEquals(null, r.theme.roles)
        assertEquals(null, r.theme.typography)
    }

    @Test
    fun `a v1 theme that uses a v2 key is rejected`() {
        val bad = minimal.replaceFirst("{", "{\"amoled\":true,")
        val r = PluginThemeCodec.decode(bad) as PluginThemeCodec.Result.Rejected
        assertEquals(PluginThemeCodec.Reason.UNKNOWN_KEY, r.reason)
    }

    @Test
    fun `a schema past the current one is rejected`() {
        // The current schema moves — it is 4 now — so the "past the current" value is computed
        // from it rather than hard-coded. A hard-coded 4 silently became a *current* schema and
        // this test stopped testing anything.
        val past = PluginTheme.SCHEMA_CURRENT + 1
        val r = PluginThemeCodec.decode(v2.replace("\"schema\":2", "\"schema\":$past")) as PluginThemeCodec.Result.Rejected
        assertEquals(PluginThemeCodec.Reason.BAD_SCHEMA, r.reason)
    }

    @Test
    fun `a schema below v1 is rejected`() {
        val r = PluginThemeCodec.decode(v2.replace("\"schema\":2", "\"schema\":0")) as PluginThemeCodec.Result.Rejected
        assertEquals(PluginThemeCodec.Reason.BAD_SCHEMA, r.reason)
    }

    @Test
    fun `an unknown role key is rejected`() {
        val bad = v2.replace("\"secondary\":\"#AA00FF\"", "\"secondary\":\"#AA00FF\",\"bogus\":\"#010101\"")
        val r = PluginThemeCodec.decode(bad) as PluginThemeCodec.Result.Rejected
        assertEquals(PluginThemeCodec.Reason.UNKNOWN_KEY, r.reason)
    }

    @Test
    fun `a malformed role colour is rejected`() {
        val r = PluginThemeCodec.decode(v2.replace("\"error\":\"#B00020\"", "\"error\":\"crimson\"")) as PluginThemeCodec.Result.Rejected
        assertEquals(PluginThemeCodec.Reason.BAD_COLOR, r.reason)
    }

    @Test
    fun `an unknown typography key is rejected`() {
        val r = PluginThemeCodec.decode(v2.replace("\"scale\":1.2", "\"scale\":1.2,\"italic\":true")) as PluginThemeCodec.Result.Rejected
        assertEquals(PluginThemeCodec.Reason.UNKNOWN_KEY, r.reason)
    }

    @Test
    fun `an unknown typography weight is rejected`() {
        val r = PluginThemeCodec.decode(v2.replace("\"weight\":\"bold\"", "\"weight\":\"heavy\"")) as PluginThemeCodec.Result.Rejected
        assertEquals(PluginThemeCodec.Reason.BAD_TYPOGRAPHY, r.reason)
    }

    @Test
    fun `a typography scale outside the bounds is clamped`() {
        val big = PluginThemeCodec.decode(v2.replace("\"scale\":1.2", "\"scale\":9.0")) as PluginThemeCodec.Result.Parsed
        assertEquals(PluginThemeTypography.MAX_SCALE, big.theme.typography!!.scale, 0.0001f)
        val small = PluginThemeCodec.decode(v2.replace("\"scale\":1.2", "\"scale\":0.1")) as PluginThemeCodec.Result.Parsed
        assertEquals(PluginThemeTypography.MIN_SCALE, small.theme.typography!!.scale, 0.0001f)
    }

    @Test
    fun `a v2 theme that sets no extras has null roles and typography`() {
        val bare = minimal.replaceFirst("{", "{\"schema\":2,")
        val r = PluginThemeCodec.decode(bare) as PluginThemeCodec.Result.Parsed
        assertEquals(2, r.theme.schema)
        assertEquals(null, r.theme.roles)
        assertEquals(null, r.theme.typography)
    }

    @Test
    fun `a v3 theme parses its glitch block`() {
        val r = PluginThemeCodec.decode(v3) as PluginThemeCodec.Result.Parsed
        assertEquals(3, r.theme.schema)
        val g = r.theme.glitch!!
        assertEquals(0.85f, g.intensity, 1e-6f)
        assertEquals(3, g.slices)
        assertEquals(24, g.sliceOffsetDp)
        assertEquals(6, g.ghostOffsetDp)
        assertEquals(150, g.burstMinMs)
        assertEquals(800, g.burstMaxMs)
        assertTrue(g.fractureLines)
        assertTrue(g.hardwareReactive)
    }

    @Test
    fun `a v3 theme with no effects block has no glitch`() {
        val bare = minimal.replaceFirst("{", "{\"schema\":3,")
        val r = PluginThemeCodec.decode(bare) as PluginThemeCodec.Result.Parsed
        assertEquals(3, r.theme.schema)
        assertEquals(null, r.theme.glitch)
    }

    @Test
    fun `effects at schema two is rejected, not ignored`() {
        // A schema-2 theme that reaches for motion must fail loudly: silently dropping it would ship
        // a theme whose author believes it tears when it does not.
        val r = PluginThemeCodec.decode(v3.replace("\"schema\":3", "\"schema\":2")) as PluginThemeCodec.Result.Rejected
        assertEquals(PluginThemeCodec.Reason.UNKNOWN_KEY, r.reason)
    }

    @Test
    fun `an unknown effects key is rejected`() {
        val bad = v3.replace("\"glitch\":{", "\"bloom\":{},\"glitch\":{")
        val r = PluginThemeCodec.decode(bad) as PluginThemeCodec.Result.Rejected
        assertEquals(PluginThemeCodec.Reason.UNKNOWN_KEY, r.reason)
    }

    @Test
    fun `an unknown glitch key is rejected`() {
        val bad = v3.replace("\"slices\":3", "\"slices\":3,\"chromaSplit\":true")
        val r = PluginThemeCodec.decode(bad) as PluginThemeCodec.Result.Rejected
        assertEquals(PluginThemeCodec.Reason.UNKNOWN_KEY, r.reason)
    }

    @Test
    fun `glitch numbers outside the bounds are clamped`() {
        val wild = v3.replace("\"intensity\":0.85", "\"intensity\":9.0")
            .replace("\"slices\":3", "\"slices\":99")
            .replace("\"sliceOffsetDp\":24", "\"sliceOffsetDp\":999")
            .replace("\"burstMinMs\":150", "\"burstMinMs\":99999")
            .replace("\"burstMaxMs\":800", "\"burstMaxMs\":99999")
        val r = PluginThemeCodec.decode(wild) as PluginThemeCodec.Result.Parsed
        val g = r.theme.glitch!!
        assertEquals(1f, g.intensity, 1e-6f)
        assertEquals(PluginGlitch.MAX_SLICES, g.slices)
        assertEquals(PluginGlitch.MAX_SLICE_OFFSET_DP, g.sliceOffsetDp)
        assertEquals(PluginGlitch.MAX_BURST_MS, g.burstMinMs)
        assertEquals(PluginGlitch.MAX_BURST_MS, g.burstMaxMs)
        assertTrue("min never exceeds max", g.burstMinMs <= g.burstMaxMs)
    }
}
