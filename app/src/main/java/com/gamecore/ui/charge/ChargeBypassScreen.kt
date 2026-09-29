package com.gamecore.ui.charge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.ui.components.KeyValueRow
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.OnResume
import com.gamecore.ui.components.PlainCard
import com.gamecore.ui.components.RowDivider
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SecureWindow
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.SectionGap
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.SwitchRow
import com.gamecore.ui.components.Tone

/**
 * The charge-bypass screen: what running the phone from the charger is, why it needs Shizuku, whether this
 * device can, and one switch to do it now.
 *
 * An explainer first and a control second, in that order on purpose. This is the feature most easily
 * mistaken for something it is not — a fast-charge trick, a charge-limit, a battery-health regime — so the
 * copy leads with the single honest claim (it keeps the heat of charging out of the cell during a plugged-in
 * session, nothing more) before it offers the switch. §24's line between a real change and a cosmetic one is
 * drawn here in words the user reads, because the change this one makes is real and worth being plain about.
 *
 * The switch is greyed the same way ShizukuScreen greys what a device will not honour: it comes alive only
 * once [com.gamecore.domain.charge.ChargeBypassController.support] has probed a writable node, and the copy
 * under a dead switch says which of the two reasons — no elevated shell, or no such node on this hardware —
 * applies, since one is fixable and one is not.
 *
 * [SecureWindow] for the same reason its sibling has it: this page names an elevated capability of the
 * device, which is not something to leave in a screen recording. It is a leaf — [onBack] and nothing else —
 * so the "needs Shizuku" case is stated rather than linked; setting Shizuku up has its own screen elsewhere.
 */
@Composable
fun ChargeBypassScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChargeBypassViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    SecureWindow()
    // The likeliest change while the user was away is Shizuku's service starting in the other app, which
    // nothing announces — so a resume re-probes rather than trusting the last verdict.
    OnResume { viewModel.onResume() }

    val padded = Modifier.padding(horizontal = ScreenPadding)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Charge bypass",
                subtitle = "Run the phone from the charger",
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

        item { ExplainerCard(modifier = padded) }
        item {
            ControlCard(
                state = state,
                onToggle = viewModel::toggle,
                modifier = padded,
            )
        }
        item { RiskCard(state = state, modifier = padded) }
        item {
            NoteBanner(
                text = PROFILE_NOTE,
                tone = Tone.Muted,
                icon = Icons.Filled.Info,
                modifier = padded,
            )
        }
    }
}

/**
 * The one honest claim, made before the switch is offered.
 *
 * A charger delivers more than a resting phone draws, and the surplus warms the cell; told to stop
 * charging, the charger still powers the phone while the cell is left alone. That is the whole of it, and
 * the paragraph is careful to say what it is *not* so the switch below is not read as a promise it never
 * made.
 */
