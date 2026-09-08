package com.gamecore.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A hue strip and a saturation/value square, with a hex field for the colour someone already knows.
 *
 * Built from [androidx.compose.foundation.Canvas], gradients and one text field rather than pulled in as a
 * dependency, because Material 3 1.3.0 ships no colour picker and a library for one would be a whole
 * artifact to obtain three gradients and some arithmetic.
 *
 * HSV as the two draggable surfaces and hex as the typed one, which is the split that matches how the two
 * are used. Nobody arrives wanting "hue 195, saturation 100" — they arrive wanting *a bit more green than
 * that*, which is a drag on a square. But people do arrive with `#00E5FF` written down, from a team kit or
 * a screenshot, and no amount of dragging finds an exact value. There are no R, G and B sliders because
 * they would be a third surface editing the same three numbers, and three surfaces that disagree while a
 * finger is down is a bug with nowhere to live.
 *
 * The alpha byte is not editable and is forced opaque by [hsvToArgb] and [parseHexColour] both. Whatever
 * this colour ends up on has its own opacity control, and a colour that carried transparency too would
 * give one visible property two owners — see [com.gamecore.core.model.CustomCrosshairColours].
 *
 * The picker holds the working colour itself rather than reporting every drag to the caller, and hands it
 * over on [onPick]. That is what makes a drag across the square cost nothing: the alternative writes a
 * database row and a preferences entry for every pixel the finger crosses.
 */
