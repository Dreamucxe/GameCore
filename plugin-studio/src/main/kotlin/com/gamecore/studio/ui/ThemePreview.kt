package com.gamecore.studio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gamecore.studio.core.ColorMath
import com.gamecore.studio.model.ThemeDraft
import com.gamecore.studio.model.GlassDraft

/**
 * A miniature GameCore screen drawn with a draft theme.
 *
 * The point is not to reproduce every host screen — a theme can never prove what an app it has
 * not seen will look like — but to show the relationships the theme actually controls: the page,
 * the cards on it, the ink on both, the accent, the corner radius and the type ramp. A theme that
 * looks wrong here will look wrong on the phone for the same reasons.
 *
 * The controls in [DevicePreviewStudio] are a *simulator*: tapping them changes the preview and
 * nothing else. The note above the device says so, because a preview that pretends to be the
 * running app is worse than one that admits it is a drawing.
 */
@Composable
internal fun ThemePreview(
    theme: ThemeDraft,
    modifier: Modifier = Modifier,
    fontScale: Float = 1f,
    interactive: Boolean = true,
    onOverflow: (Boolean) -> Unit = {},
) {
    val page = theme.background.toThemeColor()
    val surface = theme.surface.toThemeColor()
    val variant = theme.surfaceVariant.toThemeColor()
    val primary = theme.primary.toThemeColor()
    val accent = theme.accent.toThemeColor()
    val ink = theme.onSurface.toThemeColor()
    val muted = theme.onSurfaceVariant.toThemeColor()
    val shape = RoundedCornerShape(theme.cornerRadiusDp.dp)
    val family = theme.fontThemeFamily()
    var tab by remember(theme) { mutableStateOf(0) }
    var toggle by remember(theme) { mutableStateOf(true) }
    var amount by remember(theme) { mutableStateOf(0.6f) }
    var expanded by remember(theme) { mutableStateOf(false) }
    var dialogOpen by remember(theme) { mutableStateOf(false) }
    var titleOverflow by remember(theme) { mutableStateOf(false) }
    val density = LocalDensity.current
    // The type-ramp slider is a real theme field, so it multiplies the simulator's own font
    // scale. Without this the control would move a number the preview never used.
    val previewFontScale = fontScale * theme.typographyScale
    val previewScheme = theme.previewColorScheme()
    val previewTypography = MaterialTheme.typography.previewWeight(theme.typographyWeight)

    CompositionLocalProvider(LocalDensity provides Density(density.density, previewFontScale)) {
    MaterialTheme(colorScheme = previewScheme, typography = previewTypography) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(page, RoundedCornerShape((theme.cornerRadiusDp + 8).dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // A status bar and a page title, the way the host draws a screen.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(accent, CircleShape),
            )
            Text(
                text = theme.name.ifBlank { "Theme" },
                modifier = Modifier
                    .padding(start = 8.dp)
                    .weight(1f),
                color = ink,
                fontFamily = family,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
                onTextLayout = {
                    titleOverflow = it.hasVisualOverflow
                    onOverflow(it.hasVisualOverflow)
                },
            )
            Text(
                text = if (theme.dark) "Dark" else "Light",
                color = muted,
                fontFamily = family,
                style = MaterialTheme.typography.labelSmall,
            )
        }

        // A hero card: the primary colour, the primary ink, and a glass rim if the theme asks
        // for one. Glass is drawn as translucency plus a rim rather than a real backdrop blur,
        // which the preview cannot honestly simulate on every device.
        val glass = theme.glass
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = shape,
            color = if (glass != null) {
                primary.copy(alpha = (1f - glass.transparency * 0.55f).coerceIn(0.45f, 1f))
            } else {
                primary
            },
            tonalElevation = 0.dp,
            border = if (glass != null) {
                androidx.compose.foundation.BorderStroke(
                    1.dp,
                    theme.onPrimary.toThemeColor().copy(alpha = 0.35f),
                )
            } else {
                null
            },
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "A plugin theme",
                    color = theme.onPrimary.toThemeColor(),
                    fontFamily = family,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = "Colours, shape and type, applied in one package.",
                    modifier = Modifier.padding(top = 4.dp),
                    color = theme.onPrimary.toThemeColor().copy(alpha = 0.82f),
                    fontFamily = family,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (glass != null) {
                    Text(
                        text = "Glass ${(glass.intensity * 100).toInt()}% · ${glass.blurRadiusDp} dp blur",
                        modifier = Modifier.padding(top = 6.dp),
                        color = theme.onPrimary.toThemeColor().copy(alpha = 0.7f),
                        fontFamily = family,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        // A tab row, a card with a switch and a slider, and an expandable row: the three
        // component shapes that show the roles and the corner radius most clearly.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Home", "Stats", "More").forEachIndexed { index, label ->
                val selected = tab == index
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(enabled = interactive) { tab = index },
                    shape = RoundedCornerShape(theme.cornerRadiusDp.dp / 2),
                    color = if (selected) accent else variant,
                ) {
                    Text(
                        text = label,
                        modifier = Modifier.padding(vertical = 7.dp),
                        color = if (selected) theme.background.toThemeColor() else muted,
                        fontFamily = family,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = interactive) { expanded = !expanded },
            shape = shape,
            color = surface,
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Performance",
                            color = ink,
                            fontFamily = family,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = if (expanded) "Tap to collapse" else "Tap to expand",
                            color = muted,
                            fontFamily = family,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Switch(
                        checked = toggle,
                        onCheckedChange = { if (interactive) toggle = it },
                        enabled = interactive,
                    )
                }
                if (expanded) {
                    Text(
                        text = "A card inside the page, using the surface role and the theme's corner.",
                        modifier = Modifier.padding(top = 8.dp),
                        color = muted,
                        fontFamily = family,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Slider(
                        value = amount,
                        onValueChange = { if (interactive) amount = it },
                        enabled = interactive,
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { if (interactive) dialogOpen = true },
                enabled = interactive,
                shape = RoundedCornerShape(theme.cornerRadiusDp.dp / 2),
            ) {
                Text("Open dialog", fontFamily = family)
            }
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .clickable(enabled = interactive) { tab = (tab + 1) % 3 },
                shape = RoundedCornerShape(theme.cornerRadiusDp.dp / 2),
                color = variant,
            ) {
                Text(
                    text = "Next tab",
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = ink,
                    fontFamily = family,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        // A warning strip in the theme's error role, so the destructive colour is visible.
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = shape,
            color = theme.error.toThemeColor(),
        ) {
            Text(
                text = "This is the error role — not a real warning.",
                modifier = Modifier.padding(10.dp),
                color = theme.onError.toThemeColor(),
                fontFamily = family,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (titleOverflow) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = theme.error.toThemeColor(),
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "The title ellipsised at this width.",
                    modifier = Modifier.padding(start = 6.dp),
                    color = theme.error.toThemeColor(),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        if (dialogOpen) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = shape,
                color = variant,
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Simulated dialog",
                            modifier = Modifier.weight(1f),
                            color = ink,
                            fontFamily = family,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        IconButton(onClick = { dialogOpen = false }) {
                            Icon(Icons.Filled.Close, contentDescription = "Close the sample dialog", tint = muted)
                        }
                    }
                    Text(
                        text = "Dialogs use the surface variant and the same corner as cards.",
                        color = muted,
                        fontFamily = family,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        if (theme.glitch != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(accent.copy(alpha = 0.18f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = accent, modifier = Modifier.size(14.dp))
                Text(
                    text = "Glitch motion exported — the host previews the bursts.",
                    modifier = Modifier.padding(start = 6.dp),
                    color = muted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
    }
    }
}

/**
 * The preview tab: a device frame, a size simulator and the live theme inside it.
 *
 * Every control here changes only what is drawn. None of it is written into the package, and the
 * note above the frame says so — the host decides how its real layout behaves at a given width.
 */
@Composable
internal fun DevicePreviewStudio(
    theme: ThemeDraft,
    modifier: Modifier = Modifier,
) {
    val devices = listOf(
        PreviewDevice("Compact", 320, 640),
        PreviewDevice("Phone", 360, 740),
        PreviewDevice("Large", 412, 820),
        PreviewDevice("Tablet", 600, 900),
    )
    var deviceIndex by remember { mutableStateOf(1) }
    var landscape by remember { mutableStateOf(false) }
    var fontScale by remember { mutableStateOf(1f) }
    var brightness by remember { mutableStateOf(1f) }
    var overflow by remember { mutableStateOf(false) }
    val device = devices[deviceIndex]

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Device simulator",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "This frame is a drawing, not a running GameCore. It shows how the theme's width, " +
                "type scale and corners behave at different sizes; it cannot promise the host's " +
                "real layout at every density.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            devices.forEachIndexed { index, item ->
                FilterChip(
                    selected = index == deviceIndex,
                    onClick = { deviceIndex = index },
                    label = { Text(item.name, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = landscape,
                onClick = { landscape = !landscape },
                label = { Text(if (landscape) "Landscape" else "Portrait") },
            )
            FilterChip(
                selected = fontScale != 1f,
                onClick = { fontScale = if (fontScale == 1f) 1.3f else 1f },
                label = { Text(if (fontScale == 1f) "Font 100%" else "Font 130%") },
            )
        }
        Text("Preview brightness", style = MaterialTheme.typography.labelMedium)
        Slider(value = brightness, onValueChange = { brightness = it }, valueRange = 0.35f..1f)
        Text("Preview font scale ${(fontScale * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = fontScale,
            onValueChange = { fontScale = it },
            valueRange = 0.8f..1.4f,
        )

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(520.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            val chosenWidth = if (landscape) device.height else device.width
            val ratio = if (landscape) 16f / 9f else 9f / 19f
            // Fit both axes: the chosen device width, the column width, and the 520dp stage height.
            val frameWidth = minOf(chosenWidth.dp, maxWidth, maxHeight * ratio)
            Column(
                modifier = Modifier
                    .widthIn(max = maxWidth)
                    .width(frameWidth)
                    .aspectRatio(ratio)
                    .background(Color(0xFF0B0B0E), RoundedCornerShape(24.dp))
                    .border(2.dp, Color(0xFF35353D), RoundedCornerShape(24.dp))
                    .padding(8.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                // A status bar, then the page. The bars are chrome, not part of the theme.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("9:41", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.weight(1f))
                    Text("▮▮▮ 100%", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                }
                Box(modifier = Modifier.alpha(brightness)) {
                    ThemePreview(
                        theme = theme,
                        fontScale = fontScale,
                        interactive = true,
                        onOverflow = { overflow = it },
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .width(80.dp)
                            .height(3.dp)
                            .background(Color.White.copy(alpha = 0.45f), CircleShape),
                    )
                }
            }
        }

        if (overflow) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    "The preview title overflowed at this width and scale. Shorten the theme name or " +
                        "reduce the type scale.",
                    modifier = Modifier.padding(start = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Text(
            "The buttons, switch, slider and dialog on the frame are simulated interactions. They " +
                "test the preview; they do not run plugin code.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The draft as the Material roles the preview's own controls read.
 *
 * The studio's chrome uses [PluginStudioTheme] so the editor never disappears into the theme
 * being designed. Inside the device frame the roles come from the draft: a primary-coloured
 * button, a switch in the secondary role and a slider in the accent colour are what the host
 * will draw, and showing them in the studio's purple instead would make the preview a lie.
 */
private fun ThemeDraft.previewColorScheme(): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val page = background.toThemeColor()
    val card = surface.toThemeColor()
    val ink = onSurface.toThemeColor()
    return base.copy(
        primary = primary.toThemeColor(),
        onPrimary = onPrimary.toThemeColor(),
        secondary = secondary.toThemeColor(),
        onSecondary = ColorMath.readableOn(secondary).toThemeColor(),
        tertiary = tertiary.toThemeColor(),
        onTertiary = ColorMath.readableOn(tertiary).toThemeColor(),
        background = page,
        onBackground = ink,
        surface = card,
        onSurface = ink,
        surfaceVariant = surfaceVariant.toThemeColor(),
        onSurfaceVariant = onSurfaceVariant.toThemeColor(),
        outline = outline.toThemeColor(),
        error = error.toThemeColor(),
        onError = onError.toThemeColor(),
    )
}

/** The global weight field, applied to every Material type style in the preview. */
private fun Typography.previewWeight(weight: String): Typography {
    val resolved = when (weight) {
        "light" -> FontWeight.Light
        "bold" -> FontWeight.Bold
        "medium" -> FontWeight.Medium
        else -> FontWeight.Normal
    }
    return copy(
        displayLarge = displayLarge.copy(fontWeight = resolved),
        displayMedium = displayMedium.copy(fontWeight = resolved),
        displaySmall = displaySmall.copy(fontWeight = resolved),
        headlineLarge = headlineLarge.copy(fontWeight = resolved),
        headlineMedium = headlineMedium.copy(fontWeight = resolved),
        headlineSmall = headlineSmall.copy(fontWeight = resolved),
        titleLarge = titleLarge.copy(fontWeight = resolved),
        titleMedium = titleMedium.copy(fontWeight = resolved),
        titleSmall = titleSmall.copy(fontWeight = resolved),
        bodyLarge = bodyLarge.copy(fontWeight = resolved),
        bodyMedium = bodyMedium.copy(fontWeight = resolved),
        bodySmall = bodySmall.copy(fontWeight = resolved),
        labelLarge = labelLarge.copy(fontWeight = resolved),
        labelMedium = labelMedium.copy(fontWeight = resolved),
        labelSmall = labelSmall.copy(fontWeight = resolved),
    )
}

private data class PreviewDevice(val name: String, val width: Int, val height: Int)

/** The theme colour as Compose sees it, alpha included. */
internal fun Long.toThemeColor(): Color = Color((this and 0xFFFFFFFFL).toInt())

/**
 * The closest platform family to the theme's bundled face.
 *
 * The actual open-source fonts live in GameCore's assets, not in this library, so the preview
 * uses a platform family with the same proportions and says nothing about it. The installed app
 * draws the bundled face from its own assets.
 */
internal fun ThemeDraft.fontThemeFamily(): FontFamily = when (font) {
    ThemeDraft.FONT_MONO, "jetbrains-mono", "vt323", "press-start-2p" -> FontFamily.Monospace
    ThemeDraft.FONT_SERIF, "cinzel" -> FontFamily.Serif
    ThemeDraft.FONT_CURSIVE -> FontFamily.Cursive
    else -> FontFamily.Default
}

/** A short description of the glass block, for the export inspector. */
internal fun GlassDraft.summary(): String =
    "${(intensity * 100).toInt()}% intensity, ${(transparency * 100).toInt()}% transparency, " +
        "${blurRadiusDp}dp blur, ${spring} spring"
