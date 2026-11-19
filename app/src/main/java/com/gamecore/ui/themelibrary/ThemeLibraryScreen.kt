package com.gamecore.ui.themelibrary

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.ui.components.ChoiceChip
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionLabel
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.Tone
import com.gamecore.ui.theme.StatusColor
import com.gamecore.ui.theme.toColorScheme
import com.gamecore.ui.theme.toFontFamily
import com.gamecore.ui.theme.toShapes

/**
 * The Theme Library: every theme the app ships, previewed before it is applied.
 *
 * ## Why a screen rather than a longer list in Plugins
 *
 * The manager's theme switch is a row per plugin, which is right for one installed theme and useless
 * for 254. A library is a different question — "what could this app look like?" — and it is answered by
 * seeing the palettes side by side, not by reading plugin names. The two surfaces still share one
 * registry and one apply path; this screen is a lens, not an engine.
 *
 * ## What a card is allowed to claim
 *
 * The preview is drawn from the same [toColorScheme], [toShapes] and font resolution the whole app
 * uses, inside a nested `MaterialTheme`, so the swatch is the theme rather than an illustration of it.
 * It deliberately does **not** call `GameCoreTheme`: that would set system bars, density and the glass
 * layer for every card. The card is a sample of the palette and the type, and the screen says so.
 *
 * Applying a theme whose plugin is off or ungranted changes the plugin's state, so that tap is held by
 * a confirmation naming exactly what it will do. A theme the library cannot apply — incompatible,
 * quarantined, stopped — says which and routes to Plugins instead of offering a button that would fail.
 */
@Composable
fun ThemeLibraryScreen(
    onBack: () -> Unit,
    onOpenPlugins: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ThemeLibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val padded = Modifier.padding(horizontal = ScreenPadding)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // Two columns on a phone, four on a tablet, and never more than the eye can compare at once.
        // At a large font scale the floor drops to two even on a tablet, because a card's text has to
        // fit beside its neighbour rather than be measured by the screen alone.
        val available = maxWidth - ScreenPadding * 2
        val byWidth = (available / 168.dp).toInt().coerceIn(2, 4)
        val columns = if (LocalDensity.current.fontScale >= 1.3f) byWidth.coerceAtMost(2) else byWidth

        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = ScreenBottomPadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                ScreenHeader(
                    title = "Theme Library",
                    subtitle = "Every theme bundled with GameCore, previewed before you apply it",
                    onBack = onBack,
                    action = {
                        if (state.activeThemeName != null) {
                            TextButton(onClick = viewModel::reset) { Text("Reset") }
                        }
                    },
                )
            }

            if (state.killSwitchOn) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    NoteBanner(
                        text = "The plugin kill switch is on, so no plugin theme can be applied. " +
                            "Turn it off in Plugins first.",
                        tone = Tone.Warning,
                        icon = Icons.Filled.Warning,
                        modifier = padded,
                        action = {
                            TextButton(onClick = onOpenPlugins) { Text("Plugins") }
                        },
                    )
                }
            }

            state.message?.let { message ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    NoteBanner(
                        text = message,
                        tone = if (state.messageIsWarning) Tone.Warning else Tone.Accent,
                        modifier = padded,
                        action = {
                            TextButton(onClick = viewModel::dismissMessage) { Text("Dismiss") }
                        },
                    )
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    modifier = padded.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Search themes") },
                    placeholder = { Text("Name, plugin, developer or font") },
                    leadingIcon = {
                        Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(19.dp))
                    },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = viewModel::clearQuery) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Clear search",
                                    modifier = Modifier.size(19.dp),
                                )
                            }
                        }
                    },
                )
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    modifier = padded
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ThemeLibraryFilter.entries.forEach { filter ->
                        ChoiceChip(
                            text = filter.label,
                            isSelected = state.filter == filter,
                            onClick = { viewModel.setFilter(filter) },
                        )
                    }
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionLabel(
                    text = when {
                        state.isLoading -> "Loading themes"
                        state.isFiltered -> "${state.entries.size} of ${state.totalCount} themes"
                        else -> "${state.totalCount} themes"
                    },
                    subtitle = when {
                        state.activeThemeName != null -> "Wearing \"${state.activeThemeName}\"."
                        else -> "GameCore's own theme is in use."
                    },
                    modifier = padded,
                )
            }

            if (state.isLoading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier = padded
                            .fillMaxWidth()
                            .heightIn(min = 120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            } else if (state.isFilteredEmpty) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    NoMatches(
                        query = state.query,
                        onClear = {
                            viewModel.clearQuery()
                            viewModel.setFilter(ThemeLibraryFilter.ALL)
                        },
                        modifier = padded,
                    )
                }
            } else {
                items(state.entries, key = { it.pluginId }) { entry ->
                    ThemeCard(
                        entry = entry,
                        killSwitchOn = state.killSwitchOn,
                        onApply = { viewModel.apply(entry.pluginId) },
                        onOpenPlugins = onOpenPlugins,
                    )
                }
            }
        }
    }

    state.pendingApply?.let { pending ->
        ApplyThemeDialog(
            pending = pending,
            onConfirm = viewModel::confirmApply,
            onDismiss = viewModel::dismissApply,
        )
    }
}

