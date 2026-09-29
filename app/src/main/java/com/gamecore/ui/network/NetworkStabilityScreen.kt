package com.gamecore.ui.network

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.PlainCard
import com.gamecore.ui.components.ReadoutRow
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.SectionGap
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.SwitchRow
import com.gamecore.ui.components.Tone
import kotlinx.coroutines.delay

/**
 * §21/§C's network-stability screen: the live connection, the last jitter window, and an honest list of
 * what this device will let GameCore read.
 *
 * A leaf screen — it takes only [onBack]. There is no save button: the enable switch writes through as it
 * is flicked, and every other line is a reading the [NetworkStabilityViewModel] has already turned into a
 * string. Where a figure is not a real reading it shows "—" with the reason underneath, never a zero, which
 * is the whole point of the stability card living here rather than being folded into a single number.
 *
 * The auto-refresh is deliberately gentle and tied to this composable. A stability window is a small burst
 * of real TCP handshakes, so it is not something to send every second: the cadence below is slow, the
 * controller reuses a recent window rather than re-bursting on every tick, and the loop stops the moment the
 * user navigates away because it lives in the composition, not the ViewModel. The manual refresh in the
 * header is there for when the user wants a reading now.
 */
@Composable
fun NetworkStabilityScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NetworkStabilityViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Low-cadence auto-refresh. Composition-scoped, so it runs only while this screen is on screen and is
    // cancelled on the way out — the same lifetime as the ViewModel's WhileSubscribed state. The initial
    // reading is the ViewModel's job (it refreshes on construction), so this loop waits before its first tick.
    LaunchedEffect(Unit) {
        while (true) {
            delay(REFRESH_INTERVAL_MILLIS)
            viewModel.refresh()
        }
    }

    val padded = Modifier.padding(horizontal = ScreenPadding)

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Network stability",
                subtitle = "How steady this connection is right now",
                onBack = onBack,
                action = {
                    IconButton(onClick = viewModel::refresh, enabled = !state.isRefreshing) {
                        if (state.isRefreshing) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(20.dp),
                            )
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                        }
                    }
                },
            )
        }
        if (!state.isLoaded) {
            item { LoadingCard(modifier = padded) }
            return@LazyColumn
        }
        item { EnableCard(state = state, onToggle = viewModel::setEnabled, modifier = padded) }
        item { LiveCard(state = state, modifier = padded) }
        item { CapabilitiesCard(state = state, modifier = padded) }
        item { SafetyCard(modifier = padded) }
    }
}

/**
 * The master switch, and the one line that says why the figures below are not being measured.
 *
 * The chip reads the request, not a report: "On" means the watch is asked for. When latency measurement is
 * switched off in Settings the burst cannot run whatever this switch says, so the note names that rather
 * than letting ping and jitter read as a device that cannot measure them.
 */
@Composable
private fun EnableCard(
    state: NetworkStabilityState,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Network stability",
        subtitle = "Watch jitter and connection steadiness while you play",
        modifier = modifier,
        action = {
            StatusChip(
                text = if (state.isEnabled) "On" else "Off",
                tone = if (state.isEnabled) Tone.Good else Tone.Muted,
            )
        },
    ) {
        SwitchRow(
            title = "Watch network stability",
            checked = state.isEnabled,
            onCheckedChange = onToggle,
            description = "Runs short latency bursts to a well-known host and reports the jitter between " +
                "them. It sends only a TCP handshake and reads nothing about the game.",
        )
        if (!state.isLatencyMeasured) {
            SectionGap(6)
            NoteBanner(
                text = "Latency measurement is switched off in Settings, so ping, jitter and probe counts " +
                    "stay unavailable until it is turned back on.",
                tone = Tone.Warning,
                icon = Icons.Filled.Info,
            )
        }
    }
}

/**
 * The live connection and the latest stability window, one [ReadoutRow] each.
 *
 * Every row is already a string with its own tone and, where it is absent, its own reason — the card only
 * lays them out. The instability warning rides underneath as a banner rather than a row, because it is a
 * sentence about the whole window ("probes did not complete", "latency is varying by…") and not a figure.
 */
@Composable
private fun LiveCard(state: NetworkStabilityState, modifier: Modifier = Modifier) {
    SectionCard(
        title = "Now",
        subtitle = "The live connection and its most recent stability window",
        modifier = modifier,
    ) {
        state.liveRows.forEach { ReadoutRow(it) }
        state.stabilityWarning?.let { warning ->
            SectionGap(6)
            NoteBanner(text = warning, tone = Tone.Warning, icon = Icons.Filled.Info)
        }
    }
}

/**
 * Every reading this device might expose, with whether it is available here and, when it is not, why.
 *
 * The honesty §24 asks for, in a place the user can read it: a value GameCore cannot read on this device is
 * marked unavailable with the same sentence it would get anywhere else, never dropped silently and never
 * shown as a plausible-looking zero.
 */
@Composable
private fun CapabilitiesCard(state: NetworkStabilityState, modifier: Modifier = Modifier) {
    SectionCard(
        title = "What this device allows",
        subtitle = "Each reading, and why it is or is not available here",
        modifier = modifier,
    ) {
        if (state.capabilities.isEmpty()) {
            Text(
                text = "No capability information yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.capabilities.forEach { CapabilityRow(it) }
        }
    }
}

/** One capability: its name, an available/unavailable chip, and the reason underneath when it is absent. */
@Composable
private fun CapabilityRow(capability: NetworkCapabilityView) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = capability.title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            StatusChip(
                text = if (capability.isAvailable) "Available" else "Unavailable",
                tone = if (capability.isAvailable) Tone.Good else Tone.Muted,
            )
        }
        if (!capability.isAvailable) {
            capability.reason?.let { reason ->
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/**
 * What this screen is measuring, in the app rather than only in the source.
 *
 * §24 draws a line between measuring a connection and reading a game, and jitter is exactly the kind of
 * figure a user might wonder about. The answer belongs where they can read it: GameCore times its own TCP
 * handshakes to a public host and reports how much they vary, which is the honest stand-in for a packet-loss
 * figure it cannot make — that would need ICMP, and so root, which this app does not use.
 */
@Composable
private fun SafetyCard(modifier: Modifier = Modifier) {
    PlainCard(modifier = modifier) {
        Text(text = "What this actually is", style = MaterialTheme.typography.titleSmall)
        SectionGap(6)
        Text(
            text = "A jitter measurement, not a packet-loss one. GameCore opens a short burst of TCP " +
                "connections to a well-known host and times each handshake; the spread between them is what " +
                "an unstable connection looks like in a game. A probe that never completes is counted and " +
                "reported as \"did not complete\", never as packet loss — that needs ICMP and therefore " +
                "root, which this app does not use. Throughput is device-wide, since Android exposes no " +
                "per-app byte counter to an ordinary app, and the link speed is the driver's estimate of " +
                "capacity rather than a measurement. Nothing here reads the game.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LoadingCard(modifier: Modifier = Modifier) {
    PlainCard(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(strokeWidth = 2.dp)
        }
    }
}

/**
 * How long the auto-refresh waits between ticks.
 *
 * Slow on purpose: a stability window is a small burst of real handshakes, and the Performance screen makes
 * the same measurement a button rather than a loop for exactly that reason. Here the loop refreshes the
 * cheap connection reading and lets the controller decide whether the last stability window is stale enough
 * to re-measure, so an idle screen is not sending five handshakes to somebody's resolver every few seconds.
 */
private const val REFRESH_INTERVAL_MILLIS = 15_000L
