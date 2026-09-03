package com.gamecore.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gamecore.core.common.TextSanitizer
import com.gamecore.ui.theme.StatLabelStyle
import com.gamecore.ui.theme.StatValueCompactStyle
import com.gamecore.ui.theme.readableOn
import kotlin.math.roundToInt

/**
 * The controls a settings-heavy app needs, each carrying its own explanation.
 *
 * Every one of these takes a description as well as a title. That is not decoration: this app's switches
 * turn on things with a real cost — a service that samples counters, an overlay drawn over other apps —
 * and §14/§21 both ask for what an option actually does to be stated where it is offered rather than in a
 * help screen the user will not open.
 *
 * All of them are stateless. The value comes in, the change goes out; nothing here remembers anything,
 * so the ViewModel's `AppSettings` stays the only copy of the truth and a slider cannot end up showing a
 * value the store rejected in [com.gamecore.core.model.AppSettings.normalised].
 */

/** A labelled switch with its consequence written under it. */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/**
 * A slider over an integer range, with the current value shown as a figure.
 *
 * Integers rather than floats because every quantity this app lets the user set is one: a percentage, a
 * size in dp, an interval in milliseconds. The value is emitted continuously via [onValueChange] and
 * again on release via [onValueChangeFinished] — the live one so the HUD preview follows the thumb, the
 * final one so a caller that persists (a preference write, a window update) does so once per gesture
 * instead of forty times.
 *
 * [onValueClick] makes the figure itself tappable, for a range a thumb cannot land on exactly: hue is 361
 * positions wide and a gain is 201, so a user who wants −12% has to be able to say so. Null by default,
 * because a tappable label with nothing behind it is §32's button that does nothing — the readout only
 * becomes a target on the screens that offer typing, and stays plain text everywhere else.
 */
@Composable
fun SliderRow(
    title: String,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    valueLabel: String = value.toString(),
    description: String? = null,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
    onValueClick: (() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueLabel,
                style = StatValueCompactStyle,
                color = MaterialTheme.colorScheme.primary,
                // A plate around the figure only when it can be tapped, so the target looks like one.
                // `Role.Button` rather than the default, because "Saturation, +45%" read as a button is
                // what tells a screen-reader user the figure is a second way in and not just a value.
                modifier = if (onValueClick != null && enabled) {
                    Modifier
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(role = Role.Button, onClick = onValueClick)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                } else {
                    Modifier
                },
            )
        }
        if (description != null) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt().coerceIn(range)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            onValueChangeFinished = onValueChangeFinished,
            enabled = enabled,
        )
    }
}

/**
 * A set of mutually exclusive options, one of which is always selected.
 *
 * Generic over the option type so the callers — theme mode, accent, performance profile, session sort,
 * refresh rate — all use the same control rather than each rolling a row of buttons. Options wrap onto
 * as many lines as they need, laid out by chunking rather than by a flow layout, so the wrap point is
 * decided by [perRow] and not by whatever the longest label happens to be on this device's font scale.
 */
@Composable
fun <T> ChoiceRow(
    options: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    perRow: Int = 3,
    enabled: Boolean = true,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.chunked(perRow.coerceAtLeast(1)).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { option ->
                    ChoiceChip(
                        text = label(option),
                        isSelected = option == selected,
                        onClick = { onSelect(option) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    )
                }
                // Keeps a short final row's chips the same width as a full one's.
                repeat(perRow - row.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

/** One option in a [ChoiceRow]. Filled when selected, outlined when not. */
@Composable
fun ChoiceChip(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = if (isSelected) scheme.primary else Color.Transparent,
        contentColor = if (isSelected) scheme.onPrimary else scheme.onSurfaceVariant,
        border = BorderStroke(1.dp, if (isSelected) scheme.primary else scheme.outline),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 9.dp),
        )
    }
}

/**
 * A row of colour circles, for the accent choice and for a crosshair's colour.
 *
 * The check mark inside the selected swatch is drawn in whichever of black or white can be read on that
 * colour — the same [com.gamecore.ui.theme.readableOn] rule the schemes use, because a white tick on the
 * cyan swatch is the exact case that disappears.
 */
