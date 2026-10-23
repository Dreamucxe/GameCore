package com.gamecore.ui.plugins

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.core.plugin.RiskTier
import com.gamecore.ui.components.ActionRow
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
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.SwitchRow
import com.gamecore.ui.components.Tone
import com.gamecore.ui.theme.Spacing

/**
 * The plugin manager: everything installed, what each one is allowed to do, and the way out.
 *
 * This screen is the user's only general-purpose control over plugins, which shapes almost every
 * decision in it:
 *
 *  - **It is deliberately a screen a plugin can never restyle.** The safety card is drawn with
 *    GameCore's own [MaterialTheme] and nothing on it is conditioned on a plugin parsing, loading or
 *    behaving. A plugin that supplies a theme can make every other screen hard to read — black on
 *    black, a one-pixel font — and the escape hatch has to stay legible exactly when the rest of the
 *    app does not. That is also why "Use GameCore's own theme" has no `enabled` argument: it is always
 *    tappable, and `resetToDefaultTheme` is defined as clearing a selection rather than writing
 *    anything that could fail.
 *  - **The safety card comes first, above the list.** A user who arrives here because something is
 *    wrong should not have to scroll past the plugins to find the switch that stops them.
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

/** [StatusChip] takes no modifier, so badges are boxed into fixed columns — see `BadgeRow`. */
private const val CHIPS_PER_ROW = 2

/** Rows cap their description, so a long one is trimmed here rather than allowed to own the list. */
private const val SUMMARY_MAX_LINES = 3

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

private const val SCREEN_SUBTITLE =
    "What is installed, what each one may do, and the one switch that stops all of it"

private const val SAFETY_SUBTITLE = "Both controls here work without reading a single plugin"

private const val KILL_SWITCH_DESCRIPTION =
    "Nothing a plugin asked for runs while this is on, whatever each plugin's own switch says. Your " +
        "choices below are kept, not erased."

private const val THEME_RESET_DESCRIPTION =
    "A plugin can change how GameCore looks. This button puts GameCore's own theme back and does not " +
        "need that plugin to be working — it is here, on this screen, for exactly that reason."

private const val INSTALL_DESCRIPTION =
    "A plugin is data, not a program: GameCore reads a .gcplugin file and draws what it describes. It " +
        "runs no code of its own, so it cannot reach the internet, start other apps, or see anything " +
        "you have not granted it. Installing one grants it nothing — it arrives switched off."

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

private const val STILL_READING_TITLE = "Reading the plugin folder"

private const val STILL_READING_MESSAGE =
    "This takes a moment on the first open. Whatever is installed will appear here — an empty list " +
        "would be a guess until the read finishes."

private const val NO_PLUGINS_TITLE = "No plugins installed"

