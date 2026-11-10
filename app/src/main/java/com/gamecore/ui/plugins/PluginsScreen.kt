package com.gamecore.ui.plugins

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.core.i18n.LocalStrings
import com.gamecore.core.model.AppSettings
import com.gamecore.core.model.PluginSectionFont
import com.gamecore.core.plugin.PluginGroup
import com.gamecore.core.plugin.PluginGrouping
import com.gamecore.core.plugin.RiskTier
import com.gamecore.ui.components.ChoiceChip
import com.gamecore.ui.components.ChoiceRow
import com.gamecore.ui.components.ClickableCard
import com.gamecore.ui.components.ConfirmDialog
import com.gamecore.ui.components.EmptyState
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.PlainCard
import com.gamecore.ui.components.RowDivider
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SliderRow
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.SwitchRow
import com.gamecore.ui.components.Tone
import com.gamecore.studio.model.PluginDraft
import com.gamecore.studio.ui.PluginStudioScreen
import com.gamecore.studio.ui.PluginStudioTheme
import com.gamecore.ui.theme.Spacing
import com.gamecore.ui.theme.readableOn

/**
 * The plugin manager: everything installed, what each one is allowed to do, and the way out.
 *
 * This screen is the user's only general-purpose control over plugins, which shapes almost every
 * decision in it:
 *
 *  - **The list follows the current theme; the safety card does not.** The manager is part of the app
 *    the user is already looking at, so it wears the same colours, shapes and (unless overridden below)
 *    typeface. The one exception is [SafetyCard], which is wrapped back into GameCore's own
 *    [MaterialTheme] by the caller: a plugin that supplies an unreadable theme must not be able to
 *    hide the switch that stops it. That is also why "Use own theme" has no `enabled` argument: it is
 *    always tappable, and `resetToDefaultTheme` is defined as clearing a selection rather than writing
 *    anything that could fail.
 *  - **The safety card comes first, above the list.** A user who arrives here because something is
 *    wrong should not have to scroll past the plugins to find the switch that stops them.
 *  - **Compact by default, detailed on request.** Search, order, groups, the theme list and the
 *    plugin appearance controls stay one tap away rather than occupying the first two screens of the
 *    list. Every control the old layout offered is still here; only the prose and secondary actions
 *    move behind an expander.
 *  - **Search is a lens, not a stored preference.** The query lives only for this visit, filters
 *    after the chosen order is applied, and never reaches the registry. A blank query returns the
 *    whole list; a query that matches nothing is drawn as "no matches" rather than "no plugins".
 *  - **The picked file is capped while it is read, not after.** A content URI can be served by any app
 *    on the device, including one that streams forever. The ViewModel's reader copies in chunks and
 *    abandons the read the instant it passes the loader's byte cap, so a hostile or broken provider
 *    costs the cap and not the heap. It runs on the injected IO dispatcher, off the main thread.
 *  - **Plugin text is only ever a `Text`.** Names, developers, summaries and suspension details arrive
 *    sanitised from [rowFor] and go straight into [Text]. Nothing here builds an `AnnotatedString`
 *    from plugin input, resolves a plugin-named URL, or makes plugin text clickable.
 *  - **Nothing is dressed up as a success (§24).** The row's switch reflects the registry, not the tap;
 *    "still reading the plugin folder" and "no plugins installed" are drawn differently; and an
 *    incompatible plugin keeps a visible, disabled switch with the reason printed beside it rather than
 *    a hidden control the user is left to wonder about (§32).
 */

/** Rows cap their description, so a long one is trimmed here rather than allowed to own the list. */
private const val SUMMARY_MAX_LINES = 2

/**
 * A `.gcplugin` is a zip, and providers disagree about what to call one.
 *
 * Three types rather than one: a file manager that knows the extension reports `application/zip`, one
 * that does not reports `application/octet-stream`, and some providers report nothing useful at all —
 * hence the wildcard, without which the file the user is looking at would be greyed out in the picker
 * with no way to select it. Nothing is trusted on the strength of this filter: the bytes still go
 * through [PluginPackageLoader], which decides what the file actually is.
 */
private val PLUGIN_MIME_TYPES = arrayOf("application/zip", "application/octet-stream", "*/*")

private const val SAFETY_SUBTITLE = "Both controls here work without reading a single plugin"

private const val KILL_SWITCH_DESCRIPTION =
    "Nothing a plugin asked for runs while this is on, whatever each plugin's own switch says. Your " +
        "choices below are kept, not erased."

private const val THEME_RESET_DESCRIPTION =
    "A plugin can change how GameCore looks. Reset puts GameCore's own theme back and does not need " +
        "that plugin to be working — it is here, on this screen, for exactly that reason."

