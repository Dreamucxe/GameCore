package com.gamecore.ui.glass

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.core.plugin.theme.PluginGlass
import com.gamecore.data.plugin.GlassControl
import com.gamecore.ui.components.KeyValueRow
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.SliderRow
import com.gamecore.ui.components.Tone
import com.gamecore.ui.theme.LocalAnimationsEnabled
import com.gamecore.ui.theme.glassPanel
import com.gamecore.ui.theme.rememberGlassPreviewState

/**
 * The Glass Control Center: the material a schema-4 theme asked for, and the one number the user gets
 * to move it by.
 *
 * ## What this screen decides, and what it does not
 *
 * A `glass` block is a design, and a design has one place where the author's taste runs out and the
 * user's begins: how much of it they want. That is all this screen sets. It does not choose the
 * colours — the theme does; it does not choose the shape or the palette — the theme does; it moves one
 * value. Everything else here is the screen *reporting* what the material in force is made of, because
 * a control whose effect you cannot see is a control nobody uses.
 *
 * ## The preview is the material, not a picture of it
 *
 * [PreviewCard] draws through the renderer's own code path — the same modifier the app's cards use —
 * over a backdrop built from the theme's own accents, fed from the slider's *live* position rather
 * than from the stored one. So the panel under the finger is drawn from exactly the material that will
 * be stored when the finger lifts, and the highlight drifting on it is the real clock at the real
 * period rather than a still frame of one.
 *
 * ## What it says when there is nothing to control
 *
 * Each state is stated rather than approximated. A device that cannot blur a backdrop says so — see
 * [BackdropRow] — instead of calling a translucent panel "frosted". A theme with a fixed material says
 * so and hides the slider rather than drawing one that does nothing. An app wearing no glass theme
 * says that, and how to get one, rather than previewing a material nobody asked for.
 */
@Composable
fun GlassCentreScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GlassCentreViewModel = hiltViewModel(),
) {
    val control by viewModel.control.collectAsStateWithLifecycle()
    val fraction by viewModel.fraction.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val padded = Modifier.padding(horizontal = ScreenPadding)

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Glass Control Center",
                subtitle = "How much material the app is wearing",
                onBack = onBack,
            )
        }
        if (message != null) {
            item {
                NoteBanner(
                    text = message.orEmpty(),
                    tone = Tone.Warning,
                    modifier = padded,
                    action = {
                        TextButton(onClick = viewModel::clearMessage) { Text("Dismiss") }
                    },
                )
            }
        }
        when (val current = control) {
            null -> item { NoGlassCard(modifier = padded) }
            else -> {
                item { PreviewCard(control = current, fraction = fraction, modifier = padded) }
                item {
                    IntensityCard(
                        control = current,
                        fraction = fraction,
                        onDrag = viewModel::dragTo,
                        onCommit = viewModel::commit,
                        onReset = viewModel::resetToAuthored,
                        modifier = padded,
                    )
                }
                item { MaterialCard(control = current, fraction = fraction, modifier = padded) }
            }
        }
        item { ExplanationCard(modifier = padded) }
    }
}

/**
 * The panel itself, at the size of a card and over something worth refracting.
 *
 * Two things make this a real test of the material rather than a swatch. The backdrop is a diagonal
 * gradient in the screen's own primary, secondary and background — the same accents
 * [com.gamecore.ui.theme.GlassBackdrop] blooms across the app — so the translucency has structure
 * behind it to pick up, which is the only way to see whether a fill alpha is doing anything at all.
 * And the panel's contents are two rows of plausible app furniture, so the gloss and the rim are
 * judged against text rather than against an empty rectangle.
 *
 * The panel is the same `Surface` shape a card is: transparent colour, [glassPanel] for pixels, the
 * Material shape for the clip and the content colour. Dragging the slider to nothing really does take
 * the material away, and what is left is an ordinary card — which is the honest end of the track
 * rather than a panel with its pixels switched off.
 */
