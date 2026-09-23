package com.gamecore.ui.sessions

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.core.model.SessionFilterKind
import com.gamecore.core.model.SessionKind
import com.gamecore.core.model.SessionSort
import com.gamecore.core.model.SessionTile
import com.gamecore.core.model.ThermalClass
import com.gamecore.core.model.clearHistoryMessage
import com.gamecore.core.model.emptyKindMessage
import com.gamecore.core.model.emptyKindTitle
import com.gamecore.core.model.tilesSentence
import com.gamecore.ui.Destination
import com.gamecore.ui.components.ActionRow
import com.gamecore.ui.components.ChoiceChip
import com.gamecore.ui.components.ChoiceRow
import com.gamecore.ui.components.ClickableCard
import com.gamecore.ui.components.ConfirmDialog
import com.gamecore.ui.components.EmptyState
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.OnResume
import com.gamecore.ui.components.PlainCard
import com.gamecore.ui.components.ReadoutRow
import com.gamecore.ui.components.RowDivider
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.colour
import com.gamecore.ui.games.GameIcon
import com.gamecore.ui.theme.Density
import com.gamecore.ui.theme.Spacing
import com.gamecore.ui.theme.statusColor
// Two extensions called colour() meet on this screen: Tone's, which paints the app's own accents, and
// StatusColor's, which paints a classified temperature. Aliasing one keeps both call sites readable and
// keeps the thermal colour coming from the single classifier mapping rather than a second guess at it.
import com.gamecore.ui.theme.colour as statusColour

/**
 * §6's history list: everything this device recorded, with the totals above it.
 *
 * The screen's one structural idea is that a row is no longer always a game session. GameCore records play
 * sessions and Aim Lab runs in two repositories that share no table and no model, and until this redesign
 * the screen listed only the first — so a user who had trained for an hour saw a history that did not
 * contain it, under a chip that said "All". The merge happens in [SessionsViewModel]; what a card does with
 * a row is decided by [SessionRow.kind], never by testing whether some field happens to be null.
 *
 * Everything below the live card is a record. The live card is deliberately a different kind of thing: it
 * has no report to open, its figures have not settled, and it carries the "Active" chip. The two
 * destructive actions ([SessionsViewModel.askDelete] and [SessionsViewModel.askClear]) both go through a
 * dialog, and the clear dialog says out loud that it leaves Aim Lab's own history alone — the one claim a
 * merged list makes easy to get wrong.
 */
