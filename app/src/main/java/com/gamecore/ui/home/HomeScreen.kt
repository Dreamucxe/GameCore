package com.gamecore.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.core.common.Formatters
import com.gamecore.core.model.MemoryReclaimReport
import com.gamecore.domain.gaming.GamingState
import com.gamecore.ui.Destination
import com.gamecore.ui.components.ABSENT
import com.gamecore.ui.components.ActionRow
import com.gamecore.ui.components.NavRow
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.ReadoutTile
import com.gamecore.ui.components.RowDivider
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.StatEntry
import com.gamecore.ui.components.StatStrip
import com.gamecore.ui.components.SwitchRow
import com.gamecore.ui.components.TileRow
import com.gamecore.ui.components.Tone
import kotlinx.coroutines.delay

/**
 * The dashboard: what the device is doing, what GameCore is doing about it, and the way to everything
 * else.
 *
 * The order is deliberate. Anything that needs the user's attention — a message, settings a killed
 * session never put back, a thermal warning, a game being tracked right now — is above the figures,
 * because those cards are the ones with a button on them. The live grid is next, and the navigation is
 * last: a user who opened the app to check a temperature has read it before they have finished scrolling.
 *
 * Every reading arrives as a [com.gamecore.ui.components.Readout] — a label and a string. §24A.2: this
 * function cannot format a temperature wrongly, or render a missing one as a zero, because it never sees
 * a number.
 */
@Composable
fun HomeScreen(
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Collected with a lifecycle, not in the ViewModel. PerformanceMonitor.snapshots samples only while
    // it has a collector, so this is what starts the sampler when the dashboard appears and — more to
    // the point — stops it when the user navigates away.
    val readouts by viewModel.readouts.collectAsStateWithLifecycle(HomeReadouts.AWAITING)

    val padded = Modifier.padding(horizontal = ScreenPadding)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(title = "GameCore", subtitle = subtitleFor(state))
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

        // Two to a row, and the odd one out keeps its width rather than stretching across the page.
        readouts.tiles.chunked(TILES_PER_ROW).forEach { row ->
            item {
                TileRow(modifier = padded) {
                    row.forEach { readout ->
                        ReadoutTile(
                            readout = readout,
                            modifier = Modifier.weight(1f),
                            icon = tileIcon(readout.label),
                            onClick = { onNavigate(Destination.Performance) },
                        )
                    }
                    repeat(TILES_PER_ROW - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                }
            }
        }

        item { StatusCard(state = state, onNavigate = onNavigate, modifier = padded) }

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

        item { ShortcutCard(onNavigate = onNavigate, modifier = padded) }
    }
}

private fun subtitleFor(state: HomeUiState): String = when {
    state.gaming.isTracking -> "Playing ${state.gaming.gameLabel}"
    state.profileCount == 0 -> "No game profiles yet"
    else -> Formatters.count(state.profileCount, "game profile")
}

/** A glyph per tile. Decoration, so an unrecognised label simply gets none. */
private fun tileIcon(label: String): ImageVector? = when (label) {
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

/**
 * The device settings a killed session never put back, and the two honest answers to them.
 *
 * This card is the visible half of §16's restore table. A session that ended with the process — a task
 * swipe, a low-memory kill — leaves the device as the profile left it, and GameCore is the only thing
 * that knows a 120 Hz pin was its doing. Offered rather than applied silently at launch, for the reason
 * [com.gamecore.domain.StartupCoordinator] gives: the user may still be playing.
 *
 * "Leave as they are" is a real choice and drops the rows, so the card does not come back tomorrow at
 * someone who has already put their brightness where they want it.
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
                    Spacer(modifier = Modifier.width(8.dp))
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
 * Information rather than a task, so it is dismissable and dismissing it is enough. It exists because a
 * session ended by a process death is recorded with an estimated end time, and a user comparing their
 * history against what they remember playing deserves to know which rows those are before they conclude
 * the app cannot count.
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
 * What the launch's memory pass did, in one sentence, once.
 *
 * A banner and not a card, because it is the shortest-lived thing on this screen: it appears for one
 * launch, says what happened, and goes when the user acknowledges it or the next game starts. There is no
 * "details" affordance and no per-app list — the apps are closed, a list of them would only invite the
 * user to look for one that is not there, and GameCore is not going to reopen them.
 *
 * The sentence is [com.gamecore.core.model.MemoryReclaimReport.Completed.summary] verbatim, including the
 * cases where it declines to give a figure. §24 again: a pass that closed four apps and could not measure
 * what that freed says so, rather than the banner filling in a plausible number.
 *
 * Muted rather than accented when nothing was closed or nothing ran. A skip and an empty pass are both
 * "GameCore did nothing to your apps", which is reassurance and not news, and colouring them like an
 * action would teach the user to distrust the colour.
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
 * subtracted from another clock reading — there is nothing to hold in state, and a per-second emission
 * through the state flow would re-run the whole dashboard's `combine` once a second for a string only
 * this card reads.
 *
 * [com.gamecore.core.model.ProfileApplication.summary] is shown verbatim, including its failures. §24:
 * a profile whose refresh-rate pin was not honoured says so on the card, rather than the card saying
 * "optimized" because the write returned without throwing.
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
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = application.summary(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        ActionRow {
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onStop) { Text("Stop tracking") }
        }
    }
}

/**
 * What GameCore's optional parts are doing, each row leading to the screen that changes it.
 *
 * The trailing text is the state, not a verdict: Shizuku's own six-way label rather than "on/off", the
 * overlay's list of what is actually up, and the capability count as a fraction. §29 — "3 of 8
 * optimizations available" is a true sentence on a device where Shizuku is connected but the panel has
 * one refresh rate, and "connected" on its own is not.
 */
@Composable
private fun StatusCard(
    state: HomeUiState,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = "Status", icon = Icons.Filled.Insights, modifier = modifier) {
        NavRow(
            title = "Shizuku",
            description = "Optional elevated access for refresh-rate and animation control",
            icon = Icons.Filled.Terminal,
            trailing = state.shizuku.label,
            trailingTone = if (state.shizuku.isUsable) Tone.Good else Tone.Muted,
            onClick = { onNavigate(Destination.Shizuku) },
        )
        RowDivider()
        NavRow(
            title = "Overlays",
            description = "The floating button, the stats pill, the crosshair and the HUD",
            icon = Icons.Filled.Layers,
            trailing = state.overlay.summary,
            trailingTone = if (state.overlay.anythingVisible) Tone.Good else Tone.Muted,
            onClick = { onNavigate(Destination.Overlay) },
        )
        RowDivider()
        NavRow(
            title = "Device optimizations",
            description = "What this device will actually let GameCore change",
            icon = Icons.Filled.Tune,
            trailing = "${state.capabilities.usableControlCount} of ${state.capabilities.controlCount}",
            trailingTone = Tone.Muted,
            onClick = { onNavigate(Destination.Permissions) },
        )
        RowDivider()
        NavRow(
            title = "Game profiles",
            description = "Settings applied when a game starts and put back when it ends",
            icon = Icons.Filled.SportsEsports,
            trailing = state.profileCount.toString(),
            trailingTone = Tone.Muted,
            onClick = { onNavigate(Destination.Games) },
        )
    }
}

