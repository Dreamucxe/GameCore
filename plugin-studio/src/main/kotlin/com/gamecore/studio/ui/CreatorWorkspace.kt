package com.gamecore.studio.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gamecore.studio.core.PackageInspector
import com.gamecore.studio.model.BoolSetting
import com.gamecore.studio.model.BrokerActionId
import com.gamecore.studio.model.BuiltinIcon
import com.gamecore.studio.model.CreatorCategory
import com.gamecore.studio.model.CreatorSections
import com.gamecore.studio.model.EnumSetting
import com.gamecore.studio.model.FloatSetting
import com.gamecore.studio.model.IntSetting
import com.gamecore.studio.model.NodeDraft
import com.gamecore.studio.model.NodeKind
import com.gamecore.studio.model.NodeSurface
import com.gamecore.studio.model.NodeValue
import com.gamecore.studio.model.Operator
import com.gamecore.studio.model.OverlayDraft
import com.gamecore.studio.model.OverlaySize
import com.gamecore.studio.model.PluginDraft
import com.gamecore.studio.model.RuleCondition
import com.gamecore.studio.model.RuleDraft
import com.gamecore.studio.model.RuleVariable
import com.gamecore.studio.model.ScreenDraft
import com.gamecore.studio.model.SettingDraft
import com.gamecore.studio.model.StatId
import com.gamecore.studio.model.StringSetting
import com.gamecore.studio.model.Trigger
import com.gamecore.studio.model.WidgetDraft
import com.gamecore.studio.model.WidgetSize
import java.util.Locale

/** Authoring controls for every non-automation section represented by [CreatorSections]. */
@Composable
internal fun CreatorBuildTab(
    draft: PluginDraft,
    commit: (PluginDraft) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        WorkspaceCard("Project category", "Studio organisation only") {
            Text(
                "Categories organise projects in Studio. They are not plugin manifest claims and grant no capability.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ChoiceStrip(
                values = CreatorCategory.entries,
                selected = draft.creatorCategory,
                label = { it.displayName() },
                onSelect = { commit(draft.copy(creatorCategory = it)) },
            )
        }

        SectionSummary(draft.sections)

        WorkspaceCard("Theme section", "The existing theme editor owns its design values") {
            SwitchLine(
                label = "Include theme.json",
                detail = "Turning this off preserves the draft theme but removes the theme declaration and entry.",
                checked = draft.sections.themeEnabled,
                onChange = { enabled -> commit(draft.editSections { copy(themeEnabled = enabled) }) },
            )
        }

        ScreensEditor(draft, commit)
        OverlayEditor(draft, commit)
        SettingsEditor(draft, commit)
        WidgetsEditor(draft, commit)
    }
}

/** Bounded authoring for the host's edge-triggered, flat-AND automation grammar. */
@Composable
internal fun AutomationStudioTab(
    draft: PluginDraft,
    commit: (PluginDraft) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedRule by rememberSaveable { mutableStateOf(0) }
    val rules = draft.sections.rules

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        WorkspaceCard("Automation contract", "Host-owned decisions and device actions") {
            Text(
                "Rules are edge-triggered and run only while GameCore's GameDetectionService runtime is active. " +
                    "Every action still requires Shizuku plus the relevant run_automations and system_actions approvals.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "The host schema has no schedules, branches, parallel blocks, delays, loops, or retries, so Studio " +
                    "does not offer controls that could not be exported.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        WorkspaceCard("Rules", "${rules.size}/${CreatorSections.MAX_RULES} rules") {
            Button(
                onClick = {
                    val next = rules + RuleDraft(
                        id = uniqueId("rule", rules.map { it.id }),
                        trigger = Trigger.GAME_LAUNCHED,
                        actionIds = listOf(BrokerActionId.SET_PEAK_REFRESH_RATE),
                    )
                    selectedRule = next.lastIndex
                    commit(draft.editSections { copy(rules = next) })
                },
                enabled = rules.size < CreatorSections.MAX_RULES,
            ) { Text("Add rule") }

            if (rules.isEmpty()) {
                MutedText("No rules. Add one to author an automation.")
            } else {
                ChoiceStrip(
                    values = rules.indices.toList(),
                    selected = selectedRule.coerceIn(rules.indices),
                    label = { rules[it].id.ifBlank { "Rule ${it + 1}" } },
                    onSelect = { selectedRule = it },
                )
                val index = selectedRule.coerceIn(rules.indices)
                val rule = rules[index]
                RuleEditor(
                    rule = rule,
                    onChange = { changed ->
                        commit(draft.editSections { copy(rules = rules.replaced(index, changed)) })
                    },
                    onDelete = {
                        val next = rules.toMutableList().also { it.removeAt(index) }
                        selectedRule = index.coerceAtMost((next.size - 1).coerceAtLeast(0))
                        commit(draft.editSections { copy(rules = next) })
                    },
                )
            }
        }

        WorkspaceCard("Fixed host action values", "Rules choose ids, never commands, keys, or values") {
            BrokerActionId.entries.forEach { action ->
                ActionMeaning(action)
            }
        }
    }
}