@Composable
private fun PreviewCard(control: GlassControl, fraction: Float, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    // The live material: the author's block moved to wherever the finger currently is, which on a
    // settled screen is exactly the stored one. Built here rather than read from the composition local
    // so that a mid-drag frame shows what the finger is asking for rather than what was last saved.
    val material = control.authored.scaledBy(fraction)
    val shape = RoundedCornerShape(PREVIEW_CORNER)
    val glass = rememberGlassPreviewState(
        material = material,
        surfaceArgb = scheme.surface.toArgb(),
        inkArgb = scheme.onSurface.toArgb(),
        animationsEnabled = LocalAnimationsEnabled.current,
    )
    val panel = Modifier
        .fillMaxWidth(PREVIEW_PANEL_WIDTH_FRACTION)
        .padding(horizontal = PREVIEW_PANEL_INSET)

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(PREVIEW_HEIGHT)
                .clip(RoundedCornerShape(PREVIEW_CORNER))
                .drawBackdrop(
                    background = scheme.background,
                    primary = scheme.primary,
                    secondary = scheme.secondary,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = if (glass == null) panel else panel.glassPanel(glass, shape),
                shape = shape,
                // Transparent when there is a material, because the modifier draws the fill itself —
                // and the plain surface colour when there is not, which is what every other card in
                // the app is wearing at that setting.
                color = if (glass == null) scheme.surface else Color.Transparent,
                contentColor = scheme.onSurface,
                border = if (glass == null) BorderStroke(1.dp, scheme.outlineVariant) else null,
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Focus mode",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Time remaining",
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "42 min",
                            style = MaterialTheme.typography.bodyLarge,
                            color = scheme.primary,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The slider, and the two facts about it a user cannot get from the track.
 *
 * The value is a percentage of the *authored* material, not of some absolute scale: 100% is exactly
 * what the theme's author drew and 0% is none of it. That is why the readout says "as designed" at the
 * top of the travel — a claim the user can check against the plugin's own description rather than take
 * on faith.
 *
 * A theme whose material the author fixed shows no slider at all. Drawing a disabled one would suggest
 * the control exists and has been switched off by something, and it has not: it does not exist, which
 * is a different sentence and the one the card says.
 */
@Composable
private fun IntensityCard(
    control: GlassControl,
    fraction: Float,
    onDrag: (Float) -> Unit,
    onCommit: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Intensity",
        subtitle = control.pluginName,
        icon = Icons.Filled.BlurOn,
        modifier = modifier,
        action = if (control.adjustable) {
            {
                TextButton(onClick = onReset) {
                    Text("Reset")
                }
            }
        } else {
            null
        },
    ) {
        if (!control.adjustable) {
            NoteBanner(
                text = "This theme ships a fixed material, so there is nothing here to move. Its " +
                    "author asked for exactly what you are looking at.",
                tone = Tone.Muted,
            )
            return@SectionCard
        }
        SliderRow(
            title = "Material",
            value = (fraction * 100f).toInt(),
            range = 0..100,
            onValueChange = { onDrag(it / 100f) },
            valueLabel = intensityLabel(fraction),
            description = "Scales the transparency, the gloss and the blur radius together. The " +
                "sheen's speed and the way panels settle are the theme's and do not change.",
            onValueChangeFinished = onCommit,
        )
    }
}

/**
 * What the material in force is made of, in the screen's own numbers.
 *
 * Every figure is the *scaled* one — the material as it is on screen right now — not the author's,
 * because a readout that said 55% transparency while the user had dialled the material down to half
 * would be a readout of a different panel than the one previewed above it. The exception is the blur
 * radius, which is reported with its capability rather than as a bare number.
 */
@Composable
private fun MaterialCard(control: GlassControl, fraction: Float, modifier: Modifier = Modifier) {
    val material = control.authored.scaledBy(fraction)
    SectionCard(
        title = "The material",
        subtitle = "What this theme makes panels out of",
        modifier = modifier,
    ) {
        KeyValueRow("Transparency", percent(material.transparency))
        KeyValueRow("Gloss", percent(material.gloss))
        KeyValueRow("Sheen", if (material.sheen) "Drifting" else "Still")
        KeyValueRow("Settle", material.spring.id.replaceFirstChar { it.uppercase() })
        BackdropRow(material = material, available = control.blurAvailable)
    }
}

/**
 * The backdrop row, which is the one place this screen has to correct a reasonable expectation.
 *
 * "Glass" and "frosted" mean a blurred backdrop to most people, and a real layer blur is an Android 12
 * feature. On anything older the material is still a material — translucent, rimmed, glossed and lit —
 * but the thing behind it is sharp, and a screen that listed "Blur: 16 dp" without saying so would be
 * describing a device the user is not holding. That is the house rule about claims applied to a
 * specification: state what is true here.
 */
@Composable
private fun BackdropRow(material: PluginGlass, available: Boolean) {
    val radius = material.blurRadiusDp
    when {
        radius <= 0 -> KeyValueRow("Backdrop", "Sharp")

        available -> KeyValueRow("Backdrop", "Blurred $radius dp")

        else -> {
            KeyValueRow("Backdrop", "Sharp — this Android version cannot blur one")
            NoteBanner(
                text = "Backdrop blur arrived with Android 12. On this device the panels are " +
                    "translucent, rimmed and glossed against a sharp background — which is why the " +
                    "material still reads as glass, and why a blur radius would be doing nothing here " +
                    "even though the theme asked for one.",
                tone = Tone.Muted,
            )
        }
    }
}

/** The state a user sees before they have installed anything that uses this. */
@Composable
private fun NoGlassCard(modifier: Modifier = Modifier) {
    SectionCard(
        title = "No glass theme is active",
        subtitle = "Nothing to control yet",
        modifier = modifier,
    ) {
        Text(
            text = "This screen drives the material block of a plugin theme. Install a theme that " +
                "asks for one — the iOS-style Liquid Glass theme is one — then select it in " +
                "Settings → Plugins, and its control appears here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** What glass is, why it can be here at all, and what it will never do. */
@Composable
private fun ExplanationCard(modifier: Modifier = Modifier) {
    SectionCard(title = "How this works", modifier = modifier) {
        Text(
            text = "A theme can ask for a material, and this is where you say how much of it you " +
                "want. The value is stored in the plugin's own settings, so it travels with the " +
                "plugin rather than with GameCore, and it is the same number the plugin's own " +
                "settings screen shows.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "The material itself is drawn by GameCore and not by the plugin. A .gcplugin is " +
                "data: it can name a transparency, a gloss and a spring, and it cannot reach the " +
                "renderer, the frame clock or another app. That is what keeps installing one a " +
                "low-risk act, and it is why the most a theme can do here is choose a look.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Your Animations setting still wins. With animation off the material is drawn at " +
                "rest — the translucency, the rim and the gloss stay, the drifting highlight stops " +
                "and the panels stop settling.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A percentage of the authored material, spoken the way its author would have described it. */
internal fun intensityLabel(fraction: Float): String {
    val percent = (fraction.coerceIn(0f, 1f) * 100f).toInt()
    return when (percent) {
        100 -> "100% — as designed"
        0 -> "0% — no material"
        else -> "$percent%"
    }
}

/** A 0..1 quantity as a whole percentage. */
private fun percent(value: Float): String = "${(value.coerceIn(0f, 1f) * 100f).toInt()}%"

private val PREVIEW_CORNER = 18.dp
private val PREVIEW_PANEL_INSET = 20.dp
private val PREVIEW_HEIGHT = 190.dp
private const val PREVIEW_PANEL_WIDTH_FRACTION = 0.78f

/**
 * The diagonal gradient the preview panel sits on.
 *
 * The app's real backdrop is [com.gamecore.ui.theme.GlassBackdrop]'s two blooms over the theme's
 * background. This is the same idea compressed into one card, and its purpose is the same: give the
 * translucency something with structure behind it, so what the slider is doing is visible rather than
 * theoretical.
 */
private fun Modifier.drawBackdrop(
    background: Color,
    primary: Color,
    secondary: Color,
): Modifier = drawWithCache {
    val brush = Brush.linearGradient(
        colors = listOf(
            primary.copy(alpha = 0.85f),
            secondary.copy(alpha = 0.55f),
            background,
        ),
    )
    onDrawBehind { drawRect(brush) }
}