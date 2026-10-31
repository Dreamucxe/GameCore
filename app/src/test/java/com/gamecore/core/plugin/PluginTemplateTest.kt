package com.gamecore.core.plugin

import com.gamecore.core.plugin.theme.PluginThemeCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The template that ships in `plugin-template/` is a package GameCore actually accepts.
 *
 * A starter template that no build accepts is worse than no template: the first thing a third-party
 * author does is edit two lines and install it, and a rejection at that point is indistinguishable
 * from the whole plugin platform being broken. This reads the real files through the real codecs, so
 * a format change that breaks the template fails here rather than in someone else's first attempt.
 */
class PluginTemplateTest {

    private val folder = File("../plugin-template")

    @Test
    fun `template manifest is accepted`() {
        val raw = File(folder, "manifest.json").readText(Charsets.UTF_8)
        val result = PluginManifestCodec.decode(raw)
        assertTrue("template manifest was rejected: $result", result is PluginManifestCodec.Result.Parsed)
        val manifest = (result as PluginManifestCodec.Result.Parsed).manifest
        assertTrue("template must declare a theme", PluginSection.THEME in manifest.declares)
        assertEquals(Compatibility.Supported, manifest.compatibility)
    }

    @Test
    fun `template theme is accepted`() {
        val raw = File(folder, "theme.json").readText(Charsets.UTF_8)
        val result = PluginThemeCodec.decode(raw)
        assertTrue("template theme was rejected: $result", result is PluginThemeCodec.Result.Parsed)
    }
}
