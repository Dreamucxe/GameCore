package com.gamecore.studio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gamecore.studio.core.ColorMath
import com.gamecore.studio.core.ColorMemory
import com.gamecore.studio.core.ColorVision
import com.gamecore.studio.core.Hsl
import com.gamecore.studio.core.Hsv
import com.gamecore.studio.core.Rgb
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The colour being edited, and the surface it will be drawn on.
 *
 * [backdrop] is not decoration: the picker measures the candidate against the actual surface
 * the role will sit on, so "this looks nice" and "this is readable" are answered by the same
 * screen instead of by two separate inspections later.
 */
internal data class ColorTarget(
    val key: String,
    val label: String,
    val color: Long,
    val backdrop: Long,
)

/**
 * A visual colour picker with a wheel, HSV/HSL/RGB/HEX entry, palette generation and real
 * contrast measurement.
 *
 * The wheel is the primary control: angle is hue, distance from the centre is saturation, and
 * the brightness slider dims the whole disc. The numeric modes are the precise path for people
 * who already know the value they want. Every change is applied immediately — the editor behind
 * the dialog updates as the colour moves, which is the only way to judge a palette.
 *
 * Recently used colours and favourites live in the host app's preferences, not in the package.
 * Saved palettes hold the theme's thirteen roles in [com.gamecore.studio.model.ThemeDraft.COLOR_FIELDS]
 * order, so applying one is a single deliberate action rather than thirteen guesses.
 */
