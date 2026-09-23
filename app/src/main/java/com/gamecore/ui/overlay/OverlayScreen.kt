package com.gamecore.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CompareArrows
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.core.model.ButtonSizePreset
import com.gamecore.core.model.FloatingButtonConfig
import com.gamecore.core.model.HudStat
import com.gamecore.core.model.INTERVAL_TENTHS
import com.gamecore.core.model.OverlayConfig
import com.gamecore.core.model.OverlayWindowState
import com.gamecore.core.model.PanelLayoutStyle
import com.gamecore.core.model.PillDisplayMode
import com.gamecore.core.model.absentStatsNote
import com.gamecore.core.model.buttonSizeSummary
import com.gamecore.core.model.isAtDefaultPosition
import com.gamecore.core.model.overlayPositionSummary
import com.gamecore.core.model.panelWidthResetNote
import com.gamecore.core.model.pillPreviewNote
import com.gamecore.core.model.pillPreviewSentence
import com.gamecore.core.model.pillStyleSummary
import com.gamecore.core.model.quickAppsSummary
import com.gamecore.core.model.sliderDescription
import com.gamecore.core.overlay.Corner
import com.gamecore.core.overlay.OverlayAction
import com.gamecore.core.overlay.OverlayPalette
import com.gamecore.core.overlay.PerformancePill
import com.gamecore.core.overlay.QuickSheetPinEditor
import com.gamecore.core.overlay.QuickToggle
import com.gamecore.core.overlay.actionsPerRow
import com.gamecore.core.overlay.pinnedCorner
import com.gamecore.core.overlay.withCorner
import com.gamecore.domain.monitoring.StatReading
import com.gamecore.ui.Destination
import com.gamecore.ui.components.ActionRow
import com.gamecore.ui.components.ChoiceRow
import com.gamecore.ui.components.KeyValueRow
import com.gamecore.ui.components.NavRow
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.OnResume
import com.gamecore.ui.components.RowDivider
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.SliderRow
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.SwitchRow
import com.gamecore.ui.components.TextFieldRow
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.colour
import com.gamecore.ui.hud.conditionNote
import com.gamecore.ui.theme.Density
import com.gamecore.ui.theme.Spacing

/**
 * §7's overlay settings: the stats pill and the floating button, configured.
 *
 * The two windows that are up for a whole session get one screen because they are chosen together: how
 * big the button is and how opaque the pill is are both answers to the same question, which is how much
 * of the game the user is willing to cover.
 *
 * **What the redesign changed here is the shape, not the behaviour.** Every setting the audit lists
 * against `OverlayConfig` and `FloatingButtonConfig` is still on this screen and still writes through the
 * same ViewModel call it always did; what moved is the order and the grouping. §7 asks for "stats pill
 * toggle with description, Position and Style options; floating button toggle with Position and Size
 * options; a live preview of the pill", so the pill now comes first — it is what most people open this
 * screen to change — and each card is broken into named groups instead of one long run of sliders.
 *
 * Two of those grouping decisions are not literal readings of that sentence and are worth writing down:
 *
 *  - The pill's **Position** group holds the orientation switch as well as the dragged coordinates,
 *    because "a column down the side" against "a strip along the top" is a decision about how the pill
 *    lies against a screen edge, not about how it is painted. **Style** is then exactly the four settings
 *    that change how it is painted: labels, text size, opacity and corner rounding.
 *  - The button's **Size** group holds both opacity sliders beside the size slider. Size and opacity are
 *    the same question asked twice — how much of the game does this cover — and splitting them would put
 *    the two halves of one decision under two headings.
 *
 * The update interval sits on the *stats* card rather than with the style settings, because what it costs
 * is proportional to what is on the pill: every tick is one read per stat, so the slider belongs beside
 * the list of stats being read rather than beside the corner radius.
 *
 * The pill preview is drawn with the same composable the overlay service uses, fed by the same reader, so
 * a stat this device does not publish reads "n/a" here exactly as it will over a game. That is the
 * difference between a preview and a mock-up, and it is the only honest way to let someone choose eight
 * stats before they have seen any of them: a drawn mock-up would show a tidy row of plausible numbers,
 * including a frame rate this phone may have no way to measure.
 *
 * Edits land on the live windows. The service collects both configs, so a slider released while a game is
 * running changes the pill on top of it within a frame — which is why the sliders write once on release
 * rather than on every pixel of travel.
 */
@Composable
fun OverlayScreen(
    onBack: () -> Unit,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OverlayViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val readings by viewModel.readings.collectAsStateWithLifecycle(emptyList())
    val padded = Modifier.padding(horizontal = ScreenPadding)

    // §3's compact density tightens the gaps *between* cards and leaves the padding inside them alone:
    // the cards are what a user scrolls past, and the rows inside are what a thumb has to hit.
    val cardGap = if (state.isCompact) Spacing.md * Density.COMPACT_FACTOR else Spacing.md

    // Hoisted out of the card for the reason `ColorScreen` hoists its own: the cards are `LazyColumn`
    // items, and an item scrolled off the screen is disposed. State kept inside the card would take the
    // dialog down with it the moment the list moved under the user's finger.
    var editingWidth by remember { mutableStateOf(false) }

    // The overlay permission is granted on another app's screen, and Android gives no callback for it.
    OnResume { viewModel.refreshPermission() }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = Spacing.xs, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(cardGap),
    ) {
        item {
            ScreenHeader(
                title = "Overlay",
                // Counted from the service's report rather than described in the abstract, so the line
                // under the title is a reading of what is on screen instead of a restatement of the title.
                subtitle = state.subtitle,
                onBack = onBack,
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

        item {
            StatusCard(
                state = state,
                onHideEverything = viewModel::hideEverything,
                onNavigate = onNavigate,
                modifier = padded,
            )
        }

        item {
            PillCard(
                state = state,
                readings = readings,
                onShow = viewModel::setPillVisible,
                onEdit = viewModel::editPill,
                onCommit = viewModel::commitPill,
                onUpdate = viewModel::updatePill,
                onResetPosition = viewModel::resetPillPosition,
                modifier = padded,
            )
        }

        item {
            StatsCard(
                state = state,
                readings = readings,
                onAdd = viewModel::addStat,
                onMove = viewModel::moveStat,
                onRemove = viewModel::removeStat,
                onInterval = viewModel::setInterval,
                onCommit = viewModel::commitPill,
                modifier = padded,
            )
        }

        item {
            ButtonCard(
                state = state,
                onShow = viewModel::setButtonVisible,
                onEdit = viewModel::editButton,
                onCommit = viewModel::commitButton,
                onUpdate = viewModel::updateButton,
                onResetPosition = viewModel::resetButtonPosition,
                modifier = padded,
            )
        }

        item {
            QuickSheetCard(
                state = state,
                onAddPin = viewModel::addPin,
                onMovePin = viewModel::movePin,
                onRemovePin = viewModel::removePin,
                onAutoClose = viewModel::setQuickAutoClose,
                onDoubleTap = viewModel::setDoubleTapForPanel,
                modifier = padded,
            )
        }

        item {
            PanelCard(
                state = state,
                onEdit = viewModel::editButton,
                onCommit = viewModel::commitButton,
                onUpdate = viewModel::updateButton,
                onEditWidth = { editingWidth = true },
                onResetWidth = viewModel::resetPanelWidth,
                onMediaAccess = { onNavigate(Destination.MediaAccess) },
                onQuickApps = { onNavigate(Destination.QuickApps) },
                modifier = padded,
            )
        }
    }

    if (editingWidth) {
        PanelWidthDialog(
            value = state.button.panelWidthDp,
            // Through `updateButton` rather than the draft pair: a typed figure is confirmed once and has
            // no in-between states to preview, and the write clears any draft the slider left behind.
            onSet = { width ->
                editingWidth = false
                viewModel.updateButton { it.copy(panelWidthDp = width) }
            },
            onDismiss = { editingWidth = false },
        )
    }
}

// ---------------------------------------------------------------------------- furniture shared by the
// ---------------------------------------------------------------------------- cards below

/**
 * A group heading inside a card — "POSITION", "STYLE", "SIZE".
 *
 * A real heading rather than a styled `Text`, because §10 asks for sensible screen-reader semantics and a
 * card broken into four groups is a card somebody should be able to jump through by heading instead of
 * hearing forty rows in a row. The word is the only thing carrying the grouping for a screen reader — the
 * divider drawn above it is not announced — so this is structure, not decoration.
 */
@Composable
private fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.semantics { heading() },
    )
}

