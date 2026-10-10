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
}
