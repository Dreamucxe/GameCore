package com.gamecore.studio.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gamecore.studio.core.ColorMath
import com.gamecore.studio.core.ColorMemory
import com.gamecore.studio.core.ContrastCheck
import com.gamecore.studio.core.DesignWarning
import com.gamecore.studio.core.DraftStore
import com.gamecore.studio.core.ImportOutcome
import com.gamecore.studio.core.ImportReport
import com.gamecore.studio.core.PackageInspection
import com.gamecore.studio.core.PackageInspector
import com.gamecore.studio.core.PluginComposer
import com.gamecore.studio.core.PluginImporter
import com.gamecore.studio.core.Severity
import com.gamecore.studio.core.StudioProject
import com.gamecore.studio.core.StudioTemplate
import com.gamecore.studio.core.Templates
import com.gamecore.studio.core.ThemeAnalysis
import com.gamecore.studio.model.ColorFieldSpec
import com.gamecore.studio.model.DraftProblem
import com.gamecore.studio.model.GlassDraft
import com.gamecore.studio.model.GlitchDraft
import com.gamecore.studio.model.PluginDraft
import com.gamecore.studio.model.ThemeDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * The editor behind both the standalone Plugin Maker and GameCore's own Create plugin entry.
 *
 * The phone-sized shape is six focused workspaces rather than the three-panel desktop layout a
 * Figma-like tool would use: Build owns the real package sections; Theme owns the palette,
 * shape, type and effects; Logic owns bounded host automations; Preview owns the device simulator;
 * Test owns validation and simulation; Export owns inspection and save/import. A tab is a finite
 * thing to scroll, and it keeps a 360dp screen from trying to be a 1440dp one.
 *
 * Studio authors the sections GameCore actually consumes: theme, screens, one bounded overlay,
 * typed settings, dashboard widgets and automation rules. It deliberately omits interactive
 * plugin controls, arbitrary code and unsupported workflow nodes rather than drawing controls
 * that disappear or become inert after export.
 */
