package com.gamecore.ui.plugins

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gamecore.core.common.TextSanitizer
import com.gamecore.core.plugin.settings.PluginSettingsSchema
import com.gamecore.core.plugin.settings.SettingDef
import com.gamecore.core.plugin.settings.SettingValue
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.RowDivider
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.SliderRow
import com.gamecore.ui.components.StepperRow
import com.gamecore.ui.components.SwitchRow
import com.gamecore.ui.components.Tone
import kotlinx.coroutines.delay

/**
 * The host controls that render one plugin's declared settings, on the plugin detail screen.
 *
 * A plugin is data: it draws nothing here and is never asked anything. The host renders a control for
 * each [SettingDef] the schema declares, seeded from [PluginDetailUiState.settingValues], and a change
 * calls back into the ViewModel which funnels the write through the Storage-gated settings surface. The
 * value that comes back is already coerced — a clamped number, a truncated string — so what a control
 * settles on is what the store actually holds, never what the user asked for.
 *
 * Two rules shape every control below:
 *
 *  1. **Disabled, not hidden, without Storage.** When `editable` is false every control is shown but
 *     inert, over its schema default, with one note saying what to grant. A settings write is refused
 *     by the gate without [com.gamecore.core.plugin.PluginPermission.STORE_DATA], and a control that
 *     looked live while every tap was silently denied would be the dishonesty the detail screen exists
 *     to avoid.
 *  2. **A plugin's words are data.** Setting ids and enum option names are the plugin's own strings and
 *     are rendered through [TextSanitizer] into plain `Text`. Nothing here turns a plugin string into
 *     markup, a link or an AnnotatedString.
 *
 * Numeric and text writes are debounced / committed on release rather than on every frame or keystroke,
 * so dragging a slider or typing a label does not fire a write — and a Keystore-backed store write —
 * per character and run into the surface's own write rate limit.
 */

/** Delay after the last keystroke before a text or free-form numeric setting is committed. */
private const val COMMIT_DEBOUNCE_MS = 400L

/** The one note shown when Storage is not granted, in the words the detail screen's decision fixed. */
private const val GRANT_STORAGE_NOTE = "Grant Storage to this plugin to change its settings."

@Composable
fun SettingsCard(
    schema: PluginSettingsSchema,
    values: Map<String, SettingValue>,
    editable: Boolean,
    onSet: (id: String, value: SettingValue) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Settings",
        modifier = modifier,
        subtitle = "Values this plugin keeps for itself. They stay inside its own storage.",
        icon = Icons.Filled.Tune,
    ) {
        when {
            schema.definitions.isEmpty() -> Text(
                text = "This plugin declares no settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> {
                if (!editable) NoteBanner(text = GRANT_STORAGE_NOTE, tone = Tone.Muted)
                schema.definitions.forEachIndexed { index, def ->
                    if (index > 0) RowDivider()
                    SettingControl(
                        def = def,
                        value = values[def.id] ?: def.default,
                        editable = editable,
                        onSet = onSet,
                    )
                }
            }
        }
    }
}

/** Dispatches one setting to the control for its type. The title is the sanitized setting id. */
@Composable
private fun SettingControl(
    def: SettingDef,
    value: SettingValue,
    editable: Boolean,
    onSet: (id: String, value: SettingValue) -> Unit,
) {
    val title = TextSanitizer.sanitizeName(def.id).ifBlank { "setting" }
    when (def) {
        is SettingDef.BoolDef -> BoolControl(def, title, value, editable, onSet)
        is SettingDef.IntDef -> IntControl(def, title, value, editable, onSet)
        is SettingDef.FloatDef -> FloatControl(def, title, value, editable, onSet)
        is SettingDef.EnumDef -> EnumControl(def, title, value, editable, onSet)
        is SettingDef.StringDef -> StringControl(def, title, value, editable, onSet)
    }
}

@Composable
private fun BoolControl(
    def: SettingDef.BoolDef,
    title: String,
    value: SettingValue,
    editable: Boolean,
    onSet: (id: String, value: SettingValue) -> Unit,
) {
    val checked = (value as? SettingValue.BoolValue)?.value ?: def.defaultValue
    SwitchRow(
        title = title,
        checked = checked,
        onCheckedChange = { onSet(def.id, SettingValue.BoolValue(it)) },
        enabled = editable,
    )
}

/**
 * A bounded int becomes a slider; an unbounded or Int-overflowing one becomes a stepper. The slider
 * keeps a local live value and commits only on release, so a drag is one write rather than hundreds.
 */
