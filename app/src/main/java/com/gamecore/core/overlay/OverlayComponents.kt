package com.gamecore.core.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The two controls the overlay's settings panel is built from (spec §6): a slider and a toggle.
 *
 * Both are pure UI. They hold no state of their own — the caller owns the value and is handed a
 * change through a lambda — and they draw with [OverlayPalette] and Compose foundation only, never
 * `MaterialTheme`, for the same reason [PerformancePill] does: this content is composed inside a
 * service, over a game GameCore cannot see, so it carries its own dark plate and light text rather
 * than inheriting a theme that might be amber over a desert level.
 *
 * All number-to-position and number-to-text work is delegated to [OverlaySliderMapping], which is
 * plain Kotlin and unit-tested; these composables only place boxes and draw the strings it returns.
 *
 * The `accent` the caller passes is the user's chosen colour. It is used for the parts that sit on
 * GameCore's own surface — the slider fill and thumb, and a toggle's "on" fill — and defaults to
 * [OverlayPalette.Text] so the controls are still legible if no accent is supplied.
 */

private val TrackHeight = 4.dp
private val ThumbDiameter = 16.dp
private val TouchTarget = 48.dp

/**
 * A single-thumb slider: one track, one fill up to the current value, and one thumb.
 *
 * The value the user reads comes from [OverlaySliderMapping.label], so `"52%"`, `"+4%"`, `"0°"` and
 * `"60 Hz"` all format the same way here as they do anywhere else in the overlay. Dragging or tapping
 * the track maps the touch position back to a value through [OverlaySliderMapping.fractionToValue],
 * which snaps to [step] and clamps to `[min, max]`, so [onValueChange] only ever receives a legal value.
 *
 * Accessibility: the whole control is one node. It carries [label] as its content description, the
 * formatted value as its state description, and a [ProgressBarRangeInfo] plus a `setProgress` action so
 * assistive tech can both read and adjust it. The touch row is at least [TouchTarget] tall even though
 * the track is thin.
 *
 * @param label the human name of the setting, e.g. "Opacity" — also the accessibility description.
 * @param value the current value, owned by the caller.
 * @param min inclusive lower bound of the value-space.
 * @param max inclusive upper bound of the value-space.
 * @param step quantisation granularity, anchored at [min].
 * @param unit the unit shown in the value label (`"%"`, `"°"`, `"Hz"`, `""`, ...).
 * @param onValueChange invoked with the snapped, clamped value as the user drags or taps.
 * @param modifier applied to the control's root column.
 * @param signed whether the value label shows an explicit `"+"` on non-negative values.
 * @param enabled whether the slider accepts input; `false` dims it, blocks the gestures, and marks it
 *   disabled to assistive tech — so a slider shown for a control this device cannot do does not drag
 *   inertly (the failure §32 rules out).
 * @param onValueChangeFinished invoked once when a drag or tap settles, for callers that write on the
 *   commit edge rather than every frame; `null` (the default) means the caller debounces or writes live.
 * @param valueText overrides the derived value label. `null` uses [OverlaySliderMapping.label]; callers
 *   with an app-wide formatter (a colour field's `format`, a `"--"` for an unread level) pass their own so
 *   the panel and the editor never show one number two ways.
 * @param accent the fill and thumb colour; defaults to [OverlayPalette.Text].
 */
