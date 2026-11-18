package com.gamecore.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsBackupRestore
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
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
import com.gamecore.core.model.ThemePreset
import com.gamecore.core.model.WatchState
import com.gamecore.core.model.chipsSentence
import com.gamecore.core.model.profileClaimNote
import com.gamecore.core.model.shownChips
import com.gamecore.core.session.SettingsChangeRow
import com.gamecore.domain.gaming.GamingState
import com.gamecore.ui.Destination
import com.gamecore.ui.components.ABSENT
import com.gamecore.ui.components.ActionRow
import com.gamecore.ui.components.ClickableCard
import com.gamecore.ui.components.Meter
import com.gamecore.ui.components.NavRow
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.PlainCard
import com.gamecore.ui.components.PreLaunchWarningDialog
import com.gamecore.ui.components.ProfileChipRow
import com.gamecore.ui.components.Readout
import com.gamecore.ui.components.ReadoutRow
import com.gamecore.ui.components.RowDivider
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.SectionLabel
import com.gamecore.ui.components.Sparkline
import com.gamecore.ui.components.StatEntry
import com.gamecore.ui.components.StatStrip
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.TileRow
import com.gamecore.ui.components.ToolTile
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.colour
import com.gamecore.ui.games.GameIcon
import com.gamecore.ui.setup.SetupCard
import com.gamecore.ui.setup.SetupCardViewModel
import com.gamecore.ui.theme.Density
import com.gamecore.ui.theme.LocalAnimationsEnabled
import com.gamecore.ui.theme.MotionTokens
import com.gamecore.ui.theme.RedlineBanner
import com.gamecore.ui.theme.Spacing
import com.gamecore.ui.theme.StatLabelStyle
import com.gamecore.ui.theme.StatValueStyle
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

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

    // Dashboard widgets contributed by installed plugins. Hoisted here, like the setup card above, so
    // the list can leave the section out altogether when no plugin draws one: the section composable
    // returns early on an empty list, but a bare `item {}` would still spend a `spacedBy` gap on a
    // zero-height row. The same back-stack-scoped ViewModel is handed down so the section renders the
    // instance this collector is already watching.
    val pluginWidgetsViewModel: PluginWidgetsSectionViewModel = hiltViewModel()
    val pluginWidgets by pluginWidgetsViewModel.widgets.collectAsStateWithLifecycle()

    // The top-of-Home status strip: which plugins the registry currently considers active. Its own
    // ViewModel because Home's state combine is already at its five-source limit, and because the
    // strip is a small, hot read of the registry rather than part of the profile/hero derivation.
    val activePluginsViewModel: ActivePluginsSectionViewModel = hiltViewModel()
    val activePlugins by activePluginsViewModel.state.collectAsStateWithLifecycle()

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
            if (state.redlineSkin) {
                // The REDLINE identity + hero band replaces the plain header while the skin is on. The
                // watch state still has to be visible, so it rides under the banner as the same WatchPill
                // the header otherwise hosts — the skin restyles the chrome, it never drops a signal.
                Column {
                    RedlineBanner(
                        subtitle = state.subtitle,
                        onSystem = { onNavigate(Destination.Settings) },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenPadding),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        WatchPill(state.watch)
                    }
                }
            } else {
                ScreenHeader(
                    title = "GameCore",
                    subtitle = state.subtitle,
                    action = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ThemeSwitchButton(
                                preset = state.themePreset,
                                onClick = viewModel::cycleTheme,
                            )
                            WatchPill(state.watch)
                        }
                    },
                )
            }
        }

        // Global search, directly under the header: the fastest way to a screen or setting whose card is
        // further down this list. It is a row rather than a field so the keyboard never opens on Home —
        // the Search screen owns the field, its focus and its back arrow.
        item {
            SearchEntryRow(
                onClick = { onNavigate(Destination.Search) },
                compact = state.isCompact,
                modifier = padded,
            )
        }

        // The top-of-Home answer to "what is running right now?". Emitted only when the registry has
        // loaded and there is an active plugin (or the global switch is on), so a plugin-free Home
        // does not grow an empty card. The strip is host-drawn and its chips only open the manager.
        if (activePlugins.shouldShow) {
            item {
                ActivePluginsSection(
                    state = activePlugins,
                    onManage = { onNavigate(Destination.Plugins) },
                    modifier = padded,
                )
            }
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

        // §3.7.1 feature 7. The settings the running profile changed, each with a revert, and only while
        // a game is tracked and at least one change can actually be put back. A sibling of the session
        // card above rather than part of it: the two are a different question — "what is running" versus
        // "what did it change" — and the list below is empty for a profile that changed nothing.
        if (state.gaming.isTracking && state.sessionChanges.isNotEmpty()) {
            item {
                SessionSettingsChangeCard(
                    rows = state.sessionChanges,
                    onRevert = viewModel::revert,
                    onRevertAll = viewModel::revertAll,
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
                val motion = LocalAnimationsEnabled.current
                val duration = if (motion) MotionTokens.MEDIUM_MILLIS else 0
                val order = state.profiles.map { it.packageName }
                AnimatedContent(
                    targetState = hero,
                    // The banner itself reflows with the cards around it when a setup or session card
                    // appears above, and cross-fades when the featured profile changes.
                    modifier = Modifier.animateItem(),
                    transitionSpec = {
                        // Paging the hero is a sideways move, so the incoming card follows the direction
                        // the dots were tapped. Both cards move a little rather than the whole screen
                        // sliding, which keeps the dock and the header still while the featured profile
                        // changes under them.
                        val forward = order.indexOf(targetState.packageName) >=
                            order.indexOf(initialState.packageName)
                        val shift = { full: Int -> full / 10 }
                        (
                            fadeIn(tween(duration)) +
                                slideInHorizontally(tween(duration)) { w -> if (forward) shift(w) else -shift(w) }
                            ).togetherWith(
                            fadeOut(tween(duration)) +
                                slideOutHorizontally(tween(duration)) { w -> if (forward) -shift(w) else shift(w) },
                        )
                    },
                    label = "hero",
                ) { shown ->
                    HeroCard(
                        hero = shown,
                        isBusy = state.busyPackage == shown.packageName,
                        anyBusy = state.busyPackage != null,
                        onPlay = { viewModel.play(shown.packageName) },
                        onEdit = { onOpenProfile(shown.packageName) },
                        onApply = { viewModel.applyNow(shown.packageName) },
                        onOpenGames = { onNavigate(Destination.Games) },
                        // Full-bleed: the featured game is the one thing on this screen that is not a
                        // card in the page's 16dp gutter. Everything below it lines up inside the
                        // gutter, so the banner reads as the screen's masthead rather than tile zero.
                        modifier = Modifier.fillMaxWidth(),
                        // §4.2: dots only for more than one profile, one per real profile — nothing
                        // padded, nothing capped. They live inside the banner now instead of in a
                        // separate row, so paging the featured game is part of the banner's own surface.
                        pager = if (state.profileCount > 1) {
                            {
                                PagerDots(
                                    profiles = state.profiles,
                                    selectedIndex = state.heroIndex,
                                    onSelect = { pkg -> viewModel.selectProfile(pkg) },
                                )
                            }
                        } else {
                            null
                        },
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

        // §4.3. GameCore's own live tiles, omitted only when the user has asked for a plugin dashboard
        // to carry the same readings. The layout is a bento, not an even grid: the first reading — the
        // one the device is busiest with — gets a full-width tile with its figure at display size, and
        // the rest sit two to a row underneath. RAM and Temperature carry a sparkline once two real
        // samples exist; the rest show their meter or nothing. The readouts still flow for the thermal
        // note and the Device card below.
        val metrics = readouts.metrics
        if (state.showBuiltInHomeStats && metrics.isNotEmpty()) {
            item {
                SectionLabel(
                    text = "Live performance",
                    subtitle = "Straight from this device, while this screen is open.",
                    modifier = padded,
                    action = {
                        TextButton(onClick = { onNavigate(Destination.Performance) }) {
                            Text("Details")
                        }
                    },
                )
            }
            if (state.show3DHomeStats) {
                // The 3D instruments are drawn for an equal two-column bound. The bento's wide first
                // cell would stretch their artwork, so this style keeps the grid it was designed for
                // and only the heading above it changes.
                metrics.chunked(TILES_PER_ROW).forEach { row ->
                    item {
                        TileRow(modifier = padded.animateItem()) {
                            row.forEach { readout ->
                                MetricTile(
                                    readout = readout,
                                    icon = metricIcon(readout.label),
                                    trend = trendFor(readout.label, readouts),
                                    cpuCores = readouts.cpuCores,
                                    use3D = true,
                                    onClick = { onNavigate(Destination.Performance) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            repeat(TILES_PER_ROW - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                        }
                    }
                }
            } else {
                item {
                    val primary = metrics.first()
                    MetricTile(
                        readout = primary,
                        icon = metricIcon(primary.label),
                        trend = trendFor(primary.label, readouts),
                        cpuCores = readouts.cpuCores,
                        use3D = false,
                        onClick = { onNavigate(Destination.Performance) },
                        modifier = padded.animateItem().fillMaxWidth(),
                        wide = true,
                    )
                }
                metrics.drop(1).chunked(TILES_PER_ROW).forEach { row ->
                    item {
                        TileRow(modifier = padded.animateItem()) {
                            row.forEach { readout ->
                                MetricTile(
                                    readout = readout,
                                    icon = metricIcon(readout.label),
                                    trend = trendFor(readout.label, readouts),
                                    cpuCores = readouts.cpuCores,
                                    use3D = false,
                                    onClick = { onNavigate(Destination.Performance) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            repeat(TILES_PER_ROW - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }

        // Plugin dashboard widgets, below the host's own live tiles when they are shown and above the
        // device card. The `item` is emitted only when at least one widget is allowed on screen, for the same
        // zero-height-gap reason as the cards above — PluginWidgetsSection returns early on an empty
        // list, but an emitted slot would still take a `spacedBy` gap on both sides.
        if (pluginWidgets.isNotEmpty()) {
            item { PluginWidgetsSection(modifier = padded, viewModel = pluginWidgetsViewModel) }
        }

        item {
            DeviceCard(
                device = readouts.device,
                onNavigate = onNavigate,
                modifier = padded.animateItem(),
            )
        }

        if (state.statuses.isNotEmpty()) {
            item {
                StatusSection(
                    statuses = state.statuses,
                    onNavigate = onNavigate,
                    modifier = Modifier.animateItem(),
                )
            }
        }

        item {
            OverlayCard(
                state = state,
                onSetPill = viewModel::setStatsOverlay,
                onSetButton = viewModel::setFloatingButton,
                onHideAll = viewModel::hideOverlays,
                onNavigate = onNavigate,
                modifier = padded.animateItem(),
            )
        }

        item {
            ToolsCard(
                aimLabEnabled = aimLabEnabled,
                onNavigate = onNavigate,
                modifier = Modifier.animateItem(),
            )
        }
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
private fun ThemeSwitchButton(preset: ThemePreset?, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val motion = LocalAnimationsEnabled.current
    // Each preset has its own resting angle, so a tap visibly turns the two-tone ring toward the next
    // look instead of the icon silently changing colour under the thumb.
    val angle by animateFloatAsState(
        targetValue = ((preset?.ordinal ?: -1) + 1) * 45f,
        animationSpec = tween(if (motion) MotionTokens.MEDIUM_MILLIS else 0),
        label = "themeRing",
    )
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(40.dp)
            .semantics {
                contentDescription = "Switch theme. Current: ${preset?.label ?: "Custom"}"
            },
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(scheme.primary, scheme.surfaceVariant),
                    ),
                )
                .graphicsLayer { rotationZ = angle }
                .border(1.dp, scheme.outlineVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(scheme.surface),
            )
        }
    }
}

@Composable
private fun WatchPill(watch: WatchState) {
    val tone = when (watch) {
        WatchState.WATCHING -> Tone.Good
        WatchState.MANUAL -> Tone.Muted
        WatchState.BLIND -> Tone.Warning
    }
    StatusChip(text = watch.label, tone = tone, icon = Icons.Filled.Visibility)
}

/**
 * The search entry point: a rounded row with the magnifier and a prompt, not a live text field.
 *
 * Nothing is typed here. The tap opens [Destination.Search], which owns the field, the focus request and
 * the results; drawing a field on Home as well would give the app two search controls with one of them
 * unable to show what it searched. The vertical padding follows compact density, and the row itself stays
 * at the app's touch floor.
 */
@Composable
private fun SearchEntryRow(
    onClick: () -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .defaultMinSize(minHeight = 56.dp)
                .padding(
                    horizontal = Spacing.sm,
                    vertical = if (compact) Spacing.xs else Spacing.sm,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(19.dp),
                )
            }
            Spacer(modifier = Modifier.width(Spacing.sm))
            Text(
                text = "Search screens, settings and features",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
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
    pager: (@Composable () -> Unit)? = null,
) {
    val dimmed = !hero.isEnabled || !hero.isInstalled
    val identityAlpha = if (dimmed) DIMMED_ALPHA else 1f
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)

    // A banner rather than a card: it runs the full width of the page, sits directly under the header
    // with square top corners, and carries the game's own icon as an oversized watermark. The dashboard
    // below it is a grid of smaller panels, so the one thing the screen is *for* is not another tile in
    // the same stack.
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = scheme.surface,
        contentColor = scheme.onSurface,
        border = BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                scheme.primary.copy(alpha = 0.30f),
                                scheme.primary.copy(alpha = 0.08f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )
            GameIcon(
                packageName = hero.packageName,
                label = hero.label,
                size = 190.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 34.dp, y = (-18).dp)
                    .alpha(0.07f),
            )
            Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "FEATURED PROFILE",
                        style = StatLabelStyle,
                        color = scheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    HeroStateChip(hero)
                    HeroOverflowMenu(
                        label = hero.label,
                        isBusy = isBusy,
                        enabled = !anyBusy,
                        canApply = hero.isInstalled,
                        onApply = onApply,
                        onOpenGames = onOpenGames,
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onEdit),
                ) {
                    Box(
                        modifier = Modifier
                            .size(84.dp)
                            .clip(MaterialTheme.shapes.large)
                            .background(scheme.primary.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        GameIcon(
                            packageName = hero.packageName,
                            label = hero.label,
                            size = 72.dp,
                            modifier = Modifier.alpha(identityAlpha),
                        )
                    }
                    Spacer(modifier = Modifier.width(Spacing.md))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = hero.label,
                            style = MaterialTheme.typography.headlineLarge,
                            color = scheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.alpha(identityAlpha),
                        )
                        Text(
                            text = hero.packageName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.alpha(identityAlpha),
                        )
                    }
                }

                // The chips, capped, with the remainder spoken as "+N more" rather than a chip pretending
                // to be an effect. Collapsed into one screen-reader stop reading the full effect list, so
                // the cap is a sighted-only convenience and never a place data hides.
                val chips = shownChips(hero.chips)
                if (chips.shown.isNotEmpty()) {
                    val sentence = chipsSentence(hero.chips)
                    Spacer(modifier = Modifier.height(10.dp))
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
                                color = scheme.onSurfaceVariant,
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
                        color = scheme.onSurfaceVariant,
                    )
                }

                if (!hero.isInstalled) {
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    NoteBanner(
                        text = "This game is not installed at the moment. The profile is kept, and will " +
                            "start working again if you reinstall it.",
                        tone = Tone.Muted,
                        icon = Icons.Filled.Info,
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = onPlay,
                        enabled = hero.isInstalled && !anyBusy,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(modifier = Modifier.width(Spacing.sm))
                        Text("Start game")
                    }
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    TextButton(onClick = onEdit) { Text("Edit") }
                }

                pager?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    it()
                }
            }
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
        val motion = LocalAnimationsEnabled.current
        val duration = if (motion) MotionTokens.SHORT_MILLIS else 0
        profiles.forEachIndexed { index, profile ->
            val selected = index == selectedIndex
            val colour by animateColorAsState(
                targetValue = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                animationSpec = tween(duration),
                label = "dotColour",
            )
            val dotWidth by animateDpAsState(
                targetValue = if (selected) 22.dp else 8.dp,
                animationSpec = tween(duration),
                label = "dotWidth",
            )
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
                        .width(dotWidth)
                        .height(8.dp)
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
    cpuCores: List<HomeCpuCore>,
    use3D: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
) {
    if (use3D) {
        HomeMetric3DCard(
            readout = readout,
            cpuCores = cpuCores,
            onClick = onClick,
            modifier = modifier,
        )
        return
    }
    if (wide) {
        WideMetricTile(
            readout = readout,
            icon = icon,
            trend = trend,
            onClick = onClick,
            modifier = modifier,
        )
        return
    }
    ClickableCard(modifier = modifier.defaultMinSize(minHeight = 112.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = readout.label.uppercase(),
                style = StatLabelStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
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

/**
 * The bento's lead cell: one reading with room to breathe.
 *
 * The ordinary [MetricTile] is a stack of label, figure and sparkline at tile width. This variant turns
 * the same reading on its side — the figure gets the headline size and the trend or meter sits beside it
 * in its own column — so the first metric reads as the dashboard's headline rather than as a wider copy
 * of the tiles under it. Everything it prints still comes from [readout]; nothing is recomputed here.
 */
@Composable
private fun WideMetricTile(
    readout: Readout,
    icon: ImageVector?,
    trend: List<Float>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val valueColour = readout.tone.colour()
    val fraction = readout.fraction
    ClickableCard(modifier = modifier.defaultMinSize(minHeight = 136.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (icon != null) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(MaterialTheme.shapes.small)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
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
                // The headline figure is the one number on the screen that changes while the user is
                // looking at it. It rolls up or down to the new reading instead of swapping instantly,
                // which makes a live refresh legible as a change rather than as a flicker.
                val motion = LocalAnimationsEnabled.current
                val duration = if (motion) MotionTokens.SHORT_MILLIS else 0
                AnimatedContent(
                    targetState = readout.value,
                    transitionSpec = {
                        (
                            fadeIn(tween(duration)) +
                                slideInVertically(tween(duration)) { height -> height / 3 }
                            ).togetherWith(
                            fadeOut(tween(duration)) +
                                slideOutVertically(tween(duration)) { height -> -height / 3 },
                        )
                    },
                    label = "metricValue",
                ) { value ->
                    Text(
                        text = value,
                        style = MaterialTheme.typography.headlineLarge,
                        color = valueColour,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                readout.detail?.let { detail ->
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trend.size >= 2) {
                Spacer(modifier = Modifier.width(Spacing.md))
                Sparkline(
                    values = trend,
                    colour = valueColour,
                    modifier = Modifier.width(96.dp),
                    height = 44,
                )
            } else if (fraction != null) {
                Spacer(modifier = Modifier.width(Spacing.md))
                Column(
                    modifier = Modifier.width(96.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    Meter(fraction = fraction, tone = readout.tone)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${(fraction * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = valueColour,
                    )
                }
            }
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

/** The width of one tile on the Status shelf. Wide enough for a value like "Not configured". */
private val STATUS_TILE_WIDTH = 156.dp

/** The width of one tool on the Tools shelf — a square-ish launcher tile, not half a card. */
private val TOOL_TILE_WIDTH = 116.dp

/** Dimmed alpha for the identity half of a switched-off or uninstalled profile. Matches the Games screen. */
private const val DIMMED_ALPHA = 0.6f

// ------------------------------------------------------------------------------------- device (§4.4)

/**
 * The collapsible Device card (§4.4): the facts that do not change while the user is looking at them.
 *
 * Collapsed by default, because resolution and free storage are reference figures rather than things to
 * watch — a returning user wants the live tiles above, not the screen density. Nothing is removed by the
 * collapse; the Codec & GPU scanner is the only route to [Destination.Capability] in the app, so it lives
 * here where the rest of the device facts are rather than being dropped. The two diagnostics below the
 * readouts stay visible either way: they are actions, not figures, and a card that starts collapsed should
 * not make the only way to them a two-tap secret.
 */
@Composable
private fun DeviceCard(
    device: List<Readout>,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val duration = if (LocalAnimationsEnabled.current) MotionTokens.SHORT_MILLIS else 0
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
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(duration)) + expandVertically(tween(duration)),
            exit = fadeOut(tween(duration)) + shrinkVertically(tween(duration)),
        ) {
            Column {
                device.forEach { readout -> ReadoutRow(readout = readout) }
                NavRow(
                    title = "Codec & GPU scanner",
                    description = "The renderer, Vulkan level and video codecs the device actually exposes",
                    icon = Icons.Filled.Memory,
                    onClick = { onNavigate(Destination.Capability) },
                )
                RowDivider()
            }
        }
        // The two device diagnostics, always visible rather than behind the Show action: they are things
        // a user acts on, not readings to glance at, and burying them in a card that starts collapsed made
        // the only route to them two taps deep. Both read the device's own state and change nothing.
        NavRow(
            title = "Device check",
            description = "Sensors, permissions, display, thermal and GPU — what is ready, what is limited " +
                "and what each gap costs.",
            icon = Icons.Filled.Build,
            onClick = { onNavigate(Destination.Fitness) },
        )
        NavRow(
            title = "Performance Intelligence",
            description = "Offline device analysis, evidence-backed recommendations and real benchmarks.",
            icon = Icons.Filled.TrackChanges,
            onClick = { onNavigate(Destination.Intelligence) },
        )
        NavRow(
            title = "Sensor calibration",
            description = "Ten seconds still, then the bias, noise and drift the gyroscope actually shows.",
            icon = Icons.Filled.Sensors,
            onClick = { onNavigate(Destination.SensorCalibration) },
        )
    }
}

// ------------------------------------------------------------------------------------- status (§4.5)

/**
 * The Status shelf (§4.5): every subsystem as one tile in a horizontal rail, each the way to the screen
 * that changes it.
 *
 * This used to be a collapsible card that hid its own contents behind a "Show" button. That was the wrong
 * shape for the information: these four values are the answer to "is anything wrong right now?", which a
 * user should be able to read without first opening a card — and the values are short enough that a rail
 * shows all of them with a flick. The rail bleeds to the screen edge, so the next tile is visibly half
 * there and the shelf reads as scrollable rather than as a grid that happens to be cut off.
 *
 * Every value is the subsystem's own words — Shizuku's six-way label, the overlay's list of what is up, a
 * fraction of usable device controls, the profile count — decided by [com.gamecore.core.model.homeStatuses]
 * so the tile's title and its value are set together and cannot drift. A tile that wants the user is drawn
 * in the warning tone *and* says so in its value, because §10 does not let colour carry the meaning alone;
 * the label's trailing chip says the same thing in one place for the whole shelf.
 */
@Composable
private fun StatusSection(
    statuses: List<HomeStatus>,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val needsAttention = statuses.any { it.needsAttention }
    val duration = if (LocalAnimationsEnabled.current) MotionTokens.SHORT_MILLIS else 0
    Column(modifier = modifier.fillMaxWidth()) {
        SectionLabel(
            text = "Status",
            subtitle = "What GameCore's own subsystems are doing.",
            modifier = Modifier.padding(horizontal = ScreenPadding),
            action = {
                // The warning chip grows into the label when a subsystem starts needing the user and
                // shrinks away when it stops, instead of blinking in and out of the row.
                AnimatedVisibility(
                    visible = needsAttention,
                    enter = fadeIn(tween(duration)) + expandHorizontally(tween(duration)),
                    exit = fadeOut(tween(duration)) + shrinkHorizontally(tween(duration)),
                ) {
                    StatusChip(text = "Needs attention", tone = Tone.Warning)
                }
            },
        )
        Spacer(modifier = Modifier.height(10.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = ScreenPadding),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            items(statuses, key = { it.kind.name }) { status ->
                StatusTile(
                    status = status,
                    onClick = { onNavigate(statusDestination(status.kind)) },
                    // A subsystem appearing or leaving the shelf slides the others along instead of
                    // re-cutting the row in one frame.
                    modifier = Modifier.width(STATUS_TILE_WIDTH).animateItem(),
                )
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
        val duration = if (LocalAnimationsEnabled.current) MotionTokens.SHORT_MILLIS else 0
        // Two large switches rather than two rows. An overlay is a thing that is either on the screen
        // or not, and a pair of tiles answers that from across the room; the switch row's thin track
        // made the same question look like a settings detail. Each tile is one toggle target, with the
        // switch role, so the tile and its state are the same control to a screen reader.
        Row(
            modifier = Modifier.height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            OverlayToggleTile(
                icon = Icons.Filled.DataUsage,
                title = "Stats pill",
                description = "A small floating readout over whatever is on screen",
                checked = state.overlay.pillVisible,
                enabled = state.hasOverlayPermission,
                onCheckedChange = onSetPill,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            OverlayToggleTile(
                icon = Icons.Filled.TouchApp,
                title = "Floating button",
                description = "Opens the control panel over a game without leaving it",
                checked = state.overlay.buttonVisible,
                enabled = state.hasOverlayPermission,
                onCheckedChange = onSetButton,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
        // The permission note and the "hide everything" action are mutually exclusive states of the
        // same slot. Both expand and collapse with the card instead of appearing between one frame
        // and the next, so granting the permission reads as the note making way for the action.
        AnimatedVisibility(
            visible = !state.hasOverlayPermission,
            enter = fadeIn(tween(duration)) + expandVertically(tween(duration)),
            exit = fadeOut(tween(duration)) + shrinkVertically(tween(duration)),
        ) {
            Column {
                Spacer(modifier = Modifier.height(Spacing.xs))
                NoteBanner(
                    text = "Drawing over other apps has not been granted, so these cannot be shown.",
                    tone = Tone.Muted,
                    icon = Icons.Filled.Info,
                    action = {
                        TextButton(onClick = { onNavigate(Destination.Permissions) }) { Text("Grant") }
                    },
                )
            }
        }
        AnimatedVisibility(
            visible = state.hasOverlayPermission && state.overlay.anythingVisible,
            enter = fadeIn(tween(duration)) + expandVertically(tween(duration)),
            exit = fadeOut(tween(duration)) + shrinkVertically(tween(duration)),
        ) {
            ActionRow {
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onHideAll) { Text("Hide everything") }
            }
        }
    }
}

/**
 * One overlay as a large on/off tile.
 *
 * The whole surface is the control: it carries the switch role, toggles on tap, and reports its state in
 * words as well as tint. A real [androidx.compose.material3.Switch] inside would have been a second,
 * smaller target nested in a clickable card, which is the overlapping-targets problem [ProfileCard] avoids
 * by keeping its switch outside any clickable area. Here the tile *is* the switch, so there is one target
 * and no ambiguity about what a tap does.
 *
 * [enabled] is false when the overlay permission is missing. The tile stays visible, muted, and inert,
 * with the reason and the grant action in the card beneath — a control that vanishes teaches the user the
 * feature does not exist.
 */
@Composable
private fun OverlayToggleTile(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val motion = LocalAnimationsEnabled.current
    val duration = if (motion) MotionTokens.SHORT_MILLIS else 0
    val interaction = remember { MutableInteractionSource() }
    val tint = if (checked) scheme.primary else scheme.onSurfaceVariant
    // The tile's fill, outline and glyph all cross-fade together, so switching an overlay reads as one
    // object changing state rather than three unrelated properties snapping at once.
    val fill by animateColorAsState(
        targetValue = if (checked) scheme.primary.copy(alpha = 0.10f) else scheme.surfaceVariant.copy(alpha = 0.35f),
        animationSpec = tween(duration),
        label = "overlayFill",
    )
    val outline by animateColorAsState(
        targetValue = if (checked) scheme.primary.copy(alpha = 0.45f) else scheme.outlineVariant,
        animationSpec = tween(duration),
        label = "overlayOutline",
    )
    val glyph by animateColorAsState(
        targetValue = tint,
        animationSpec = tween(duration),
        label = "overlayGlyph",
    )
    // A pressed tile dips like every other card in the app, so the new control obeys the same touch
    // language as the rest of the screen instead of feeling like a different kind of surface.
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && motion) 0.975f else 1f,
        animationSpec = if (motion) {
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
        } else {
            snap()
        },
        label = "overlayPress",
    )
    Surface(
        modifier = modifier
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                interactionSource = interaction,
                indication = ripple(),
                onValueChange = onCheckedChange,
            )
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .defaultMinSize(minHeight = 132.dp),
        shape = MaterialTheme.shapes.large,
        color = fill,
        contentColor = if (enabled) scheme.onSurface else scheme.onSurfaceVariant,
        border = BorderStroke(1.dp, outline),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(glyph.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = glyph,
                        modifier = Modifier.size(17.dp),
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                StatusChip(
                    text = if (checked) "On" else "Off",
                    tone = if (checked) Tone.Accent else Tone.Muted,
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) scheme.onSurface else scheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// --------------------------------------------------------------------------------------- tools (§4.7)

/**
 * The tools shelf (§4.7): the sections and instruments that have no tab of their own, as one rail.
 *
 * A horizontal rail rather than a card of two-column rows. These are destinations a user goes to
 * deliberately and equally, not a ranked menu, and the rail gives each one a full square of its own
 * instead of half a card's width — the same treatment a launcher gives its library. The shelf bleeds to
 * the edge so the next tile is half-visible and the row reads as scrollable; "More tools" sits at the end
 * as the explicit way to the complete list, so the rail never has to pretend to be exhaustive.
 *
 * Aim Lab drops out entirely when §38's switch is off — a tile that lands nowhere is worse than no tile —
 * while its inner screens stay reachable through the Aim Lab tab. Gyro analytics, the touch heatmap and
 * the controller lab are the only routes to [Destination.Motion], [Destination.Touch] and
 * [Destination.Controller] in the app, so the rail keeps every one of them.
 */
@Composable
private fun ToolsCard(
    aimLabEnabled: Boolean,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tools = buildList {
        if (aimLabEnabled) {
            add(Tool("Aim Lab", Icons.Filled.TrackChanges, Destination.AimLabHome))
        }
        add(Tool("HUD builder", Icons.Filled.GridView, Destination.Hud))
        add(Tool("Controller lab", Icons.Filled.SportsEsports, Destination.Controller))
        add(Tool("Touch heatmap", Icons.Filled.TouchApp, Destination.Touch))
        add(Tool("Gyro & aim", Icons.Filled.Sensors, Destination.Motion))
        add(Tool("Screen Extraction", Icons.Filled.Layers, Destination.ScreenExtraction))
        add(Tool("Touch sampling", Icons.Filled.DataUsage, Destination.SamplingMonitor))
        add(Tool("More tools", Icons.Filled.Build, Destination.Tools))
    }
    Column(modifier = modifier.fillMaxWidth()) {
        SectionLabel(
            text = "Tools",
            subtitle = "The instruments that do not have a tab of their own.",
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
        Spacer(modifier = Modifier.height(10.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = ScreenPadding),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            items(tools, key = { it.label }) { tool ->
                ToolTile(
                    icon = tool.icon,
                    label = tool.label,
                    onClick = { onNavigate(tool.destination) },
                    // The Aim Lab switch can add or remove the first tile; the rest of the rail glides
                    // to its new position rather than jumping.
                    modifier = Modifier.width(TOOL_TILE_WIDTH).animateItem(),
                )
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
 * The settings this session's profile changed, each with a per-row revert (§3.7.1, feature 7).
 *
 * The manual, one-at-a-time path in front of the automatic session-end restore: a user who sees a
 * profile dim the screen too far or silence a notification they wanted can put that one setting back
 * without ending the game. Every row is one
 * [com.gamecore.domain.optimization.OptimizationManager.restore] can genuinely put back —
 * [com.gamecore.core.session.SettingsChangeRows] has already dropped the rows no revert could discharge,
 * which is the no-fake-data rule this card rests on — so there is no button here that does nothing.
 *
 * The order is the recording order the ledger hands back, which is also the order the end-of-session
 * sweep replays. Not re-sorted here: the card reads as a log of what the profile did, and the pairs stay
 * legible in the sequence they were written (brightness level then mode, min then peak refresh rate).
 *
 * [SettingsChangeRow.previousValue] draws itself through its own [text] — a rendered unit where this
 * build can state one, the raw string where it cannot, and an honest "Not stated" where it cannot say
 * anything true — so this composable prints a string and never second-guesses what a stored value means.
 */
@Composable
private fun SessionSettingsChangeCard(
    rows: List<SettingsChangeRow>,
    onRevert: (String, String) -> Unit,
    onRevertAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Changed this session",
        subtitle = "Settings the active profile changed. Put any of them back now.",
        icon = Icons.Filled.SettingsBackupRestore,
        modifier = modifier,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            rows.forEach { row ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = row.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = row.previousValue.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onRevert(row.namespace, row.key) }) { Text("Revert") }
                }
            }
        }
        Spacer(modifier = Modifier.height(Spacing.sm))
        ActionRow {
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onRevertAll) { Text("Revert all") }
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