/**
 * One theme, as a sample of itself.
 *
 * The preview is nested `MaterialTheme` rather than a hand-built row of colour chips: the palette,
 * the corner radius and the face are exactly the ones the app would wear, which is the only kind of
 * preview worth trusting. It is marked decorative for TalkBack — the card's name, plugin, mode and
 * button carry the information, and a screen reader walking three coloured rectangles adds nothing.
 */
@Composable
private fun ThemeCard(
    entry: ThemeLibraryEntry,
    killSwitchOn: Boolean,
    onApply: () -> Unit,
    onOpenPlugins: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = entry.theme
    val outer = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = outer.surface,
        contentColor = outer.onSurface,
        border = BorderStroke(1.dp, outer.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            MaterialTheme(
                colorScheme = theme.toColorScheme(),
                shapes = theme.toShapes(),
            ) {
                ThemePreview(entry)
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = theme.name,
                style = MaterialTheme.typography.titleSmall,
                color = outer.onSurface,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.pluginName,
                style = MaterialTheme.typography.bodySmall,
                color = outer.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(entry.modeLabel)
                    append(" · ")
                    append(entry.fontLabel)
                    if (theme.glass != null) append(" · Glass")
                },
                style = MaterialTheme.typography.labelSmall,
                color = outer.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(8.dp))
            when {
                entry.isActive -> StatusChip("Active", StatusColor.Ok)
                entry.blockedReason != null -> {
                    Text(
                        text = entry.blockedReason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = onOpenPlugins) { Text("Open Plugins") }
                }
                else -> Button(
                    onClick = onApply,
                    enabled = !killSwitchOn,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (entry.requiresConfirmation) "Apply…" else "Apply")
                }
            }
        }
    }
}

@Composable
private fun ThemePreview(entry: ThemeLibraryEntry) {
    val theme = entry.theme
    val scheme = MaterialTheme.colorScheme
    val family = theme.toFontFamily()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(104.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(scheme.background)
            .clearAndSetSemantics { },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(scheme.primary),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Aa",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = family,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = scheme.onSurface,
                )
                Spacer(modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(scheme.tertiary),
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = scheme.primary,
                    contentColor = scheme.onPrimary,
                ) {
                    Text(
                        text = "Apply",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = family),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = scheme.surface,
                    contentColor = scheme.onSurface,
                    border = BorderStroke(1.dp, scheme.outline),
                ) {
                    Text(
                        text = "Aa",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = family),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun NoMatches(query: String, onClear: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (query.isBlank()) "No themes match this filter." else "No theme matches \"$query\".",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Search covers theme names, plugins, developers and fonts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = onClear) { Text("Show all themes") }
        }
    }
}

/**
 * The one confirmation in the library, and it names every change it will make.
 *
 * "Apply" would be a lie here: the tap may also switch a plugin on and write a permission. The dialog
 * states the grant, says the plugin stays installed, and points at Reset, so the user is agreeing to
 * the whole operation rather than to a repaint.
 */
@Composable
private fun ApplyThemeDialog(
    pending: ThemeApplyConfirmation,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sentence = ThemeLibraryLogic.grantSentence(
        pending.needsEnable,
        pending.needsGrant,
        pending.pluginName,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Apply \"${pending.themeName}\"?") },
        text = {
            Text(
                "$sentence Its existing grants are kept, and it stays installed. " +
                    "Reset in the library returns the app to GameCore's own theme at any time.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Apply theme") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