/**
 * The tone an [OverlayWindowState] draws in, keeping the meaning-to-colour decision in the UI layer.
 *
 * [OverlayWindowState] deliberately names no colours; this is where its four states become tones, which
 * is the same split `ProfileChipKind.tone()` makes. The chip always prints the state's own word beside
 * the tint, so §10's "never status by colour alone" holds whichever tone this returns.
 *
 * [OverlayWindowState.LOCKED] is muted rather than a warning: a game's profile taking charge is the
 * feature working, not a fault, and the banner on the status card above explains it once for the screen.
 */
private fun OverlayWindowState.tone(): Tone = when (this) {
    OverlayWindowState.UP -> Tone.Good
    OverlayWindowState.DOWN -> Tone.Muted
    OverlayWindowState.BLOCKED -> Tone.Warning
    OverlayWindowState.LOCKED -> Tone.Muted
}

/**
 * §7's Position group: where the window was dragged to, and the way back.
 *
 * Shared by the pill and the button because both are positioned the same way — by dragging them over a
 * game — and two hand-written variations would eventually word the same fact differently.
 *
 * The readout names no corner. The stored figures are raw pixels from the top-left with no display size
 * beside them, so "1020, 300" is bottom-right on one phone and off the edge of another; the one true
 * thing to say is the pair itself, which is what [overlayPositionSummary] says.
 *
 * The reset is disabled once the window already is where it starts out, because a button that does
 * nothing is worse than a missing one: the user presses it, nothing moves, and they are left unsure
 * whether the feature is broken or they misunderstood it. Disabled, with the readout beside it saying
 * "where it starts out", is the same information without the doubt.
 */
/**
 * The button's position in words: the named corner if it is pinned to one, otherwise the pixel summary the
 * pill and panel have always shown. Kept here rather than in the pure summary function because a corner is
 * an overlay-layer idea and the model's position is plain fractions.
 */
private fun buttonPositionSummary(pinned: Corner?, x: Int, y: Int, defaultX: Int, defaultY: Int): String =
    if (pinned != null) "Pinned to ${pinned.label.replaceFirstChar { it.lowercase() }}" else
        overlayPositionSummary(x, y, defaultX, defaultY)

/**
 * True when a reset would do nothing: the pixels are the default *and* no fraction is stored. Both halves
 * matter — a corner pin leaves the pixels alone, so the pixel check on its own would grey out the reset the
 * user needs to undo the pin.
 */
private fun isButtonPositionPristine(button: FloatingButtonConfig, defaultX: Int, defaultY: Int): Boolean =
    isAtDefaultPosition(button.x, button.y, defaultX, defaultY) && !button.hasStoredPositionFraction()

@Composable
private fun PositionGroup(
    summary: String,
    isDefault: Boolean,
    hint: String,
    onReset: () -> Unit,
) {
    // The label is "Position" and not "Dragged to", because the summary is a whole sentence that already
    // says whether the window has been moved — "Dragged to: Dragged to 640, 128" is what the other
    // wording produces on every screen where it has.
    KeyValueRow(label = "Position", value = summary, tone = Tone.Neutral)
    ActionRow {
        Text(
            text = hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onReset, enabled = !isDefault) { Text("Reset position") }
    }
}

/**
 * What is on screen right now, from the service rather than from the request.
 *
 * The summary counts all four overlay windows, not just this screen's two, because "hide everything" is
 * here and a user reaching for it wants to know what everything currently is. The banner order matters:
 * no permission is why nothing appears at all, and a profile in charge is why a switch flicked here would
 * be overridden, so whichever of the two applies is the one sentence shown.
 */
