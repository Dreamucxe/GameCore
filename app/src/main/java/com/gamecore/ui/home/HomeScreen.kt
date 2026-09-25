package com.gamecore.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.core.common.Formatters
import com.gamecore.core.model.HomeStatus
import com.gamecore.core.model.HomeStatusKind
import com.gamecore.core.model.MemoryReclaimReport
import com.gamecore.core.model.WatchState
import com.gamecore.core.model.chipsSentence
import com.gamecore.core.model.profileClaimNote
import com.gamecore.core.model.shownChips
import com.gamecore.domain.gaming.GamingState
import com.gamecore.ui.Destination
import com.gamecore.ui.components.ABSENT
import com.gamecore.ui.components.ActionRow
import com.gamecore.ui.components.ClickableCard
import com.gamecore.ui.components.NavRow
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.PlainCard
import com.gamecore.ui.components.PreLaunchWarningDialog
import com.gamecore.ui.components.ProfileChipRow
import com.gamecore.ui.components.Readout
import com.gamecore.ui.components.ReadoutRow
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.Sparkline
import com.gamecore.ui.components.StatEntry
import com.gamecore.ui.components.StatStrip
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.SwitchRow
import com.gamecore.ui.components.TileRow
import com.gamecore.ui.components.ToolTile
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.colour
import com.gamecore.ui.games.GameIcon
import com.gamecore.ui.setup.SetupCard
import com.gamecore.ui.setup.SetupCardViewModel
import com.gamecore.ui.theme.Density
import com.gamecore.ui.theme.Spacing
import com.gamecore.ui.theme.StatLabelStyle
import com.gamecore.ui.theme.StatValueStyle
import kotlinx.coroutines.delay

/**
 * The dashboard (§4): the game you are about to play, what the device is doing under it, and the way to
 * everything else.
 *
 * The order is the redesign's argument about what a dashboard is for. Anything asking for a decision — a
 * message, settings a killed session never put back, a thermal warning, a game being tracked right now —
 * is above everything else, because those are the cards with a button on them. Then the one featured
 * profile (§4.2), the live figures (§4.3), the collapsed detail (§4.4/§4.5), the overlay switches (§4.6)
 * and the tools grid (§4.7). A user who opened the app to start a game does it in the first screenful;
 * one who opened it to read a temperature has read it before the navigation begins.
 *
 * Every reading arrives as a [Readout] — a label and a string. §24A.2: this function cannot format a
 * temperature wrongly, or render a missing one as a zero, because it never sees a number. The one figure
 * built here, the tracked session's elapsed time, is a subtraction of two clock readings and nothing the
 * ViewModel needs to hold.
 *
 * @param onNavigate move to a top-level or pushed destination.
 * @param aimLabEnabled whether the Aim Lab section exists. Handed down from the graph rather than read
 *   from a ViewModel here, so the tool tile and the routes behind it are driven by one value: a tile
 *   offering a tap that lands nowhere is worse than no tile.
 * @param onOpenProfile open the editor for one profile by package. Defaulted to the Games tab so the
 *   screen stays correct on its own; the graph wires the real editor.
 * @param onReviewSuggestion open the editor for a §4 suggestion, seeded with the derived draft. Defaulted
 *   to [onOpenProfile] so the screen is correct alone; the graph wires the seeded route (the same editor
 *   destination with the seed flag set), so a suggestion opens as an unsaved draft rather than empty.
 */