@Composable
private fun IntControl(
    def: SettingDef.IntDef,
    title: String,
    value: SettingValue,
    editable: Boolean,
    onSet: (id: String, value: SettingValue) -> Unit,
) {
    val current = (value as? SettingValue.IntValue)?.value ?: def.defaultValue
    val min = def.min
    val max = def.max
    val sliderable = min != null && max != null && max > min &&
        min >= Int.MIN_VALUE.toLong() && max <= Int.MAX_VALUE.toLong()
    if (sliderable) {
        var live by remember(current) { mutableStateOf(current.coerceIn(min!!, max!!).toInt()) }
        SliderRow(
            title = title,
            value = live,
            range = min!!.toInt()..max!!.toInt(),
            onValueChange = { live = it },
            valueLabel = live.toString(),
            enabled = editable,
            onValueChangeFinished = { onSet(def.id, SettingValue.IntValue(live.toLong())) },
        )
    } else {
        StepperRow(
            title = title,
            valueLabel = current.toString(),
            onStep = { step ->
                var next = current + step
                if (min != null && next < min) next = min
                if (max != null && next > max) next = max
                if (next != current) onSet(def.id, SettingValue.IntValue(next))
            },
            canDecrease = editable && (min == null || current > min),
            canIncrease = editable && (max == null || current < max),
        )
    }
}

/**
 * A bounded float becomes a slider committing on release; an unbounded one becomes a numeric field,
 * because a slider with no declared range has no honest extent to draw.
 */
@Composable
private fun FloatControl(
    def: SettingDef.FloatDef,
    title: String,
    value: SettingValue,
    editable: Boolean,
    onSet: (id: String, value: SettingValue) -> Unit,
) {
    val current = (value as? SettingValue.FloatValue)?.value ?: def.defaultValue
    val min = def.min
    val max = def.max
    if (min != null && max != null && max > min) {
        var live by remember(current) { mutableStateOf(current.coerceIn(min, max).toFloat()) }
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = formatFloat(live.toDouble()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = live,
                onValueChange = { live = it },
                valueRange = min.toFloat()..max.toFloat(),
                enabled = editable,
                onValueChangeFinished = { onSet(def.id, SettingValue.FloatValue(live.toDouble())) },
            )
        }
    } else {
        DebouncedTextControl(
            id = def.id,
            title = title,
            stored = formatFloat(current),
            editable = editable,
            maxLength = 32,
            keyboardType = KeyboardType.Number,
            parse = { raw -> raw.toDoubleOrNull()?.takeIf { it.isFinite() }?.let(SettingValue::FloatValue) },
            onSet = onSet,
        )
    }
}

/** An enum becomes a dropdown over its declared options; a pick commits immediately. */
@Composable
private fun EnumControl(
    def: SettingDef.EnumDef,
    title: String,
    value: SettingValue,
    editable: Boolean,
    onSet: (id: String, value: SettingValue) -> Unit,
) {
    val current = (value as? SettingValue.EnumValue)?.value ?: def.defaultValue
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = editable) {
                Text(text = TextSanitizer.sanitizeName(current).ifBlank { "-" })
                Icon(imageVector = Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                def.options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(TextSanitizer.sanitizeName(option).ifBlank { "-" }) },
                        onClick = {
                            expanded = false
                            if (option != current) onSet(def.id, SettingValue.EnumValue(option))
                        },
                    )
                }
            }
        }
    }
}

/** A string becomes a length-capped text field, committed a beat after the last keystroke. */
@Composable
private fun StringControl(
    def: SettingDef.StringDef,
    title: String,
    value: SettingValue,
    editable: Boolean,
    onSet: (id: String, value: SettingValue) -> Unit,
) {
    val current = (value as? SettingValue.StringValue)?.value ?: def.defaultValue
    DebouncedTextControl(
        id = def.id,
        title = title,
        stored = current,
        editable = editable,
        maxLength = def.maxLength.coerceAtLeast(0),
        keyboardType = KeyboardType.Text,
        parse = { raw -> SettingValue.StringValue(raw) },
        onSet = onSet,
    )
}

/**
 * A text field whose edits are committed [COMMIT_DEBOUNCE_MS] after the last keystroke, and only when
 * [parse] yields a value. Local state is re-seeded from [stored] whenever the stored value changes, so
 * when the host returns a coerced value (a truncated string, a clamped number) the field settles on it.
 */
@Composable
private fun DebouncedTextControl(
    id: String,
    title: String,
    stored: String,
    editable: Boolean,
    maxLength: Int,
    keyboardType: KeyboardType,
    parse: (String) -> SettingValue?,
    onSet: (id: String, value: SettingValue) -> Unit,
) {
    var text by remember(stored) { mutableStateOf(stored) }
    LaunchedEffect(text, editable, stored) {
        if (editable && text != stored) {
            delay(COMMIT_DEBOUNCE_MS)
            parse(text)?.let { onSet(id, it) }
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { if (it.length <= maxLength) text = it },
        label = { Text(title) },
        singleLine = true,
        enabled = editable,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    )
}

/** Renders a whole number without a trailing ".0", and other values as themselves. */
private fun formatFloat(v: Double): String =
    if (v.isFinite() && v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