@Composable
private fun StatusCard(
    state: OverlayUiState,
    onHideEverything: () -> Unit,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "On screen",
        icon = Icons.Filled.Layers,
        modifier = modifier,
        action = {
            StatusChip(
                text = if (state.isAnythingShown) "Showing" else "Hidden",
                tone = if (state.isAnythingShown) Tone.Good else Tone.Muted,
            )
        },
    ) {
        KeyValueRow(
            label = "Overlay windows",
            value = state.statusSummary,
            tone = if (state.isAnythingShown) Tone.Good else Tone.Muted,
        )
        if (!state.hasPermission) {
            Spacer(modifier = Modifier.height(Spacing.sm))
            NoteBanner(
                text = "GameCore cannot draw over other apps yet, so neither window below would appear. " +
                    "Grant that and the switches will stay where you put them.",
                tone = Tone.Warning,
                icon = Icons.Filled.Info,
                action = {
                    TextButton(onClick = { onNavigate(Destination.Permissions) }) { Text("Grant") }
                },
            )
        } else if (state.isDrivenByProfile) {
            Spacer(modifier = Modifier.height(Spacing.sm))
            NoteBanner(
                text = "${state.drivingGameLabel.ifBlank { "A game" }}'s profile is in charge of the " +
                    "overlay right now, so the switches below are locked. Your own choice comes back " +
                    "when the game stops.",
                tone = Tone.Muted,
                icon = Icons.Filled.Info,
            )
        }
        if (state.isAnythingShown) {
            RowDivider()
            ActionRow {
                Text(
                    text = "The overlay service stops on its own once there is nothing left to draw.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onHideEverything) {
                    Text(text = "Hide everything", color = Tone.Danger.colour())
                }
            }
        } else if (state.hasPermission) {
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                text = "Nothing is being drawn, so the overlay service is not running and costs nothing.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
// ---------------------------------------------------------------------------------- §7's stats pill

/**
 * §7's stats pill: the toggle and its description, a live preview, then Position and Style.
 *
 * The preview sits directly under the toggle rather than at the foot of the card, because it is the thing
 * every control below it changes — a user dragging the opacity slider is looking at the plate, and a
 * preview they have to scroll back up to is a preview they stop using.
 *
 * The switch is disabled when the pill has no stats as well as when the permission or a profile blocks
 * it: an empty pill is a small dark rectangle sitting over a game, and the ViewModel refuses that anyway.
 * All three reasons are spelled out in the description under the switch, because a control greyed out
 * with no word beside it is exactly §10's status-by-appearance-alone.
 */
@Composable
private fun PillCard(
    state: OverlayUiState,
    readings: List<StatReading>,
    onShow: (Boolean) -> Unit,
    onEdit: ((OverlayConfig) -> OverlayConfig) -> Unit,
    onCommit: () -> Unit,
    onUpdate: ((OverlayConfig) -> OverlayConfig) -> Unit,
    onResetPosition: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pill = state.pill
    // The model's own starting values, which is what "reset" means here — read from the data class rather
    // than retyped, so a default changed in `OverlayModels.kt` changes this readout with it.
    val defaults = remember { OverlayConfig() }
    SectionCard(
        title = "Stats pill",
        subtitle = "Live stats on a small plate, over whatever is running",
        icon = Icons.Filled.Insights,
        modifier = modifier,
        action = { StatusChip(text = state.pillState.label, tone = state.pillState.tone()) },
    ) {
        SwitchRow(
            title = "Show the stats pill",
            checked = state.isPillVisible,
            onCheckedChange = onShow,
            enabled = state.canToggle && state.hasStats,
            description = when {
                !state.hasStats -> "Add at least one stat below first — an empty pill would be a blank " +
                    "plate over your game."

                state.pillState == OverlayWindowState.BLOCKED ->
                    "Needs permission to draw over other apps. Grant that above and this switch works."

                state.pillState == OverlayWindowState.LOCKED ->
                    "A game's profile is in charge of the overlay until that game stops."

                else -> "Drag it anywhere over a game; it snaps to the nearest edge."
            },
        )

        RowDivider()
        PillPreview(pill = pill, readings = readings)

        RowDivider()
        GroupLabel("POSITION")
        SwitchRow(
            title = "Stack the stats vertically",
            checked = pill.isVertical,
            onCheckedChange = { vertical -> onUpdate { it.copy(isVertical = vertical) } },
            description = "A column down the side instead of a strip along the top.",
        )
        PositionGroup(
            summary = overlayPositionSummary(pill.pillX, pill.pillY, defaults.pillX, defaults.pillY),
            isDefault = isAtDefaultPosition(pill.pillX, pill.pillY, defaults.pillX, defaults.pillY),
            hint = "Dragged off the edge, or onto something you need to see? Put it back.",
            onReset = onResetPosition,
        )

        RowDivider()
        GroupLabel("STYLE")
        Spacer(modifier = Modifier.height(Spacing.xs))
        Text(
            // The four settings below as one sentence, so the card can be understood without reading
            // four separate figures off four separate sliders.
            text = pillStyleSummary(pill),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Spec §3's two shapes: Compact is the single glanceable line, Detailed the labelled card. One
        // tap, one write — a choice, not the slider's draft/commit. Default stays DETAILED so an install
        // over an old version that never opens this reads the exact pill it always had.
        ChoiceRow(
            options = PillDisplayMode.entries,
            selected = pill.displayMode,
            onSelect = { mode -> onUpdate { it.copy(displayMode = mode) } },
            label = { it.label },
        )
        Spacer(modifier = Modifier.height(Spacing.xs))
        SwitchRow(
            title = "Show labels",
            checked = pill.showLabels,
            onCheckedChange = { labels -> onUpdate { it.copy(showLabels = labels) } },
            description = "\"CPU 42%\" rather than \"42%\". Off is narrower and covers less.",
        )
        SliderRow(
            title = "Text size",
            value = pill.textSizeSp,
            range = OverlayConfig.TEXT_SIZE_RANGE,
            onValueChange = { size -> onEdit { it.copy(textSizeSp = size) } },
            valueLabel = "${pill.textSizeSp} sp",
            // Every slider on this screen states its own range in words, because §10's screen-reader user
            // gets a percentage from the platform and no sense of what the ends of the track mean. The
            // range passed in is the model's own constant, never a number retyped here.
            description = sliderDescription(
                "How big the figures are drawn over the game.",
                OverlayConfig.TEXT_SIZE_RANGE,
                "sp",
            ),
            onValueChangeFinished = onCommit,
        )
        SliderRow(
            title = "Opacity",
            value = pill.opacityPercent,
            range = OverlayConfig.OPACITY_RANGE,
            onValueChange = { percent -> onEdit { it.copy(opacityPercent = percent) } },
            valueLabel = "${pill.opacityPercent}%",
            description = sliderDescription(
                "Fades the plate with the text, so the numbers stay as readable as they were.",
                OverlayConfig.OPACITY_RANGE,
                "%",
            ),
            onValueChangeFinished = onCommit,
        )
        SliderRow(
            title = "Corner rounding",
            value = pill.cornerRadiusDp,
            range = OverlayConfig.CORNER_RANGE,
            onValueChange = { radius -> onEdit { it.copy(cornerRadiusDp = radius) } },
            valueLabel = "${pill.cornerRadiusDp} dp",
            description = sliderDescription(
                "Zero is a square plate; the top of the range is a lozenge.",
                OverlayConfig.CORNER_RANGE,
                "dp",
            ),
            onValueChangeFinished = onCommit,
        )
    }
}

/**
 * §7's "live preview of the pill": the real composable, on a plate standing in for the game behind it.
 *
 * The one thing on this screen that has to be the real thing rather than a drawing. What is below is
 * [PerformancePill] fed with the same [StatReading]s the overlay service draws from, at the config the
 * sliders above are editing, so a stat that will read "n/a" over a game reads "n/a" here and a 20%
 * opacity that looked fine as a number is visibly a ghost before it is chosen.
 *
 * The caption says out loud that these are live readings and counts the ones reading nothing, because a
 * preview of eight stats on a device that can report five otherwise looks like a working pill with three
 * quiet ones. The banner below it names *which*, and deliberately names only the stats that read nothing
 * at all — not the ones still waiting for the sampler's first tick, which render identically and mean the
 * opposite. Announcing "this device reports no CPU temperature" for the first second of every visit would
 * teach the user to ignore the one message on this card worth reading.
 *
 * The plate itself is collapsed into a single screen-reader stop that names it as a preview before
 * reading the stats out: eight figures announced one after another with no context are indistinguishable
 * from a live dashboard, which is the one way a preview can mislead somebody who cannot see that it is a
 * preview.
 */
@Composable
private fun PillPreview(pill: OverlayConfig, readings: List<StatReading>) {
    val absent = readings.filter { !it.isAvailable && !it.isAwaitingFirstSample }
    // Null while the pill is empty, in which case the plate below holds one plain sentence that reads
    // perfectly well on its own and needs no collapsing.
    val described = pillPreviewSentence(readings.map { "${it.stat.label} ${it.display()}" })
        ?.let { sentence -> Modifier.clearAndSetSemantics { contentDescription = sentence } }
        ?: Modifier
    Column {
        GroupLabel("PREVIEW")
        Spacer(modifier = Modifier.height(Spacing.sm))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Spacing.md))
                .background(OverlayPalette.PanelPlate)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Spacing.md))
                .padding(Spacing.md)
                .then(described),
            contentAlignment = if (pill.isVertical) Alignment.CenterStart else Alignment.Center,
        ) {
            if (readings.isEmpty()) {
                Text(
                    text = "Nothing on it yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OverlayPalette.Muted,
                )
            } else {
                PerformancePill(readings = readings, config = pill)
            }
        }
        Spacer(modifier = Modifier.height(Spacing.sm))
        Text(
            text = pillPreviewNote(readings.size, absent.size),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        absentStatsNote(absent.map { it.stat.label.lowercase() }, StatReading.PLACEHOLDER)?.let { note ->
            Spacer(modifier = Modifier.height(Spacing.sm))
            NoteBanner(text = note, tone = Tone.Warning, icon = Icons.Filled.Info)
        }
    }
}
/**
 * Which stats are on the pill, in the order they are drawn, what each one reads, and how often.
 *
 * The order is editable because the pill is read at a glance in the corner of a game: the stat that
 * matters to this user goes at the front, and "front" is a real position rather than a preference, so the
 * arrows move the item in the stored list. The ends are walls rather than a wrap — a stat that jumped
 * from first to last on one more tap would be an accident every time.
 *
 * Each row carries its live reading, which is how a user finds out that the frame rate they just added
 * reads nothing on this phone before they take it into a game.
 *
 * The refresh interval is the last group on this card rather than on the pill's own, and that placement
 * is the point being made: a tick costs one read per stat, so what the slider actually buys depends on
 * the list directly above it. Filed under the corner-radius slider it would look like another style
 * setting, and a user trying to spend less battery would have no reason to look at it.
 */