@Composable
internal fun StudioEditor(
    draft: PluginDraft,
    onDraftChange: (PluginDraft) -> Unit,
    modifier: Modifier = Modifier,
    onSaved: (fileName: String) -> Unit = {},
    /** Offered only by a host that is not already GameCore; null hides the jump button. */
    onOpenGameCore: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val store = remember(context) { DraftStore(context) }
    val memory = remember(context) { ColorMemory(context) }
    val scope = rememberCoroutineScope()

    var tab by rememberSaveable { mutableStateOf(StudioTab.BUILD) }
    var undo by remember { mutableStateOf<List<PluginDraft>>(emptyList()) }
    var redo by remember { mutableStateOf<List<PluginDraft>>(emptyList()) }
    var currentProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    var currentProjectName by rememberSaveable { mutableStateOf<String?>(null) }
    var projectsOpen by remember { mutableStateOf(false) }
    var colorTarget by remember { mutableStateOf<ColorTarget?>(null) }
    var recents by remember { mutableStateOf(memory.recent()) }
    var report by remember { mutableStateOf<ImportReport?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    var savedNote by remember { mutableStateOf<String?>(null) }
    var restored by remember { mutableStateOf(false) }
    var restoreNote by remember { mutableStateOf<String?>(null) }

    fun commit(next: PluginDraft) {
        if (next == draft) return
        undo = (undo + draft).takeLast(60)
        redo = emptyList()
        onDraftChange(next)
    }

    // Draft recovery: only when the host handed us a pristine draft, so a caller that restored
    // its own draft (or a test that passed one in) is never overwritten by stale autosave.
    LaunchedEffect(Unit) {
        if (draft == PluginDraft()) {
            val remembered = store.lastOpened() ?: store.list().firstOrNull()
            if (remembered != null) {
                onDraftChange(remembered.draft)
                currentProjectId = remembered.id
                currentProjectName = remembered.name
                restoreNote = "Restored ${remembered.name} from the last session."
            }
        }
        restored = true
    }

    // Autosave debounces keystrokes. A draft is a few kilobytes of JSON; the point of the delay is
    // not cost but not rewriting the preference on every character.
    LaunchedEffect(draft, restored) {
        if (!restored) return@LaunchedEffect
        delay(800)
        val saved = store.save(draft, currentProjectId, currentProjectName)
        currentProjectId = saved.id
        currentProjectName = saved.name
        store.rememberLastOpened(saved.id)
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = PluginComposer.packageBytes(draft)
        val result = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: error("The file picker returned a location that cannot be written.")
        }
        result.onSuccess {
            savedNote = "Saved ${draft.fileName}.gcplugin — ${bytes.size} bytes."
            onSaved(draft.fileName)
        }.onFailure {
            savedNote = "Could not save the package: ${it.message ?: "unknown error"}"
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { readAtMost(it, MAX_IMPORT_BYTES) }
                        ?.let(PluginImporter::import)
                }.getOrElse { failure ->
                    ImportOutcome.Failure(failure.message ?: "The package could not be read.")
                }
            }
            when (outcome) {
                null -> importError = "The picker returned an empty file."
                is ImportOutcome.Failure -> importError = outcome.message
                is ImportOutcome.Success -> {
                    undo = (undo + draft).takeLast(60)
                    redo = emptyList()
                    onDraftChange(outcome.draft)
                    currentProjectId = null
                    currentProjectName = null
                    report = outcome.report
                    tab = StudioTab.BUILD
                    savedNote = "Imported ${outcome.draft.name}. Nothing has been installed."
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        StudioTopBar(
            draft = draft,
            canUndo = undo.isNotEmpty(),
            canRedo = redo.isNotEmpty(),
            onUndo = {
                val previous = undo.lastOrNull()
                if (previous != null) {
                    undo = undo.dropLast(1)
                    redo = (redo + draft).takeLast(60)
                    onDraftChange(previous)
                }
            },
            onRedo = {
                val next = redo.lastOrNull()
                if (next != null) {
                    redo = redo.dropLast(1)
                    undo = (undo + draft).takeLast(60)
                    onDraftChange(next)
                }
            },
            onProjects = { projectsOpen = true },
            onClose = onClose,
        )
        ScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 8.dp) {
            StudioTab.entries.forEach { item ->
                Tab(
                    selected = tab == item,
                    onClick = { tab = item },
                    text = { Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }

        val contentModifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)

        when (tab) {
            StudioTab.BUILD -> CreatorBuildTab(
                draft = draft,
                commit = ::commit,
                modifier = contentModifier,
            )
            StudioTab.DESIGN -> DesignTab(
                draft = draft,
                commit = ::commit,
                onPickColor = { colorTarget = it },
                modifier = contentModifier,
            )
            StudioTab.PREVIEW -> Column(
                modifier = contentModifier,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DevicePreviewStudio(theme = draft.theme)
            }
            StudioTab.LOGIC -> AutomationStudioTab(
                draft = draft,
                commit = ::commit,
                modifier = contentModifier,
            )
            StudioTab.TEST -> Column(
                modifier = contentModifier,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CreatorTestTab(draft = draft, commit = ::commit)
                CheckTab(draft = draft, commit = ::commit)
            }
            StudioTab.EXPORT -> ExportTab(
                draft = draft,
                inspection = remember(draft) { PackageInspector.inspect(draft) },
                savedNote = savedNote,
                restoreNote = restoreNote,
                onSave = { saveLauncher.launch("${draft.fileName}.gcplugin") },
                onImport = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                onOpenGameCore = onOpenGameCore,
                modifier = contentModifier,
            )
        }
    }

    colorTarget?.let { target ->
        AdvancedColorPickerDialog(
            target = target,
            themeName = draft.theme.name.ifBlank { "Theme" },
            palette = draft.theme.palette(),
            memory = memory,
            recents = recents,
            onColorChange = { color -> commit(draft.withThemeColor(target.key, color)) },
            onApplyPalette = { colors -> commit(draft.withPalette(colors)) },
            onRemember = { color ->
                memory.rememberRecent(color)
                recents = memory.recent()
            },
            onDismiss = { colorTarget = null },
        )
    }

    if (projectsOpen) {
        ProjectsDialog(
            store = store,
            currentDraft = draft,
            currentId = currentProjectId,
            onOpen = { project ->
                currentProjectId = project.id
                currentProjectName = project.name
                undo = (undo + draft).takeLast(60)
                redo = emptyList()
                onDraftChange(project.draft)
                projectsOpen = false
            },
            onNew = { project ->
                currentProjectId = project.id
                currentProjectName = project.name
                undo = (undo + draft).takeLast(60)
                redo = emptyList()
                onDraftChange(project.draft)
                projectsOpen = false
            },
            onDismiss = { projectsOpen = false },
        )
    }

    report?.let { imported ->
        ImportReportDialog(report = imported, onDismiss = { report = null })
    }
    importError?.let { message ->
        AlertDialog(
            onDismissRequest = { importError = null },
            title = { Text("Could not import") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { importError = null }) { Text("Close") } },
        )
    }
}

// --------------------------------------------------------------------------------- the shell

private enum class StudioTab(val label: String) {
    BUILD("Build"),
    DESIGN("Theme"),
    LOGIC("Logic"),
    PREVIEW("Preview"),
    TEST("Test"),
    EXPORT("Export"),
}

@Composable
private fun StudioTopBar(
    draft: PluginDraft,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onProjects: () -> Unit,
    onClose: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Plugin Studio", style = MaterialTheme.typography.titleLarge)
            Text(
                draft.name.ifBlank { "Untitled plugin" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onUndo, enabled = canUndo) {
            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
        }
        IconButton(onClick = onRedo, enabled = canRedo) {
            Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
        }
        IconButton(onClick = onProjects) {
            Icon(Icons.Filled.FolderOpen, contentDescription = "Projects and drafts")
        }
        if (onClose != null) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close Plugin Studio")
            }
        }
    }
}