private const val INSTALL_DESCRIPTION =
    "A plugin is data, not a program: GameCore reads a .gcplugin file and draws what it describes. It " +
        "runs no code of its own, so it cannot reach the internet, start other apps, or see anything " +
        "you have not granted it. Installing one grants it nothing — it arrives switched off. You can " +
        "pick one file or several at once; every file you pick is read and shown to you before anything " +
        "is written."

private const val CREATE_DESCRIPTION =
    "The shared Studio can combine a theme, read-only screens, one bounded overlay, typed settings, " +
        "dashboard widgets and allowlisted automation rules. It writes validated JSON, never plugin " +
        "code. Asking for a permission in the package grants nothing: every permission and data scope " +
        "is approved after install."

/**
 * What each order means, including the one fact a user could otherwise get wrong.
 *
 * "By date" is when the bytes reached the device, and for a bundled plugin that is the app's own
 * install date rather than anything about the plugin. Saying so here is cheaper than a user concluding
 * the sort is broken because the eight plugins that came with GameCore all share one day.
 */
private const val ORDER_DESCRIPTION =
    "By date puts the most recently installed or updated package first. A plugin bundled with GameCore " +
        "has no file of its own, so it dates from when the app was installed; a plugin whose file time " +
        "the device will not report sorts last rather than pretending to be old."

private const val SEARCH_DESCRIPTION =
    "Every word you type has to match. Search covers names, ids, developers, descriptions and version " +
        "names."

private const val GROUPS_DESCRIPTION =
    "Groups made from a plugin's own declared sections and permissions update with the plugin. Your " +
        "groups are kept until you delete them."

private const val STILL_READING_TITLE = "Reading the plugin folder"

private const val STILL_READING_MESSAGE =
    "This takes a moment on the first open. Whatever is installed will appear here — an empty list " +
        "would be a guess until the read finishes."

private const val NO_PLUGINS_TITLE = "No plugins installed"

private const val NO_PLUGINS_MESSAGE =
    "A plugin adds a theme, a Pill module or an automation rule, and only what you grant it. Nothing " +
        "is installed yet, so nothing extra is running."

private const val NO_MATCHES_TITLE = "No plugins match"

private const val NO_MATCHES_MESSAGE =
    "Nothing installed matches every word in the search. Try a plugin name, its id, the developer, or " +
        "a word from its description."

private const val NO_GROUP_MATCHES_TITLE = "No plugins in this group"

private const val NO_GROUP_MATCHES_MESSAGE =
    "This group has no installed plugins. Open a plugin and use its Groups card to add it, or choose " +
        "another group above."

private const val APPEARANCE_DESCRIPTION =
    "These controls change the plugin manager, its consent screen and automation history. Colours and " +
        "shapes still follow the theme the rest of GameCore is wearing; the safety card keeps " +
        "GameCore's own colours and type so it stays legible."

private const val APPEARANCE_SIZE_DESCRIPTION =
    "Scales text in this section only, on top of the font size you chose for GameCore. It does not " +
        "change the rest of the app."

private const val SUSPENDED_GENERIC =
    "GameCore stopped this plugin during this run after it repeatedly failed. Your settings for it are " +
        "untouched."

private const val QUARANTINED_GENERIC =
    "This plugin is quarantined. It is still listed so you can inspect it, but GameCore will not load " +
        "it — not after a restart either — until you release it."

