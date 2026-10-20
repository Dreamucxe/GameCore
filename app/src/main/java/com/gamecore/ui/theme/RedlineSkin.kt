package com.gamecore.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * REDLINE — a built-in RedMagic-inspired skin for GameCore, drawn in app code because a data-only
 * plugin cannot: the pinwheel mark and the hexagon are vectors, and the jagged edges are a shape the
 * package format has no field for. A plugin can carry this palette (see the `redmagic` theme plugin);
 * only the host can carry the look.
 *
 * Everything here is behind [com.gamecore.core.model.AppSettings.redlineSkin]. With the flag off, not
 * one byte of this file runs and GameCore is exactly itself. The skin is a dark skin by definition, so
 * the theme forces dark polarity when it is on — a light Redline would be a contradiction, not an
 * option.
 */

/** The signature red, the near-black page, and the cool dark card the telemetry tiles sit on. */
private val RedlineRed = Color(0xFFE8142D)
private val RedlineBackground = Color(0xFF08070A)
private val RedlineSurface = Color(0xFF161620)
private val RedlineSurfaceVariant = Color(0xFF23242F)

/** A faintly red-lifted outline, so a cut-corner card reads as etched rather than merely darker. */
private val RedlineOutline = Color(0xFF3A2630)

/**
 * The Redline colour scheme, built on the house [darkSchemeFor] so every Material role the app already
 * relies on is filled in correctly — only the five anchors change. Reusing that body rather than
 * hand-rolling a `darkColorScheme` is what keeps a container, an inverse surface or an error role from
 * silently reverting to a Material default the rest of the app never sees.
 */
fun redlineColorScheme(): ColorScheme = darkSchemeFor(
    primary = RedlineRed,
    background = RedlineBackground,
    surface = RedlineSurface,
    surfaceVariant = RedlineSurfaceVariant,
    outline = RedlineOutline,
)

/**
 * The jagged edges. Opposite corners are cut on the diagonal — tight on the small roles so a chip stays
 * legible, pronounced on `large`/`extraLarge` where the cards, the hero and the nav bar live, which is
 * where the RedMagic bevel actually reads. All four corners are cut on the mid sizes for the octagonal
 * button look.
 */
val RedlineShapes = Shapes(
    extraSmall = CutCornerShape(3.dp),
    small = CutCornerShape(5.dp),
    medium = CutCornerShape(8.dp),
    large = CutCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomStart = 4.dp, bottomEnd = 16.dp),
    extraLarge = CutCornerShape(topStart = 20.dp, topEnd = 6.dp, bottomStart = 6.dp, bottomEnd = 20.dp),
)

/**
 * The pinwheel mark: four swept blades, optionally inside a hexagon ring, drawn with [Canvas] rather
 * than shipped as a raster so it is crisp at every size and tints with the theme. [tint] defaults to
 * the scheme primary so the mark is always the Redline red against whatever it sits on.
 */
@Composable
fun RedlineMark(
    modifier: Modifier = Modifier,
    hex: Boolean = false,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    Canvas(modifier = modifier) {
        val s = size.minDimension
        val c = s / 2f
        if (hex) {
            val r = s * 0.46f
            val ring = Path()
            for (i in 0..5) {
                // Flat-top hexagon: vertices every 60°, first at -30° so the top edge is horizontal.
                val a = Math.toRadians((60.0 * i) - 30.0)
                val x = c + r * Math.cos(a).toFloat()
                val y = c + r * Math.sin(a).toFloat()
                if (i == 0) ring.moveTo(x, y) else ring.lineTo(x, y)
            }
            ring.close()
            drawPath(ring, color = tint.copy(alpha = 0.55f), style = Stroke(width = s * 0.03f))
        }
        // One blade, then rotated into four. Each blade is a thin swept triangle from the centre out to
        // the rim, offset off the spoke so the four together spin rather than form a plus.
        val blade = Path().apply {
            moveTo(c, c)
            lineTo(c * 0.86f, c * 0.16f)
            lineTo(c * 1.5f, c * 0.52f)
            close()
        }
        for (i in 0 until 4) {
            rotate(degrees = 90f * i, pivot = androidx.compose.ui.geometry.Offset(c, c)) {
                drawPath(blade, color = tint)
            }
        }
    }
}

/**
 * The REDLINE home banner: the identity row and the "YOUR GAME. YOUR RULES." hero band that replace the
 * plain `ScreenHeader` while the skin is on. Pure chrome — it reads [subtitle] for the one line that
 * actually reflects state and dispatches [onSystem] for the settings glyph, and draws nothing it cannot
 * stand behind.
 */
@Composable
fun RedlineBanner(
    subtitle: String?,
    onSystem: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val mono = FontFamily.Monospace
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            RedlineMark(modifier = Modifier.size(34.dp))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "REDLINE",
                    fontFamily = mono,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 3.sp,
                    fontSize = 18.sp,
                    color = scheme.onSurface,
                )
                Text(
                    "GAME SPACE / 01",
                    fontFamily = mono,
                    letterSpacing = 2.sp,
                    fontSize = 10.sp,
                    color = scheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.Filled.Tune,
                contentDescription = "System",
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp).clickable(onClick = onSystem),
            )
        }
        Spacer(Modifier.height(12.dp))
        Surface(
            shape = MaterialTheme.shapes.large,
            color = Color.Transparent,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightBand()
                    .redlineHeroBackground(scheme.primary, scheme.surface),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "ENGINEERED FOR PLAY",
                        fontFamily = mono,
                        letterSpacing = 2.sp,
                        fontSize = 10.sp,
                        color = scheme.primary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "YOUR GAME.",
                        fontWeight = FontWeight.Black,
                        fontSize = 26.sp,
                        color = scheme.onSurface,
                    )
                    Text(
                        "YOUR RULES.",
                        fontWeight = FontWeight.Black,
                        fontSize = 26.sp,
                        color = scheme.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        subtitle?.takeIf { it.isNotBlank() } ?: "Profiles. Telemetry. Total focus.",
                        fontSize = 13.sp,
                        color = scheme.onSurfaceVariant,
                    )
                }
                RedlineMark(
                    hex = true,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 18.dp)
                        .size(72.dp),
                )
            }
        }
    }
}

/** A fixed hero-band height, kept here so the banner's two call sites cannot drift. */
private fun Modifier.heightBand(): Modifier = this.height(150.dp)

/** The hero's diagonal red wash over the card surface — brightest at the top-left, where the mark is not. */
private fun Modifier.redlineHeroBackground(primary: Color, surface: Color): Modifier = this.then(
    Modifier.background(
        Brush.linearGradient(
            colors = listOf(
                primary.copy(alpha = 0.28f),
                surface,
                surface,
            ),
        ),
    ),
)