@Composable
fun OverlaySlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    step: Float,
    unit: String,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    signed: Boolean = false,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
    valueText: String? = null,
    accent: Color = OverlayPalette.Text,
) {
    val fraction = OverlaySliderMapping.valueToFraction(value, min, max)
    val shownText = valueText ?: OverlaySliderMapping.label(value, unit, signed)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnFinished by rememberUpdatedState(onValueChangeFinished)
    var trackWidthPx by remember { mutableFloatStateOf(0f) }
    val thumbPx = with(LocalDensity.current) { ThumbDiameter.toPx() }

    // Maps a touch x (within the track) to a snapped value and reports it. The thumb has width, so the
    // usable travel is the track minus the thumb; anchoring at half a thumb keeps the ends reachable.
    fun reportAtX(x: Float) {
        val travel = (trackWidthPx - thumbPx).coerceAtLeast(1f)
        val f = ((x - thumbPx / 2f) / travel).coerceIn(0f, 1f)
        currentOnValueChange(OverlaySliderMapping.fractionToValue(f, min, max, step))
    }

    Column(
        modifier = modifier
            .heightIn(min = TouchTarget)
            .alpha(if (enabled) 1f else 0.5f)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                stateDescription = shownText
                if (!enabled) disabled()
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = value,
                    range = min..max,
                    steps = OverlaySliderMapping.steps(min, max, step),
                )
                if (enabled) {
                    setProgress { target ->
                        val f = OverlaySliderMapping.valueToFraction(target, min, max)
                        currentOnValueChange(OverlaySliderMapping.fractionToValue(f, min, max, step))
                        currentOnFinished?.invoke()
                        true
                    }
                }
            },
        verticalArrangement = Arrangement.Center,
    ) {
        // Name on the left, current value on the right — the value in the bright text colour so the eye
        // lands on the figure, the label in the muted colour like it is in the pill.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = label,
                style = TextStyle(color = OverlayPalette.Muted, fontWeight = FontWeight.Medium),
            )
            BasicText(
                text = shownText,
                style = TextStyle(color = OverlayPalette.Text, fontWeight = FontWeight.SemiBold),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(TouchTarget)
                .onSizeChanged { trackWidthPx = it.width.toFloat() }
                .then(
                    if (enabled) {
                        Modifier
                            .pointerInput(min, max, step) {
                                detectTapGestures { offset ->
                                    reportAtX(offset.x)
                                    currentOnFinished?.invoke()
                                }
                            }
                            .pointerInput(min, max, step) {
                                detectHorizontalDragGestures(
                                    onDragEnd = { currentOnFinished?.invoke() },
                                ) { change, _ ->
                                    change.consume()
                                    reportAtX(change.position.x)
                                }
                            }
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            // Track groove.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TrackHeight)
                    .background(OverlayPalette.Divider, CircleShape),
            )
            // Fill up to the current fraction.
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(TrackHeight)
                    .background(accent, CircleShape),
            )
            // Thumb, offset so its centre tracks the fraction across the usable travel.
            Box(
                modifier = Modifier
                    .offset {
                        val travel = (trackWidthPx - thumbPx).coerceAtLeast(0f)
                        IntOffset((fraction * travel).roundToInt(), 0)
                    }
                    .size(ThumbDiameter)
                    .background(accent, CircleShape),
            )
        }
    }
}

/**
 * A labelled on/off control.
 *
 * On and off are told apart by shape and word, not colour alone: "on" is an accent-filled pill reading
 * "On", "off" is an outlined pill reading "Off". A user who cannot separate the two colours still has the
 * fill-vs-outline shape and the label to go on.
 *
 * When [enabled] is `false` the whole row dims and stops taking input, and [reason] — if given — is both
 * shown as a small line under the label and used as the control's state description, so a screen reader
 * says why it is off rather than just that it is disabled.
 *
 * @param label the human name of the setting — also the accessibility description.
 * @param checked the current state, owned by the caller.
 * @param onCheckedChange invoked with the new state when the row is toggled.
 * @param modifier applied to the control's root row.
 * @param enabled whether the control accepts input; `false` dims it and blocks toggling.
 * @param reason why the control is disabled, shown as a subtitle and announced to assistive tech.
 * @param accent the "on" fill colour; defaults to [OverlayPalette.Text].
 * @param icon an optional leading glyph slot, drawn before the label.
 */
@Composable
fun OverlayToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    reason: String? = null,
    accent: Color = OverlayPalette.Text,
    icon: (@Composable () -> Unit)? = null,
) {
    val pillShape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = TouchTarget)
            .alpha(if (enabled) 1f else 0.5f)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .semantics {
                contentDescription = label
                stateDescription = when {
                    !enabled && reason != null -> reason
                    checked -> "On"
                    else -> "Off"
                }
                if (!enabled) disabled()
            }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            icon()
        }
        Column(
            modifier = Modifier.weightIfPossible(),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            BasicText(
                text = label,
                style = TextStyle(color = OverlayPalette.Text, fontWeight = FontWeight.Medium),
            )
            if (!enabled && reason != null) {
                BasicText(
                    text = reason,
                    style = TextStyle(color = OverlayPalette.Muted, fontWeight = FontWeight.Normal),
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        // Shape + word, not colour alone: filled pill "On" vs outlined pill "Off".
        if (checked) {
            Box(
                modifier = Modifier
                    .background(accent, pillShape)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                BasicText(
                    text = "On",
                    style = TextStyle(color = OverlayPalette.Plate, fontWeight = FontWeight.SemiBold),
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .border(1.dp, OverlayPalette.Muted, pillShape)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                BasicText(
                    text = "Off",
                    style = TextStyle(color = OverlayPalette.Muted, fontWeight = FontWeight.SemiBold),
                )
            }
        }
    }
}

/**
 * Lets the label column take the row's leftover width so the state pill stays hard against the right
 * edge. Kept as a tiny extension purely to keep the [Row] body readable.
 */
private fun Modifier.weightIfPossible(): Modifier = this.fillMaxWidth(0.7f)
