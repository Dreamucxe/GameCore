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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.core.model.FloatingButtonConfig
import com.gamecore.core.model.HudStat
import com.gamecore.core.model.OverlayConfig
import com.gamecore.core.overlay.OverlayPalette
import com.gamecore.core.overlay.PerformancePill
import com.gamecore.domain.monitoring.StatReading
import com.gamecore.ui.Destination
import com.gamecore.ui.components.ActionRow
import com.gamecore.ui.components.ChoiceRow
import com.gamecore.ui.components.KeyValueRow
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
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.colour
import com.gamecore.ui.hud.conditionNote

/**
 * §7's floating button and §8's performance pill, configured.
 *
 * The two windows that are up for a whole session get one screen because they are chosen together: how
 * big the button is and how opaque the pill is are both answers to the same question, which is how much
 * of the game the user is willing to cover.
 *
 * The pill preview is drawn with the same composable the overlay service uses, fed by the same reader, so
 * a stat this device does not publish reads "n/a" here exactly as it will over a game. That is the
 * difference between a preview and a mock-up, and it is the only honest way to let someone choose eight
 * stats before they have seen any of them.
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

    // The overlay permission is granted on another app's screen, and Android gives no callback for it.
    OnResume { viewModel.refreshPermission() }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Overlay",
                subtitle = "The floating button and the stats pill, and how much of the game they cover",
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
            PillCard(
                state = state,
                readings = readings,
                onShow = viewModel::setPillVisible,
                onEdit = viewModel::editPill,
                onCommit = viewModel::commitPill,
                onUpdate = viewModel::updatePill,
                onInterval = viewModel::setInterval,
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
                modifier = padded,
            )
        }
    }
}

/**
 * What is on screen right now, from the service rather than from the request.
 *
 * The summary counts all four windows, not just this screen's two, because "hide everything" is here and a
 * user reaching for it wants to know what everything currently is. The banner order matters: no permission
 * is why nothing appears at all, and a profile in charge is why a switch flicked here would be overridden,
 * so whichever of the two applies is the one sentence shown.
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
            Spacer(modifier = Modifier.height(6.dp))
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
            Spacer(modifier = Modifier.height(6.dp))
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
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Nothing is being drawn, so the overlay service is not running and costs nothing.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * §7's floating button: whether it is up, how big, and how far it fades once the game has focus.
 *
 * The preview is drawn here rather than reusing `FloatingGameButton`, which needs a drag handler and a
 * live position provider it has no business being given by a settings screen. Two circles side by side,
 * because the pair of opacities is the actual decision: the first is the button while the panel is open,
 * the second is what the user will spend the session looking at.
 *
 * The size and opacity sliders write on release. The switches write on the tap, because there is no
 * intermediate state worth previewing between on and off.
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
    SectionCard(
        title = "Floating button",
        subtitle = "Drag it anywhere over a game; tap it for the control panel",
        icon = Icons.Filled.TouchApp,
        modifier = modifier,
        action = {
            StatusChip(
                text = if (state.isButtonVisible) "Up" else "Down",
                tone = if (state.isButtonVisible) Tone.Good else Tone.Muted,
            )
        },
    ) {
        SwitchRow(
            title = "Show the floating button",
            checked = state.isButtonVisible,
            onCheckedChange = onShow,
            enabled = state.canToggle,
            description = "Stays put across app switches and comes back after a restart.",
        )
        RowDivider()
        ButtonPreview(button = button)
        RowDivider()
        SliderRow(
            title = "Size",
            value = button.sizeDp,
            range = FloatingButtonConfig.SIZE_RANGE,
            onValueChange = { size -> onEdit { it.copy(sizeDp = size) } },
            valueLabel = "${button.sizeDp} dp",
            onValueChangeFinished = onCommit,
        )
        SliderRow(
            title = "Opacity",
            value = button.opacityPercent,
            range = FloatingButtonConfig.OPACITY_RANGE,
            onValueChange = { percent -> onEdit { it.copy(opacityPercent = percent) } },
            valueLabel = "${button.opacityPercent}%",
            description = "How solid it is while you are in GameCore or the panel is open.",
            onValueChangeFinished = onCommit,
        )
        SliderRow(
            title = "Faded opacity",
            value = button.idleOpacityPercent,
            range = FloatingButtonConfig.IDLE_OPACITY_RANGE,
            onValueChange = { percent -> onEdit { it.copy(idleOpacityPercent = percent) } },
            valueLabel = "${button.idleOpacityPercent}%",
            description = "What it drops to while a game has focus and the panel is closed.",
            onValueChangeFinished = onCommit,
        )
        RowDivider()
        SwitchRow(
            title = "Snap to the nearest edge",
            checked = button.snapToEdge,
            onCheckedChange = { snap -> onUpdate { it.copy(snapToEdge = snap) } },
            description = "Keeps it off the middle of the screen, where a thumb finds it by accident.",
        )
        SwitchRow(
            title = "Vibrate on tap",
            checked = button.hapticFeedback,
            onCheckedChange = { haptic -> onUpdate { it.copy(hapticFeedback = haptic) } },
            description = "A short tick, so a tap over a loud game is still confirmed.",
        )
        RowDivider()
        ActionRow {
            Text(
                text = "Dragged somewhere you cannot reach? Put it back.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onResetPosition) { Text("Reset position") }
        }
    }
}

/**
 * The button at the size and both opacities it will actually be drawn with, on a dark plate.
 *
 * Two circles rather than one, because the pair is the decision. The left is the button while the user is
 * looking at it; the right is what it will be for the rest of the session, and a 15% idle setting that
 * looked fine as a number on a slider is visibly a ghost here before it is chosen.
 */