@Composable
private fun DesignTab(
    draft: PluginDraft,
    commit: (PluginDraft) -> Unit,
    onPickColor: (ColorTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = draft.theme
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TemplateStrip(onApply = { template ->
            commit(draft.copy(theme = template.theme.copy(name = theme.name)))
        })

        StudioCard(
            title = "Palette",
            subtitle = "Thirteen real theme roles. Tap one to open the wheel.",
            icon = Icons.Filled.Palette,
        ) {
            ThemeDraft.COLOR_FIELDS.forEach { spec ->
                ColorRow(
                    spec = spec,
                    value = theme.colorFor(spec.key),
                    backdrop = theme.backdropFor(spec.key),
                    onOpen = {
                        onPickColor(
                            ColorTarget(
                                key = spec.key,
                                label = spec.label,
                                color = theme.colorFor(spec.key),
                                backdrop = theme.backdropFor(spec.key),
                            ),
                        )
                    },
                )
            }
        }

        StudioCard(title = "Shape and type", subtitle = "What the components are made of") {
            LabelledSlider(
                label = "Corner radius ${theme.cornerRadiusDp} dp",
                value = theme.cornerRadiusDp.toFloat(),
                range = ThemeDraft.MIN_CORNER_DP.toFloat()..ThemeDraft.MAX_CORNER_DP.toFloat(),
                steps = ThemeDraft.MAX_CORNER_DP - ThemeDraft.MIN_CORNER_DP - 1,
            ) { commit(draft.copy(theme = theme.copy(cornerRadiusDp = it.toInt()))) }
            LabelledSlider(
                label = "UI scale ${"%.2f".format(theme.uiScale)}×",
                value = theme.uiScale,
                range = ThemeDraft.MIN_UI_SCALE..ThemeDraft.MAX_UI_SCALE,
            ) { commit(draft.copy(theme = theme.copy(uiScale = it))) }
            LabelledSlider(
                label = "Type ramp ${"%.2f".format(theme.typographyScale)}×",
                value = theme.typographyScale,
                range = ThemeDraft.MIN_TYPO_SCALE..ThemeDraft.MAX_TYPO_SCALE,
            ) { commit(draft.copy(theme = theme.copy(typographyScale = it))) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Dark theme", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Flips the host's own relative colours in the preview and in GameCore.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = theme.dark,
                    onCheckedChange = { commit(draft.copy(theme = theme.copy(dark = it))) },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("AMOLED black", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Lets the host collapse true black for an OLED panel.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = theme.amoled,
                    onCheckedChange = { commit(draft.copy(theme = theme.copy(amoled = it))) },
                )
            }

            Text("Typeface", style = MaterialTheme.typography.labelMedium)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ThemeDraft.KNOWN_FONTS.forEach { font ->
                    FilterChip(
                        selected = theme.font == font,
                        onClick = { commit(draft.copy(theme = theme.copy(font = font))) },
                        label = { Text(ThemeDraft.FONT_NAMES[font] ?: font) },
                    )
                }
            }
            Text(
                "Bundled faces appear in the preview as a close platform family; the installed app " +
                    "draws the real bundled file.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Global weight", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ThemeDraft.KNOWN_WEIGHTS.forEach { weight ->
                    FilterChip(
                        selected = theme.typographyWeight == weight,
                        onClick = { commit(draft.copy(theme = theme.copy(typographyWeight = weight))) },
                        label = { Text(weight.replaceFirstChar { it.uppercase() }) },
                    )
                }
            }
            OutlinedTextField(
                value = theme.name,
                onValueChange = { commit(draft.copy(theme = theme.copy(name = it))) },
                label = { Text("Theme name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        EffectsCard(draft = draft, commit = commit)

        ExpandableCard(
            title = "Identity",
            subtitle = "The package's name, id and version",
            initiallyExpanded = false,
            icon = Icons.Filled.Info,
        ) {
            TextField(
                value = draft.name,
                onChange = { commit(draft.copy(name = it)) },
                label = "Plugin name",
                problem = draft.problems().firstOrNull { it.field == "name" },
            )
            TextField(
                value = draft.id,
                onChange = { commit(draft.copy(id = it)) },
                label = "Plugin id",
                problem = draft.problems().firstOrNull { it.field == "id" },
            )
            TextButton(
                onClick = { commit(draft.copy(id = suggestId(draft.name))) },
            ) { Text("Suggest an id from the name") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(
                    label = "Version number",
                    value = draft.version,
                    min = 1,
                    max = Int.MAX_VALUE,
                    onChange = { commit(draft.copy(version = it)) },
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = draft.versionName,
                    onValueChange = { commit(draft.copy(versionName = it)) },
                    label = { Text("Version name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            TextField(
                value = draft.developer,
                onChange = { commit(draft.copy(developer = it)) },
                label = "Developer",
                problem = draft.problems().firstOrNull { it.field == "developer" },
            )
            TextField(
                value = draft.description,
                onChange = { commit(draft.copy(description = it)) },
                label = "Description",
                problem = draft.problems().firstOrNull { it.field == "description" },
                singleLine = false,
            )
            TextField(
                value = draft.changelog,
                onChange = { commit(draft.copy(changelog = it)) },
                label = "Changelog",
                problem = null,
                singleLine = false,
            )
        }

        ExpandableCard(
            title = "Permissions",
            subtitle = "${draft.effectivePermissions().size} requested · nothing is granted by asking",
            initiallyExpanded = false,
            icon = Icons.Filled.Check,
        ) {
            Text(
                "The package may ask for these. It arrives switched off, and every one is approved " +
                    "on the plugin's own screen in GameCore.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PluginDraft.KNOWN_PERMISSIONS.forEach { permission ->
                val required = permission.id in draft.sections.requiredPermissions()
                val asked = permission.id in draft.effectivePermissions()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Switch(
                        checked = asked,
                        enabled = !required,
                        onCheckedChange = { checked ->
                            val next = draft.permissions.toMutableSet()
                            val scopes = draft.permissionScopes.toMutableMap()
                            if (checked) {
                                next += permission.id
                            } else {
                                next -= permission.id
                                scopes -= permission.id
                            }
                            commit(draft.copy(permissions = next, permissionScopes = scopes))
                        },
                    )
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text(
                            permission.title + if (required) " · required by content" else "",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            permission.rationale,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (permission.id == PluginDraft.PERMISSION_READ_APPROVED_DATA && asked) {
                    val requiredScopes = draft.sections.requiredScopes()
                    val selectedScopes = draft.effectivePermissionScopes()[permission.id].orEmpty()
                    Text("Approved-data scopes", style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        PluginDraft.KNOWN_DATA_SCOPES.forEach { scope ->
                            val scopeRequired = scope in requiredScopes
                            FilterChip(
                                selected = scope in selectedScopes,
                                enabled = !scopeRequired,
                                onClick = {
                                    val nextPermissions = draft.permissions + permission.id
                                    val nextScopes = draft.permissionScopes.toMutableMap()
                                    val values = nextScopes[permission.id].orEmpty().toMutableSet()
                                    if (scope in values) values -= scope else values += scope
                                    if (values.isEmpty()) nextScopes -= permission.id
                                    else nextScopes[permission.id] = values
                                    commit(
                                        draft.copy(
                                            permissions = nextPermissions,
                                            permissionScopes = nextScopes,
                                        ),
                                    )
                                },
                                label = {
                                    Text(scope.replace('_', ' ') + if (scopeRequired) " · required" else "")
                                },
                            )
                        }
                    }
                }
            }
        }

        Text(
            "The Build and Logic tabs author GameCore's real screens, overlay, settings, widgets " +
                "and automation sections. Plugin-provided assets, sounds, arbitrary animations and " +
                "interactive screen or overlay controls are not offered because the current host " +
                "has no complete execution path for them.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EffectsCard(draft: PluginDraft, commit: (PluginDraft) -> Unit) {
    val theme = draft.theme
    StudioCard(
        title = "Material and motion",
        subtitle = "The schema-4 glass block and the schema-3 glitch block",
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Glass material", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Transparency, gloss, a rim and an optional sheen. A real backdrop blur needs " +
                        "Android 12; below it the host still draws translucency and the rim.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = theme.glass != null,
                onCheckedChange = { on ->
                    commit(draft.copy(theme = theme.copy(glass = if (on) GlassDraft() else null)))
                },
            )
        }
        theme.glass?.let { glass ->
            LabelledSlider(
                label = "Glass intensity ${(glass.intensity * 100).toInt()}%",
                value = glass.intensity,
                range = GlassDraft.MIN_FRACTION..GlassDraft.MAX_FRACTION,
            ) { commit(draft.copy(theme = theme.copy(glass = glass.copy(intensity = it)))) }
            LabelledSlider(
                label = "Transparency ${(glass.transparency * 100).toInt()}%",
                value = glass.transparency,
                range = GlassDraft.MIN_FRACTION..GlassDraft.MAX_FRACTION,
            ) { commit(draft.copy(theme = theme.copy(glass = glass.copy(transparency = it)))) }
            LabelledSlider(
                label = "Gloss ${(glass.gloss * 100).toInt()}%",
                value = glass.gloss,
                range = GlassDraft.MIN_FRACTION..GlassDraft.MAX_FRACTION,
            ) { commit(draft.copy(theme = theme.copy(glass = glass.copy(gloss = it)))) }
            LabelledSlider(
                label = "Blur ${glass.blurRadiusDp} dp",
                value = glass.blurRadiusDp.toFloat(),
                range = GlassDraft.MIN_BLUR_DP.toFloat()..GlassDraft.MAX_BLUR_DP.toFloat(),
                steps = GlassDraft.MAX_BLUR_DP - GlassDraft.MIN_BLUR_DP - 1,
            ) { commit(draft.copy(theme = theme.copy(glass = glass.copy(blurRadiusDp = it.toInt())))) }
            LabelledSlider(
                label = "Sheen period ${glass.sheenPeriodMs} ms",
                value = glass.sheenPeriodMs.toFloat(),
                range = GlassDraft.MIN_SHEEN_MS.toFloat()..GlassDraft.MAX_SHEEN_MS.toFloat(),
            ) { commit(draft.copy(theme = theme.copy(glass = glass.copy(sheenPeriodMs = it.toInt())))) }
            SwitchRow(
                label = "Drift a sheen across the glass",
                checked = glass.sheen,
                onChange = { commit(draft.copy(theme = theme.copy(glass = glass.copy(sheen = it)))) },
            )
            SwitchRow(
                label = "Let a hot device thin the material",
                checked = glass.hardwareReactive,
                onChange = { commit(draft.copy(theme = theme.copy(glass = glass.copy(hardwareReactive = it)))) },
            )
            Text("Spring", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GlassDraft.KNOWN_SPRINGS.forEach { spring ->
                    FilterChip(
                        selected = glass.spring == spring,
                        onClick = { commit(draft.copy(theme = theme.copy(glass = glass.copy(spring = spring)))) },
                        label = { Text(spring.replaceFirstChar { it.uppercase() }) },
                    )
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Glitch motion", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "The host owns every frame, and a user's animations-off setting overrides it. " +
                        "This is a request for distortion, not a script.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = theme.glitch != null,
                onCheckedChange = { on ->
                    commit(draft.copy(theme = theme.copy(glitch = if (on) GlitchDraft() else null)))
                },
            )
        }
        theme.glitch?.let { glitch ->
            LabelledSlider(
                label = "Intensity ${(glitch.intensity * 100).toInt()}%",
                value = glitch.intensity,
                range = GlitchDraft.MIN_INTENSITY..GlitchDraft.MAX_INTENSITY,
            ) { commit(draft.copy(theme = theme.copy(glitch = glitch.copy(intensity = it)))) }
            LabelledSlider(
                label = "Slices ${glitch.slices}",
                value = glitch.slices.toFloat(),
                range = GlitchDraft.MIN_SLICES.toFloat()..GlitchDraft.MAX_SLICES.toFloat(),
                steps = GlitchDraft.MAX_SLICES - GlitchDraft.MIN_SLICES - 1,
            ) { commit(draft.copy(theme = theme.copy(glitch = glitch.copy(slices = it.toInt())))) }
            LabelledSlider(
                label = "Slice offset ${glitch.sliceOffsetDp} dp",
                value = glitch.sliceOffsetDp.toFloat(),
                range = GlitchDraft.MIN_SLICE_OFFSET_DP.toFloat()..GlitchDraft.MAX_SLICE_OFFSET_DP.toFloat(),
            ) { commit(draft.copy(theme = theme.copy(glitch = glitch.copy(sliceOffsetDp = it.toInt())))) }
            LabelledSlider(
                label = "Ghost offset ${glitch.ghostOffsetDp} dp",
                value = glitch.ghostOffsetDp.toFloat(),
                range = GlitchDraft.MIN_GHOST_OFFSET_DP.toFloat()..GlitchDraft.MAX_GHOST_OFFSET_DP.toFloat(),
            ) { commit(draft.copy(theme = theme.copy(glitch = glitch.copy(ghostOffsetDp = it.toInt())))) }
            LabelledSlider(
                label = "Burst gap ${glitch.burstMinMs}–${glitch.burstMaxMs} ms",
                value = glitch.burstMinMs.toFloat(),
                range = GlitchDraft.MIN_BURST_MS.toFloat()..glitch.burstMaxMs.toFloat(),
            ) {
                commit(
                    draft.copy(
                        theme = theme.copy(
                            glitch = glitch.copy(burstMinMs = it.toInt().coerceAtMost(glitch.burstMaxMs)),
                        ),
                    ),
                )
            }
            LabelledSlider(
                label = "Longest gap ${glitch.burstMaxMs} ms",
                value = glitch.burstMaxMs.toFloat(),
                range = glitch.burstMinMs.toFloat()..GlitchDraft.MAX_BURST_MS.toFloat(),
            ) {
                commit(
                    draft.copy(
                        theme = theme.copy(
                            glitch = glitch.copy(burstMaxMs = it.toInt().coerceAtLeast(glitch.burstMinMs)),
                        ),
                    ),
                )
            }
            SwitchRow("Fracture lines", glitch.fractureLines) {
                commit(draft.copy(theme = theme.copy(glitch = glitch.copy(fractureLines = it))))
            }
            SwitchRow("Block noise", glitch.noise) {
                commit(draft.copy(theme = theme.copy(glitch = glitch.copy(noise = it))))
            }
            SwitchRow("Micro-jitter between bursts", glitch.microJitter) {
                commit(draft.copy(theme = theme.copy(glitch = glitch.copy(microJitter = it))))
            }
            SwitchRow("React to device load", glitch.hardwareReactive) {
                commit(draft.copy(theme = theme.copy(glitch = glitch.copy(hardwareReactive = it))))
            }
        }
    }
}

@Composable
private fun CheckTab(
    draft: PluginDraft,
    commit: (PluginDraft) -> Unit,
    modifier: Modifier = Modifier,
) {
    val checks = remember(draft.theme) { ThemeAnalysis.contrastChecks(draft.theme) }
    val warnings = remember(draft.theme) { ThemeAnalysis.warnings(draft.theme) }
    val score = remember(draft.theme) { ThemeAnalysis.score(draft.theme) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StudioCard(title = "Design check", subtitle = "Measured from the theme's own values") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$score", style = MaterialTheme.typography.displaySmall)
                Text(
                    "/100",
                    modifier = Modifier.padding(start = 2.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    LinearProgressIndicator(
                        progress = { score / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Contrast, ink opacity and scale checks only. A theme has no component tree, " +
                            "so touch targets and spacing are the host's job, not something this " +
                            "screen can honestly measure.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text("Contrast (WCAG)", style = MaterialTheme.typography.titleSmall)
            checks.forEach { check -> ContrastRow(check) }
            Text("Observations", style = MaterialTheme.typography.titleSmall)
            if (warnings.isEmpty()) {
                Text(
                    "No warnings from the theme's values.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            } else {
                warnings.forEach { warning -> WarningRow(warning) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val theme = draft.theme
                        commit(
                            draft.copy(
                                theme = theme.copy(
                                    onPrimary = ColorMath.readableOn(theme.primary),
                                    onSurface = ColorMath.readableOn(theme.surface),
                                    onSurfaceVariant = ColorMath.readableOn(theme.surfaceVariant),
                                    onError = ColorMath.readableOn(theme.error),
                                ),
                            ),
                        )
                    },
                ) { Text("Fix the inks to black or white") }
            }
        }

        StudioCard(title = "Colour vision", subtitle = "How the main roles read with common colour-vision types") {
            val roles = listOf(
                "Primary" to draft.theme.primary,
                "Accent" to draft.theme.accent,
                "Error" to draft.theme.error,
                "Surface" to draft.theme.surface,
            )
            roles.forEach { (name, value) ->
                Text(name, style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VisionCell("None", value)
                    com.gamecore.studio.core.ColorVision.entries.forEach { vision ->
                        VisionCell(vision.name.lowercase().replaceFirstChar { it.uppercase() }, ColorMath.simulate(value, vision))
                    }
                }
            }
        }
    }
}

@Composable
private fun ExportTab(
    draft: PluginDraft,
    inspection: PackageInspection,
    savedNote: String?,
    restoreNote: String?,
    onSave: () -> Unit,
    onImport: () -> Unit,
    onOpenGameCore: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val problems = draft.problems()
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        restoreNote?.let { note ->
            NoteCard(note, tone = MaterialTheme.colorScheme.secondary)
        }
        StudioCard(title = "Package inspector", subtitle = "Exactly what the zip will contain") {
            Text(
                if (problems.isEmpty()) {
                    "The draft passes every rule the host checks."
                } else {
                    "The draft is not exportable yet. ${problems.size} problem" +
                        (if (problems.size == 1) "" else "s") + " listed below."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (problems.isEmpty()) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            if (problems.isNotEmpty()) {
                problems.forEach { problem -> ProblemText("${problem.field}: ${problem.message}") }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            inspection.entries.forEach { entry ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(entry.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text("${entry.bytes} B", style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("Total", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text("${inspection.totalBytes} bytes", style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                "Declared sections: ${draft.sections.declaredSections().joinToString().ifEmpty { "none" }}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (draft.sections.themeEnabled) {
                Text("Theme schema ${draft.theme.schema}", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "SHA-256",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                inspection.sha256,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (inspection.issues.isNotEmpty()) {
                inspection.issues.forEach { issue -> ProblemText(issue) }
            }
            Text(
                "The checksum is of the deterministic zip. The same draft produces the same bytes " +
                    "on every run; changing one colour changes the hash.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Button(
            onClick = onSave,
            enabled = problems.isEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Save .gcplugin", modifier = Modifier.padding(start = 8.dp))
        }
        OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Import an existing package", modifier = Modifier.padding(start = 8.dp))
        }
        if (onOpenGameCore != null) {
            OutlinedButton(onClick = onOpenGameCore, modifier = Modifier.fillMaxWidth()) {
                Text("Open GameCore")
            }
        }
        savedNote?.let { note -> NoteCard(note, tone = MaterialTheme.colorScheme.secondary) }
        StudioCard(title = "What export does and does not carry", subtitle = "The honest boundary") {
            Text(
                "A package contains manifest.json plus only the enabled theme, screens, overlay, " +
                    "settings, widget and rules files listed above. It carries no executable code, " +
                    "and asking for a permission grants nothing — the plugin arrives switched off.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Studio does not package arbitrary assets, sounds, scripts, touchable overlays, " +
                    "interactive plugin-screen controls, scheduled or branching workflows, or a " +
                    "release signing key. Import refuses profiles, Aim Lab, stats, icon, asset and " +
                    "other unsupported files rather than silently deleting them on re-export.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// --------------------------------------------------------------------------------- pieces

@Composable
private fun TemplateStrip(onApply: (StudioTemplate) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Start from a template", style = MaterialTheme.typography.titleMedium)
        Text(
            "A template replaces the theme; the package identity and permissions stay yours.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(Templates.ALL, key = { it.id }) { template ->
                TemplateCard(template = template, onClick = { onApply(template) })
            }
        }
    }
}

@Composable
private fun TemplateCard(template: StudioTemplate, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .width(168.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column {
            Row(modifier = Modifier.height(46.dp)) {
                template.palette.take(6).forEach { color ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .background(color.toThemeColor()),
                    )
                }
            }
            Column(modifier = Modifier.padding(10.dp)) {
                Text(template.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    template.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun StudioCard(
    title: String,
    subtitle: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(20.dp),
                    )
                }
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            content()
        }
    }
}

@Composable
private fun ExpandableCard(
    title: String,
    subtitle: String? = null,
    initiallyExpanded: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(20.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(if (expanded) "Hide" else "Edit", style = MaterialTheme.typography.labelMedium)
            }
            if (expanded) {
                Column(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun ColorRow(
    spec: ColorFieldSpec,
    value: Long,
    backdrop: Long,
    onOpen: () -> Unit,
) {
    val ratio = ColorMath.contrastRatio(value, backdrop)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(value.toThemeColor(), RoundedCornerShape(10.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(spec.label, style = MaterialTheme.typography.bodyMedium)
            Text(
                spec.description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(ColorMath.hex(value), style = MaterialTheme.typography.labelMedium)
            if (ratio < ThemeAnalysis.AA) {
                Text(
                    "${"%.2f".format(ratio)}:1",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ContrastRow(check: ContrastCheck) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(check.label, style = MaterialTheme.typography.bodySmall)
            Text(
                "${"%.2f".format(check.ratio)}:1 · ${if (check.meetsAAA) "AAA" else if (check.meetsAA) "AA" else "below AA"}",
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    check.meetsAAA -> MaterialTheme.colorScheme.secondary
                    check.meetsAA -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.error
                },
            )
        }
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(check.foreground.toThemeColor(), RoundedCornerShape(6.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp)),
        )
        Box(
            modifier = Modifier
                .padding(start = 4.dp)
                .size(28.dp)
                .background(check.background.toThemeColor(), RoundedCornerShape(6.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp)),
        )
    }
}

@Composable
private fun WarningRow(warning: DesignWarning) {
    val color = when (warning.severity) {
        Severity.ERROR -> MaterialTheme.colorScheme.error
        Severity.WARNING -> MaterialTheme.colorScheme.primary
        Severity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        Icon(
            Icons.Filled.Warning,
            contentDescription = null,
            tint = color,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(16.dp),
        )
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(warning.title, style = MaterialTheme.typography.bodySmall, color = color)
            Text(
                warning.detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VisionCell(label: String, color: Long) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(color.toThemeColor(), RoundedCornerShape(8.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
        )
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun NoteCard(text: String, tone: Color) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = tone.copy(alpha = 0.14f),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Info, contentDescription = null, tint = tone, modifier = Modifier.size(18.dp))
            Text(text, modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ProblemText(message: String) {
    Text(message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun LabelledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onChange: (Float) -> Unit,
) {
    Text(label, style = MaterialTheme.typography.labelMedium)
    Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun TextField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    problem: DraftProblem?,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        isError = problem != null,
        supportingText = {
            if (problem != null) Text(problem.message, color = MaterialTheme.colorScheme.error)
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NumberField(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { typed ->
            typed.toIntOrNull()?.takeIf { it in min..max }?.let(onChange)
        },
        label = { Text(label) },
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
    )
}

// --------------------------------------------------------------------------------- projects

@Composable
private fun ProjectsDialog(
    store: DraftStore,
    currentDraft: PluginDraft,
    currentId: String?,
    onOpen: (StudioProject) -> Unit,
    onNew: (StudioProject) -> Unit,
    onDismiss: () -> Unit,
) {
    var projects by remember { mutableStateOf(store.list()) }
    fun refresh() {
        projects = store.list()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Projects") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Every project is saved on this device as the draft's JSON, not as a package. " +
                        "The editor autosaves the open project after you stop typing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val created = store.save(
                                PluginDraft(),
                                id = "project-${UUID.randomUUID()}",
                                name = "Untitled plugin",
                            )
                            refresh()
                            onNew(created)
                        },
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("New", modifier = Modifier.padding(start = 4.dp))
                    }
                    OutlinedButton(
                        onClick = {
                            val saved = store.save(currentDraft, currentId)
                            refresh()
                            onOpen(saved)
                        },
                    ) {
                        Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("Save current", modifier = Modifier.padding(start = 4.dp))
                    }
                }
                HorizontalDivider()
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (projects.isEmpty()) {
                        Text("No saved projects yet.", style = MaterialTheme.typography.bodySmall)
                    }
                    projects.forEach { project ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onOpen(project) },
                            ) {
                                Text(
                                    project.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (project.id == currentId) FontWeight.Bold else FontWeight.Normal,
                                )
                                Text(
                                    "${project.draft.theme.name.ifBlank { "no theme" }} · " +
                                        SimpleDateFormat("d MMM, HH:mm", Locale.US).format(Date(project.updatedAt)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(
                                onClick = {
                                    store.duplicate(project.id)
                                    refresh()
                                },
                            ) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = "Duplicate ${project.name}")
                            }
                            IconButton(
                                onClick = {
                                    store.delete(project.id)
                                    refresh()
                                    if (project.id == currentId) {
                                        val created = store.save(
                                            PluginDraft(),
                                            id = "project-${UUID.randomUUID()}",
                                            name = "Untitled plugin",
                                        )
                                        onNew(created)
                                    }
                                },
                            ) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete ${project.name}")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun ImportReportDialog(report: ImportReport, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Imported, with notes") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "Everything the studio understands was loaded into the editor. The notes below " +
                        "name what it could not carry; re-exporting will not write those fields back.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (report.ignoredManifestKeys.isNotEmpty()) {
                    Text("Manifest keys not written back:", style = MaterialTheme.typography.labelMedium)
                    Text(report.ignoredManifestKeys.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                }
                if (report.ignoredThemeKeys.isNotEmpty()) {
                    Text("Theme keys not written back:", style = MaterialTheme.typography.labelMedium)
                    Text(report.ignoredThemeKeys.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                }
                if (report.ignoredSections.isNotEmpty()) {
                    Text("Sections not written back:", style = MaterialTheme.typography.labelMedium)
                    Text(report.ignoredSections.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                }
                report.warnings.forEach { warning ->
                    Text("• $warning", style = MaterialTheme.typography.bodySmall)
                }
                if (report.ignoredManifestKeys.isEmpty() && report.ignoredThemeKeys.isEmpty() &&
                    report.ignoredSections.isEmpty() && report.warnings.isEmpty()
                ) {
                    Text("Nothing was lost: every field in the package has an editor control.")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

// --------------------------------------------------------------------------------- helpers

/** Reads at most [limit] bytes, so a hostile picker cannot make the importer allocate a gigabyte. */
private fun readAtMost(input: InputStream, limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > limit) error("The package is larger than ${limit / (1024 * 1024)} MiB.")
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

private const val MAX_IMPORT_BYTES = 4 * 1024 * 1024

private fun suggestId(name: String): String {
    val slug = name.lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), "")
        .ifEmpty { "mytheme" }
    return "com.example.$slug"
}

private fun ThemeDraft.colorFor(key: String): Long = when (key) {
    "primary" -> primary
    "onPrimary" -> onPrimary
    "background" -> background
    "surface" -> surface
    "onSurface" -> onSurface
    "accent" -> accent
    "secondary" -> secondary
    "tertiary" -> tertiary
    "surfaceVariant" -> surfaceVariant
    "onSurfaceVariant" -> onSurfaceVariant
    "outline" -> outline
    "error" -> error
    "onError" -> onError
    else -> primary
}

private fun ThemeDraft.backdropFor(key: String): Long = when (key) {
    "onPrimary" -> primary
    "onSurface" -> surface
    "onSurfaceVariant" -> surfaceVariant
    "onError" -> error
    "accent" -> background
    "surface" -> background
    else -> background
}

private fun PluginDraft.withThemeColor(key: String, value: Long): PluginDraft {
    val theme = theme
    val next = when (key) {
        "primary" -> theme.copy(primary = value)
        "onPrimary" -> theme.copy(onPrimary = value)
        "background" -> theme.copy(background = value)
        "surface" -> theme.copy(surface = value)
        "onSurface" -> theme.copy(onSurface = value)
        "accent" -> theme.copy(accent = value)
        "secondary" -> theme.copy(secondary = value)
        "tertiary" -> theme.copy(tertiary = value)
        "surfaceVariant" -> theme.copy(surfaceVariant = value)
        "onSurfaceVariant" -> theme.copy(onSurfaceVariant = value)
        "outline" -> theme.copy(outline = value)
        "error" -> theme.copy(error = value)
        "onError" -> theme.copy(onError = value)
        else -> theme
    }
    return copy(theme = next)
}

/** The thirteen roles, in the order a saved palette stores them. */
private fun ThemeDraft.palette(): List<Long> = ThemeDraft.COLOR_FIELDS.map { colorFor(it.key) }

private fun PluginDraft.withPalette(colors: List<Long>): PluginDraft {
    if (colors.size != ThemeDraft.COLOR_FIELDS.size) return this
    var next = this
    ThemeDraft.COLOR_FIELDS.forEachIndexed { index, spec ->
        next = next.withThemeColor(spec.key, colors[index])
    }
    return next
}