@Composable
fun SessionsScreen(
    onOpenReport: (Long) -> Unit,
    onOpenAimLabRun: (Long) -> Unit,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SessionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // statistics() is a set of aggregate queries rather than a flow, so resuming re-asks.
    OnResume { viewModel.onResume() }

    val padded = Modifier.padding(horizontal = ScreenPadding)
    // §3's compact density: on a list screen the gap between cards is the measurement a user actually
    // feels, so it is the one that shrinks. Type sizes and touch targets are left alone.
    val cardGap = if (state.isCompact) Spacing.md * Density.COMPACT_FACTOR else Spacing.md

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = Spacing.xs, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(cardGap),
    ) {
        item {
            ScreenHeader(
                title = "Sessions",
                subtitle = state.subtitle,
                // Offered only when there is a game history to clear. The button never touches Aim Lab's
                // runs, and one that would delete nothing the user can see is worse than no button.
                action = if (state.gameCount > 0) {
                    { TextButton(onClick = viewModel::askClear) { Text("Clear") } }
                } else {
                    null
                },
            )
        }

        state.message?.let { message ->
            item {
                NoteBanner(
                    text = message,
                    tone = Tone.Accent,
                    icon = Icons.Filled.Info,
                    modifier = padded,
                    action = { TextButton(onClick = viewModel::dismissMessage) { Text("OK") } },
                )
            }
        }

        if (!state.trackingEnabled) {
            item {
                NoteBanner(
                    text = TRACKING_OFF,
                    tone = Tone.Warning,
                    icon = Icons.Filled.Visibility,
                    modifier = padded,
                    action = {
                        TextButton(onClick = { onNavigate(Destination.Settings) }) { Text("Settings") }
                    },
                )
            }
        }

        state.live?.let { live ->
            item { LiveCard(live = live, onStop = viewModel::stopLiveSession, modifier = padded) }
        }

        if (state.statistics.isNotEmpty()) {
            item { StatisticsCard(state = state, modifier = padded) }
        }

        if (state.isEmpty && !state.isLoading) {
            item {
                EmptyState(
                    icon = Icons.Filled.History,
                    title = emptyKindTitle(SessionFilterKind.ALL),
                    message = emptyKindMessage(SessionFilterKind.ALL),
                    actionLabel = "Set up a game",
                    onAction = { onNavigate(Destination.Games) },
                )
            }
        }

        if (!state.isEmpty) {
            item { KindFilterRow(state = state, onKind = viewModel::setKind) }
            if (state.showsGameFilters) {
                item { GameFilterRow(state = state, onFilter = viewModel::setFilter) }
            }
            item { SortCard(state = state, onSort = viewModel::setSort, modifier = padded) }
        }

        // Keyed on the composite key and never on the id: the two repositories number their rows
        // independently, so a game session and a training run can both be id 5.
        items(state.rows, key = { it.key }) { row ->
            SessionCard(
                row = row,
                onOpen = {
                    when (row.kind) {
                        SessionKind.GAME -> onOpenReport(row.id)
                        SessionKind.AIMLAB -> onOpenAimLabRun(row.id)
                    }
                },
                onDelete = { viewModel.askDelete(row) },
                modifier = padded,
            )
        }

        // Nothing matched the chips, but something was recorded — a different sentence from "nothing was
        // ever recorded", and the user needs to be told which of the two they are looking at.
        if (!state.isEmpty && state.rows.isEmpty() && !state.isLoading) {
            item {
                val byGame = state.filterPackage != null
                EmptyState(
                    icon = Icons.Filled.Insights,
                    title = if (byGame) "No sessions for ${state.filterLabel}" else emptyKindTitle(state.kind),
                    message = if (byGame) FILTER_HIDING else emptyKindMessage(state.kind),
                    actionLabel = "Show everything",
                    onAction = viewModel::clearFilters,
                )
            }
        }

        item { RetentionCard(aimLabCount = state.aimLabCount, modifier = padded) }
    }

    state.pendingDelete?.let { row ->
        ConfirmDialog(
            title = "Delete this session?",
            message = "${row.label} · ${row.started} · ${row.duration}. The session and its samples are " +
                "removed from this device, and nothing keeps a copy.",
            confirmLabel = "Delete",
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::cancelDelete,
        )
    }

    if (state.pendingClear) {
        ConfirmDialog(
            title = "Clear game session history?",
            // What this promises depends on how many Aim Lab runs are sitting in the same list that the
            // button will not touch, so the sentence is built in core/model and tested there.
            message = clearHistoryMessage(state.aimLabCount),
            confirmLabel = "Delete everything",
            onConfirm = viewModel::confirmClear,
            onDismiss = viewModel::cancelDelete,
        )
    }
}

/**
 * The session being recorded right now.
 *
 * Given its own card, above the list and outside it, because it is not a record yet: the duration is still
 * moving, the figures are as of the last flush, and there is no report to open. §6 puts the "Active" chip
 * here rather than on a row, because the list below holds finished sessions only — a running one has never
 * been in it. Ending it from here is a clean stop, and the row it leaves behind carries no "at least".
 */
@Composable
private fun LiveCard(live: LiveSession, onStop: () -> Unit, modifier: Modifier = Modifier) {
    SectionCard(
        title = "Recording now",
        icon = Icons.Filled.Insights,
        modifier = modifier,
        // A word, not a pulsing dot: §2 does not allow a state to be carried by colour or motion alone.
        action = { StatusChip(text = "Active", tone = Tone.Accent) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GameIcon(packageName = live.packageName, label = live.label, size = ROW_ICON)
            Spacer(modifier = Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = live.label,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = live.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(Spacing.md))
            Text(text = live.duration, style = MaterialTheme.typography.headlineSmall)
        }
        if (live.tiles.isNotEmpty()) {
            Spacer(modifier = Modifier.height(Spacing.md))
            SessionTileStrip(tiles = live.tiles, spoken = tilesSentence(live.tiles))
        }
        ActionRow {
            TextButton(onClick = onStop) { Text("Stop recording") }
        }
    }
}

/**
 * The totals, across every game session ever recorded.
 *
 * The subtitle is doing real work twice over. These figures ignore the chips below, so a user reading "14 h
 * played" under a list filtered to one game has to be told which of the two they are looking at. And they
 * are game aggregates: the repository that answers them has never seen a training run, so the card says
 * "game session" rather than quietly counting Aim Lab into a total it did not measure.
 */
@Composable
private fun StatisticsCard(state: SessionsUiState, modifier: Modifier = Modifier) {
    SectionCard(
        title = "All time",
        subtitle = "Across every game session recorded, whatever the chips below show",
        icon = Icons.Filled.History,
        modifier = modifier,
    ) {
        state.statistics.forEach { ReadoutRow(it) }
    }
}