@Composable
fun PluginsScreen(
    onBack: () -> Unit,
    onOpenPlugin: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PluginsViewModel = hiltViewModel(),
    /**
     * Re-applies GameCore's own theme to one subtree. The root passes its `houseTheme` wrapper here so
     * the safety card and confirmation dialogs stay legible even while the manager follows a plugin
     * theme; a preview or a test can leave the default and get the current theme throughout.
     */
    safetyTheme: @Composable (@Composable () -> Unit) -> Unit = { content -> content() },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val padded = Modifier.padding(horizontal = ScreenPadding)
    val visibleRows = state.filteredRows
    var picked by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var creating by remember { mutableStateOf(false) }
    var creatingGroup by remember { mutableStateOf(false) }
    var deletingGroup by remember { mutableStateOf<PluginGroup?>(null) }
    var studioDraft by remember { mutableStateOf(PluginDraft()) }

    // The picker callback runs on the main thread, so it only records the URIs. A cancelled pick returns
    // an empty list and simply does nothing.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        picked = uris
    }

    // The ViewModel reads the files on IO, validates them and holds the resulting preview. No install call
    // is made until the user confirms the dialog below, and clearing the URIs does not cancel that work.
    // One file keeps the single-package preview; several are previewed together and confirmed at once.
    LaunchedEffect(picked) {
        val uris = picked
        if (uris.isEmpty()) return@LaunchedEffect
        if (uris.size == 1) viewModel.previewInstall(uris.first()) else viewModel.previewInstallBatch(uris)
        picked = emptyList()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        item {
            ScreenHeader(
                title = LocalStrings.current.pluginsTitle,
                subtitle = LocalStrings.current.pluginsSubtitle,
                onBack = onBack,
                action = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Read the plugin folder again",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
            )
        }

        item {
            safetyTheme {
                SafetyCard(
                    killSwitchOn = state.killSwitchOn,
                    activeThemeName = state.activeThemeName,
                    hasAnyActive = state.hasAnyActive,
                    onSetKillSwitch = viewModel::setKillSwitch,
                    onResetTheme = viewModel::resetToDefaultTheme,
                    modifier = padded,
                )
            }
        }

        if (state.themeRows.isNotEmpty()) {
            item {
                ThemeSelectionCard(
                    rows = state.themeRows,
                    activeThemeName = state.activeThemeName,
                    killSwitchOn = state.killSwitchOn,
                    onActivate = viewModel::activateTheme,
                    onDeactivate = viewModel::resetToDefaultTheme,
                    modifier = padded,
                )
            }
        }

        item {
            PluginAppearanceCard(
                font = state.pluginSectionFont,
                fontScalePercent = state.pluginSectionFontScalePercent,
                onFont = viewModel::setPluginSectionFont,
                onScaleChange = viewModel::setPluginSectionFontScale,
                onScaleCommit = viewModel::commitPluginSectionFontScale,
                modifier = padded,
            )
        }

        val message = state.message
        if (message != null) {
            item {
                NoteBanner(
                    text = message,
                    tone = Tone.Accent,
                    icon = Icons.Filled.Info,
                    modifier = padded,
                    action = {
                        TextButton(onClick = viewModel::dismissMessage) { Text("Dismiss") }
                    },
                )
            }
        }

        item {
            GetPluginsCard(
                note = state.previewError,
                isPreviewing = state.isPreviewing,
                onPick = { picker.launch(PLUGIN_MIME_TYPES) },
                onCreate = { creating = true },
                modifier = padded,
            )
        }

        // Only once there is something to search or order. A control over an empty list is a control
        // that cannot do anything, and §32's rule covers a choice offered with no list to apply it to.
        if (state.hasPlugins) {
            item {
                BrowseToolsCard(
                    query = state.searchQuery,
                    onQueryChange = viewModel::setSearchQuery,
                    onClear = viewModel::clearSearch,
                    sort = state.sort,
                    onSort = viewModel::setSort,
                    groups = state.groups,
                    selectedGroupId = state.groupFilterId,
                    selectedGroup = state.selectedGroup,
                    onSelectGroup = viewModel::setGroupFilter,
                    onCreateGroup = { creatingGroup = true },
                    onDeleteGroup = { deletingGroup = it },
                    visibleCount = visibleRows.size,
                    totalCount = state.rows.size,
                    modifier = padded,
                )
            }
        }

        when {
            // "Not read yet" and "nothing installed" look identical as an empty list, and only one of
            // them is a fact — §24.
            !state.isLoaded -> item {
                PlainCard(modifier = padded, contentPadding = 12.dp) {
                    EmptyState(
                        icon = Icons.Filled.Refresh,
                        title = STILL_READING_TITLE,
                        message = STILL_READING_MESSAGE,
                    )
                }
            }

            !state.hasPlugins -> item {
                PlainCard(modifier = padded, contentPadding = 12.dp) {
                    EmptyState(
                        icon = Icons.Filled.Extension,
                        title = NO_PLUGINS_TITLE,
                        message = NO_PLUGINS_MESSAGE,
                    )
                }
            }

            // A search or group with no hits is not an empty install. Saying so, and offering the way
            // back to the full list, keeps the screen from looking like the plugins disappeared.
            visibleRows.isEmpty() -> item {
                PlainCard(modifier = padded, contentPadding = 12.dp) {
                    val filteredByGroup = state.hasGroupFilter
                    EmptyState(
                        icon = if (filteredByGroup) Icons.Filled.Folder else Icons.Filled.Search,
                        title = if (filteredByGroup) NO_GROUP_MATCHES_TITLE else NO_MATCHES_TITLE,
                        message = if (filteredByGroup) {
                            NO_GROUP_MATCHES_MESSAGE
                        } else {
                            NO_MATCHES_MESSAGE
                        },
                        actionLabel = if (filteredByGroup) "Show every plugin" else "Clear search",
                        onAction = if (filteredByGroup) {
                            viewModel::clearGroupFilter
                        } else {
                            viewModel::clearSearch
                        },
                    )
                }
            }

            else -> items(visibleRows, key = { it.id }) { row ->
                PluginCard(
                    row = row,
                    killSwitchOn = state.killSwitchOn,
                    onOpen = { onOpenPlugin(row.id) },
                    onSetEnabled = { enabled -> viewModel.setEnabled(row.id, enabled) },
                    onClearSuspension = { viewModel.clearSuspension(row.id) },
                    onReleaseQuarantine = { viewModel.releaseQuarantine(row.id, row.name) },
                    onAskRollback = { viewModel.askRollback(row) },
                    onAskRemove = { viewModel.askRemove(row) },
                    modifier = padded,
                )
            }
        }
    }

    if (creating) {
        // A full-screen dialog rather than a navigation destination: the studio is one screen with
        // its own scroll and its own save flow, and keeping it out of the nav graph means the
        // plugin manager's back behaviour does not change while a draft is open.
        Dialog(
            onDismissRequest = { creating = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                // The studio ships its own colours on purpose: a tool that borrowed the theme being
                // designed would make the preview disappear into the page as soon as the background
                // was darker than the editor.
                PluginStudioTheme {
                    PluginStudioScreen(
                        draft = studioDraft,
                        onDraftChange = { studioDraft = it },
                        modifier = Modifier.fillMaxSize(),
                        onSaved = { viewModel.refresh() },
                        onClose = { creating = false },
                    )
                }
            }
        }
    }

    val pending = state.pendingRemoval
    if (pending != null) {
        safetyTheme {
            ConfirmDialog(
                title = "Remove ${pending.name}?",
                message = "This deletes the plugin's file from this device along with everything it " +
                    "had stored — its permissions, its settings and any data it kept. None of that " +
                    "can be brought back by installing it again.",
                confirmLabel = "Remove",
                onConfirm = viewModel::confirmRemove,
                onDismiss = viewModel::cancelRemove,
            )
        }
    }

    val pendingRollback = state.pendingRollback
    if (pendingRollback != null) {
        safetyTheme {
            ConfirmDialog(
                title = "Restore the previous ${pendingRollback.name}?",
                message = "The version installed now is overwritten by the copy GameCore kept from " +
                    "the install before it. The restored version arrives switched off with its " +
                    "permissions cleared, exactly as a fresh install would; you will need to grant " +
                    "them again if you turn it on.",
                confirmLabel = "Restore",
                onConfirm = viewModel::confirmRollback,
                onDismiss = viewModel::cancelRollback,
            )
        }
    }

    val preview = state.preview
    if (preview != null) {
        safetyTheme {
            if (preview.canInstall) {
                ConfirmDialog(
                    title = if (preview.replacesExisting) {
                        "Replace ${preview.name}?"
                    } else {
                        "Install ${preview.name}?"
                    },
                    message = previewSummary(preview),
                    confirmLabel = if (preview.replacesExisting) "Replace" else "Install",
                    onConfirm = viewModel::confirmInstall,
                    onDismiss = viewModel::cancelPreview,
                    isDestructive = false,
                )
            } else {
                // The preview is still shown, because the reason it cannot be installed is the useful
                // part. There is deliberately no button that would call install anyway.
                ConfirmDialog(
                    title = "This package cannot be installed",
                    message = previewSummary(preview),
                    confirmLabel = "Close",
                    onConfirm = viewModel::cancelPreview,
                    onDismiss = viewModel::cancelPreview,
                    isDestructive = false,
                )
            }
        }
    }

    // A pick of several files: one dialog, one yes, and every file that went wrong named in it.
    val batch = state.batch
    if (batch != null) {
        safetyTheme {
            if (batch.hasInstallable) {
                ConfirmDialog(
                    title = if (batch.installableCount == 1) {
                        "Install 1 plugin?"
                    } else {
                        "Install ${batch.installableCount} plugins?"
                    },
                    message = batchPreviewSummary(batch),
                    confirmLabel = "Install ${batch.installableCount}",
                    onConfirm = viewModel::confirmInstallBatch,
                    onDismiss = viewModel::cancelBatch,
                    isDestructive = false,
                )
            } else {
                // Nothing here can be installed, so there is no button that would try. The reasons are
                // the useful part, exactly as they are for a single package.
                ConfirmDialog(
                    title = "None of these files can be installed",
                    message = batchPreviewSummary(batch),
                    confirmLabel = "Close",
                    onConfirm = viewModel::cancelBatch,
                    onDismiss = viewModel::cancelBatch,
                    isDestructive = false,
                )
            }
        }
    }

    val deleting = deletingGroup
    if (deleting != null) {
        safetyTheme {
            ConfirmDialog(
                title = "Delete \"${deleting.name}\"?",
                message = "The group goes away. The plugins in it are not changed, and any other " +
                    "group they belong to stays as it is.",
                confirmLabel = "Delete group",
                onConfirm = {
                    viewModel.deleteGroup(deleting)
                    deletingGroup = null
                },
                onDismiss = { deletingGroup = null },
            )
        }
    }

    if (creatingGroup) {
        safetyTheme {
            CreateGroupDialog(
                onConfirm = { name ->
                    viewModel.createGroup(name)
                    creatingGroup = false
                },
                onDismiss = { creatingGroup = false },
            )
        }
    }
}