/** Validation, pure rule simulation, package inspection, and local capability observations. */
@Composable
internal fun CreatorTestTab(
    draft: PluginDraft,
    commit: (PluginDraft) -> Unit,
    modifier: Modifier = Modifier,
) {
    val inspection = remember(draft) { PackageInspector.inspect(draft) }
    val context = LocalContext.current
    val capabilities = remember(context) { inspectCapabilities(context.applicationContext) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ValidationReport(draft)
        SimulationSandbox(draft)

        WorkspaceCard("Package inspection", "The deterministic archive Studio would export") {
            inspection.entries.forEach { entry ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        entry.name,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text("${entry.bytes} B", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("Total: ${inspection.totalBytes} bytes", style = MaterialTheme.typography.bodyMedium)
            Text("SHA-256", style = MaterialTheme.typography.labelMedium)
            Text(
                inspection.sha256,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (inspection.issues.isEmpty()) {
                StatusText("No container issues found.", good = true)
            } else {
                inspection.issues.forEach { StatusText(it, good = false) }
            }
        }

        WorkspaceCard("Device capability explorer", "Local observations only; no probing services or network") {
            capabilities.forEach { CapabilityRow(it) }
            CapabilityRow(
                CapabilityObservation(
                    "Shizuku broker",
                    CapabilityState.RUNTIME,
                    "Checked by GameCore at runtime. The shared maker cannot bind it or claim a connection.",
                ),
            )
        }

        BoundaryCard(
            "No plugin-to-plugin communication",
            "The package schema has no messaging, discovery, shared-memory, or rule-to-rule call surface.",
        )
        BoundaryCard(
            "No release-key package signing",
            "Studio creates a deterministic .gcplugin data archive. It cannot access or sign with GameCore's release private key.",
        )
        BoundaryCard(
            "No interactive overlay or screens",
            "Overlay windows take no touches and full screens are read-only. Studio therefore authors no button, slider, or switch nodes.",
        )
        BoundaryCard(
            "No generic settings binding",
            "A setting cannot target arbitrary UI or system state. The sole schema bridge is a numeric setting named by glass.intensitySetting.",
        )
    }
}

// ----------------------------------------------------------------------------------------- Build

@Composable
private fun SectionSummary(sections: CreatorSections) {
    WorkspaceCard("Section plan", "Derived from authored content, not manually claimed") {
        SummaryLine("Manifest declares", sections.declaredSections())
        SummaryLine("Required permission requests", sections.requiredPermissions())
        SummaryLine("Required approved-data scopes", sections.requiredScopes())
        MutedText("These are requests GameCore and the user may approve or refuse; Studio grants nothing.")
    }
}

@Composable
private fun SummaryLine(label: String, values: Set<String>) {
    Text(label, style = MaterialTheme.typography.labelMedium)
    Text(
        values.sorted().takeIf { it.isNotEmpty() }?.joinToString() ?: "None",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ScreensEditor(draft: PluginDraft, commit: (PluginDraft) -> Unit) {
    var selectedScreen by rememberSaveable { mutableStateOf(0) }
    val screens = draft.sections.screens
    WorkspaceCard("Full screens", "${screens.size}/${CreatorSections.MAX_SCREENS} · read-only in GameCore") {
        Text(
            "GameCore renders full screens as read-only host UI. Components display content but cannot dispatch actions.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = {
                val next = screens + ScreenDraft(
                    id = uniqueId("screen", screens.map { it.id }),
                    title = "New screen",
                    icon = BuiltinIcon.INSIGHTS.id,
                    root = NodeDraft.column(),
                )
                selectedScreen = next.lastIndex
                commit(draft.editSections { copy(screens = next) })
            },
            enabled = screens.size < CreatorSections.MAX_SCREENS,
        ) { Text("Add screen") }

        if (screens.isNotEmpty()) {
            ChoiceStrip(
                screens.indices.toList(),
                selectedScreen.coerceIn(screens.indices),
                { screens[it].title.ifBlank { screens[it].id.ifBlank { "Screen ${it + 1}" } } },
                { selectedScreen = it },
            )
            val index = selectedScreen.coerceIn(screens.indices)
            val screen = screens[index]
            DeleteHeader("Screen ${index + 1}", "Delete screen") {
                val next = screens.toMutableList().also { it.removeAt(index) }
                selectedScreen = index.coerceAtMost((next.size - 1).coerceAtLeast(0))
                commit(draft.editSections { copy(screens = next) })
            }
            OutlinedTextField(
                value = screen.id,
                onValueChange = { value ->
                    commit(draft.editSections { copy(screens = screens.replaced(index, screen.copy(id = value))) })
                },
                label = { Text("Screen id") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = screen.title,
                onValueChange = { value ->
                    commit(draft.editSections { copy(screens = screens.replaced(index, screen.copy(title = value))) })
                },
                label = { Text("Screen title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Built-in icon", style = MaterialTheme.typography.labelMedium)
            ChoiceStrip(
                values = listOf<String?>(null) + BuiltinIcon.entries.map { it.id },
                selected = screen.icon,
                label = { it ?: "None" },
                onSelect = { icon ->
                    commit(draft.editSections { copy(screens = screens.replaced(index, screen.copy(icon = icon))) })
                },
            )
            FlatContentEditor(screen.root, NodeSurface.SCREEN) { root ->
                commit(draft.editSections { copy(screens = screens.replaced(index, screen.copy(root = root))) })
            }
        }
    }
}

@Composable
private fun OverlayEditor(draft: PluginDraft, commit: (PluginDraft) -> Unit) {
    val overlay = draft.sections.overlay
    WorkspaceCard("Overlay", "One per plugin; the host allows at most three active globally") {
        Text(
            "The runtime window is fixed-size and non-touchable. It cannot scroll or dispatch interaction; charts here are literal-only.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SwitchLine("Include overlay", null, overlay != null) { enabled ->
            val next = if (enabled) {
                OverlayDraft(
                    id = "performance-pill",
                    title = "Performance",
                    content = NodeDraft.column(listOf(NodeDraft.stat(StatId.FPS))),
                )
            } else null
            commit(draft.editSections { copy(overlay = next) })
        }
        overlay?.let { current ->
            fun update(next: OverlayDraft) = commit(draft.editSections { copy(overlay = next) })
            OutlinedTextField(
                current.id,
                { update(current.copy(id = it)) },
                label = { Text("Overlay id") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                current.title,
                { update(current.copy(title = it)) },
                label = { Text("Overlay title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ValueSlider(
                "Horizontal anchor ${(current.xFraction * 100).toInt()}%",
                current.xFraction.toFloat(),
                0f..1f,
                "Overlay horizontal anchor",
            ) { update(current.copy(xFraction = it.toDouble())) }
            ValueSlider(
                "Vertical anchor ${(current.yFraction * 100).toInt()}%",
                current.yFraction.toFloat(),
                0f..1f,
                "Overlay vertical anchor",
            ) { update(current.copy(yFraction = it.toDouble())) }
            Text("Fixed host size", style = MaterialTheme.typography.labelMedium)
            ChoiceStrip(OverlaySize.entries, current.size, { it.id }, { update(current.copy(size = it)) })
            ValueSlider(
                "Opacity ${current.opacity}%",
                current.opacity.toFloat(),
                OverlayDraft.MIN_OPACITY.toFloat()..OverlayDraft.MAX_OPACITY.toFloat(),
                "Overlay opacity",
                OverlayDraft.MAX_OPACITY - OverlayDraft.MIN_OPACITY - 1,
            ) { update(current.copy(opacity = it.toInt())) }
            Text("All eight instant overlay stats are available:", style = MaterialTheme.typography.labelSmall)
            MutedText(StatId.entries.joinToString { it.id })
            FlatContentEditor(current.content, NodeSurface.OVERLAY) { update(current.copy(content = it)) }
        }
    }
}

@Composable
private fun SettingsEditor(draft: PluginDraft, commit: (PluginDraft) -> Unit) {
    val settings = draft.sections.settings
    WorkspaceCard("Typed settings", "${settings.size}/${CreatorSections.MAX_SETTINGS}") {
        Text(
            "The host format has no title or description fields. GameCore displays a sanitized form of each id.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ChoiceStrip(
            values = SettingKind.entries,
            selected = null,
            label = { "Add ${it.label}" },
            onSelect = { kind ->
                if (settings.size < CreatorSections.MAX_SETTINGS) {
                    val id = uniqueId(kind.idPrefix, settings.map { it.id })
                    commit(draft.editSections { copy(settings = settings + kind.create(id)) })
                }
            },
        )
        settings.forEachIndexed { index, setting ->
            key(index, setting::class) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DeleteHeader("${index + 1}. ${setting.typeName()}", "Delete setting ${setting.id}") {
                            val next = settings.toMutableList().also { it.removeAt(index) }
                            val glass = draft.theme.glass
                            val nextTheme = if (glass?.intensitySetting?.trim() == setting.id.trim()) {
                                draft.theme.copy(glass = glass.copy(intensitySetting = null))
                            } else draft.theme
                            commit(draft.copy(sections = draft.sections.copy(settings = next), theme = nextTheme))
                        }
                        SettingEditor(
                            setting = setting,
                            glassAvailable = draft.theme.glass != null,
                            glassLinked = draft.theme.glass?.intensitySetting?.trim() == setting.id.trim(),
                            onChange = { changed ->
                                val linked = draft.theme.glass?.intensitySetting?.trim() == setting.id.trim()
                                val nextGlass = draft.theme.glass?.let { glass ->
                                    if (linked && changed.id != setting.id) glass.copy(intensitySetting = changed.id) else glass
                                }
                                commit(
                                    draft.copy(
                                        sections = draft.sections.copy(settings = settings.replaced(index, changed)),
                                        theme = draft.theme.copy(glass = nextGlass),
                                    ),
                                )
                            },
                            onToggleGlass = { linked ->
                                val glass = draft.theme.glass ?: return@SettingEditor
                                commit(
                                    draft.copy(
                                        theme = draft.theme.copy(
                                            glass = glass.copy(intensitySetting = if (linked) setting.id else null),
                                        ),
                                    ),
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingEditor(
    setting: SettingDraft,
    glassAvailable: Boolean,
    glassLinked: Boolean,
    onChange: (SettingDraft) -> Unit,
    onToggleGlass: (Boolean) -> Unit,
) {
    OutlinedTextField(
        value = setting.id,
        onValueChange = { id -> onChange(setting.withId(id)) },
        label = { Text("Setting id") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    when (setting) {
        is BoolSetting -> SwitchLine("Default value", null, setting.default) {
            onChange(setting.copy(default = it))
        }
        is IntSetting -> {
            LongField("Default", setting.default) { onChange(setting.copy(default = it)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NullableLongField("Minimum", setting.min, { onChange(setting.copy(min = it)) }, Modifier.weight(1f))
                NullableLongField("Maximum", setting.max, { onChange(setting.copy(max = it)) }, Modifier.weight(1f))
            }
        }
        is FloatSetting -> {
            DoubleField("Default", setting.default) { onChange(setting.copy(default = it)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NullableDoubleField("Minimum", setting.min, { onChange(setting.copy(min = it)) }, Modifier.weight(1f))
                NullableDoubleField("Maximum", setting.max, { onChange(setting.copy(max = it)) }, Modifier.weight(1f))
            }
        }
        is EnumSetting -> {
            EnumOptionsField(setting) { onChange(it) }
            Text("Default option", style = MaterialTheme.typography.labelMedium)
            ChoiceStrip(setting.options, setting.default, { it }, { onChange(setting.copy(default = it)) })
        }
        is StringSetting -> {
            OutlinedTextField(
                value = setting.default,
                onValueChange = { onChange(setting.copy(default = it.take(setting.maxLength.coerceAtLeast(1)))) },
                label = { Text("Default") },
                modifier = Modifier.fillMaxWidth(),
            )
            IntField("Maximum length (1–256)", setting.maxLength, 1..256) { length ->
                onChange(setting.copy(maxLength = length, default = setting.default.take(length)))
            }
        }
    }
    if (glassAvailable && (setting is IntSetting || setting is FloatSetting)) {
        SwitchLine(
            "Drive glass intensity",
            "The only generic setting bridge: theme.glass.intensitySetting → ${setting.id.ifBlank { "this id" }}.",
            glassLinked,
            onToggleGlass,
        )
    }
}

@Composable
private fun EnumOptionsField(setting: EnumSetting, onChange: (EnumSetting) -> Unit) {
    var typed by remember(setting.options) { mutableStateOf(setting.options.joinToString(", ")) }
    val parsed = typed.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(32)
    OutlinedTextField(
        value = typed,
        onValueChange = { value ->
            typed = value.take(2_000)
            val options = typed.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(32)
            if (options.isNotEmpty()) {
                onChange(setting.copy(options = options, default = setting.default.takeIf { it in options } ?: options.first()))
            }
        },
        label = { Text("Options (comma separated)") },
        supportingText = { Text("${parsed.size}/32 distinct options") },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun WidgetsEditor(draft: PluginDraft, commit: (PluginDraft) -> Unit) {
    var selectedWidget by rememberSaveable { mutableStateOf(0) }
    val widgets = draft.sections.widgets
    WorkspaceCard("Dashboard widgets", "${widgets.size}/${CreatorSections.MAX_WIDGETS} · non-interactive") {
        Text(
            "Widget sizes choose the host dashboard height footprint, not free canvas dimensions. The host draws widget content read-only, with no interaction.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = {
                val next = widgets + WidgetDraft(
                    uniqueId("widget", widgets.map { it.id }),
                    "New widget",
                    content = NodeDraft.column(listOf(NodeDraft.text("Widget"))),
                )
                selectedWidget = next.lastIndex
                commit(draft.editSections { copy(widgets = next) })
            },
            enabled = widgets.size < CreatorSections.MAX_WIDGETS,
        ) { Text("Add widget") }

        if (widgets.isNotEmpty()) {
            ChoiceStrip(
                widgets.indices.toList(),
                selectedWidget.coerceIn(widgets.indices),
                { widgets[it].title.ifBlank { widgets[it].id.ifBlank { "Widget ${it + 1}" } } },
                { selectedWidget = it },
            )
            val index = selectedWidget.coerceIn(widgets.indices)
            val widget = widgets[index]
            fun update(next: WidgetDraft) =
                commit(draft.editSections { copy(widgets = widgets.replaced(index, next)) })
            DeleteHeader("Widget ${index + 1}", "Delete widget") {
                val next = widgets.toMutableList().also { it.removeAt(index) }
                selectedWidget = index.coerceAtMost((next.size - 1).coerceAtLeast(0))
                commit(draft.editSections { copy(widgets = next) })
            }
            OutlinedTextField(
                widget.id,
                { update(widget.copy(id = it)) },
                label = { Text("Widget id") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                widget.title,
                { update(widget.copy(title = it)) },
                label = { Text("Widget title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Host height footprint", style = MaterialTheme.typography.labelMedium)
            ChoiceStrip(WidgetSize.entries, widget.size, { it.id }, { update(widget.copy(size = it)) })
            Text("Content outline", style = MaterialTheme.typography.labelMedium)
            nodeOutline(widget.content).forEach { MutedText(it) }
            FlatContentEditor(widget.content, NodeSurface.WIDGET) { content ->
                update(widget.copy(content = content))
            }
        }
    }
}

// ------------------------------------------------------------------------------- flat node editor

@Composable
private fun FlatContentEditor(root: NodeDraft, surface: NodeSurface, onChange: (NodeDraft) -> Unit) {
    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
    Text("Flat component editor", style = MaterialTheme.typography.titleSmall)
    MutedText(
        "Studio edits a COLUMN/CARD root and its direct children. Up/down changes the actual exported order; " +
            "the library intentionally has no button, slider, or switch nodes.",
    )
    if (root.kind !in setOf(NodeKind.COLUMN, NodeKind.CARD)) {
        StatusText("Imported root is ${root.kind.id}; convert it before editing this flat surface.", good = false)
        OutlinedButton(onClick = {
            onChange(
                if (root.kind.isContainer) root.copy(kind = NodeKind.COLUMN)
                else NodeDraft.column(listOf(root)),
            )
        }) { Text(if (root.kind.isContainer) "Convert root to column" else "Wrap root in column") }
        return
    }

    Text("Root container", style = MaterialTheme.typography.labelMedium)
    ChoiceStrip(
        listOf(NodeKind.COLUMN, NodeKind.CARD),
        root.kind,
        { it.id },
        { onChange(root.copy(kind = it)) },
    )
    LayoutPropsEditor(root, onChange)
    if (root.kind == NodeKind.CARD) ColorPropEditor(root, onChange)

    Text("Add a direct child", style = MaterialTheme.typography.labelMedium)
    val addKinds = listOf(NodeKind.TEXT, NodeKind.STAT, NodeKind.IMAGE, NodeKind.CHART, NodeKind.CARD, NodeKind.SPACER)
    val childLimit = if (surface == NodeSurface.SCREEN) 100 else 12
    ChoiceStrip(
        addKinds,
        selected = null,
        label = { if (it == NodeKind.CHART) "literal chart" else it.id },
        onSelect = { kind ->
            if (root.children.size < childLimit) onChange(root.copy(children = root.children + newNode(kind, surface)))
        },
    )
    Text("${root.children.size}/$childLimit direct children", style = MaterialTheme.typography.labelSmall)

    root.children.forEachIndexed { index, child ->
        key(index, child.kind) {
            NodeChildEditor(
                node = child,
                index = index,
                surface = surface,
                canMoveUp = index > 0,
                canMoveDown = index < root.children.lastIndex,
                onChange = { changed -> onChange(root.copy(children = root.children.replaced(index, changed))) },
                onMoveUp = { onChange(root.copy(children = root.children.moved(index, index - 1))) },
                onMoveDown = { onChange(root.copy(children = root.children.moved(index, index + 1))) },
                onDelete = { onChange(root.copy(children = root.children.removed(index))) },
            )
        }
    }
}

@Composable
private fun NodeChildEditor(
    node: NodeDraft,
    index: Int,
    surface: NodeSurface,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onChange: (NodeDraft) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}. ${node.kind.id}", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                IconButton(onMoveUp, enabled = canMoveUp) {
                    Icon(Icons.Filled.ArrowUpward, contentDescription = "Move ${node.kind.id} up")
                }
                IconButton(onMoveDown, enabled = canMoveDown) {
                    Icon(Icons.Filled.ArrowDownward, contentDescription = "Move ${node.kind.id} down")
                }
                IconButton(onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete ${node.kind.id}")
                }
            }
            when (node.kind) {
                NodeKind.TEXT -> {
                    val value = node.textProp("text") ?: ""
                    OutlinedTextField(
                        value,
                        { onChange(node.withProp("text", NodeValue.Text(it.take(surface.stringCap())))) },
                        label = { Text("Text") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ColorPropEditor(node, onChange)
                }
                NodeKind.STAT -> {
                    val allowed = if (surface == NodeSurface.OVERLAY) StatId.entries else StatId.entries.take(6)
                    ChoiceStrip(
                        allowed,
                        allowed.firstOrNull { it.id == node.textProp("stat") },
                        { it.id },
                        { onChange(node.withProp("stat", NodeValue.Text(it.id))) },
                    )
                    ColorPropEditor(node, onChange)
                }
                NodeKind.IMAGE -> ChoiceStrip(
                    BuiltinIcon.entries,
                    BuiltinIcon.entries.firstOrNull { it.id == node.textProp("icon") },
                    { it.id },
                    { onChange(node.withProp("icon", NodeValue.Text(it.id))) },
                )
                NodeKind.CHART -> {
                    ChoiceStrip(
                        listOf("line", "bar"),
                        node.textProp("kind") ?: "line",
                        { it },
                        { onChange(node.withProp("kind", NodeValue.Text(it))) },
                    )
                    if (node.props.containsKey("stat") || node.props.containsKey("stat2")) {
                        StatusText("This imported chart is live. Entering values converts it to a literal chart.", false)
                    }
                    LiteralSeriesField(node.textProp("values").orEmpty(), surface.stringCap()) { values ->
                        onChange(node.withoutProps("stat", "stat2").withProp("values", NodeValue.Text(values)))
                    }
                    ColorPropEditor(node, onChange)
                }
                NodeKind.CARD -> {
                    LayoutPropsEditor(node, onChange)
                    ColorPropEditor(node, onChange)
                    val textIndex = node.children.indexOfFirst { it.kind == NodeKind.TEXT }
                    if (textIndex >= 0) {
                        val textNode = node.children[textIndex]
                        OutlinedTextField(
                            textNode.textProp("text").orEmpty(),
                            { value ->
                                onChange(
                                    node.copy(
                                        children = node.children.replaced(
                                            textIndex,
                                            textNode.withProp("text", NodeValue.Text(value.take(surface.stringCap()))),
                                        ),
                                    ),
                                )
                            },
                            label = { Text("Card text") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        MutedText("Nested imported content is preserved but not edited by this flat editor.")
                    }
                }
                NodeKind.SPACER -> {
                    val size = node.numberProp("size").coerceIn(0.0, 96.0).toFloat()
                    ValueSlider("Size ${size.toInt()} dp", size, 0f..96f, "Spacer size", 95) {
                        onChange(node.withProp("size", NodeValue.Number(it.toInt())))
                    }
                }
                NodeKind.COLUMN, NodeKind.ROW, NodeKind.GRID, NodeKind.SCROLL -> {
                    MutedText("Imported ${node.kind.id} content is preserved. This flat editor only authors direct simple children.")
                    LayoutPropsEditor(node, onChange)
                }
            }
        }
    }
}

@Composable
private fun LayoutPropsEditor(node: NodeDraft, onChange: (NodeDraft) -> Unit) {
    listOf("padding" to 64, "gap" to 48).forEach { (key, max) ->
        val current = node.numberProp(key).coerceIn(0.0, max.toDouble()).toFloat()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${key.displayName()} ${current.toInt()} dp", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            if (key in node.props) TextButton(onClick = { onChange(node.withoutProps(key)) }) { Text("Clear") }
        }
        Slider(
            value = current,
            onValueChange = { onChange(node.withProp(key, NodeValue.Number(it.toInt()))) },
            valueRange = 0f..max.toFloat(),
            steps = max - 1,
            modifier = Modifier.semantics { contentDescription = "${key.displayName()} in dp" },
        )
    }
}

@Composable
private fun ColorPropEditor(node: NodeDraft, onChange: (NodeDraft) -> Unit) {
    val current = (node.props["color"] as? NodeValue.Color)?.argb
    Text("Color", style = MaterialTheme.typography.labelMedium)
    ChoiceStrip(
        values = NODE_COLORS,
        selected = current,
        label = { it.second },
        value = { it.first },
        onSelect = { onChange(node.withProp("color", NodeValue.Color(it.first))) },
        leading = {
            FilterChip(
                selected = current == null,
                onClick = { onChange(node.withoutProps("color")) },
                label = { Text("Theme default") },
            )
        },
    )
}

@Composable
private fun LiteralSeriesField(value: String, maxLength: Int, onChange: (String) -> Unit) {
    var typed by remember(value) { mutableStateOf(value) }
    val valid = validSeries(typed, maxLength)
    OutlinedTextField(
        value = typed,
        onValueChange = { input ->
            typed = input.take(maxLength)
            if (validSeries(typed, maxLength)) onChange(typed)
        },
        label = { Text("Literal values") },
        placeholder = { Text("0, 25, 50, 25, 0") },
        supportingText = { Text(if (valid) "Finite numbers separated by commas or spaces" else "Enter 1–120 finite numbers") },
        isError = !valid,
        modifier = Modifier.fillMaxWidth(),
    )
}

// ------------------------------------------------------------------------------------ Automation

@Composable
private fun RuleEditor(rule: RuleDraft, onChange: (RuleDraft) -> Unit, onDelete: () -> Unit) {
    DeleteHeader("Rule editor", "Delete rule ${rule.id}", onDelete)
    OutlinedTextField(
        rule.id,
        { onChange(rule.copy(id = it)) },
        label = { Text("Rule id") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Text("Exact trigger", style = MaterialTheme.typography.labelMedium)
    ChoiceStrip(Trigger.entries, rule.trigger, { it.name }, { onChange(rule.copy(trigger = it)) })

    HorizontalDivider()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Conditions (${rule.conditions.size}/10, flat AND)", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        TextButton(
            onClick = {
                onChange(rule.copy(conditions = rule.conditions + RuleCondition(RuleVariable.CPU, Operator.GTE, 0.0)))
            },
            enabled = rule.conditions.size < RuleDraft.MAX_CONDITIONS,
        ) { Text("Add condition") }
    }
    rule.conditions.forEachIndexed { index, condition ->
        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DeleteHeader("Condition ${index + 1}", "Delete condition ${index + 1}") {
                    onChange(rule.copy(conditions = rule.conditions.removed(index)))
                }
                ChoiceStrip(
                    RuleVariable.entries,
                    condition.variable,
                    { it.id },
                    { onChange(rule.copy(conditions = rule.conditions.replaced(index, condition.copy(variable = it)))) },
                )
                ChoiceStrip(
                    Operator.entries,
                    condition.op,
                    { it.name },
                    { onChange(rule.copy(conditions = rule.conditions.replaced(index, condition.copy(op = it)))) },
                )
                DoubleField("Finite comparison value", condition.value) { value ->
                    onChange(rule.copy(conditions = rule.conditions.replaced(index, condition.copy(value = value))))
                }
            }
        }
    }

    HorizontalDivider()
    Text("Sequential actions (${rule.actionIds.size}/8)", style = MaterialTheme.typography.titleSmall)
    MutedText("Tap an allowlisted id to append it. Repeated ids are visible to the conflict check.")
    ChoiceStrip(
        BrokerActionId.entries,
        selected = null,
        label = { it.id },
        onSelect = { action ->
            if (rule.actionIds.size < RuleDraft.MAX_ACTIONS) onChange(rule.copy(actionIds = rule.actionIds + action))
        },
    )
    if (rule.actionIds.isEmpty()) StatusText("Add at least one action.", false)
    rule.actionIds.forEachIndexed { index, action ->
        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}. ${action.id}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    IconButton(
                        onClick = { onChange(rule.copy(actionIds = rule.actionIds.moved(index, index - 1))) },
                        enabled = index > 0,
                    ) { Icon(Icons.Filled.ArrowUpward, contentDescription = "Move ${action.id} up") }
                    IconButton(
                        onClick = { onChange(rule.copy(actionIds = rule.actionIds.moved(index, index + 1))) },
                        enabled = index < rule.actionIds.lastIndex,
                    ) { Icon(Icons.Filled.ArrowDownward, contentDescription = "Move ${action.id} down") }
                    IconButton(
                        onClick = { onChange(rule.copy(actionIds = rule.actionIds.removed(index))) },
                        enabled = rule.actionIds.size > 1,
                    ) { Icon(Icons.Filled.Delete, contentDescription = "Delete ${action.id}") }
                }
                MutedText(action.meaning())
            }
        }
    }
}

@Composable
private fun ActionMeaning(action: BrokerActionId) {
    Text(action.id, style = MaterialTheme.typography.labelMedium)
    MutedText(action.meaning())
}

// ------------------------------------------------------------------------------------------ Test

@Composable
private fun ValidationReport(draft: PluginDraft) {
    val grouped = remember(draft) { draft.problems().groupBy { it.field }.toSortedMap() }
    WorkspaceCard("Validation report", "Grouped by the exact draft field") {
        if (grouped.isEmpty()) {
            StatusText("No validation problems.", true)
        } else {
            grouped.forEach { (field, problems) ->
                Text(field, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                problems.forEach { Text("• ${it.message}", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun SimulationSandbox(draft: PluginDraft) {
    var trigger by rememberSaveable { mutableStateOf(Trigger.GAME_LAUNCHED) }
    var values by remember {
        mutableStateOf(
            mapOf(
                RuleVariable.FPS to 60.0,
                RuleVariable.CPU to 30.0,
                RuleVariable.GPU to 30.0,
                RuleVariable.RAM to 50.0,
                RuleVariable.BATTERY to 80.0,
                RuleVariable.TEMP to 35.0,
            ),
        )
    }
    var available by remember { mutableStateOf(RuleVariable.entries.associateWith { true }) }
    var result by remember { mutableStateOf<SimulationResult?>(null) }

    LaunchedEffect(draft.sections.rules, trigger, values, available) { result = null }
    val input = values.filterKeys { available[it] == true }
    val conflicts = remember(draft.sections.rules, available) { staticConflicts(draft.sections.rules, available) }

    WorkspaceCard("SIMULATION — no device actions run", "Pure local comparison against the draft") {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f),
        ) {
            Text(
                "This sandbox never calls the host broker, shell, intents, reflection, or network.",
                modifier = Modifier.padding(10.dp),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
            )
        }
        Text("Observed trigger", style = MaterialTheme.typography.labelMedium)
        ChoiceStrip(Trigger.entries, trigger, { it.name }, { trigger = it })
        Text(
            "Unavailable means omitted, never zero. GameCore may omit FPS, CPU, GPU, or temperature when no real reading exists; all inputs are absent before a performance snapshot.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RuleVariable.entries.forEach { variable ->
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SwitchLine("${variable.id} input available", null, available[variable] == true) { enabled ->
                        available = available + (variable to enabled)
                    }
                    DoubleField(variable.id, values.getValue(variable)) { value ->
                        values = values + (variable to value)
                    }
                }
            }
        }
        Button(
            onClick = { result = evaluateRules(draft.sections.rules, trigger, input) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Evaluate rules locally") }
        result?.let { evaluated ->
            Text("Matched rule ids", style = MaterialTheme.typography.labelMedium)
            Text(evaluated.matches.joinToString().ifEmpty { "None" }, style = MaterialTheme.typography.bodySmall)
            Text("Planned action ids", style = MaterialTheme.typography.labelMedium)
            Text(evaluated.actions.joinToString().ifEmpty { "None" }, style = MaterialTheme.typography.bodySmall)
        }

        HorizontalDivider()
        Text("Static conflict observations", style = MaterialTheme.typography.titleSmall)
        MutedText("Derived only from rule data and the availability switches above; no device state is inferred.")
        if (conflicts.isEmpty()) StatusText("No observed conflicts.", true)
        conflicts.forEach { conflict ->
            Text(
                "${conflict.level}: ${conflict.message}",
                style = MaterialTheme.typography.bodySmall,
                color = if (conflict.level == "Info") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun evaluateRules(
    rules: List<RuleDraft>,
    trigger: Trigger,
    values: Map<RuleVariable, Double>,
): SimulationResult {
    val matching = rules.filter { rule ->
        rule.trigger == trigger && rule.conditions.all { condition ->
            val actual = values[condition.variable] ?: return@all false
            when (condition.op) {
                Operator.EQ -> actual == condition.value
                Operator.NEQ -> actual != condition.value
                Operator.LT -> actual < condition.value
                Operator.LTE -> actual <= condition.value
                Operator.GT -> actual > condition.value
                Operator.GTE -> actual >= condition.value
            }
        }
    }
    return SimulationResult(matching.map { it.id }, matching.flatMap { it.actionIds.map(BrokerActionId::id) })
}

private fun staticConflicts(
    rules: List<RuleDraft>,
    available: Map<RuleVariable, Boolean>,
): List<ConflictObservation> = buildList {
    rules.groupBy { rule ->
        rule.trigger.name + ":" + rule.conditions
            .map { "${it.variable.id}:${it.op.name}:${it.value}" }
            .sorted()
            .joinToString("|")
    }.values.filter { it.size > 1 }.forEach { duplicates ->
        add(ConflictObservation("Warning", "Rules ${duplicates.joinToString { it.id }} share one trigger and the same flat conditions."))
    }
    rules.forEach { rule ->
        val duplicateActions = rule.actionIds.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (duplicateActions.isNotEmpty()) {
            add(ConflictObservation("Warning", "${rule.id}: duplicate actions ${duplicateActions.joinToString { it.id }}."))
        }
        if (BrokerActionId.SET_PEAK_REFRESH_RATE in rule.actionIds &&
            BrokerActionId.SET_MIN_REFRESH_RATE in rule.actionIds
        ) {
            add(ConflictObservation("Info", "${rule.id}: peak and minimum refresh are both requested; both fixed host values are 120 Hz."))
        }
        val missing = rule.conditions.map { it.variable }.distinct().filter { available[it] != true }
        if (missing.isNotEmpty()) {
            add(ConflictObservation("Warning", "${rule.id}: simulated input unavailable for ${missing.joinToString { it.id }}; that condition cannot match."))
        }
    }
}

private fun inspectCapabilities(context: Context): List<CapabilityObservation> {
    val manager = context.packageManager
    fun feature(name: String, label: String): CapabilityObservation = try {
        if (manager.hasSystemFeature(name)) {
            CapabilityObservation(label, CapabilityState.SUPPORTED, "Reported by PackageManager.")
        } else CapabilityObservation(label, CapabilityState.UNAVAILABLE, "Not reported by PackageManager.")
    } catch (_: SecurityException) {
        CapabilityObservation(label, CapabilityState.UNAVAILABLE, "The local feature check was blocked.")
    }
    val overlay = try {
        if (Settings.canDrawOverlays(context)) {
            CapabilityObservation("Current host overlay access", CapabilityState.SUPPORTED, "Settings.canDrawOverlays returned true.")
        } else {
            CapabilityObservation("Current host overlay access", CapabilityState.PERMISSION, "Display-over-other-apps access is not granted to this host app.")
        }
    } catch (_: SecurityException) {
        CapabilityObservation("Current host overlay access", CapabilityState.UNAVAILABLE, "The local overlay-access check was blocked.")
    }
    val installed = try {
        val packages = listOf(GAMECORE_RELEASE, GAMECORE_DEBUG).filter { manager.isInstalled(it) }
        if (packages.isEmpty()) {
            CapabilityObservation("GameCore installation", CapabilityState.UNAVAILABLE, "Neither release nor debug package is installed or visible.")
        } else CapabilityObservation("GameCore installation", CapabilityState.SUPPORTED, packages.joinToString())
    } catch (_: SecurityException) {
        CapabilityObservation("GameCore installation", CapabilityState.UNAVAILABLE, "The package query was blocked.")
    }
    return listOf(
        CapabilityObservation("Android API", CapabilityState.SUPPORTED, "SDK ${Build.VERSION.SDK_INT}"),
        overlay,
        feature(PackageManager.FEATURE_SENSOR_ACCELEROMETER, "Accelerometer"),
        feature(PackageManager.FEATURE_SENSOR_GYROSCOPE, "Gyroscope"),
        installed,
    )
}

@Suppress("DEPRECATION")
private fun PackageManager.isInstalled(packageName: String): Boolean = try {
    getPackageInfo(packageName, 0)
    true
} catch (_: PackageManager.NameNotFoundException) {
    false
}

@Composable
private fun CapabilityRow(observation: CapabilityObservation) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(modifier = Modifier.weight(1f)) {
            Text(observation.name, style = MaterialTheme.typography.bodyMedium)
            MutedText(observation.detail)
        }
        Text(
            observation.state.label,
            style = MaterialTheme.typography.labelMedium,
            color = when (observation.state) {
                CapabilityState.SUPPORTED -> MaterialTheme.colorScheme.secondary
                CapabilityState.PERMISSION -> MaterialTheme.colorScheme.primary
                CapabilityState.UNAVAILABLE -> MaterialTheme.colorScheme.error
                CapabilityState.RUNTIME -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

// ------------------------------------------------------------------------------------- UI pieces

@Composable
private fun WorkspaceCard(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                subtitle?.let { MutedText(it) }
            }
            content()
        }
    }
}

@Composable
private fun BoundaryCard(title: String, detail: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.padding(start = 10.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                MutedText(detail)
            }
        }
    }
}

@Composable
private fun <T> ChoiceStrip(
    values: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) = ChoiceStrip(values, selected, label, { it }, onSelect)

@Composable
private fun <T, S> ChoiceStrip(
    values: List<T>,
    selected: S?,
    label: (T) -> String,
    value: (T) -> S,
    onSelect: (T) -> Unit,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        values.forEach { item ->
            FilterChip(selected = selected == value(item), onClick = { onSelect(item) }, label = { Text(label(item)) })
        }
    }
}

@Composable
private fun DeleteHeader(title: String, description: String, onDelete: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = description) }
    }
}

@Composable
private fun SwitchLine(
    label: String,
    detail: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            detail?.let { MutedText(it) }
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

@Composable
private fun ValueSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    description: String,
    steps: Int = 0,
    onChange: (Float) -> Unit,
) {
    Text(label, style = MaterialTheme.typography.labelMedium)
    Slider(
        value = value.coerceIn(range.start, range.endInclusive),
        onValueChange = onChange,
        valueRange = range,
        steps = steps,
        modifier = Modifier.semantics { contentDescription = description },
    )
}

@Composable
private fun DoubleField(
    label: String,
    value: Double,
    modifier: Modifier = Modifier,
    onChange: (Double) -> Unit,
) {
    var typed by remember(value) { mutableStateOf(finiteText(value)) }
    val parsed = typed.toDoubleOrNull()?.takeIf { it.isFinite() }
    OutlinedTextField(
        value = typed,
        onValueChange = { input ->
            typed = input
            input.toDoubleOrNull()?.takeIf { it.isFinite() }?.let(onChange)
        },
        label = { Text(label) },
        isError = parsed == null,
        supportingText = { if (parsed == null) Text("Enter a finite number") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun NullableDoubleField(
    label: String,
    value: Double?,
    onChange: (Double?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var typed by remember(value) { mutableStateOf(value?.let(::finiteText).orEmpty()) }
    val valid = typed.isBlank() || typed.toDoubleOrNull()?.isFinite() == true
    OutlinedTextField(
        value = typed,
        onValueChange = { input ->
            typed = input
            if (input.isBlank()) onChange(null) else input.toDoubleOrNull()?.takeIf { it.isFinite() }?.let(onChange)
        },
        label = { Text(label) },
        isError = !valid,
        supportingText = { Text(if (valid) "Blank = no bound" else "Enter a finite number") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun LongField(label: String, value: Long, onChange: (Long) -> Unit) {
    var typed by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = typed,
        onValueChange = { input ->
            typed = input
            input.toLongOrNull()?.let(onChange)
        },
        label = { Text(label) },
        isError = typed.toLongOrNull() == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NullableLongField(
    label: String,
    value: Long?,
    onChange: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var typed by remember(value) { mutableStateOf(value?.toString().orEmpty()) }
    val valid = typed.isBlank() || typed.toLongOrNull() != null
    OutlinedTextField(
        value = typed,
        onValueChange = { input ->
            typed = input
            if (input.isBlank()) onChange(null) else input.toLongOrNull()?.let(onChange)
        },
        label = { Text(label) },
        isError = !valid,
        supportingText = { Text(if (valid) "Blank = no bound" else "Enter a whole number") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun IntField(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    var typed by remember(value) { mutableStateOf(value.toString()) }
    val parsed = typed.toIntOrNull()
    OutlinedTextField(
        value = typed,
        onValueChange = { input ->
            typed = input
            input.toIntOrNull()?.takeIf { it in range }?.let(onChange)
        },
        label = { Text(label) },
        isError = parsed !in range,
        supportingText = { if (parsed !in range) Text("Enter ${range.first}–${range.last}") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun MutedText(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun StatusText(text: String, good: Boolean) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (good) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
    )
}

// ----------------------------------------------------------------------------------------- Models

private enum class SettingKind(val label: String, val idPrefix: String) {
    BOOL("boolean", "toggle"),
    INT("integer", "amount"),
    FLOAT("float", "intensity"),
    ENUM("enum", "choice"),
    STRING("string", "text"),
    ;

    fun create(id: String): SettingDraft = when (this) {
        BOOL -> BoolSetting(id)
        INT -> IntSetting(id, default = 50, min = 0, max = 100)
        FLOAT -> FloatSetting(id, default = 0.5, min = 0.0, max = 1.0)
        ENUM -> EnumSetting(id, listOf("option_a", "option_b"), "option_a")
        STRING -> StringSetting(id, maxLength = 64)
    }
}

private enum class CapabilityState(val label: String) {
    SUPPORTED("Supported"),
    PERMISSION("Permission required"),
    UNAVAILABLE("Unavailable"),
    RUNTIME("Checked by GameCore at runtime"),
}

private data class CapabilityObservation(val name: String, val state: CapabilityState, val detail: String)
private data class SimulationResult(val matches: List<String>, val actions: List<String>)
private data class ConflictObservation(val level: String, val message: String)

private fun newNode(kind: NodeKind, surface: NodeSurface): NodeDraft = when (kind) {
    NodeKind.TEXT -> NodeDraft.text("Text")
    NodeKind.STAT -> NodeDraft.stat(if (surface == NodeSurface.OVERLAY) StatId.FPS else StatId.FPS)
    NodeKind.IMAGE -> NodeDraft.image(BuiltinIcon.INFO)
    NodeKind.CHART -> NodeDraft.chart(values = "0, 25, 50, 25, 0", props = mapOf("kind" to NodeValue.Text("line")))
    NodeKind.CARD -> NodeDraft.card(listOf(NodeDraft.text("Card")))
    NodeKind.SPACER -> NodeDraft.spacer(12.0)
    NodeKind.COLUMN -> NodeDraft.column()
    NodeKind.ROW -> NodeDraft.row()
    NodeKind.GRID -> NodeDraft.grid()
    NodeKind.SCROLL -> NodeDraft.scroll()
}

private fun SettingDraft.withId(id: String): SettingDraft = when (this) {
    is BoolSetting -> copy(id = id)
    is IntSetting -> copy(id = id)
    is FloatSetting -> copy(id = id)
    is EnumSetting -> copy(id = id)
    is StringSetting -> copy(id = id)
}

private fun SettingDraft.typeName(): String = when (this) {
    is BoolSetting -> "Boolean"
    is IntSetting -> "Integer"
    is FloatSetting -> "Float"
    is EnumSetting -> "Enum"
    is StringSetting -> "String"
}

private fun BrokerActionId.meaning(): String = when (this) {
    BrokerActionId.SET_PEAK_REFRESH_RATE -> "Host value 120 Hz — set the display peak refresh bound."
    BrokerActionId.SET_MIN_REFRESH_RATE -> "Host value 120 Hz — set the display minimum refresh bound."
    BrokerActionId.SET_WINDOW_ANIMATION_SCALE -> "Host value 0 — disable the window animation scale."
    BrokerActionId.SET_TRANSITION_ANIMATION_SCALE -> "Host value 0 — disable the transition animation scale."
    BrokerActionId.SET_ANIMATOR_DURATION_SCALE -> "Host value 0 — disable the animator duration scale."
    BrokerActionId.SET_LOW_POWER -> "Host value 1 — turn Android battery saver on."
    BrokerActionId.SET_SCREEN_BRIGHTNESS -> "Host value 200 (raw 0–255) — raise brightness; GameCore auto-reverts it."
}

private fun CreatorCategory.displayName(): String = name.lowercase(Locale.US).replace('_', ' ')
    .replaceFirstChar { it.titlecase(Locale.US) }

private fun String.displayName(): String = replaceFirstChar { it.titlecase(Locale.US) }

private fun PluginDraft.editSections(change: CreatorSections.() -> CreatorSections): PluginDraft =
    copy(sections = sections.change())

private fun NodeDraft.textProp(key: String): String? = (props[key] as? NodeValue.Text)?.value
private fun NodeDraft.numberProp(key: String): Double = (props[key] as? NodeValue.Number)?.value?.takeIf { it.isFinite() } ?: 0.0
private fun NodeDraft.withProp(key: String, value: NodeValue): NodeDraft = copy(props = props + (key to value))
private fun NodeDraft.withoutProps(vararg keys: String): NodeDraft = copy(props = props - keys.toSet())

private fun NodeSurface.stringCap(): Int = if (this == NodeSurface.SCREEN) 2_000 else 120

private fun validSeries(raw: String, maxLength: Int): Boolean {
    if (raw.isBlank() || raw.length > maxLength) return false
    val tokens = raw.trim().split(Regex("[,;\\s]+"))
    return tokens.size in 1..120 && tokens.all { it.toDoubleOrNull()?.isFinite() == true }
}

private fun finiteText(value: Double): String = if (value.isFinite()) value.toString() else ""

private fun uniqueId(prefix: String, existing: List<String>): String {
    val used = existing.mapTo(hashSetOf()) { it.trim().lowercase(Locale.US) }
    if (prefix !in used) return prefix
    var suffix = 2
    while ("$prefix-$suffix" in used) suffix++
    return "$prefix-$suffix"
}

private fun <T> List<T>.replaced(index: Int, value: T): List<T> = toMutableList().also { it[index] = value }
private fun <T> List<T>.removed(index: Int): List<T> = toMutableList().also { it.removeAt(index) }
private fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from !in indices || to !in indices || from == to) return this
    return toMutableList().also { list -> list.add(to, list.removeAt(from)) }
}

private fun nodeOutline(root: NodeDraft): List<String> {
    val result = mutableListOf<String>()
    val pending = ArrayDeque<Pair<NodeDraft, Int>>()
    pending.add(root to 0)
    while (pending.isNotEmpty() && result.size < 40) {
        val (node, depth) = pending.removeFirst()
        result += "${"  ".repeat(depth.coerceAtMost(8))}• ${node.kind.id}"
        if (depth < 8) node.children.forEach { pending.add(it to depth + 1) }
    }
    if (pending.isNotEmpty()) result += "… more nodes preserved"
    return result
}

private val NODE_COLORS = listOf(
    0xFFFFFFFFL to "White",
    0xFF7C5CFFL to "Violet",
    0xFF2BD4C0L to "Teal",
    0xFFFFC46BL to "Amber",
    0xFFFF6B6BL to "Red",
)

private const val GAMECORE_RELEASE = "com.gamecore"
private const val GAMECORE_DEBUG = "com.gamecore.debug"
