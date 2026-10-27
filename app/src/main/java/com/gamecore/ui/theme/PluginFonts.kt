package com.gamecore.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.gamecore.R
import com.gamecore.core.model.AppSettings
import com.gamecore.core.model.PluginSectionFont
import com.gamecore.core.plugin.theme.PluginTheme

/**
 * The real [FontFamily] values behind the font names the app offers.
 *
 * Kept in one Android-aware file so [PluginThemeBridge] can stay a pure mapping of validated theme
 * values to Compose values, and so the plugin section's own font choice and a plugin's theme font
 * cannot drift apart. The only bundled face is [minecraft]; every other choice is a platform family
 * the device already has, which means an OEM may render it differently and the UI says "System"
 * rather than promising a specific typeface.
 */
internal object GameCoreFontFamilies {

    /**
     * A bundled pixel face inspired by Minecraft, licensed under the SIL Open Font License.
     *
     * The licence text ships in `assets/licenses/monocraft-OFL.txt`. The resource is loaded by
     * Compose lazily, so constructing this object does not read the font file.
     */
    val minecraft: FontFamily = FontFamily(Font(R.font.monocraft))

    /** The family a plugin theme names, or the platform default for "system". */
    fun of(font: PluginTheme.Font): FontFamily = when (font) {
        PluginTheme.Font.SYSTEM -> FontFamily.Default
        PluginTheme.Font.ROUNDED -> FontFamily.SansSerif
        PluginTheme.Font.MONO -> FontFamily.Monospace
        PluginTheme.Font.SERIF -> FontFamily.Serif
        PluginTheme.Font.CURSIVE -> FontFamily.Cursive
        PluginTheme.Font.MINECRAFT -> minecraft
    }

    /**
     * The family the plugin section's own setting names, or null to keep the current theme's face.
     *
     * Null is not the same as [FontFamily.Default]: the former means "the theme is already wearing
     * the right face", while the latter is an explicit request to use the device's default family.
     */
    fun of(font: PluginSectionFont): FontFamily? = when (font) {
        PluginSectionFont.THEME -> null
        PluginSectionFont.SYSTEM -> FontFamily.Default
        PluginSectionFont.ROUNDED -> FontFamily.SansSerif
        PluginSectionFont.MONO -> FontFamily.Monospace
        PluginSectionFont.SERIF -> FontFamily.Serif
        PluginSectionFont.CURSIVE -> FontFamily.Cursive
        PluginSectionFont.MINECRAFT -> minecraft
    }
}

/**
 * Applies the plugin section's own font and text-size choice inside the current theme.
 *
 * This is deliberately a nested `MaterialTheme`, not another `GameCoreTheme`: colours, shapes,
 * elevation and the plugin theme's own density stay exactly as the shell resolved them, while the
 * section gets the face and type scale the user chose. [PluginSectionFont.THEME] with 100% returns
 * the existing typography instance unchanged, so the default costs no copy and cannot drift from a
 * theme that later gains a font.
 */
@Composable
fun PluginSectionTheme(
    font: PluginSectionFont,
    fontScalePercent: Int,
    content: @Composable () -> Unit,
) {
    val base = MaterialTheme.typography
    val family = GameCoreFontFamilies.of(font)
    val scale = fontScalePercent.coerceIn(
        AppSettings.MIN_PLUGIN_SECTION_FONT_SCALE,
        AppSettings.MAX_PLUGIN_SECTION_FONT_SCALE,
    ) / 100f
    val typography = remember(base, family, scale) {
        if (family == null && scale == 1f) base else base.withPluginSectionType(family, scale)
    }
    MaterialTheme(typography = typography, content = content)
}