@Composable
private fun ButtonPreview(button: FloatingButtonConfig) {
    Column {
        Text(
            text = "PREVIEW",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(OverlayPalette.PanelPlate)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                .padding(vertical = 14.dp),
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
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "$caption · $percent%",
            style = MaterialTheme.typography.labelSmall,
            color = OverlayPalette.Muted,
        )
    }
}

/**
 * §8's performance pill, previewed with the composable the service draws and the readings it draws from.
 *
 * That is the whole point of this card. Eight stats, four sliders and a layout switch are a lot to choose
 * blind, and a hand-drawn mock-up would show a tidy row of plausible numbers — including a frame rate this
 * device may have no way to measure. What is below is the real thing: the same [PerformancePill], the same
 * [StatReading]s, so a stat that will read "n/a" over a game reads "n/a" here.
 *
 * The switch is disabled when there are no stats as well as when the permission or a profile blocks it: an
 * empty pill is a small dark rectangle sitting over a game, and the ViewModel refuses that anyway.
 */
@Composable
private fun PillCard(
    state: OverlayUiState,
    readings: List<StatReading>,
    onShow: (Boolean) -> Unit,
    onEdit: ((OverlayConfig) -> OverlayConfig) -> Unit,
    onCommit: () -> Unit,
    onUpdate: ((OverlayConfig) -> OverlayConfig) -> Unit,
    onInterval: (Int) -> Unit,
    onResetPosition: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pill = state.pill
    SectionCard(
        title = "Performance pill",
        subtitle = "Live stats on a small plate, over whatever is running",
        icon = Icons.Filled.Insights,
        modifier = modifier,
        action = {
            StatusChip(
                text = if (state.isPillVisible) "Up" else "Down",
                tone = if (state.isPillVisible) Tone.Good else Tone.Muted,
            )
        },
    ) {
        SwitchRow(
            title = "Show the performance pill",
            checked = state.isPillVisible,
            onCheckedChange = onShow,
            enabled = state.canToggle && state.hasStats,
            description = if (state.hasStats) {
                "Drag it anywhere; it snaps to the nearest edge."
            } else {
                "Add at least one stat below first."
            },
        )
        RowDivider()
        PillPreview(pill = pill, readings = readings)
        RowDivider()
        SwitchRow(
            title = "Stack the stats vertically",
            checked = pill.isVertical,
            onCheckedChange = { vertical -> onUpdate { it.copy(isVertical = vertical) } },
            description = "A column down the side instead of a strip along the top.",
        )
        SwitchRow(
            title = "Show labels",
            checked = pill.showLabels,
            onCheckedChange = { labels -> onUpdate { it.copy(showLabels = labels) } },
            description = "\"CPU 42%\" rather than \"42%\". Off is narrower and covers less.",
        )
        RowDivider()
        SliderRow(
            title = "Text size",
            value = pill.textSizeSp,
            range = OverlayConfig.TEXT_SIZE_RANGE,
            onValueChange = { size -> onEdit { it.copy(textSizeSp = size) } },
            valueLabel = "${pill.textSizeSp} sp",
            onValueChangeFinished = onCommit,
        )
        SliderRow(
            title = "Opacity",
            value = pill.opacityPercent,
            range = OverlayConfig.OPACITY_RANGE,
            onValueChange = { percent -> onEdit { it.copy(opacityPercent = percent) } },
            valueLabel = "${pill.opacityPercent}%",
            description = "Fades the plate with the text, so the numbers stay as readable as they were.",
            onValueChangeFinished = onCommit,
        )
        SliderRow(
            title = "Corner rounding",
            value = pill.cornerRadiusDp,
            range = OverlayConfig.CORNER_RANGE,
            onValueChange = { radius -> onEdit { it.copy(cornerRadiusDp = radius) } },
            valueLabel = "${pill.cornerRadiusDp} dp",
            onValueChangeFinished = onCommit,
        )
        SliderRow(
            title = "Update every",
            value = state.intervalTenths,
            range = OverlayUiState.INTERVAL_TENTHS,
            onValueChange = onInterval,
            valueLabel = state.intervalLabel,
            description = "Each tick is a real read. Faster is more current and costs the game more.",
            onValueChangeFinished = onCommit,
        )
        RowDivider()
        ActionRow {
            Text(
                text = "Dragged off the edge? Put it back.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onResetPosition) { Text("Reset position") }
        }
    }
}

/**
 * The pill as it will be drawn, on a plate standing in for the game behind it.
 *
 * The banner lists the stats that read *nothing at all* — not the ones still waiting for the sampler's
 * first tick, which render identically and mean the opposite. Announcing "this device reports no CPU
 * temperature" for the first second of every visit would teach the user to ignore the one message on this
 * screen worth reading.
 */
@Composable
private fun PillPreview(pill: OverlayConfig, readings: List<StatReading>) {
    val absent = readings.filter { !it.isAvailable && !it.isAwaitingFirstSample }
    Column {
        Text(
            text = "PREVIEW",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(OverlayPalette.PanelPlate)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                .padding(14.dp),
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
        if (absent.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            NoteBanner(
                text = "This device reports no ${absent.joinToString { it.stat.label.lowercase() }}. " +
                    "Those read \"${StatReading.PLACEHOLDER}\" over a game rather than a number.",
                tone = Tone.Warning,
                icon = Icons.Filled.Info,
            )
        }
    }
}

/**
 * Which stats are on the pill, in the order they are drawn, and what each one reads right now.
 *
 * The order is editable because the pill is read at a glance in the corner of a game: the stat that
 * matters to this user goes at the front, and "front" is a real position rather than a preference, so the
 * arrows move the item in the stored list. The ends are walls rather than a wrap — a stat that jumped from
 * first to last on one more tap would be an accident every time.
 *
 * Each row carries its live reading, which is how a user finds out that the frame rate they just added
 * reads nothing on this phone before they take it into a game.
 */
@Composable
private fun StatsCard(
    state: OverlayUiState,
    readings: List<StatReading>,
    onAdd: (HudStat) -> Unit,
    onMove: (HudStat, Int) -> Unit,
    onRemove: (HudStat) -> Unit,
    modifier: Modifier = Modifier,
) {
    val stats = state.pill.stats
    SectionCard(
        title = "Stats on the pill",
        subtitle = "${stats.size} of ${OverlayConfig.MAX_STATS}, in drawing order",
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
            Spacer(modifier = Modifier.height(8.dp))
            NoteBanner(text = note, tone = Tone.Muted, icon = Icons.Filled.Info)
        }
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = "ADD A STAT",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (state.isPillFull) {
            NoteBanner(
                text = "The pill is holding all ${OverlayConfig.MAX_STATS} stats it can. Remove one to " +
                    "add another.",
                tone = Tone.Muted,
                icon = Icons.Filled.Info,
            )
            return@SectionCard
        }
        if (state.addableStats.isEmpty()) {
            NoteBanner(
                text = "Every stat GameCore can draw is already on the pill.",
                tone = Tone.Muted,
                icon = Icons.Filled.Info,
            )
            return@SectionCard
        }
        ChoiceRow(
            options = state.addableStats,
            selected = null,
            onSelect = onAdd,
            label = { if (it.isAlwaysAvailable) it.label else "${it.label} •" },
            perRow = 2,
        )
        if (state.addableStats.any { !it.isAlwaysAvailable }) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "• needs a device, a permission or a network that may not be there. Add one and " +
                    "the preview above shows what it actually reads on this phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One stat on the pill: its name, what it reads at this moment, and where it sits.
 *
 * The second line is the reading, and when there is none it is the reason there is none rather than a
 * dash — the same sentence the performance screen gives for that field, so the user gets one explanation
 * instead of two differently-worded ones.
 *
 * The arrows are disabled at the ends rather than wrapping, and the row keeps its remove button always
 * enabled: taking the last stat off is allowed, and the ViewModel switches the pill off and says so rather
 * than leaving an empty plate over a game.
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