@Composable
fun HomeScreen(
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
    aimLabEnabled: Boolean = true,
    onOpenProfile: (String) -> Unit = { onNavigate(Destination.Games) },
    onReviewSuggestion: (String) -> Unit = onOpenProfile,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Collected with a lifecycle, not in the ViewModel. PerformanceMonitor.snapshots samples only while
    // it has a collector, so this is what starts the sampler when the dashboard appears and — more to
    // the point — stops it when the user navigates away. The sparkline ring buffers are filled and
    // cleared by that same subscription (§4.3): no timer runs behind a screen nobody is looking at.
    val readouts by viewModel.readouts.collectAsStateWithLifecycle(HomeReadouts.AWAITING)

    // §A1's card carries its own ViewModel (see SetupCard for why), and Home reads its state here rather
    // than only inside the card so the list can leave the slot out altogether when there is nothing to
    // show. `hiltViewModel()` is scoped to this back-stack entry, so handing the same instance down is
    // the one the card would have resolved for itself.
    val setupViewModel: SetupCardViewModel = hiltViewModel()
    val setupState by setupViewModel.state.collectAsStateWithLifecycle()

    val padded = Modifier.padding(horizontal = ScreenPadding)
    // The one gap value the whole app shares, tightened by the same factor when the user has asked for
    // compact density (§3). Home reading a different spacing from Games would be a visible seam.
    val cardGap = if (state.isCompact) Spacing.md * Density.COMPACT_FACTOR else Spacing.md

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = Spacing.xs, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(cardGap),
    ) {
        item {
            ScreenHeader(
                title = "GameCore",
                subtitle = state.subtitle,
                action = { WatchPill(state.watch) },
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

        // §A1. High in the list because an unfinished setup is the reason the cards below it may be
        // saying "Unavailable" — but below the message banner, which is about something the user just
        // did. The `item` is emitted only when the card has something to say: a hidden card that still
        // occupied a slot would leave a doubled gap here on every correctly configured device, since a
        // `spacedBy` arrangement spaces zero-height items exactly like any other.
        if (setupState.isVisible) {
            item {
                SetupCard(
                    onOpenWizard = { onNavigate(Destination.SetupWizard) },
                    onOpenHealth = { onNavigate(Destination.SetupHealth) },
                    modifier = padded,
                    viewModel = setupViewModel,
                )
            }
        }

        if (state.outstandingRestores > 0) {
            item {
                RestoreCard(
                    count = state.outstandingRestores,
                    isRestoring = state.isRestoring,
                    onRestore = viewModel::restoreOutstanding,
                    onForget = viewModel::forgetOutstanding,
                    modifier = padded,
                )
            }
        }

        if (state.repairedSessions > 0) {
            item {
                RepairNotice(
                    count = state.repairedSessions,
                    onDismiss = viewModel::dismissRepairNotice,
                    onOpenSessions = { onNavigate(Destination.Sessions) },
                    modifier = padded,
                )
            }
        }

        if (state.gaming.isTracking) {
            item {
                SessionCard(
                    gaming = state.gaming,
                    onStop = viewModel::stopSession,
                    modifier = padded,
                )
            }
        }

        state.gaming.reclaim?.let { report ->
            item {
                ReclaimNotice(
                    report = report,
                    onDismiss = viewModel::dismissReclaim,
                    modifier = padded,
                )
            }
        }

        readouts.thermalNote?.let { note ->
            item {
                NoteBanner(
                    text = note,
                    tone = Tone.Warning,
                    icon = Icons.Filled.Thermostat,
                    modifier = padded,
                )
            }
        }

        // §4.2. One featured profile, or the honest "add a game" when there is truly nothing — never a
        // blank hero, and never the empty state while the list is still loading.
        val hero = state.hero
        if (state.isEmpty) {
            item {
                SectionCard(title = "Your games", icon = Icons.Filled.SportsEsports, modifier = padded) {
                    com.gamecore.ui.components.EmptyState(
                        icon = Icons.Filled.SportsEsports,
                        title = "No games yet",
                        message = "Add a game and GameCore will apply its profile when the game starts and " +
                            "put your settings back when it ends.",
                        actionLabel = "Add a game",
                        onAction = { onOpenProfile(Destination.NEW_PROFILE) },
                    )
                }
            }
        } else if (hero != null) {
            item {
                HeroCard(
                    hero = hero,
                    isBusy = state.busyPackage == hero.packageName,
                    anyBusy = state.busyPackage != null,
                    onPlay = { viewModel.play(hero.packageName) },
                    onEdit = { onOpenProfile(hero.packageName) },
                    onApply = { viewModel.applyNow(hero.packageName) },
                    onOpenGames = { onNavigate(Destination.Games) },
                    modifier = padded,
                )
            }
            // §4.2: dots only for more than one profile, and one dot per real profile — nothing padded,
            // nothing capped. Tapping one features that profile; the rule takes back over the moment the
            // chosen profile stops existing.
            if (state.profileCount > 1) {
                item {
                    PagerDots(
                        profiles = state.profiles,
                        selectedIndex = state.heroIndex,
                        onSelect = { pkg -> viewModel.selectProfile(pkg) },
                        modifier = padded,
                    )
                }
            }
        }

        // §4 feature #3. A game with real recorded history but no profile yet, and a starting profile
        // derived only from what it was measured doing. Below the hero because it is an offer, not a task
        // the device is waiting on. Dismiss persists (HomeViewModel.dismissSuggestion), so a game waved
        // away here does not come back tomorrow; Review opens the editor seeded with the draft.
        state.suggestion?.let { suggestion ->
            item {
                SuggestionCard(
                    suggestion = suggestion,
                    onReview = { onReviewSuggestion(suggestion.packageName) },
                    onDismiss = { viewModel.dismissSuggestion(suggestion.packageName) },
                    modifier = padded,
                )
            }
        }

        // §4.3. The five live tiles, two to a row, with the odd one keeping its width. RAM and Temperature
        // carry a sparkline once two real samples exist; the rest show their meter or nothing.
        readouts.metrics.chunked(TILES_PER_ROW).forEach { row ->
            item {
                TileRow(modifier = padded) {
                    row.forEach { readout ->
                        MetricTile(
                            readout = readout,
                            icon = metricIcon(readout.label),
                            trend = trendFor(readout.label, readouts),
                            onClick = { onNavigate(Destination.Performance) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(TILES_PER_ROW - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                }
            }
        }

        item { DeviceCard(device = readouts.device, onNavigate = onNavigate, modifier = padded) }

        item { StatusSection(statuses = state.statuses, onNavigate = onNavigate, modifier = padded) }

        item {
            OverlayCard(
                state = state,
                onSetPill = viewModel::setStatsOverlay,
                onSetButton = viewModel::setFloatingButton,
                onHideAll = viewModel::hideOverlays,
                onNavigate = onNavigate,
                modifier = padded,
            )
        }

        item { ToolsCard(aimLabEnabled = aimLabEnabled, onNavigate = onNavigate, modifier = padded) }
    }

    // §C4. Shown when starting the hero measured a poor connection on a profile that asked to be warned.
    // Outside the LazyColumn: a dialog scrolling with the list would be a dialog that can scroll away.
    state.pendingLaunch?.let { pending ->
        PreLaunchWarningDialog(
            reason = pending.reason,
            onLaunchAnyway = viewModel::confirmPendingLaunch,
            onDontWarn = viewModel::dontWarnPendingLaunch,
            onCancel = viewModel::dismissPendingLaunch,
        )
    }
}

// ------------------------------------------------------------------------------------------- header

/**
 * The §4.1 "Automatic" pill: what GameCore's watching actually amounts to right now.
 *
 * A word, not a colour — §10 forbids conveying state by tint alone, so the state's own label is the
 * signal and the tint only reinforces it. "Not watching" is the one that wants the user: automatic
 * application is on but nothing on this device can see a game reach the front, so a profile the user
 * expects to apply itself never will. The other two are working-as-configured and are muted.
 */
@Composable
private fun WatchPill(watch: WatchState) {
    val tone = when (watch) {
        WatchState.WATCHING -> Tone.Good
        WatchState.MANUAL -> Tone.Muted
        WatchState.BLIND -> Tone.Warning
    }
    StatusChip(text = watch.label, tone = tone, icon = Icons.Filled.Visibility)
}

// --------------------------------------------------------------------------------------------- hero

/**
 * The featured profile (§4.2): who it is, what it does, and the one big button that starts it.
 *
 * The identity half — icon, name, package, chips — is dimmed when the profile is switched off or its game
 * is gone, but the controls never are, so the card never looks inert. The state is always a [StatusChip]
 * word beside the dimming, because §10 does not allow alpha to carry meaning on its own. The chip list is
 * the shared generator's, capped with a spoken "+N more", and the whole row collapses into one
 * screen-reader stop that reads the *full* effect list so the visual cap never hides an effect.
 *
 * "Start game" is labelled for exactly what it does (§4.2): [HomeViewModel.play] launches the package and
 * nothing else. Applying the profile is a separate item in the overflow, kept apart for the same reason
 * the Games screen keeps them apart — with automatic application on, one tap that also wrote the refresh
 * rate on some devices and not others would be the opposite of predictable. The chevron opens the editor.
 */
@Composable
private fun HeroCard(
    hero: HomeProfile,
    isBusy: Boolean,
    anyBusy: Boolean,
    onPlay: () -> Unit,
    onEdit: () -> Unit,
    onApply: () -> Unit,
    onOpenGames: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimmed = !hero.isEnabled || !hero.isInstalled
    val identityAlpha = if (dimmed) DIMMED_ALPHA else 1f

    PlainCard(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onEdit),
        ) {
            GameIcon(
                packageName = hero.packageName,
                label = hero.label,
                size = 52.dp,
                modifier = Modifier.alpha(identityAlpha),
            )
            Spacer(modifier = Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = hero.label,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alpha(identityAlpha),
                )
                Text(
                    text = hero.packageName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alpha(identityAlpha),
                )
            }
            Spacer(modifier = Modifier.width(Spacing.sm))
            HeroStateChip(hero)
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // The chips, capped, with the remainder spoken as "+N more" rather than a chip pretending to be an
        // effect. Collapsed into one screen-reader stop reading the full effect list, so the cap is a
        // sighted-only convenience and never a place data hides.
        val chips = shownChips(hero.chips)
        if (chips.shown.isNotEmpty()) {
            val sentence = chipsSentence(hero.chips)
            Spacer(modifier = Modifier.height(Spacing.sm))
            Column(
                modifier = Modifier
                    .alpha(identityAlpha)
                    .then(
                        if (sentence != null) {
                            Modifier.clearAndSetSemantics { contentDescription = sentence }
                        } else {
                            Modifier
                        },
                    ),
            ) {
                ProfileChipRow(chips = chips.shown)
                if (chips.overflow > 0) {
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Text(
                        text = "+${chips.overflow} more",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // The honest caveat under the chips — only for the two states the chips do not speak for
        // themselves: nothing configured yet, or effects that write no device setting.
        profileClaimNote(hero.claim)?.let { note ->
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                text = note,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!hero.isInstalled) {
            Spacer(modifier = Modifier.height(Spacing.sm))
            NoteBanner(
                text = "This game is not installed at the moment. The profile is kept, and will start " +
                    "working again if you reinstall it.",
                tone = Tone.Muted,
                icon = Icons.Filled.Info,
            )
        }

        Spacer(modifier = Modifier.height(Spacing.md))
        ActionRow {
            Button(
                onClick = onPlay,
                enabled = hero.isInstalled && !anyBusy,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text("Start game")
            }
            Spacer(modifier = Modifier.weight(1f))
            HeroOverflowMenu(
                label = hero.label,
                isBusy = isBusy,
                enabled = !anyBusy,
                canApply = hero.isInstalled,
                onApply = onApply,
                onOpenGames = onOpenGames,
            )
        }
    }
}

/** The hero's state word — tinted, but the word is always the signal (§10). */
@Composable
private fun HeroStateChip(hero: HomeProfile) {
    val (text, tone) = when {
        !hero.isInstalled -> "Not installed" to Tone.Warning
        hero.isEnabled -> "On" to Tone.Good
        else -> "Off" to Tone.Muted
    }
    StatusChip(text = text, tone = tone)
}

/**
 * The hero's overflow: Apply now, and the way to the full list.
 *
 * Apply lives here rather than as a second big button because §4.2 asks for one primary action and the
 * launch is it. The busy spinner is shown on the menu item so a slow write is visibly in progress without
 * a second control appearing on the card.
 */
@Composable
private fun HeroOverflowMenu(
    label: String,
    isBusy: Boolean,
    enabled: Boolean,
    canApply: Boolean,
    onApply: () -> Unit,
    onOpenGames: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    if (isBusy) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(modifier = Modifier.width(Spacing.sm))
    }
    androidx.compose.material3.IconButton(onClick = { expanded = true }, enabled = enabled) {
        Icon(imageVector = Icons.Filled.MoreVert, contentDescription = "More options for $label")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text("Apply now") },
            enabled = canApply,
            onClick = {
                expanded = false
                onApply()
            },
        )
        DropdownMenuItem(
            text = { Text("All games") },
            onClick = {
                expanded = false
                onOpenGames()
            },
        )
    }
}

/**
 * The pager dots (§4.2): one per real profile, the featured one filled.
 *
 * Tappable, so the dots are a way to feature a profile and not only an indicator, and each carries a
 * spoken label because a bare dot says nothing to a screen reader. The count is [profiles].size exactly —
 * a dot with no profile behind it would be the screen inventing a game.
 */
@Composable
private fun PagerDots(
    profiles: List<HomeProfile>,
    selectedIndex: Int,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.CenterHorizontally),
    ) {
        profiles.forEachIndexed { index, profile ->
            val selected = index == selectedIndex
            val colour = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
            // The dot is small by design, but the tap target around it is a full 48dp (§10). The Box that
            // carries the click and the label is the target; the visible dot is drawn inside it.
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable { onSelect(profile.packageName) }
                    .semantics {
                        contentDescription = if (selected) {
                            "Showing ${profile.label}"
                        } else {
                            "Show ${profile.label}"
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(if (selected) 10.dp else 8.dp)
                        .clip(CircleShape)
                        .background(colour),
                )
            }
        }
    }
}

// ------------------------------------------------------------------------------------------ metrics

/**
 * One live figure as a tile (§4.3), with an optional sparkline of its recent real samples.
 *
 * A hand-built tile rather than [com.gamecore.ui.components.ReadoutTile] for one reason: RAM and
 * Temperature carry a trend line, and the shared tile has no slot for one. Everything else here is the
 * shared tile's own treatment — the uppercase label, the monospaced value, the tone that is also the
 * value's colour — so a tile with a sparkline and one without read as the same component.
 *
 * The sparkline draws only with two or more samples; before then the tile falls back to its meter (RAM)
 * or to nothing (Temperature has no fraction), so an empty trend is a quiet gap rather than a flat line
 * pretending to be history. The whole tile is tappable through to the full performance screen.
 */
@Composable
private fun MetricTile(
    readout: Readout,
    icon: ImageVector?,
    trend: List<Float>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ClickableCard(modifier = modifier.defaultMinSize(minHeight = 112.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = readout.label.uppercase(),
                style = StatLabelStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = readout.value,
            style = StatValueStyle,
            color = readout.tone.colour(),
            maxLines = 1,
        )
        if (trend.size >= 2) {
            Spacer(modifier = Modifier.height(8.dp))
            Sparkline(values = trend, colour = readout.tone.colour())
        } else if (readout.fraction != null) {
            Spacer(modifier = Modifier.height(8.dp))
            com.gamecore.ui.components.Meter(fraction = readout.fraction!!, tone = readout.tone)
        }
        readout.detail?.let { detail ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Which trend, if any, belongs to a metric tile. Matched by label so the tile order can change freely. */
private fun trendFor(label: String, readouts: HomeReadouts): List<Float> = when (label) {
    HomeLabels.MEMORY -> readouts.memoryTrend
    HomeLabels.TEMPERATURE -> readouts.temperatureTrend
    else -> emptyList()
}

/** A glyph per tile. Decoration, so an unrecognised label simply gets none. */
private fun metricIcon(label: String): ImageVector? = when (label) {
    HomeLabels.CPU -> Icons.Filled.Memory
    HomeLabels.MEMORY -> Icons.Filled.DataUsage
    HomeLabels.BATTERY -> Icons.Filled.BatteryFull
    HomeLabels.TEMPERATURE -> Icons.Filled.Thermostat
    HomeLabels.REFRESH -> Icons.Filled.Refresh
    HomeLabels.DISPLAY -> Icons.Filled.PhoneAndroid
    HomeLabels.STORAGE -> Icons.Filled.Storage
    else -> null
}

private const val TILES_PER_ROW = 2

/** Dimmed alpha for the identity half of a switched-off or uninstalled profile. Matches the Games screen. */
private const val DIMMED_ALPHA = 0.6f

// ------------------------------------------------------------------------------------- device (§4.4)

/**
 * The collapsible Device card (§4.4): the facts that do not change while the user is looking at them.
 *
 * Collapsed by default, because resolution and free storage are reference figures rather than things to
 * watch — a returning user wants the live tiles above, not the screen density. Nothing is removed by the
 * collapse; the Codec & GPU scanner is the only route to [Destination.Capability] in the app, so it lives
 * here where the rest of the device facts are rather than being dropped.
 */
@Composable
private fun DeviceCard(
    device: List<Readout>,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    SectionCard(
        title = "Device",
        icon = Icons.Filled.PhoneAndroid,
        modifier = modifier,
        action = {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide" else "Show")
            }
        },
    ) {
        if (expanded) {
            device.forEach { readout -> ReadoutRow(readout = readout) }
            NavRow(
                title = "Codec & GPU scanner",
                description = "The renderer, Vulkan level and video codecs the device actually exposes",
                icon = Icons.Filled.Memory,
                onClick = { onNavigate(Destination.Capability) },
            )
        }
    }
}

// ------------------------------------------------------------------------------------- status (§4.5)

/**
 * The collapsible Status section (§4.5): four tappable tiles, each the way to the screen that changes it.
 *
 * Every value is the subsystem's own words — Shizuku's six-way label, the overlay's list of what is up, a
 * fraction of usable device controls, the profile count — decided by [com.gamecore.core.model.homeStatuses]
 * so the tile's title and its value are set together and cannot drift. A tile that wants the user is drawn
 * in the warning tone *and* says so in its value, because §10 does not let colour carry the meaning alone.
 */
@Composable
private fun StatusSection(
    statuses: List<HomeStatus>,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val needsAttention = statuses.any { it.needsAttention }
    SectionCard(
        title = "Status",
        icon = Icons.Filled.Info,
        subtitle = if (needsAttention) "Something needs your attention." else null,
        modifier = modifier,
        action = {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide" else "Show")
            }
        },
    ) {
        if (expanded) {
            statuses.chunked(2).forEach { row ->
                TileRow {
                    row.forEach { status ->
                        StatusTile(
                            status = status,
                            onClick = { onNavigate(statusDestination(status.kind)) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(2 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** One §4.5 tile: the subsystem's name, its real state word, and an attention tint that never stands alone. */
@Composable
private fun StatusTile(
    status: HomeStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    com.gamecore.ui.components.StatTile(
        label = status.kind.label,
        value = status.value,
        modifier = modifier,
        icon = statusIcon(status.kind),
        tone = if (status.needsAttention) Tone.Warning else Tone.Muted,
        onClick = onClick,
    )
}

/** Where each status tile leads. Every one is an existing route; nothing here is new. */
private fun statusDestination(kind: HomeStatusKind): Destination = when (kind) {
    HomeStatusKind.SHIZUKU -> Destination.Shizuku
    HomeStatusKind.OVERLAYS -> Destination.Overlay
    HomeStatusKind.OPTIMIZATIONS -> Destination.Permissions
    HomeStatusKind.PROFILES -> Destination.Games
}

private fun statusIcon(kind: HomeStatusKind): ImageVector = when (kind) {
    HomeStatusKind.SHIZUKU -> Icons.Filled.Terminal
    HomeStatusKind.OVERLAYS -> Icons.Filled.Layers
    HomeStatusKind.OPTIMIZATIONS -> Icons.Filled.Tune
    HomeStatusKind.PROFILES -> Icons.Filled.SportsEsports
}

// ------------------------------------------------------------------------------------ overlays (§4.6)

/**
 * The two overlay switches worth reaching without opening a settings screen, and the way to take
 * everything down.
 *
 * Disabled rather than hidden without the overlay permission, with the reason under them: a switch that
 * is not there tells the user the feature does not exist on their device, which is a different and untrue
 * statement. Pressing one anyway is handled by the ViewModel, which explains what is missing.
 *
 * "Hide everything" is separate from the switches on purpose: it stops the service too, and a user who
 * wants their screen back does not want to work out which of four windows is the one they can see.
 */
@Composable
private fun OverlayCard(
    state: HomeUiState,
    onSetPill: (Boolean) -> Unit,
    onSetButton: (Boolean) -> Unit,
    onHideAll: () -> Unit,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Overlays",
        icon = Icons.Filled.Visibility,
        modifier = modifier,
        action = {
            TextButton(onClick = { onNavigate(Destination.Overlay) }) { Text("Configure") }
        },
    ) {
        SwitchRow(
            title = "Stats pill",
            description = "A small floating readout over whatever is on screen",
            checked = state.overlay.pillVisible,
            enabled = state.hasOverlayPermission,
            onCheckedChange = onSetPill,
        )
        SwitchRow(
            title = "Floating button",
            description = "Opens the control panel over a game without leaving it",
            checked = state.overlay.buttonVisible,
            enabled = state.hasOverlayPermission,
            onCheckedChange = onSetButton,
        )
        if (!state.hasOverlayPermission) {
            Spacer(modifier = Modifier.height(Spacing.xs))
            NoteBanner(
                text = "Drawing over other apps has not been granted, so these cannot be shown.",
                tone = Tone.Muted,
                icon = Icons.Filled.Info,
                action = {
                    TextButton(onClick = { onNavigate(Destination.Permissions) }) { Text("Grant") }
                },
            )
        } else if (state.overlay.anythingVisible) {
            ActionRow {
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onHideAll) { Text("Hide everything") }
            }
        }
    }
}

// --------------------------------------------------------------------------------------- tools (§4.7)

/**
 * The tools grid (§4.7): the sections and instruments that have no other tile of their own.
 *
 * A grid of large tap targets rather than a list of rows, because these are destinations a user goes to
 * deliberately and equally, not a ranked menu. Aim Lab is here as one tile and drops out entirely when
 * §38's switch is off — a tile that lands nowhere is worse than no tile — while its inner screens stay
 * reachable through the Aim Lab tab. Gyro analytics, the touch heatmap and the controller lab are the
 * only routes to [Destination.Motion], [Destination.Touch] and [Destination.Controller] in the app, so
 * dropping the old catch-all list did not strand them.
 */
@Composable
private fun ToolsCard(
    aimLabEnabled: Boolean,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = "Tools", icon = Icons.Filled.Build, modifier = modifier) {
        val tools = buildList {
            if (aimLabEnabled) {
                add(Tool("Aim Lab", Icons.Filled.TrackChanges, Destination.AimLabHome))
            }
            add(Tool("HUD builder", Icons.Filled.GridView, Destination.Hud))
            add(Tool("Controller lab", Icons.Filled.SportsEsports, Destination.Controller))
            add(Tool("Touch heatmap", Icons.Filled.TouchApp, Destination.Touch))
            add(Tool("Gyro & aim", Icons.Filled.Sensors, Destination.Motion))
            add(Tool("More tools", Icons.Filled.Build, Destination.Tools))
        }
        tools.chunked(2).forEach { row ->
            TileRow {
                row.forEach { tool ->
                    ToolTile(
                        icon = tool.icon,
                        label = tool.label,
                        onClick = { onNavigate(tool.destination) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(2 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

/** One tools-grid entry. A local type, since nothing outside this grid describes a tool. */
private data class Tool(val label: String, val icon: ImageVector, val destination: Destination)

// ------------------------------------------------------------------------------------ attention cards

/**
 * The device settings a killed session never put back, and the two honest answers to them.
 *
 * The visible half of §16's restore table. A session that ended with the process — a task swipe, a
 * low-memory kill — leaves the device as the profile left it, and GameCore is the only thing that knows a
 * 120 Hz pin was its doing. "Leave as they are" is a real choice and drops the rows, so the card does not
 * come back tomorrow at someone who has already put their brightness where they want it.
 */
@Composable
private fun RestoreCard(
    count: Int,
    isRestoring: Boolean,
    onRestore: () -> Unit,
    onForget: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Settings still changed",
        subtitle = "A session ended without putting ${Formatters.count(count, "setting")} back.",
        icon = Icons.Filled.Restore,
        modifier = modifier,
    ) {
        ActionRow {
            TextButton(onClick = onRestore, enabled = !isRestoring) {
                if (isRestoring) {
                    CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(Spacing.sm))
                }
                Text(if (isRestoring) "Putting them back" else "Put them back")
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onForget, enabled = !isRestoring) { Text("Leave as they are") }
        }
    }
}

/**
 * "Some sessions were closed for you", with the history to check it against.
 *
 * Information rather than a task, so it is dismissable and dismissing it is enough. A session ended by a
 * process death is recorded with an estimated end time, and a user comparing their history against what
 * they remember playing deserves to know which rows those are before they conclude the app cannot count.
 */
@Composable
private fun RepairNotice(
    count: Int,
    onDismiss: () -> Unit,
    onOpenSessions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Sessions closed on restart",
        subtitle = if (count == 1) {
            "One session was still open from a previous run. It is recorded with the last reading taken."
        } else {
            "$count sessions were still open from a previous run. They are recorded with the last " +
                "readings taken."
        },
        icon = Icons.Filled.History,
        modifier = modifier,
    ) {
        ActionRow {
            TextButton(onClick = onOpenSessions) { Text("View history") }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

/**
 * A §4 starting profile GameCore worked out from a game's own history, offered rather than applied.
 *
 * Everything on it is [com.gamecore.domain.gaming.ProfileSuggester]'s output already turned to strings:
 * the subtitle names how many sessions were read (the M the spec asks for), and each rationale line is one
 * field the suggester filled with the measurement behind it, so the card states why it is offering each
 * change rather than asking the user to trust it. Nothing here is saved or applied — Review opens the
 * editor on an unsaved draft, and Dismiss remembers the package so the offer does not return.
 */
@Composable
private fun SuggestionCard(
    suggestion: HomeSuggestion,
    onReview: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Suggested profile for ${suggestion.label}",
        subtitle = "From your last ${Formatters.count(suggestion.sessionCount, "session")}. " +
            "Nothing is applied until you save it.",
        icon = Icons.Filled.Tune,
        modifier = modifier,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            suggestion.rationale.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.height(Spacing.sm))
        ActionRow {
            TextButton(onClick = onReview) { Text("Review") }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

/**
 * What the launch's memory pass did, in one sentence, once.
 *
 * The shortest-lived thing on the screen: it appears for one launch, says what happened, and goes when
 * the user acknowledges it or the next game starts. The sentence is
 * [com.gamecore.core.model.MemoryReclaimReport.Completed.summary] verbatim, including the cases where it
 * declines to give a figure — §24: a pass that closed four apps and could not measure what that freed
 * says so, rather than the banner filling in a plausible number. Muted when nothing was closed, because a
 * skip and an empty pass are reassurance, not news.
 */
@Composable
private fun ReclaimNotice(
    report: MemoryReclaimReport,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val text: String
    val tone: Tone
    when (report) {
        is MemoryReclaimReport.Skipped -> {
            text = report.reason
            tone = Tone.Muted
        }

        is MemoryReclaimReport.Completed -> {
            text = report.summary()
            tone = if (report.touchedNothing) Tone.Muted else Tone.Accent
        }
    }
    NoteBanner(
        text = text,
        tone = tone,
        icon = Icons.Filled.Memory,
        modifier = modifier,
        action = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

/**
 * The game being tracked right now: how long, what the profile managed, and the way to stop.
 *
 * The elapsed figure ticks here rather than in the ViewModel. A session's duration is a clock reading
 * subtracted from another — there is nothing to hold in state, and a per-second emission through the
 * state flow would re-run the whole dashboard's `combine` once a second for a string only this card
 * reads. [com.gamecore.core.model.ProfileApplication.summary] is shown verbatim, including its failures.
 */
@Composable
private fun SessionCard(
    gaming: GamingState,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val application = gaming.application
    SectionCard(
        title = gaming.gameLabel.ifBlank { "Game running" },
        subtitle = "Tracking since ${Formatters.clockTime(gaming.startedAtMillis)}",
        icon = Icons.Filled.SportsEsports,
        modifier = modifier,
    ) {
        StatStrip(
            entries = listOf(
                StatEntry("Elapsed", rememberElapsed(gaming.startedAtMillis), Tone.Accent),
                StatEntry(
                    label = "Profile",
                    value = when {
                        application == null -> "Not applied"
                        application.changedNothing -> "No changes"
                        else -> "${application.appliedCount} applied"
                    },
                    tone = if (application?.problems?.isNotEmpty() == true) Tone.Warning else Tone.Neutral,
                ),
            ),
        )
        if (application != null && !application.changedNothing) {
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                text = application.summary(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(Spacing.xs))
        ActionRow {
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onStop) { Text("Stop tracking") }
        }
    }
}

/**
 * A session's running time, recomputed once a second for as long as this card is composed.
 *
 * `produceState` rather than a ViewModel field: the value is `now - startedAt`, so there is nothing to
 * hold — and a tick published through [HomeViewModel.state] would re-run the dashboard's whole `combine`
 * every second to change one string. The coroutine is tied to the composition, so navigating away stops
 * the clock. Keyed on the start time, so a straight switch from one game to another restarts the count.
 */
@Composable
private fun rememberElapsed(startedAtMillis: Long): String {
    val elapsed by produceState(initialValue = elapsedSince(startedAtMillis), startedAtMillis) {
        while (true) {
            value = elapsedSince(startedAtMillis)
            delay(TICK_MILLIS)
        }
    }
    return elapsed
}

/**
 * How long since a start time, or [ABSENT] when there is no start time to count from.
 *
 * The clamp matters: [System.currentTimeMillis] can move backwards across an NTP correction, and a
 * negative duration would otherwise be rendered by a formatter that has no case for one. §24 forbids
 * inventing figures in both directions — a session that appears to have started in the future gets no
 * duration rather than a fictional one.
 */
private fun elapsedSince(startedAtMillis: Long): String = if (startedAtMillis <= 0L) {
    ABSENT
} else {
    Formatters.duration((System.currentTimeMillis() - startedAtMillis).coerceAtLeast(0L))
}

/** One second, which is the resolution [Formatters.duration] prints. */
private const val TICK_MILLIS = 1_000L