@Composable
private fun StatsCard(
    state: OverlayUiState,
    readings: List<StatReading>,
    onAdd: (HudStat) -> Unit,
    onMove: (HudStat, Int) -> Unit,
    onRemove: (HudStat) -> Unit,
    onInterval: (Int) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stats = state.pill.stats
    SectionCard(
        title = "Stats on the pill",
        subtitle = state.statsSubtitle,
        icon = Icons.Filled.Add,
        modifier = modifier,
    ) {
        if (stats.isEmpty()) {
            NoteBanner(
                text = "The pill has nothing on it, so it cannot be shown. Add a stat below.",
                tone = Tone.Muted,
                icon = Icons.Filled.Info,
            )
        }
        stats.forEachIndexed { index, stat ->
            if (index > 0) RowDivider()
            StatRow(
                stat = stat,
                reading = readings.firstOrNull { it.stat == stat },
                canMoveUp = index > 0,
                canMoveDown = index < stats.lastIndex,
                onMove = onMove,
                onRemove = onRemove,
            )
        }
        // What the conditional stats on the pill depend on, for the ones the user actually chose. Bounded
        // by that choice rather than listing all six, and shown whether or not the pill is full.
        stats.mapNotNull { it.conditionNote() }.distinct().forEach { note ->
            Spacer(modifier = Modifier.height(Spacing.sm))
            NoteBanner(text = note, tone = Tone.Muted, icon = Icons.Filled.Info)
        }

        RowDivider()
        GroupLabel("ADD A STAT")
        Spacer(modifier = Modifier.height(Spacing.sm))
        // A `when` rather than the early returns this card used before: the interval group below has to
        // be reached in every branch, and a `return@SectionCard` on a full pill would take it with it.
        when {
            state.isPillFull -> NoteBanner(
                text = "The pill is holding all ${OverlayConfig.MAX_STATS} stats it can. Remove one to " +
                    "add another.",
                tone = Tone.Muted,
                icon = Icons.Filled.Info,
            )

            state.addableStats.isEmpty() -> NoteBanner(
                text = "Every stat GameCore can draw is already on the pill.",
                tone = Tone.Muted,
                icon = Icons.Filled.Info,
            )

            else -> {
                ChoiceRow(
                    options = state.addableStats,
                    selected = null,
                    onSelect = onAdd,
                    label = { if (it.isAlwaysAvailable) it.label else "${it.label} •" },
                    perRow = 2,
                )
                if (state.addableStats.any { !it.isAlwaysAvailable }) {
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    Text(
                        text = "• needs a device, a permission or a network that may not be there. Add " +
                            "one and the preview above shows what it actually reads on this phone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        RowDivider()
        GroupLabel("UPDATES")
        SliderRow(
            title = "Update every",
            // Tenths of a second, because a slider steps in integers and 100 ms is the smallest step
            // worth having. The range and the label are both the pure layer's, so the track and the
            // figure beside it cannot disagree about what half a second is.
            value = state.intervalTenths,
            range = INTERVAL_TENTHS,
            onValueChange = onInterval,
            valueLabel = state.intervalLabel,
            description = "Each tick is one real read per stat above, so what this costs depends on how " +
                "many are on the list. Anything from half a second to ten seconds.",
            onValueChangeFinished = onCommit,
        )
    }
}

/**
 * One stat on the pill: its name, what it reads at this moment, and where it sits.
 *
 * The second line is the reading, and when there is none it is the reason there is none rather than a
 * dash — the same sentence the performance screen gives for that field, so the user gets one explanation
 * instead of two differently-worded ones. The reason is a word, not just a warning tint, which is what
 * keeps §10's "never status by colour alone" true for a row that can be read by three different people
 * on three different devices and mean three different things.
 *
 * The arrows are disabled at the ends rather than wrapping, and the row keeps its remove button always
 * enabled: taking the last stat off is allowed, and the ViewModel switches the pill off and says so
 * rather than leaving an empty plate over a game.
 *
 * The icons are drawn small inside full-size [IconButton]s deliberately: the button keeps the 48 dp touch
 * target §10 asks for while three of them still fit on one row beside the stat's name at any font scale.
 */
@Composable
private fun StatRow(
    stat: HudStat,
    reading: StatReading?,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (HudStat, Int) -> Unit,
    onRemove: (HudStat) -> Unit,
) {
    val isAbsent = reading != null && !reading.isAvailable && !reading.isAwaitingFirstSample
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stat.label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = when {
                    reading == null -> StatReading.AWAITING_REASON
                    reading.isAvailable -> "Reads ${reading.display()} right now."
                    else -> reading.reason ?: "Not available on this device."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (isAbsent) Tone.Warning.colour() else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { onMove(stat, -1) }, enabled = canMoveUp) {
            Icon(
                imageVector = Icons.Filled.ArrowUpward,
                contentDescription = "Move ${stat.label} earlier",
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(onClick = { onMove(stat, 1) }, enabled = canMoveDown) {
            Icon(
                imageVector = Icons.Filled.ArrowDownward,
                contentDescription = "Move ${stat.label} later",
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(onClick = { onRemove(stat) }) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "Take ${stat.label} off the pill",
                tint = Tone.Danger.colour(),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
// ----------------------------------------------------------------------------- §7's floating button

/**
 * §7's floating button: the toggle and its description, a preview, then Position and Size.
 *
 * The preview is drawn here rather than by reusing `FloatingGameButton`, which needs a drag handler and a
 * live position provider a settings screen has no business handing it. Two circles side by side, because
 * the pair of opacities is the actual decision: the first is the button while the panel is open, the
 * second is what the user will spend the session looking at.
 *
 * The size and opacity sliders write on release. The switches write on the tap, because there is no
 * intermediate state worth previewing between on and off.
 *
 * "Vibrate on tap" gets its own short group at the foot of the card. It belongs to neither Position nor
 * Size — it is the button's answer, not its shape or its place — and filing it under one of them to avoid
 * a third heading would be tidier and less true.
 */
@Composable
private fun ButtonCard(
    state: OverlayUiState,
    onShow: (Boolean) -> Unit,
    onEdit: ((FloatingButtonConfig) -> FloatingButtonConfig) -> Unit,
    onCommit: () -> Unit,
    onUpdate: ((FloatingButtonConfig) -> FloatingButtonConfig) -> Unit,
    onResetPosition: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val button = state.button
    val defaults = remember { FloatingButtonConfig() }
    SectionCard(
        title = "Floating button",
        subtitle = "Drag it anywhere over a game; tap it for the control panel",
        icon = Icons.Filled.TouchApp,
        modifier = modifier,
        action = { StatusChip(text = state.buttonState.label, tone = state.buttonState.tone()) },
    ) {
        SwitchRow(
            title = "Show the floating button",
            checked = state.isButtonVisible,
            onCheckedChange = onShow,
            enabled = state.canToggle,
            description = when (state.buttonState) {
                OverlayWindowState.BLOCKED ->
                    "Needs permission to draw over other apps. Grant that above and this switch works."

                OverlayWindowState.LOCKED ->
                    "A game's profile is in charge of the overlay until that game stops."

                else -> "Stays put across app switches and comes back after a restart."
            },
        )

        RowDivider()
        ButtonPreview(button = button)

        RowDivider()
        GroupLabel("POSITION")
        SwitchRow(
            title = "Snap to the nearest edge",
            checked = button.snapToEdge,
            onCheckedChange = { snap -> onUpdate { it.copy(snapToEdge = snap) } },
            description = "Keeps it off the middle of the screen, where a thumb finds it by accident. " +
                "Switching this off leaves it wherever you let go of it.",
        )
        Spacer(modifier = Modifier.height(Spacing.xs))
        // Spec §2's four named positions. A corner is the same place in either orientation, so a tap here
        // pins both; dragging the button afterwards moves it off the corner (and lights no chip). Nothing
        // is preselected until the button is at a corner — a free-dragged spot belongs to no chip.
        val pinned = button.pinnedCorner()
        ChoiceRow(
            options = Corner.entries,
            selected = pinned,
            onSelect = { corner -> onUpdate { it.withCorner(corner) } },
            label = { it.label },
            perRow = 2,
        )
        PositionGroup(
            summary = buttonPositionSummary(pinned, button.x, button.y, defaults.x, defaults.y),
            isDefault = isButtonPositionPristine(button, defaults.x, defaults.y),
            hint = "Dragged somewhere you cannot reach? Put it back.",
            onReset = onResetPosition,
        )

        RowDivider()
        GroupLabel("SIZE")
        Spacer(modifier = Modifier.height(Spacing.xs))
        Text(
            // Size and both opacities read back as one sentence, which is the whole of this group.
            text = buttonSizeSummary(button),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Spec §2's three named sizes: a one-tap shortcut to the sizes most people want. The slider below
        // stays for anything between them; a preset just moves it to a point on the same scale, so the two
        // controls read the one stored size and cannot disagree. A slider between presets lights no chip.
        ChoiceRow(
            options = ButtonSizePreset.entries,
            selected = ButtonSizePreset.of(button.sizeDp),
            // A chip is one tap, one write — the draft-and-commit dance is the slider's, not a choice's.
            onSelect = { preset -> onUpdate { it.copy(sizeDp = preset.sizeDp) } },
            label = { it.label },
        )
        Spacer(modifier = Modifier.height(Spacing.xs))
        SliderRow(
            title = "Size",
            value = button.sizeDp,
            range = FloatingButtonConfig.SIZE_RANGE,
            onValueChange = { size -> onEdit { it.copy(sizeDp = size) } },
            valueLabel = "${button.sizeDp} dp",
            description = sliderDescription(
                "How big a target it is over the game.",
                FloatingButtonConfig.SIZE_RANGE,
                "dp",
            ),
            onValueChangeFinished = onCommit,
        )
        SliderRow(
            title = "Opacity",
            value = button.opacityPercent,
            range = FloatingButtonConfig.OPACITY_RANGE,
            onValueChange = { percent -> onEdit { it.copy(opacityPercent = percent) } },
            valueLabel = "${button.opacityPercent}%",
            description = sliderDescription(
                "How solid it is while you are in GameCore or the panel is open.",
                FloatingButtonConfig.OPACITY_RANGE,
                "%",
            ),
            onValueChangeFinished = onCommit,
        )
        SliderRow(
            title = "Faded opacity",
            value = button.idleOpacityPercent,
            range = FloatingButtonConfig.IDLE_OPACITY_RANGE,
            onValueChange = { percent -> onEdit { it.copy(idleOpacityPercent = percent) } },
            valueLabel = "${button.idleOpacityPercent}%",
            description = sliderDescription(
                "What it drops to while a game has focus and the panel is closed.",
                FloatingButtonConfig.IDLE_OPACITY_RANGE,
                "%",
            ),
            onValueChangeFinished = onCommit,
        )

        RowDivider()
        GroupLabel("FEEDBACK")
        SwitchRow(
            title = "Vibrate on tap",
            checked = button.hapticFeedback,
            onCheckedChange = { haptic -> onUpdate { it.copy(hapticFeedback = haptic) } },
            description = "A short tick, so a tap over a loud game is still confirmed.",
        )
    }
}

/**
 * The button at the size and both opacities it will actually be drawn with, on a dark plate.
 *
 * Two circles rather than one, because the pair is the decision. The left is the button while the user is
 * looking at it; the right is what it will be for the rest of the session, and a 15% idle setting that
 * looked reasonable as a number on a slider is visibly a ghost here before it is chosen.
 *
 * Collapsed into one screen-reader stop for the same reason the pill's preview is: two circles and two
 * percentages read out separately are four announcements, none of which says it is a preview. The
 * sentence is the same one the Size group prints in words, so the two cannot drift apart.
 */
@Composable
private fun ButtonPreview(button: FloatingButtonConfig) {
    val sentence = "Preview of the button: ${buttonSizeSummary(button)}"
    Column {
        GroupLabel("PREVIEW")
        Spacer(modifier = Modifier.height(Spacing.sm))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Spacing.md))
                .background(OverlayPalette.PanelPlate)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Spacing.md))
                .padding(vertical = Spacing.md)
                .clearAndSetSemantics { contentDescription = sentence },
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PreviewButton(button = button, percent = button.opacityPercent, caption = "Panel open")
            PreviewButton(button = button, percent = button.idleOpacityPercent, caption = "In a game")
        }
    }
}

@Composable
private fun PreviewButton(button: FloatingButtonConfig, percent: Int, caption: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(button.sizeDp.dp)
                .alpha(percent / 100f)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.SportsEsports,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size((button.sizeDp / 2).dp),
            )
        }
        Spacer(modifier = Modifier.height(Spacing.xs))
        Text(
            // The caption is under the circle rather than only in the tint, so a faded button is labelled
            // "In a game · 40%" instead of being the dim one somebody has to guess about.
            text = "$caption · $percent%",
            style = MaterialTheme.typography.labelSmall,
            color = OverlayPalette.Muted,
        )
    }
}
// -------------------------------------------------------------------------------- the control panel

/**
 * §4's quick sheet: which toggles it holds, whether it closes itself, and how it is opened.
 *
 * Filed between the button's card and the panel's because that is the order the user meets them in — one
 * tap on the button is the sheet, and the full panel is the thing behind it. The sheet is not a window
 * with a switch of its own, which is why this card has no visibility chip: it exists only while a finger
 * has just summoned it, and there is nothing on screen for the service to report on.
 *
 * The editor is drawn with [OverlayPalette] rather than this screen's theme, on its own dark plate. That
 * is the same choice [PillPreview] makes and for the same reason: the grid above is a picture of the
 * thing being configured, and a Material list of checkboxes would edit the sheet without ever showing it.
 *
 * What this card cannot do is the interesting part, and it says so rather than guessing. Every toggle is
 * offered, including ones this phone may not be able to do, because whether a torch or a refresh-rate
 * switch works is answered by a probe that runs inside the overlay service over a live game — see
 * [OverlayUiState.pinnedToggles]. Filtering on the enum's own `isAlwaysAvailable` hint would mark most of
 * the set unavailable on every device and then refuse to pin three of the six defaults, which is a worse
 * answer than a sentence explaining where the real answer lives.
 */
@Composable
private fun QuickSheetCard(
    state: OverlayUiState,
    onAddPin: (QuickToggle) -> Unit,
    onMovePin: (QuickToggle, Int) -> Unit,
    onRemovePin: (QuickToggle) -> Unit,
    onAutoClose: (Boolean) -> Unit,
    onDoubleTap: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Quick sheet",
        subtitle = state.pinsSubtitle,
        icon = Icons.Filled.TouchApp,
        modifier = modifier,
    ) {
        Text(
            text = "One tap on the floating button opens a small sheet beside it: the toggles below, the " +
                "brightness and volume sliders, and how long this session has been running.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(Spacing.md))
        // Draws its own plate and padding, so it is placed rather than wrapped.
        QuickSheetPinEditor(
            pinned = state.pinnedToggles,
            isAvailable = { true },
            reasonFor = { null },
            onAdd = onAddPin,
            onRemove = onRemovePin,
            onMove = onMovePin,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(Spacing.sm))
        NoteBanner(
            text = "Every toggle is offered here. Whether one can actually do anything on this phone — a " +
                "torch, Do Not Disturb, a refresh-rate switch — depends on the hardware and on the access " +
                "GameCore has been given, and that is only known once the sheet is open. Any pinned " +
                "toggle the device cannot do is dimmed there, with the reason.",
            tone = Tone.Muted,
            icon = Icons.Filled.Info,
        )

        RowDivider()
        GroupLabel("HOW IT BEHAVES")
        SwitchRow(
            title = "Close it by itself",
            checked = state.pill.quickAutoClose,
            onCheckedChange = onAutoClose,
            description = "The sheet leaves after about eight seconds with no touches, so it is not still " +
                "sitting over a match you have gone back to. Off, it stays until you close it, tap away " +
                "from it, or open the full panel.",
        )
        SwitchRow(
            title = "Double tap opens the full panel",
            checked = state.button.doubleTapForPanel,
            onCheckedChange = onDoubleTap,
            description = "Off by default, because turning it on costs every single tap: the button has " +
                "to wait and see whether a second one is coming before it can open the sheet. The full " +
                "panel is on the sheet's own \"More\" either way.",
        )
    }
}

/**
 * How wide the control panel opens — the one thing about the panel that is worth a setting.
 *
 * The panel is the floating button's expanded form, which is why this card sits under the button's rather
 * than under the pill's: the width is stored on [FloatingButtonConfig] and the panel is positioned from
 * the button that opened it. One width for every game, deliberately, because the panel is the same set of
 * controls whichever game is running.
 *
 * The slider writes on release like every other slider here, and the figure beside it is tappable for an
 * exact number — a dp width is the sort of value someone matches to a screenshot or to a figure they were
 * given, and hunting for 268 with a thumb on a 336-step slider is not that.
 *
 * Width only. Height is not a setting and the note below says so, because a user who can set one
 * dimension will look for the other: the panel is measured against the gap between the button and the
 * nearest screen edge and scrolls inside whatever that leaves, so a stored height would be a number the
 * anchoring overrules on every device that had less room than it asked for.
 *
 * Two rows at the bottom are not about the panel's dimensions at all — the quick-launch switch and the
 * two screens the card links to. They are here because this is the card a user opens when they want to
 * change what the panel *is*, and a control panel setting filed anywhere else is a control panel setting
 * nobody finds. The quick-launch switch in particular is deliberately not a third [PanelLayoutStyle]: the
 * row is drawn in whichever shape is selected, so making it a shape would force a choice between two
 * things that were never alternatives.
 */
@Composable
private fun PanelCard(
    state: OverlayUiState,
    onEdit: ((FloatingButtonConfig) -> FloatingButtonConfig) -> Unit,
    onCommit: () -> Unit,
    onUpdate: ((FloatingButtonConfig) -> FloatingButtonConfig) -> Unit,
    onEditWidth: () -> Unit,
    onResetWidth: () -> Unit,
    onMediaAccess: () -> Unit,
    onQuickApps: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val button = state.button
    val split = button.panelLayout == PanelLayoutStyle.SPLIT_EDGES
    val perRow = actionsPerRow(button.panelWidthDp)
    SectionCard(
        title = "Control panel",
        subtitle = "What the floating button opens, and the shape it opens in",
        icon = Icons.Filled.Dashboard,
        modifier = modifier,
        action = {
            StatusChip(
                text = if (split) "Two plates" else "$perRow across",
                tone = Tone.Muted,
            )
        },
    ) {
        if (split) SplitPanelPreview() else PanelPreview(button = button, perRow = perRow)

        RowDivider()
        GroupLabel("LAYOUT")
        Spacer(modifier = Modifier.height(Spacing.sm))
        ChoiceRow(
            options = PanelLayoutStyle.entries,
            selected = button.panelLayout,
            onSelect = { style -> onEdit { it.copy(panelLayout = style) } },
            label = { it.label },
            perRow = 2,
        )
        Spacer(modifier = Modifier.height(Spacing.xs))
        Text(
            text = button.panelLayout.description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        RowDivider()
        GroupLabel("WIDTH")
        SliderRow(
            title = "Width",
            value = button.panelWidthDp,
            range = FloatingButtonConfig.PANEL_WIDTH_RANGE,
            onValueChange = { width -> onEdit { it.copy(panelWidthDp = width) } },
            valueLabel = "${button.panelWidthDp} dp",
            description = if (split) {
                "Kept for the centered layout. Split edges sizes its own plates against the screen."
            } else {
                sliderDescription(
                    "Wider fits more action tiles on a row. Tap the figure to type an exact one.",
                    FloatingButtonConfig.PANEL_WIDTH_RANGE,
                    "dp",
                )
            },
            onValueChangeFinished = onCommit,
            onValueClick = onEditWidth,
            enabled = !split,
        )
        NoteBanner(
            text = if (split) {
                "Split edges is as tall as the screen and as wide as it needs to be on each side, so " +
                    "neither dimension is a setting. Each plate takes a share of the screen's width and " +
                    "scrolls on its own; the gap between them is the game, and tapping it closes the " +
                    "panel. There is no resize grip in this layout — the width slider above is saved for " +
                    "the centered one and takes effect when you switch back."
            } else {
                "Height is not a setting: the panel is as tall as its contents need, up to the room " +
                    "between the button and the nearest screen edge, and scrolls inside whatever that " +
                    "leaves. The width is a request too — the panel never opens wider than the screen it " +
                    "opens on, so in portrait it may be drawn narrower than the figure above."
            },
            tone = Tone.Muted,
            icon = Icons.Filled.Info,
        )
        Spacer(modifier = Modifier.height(Spacing.sm))
        ActionRow {
            Text(
                text = if (split) {
                    "Split edges suits a landscape game held in two hands. In portrait there is less " +
                        "width to divide, so the plates are narrower and the game between them is a strip."
                } else {
                    "You can also drag the grip in the panel's bottom corner while a game is running."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            // Disabled once the width already is the default, for the reason the position resets are:
            // a control that does nothing when pressed reads as a fault rather than as "nothing to do".
            TextButton(
                onClick = onResetWidth,
                enabled = !split && panelWidthResetNote(button.panelWidthDp) != null,
            ) {
                Text("Reset to default size")
            }
        }

        // The three rows below configure parts of the panel this card cannot preview. They are on this
        // card rather than only where they matter for the same reason the Shizuku screen is reachable
        // from Settings and not only from a failure: an explanation is worth more before it is needed.
        RowDivider()
        GroupLabel("WHAT IS IN IT")
        SwitchRow(
            title = "Show quick-launch apps",
            checked = button.showQuickApps,
            onCheckedChange = { show -> onUpdate { it.copy(showQuickApps = show) } },
            description = "Adds a row of app icons under the action tiles, in either layout. Off until " +
                "you pick the apps.",
        )
        NavRow(
            title = "Quick-launch apps",
            onClick = onQuickApps,
            description = "Which apps the row offers, and the order they sit in",
            icon = Icons.Filled.Apps,
            // "3 of 6" rather than "3 apps": the ceiling is part of the answer on a row whose whole
            // purpose is choosing up to six, and it is the model's own limit rather than a retyped one.
            trailing = quickAppsSummary(button.quickAppPackages.size),
        )
        NavRow(
            title = "Media controls",
            onClick = onMediaAccess,
            description = "What the strip under the tiles reads, and the access Android asks for",
            icon = Icons.Filled.MusicNote,
        )
    }
}

/**
 * The panel's proportions, drawn to scale rather than at size.
 *
 * A scale drawing rather than the real composable, which makes it the one preview on this screen that is
 * not the live thing — and the reason is honest: at its widest the panel is 480 dp, and nothing 480 dp
 * wide fits inside a settings card on a phone. Shrinking the real panel would misreport the only fact
 * this card is about, since a panel drawn at half scale has tiles half the size of the ones a thumb has
 * to hit. The caption says "to scale, not to size" for exactly that reason.
 *
 * So what is drawn is the shape: the plate as a fraction of the widest it can be, and the action grid at
 * the row count [actionsPerRow] will really give it — the same function the panel itself lays out with,
 * so the number of tiles per row here is not an illustrator's guess. The grip is drawn with the panel's
 * own icon, in its bottom corner, because a drag handle nobody finds is a feature nobody has.
 *
 * The drawing carries one screen-reader description and no child nodes at all: a diagram made of empty
 * boxes has nothing worth announcing row by row, and the caption under it is the same fact in words.
 */
@Composable
private fun PanelPreview(button: FloatingButtonConfig, perRow: Int) {
    val scheme = MaterialTheme.colorScheme
    val fraction = (button.panelWidthDp.toFloat() / FloatingButtonConfig.MAX_PANEL_WIDTH_DP)
        .coerceIn(PREVIEW_MIN_FRACTION, 1f)
    val caption = "To scale, not to size: ${button.panelWidthDp} dp of a possible " +
        "${FloatingButtonConfig.MAX_PANEL_WIDTH_DP}, with $perRow tiles per row."
    Column {
        GroupLabel("PREVIEW")
        Spacer(modifier = Modifier.height(Spacing.sm))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Spacing.md))
                .background(scheme.surfaceVariant)
                .border(1.dp, scheme.outlineVariant, RoundedCornerShape(Spacing.md))
                .padding(Spacing.sm)
                .clearAndSetSemantics { contentDescription = "Panel shape preview. $caption" },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .clip(RoundedCornerShape(Spacing.sm))
                    .background(OverlayPalette.PanelPlate)
                    .padding(PREVIEW_PLATE_PAD_DP.dp),
                verticalArrangement = Arrangement.spacedBy(PREVIEW_GAP_DP.dp),
            ) {
                PreviewBar(fraction = PREVIEW_HEADER_FRACTION, colour = OverlayPalette.Muted)
                OverlayAction.entries.chunked(perRow).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(PREVIEW_GAP_DP.dp)) {
                        row.forEach { _ ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(PREVIEW_TILE_DP.dp)
                                    .clip(RoundedCornerShape(PREVIEW_GAP_DP.dp))
                                    .background(OverlayPalette.Plate),
                            )
                        }
                        // The last row's gap, held open so four tiles and three do not draw at
                        // different widths. `ChoiceRow`'s idiom, and `PanelActions`' own.
                        repeat(perRow - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PreviewBar(fraction = PREVIEW_FOOTER_FRACTION, colour = OverlayPalette.Divider)
                    Spacer(modifier = Modifier.weight(1f))
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.CompareArrows,
                        contentDescription = null,
                        tint = scheme.primary,
                        modifier = Modifier.size(PREVIEW_GRIP_DP.dp),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(Spacing.sm))
        Text(
            text = caption,
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
        )
    }
}

/**
 * The split layout's proportions, as its own drawing rather than [PanelPreview] bent into a new shape.
 *
 * Separate because there is nothing left to share. [PanelPreview]'s whole subject is one number — how
 * wide the user set the plate, and how many tiles that buys per row — and this layout has no such
 * number: each plate takes a fixed share of whatever screen it opens on, and the tiles inside are two
 * across because that is what a plate a third of a screen wide fits. Reusing the other preview would
 * mean passing it a width it does not use and a row count it did not compute, to draw a shape it was
 * not written for.
 *
 * What is worth showing here is the arrangement, so that is all it draws: two plates against the edges,
 * the game between them, and readouts against controls. The middle is drawn as the game rather than as
 * empty space, because that gap is the entire reason to choose this layout.
 */
@Composable
private fun SplitPanelPreview() {
    val scheme = MaterialTheme.colorScheme
    val caption = "Readouts on one edge, controls on the other, the game between them. Each plate " +
        "scrolls on its own and the whole height of the screen is available."
    Column {
        GroupLabel("PREVIEW")
        Spacer(modifier = Modifier.height(Spacing.sm))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Spacing.md))
                .background(scheme.surfaceVariant)
                .border(1.dp, scheme.outlineVariant, RoundedCornerShape(Spacing.md))
                .padding(Spacing.sm)
                .clearAndSetSemantics { contentDescription = "Split panel shape preview. $caption" },
            horizontalArrangement = Arrangement.spacedBy(PREVIEW_GAP_DP.dp),
        ) {
            // Readouts: a header, then the stat rows and the level sliders.
            Column(
                modifier = Modifier
                    .weight(SPLIT_PREVIEW_PLATE_WEIGHT)
                    .clip(RoundedCornerShape(Spacing.sm))
                    .background(OverlayPalette.PanelPlate)
                    .padding(PREVIEW_PLATE_PAD_DP.dp),
                verticalArrangement = Arrangement.spacedBy(PREVIEW_GAP_DP.dp),
            ) {
                PreviewBar(fraction = PREVIEW_SPLIT_HEADER_FRACTION, colour = OverlayPalette.Muted)
                repeat(SPLIT_PREVIEW_READOUT_ROWS) {
                    PreviewBar(fraction = 1f, colour = OverlayPalette.Plate)
                }
                PreviewBar(fraction = PREVIEW_SPLIT_FOOTER_FRACTION, colour = OverlayPalette.Divider)
            }
            // The game. Named in the drawing because the gap is the point.
            Box(
                modifier = Modifier.weight(SPLIT_PREVIEW_GAME_WEIGHT),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "GAME",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            // Controls: the action grid, two tiles across.
            Column(
                modifier = Modifier
                    .weight(SPLIT_PREVIEW_PLATE_WEIGHT)
                    .clip(RoundedCornerShape(Spacing.sm))
                    .background(OverlayPalette.PanelPlate)
                    .padding(PREVIEW_PLATE_PAD_DP.dp),
                verticalArrangement = Arrangement.spacedBy(PREVIEW_GAP_DP.dp),
            ) {
                OverlayAction.entries.chunked(SPLIT_PREVIEW_TILES_PER_ROW).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(PREVIEW_GAP_DP.dp)) {
                        row.forEach { _ ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(PREVIEW_TILE_DP.dp)
                                    .clip(RoundedCornerShape(PREVIEW_GAP_DP.dp))
                                    .background(OverlayPalette.Plate),
                            )
                        }
                        repeat(SPLIT_PREVIEW_TILES_PER_ROW - row.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(Spacing.sm))
        Text(
            text = caption,
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
        )
    }
}

/** One line of the scale drawing: a header, or the level slider under the grid. */
@Composable
private fun PreviewBar(fraction: Float, colour: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth(fraction)
            .height(PREVIEW_BAR_DP.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(colour),
    )
}

/**
 * The panel's width, typed.
 *
 * `ColorScreen`'s dialog, with its rules rather than a variation on them: digits only, and "Set" stays
 * disabled until what is typed is inside the range. Clamping silently would take "600" and store 480,
 * which is not the figure the user typed and confirmed.
 *
 * No sign button here — a width has no negative half to reach.
 */
@Composable
private fun PanelWidthDialog(value: Int, onSet: (Int) -> Unit, onDismiss: () -> Unit) {
    val range = FloatingButtonConfig.PANEL_WIDTH_RANGE
    var text by remember { mutableStateOf(value.toString()) }
    val entered = text.trim().toIntOrNull()
    val isValid = entered != null && entered in range
    val rangeText = "${range.first} to ${range.last} dp"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Panel width", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                TextFieldRow(
                    label = "Width in dp",
                    value = text,
                    onValueChange = { raw -> text = raw.filter { it.isDigit() } },
                    placeholder = value.toString(),
                    maxLength = range.last.toString().length,
                    description = "Anything from $rangeText. The default is " +
                        "${FloatingButtonConfig.DEFAULT_PANEL_WIDTH_DP}.",
                    keyboardType = KeyboardType.Number,
                )
                if (!isValid && text.isNotBlank()) {
                    Text(
                        text = "That is outside $rangeText.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Tone.Warning.colour(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { entered?.let(onSet) }, enabled = isValid) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
    )
}

/**
 * The scale drawing's own dimensions, in dp on the settings screen.
 *
 * Not the panel's, and not derived from them: these size a diagram inside a card, while the panel's
 * constants size controls a thumb has to hit. Sharing them would tie a settings card's legibility to a
 * touch target's minimum, and neither number would then be free to be right.
 *
 * They are deliberately not [com.gamecore.ui.theme.Spacing] tokens either. The token scale is the rhythm
 * a layout is built on; a 4 dp gap between two boxes in a diagram is a drawing's proportion that happens
 * to land on the same figure. Tying them together would mean a change to the app's spacing scale silently
 * redrew an illustration.
 */
private const val PREVIEW_TILE_DP = 16
private const val PREVIEW_BAR_DP = 4
private const val PREVIEW_GAP_DP = 4
private const val PREVIEW_GRIP_DP = 12
private const val PREVIEW_PLATE_PAD_DP = 6

/**
 * How long the drawing's header and footer bars run, as fractions of the plate.
 *
 * Named rather than left inline because they are the one part of the diagram with no counterpart in the
 * real panel to check against: the plate's width is the user's own setting and the tile grid comes from
 * [actionsPerRow], but a title bar is a title bar. These say "a part-width bar, not a full one", which is
 * all the drawing is claiming.
 */
private const val PREVIEW_HEADER_FRACTION = 0.55f
private const val PREVIEW_FOOTER_FRACTION = 0.6f
private const val PREVIEW_SPLIT_HEADER_FRACTION = 0.7f
private const val PREVIEW_SPLIT_FOOTER_FRACTION = 0.8f

/**
 * A floor under the plate's width as a fraction of the widest the panel can be.
 *
 * The range's own minimum draws at 0.3, so this catches only a config that reached the screen without
 * [FloatingButtonConfig.normalised] — and `fillMaxWidth` takes nothing outside 0..1 at all. A card with
 * an invisible plate and a caption under it explaining its proportions is the failure being avoided.
 */
private const val PREVIEW_MIN_FRACTION = 0.2f

/**
 * The split drawing's proportions, as weights rather than fractions of the maximum.
 *
 * Weights because that drawing has no maximum to be a fraction of: the real layout divides whatever
 * screen it opens on, so what the card has to convey is the ratio between the plates and the game, and
 * a ratio is what these are. They are the diagram's own figures and not the layout's — `splitPlateWidth`
 * in `com.gamecore.core.overlay` decides the real share against the real screen — because a card 340 dp
 * wide drawn at the true share would give each plate about 100 dp, which is too narrow to show a grid in
 * at all. Erring wider here shows the arrangement; matching the arithmetic would show a smudge.
 *
 * Two tiles per row for the same reason the real plate settles there: a third of a phone's landscape
 * width is a little over 200 dp, and `actionsPerRow` gives 2 across at that width.
 */
private const val SPLIT_PREVIEW_PLATE_WEIGHT = 1f
private const val SPLIT_PREVIEW_GAME_WEIGHT = 0.7f
private const val SPLIT_PREVIEW_TILES_PER_ROW = 2

/** Stats and levels, stood in for by bars: enough rows to read as a column of readouts. */
private const val SPLIT_PREVIEW_READOUT_ROWS = 3