/**
 * §6's All / Games / Aim Lab chips.
 *
 * All three are always drawn, each with its whole-history count, including the ones that would select
 * nothing. A user who has never opened Aim Lab learns that the tab exists and that it has recorded nothing;
 * a user who has trained learns that those runs are in this list and can be looked at on their own. Hiding
 * an empty chip would make the screen's shape depend on data the user cannot see.
 */
@Composable
private fun KindFilterRow(state: SessionsUiState, onKind: (SessionFilterKind) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        items(state.kinds, key = { it.kind.name }) { chip ->
            ChoiceChip(
                text = "${chip.label} · ${chip.count}",
                isSelected = chip.kind == state.kind,
                onClick = { onKind(chip.kind) },
            )
        }
    }
}

private const val ALL_GAMES_KEY = "__all__"

/**
 * One chip per game, each with its own count, subordinate to the kind chips above.
 *
 * A [LazyRow] rather than a wrapping layout: the number of games a user has recorded is unbounded, and this
 * is the one place in the app where horizontal scrolling is the honest answer to that. The row is hidden
 * entirely when it would filter nothing — see [SessionsUiState.showsGameFilters].
 */
@Composable
private fun GameFilterRow(state: SessionsUiState, onFilter: (String?) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        items(state.games, key = { it.packageName ?: ALL_GAMES_KEY }) { filter ->
            ChoiceChip(
                text = "${filter.label} · ${filter.count}",
                isSelected = filter.packageName == state.filterPackage,
                onClick = { onFilter(filter.packageName) },
            )
        }
    }
}

@Composable
private fun SortCard(state: SessionsUiState, onSort: (SessionSort) -> Unit, modifier: Modifier = Modifier) {
    SectionCard(title = "Order", modifier = modifier) {
        ChoiceRow(
            options = SessionSort.entries.toList(),
            selected = state.sort,
            onSelect = onSort,
            label = { it.label },
            perRow = 2,
        )
    }
}

/**
 * One recorded session — a game session or an Aim Lab run, drawn the same way on purpose.
 *
 * A merged list only works if both kinds of row answer the same three questions in the same three places:
 * what produced it, when, for how long, and then the three figures §6 asks for. What differs is carried
 * explicitly — the icon, the tiles' meaning, which actions exist — and never inferred from a null.
 *
 * The card is a [ClickableCard] only when there is somewhere to go. An Aim Lab run with Aim Lab switched
 * off is drawn as a [PlainCard] with its reason underneath, because a card that ripples under a tap and
 * then does nothing is a worse answer than one that never claimed to be tappable.
 */
@Composable
private fun SessionCard(
    row: SessionRow,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (row.canOpen) {
        ClickableCard(onClick = onOpen, modifier = modifier) {
            SessionCardBody(row = row, onOpen = onOpen, onDelete = onDelete)
        }
    } else {
        PlainCard(modifier = modifier) {
            SessionCardBody(row = row, onOpen = onOpen, onDelete = onDelete)
        }
    }
}

@Composable
private fun SessionCardBody(row: SessionRow, onOpen: () -> Unit, onDelete: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RowIcon(row = row)
        Spacer(modifier = Modifier.width(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.label,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.started,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(Spacing.md))
        Text(text = row.duration, style = MaterialTheme.typography.titleMedium)
    }
    if (row.tiles.isNotEmpty()) {
        Spacer(modifier = Modifier.height(Spacing.md))
        SessionTileStrip(tiles = row.tiles, spoken = row.spokenTiles)
    }
    row.note?.let { note ->
        Spacer(modifier = Modifier.height(Spacing.sm))
        Text(
            text = note,
            style = MaterialTheme.typography.bodySmall,
            color = Tone.Warning.colour(),
        )
    }
    // Both can be absent at once: an Aim Lab run with its screen switched off is read-only and
    // unreachable, and an empty action row would only add height.
    if (row.canOpen || row.canDelete) {
        ActionRow {
            if (row.canOpen) {
                TextButton(onClick = onOpen) {
                    Text(if (row.kind == SessionKind.GAME) "Report" else "Open run")
                }
            }
            if (row.canDelete) {
                TextButton(onClick = onDelete) { Text("Delete", color = Tone.Danger.colour()) }
            }
        }
    }
}

/**
 * What produced the row, at a glance.
 *
 * A game gets its real launcher icon, which is how a user recognises it in a list of twenty. An Aim Lab run
 * has no package and therefore no icon to look up, so it gets a drawn badge instead of a blank square — the
 * two kinds of row have to be distinguishable while scrolling, and §2 will not let that distinction rest on
 * a colour.
 */