@Composable
private fun ExplainerCard(modifier: Modifier = Modifier) {
    SectionCard(title = "What this does", icon = Icons.Filled.Bolt, modifier = modifier) {
        Text(
            text = WHAT_IT_DOES,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SectionGap(8)
        Text(
            text = WHAT_IT_IS_NOT,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The manual switch, and the state that decides whether it is alive.
 *
 * The chip in the header is the probe's verdict; the switch is dead until that verdict is a confirmed
 * reading of a writable node. When it is not, the reason sits under the switch in the device's own words —
 * a shell it can install, or a node this hardware simply does not have — so a greyed control is never a
 * mystery.
 */
@Composable
private fun ControlCard(
    state: ChargeBypassUiState,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Manual override",
        subtitle = "Switch it on for right now",
        icon = Icons.Filled.Bolt,
        modifier = modifier,
        action = { StatusChip(text = state.statusLabel(), tone = state.statusTone()) },
    ) {
        SwitchRow(
            title = "Run from the charger now",
            checked = state.isBypassed,
            onCheckedChange = onToggle,
            description = "Asks the battery to stop charging while you play, and gives charging back when " +
                "you switch this off.",
            enabled = state.canToggle,
        )
        if (state.isBusy) {
            SectionGap(4)
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Asking the shell, then reading the node back.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (state.isSupported) {
            RowDivider()
            KeyValueRow(label = "Control node", value = state.node?.label ?: "—", tone = Tone.Good)
        }
        if (state.isProbing) {
            SectionGap(6)
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Checking this device for a writable charge-control node.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            state.unavailableReason?.let { reason ->
                SectionGap(8)
                NoteBanner(
                    text = reason,
                    tone = if (state.needsShizuku) Tone.Warning else Tone.Muted,
                    icon = Icons.Filled.Info,
                )
            }
        }
    }
}

/**
 * What to keep in mind, listed before it bites.
 *
 * Three things the switch's plain label cannot carry: that the battery will not charge while it is on, that
 * it does nothing at all off the charger, and that it cools the cell and not the processor. The last one
 * points at GameCore's own thermal warning honestly — whether it is on or off — rather than implying this
 * feature watches the heat for you.
 */
@Composable
private fun RiskCard(state: ChargeBypassUiState, modifier: Modifier = Modifier) {
    PlainCard(modifier = modifier) {
        Text(text = "What to keep in mind", style = MaterialTheme.typography.titleSmall)
        SectionGap(8)
        Text(text = RISK_CHARGING, style = MaterialTheme.typography.bodyMedium)
        RowDivider()
        Text(text = RISK_PLUGGED_IN, style = MaterialTheme.typography.bodyMedium)
        RowDivider()
        Text(
            text = if (state.thermalWarningsOn) RISK_HEAT_WARNINGS_ON else RISK_HEAT_WARNINGS_OFF,
            style = MaterialTheme.typography.bodyMedium,
        )
        RowDivider()
        Text(text = RISK_SHIZUKU, style = MaterialTheme.typography.bodyMedium)
    }
}

/** "Available" / "Checking…" / the reason it is not, for the chip in the control card's header. */
private fun ChargeBypassUiState.statusLabel(): String = when {
    isSupported -> "Available"
    isProbing -> "Checking…"
    needsShizuku -> "Needs Shizuku"
    nodeAbsent -> "Not on this device"
    else -> "Unavailable"
}

private fun ChargeBypassUiState.statusTone(): Tone = when {
    isSupported -> Tone.Good
    isProbing -> Tone.Muted
    needsShizuku -> Tone.Warning
    else -> Tone.Muted
}

private const val WHAT_IT_DOES =
    "A charger delivers more current than a phone at rest uses, and the extra goes into the battery — which " +
        "is where the heat and the wear of a long charging-while-gaming session come from. On the devices " +
        "that expose the control, GameCore can ask the battery to stop charging: the charger still powers " +
        "the phone, so it runs on wall power while the cell is left alone."

private const val WHAT_IT_IS_NOT =
    "That is the whole of it. It does not make the device faster, does not charge it more quickly, does not " +
        "cap the charge level, and is not a battery-health mode — it is one runtime setting that, on the " +
        "hardware that has it, keeps a hot game from also heating the cell."

private const val RISK_CHARGING =
    "The battery does not charge while this is on. GameCore turns charging back on when you switch this off " +
        "or a game closes, and records the previous state first so a session it does not get to finish is " +
        "still put back — but left on by hand, it will hold charging off."

private const val RISK_PLUGGED_IN =
    "It only means anything while plugged in. With no charger connected there is nothing to bypass — the " +
        "phone runs on the battery either way — so this does nothing until you plug in."

private const val RISK_HEAT_WARNINGS_ON =
    "It keeps charging heat out of the cell; it does not cool the processor, and a hot game is still a hot " +
        "game. GameCore's thermal warnings are on, so it will still tell you if the device gets hot."

private const val RISK_HEAT_WARNINGS_OFF =
    "It keeps charging heat out of the cell; it does not cool the processor, and a hot game is still a hot " +
        "game. GameCore's thermal warnings are off — turn them on in Settings to be told if the device gets " +
        "hot while you play."

private const val RISK_SHIZUKU =
    "Android gives an app no way to tell the battery to stop charging, so this needs the elevated shell — on " +
        "this device, Shizuku. Without it the switch stays greyed."

private const val PROFILE_NOTE =
    "To have this apply on its own when a game starts, turn it on in that game's profile. This switch is the " +
        "manual override for the here and now."