@Composable
internal fun AdvancedColorPickerDialog(
    target: ColorTarget,
    themeName: String,
    palette: List<Long>,
    memory: ColorMemory,
    recents: List<Long>,
    onColorChange: (Long) -> Unit,
    onApplyPalette: (List<Long>) -> Unit,
    onRemember: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var color by remember { mutableStateOf(target.color) }
    var hsv by remember { mutableStateOf(ColorMath.hsv(target.color)) }
    var alpha by remember { mutableStateOf(ColorMath.alpha(target.color)) }
    var mode by remember { mutableStateOf(ColorMode.HEX) }
    var hexText by remember { mutableStateOf(ColorMath.hex(target.color)) }
    var favorites by remember { mutableStateOf(memory.favorites()) }
    var savedPalettes by remember { mutableStateOf(memory.palettes()) }
    var paletteName by remember { mutableStateOf("$themeName palette") }
    var naming by remember { mutableStateOf(false) }

    fun apply(next: Long) {
        color = next
        hsv = ColorMath.hsv(next)
        alpha = ColorMath.alpha(next)
        hexText = ColorMath.hex(next)
        onColorChange(next)
    }

    fun applyHsv(next: Hsv, nextAlpha: Int = alpha) {
        apply(ColorMath.fromHsv(next, nextAlpha))
    }

    fun applyHsl(next: Hsl) {
        apply(ColorMath.fromHsl(next, alpha))
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .padding(vertical = 16.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(target.label, style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Angle is hue, distance is saturation, the slider is brightness.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = {
                            val nowFavorite = memory.toggleFavorite(color)
                            favorites = memory.favorites()
                            if (nowFavorite) Unit
                        },
                    ) {
                        Icon(
                            if (color in favorites) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = if (color in favorites) {
                                "Remove this colour from favourites"
                            } else {
                                "Save this colour as a favourite"
                            },
                            tint = if (color in favorites) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }

                ColorWheel(
                    hue = hsv.hue,
                    saturation = hsv.saturation,
                    value = hsv.value,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp),
                    onChange = { hue, saturation -> applyHsv(Hsv(hue, saturation, hsv.value)) },
                )

                PickerSlider(
                    label = "Hue ${hsv.hue.toInt()}°",
                    value = hsv.hue,
                    range = 0f..360f,
                    onChange = { applyHsv(hsv.copy(hue = it)) },
                )
                PickerSlider(
                    label = "Saturation ${(hsv.saturation * 100).toInt()}%",
                    value = hsv.saturation,
                    range = 0f..1f,
                    onChange = { applyHsv(hsv.copy(saturation = it)) },
                )
                PickerSlider(
                    label = "Brightness ${(hsv.value * 100).toInt()}%",
                    value = hsv.value,
                    range = 0f..1f,
                    onChange = { applyHsv(hsv.copy(value = it)) },
                )
                PickerSlider(
                    label = "Opacity ${(alpha / 255f * 100).toInt()}%",
                    value = alpha.toFloat(),
                    range = 0f..255f,
                    onChange = { apply(ColorMath.withAlpha(color, it.toInt())) },
                )
                Text(
                    "The host accepts translucent colours, but ink below full opacity can be hard " +
                        "to read. The contrast check below composites the colour over its backdrop.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // A live swatch, its value, and the real contrast against the target surface.
                val ratio = ColorMath.contrastRatio(color, target.backdrop)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(color.toThemeColor(), RoundedCornerShape(14.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp)),
                    )
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text(ColorMath.hex(color), style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Contrast on the backdrop: ${"%.2f".format(ratio)}:1 " +
                                if (ratio >= 4.5) "· passes AA" else "· below AA",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (ratio >= 4.5) {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(target.backdrop.toThemeColor(), RoundedCornerShape(10.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)),
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ColorMode.entries.forEach { item ->
                        FilterChip(
                            selected = mode == item,
                            onClick = { mode = item },
                            label = { Text(item.label) },
                        )
                    }
                }
                when (mode) {
                    ColorMode.HEX -> OutlinedTextField(
                        value = hexText,
                        onValueChange = { typed ->
                            hexText = typed
                            ColorMath.parseHex(typed)?.let { apply(it) }
                        },
                        label = { Text("#RRGGBB or #AARRGGBB") },
                        singleLine = true,
                        isError = ColorMath.parseHex(hexText) == null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ColorMode.RGB -> {
                        val rgb = ColorMath.rgb(color)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NumberField("R", rgb.red, 0, 255) {
                                apply(ColorMath.argb(it, rgb.green, rgb.blue, alpha))
                            }
                            NumberField("G", rgb.green, 0, 255) {
                                apply(ColorMath.argb(rgb.red, it, rgb.blue, alpha))
                            }
                            NumberField("B", rgb.blue, 0, 255) {
                                apply(ColorMath.argb(rgb.red, rgb.green, it, alpha))
                            }
                        }
                    }
                    ColorMode.HSL -> {
                        val hsl = ColorMath.hsl(color)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NumberField("H", hsl.hue.toInt(), 0, 360) {
                                applyHsl(hsl.copy(hue = it.toFloat()))
                            }
                            NumberField("S", (hsl.saturation * 100).toInt(), 0, 100) {
                                applyHsl(hsl.copy(saturation = it / 100f))
                            }
                            NumberField("L", (hsl.lightness * 100).toInt(), 0, 100) {
                                applyHsl(hsl.copy(lightness = it / 100f))
                            }
                        }
                    }
                }

                SuggestionRow(
                    title = "Harmonies",
                    colors = listOf(ColorMath.complementary(color)) +
                        ColorMath.analogous(color) +
                        ColorMath.monochromatic(color),
                    onPick = ::apply,
                )
                SuggestionRow(
                    title = "Readable inks for this backdrop",
                    colors = listOf(ColorMath.readableOn(target.backdrop), ColorMath.readableOn(color)),
                    onPick = ::apply,
                )

                if (recents.isNotEmpty()) {
                    SuggestionRow(
                        title = "Recent",
                        colors = recents,
                        onPick = ::apply,
                    )
                }
                if (favorites.isNotEmpty()) {
                    SuggestionRow(
                        title = "Favourites",
                        colors = favorites,
                        onPick = ::apply,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Saved palettes",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    TextButton(onClick = { naming = true }) { Text("Save this theme's palette") }
                }
                if (savedPalettes.isEmpty()) {
                    Text(
                        "A saved palette stores all thirteen roles and can be applied to another theme.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        savedPalettes.take(3).forEach { saved ->
                            FilterChip(
                                selected = false,
                                onClick = {
                                    onApplyPalette(saved.colors)
                                    onDismiss()
                                },
                                label = { Text(saved.name.take(18)) },
                            )
                        }
                    }
                }

                Text("Colour-vision simulation", style = MaterialTheme.typography.labelMedium)
                Text(
                    "An approximation using published LMS matrices. It is a design aid, not a " +
                        "medical test.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VisionSwatch("None", color, null)
                    ColorVision.entries.forEach { vision ->
                        VisionSwatch(
                            label = vision.name.lowercase().replaceFirstChar { it.uppercase() },
                            color = ColorMath.simulate(color, vision),
                            vision = vision,
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(
                        onClick = {
                            onRemember(color)
                            onDismiss()
                        },
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Use colour", modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
        }
    }

    if (naming) {
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("Save palette") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The thirteen roles in this theme, saved under a name you choose.")
                    OutlinedTextField(
                        value = paletteName,
                        onValueChange = { paletteName = it.take(48) },
                        label = { Text("Palette name") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        memory.savePalette(paletteName, palette)
                        savedPalettes = memory.palettes()
                        naming = false
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { naming = false }) { Text("Cancel") }
            },
        )
    }
}

/** The wheel itself: a hue ring around a saturation/brightness disc. */
@Composable
private fun ColorWheel(
    hue: Float,
    saturation: Float,
    value: Float,
    modifier: Modifier = Modifier,
    onChange: (hue: Float, saturation: Float) -> Unit,
) {
    Canvas(
        modifier = modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                fun report(position: Offset) {
                    val centre = Offset(size.width / 2f, size.height / 2f)
                    val dx = position.x - centre.x
                    val dy = position.y - centre.y
                    val radius = kotlin.math.sqrt(dx * dx + dy * dy)
                    val inner = minOf(size.width, size.height) / 2f * 0.78f
                    val angle = (Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 360.0) % 360.0
                    onChange(angle.toFloat(), (radius / inner).coerceIn(0f, 1f))
                }
                report(down.position)
                drag(down.id) { change ->
                    report(change.position)
                    change.consume()
                }
            }
        },
    ) {
        val centre = Offset(size.width / 2f, size.height / 2f)
        val outer = minOf(size.width, size.height) / 2f
        val stroke = outer * 0.13f
        val ringRadius = outer - stroke / 2f
        val inner = outer - stroke
        val hueRing = List(13) { index -> Color.hsv(index * 30f, 1f, 1f) }
        drawCircle(
            brush = Brush.sweepGradient(colors = hueRing, center = centre),
            radius = ringRadius,
            center = centre,
            style = Stroke(width = stroke),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White, Color.hsv(hue, 1f, value)),
                center = centre,
                radius = inner,
            ),
            radius = inner,
            center = centre,
        )
        if (value < 1f) {
            drawCircle(
                color = Color.Black.copy(alpha = (1f - value).coerceIn(0f, 1f)),
                radius = inner,
                center = centre,
            )
        }
        val radians = Math.toRadians(hue.toDouble())
        val thumb = Offset(
            centre.x + cos(radians).toFloat() * inner * saturation,
            centre.y + sin(radians).toFloat() * inner * saturation,
        )
        drawCircle(Color.White, radius = 10.dp.toPx(), center = thumb, style = Stroke(3.dp.toPx()))
        drawCircle(Color.Black.copy(alpha = 0.5f), radius = 12.dp.toPx(), center = thumb, style = Stroke(1.dp.toPx()))
    }
}

@Composable
private fun PickerSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Text(label, style = MaterialTheme.typography.labelMedium)
    Slider(value = value, onValueChange = onChange, valueRange = range)
}

@Composable
private fun NumberField(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    onChange: (Int) -> Unit,
) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { typed ->
            typed.toIntOrNull()?.takeIf { it in min..max }?.let(onChange)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.width(96.dp),
    )
}

@Composable
private fun SuggestionRow(
    title: String,
    colors: List<Long>,
    onPick: (Long) -> Unit,
) {
    Text(title, style = MaterialTheme.typography.labelMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        colors.distinct().take(10).forEach { value ->
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(value.toThemeColor(), CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable { onPick(value) },
            )
        }
    }
}

@Composable
private fun VisionSwatch(label: String, color: Long, vision: ColorVision?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .background(color.toThemeColor(), RoundedCornerShape(10.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)),
        )
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

private enum class ColorMode(val label: String) {
    HEX("HEX"),
    RGB("RGB"),
    HSL("HSL"),
}