@Composable
private fun RowIcon(row: SessionRow) {
    val packageName = row.packageName
    if (row.kind == SessionKind.GAME && packageName != null) {
        GameIcon(packageName = packageName, label = row.label, size = ROW_ICON)
        return
    }
    Box(
        modifier = Modifier
            .size(ROW_ICON)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (row.kind == SessionKind.AIMLAB) Icons.Filled.TrackChanges else Icons.Filled.History,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(ROW_ICON / 2),
        )
    }
}

/**
 * §6's three figures, and the reasons behind the ones that are not figures.
 *
 * Drawn as a strip rather than as rows because at this size they are scanned down a column, not read. The
 * reasons are the part that cannot be dropped: §6 says an unrecorded value reads as "Unavailable" plus why,
 * so every tile that is not a reading prints its sentence underneath. A warm-or-worse temperature prints
 * its sentence too — it is the one reading whose colour carries meaning, and §2 does not allow that meaning
 * to exist only as a colour.
 *
 * The whole strip is collapsed into [spoken] for a screen reader. Nine separate pieces of text would turn
 * one session into nine stops on the way down the list, and the sentence keeps the reasons in it.
 */
@Composable
private fun SessionTileStrip(tiles: List<SessionTile>, spoken: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = spoken },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            tiles.forEach { tile -> TileCell(tile = tile, modifier = Modifier.weight(1f)) }
        }
        tiles.filter { it.needsDetailOnScreen }.forEach { tile ->
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = "${tile.label}: ${tile.detail}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TileCell(tile: SessionTile, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val thermal = tile.thermal
    val valueColour: Color = when {
        // A missing reading is never dressed as one: it is muted, it says "Unavailable", and its reason is
        // printed under the strip.
        !tile.isReading -> scheme.onSurfaceVariant
        // Colour only once the temperature means something, and only ever through the single classifier
        // mapping, so the word under the strip and the colour above it can never disagree.
        thermal != null && thermal.severity >= ThermalClass.WARM.severity -> thermal.statusColor().statusColour()
        else -> scheme.onSurface
    }
    Column(modifier = modifier) {
        Text(
            text = tile.label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(Spacing.xs))
        Text(
            // "Unavailable" is a long word in a third of a card. It is stepped down rather than clipped,
            // because a truncated "Unavaila…" is exactly the kind of half-fact §6 is trying to remove.
            text = tile.value,
            style = if (tile.isReading) {
                MaterialTheme.typography.titleSmall
            } else {
                MaterialTheme.typography.labelMedium
            },
            color = valueColour,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Where the history lives, said once on the screen that holds it.
 *
 * §30 and §24A.5 are both user-visible facts here: nothing recorded leaves the device, and the database it
 * sits in is encrypted. A user deciding whether to let an app record what they play for months is entitled
 * to read that without going looking for it.
 *
 * The last paragraph appears only when there are Aim Lab runs in the list, and it exists because the merge
 * created a question that did not exist before: this screen shows records it does not own. Saying so is the
 * difference between "Clear" being understood and being a nasty surprise.
 */
@Composable
private fun RetentionCard(aimLabCount: Int, modifier: Modifier = Modifier) {
    SectionCard(title = "Where this is kept", icon = Icons.Filled.Info, modifier = modifier) {
        Text(
            text = RETENTION,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RowDivider()
        Text(
            text = SAMPLES_NOTE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (aimLabCount > 0) {
            RowDivider()
            Text(
                text = AIM_LAB_RETENTION,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Big enough to recognise a launcher icon by, small enough that a row stays one line of text tall. */
private val ROW_ICON = 40.dp

private const val TRACKING_OFF =
    "Session tracking is off, so no new game session is being recorded. What is already here stays until " +
        "you delete it."

private const val FILTER_HIDING =
    "The filter above is hiding everything else that was recorded."

private const val RETENTION =
    "Every game session is stored in GameCore's own encrypted database on this device. Nothing is " +
        "uploaded, there is no account, and no session or sample is ever sent anywhere. The app uses the " +
        "network for one thing — the latency probe — and it is given none of this. Deleting a session or " +
        "clearing the history removes it for good."

private const val SAMPLES_NOTE =
    "Each game session also keeps the individual samples behind its graphs. They go with the session when " +
        "it is deleted, and an export from Settings writes exactly what is here — nothing is reconstructed."

private const val AIM_LAB_RETENTION =
    "Aim Lab runs are listed here but kept by Aim Lab, in its own history. Clearing above leaves them " +
        "alone, and a run is deleted from Aim Lab's results screen."