@Composable
fun ColourSwatches(
    colours: List<Color>,
    selected: Color?,
    onSelect: (Color) -> Unit,
    modifier: Modifier = Modifier,
    perRow: Int = 6,
    swatchSize: Int = 40,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        colours.chunked(perRow.coerceAtLeast(1)).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { colour ->
                    val isSelected = selected != null && colour.value == selected.value
                    Box(
                        modifier = Modifier
                            .size(swatchSize.dp)
                            .clip(CircleShape)
                            .background(colour)
                            .clickable(role = Role.Button) { onSelect(colour) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = "Selected",
                                tint = readableOn(colour),
                                modifier = Modifier.size((swatchSize / 2).dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A value with a minus and a plus, for quantities where one step is meaningful and a drag is not.
 *
 * Used for interval settings: a sample interval is chosen in whole seconds and a slider over 1 000 to
 * 30 000 milliseconds would put the useful part of the range in the first eighth of its travel.
 */
@Composable
fun StepperRow(
    title: String,
    valueLabel: String,
    onStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    canDecrease: Boolean = true,
    canIncrease: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        StepButton(text = "−", enabled = canDecrease) { onStep(-1) }
        Text(
            text = valueLabel,
            style = StatValueCompactStyle,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(72.dp),
        )
        StepButton(text = "+", enabled = canIncrease) { onStep(1) }
    }
}

@Composable
private fun StepButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (enabled) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        },
        modifier = Modifier.size(36.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text = text, style = MaterialTheme.typography.titleLarge)
        }
    }
}

/**
 * A single-line text field, for the three things in this app the user types: a HUD layout's name, a
 * crosshair preset's name, and the host the latency prober aims at.
 *
 * `BasicTextField` with a hand-drawn container rather than Material's filled `TextField`, because every
 * other input on these screens sits in an outlined card and a filled field inside one is the only
 * component on the page with a different surface treatment.
 *
 * [maxLength] is enforced here rather than left to the caller: all three of these strings end up in an
 * overlay window or a notification, so §24A.4's cap has to hold at the point of entry as well as on the
 * way to storage. Newlines from a paste are flattened for the same reason. The *full* sanitiser runs on
 * save rather than per keystroke — it trims and collapses whitespace, which would delete the space the
 * user just typed in the middle of a name.
 *
 * [keyboardType] is here for the fourth thing the user types, which is a number: the colour editor's
 * tap-the-figure entry, where a full alphabetic keyboard for a value between −100 and 100 is three extra
 * taps and a wrong first guess. It only asks for a keyboard — the range check belongs to the caller that
 * knows the range.
 */
@Composable
fun TextFieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    maxLength: Int = TextSanitizer.MAX_NAME_LENGTH,
    description: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(text = label.uppercase(), style = StatLabelStyle, color = scheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(6.dp))
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = scheme.surfaceVariant,
            border = BorderStroke(1.dp, scheme.outline),
            modifier = Modifier.fillMaxWidth(),
        ) {
            BasicTextField(
                value = value,
                onValueChange = { onValueChange(it.replace('\n', ' ').take(maxLength)) },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                decorationBox = { field ->
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyLarge,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    field()
                },
            )
        }
        if (description != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

/** A hairline between rows inside a card. Thin, and the same thin everywhere. */
@Composable
fun RowDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/**
 * The one dialog in the app: "are you sure", for the things that cannot be undone.
 *
 * Used before deleting a profile, a HUD layout, a saved session and the whole history. Each of those is
 * the user's own work and nothing in GameCore keeps a copy, so the confirmation is not ceremony — there
 * is no undo behind it.
 *
 * [confirmLabel] says what will happen rather than "OK", because a dialog whose buttons are "OK" and
 * "Cancel" makes the user re-read the sentence to work out which one deletes their profile.
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = "Cancel",
    isDestructive: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(text = message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmLabel,
                    color = if (isDestructive) Tone.Danger.colour() else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
    )
}