/**
 * The two controls that have to keep working when a plugin has made the rest of the app unusable.
 *
 * Neither reads a plugin, parses a manifest or depends on one loading, and the card is drawn in
 * GameCore's own colours. A plugin theme that turns every other screen unreadable cannot touch this one,
 * which is the whole reason the theming feature is safe to offer at all.
 *
 * The chip states what is true now rather than what the switches say: with the kill switch on the
 * permission gate denies every plugin call, so "All off" is the honest reading even though each
 * plugin's own switch is still wherever the user left it.
 */
@Composable
private fun SafetyCard(
    killSwitchOn: Boolean,
    activeThemeName: String?,
    hasAnyActive: Boolean,
    onSetKillSwitch: (Boolean) -> Unit,
    onResetTheme: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlainCard(modifier = modifier.fillMaxWidth(), contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Shield,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Safety",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = SAFETY_SUBTITLE,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            StatusChip(
                text = when {
                    killSwitchOn -> "All off"
                    hasAnyActive -> "Running"
                    else -> "None running"
                },
                tone = when {
                    killSwitchOn -> Tone.Danger
                    hasAnyActive -> Tone.Good
                    else -> Tone.Muted
                },
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        SwitchRow(
            title = "Turn all plugins off",
            checked = killSwitchOn,
            onCheckedChange = onSetKillSwitch,
            description = if (killSwitchOn) KILL_SWITCH_DESCRIPTION else null,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Palette,
                contentDescription = null,
                tint = if (activeThemeName == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (activeThemeName == null) {
                    "GameCore's own theme is in force."
                } else {
                    "$activeThemeName is supplying the theme. Resetting does not need it to be working."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // No `enabled` argument, deliberately. Offering this only when a theme is active would make
            // it unavailable in the one case it is needed — a plugin theme the app can no longer read
            // back — and resetting to the built-in theme is a no-op when it is already in force.
            TextButton(onClick = onResetTheme) { Text("Use own theme") }
        }
    }
}

/**
 * The one theme in force, as a single horizontal row of swatches with the full list one tap away.
 *
 * The list rows' switches are the *enable* switch, not the theme selection: a plugin can be running
 * for its widgets or automations without supplying the app's colours. That distinction was invisible
 * here, so switching to another theme left the previous row's enable switch on and looked like the
 * old theme was still active. The swatches and the expanded switches both read [PluginRow.isThemeSource],
 * and activating one row turns the previous one off on the same registry emission.
 *
 * A row that cannot be selected right now keeps its swatch dimmed and its switch disabled with the
 * reason in its description rather than offering a tap the registry would refuse (§32). The expanded
 * list is where those reasons live; the horizontal row stays one line tall.
 */
@Composable
private fun ThemeSelectionCard(
    rows: List<PluginRow>,
    activeThemeName: String?,
    killSwitchOn: Boolean,
    onActivate: (PluginRow) -> Unit,
    onDeactivate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme

    PlainCard(modifier = modifier.fillMaxWidth(), contentPadding = 12.dp) {
        CompactCardHeader(
            icon = Icons.Filled.Palette,
            title = "Theme",
            summary = activeThemeName?.let { "$it is in use" } ?: "GameCore's own theme",
            expanded = expanded,
            onToggle = { expanded = !expanded },
        )
        Spacer(modifier = Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "gamecore-theme") {
                ThemeSwatch(
                    name = "GameCore",
                    primary = scheme.primary,
                    accent = scheme.primary,
                    surface = scheme.surfaceVariant,
                    selected = activeThemeName == null,
                    enabled = true,
                    onClick = onDeactivate,
                )
            }
            items(rows, key = { it.id }) { row ->
                ThemeSwatch(
                    name = row.name,
                    primary = row.themePrimaryArgb?.let { Color(it) } ?: scheme.primary,
                    accent = row.themeAccentArgb?.let { Color(it) }
                        ?: row.themePrimaryArgb?.let { Color(it) }
                        ?: scheme.primary,
                    surface = row.themeSurfaceArgb?.let { Color(it) } ?: scheme.surfaceVariant,
                    selected = row.isThemeSource,
                    enabled = !killSwitchOn && (row.canActivateTheme || row.isThemeSource),
                    onClick = {
                        if (row.isThemeSource) onDeactivate() else onActivate(row)
                    },
                )
            }
        }
        if (killSwitchOn) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Turn all plugins off is on, so no plugin can supply a theme.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            Spacer(modifier = Modifier.height(8.dp))
            RowDivider()
            rows.forEachIndexed { index, row ->
                if (index > 0) RowDivider()
                SwitchRow(
                    title = row.name,
                    checked = row.isThemeSource,
                    onCheckedChange = { on -> if (on) onActivate(row) else onDeactivate() },
                    enabled = !killSwitchOn && (row.canActivateTheme || row.isThemeSource),
                    description = when {
                        killSwitchOn -> "Turn all plugins off is on, so no plugin can supply a theme."
                        row.isThemeSource -> "In use now. Turning this off restores GameCore's own theme."
                        row.canActivateTheme -> "Turning this on switches off the theme in use."
                        !row.compatibility.isRunnable -> row.compatibility.reason
                        row.quarantined -> "Quarantined. Release it before it can supply a theme."
                        row.suspended -> "Stopped for this run. Let it try again before using its theme."
                        !row.enabled -> "Turn this plugin on first, then use its theme."
                        else -> "Grant it \"Change the look\" first, then use its theme."
                    },
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = THEME_RESET_DESCRIPTION,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A compact circular theme swatch, showing the plugin's real parsed surface, primary and accent. */
@Composable
private fun ThemeSwatch(
    name: String,
    primary: Color,
    accent: Color,
    surface: Color,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(64.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.45f)
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(surface)
                .border(width = if (selected) 3.dp else 2.dp, color = primary, shape = CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(accent),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "Selected",
                        tint = readableOn(accent),
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The plugin section's own face and text size, folded away until asked for.
 *
 * These are host preferences, not plugin data: they are stored in [AppSettings], normalised on read
 * and write, and applied by [com.gamecore.ui.theme.PluginSectionTheme] as a nested typography override.
 * The card is inside the section, so the user can see the chosen face before leaving the screen.
 */
@Composable
private fun PluginAppearanceCard(
    font: PluginSectionFont,
    fontScalePercent: Int,
    onFont: (PluginSectionFont) -> Unit,
    onScaleChange: (Int) -> Unit,
    onScaleCommit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    PlainCard(modifier = modifier.fillMaxWidth(), contentPadding = 12.dp) {
        CompactCardHeader(
            icon = Icons.Filled.FormatSize,
            title = "Plugin appearance",
            summary = "${font.label} · $fontScalePercent%",
            expanded = expanded,
            onToggle = { expanded = !expanded },
        )
        if (expanded) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = APPEARANCE_DESCRIPTION,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "FONT",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            ChoiceRow(
                options = PluginSectionFont.entries.toList(),
                selected = font,
                onSelect = onFont,
                label = { it.label },
                perRow = 3,
            )
            Spacer(modifier = Modifier.height(4.dp))
            SliderRow(
                title = "Font size",
                value = fontScalePercent,
                range = AppSettings.MIN_PLUGIN_SECTION_FONT_SCALE..AppSettings.MAX_PLUGIN_SECTION_FONT_SCALE,
                onValueChange = onScaleChange,
                valueLabel = "$fontScalePercent%",
                description = APPEARANCE_SIZE_DESCRIPTION,
                onValueChangeFinished = onScaleCommit,
            )
        }
    }
}

/**
 * The two ways a plugin arrives, in one card: install a file, or build one here.
 *
 * The standalone Plugin Maker app exists for people who want to design a package on a phone with no
 * GameCore install at all. This card hosts the same editor, so the common case — I have GameCore, I
 * want it to look different — does not require a second download. Saving still goes through the system
 * file picker: the draft never becomes an installed plugin behind the user's back.
 *
 * [note] is this card's own, separate from the screen's message banner: a file that was too large or
 * unreadable never reached the registry, so saying so next to the button that produced it is more use
 * than a banner at the top of a screen the user may have scrolled past.
 */
@Composable
private fun GetPluginsCard(
    note: String?,
    isPreviewing: Boolean,
    onPick: () -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDetails by rememberSaveable { mutableStateOf(false) }

    PlainCard(modifier = modifier.fillMaxWidth(), contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Download,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Get plugins",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Install a .gcplugin or build one here",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onPick,
                enabled = !isPreviewing,
                modifier = Modifier.weight(1f),
            ) {
                Text(LocalStrings.current.pluginsChooseFiles)
            }
            OutlinedButton(
                onClick = onCreate,
                modifier = Modifier.weight(1f),
            ) {
                Text("Plugin Studio")
            }
        }
        if (isPreviewing) {
            Spacer(modifier = Modifier.height(8.dp))
            NoteBanner(
                text = "Reading the package. Nothing has been installed — you will see what each one " +
                    "asks for before anything is written.",
                tone = Tone.Muted,
                icon = Icons.Filled.Info,
            )
        }
        if (note != null) {
            Spacer(modifier = Modifier.height(8.dp))
            NoteBanner(text = note, tone = Tone.Warning, icon = Icons.Filled.Warning)
        }
        TextButton(onClick = { showDetails = !showDetails }) {
            Text(if (showDetails) "Hide details" else "How installing works")
        }
        if (showDetails) {
            Text(
                text = INSTALL_DESCRIPTION,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = CREATE_DESCRIPTION,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Search, order and groups, together because they are the three lenses over one list.
 *
 * Filtering is live: every keystroke narrows [PluginsUiState.filteredRows], and there is no submit
 * action to miss. The query is deliberately not persisted — see [PluginsUiState.searchQuery] — and
 * the clear button only exists when there is something to clear, so it is never a dead target.
 *
 * The order chips are presentation only: choosing one cannot change what any plugin is allowed to do.
 * The explanation underneath says what "date" actually measures, because for the bundled plugins it is
 * the app's install date.
 */
@Composable
private fun BrowseToolsCard(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    sort: PluginSort,
    onSort: (PluginSort) -> Unit,
    groups: List<PluginGroup>,
    selectedGroupId: String?,
    selectedGroup: PluginGroup?,
    onSelectGroup: (String?) -> Unit,
    onCreateGroup: () -> Unit,
    onDeleteGroup: (PluginGroup) -> Unit,
    visibleCount: Int,
    totalCount: Int,
    modifier: Modifier = Modifier,
) {
    var showDetails by rememberSaveable { mutableStateOf(false) }
    val filtering = query.isNotBlank() || selectedGroupId != null

    PlainCard(modifier = modifier.fillMaxWidth(), contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Find & order",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (filtering) {
                        "$visibleCount of $totalCount plugins shown"
                    } else {
                        "$totalCount installed"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = { showDetails = !showDetails }) {
                Text(if (showDetails) "Hide" else "Help")
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(LocalStrings.current.pluginsSearch) },
            placeholder = { Text(LocalStrings.current.pluginsSearchHint) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
            trailingIcon = if (query.isNotEmpty()) {
                {
                    IconButton(onClick = onClear) {
                        Icon(
                            imageVector = Icons.Filled.Clear,
                            contentDescription = "Clear search",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            } else {
                null
            },
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PluginSort.entries.forEach { option ->
                ChoiceChip(
                    text = option.label,
                    isSelected = option == sort,
                    onClick = { onSort(option) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (groups.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item(key = "all-groups") {
                    ChoiceChip(
                        text = "All plugins",
                        isSelected = selectedGroupId == null,
                        onClick = { onSelectGroup(null) },
                    )
                }
                items(groups, key = { it.id }) { group ->
                    ChoiceChip(
                        text = "${group.name} (${group.pluginIds.size})",
                        isSelected = group.id == selectedGroupId,
                        onClick = { onSelectGroup(group.id) },
                    )
                }
                item(key = "new-group") {
                    TextButton(onClick = onCreateGroup) { Text("New group") }
                }
                if (selectedGroup != null && !selectedGroup.automatic) {
                    item(key = "delete-group") {
                        TextButton(onClick = { onDeleteGroup(selectedGroup) }) { Text("Delete") }
                    }
                }
            }
        }
        if (showDetails) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = SEARCH_DESCRIPTION,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = ORDER_DESCRIPTION,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (groups.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = GROUPS_DESCRIPTION,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * One plugin, compact.
 *
 * The switch is [Switch] rather than a [SwitchRow] because the row carries a name, a developer, a
 * version, a description and badges, and the whole card is a tap target for the detail screen.
 *
 * Incompatible plugins keep their switch, disabled, with the reason printed below it. Hiding it would
 * leave the user with a row that looks like every other except that nothing can be done to it; §32's
 * point is that a control must never be tapped and do nothing, and a visibly disabled one beside its
 * reason is the honest version of that.
 *
 * Secondary actions — roll back, remove — live behind the "Manage" expander so the default card is
 * three lines tall. Recovery actions for a suspended or quarantined plugin stay visible, because those
 * are the reasons the user came to this row in the first place.
 */
@Composable
private fun PluginCard(
    row: PluginRow,
    killSwitchOn: Boolean,
    onOpen: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onClearSuspension: () -> Unit,
    onReleaseQuarantine: () -> Unit,
    onAskRollback: () -> Unit,
    onAskRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var managing by rememberSaveable(row.id) { mutableStateOf(false) }
    val hasManageActions = row.canRollback || row.canBeRemoved

    ClickableCard(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth(),
        contentPadding = 12.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = attributionOf(row),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(
                checked = row.enabled,
                onCheckedChange = onSetEnabled,
                // A quarantined plugin can be switched off, but not on: the registry would refuse the
                // write and the switch would stay put, which is a dead tap. Release is the way back.
                enabled = row.compatibility.isRunnable && !row.quarantined,
            )
        }

        if (row.summary.isNotBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = row.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = SUMMARY_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(modifier = Modifier.height(6.dp))
        BadgeRow(row = row, killSwitchOn = killSwitchOn)

        if (!row.compatibility.isRunnable) {
            Spacer(modifier = Modifier.height(6.dp))
            NoteBanner(
                text = row.compatibility.reason,
                tone = Tone.Danger,
                icon = Icons.Filled.Warning,
            )
        }

        if (row.suspended) {
            Spacer(modifier = Modifier.height(6.dp))
            NoteBanner(
                text = row.suspensionDetail ?: SUSPENDED_GENERIC,
                tone = Tone.Warning,
                icon = Icons.Filled.Block,
            )
        }

        if (row.quarantined) {
            Spacer(modifier = Modifier.height(6.dp))
            NoteBanner(
                text = row.quarantineDetail ?: QUARANTINED_GENERIC,
                tone = Tone.Danger,
                icon = Icons.Filled.Block,
            )
        }

        if (row.suspended || row.quarantined || hasManageActions) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (row.suspended) {
                    TextButton(onClick = onClearSuspension) { Text("Let it try again") }
                }
                if (row.quarantined) {
                    TextButton(onClick = onReleaseQuarantine) { Text("Release") }
                }
                Spacer(modifier = Modifier.weight(1f))
                if (hasManageActions) {
                    TextButton(onClick = { managing = !managing }) {
                        Text(if (managing) "Done" else "Manage")
                        Icon(
                            imageVector = if (managing) {
                                Icons.Filled.ExpandLess
                            } else {
                                Icons.Filled.ExpandMore
                            },
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
            if (managing) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Shown only when the registry found a previous package on disk, so this is never
                    // a tap that discovers there is nothing to restore.
                    if (row.canRollback) {
                        TextButton(onClick = onAskRollback) { Text("Roll back") }
                    }
                    // Disabled rather than absent for a bundled plugin, with its origin badge directly
                    // above saying why: the rows stay the same shape down the list, and a disabled
                    // button is not a tap that did nothing.
                    TextButton(onClick = onAskRemove, enabled = row.canBeRemoved) {
                        Text(LocalStrings.current.pluginsRemove)
                    }
                }
            }
        }
    }
}

/**
 * The row's badges, in the order a worried user needs them, on one scrolling line.
 *
 * Compatibility and suspension come before anything cosmetic, because they are the two reasons a plugin
 * the user switched on is not doing anything. A horizontal row rather than a chunked grid: the old grid
 * could grow to three or four lines on a row with several badges, which was most of the card's height.
 */
@Composable
private fun BadgeRow(row: PluginRow, killSwitchOn: Boolean) {
    val badges: List<Pair<String, Tone>> = buildList {
        if (!row.compatibility.isRunnable) add("Incompatible" to Tone.Danger)
        if (row.quarantined) add("Quarantined" to Tone.Danger)
        if (row.suspended) add("Stopped this run" to Tone.Warning)
        // Only worth saying on a plugin the user switched on: on an already-off row it would be a
        // second badge for a state the switch already shows.
        if (killSwitchOn && row.enabled) add("Held off" to Tone.Muted)
        if (row.isThemeSource) add("Supplies the current theme" to Tone.Accent)
        row.highestTier?.let { add(it.label to toneOf(it)) }
        add(row.origin.label to Tone.Muted)
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        items(badges) { (text, tone) ->
            StatusChip(text = text, tone = tone)
        }
    }
}

/**
 * A card's heading, with the same icon-and-two-lines shape everywhere on this screen.
 *
 * When [onToggle] is supplied the whole row is the expander, so the touch target is the width of the
 * card rather than the small chevron at its end.
 */
@Composable
private fun CompactCardHeader(
    icon: ImageVector,
    title: String,
    summary: String? = null,
    expanded: Boolean? = null,
    onToggle: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val headerModifier = if (onToggle != null) {
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(role = Role.Button, onClick = onToggle)
            .padding(vertical = 2.dp)
    } else {
        Modifier.fillMaxWidth()
    }
    Row(modifier = headerModifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (summary != null) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) trailing()
        if (expanded != null) {
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Names a custom group. The name is the only thing stored; membership is chosen on each plugin. */
@Composable
private fun CreateGroupDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val clean = name.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "New group", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Give the group a name. You can add any number of plugins to it from " +
                        "their own screens, and a plugin can stay in several groups at once.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(PluginGrouping.MAX_NAME_LENGTH) },
                    label = { Text("Group name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(clean) },
                enabled = clean.isNotEmpty(),
            ) { Text("Create group") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
    )
}

/**
 * "Who made this, and which build of it is installed", on one line.
 *
 * A package may legitimately omit either, and the row says so rather than printing a bare separator —
 * an unattributed plugin is a thing the user should notice, not a gap in the layout.
 */
private fun attributionOf(row: PluginRow): String {
    val developer = row.developer.ifBlank { "No developer named" }
    return if (row.versionName.isBlank()) developer else "$developer · ${row.versionName}"
}

/** The tier's own colour, so the badge matches the consent friction the detail screen will apply. */
private fun toneOf(tier: RiskTier): Tone = when (tier) {
    RiskTier.LOW -> Tone.Muted
    RiskTier.MEDIUM -> Tone.Accent
    RiskTier.HIGH -> Tone.Warning
}