/**
 * The two overlay switches worth reaching without opening a settings screen, and the way to take
 * everything down.
 *
 * Disabled rather than hidden without the overlay permission, with the reason under them: a switch that
 * is not there tells the user the feature does not exist on their device, which is a different and
 * untrue statement. Pressing one anyway is handled by the ViewModel, which explains what is missing —
 * this is a `SwitchRow`, and a disabled one cannot be pressed, so the guard there is for the profile
 * path that shares it.
 *
 * "Hide everything" is separate from the switches on purpose: it stops the service as well, and a user
 * who wants their screen back does not want to work out which of four windows is the one they can see.
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
            Spacer(modifier = Modifier.height(4.dp))
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

/**
 * Everything §5 asks the dashboard to be able to reach, as one list at the bottom.
 *
 * Last on the screen deliberately. Games, HUD, Sessions and Settings are already one tap away on the
 * bottom bar; these are the destinations that have no other route to them, and putting them above the
 * live figures would make the dashboard a menu with a temperature in it.
 */
@Composable
private fun ShortcutCard(
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = "Everything else", icon = Icons.Filled.GridView, modifier = modifier) {
        NavRow(
            title = "Performance",
            description = "Live graphs, per-core detail, network and thermals",
            icon = Icons.Filled.Speed,
            onClick = { onNavigate(Destination.Performance) },
        )
        NavRow(
            title = "Gaming HUD",
            description = "Build a layout of readouts to draw over a game",
            icon = Icons.Filled.GridView,
            onClick = { onNavigate(Destination.Hud) },
        )
        NavRow(
            title = "Crosshair",
            description = "A fixed aiming point, drawn over the screen",
            icon = Icons.Filled.Adjust,
            onClick = { onNavigate(Destination.Crosshair) },
        )
        NavRow(
            title = "Colour correction",
            description = "Channel gain, gamma, saturation and presets, applied to the display",
            icon = Icons.Filled.Palette,
            onClick = { onNavigate(Destination.Colour) },
        )
        NavRow(
            title = "Tools",
            description = "Screenshot, recording, brightness, volume, Do Not Disturb, torch",
            icon = Icons.Filled.Build,
            onClick = { onNavigate(Destination.Tools) },
        )
        NavRow(
            title = "Session history",
            description = "What was played, for how long, and what it cost the battery",
            icon = Icons.Filled.History,
            onClick = { onNavigate(Destination.Sessions) },
        )
        NavRow(
            title = "Permissions",
            description = "What GameCore asks for, and what each one unlocks",
            icon = Icons.Filled.Shield,
            onClick = { onNavigate(Destination.Permissions) },
        )
        NavRow(
            title = "Settings",
            description = "Appearance, overlays, sampling, games and stored data",
            icon = Icons.Filled.Settings,
            onClick = { onNavigate(Destination.Settings) },
        )
    }
}

/**
 * A session's running time, recomputed once a second for as long as this card is composed.
 *
 * `produceState` rather than a ViewModel field: the value is `now - startedAt`, so there is nothing to
 * hold — and a tick published through [HomeViewModel.state] would re-run the dashboard's whole `combine`
 * every second to change one string. The coroutine is tied to the composition, so a user who navigates
 * away stops the clock rather than leaving it running behind a screen nobody is looking at.
 *
 * Keyed on the start time, so a straight switch from one game to another restarts the count instead of
 * continuing the first game's.
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
 * The clamp matters more than it looks: [System.currentTimeMillis] can move backwards across an NTP
 * correction, and a negative duration would otherwise be rendered by a formatter that has no case for
 * one. §24 forbids inventing figures in both directions — a session that appears to have started in the
 * future gets no duration rather than a fictional one.
 */
private fun elapsedSince(startedAtMillis: Long): String = if (startedAtMillis <= 0L) {
    ABSENT
} else {
    Formatters.duration((System.currentTimeMillis() - startedAtMillis).coerceAtLeast(0L))
}

/** One second, which is the resolution [Formatters.duration] prints. */
private const val TICK_MILLIS = 1_000L