@Composable
fun ColourPicker(
    initialArgb: Int,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Seeded from the colour that was already chosen, so opening the picker starts where the user is
    // rather than at red — and `remember(initialArgb)` rather than `remember`, so re-opening it after
    // tapping a swatch starts from that swatch instead of from wherever the last drag ended.
    val seed = remember(initialArgb) { argbToHsv(initialArgb) }
    var hue by remember(initialArgb) { mutableStateOf(seed[0]) }
    var saturation by remember(initialArgb) { mutableStateOf(seed[1]) }
    var value by remember(initialArgb) { mutableStateOf(seed[2]) }
    var typed by remember(initialArgb) { mutableStateOf(hexOf(initialArgb)) }

    val argb = hsvToArgb(hue, saturation, value)

    // The field follows the drags, so the hex under a dragged square is the colour of the square. Set
    // from the same `argb` the preview uses rather than recomputed, so the two cannot disagree.
    fun moveTo(h: Float, s: Float, v: Float) {
        hue = h
        saturation = s
        value = v
        typed = hexOf(hsvToArgb(h, s, v))
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SaturationValueSquare(
            hue = hue,
            saturation = saturation,
            value = value,
            onChange = { s, v -> moveTo(hue, s, v) },
        )
        HueStrip(hue = hue, onChange = { moveTo(it, saturation, value) })
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(PREVIEW_DP.dp)
                    .clip(RoundedCornerShape(PREVIEW_CORNER_DP.dp)),
            ) {
                Canvas(modifier = Modifier.size(PREVIEW_DP.dp)) { drawRect(color = Color(argb)) }
            }
            Box(modifier = Modifier.weight(1f)) {
                TextFieldRow(
                    label = "Hex",
                    value = typed,
                    // Typed text only moves the sliders once it is a whole colour. A field that snapped
                    // the square to black on the first digit of "00E5FF" would fight the person typing.
                    onValueChange = { text ->
                        typed = text
                        parseHexColour(text)?.let { parsed ->
                            val hsv = argbToHsv(parsed)
                            hue = hsv[0]
                            saturation = hsv[1]
                            value = hsv[2]
                        }
                    },
                    placeholder = "00E5FF",
                    maxLength = HEX_FIELD_MAX,
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { onPick(argb) }) { Text("Use this colour") }
        }
        Text(
            text = "Kept in your saved colours so the next crosshair can use it too. Transparency is the " +
                "opacity slider above, not part of the colour.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Saturation left to right, value top to bottom, at the current [hue].
 *
 * Two gradients over one another, which is the standard construction and worth naming: white-to-hue
 * horizontally gives saturation, and transparent-to-black vertically darkens it. Drawn in that order
 * because the black has to be on top — a hue gradient over black would wash the bottom edge back out.
 *
 * Tap and drag are both handled, in two `pointerInput` blocks rather than one. A single block cannot do
 * both: `detectDragGestures` consumes the down event, so a tap that never moves would produce nothing.
 */
@Composable
private fun SaturationValueSquare(
    hue: Float,
    saturation: Float,
    value: Float,
    onChange: (Float, Float) -> Unit,
) {
    var size by remember { mutableStateOf(Size.Zero) }

    fun report(position: Offset) {
        if (size.width <= 0f || size.height <= 0f) return
        onChange(
            (position.x / size.width).coerceIn(0f, 1f),
            (1f - position.y / size.height).coerceIn(0f, 1f),
        )
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(SQUARE_HEIGHT_DP.dp)
            .clip(RoundedCornerShape(SURFACE_CORNER_DP.dp))
            .pointerInput(Unit) { detectTapGestures { report(it) } }
            .pointerInput(Unit) {
                detectDragGestures { change, _ -> report(change.position) }
            },
    ) {
        size = this.size
        drawRect(
            brush = Brush.horizontalGradient(
                listOf(Color.White, Color(hsvToArgb(hue, 1f, 1f))),
            ),
        )
        drawRect(brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        marker(
            centre = Offset(
                x = saturation * this.size.width,
                y = (1f - value) * this.size.height,
            ),
        )
    }
}

/**
 * The hue wheel, cut open and laid flat.
 *
 * Seven stops rather than six, because the first and last are both red: a six-stop gradient runs red to
 * magenta and leaves the wrap-around as a hard edge at the right-hand end.
 */
@Composable
private fun HueStrip(hue: Float, onChange: (Float) -> Unit) {
    var width by remember { mutableStateOf(0f) }

    fun report(x: Float) {
        if (width <= 0f) return
        onChange((x / width).coerceIn(0f, 1f) * HUE_MAX)
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(STRIP_HEIGHT_DP.dp)
            .clip(RoundedCornerShape(SURFACE_CORNER_DP.dp))
            .pointerInput(Unit) { detectTapGestures { report(it.x) } }
            .pointerInput(Unit) {
                detectDragGestures { change, _ -> report(change.position.x) }
            },
    ) {
        width = size.width
        drawRect(
            brush = Brush.horizontalGradient(
                HUE_STOPS.map { Color(hsvToArgb(it, 1f, 1f)) },
            ),
        )
        marker(centre = Offset(x = hue / HUE_MAX * size.width, y = size.height / 2f))
    }
}

/**
 * Where the finger last was, as a ring rather than a dot.
 *
 * A ring, and a white one inside a black one, for the same reason the crosshair itself has an outline: a
 * marker in one colour vanishes over the part of the gradient that matches it, and the two colours it
 * would vanish over are at opposite ends of every gradient here.
 */
private fun DrawScope.marker(centre: Offset) {
    drawCircle(
        color = Color.Black,
        radius = MARKER_RADIUS_PX + MARKER_STROKE_PX,
        center = centre,
        style = Stroke(width = MARKER_STROKE_PX),
    )
    drawCircle(
        color = Color.White,
        radius = MARKER_RADIUS_PX,
        center = centre,
        style = Stroke(width = MARKER_STROKE_PX),
    )
}

// ------------------------------------------------------------------------------------ the arithmetic

/**
 * HSV to an opaque ARGB integer.
 *
 * Written out rather than calling `android.graphics.Color.HSVToColor` or `Color.hsv`, and the reason is
 * the test file next to this one: the platform version needs a device, and a colour conversion that can
 * only be checked on a device is one nobody checks. This is twenty lines of arithmetic with a known
 * answer at every sixth of the wheel, so it is worth being able to assert.
 *
 * [hue] wraps rather than clamping, so 360 and 0 are the same red and a drag that overshoots the end of
 * the strip does not stick. [saturation] and [value] clamp, because they have real ends.
 */
internal fun hsvToArgb(hue: Float, saturation: Float, value: Float): Int {
    val h = ((hue % HUE_MAX) + HUE_MAX) % HUE_MAX
    val s = saturation.coerceIn(0f, 1f)
    val v = value.coerceIn(0f, 1f)

    val sector = h / 60f
    val chroma = v * s
    // The second-largest component, which is what makes a hue between two primaries a mix of them.
    val second = chroma * (1f - abs(sector % 2f - 1f))
    val floor = v - chroma

    val (r, g, b) = when (sector.toInt()) {
        0 -> Triple(chroma, second, 0f)
        1 -> Triple(second, chroma, 0f)
        2 -> Triple(0f, chroma, second)
        3 -> Triple(0f, second, chroma)
        4 -> Triple(second, 0f, chroma)
        // 6 is reachable only when `h` is a hair under 360 and rounds up in the division above.
        else -> Triple(chroma, 0f, second)
    }

    return OPAQUE_ALPHA or
        (byteOf(r + floor) shl 16) or
        (byteOf(g + floor) shl 8) or
        byteOf(b + floor)
}

/**
 * ARGB to hue, saturation and value, as a three-element array.
 *
 * The inverse of [hsvToArgb] on everything except the alpha byte, which is dropped rather than reported —
 * nothing here can produce a transparent colour, so there would be nothing to do with it.
 *
 * Grey is the case worth knowing about: when all three channels are equal the chroma is zero and the hue
 * is genuinely undefined, so this returns 0 for it. That means a round trip through grey loses the hue
 * the user had picked, which is why the picker keeps its own hue in state rather than recomputing it from
 * the colour on every frame.
 */
internal fun argbToHsv(argb: Int): FloatArray {
    val r = (argb shr 16 and 0xFF) / 255f
    val g = (argb shr 8 and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f

    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val chroma = max - min

    val hue = when {
        chroma == 0f -> 0f
        max == r -> 60f * (((g - b) / chroma) % 6f)
        max == g -> 60f * ((b - r) / chroma + 2f)
        else -> 60f * ((r - g) / chroma + 4f)
    }

    return floatArrayOf(
        ((hue % HUE_MAX) + HUE_MAX) % HUE_MAX,
        if (max == 0f) 0f else chroma / max,
        max,
    )
}

/** `RRGGBB`, upper case, with the alpha byte left off because nothing here can change it. */
internal fun hexOf(argb: Int): String = "%06X".format(argb and 0xFFFFFF)

/**
 * A typed colour, or null if it is not one yet.
 *
 * Accepts a leading `#` and either three or six digits, because those are the two forms people copy: a
 * six-digit hex from a design tool and a three-digit one from CSS. Three digits expand by doubling each,
 * which is what `#0AF` means everywhere else it appears.
 *
 * Null rather than a fallback colour for anything else, including the empty string and a half-typed
 * value. The caller's job is to leave the colour alone until this returns something — a parser that
 * guessed would move the square while the second character was being typed.
 */
internal fun parseHexColour(text: String): Int? {
    val digits = text.trim().removePrefix("#")
    val expanded = when (digits.length) {
        3 -> digits.map { "$it$it" }.joinToString("")
        6 -> digits
        else -> return null
    }
    val rgb = expanded.toIntOrNull(radix = 16) ?: return null
    return OPAQUE_ALPHA or rgb
}

/** 0..1 to one byte, rounded rather than truncated so 1f is 255 and not 254. */
private fun byteOf(channel: Float): Int = (channel.coerceIn(0f, 1f) * 255f).roundToInt()

private const val OPAQUE_ALPHA = 0xFF000000.toInt()

private const val HUE_MAX = 360f

/** Red at both ends. See [HueStrip]. */
private val HUE_STOPS = listOf(0f, 60f, 120f, 180f, 240f, 300f, 360f)

private const val SQUARE_HEIGHT_DP = 150

private const val STRIP_HEIGHT_DP = 26

private const val SURFACE_CORNER_DP = 10

private const val PREVIEW_DP = 52

private const val PREVIEW_CORNER_DP = 10

/** `#` plus six digits, so a paste with the hash still fits. */
private const val HEX_FIELD_MAX = 7

private const val MARKER_RADIUS_PX = 9f

private const val MARKER_STROKE_PX = 2f