private const val NO_PLUGINS_MESSAGE =
    "A plugin adds a theme, a Pill module or an automation rule, and only what you grant it. Nothing " +
        "is installed yet, so nothing extra is running."

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
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val padded = Modifier.padding(horizontal = ScreenPadding)
    var picked by remember { mutableStateOf<Uri?>(null) }

    // The picker callback runs on the main thread, so it only records the URI. A cancelled pick returns
    // null and simply does nothing.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        picked = uri
    }

    // The ViewModel reads the file on IO, validates it and holds the resulting preview. No install call
    // is made until the user confirms the dialog below, and clearing the URI does not cancel that work.
    LaunchedEffect(picked) {
        val uri = picked ?: return@LaunchedEffect
        viewModel.previewInstall(uri)
        picked = null
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        item {
            ScreenHeader(
                title = "Plugins",
                subtitle = SCREEN_SUBTITLE,
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
            SafetyCard(
                killSwitchOn = state.killSwitchOn,
                activeThemeName = state.activeThemeName,
                hasAnyActive = state.hasAnyActive,
                onSetKillSwitch = viewModel::setKillSwitch,
                onResetTheme = viewModel::resetToDefaultTheme,
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
            InstallCard(
                note = state.previewError,
                isPreviewing = state.isPreviewing,
                onPick = { picker.launch(PLUGIN_MIME_TYPES) },
                modifier = padded,
            )
        }

        // Only once there is something to order. A sort control over an empty list is a control that
        // cannot do anything, and §32's rule covers a choice offered with no list to apply it to.
        if (state.hasPlugins) {
            item {
                SortCard(
                    sort = state.sort,
                    onSort = viewModel::setSort,
                    modifier = padded,
                )
            }
        }

        when {
            // "Not read yet" and "nothing installed" look identical as an empty list, and only one of
            // them is a fact — §24.
            !state.isLoaded -> item {
                PlainCard(modifier = padded) {
                    EmptyState(
                        icon = Icons.Filled.Refresh,
                        title = STILL_READING_TITLE,
                        message = STILL_READING_MESSAGE,
                    )
                }
            }

            !state.hasPlugins -> item {
                PlainCard(modifier = padded) {
                    EmptyState(
                        icon = Icons.Filled.Extension,
                        title = NO_PLUGINS_TITLE,
                        message = NO_PLUGINS_MESSAGE,
                    )
                }
            }

            else -> items(state.rows, key = { it.id }) { row ->
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

    val pending = state.pendingRemoval
    if (pending != null) {
        ConfirmDialog(
            title = "Remove ${pending.name}?",
            message = "This deletes the plugin's file from this device along with everything it had " +
                "stored — its permissions, its settings and any data it kept. None of that can be " +
                "brought back by installing it again.",
            confirmLabel = "Remove",
            onConfirm = viewModel::confirmRemove,
            onDismiss = viewModel::cancelRemove,
        )
    }

    val pendingRollback = state.pendingRollback
    if (pendingRollback != null) {
        ConfirmDialog(
            title = "Restore the previous ${pendingRollback.name}?",
            message = "The version installed now is overwritten by the copy GameCore kept from the " +
                "install before it. The restored version arrives switched off with its permissions " +
                "cleared, exactly as a fresh install would; you will need to grant them again if you " +
                "turn it on.",
            confirmLabel = "Restore",
            onConfirm = viewModel::confirmRollback,
            onDismiss = viewModel::cancelRollback,
        )
    }

    val preview = state.preview
    if (preview != null) {
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
            // The preview is still shown, because the reason it cannot be installed is the useful part.
            // There is deliberately no button that would call install anyway.
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
    SectionCard(
        title = "Safety",
        modifier = modifier,
        subtitle = SAFETY_SUBTITLE,
        icon = Icons.Filled.Shield,
        action = {
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
        },
    ) {
        SwitchRow(
            title = "Turn all plugins off",
            checked = killSwitchOn,
            onCheckedChange = onSetKillSwitch,
            description = KILL_SWITCH_DESCRIPTION,
        )
        RowDivider()
        NoteBanner(
            text = if (activeThemeName == null) {
                "GameCore's own theme is in force."
            } else {
                "$activeThemeName is supplying the theme the app is wearing."
            },
            tone = if (activeThemeName == null) Tone.Muted else Tone.Accent,
            icon = Icons.Filled.Palette,
        )
        Spacer(modifier = Modifier.height(Spacing.sm))
        Text(
            text = THEME_RESET_DESCRIPTION,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(Spacing.sm))
        ActionRow {
            // No `enabled` argument, deliberately. Offering this only when a theme is active would make
            // it unavailable in the one case it is needed — a plugin theme the app can no longer read
            // back — and resetting to the built-in theme is a no-op when it is already in force.
            Button(onClick = onResetTheme) { Text("Use GameCore's own theme") }
        }
    }
}

/**
 * Install from a file, with the trust boundary stated before the button rather than after it.
 *
 * [note] is this card's own, separate from the screen's message banner: a file that was too large or
 * unreadable never reached the registry, so saying so next to the button that produced it is more use
 * than a banner at the top of a screen the user may have scrolled past.
 */
@Composable
private fun InstallCard(
    note: String?,
    isPreviewing: Boolean,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Install a plugin",
        modifier = modifier,
        subtitle = "From a .gcplugin file on this device",
        icon = Icons.Filled.Download,
    ) {
        Text(
            text = INSTALL_DESCRIPTION,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (isPreviewing) {
            Spacer(modifier = Modifier.height(Spacing.sm))
            NoteBanner(
                text = "Reading the package. Nothing has been installed — you will see what it asks " +
                    "for before anything is written.",
                tone = Tone.Muted,
                icon = Icons.Filled.Info,
            )
        }
        if (note != null) {
            Spacer(modifier = Modifier.height(Spacing.sm))
            NoteBanner(text = note, tone = Tone.Warning, icon = Icons.Filled.Warning)
        }
        Spacer(modifier = Modifier.height(Spacing.sm))
        ActionRow {
            Button(onClick = onPick, enabled = !isPreviewing) { Text("Choose a file") }
        }
    }
}

/**
 * The list's order, as three chips.
 *
 * Nothing here reaches the registry. The order is a lens over rows the screen already has, so choosing
 * one cannot change what any plugin is allowed to do — and the explanation underneath says what "date"
 * actually measures, because for the bundled plugins it is the app's install date.
 */
@Composable
private fun SortCard(
    sort: PluginSort,
    onSort: (PluginSort) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Order",
        modifier = modifier,
        subtitle = "How the installed list is arranged",
        icon = Icons.Filled.Sort,
    ) {
        ChoiceRow(
            options = PluginSort.entries.toList(),
            selected = sort,
            onSelect = onSort,
            label = { it.label },
            perRow = 3,
        )
        Spacer(modifier = Modifier.height(Spacing.sm))
        Text(
            text = ORDER_DESCRIPTION,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One plugin.
 *
 * The switch is [Switch] rather than a [SwitchRow] because the row carries a name, a developer, a
 * version, a description and badges, and the whole card is a tap target for the detail screen.
 *
 * Incompatible plugins keep their switch, disabled, with the reason printed below it. Hiding it would
 * leave the user with a row that looks like every other except that nothing can be done to it; §32's
 * point is that a control must never be tapped and do nothing, and a visibly disabled one beside its
 * reason is the honest version of that.
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
    ClickableCard(onClick = onOpen, modifier = modifier.fillMaxWidth()) {
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
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = row.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = SUMMARY_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(modifier = Modifier.height(Spacing.sm))
        BadgeRow(row = row, killSwitchOn = killSwitchOn)

        if (!row.compatibility.isRunnable) {
            NoteBanner(
                text = row.compatibility.reason,
                tone = Tone.Danger,
                icon = Icons.Filled.Warning,
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
        }

        if (row.suspended) {
            NoteBanner(
                text = row.suspensionDetail ?: SUSPENDED_GENERIC,
                tone = Tone.Warning,
                icon = Icons.Filled.Block,
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
        }

        if (row.quarantined) {
            NoteBanner(
                text = row.quarantineDetail ?: QUARANTINED_GENERIC,
                tone = Tone.Danger,
                icon = Icons.Filled.Block,
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
        }

        ActionRow {
            if (row.suspended) {
                TextButton(onClick = onClearSuspension) { Text("Let it try again") }
            }
            if (row.quarantined) {
                TextButton(onClick = onReleaseQuarantine) { Text("Release") }
            }
            // Shown only when the registry found a previous package on disk, so this is never a tap
            // that discovers there is nothing to restore.
            if (row.canRollback) {
                TextButton(onClick = onAskRollback) { Text("Roll back") }
            }
            // Disabled rather than absent for a bundled plugin, with its origin badge directly above
            // saying why: the rows stay the same shape down the list, and a disabled button is not a
            // tap that did nothing.
            TextButton(onClick = onAskRemove, enabled = row.canBeRemoved) { Text("Remove") }
        }
    }
}

/**
 * The row's badges, in the order a worried user needs them.
 *
 * Compatibility and suspension come before anything cosmetic, because they are the two reasons a plugin
 * the user switched on is not doing anything. Chunked into fixed columns rather than flowed: `FlowRow`
 * is still experimental in the pinned Compose version, which is the same call made in
 * `CapabilityScreen` and `OverlayControlPanel`.
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
    badges.chunked(CHIPS_PER_ROW).forEach { line ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            line.forEach { (text, tone) ->
                Box(modifier = Modifier.weight(1f)) { StatusChip(text = text, tone = tone) }
            }
            repeat(CHIPS_PER_ROW - line.size) { Spacer(modifier = Modifier.weight(1f)) }
        }
    }
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
