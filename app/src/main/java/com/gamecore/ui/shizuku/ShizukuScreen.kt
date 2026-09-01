package com.gamecore.ui.shizuku

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.core.shizuku.SelfGrantablePermission
import com.gamecore.ui.Destination
import com.gamecore.ui.components.ActionRow
import com.gamecore.ui.components.NavRow
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.OnResume
import com.gamecore.ui.components.ReadoutRow
import com.gamecore.ui.components.RowDivider
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SecureWindow
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.readout
import com.gamecore.ui.components.startIntentSafely

/**
 * §16's setup screen and §29's capability report, on one page.
 *
 * They belong together because a user arrives here with one of two questions — "why isn't this working"
 * or "what would Shizuku get me" — and both are answered by the same set of facts. The page leads with
 * the shell check rather than the permission state: a permission survives a reboot and the service behind
 * it does not, so "connected" is a claim this screen only makes after a command has come back.
 *
 * [SecureWindow] is the §24A.12 requirement. What is on this page is a list of the elevated operations
 * this device permits, which is not something to leave in a screen recording.
 */
@Composable
fun ShizukuScreen(
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ShizukuViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    SecureWindow()
    // Shizuku's service is started in Shizuku's own app, and nothing tells GameCore when it happens.
    OnResume { viewModel.onResume() }

    val padded = Modifier.padding(horizontal = ScreenPadding)
    val launch: (Intent?) -> Unit = { intent ->
        if (intent != null && !context.startIntentSafely(intent)) viewModel.onIntentFailed()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Shizuku",
                subtitle = state.controlSummary,
                action = { TextButton(onClick = viewModel::recheck) { Text("Re-check") } },
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
            ConnectionCard(
                state = state,
                onAction = { launch(viewModel.onPrimaryAction()) },
                onVerify = viewModel::verify,
                modifier = padded,
            )
        }

        state.root.warning?.let { warning ->
            item {
                NoteBanner(
                    text = warning,
                    tone = Tone.Warning,
                    icon = Icons.Filled.Shield,
                    modifier = padded,
                )
            }
        }

        if (state.isConnected && state.grants.isNotEmpty()) {
            item {
                GrantsCard(
                    state = state,
                    onGrant = viewModel::grant,
                    onNavigate = onNavigate,
                    modifier = padded,
                )
            }
        }

        if (!state.isConnected) {
            item { SetupCard(onGuide = { launch(viewModel.openSetupGuide()) }, modifier = padded) }
        }

        item { ControlsCard(state = state, modifier = padded) }
        item { ReadingsCard(state = state, modifier = padded) }
        item { LimitsCard(modifier = padded) }

        item {
            SectionCard(title = "Everything else", icon = Icons.Filled.Info, modifier = padded) {
                Text(
                    text = OTHER_ACCESS,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                NavRow(
                    title = "Permissions",
                    onClick = { onNavigate(Destination.Permissions) },
                    description = "What each one is for, and the Settings page that grants it",
                )
            }
        }
    }
}

/**
 * The state, and the proof.
 *
 * The chip reports what Shizuku says; the row underneath reports what the shell did when GameCore asked
 * it something. They disagree after a reboot, and this card is arranged so the second one is the one the
 * eye lands on.
 */
@Composable
private fun ConnectionCard(
    state: ShizukuUiState,
    onAction: () -> Unit,
    onVerify: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Connection",
        subtitle = "What GameCore can prove, rather than what it was told",
        icon = Icons.Filled.Terminal,
        modifier = modifier,
        action = {
            StatusChip(
                text = state.shizuku.label,
                tone = when {
                    state.isProven -> Tone.Good
                    state.isConnected -> Tone.Warning
                    else -> Tone.Muted
                },
            )
        },
    ) {
        Text(
            text = state.shizuku.explanation,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RowDivider()
        ReadoutRow(
            state.verification.readout(
                label = "Shell check",
                tone = Tone.Good,
                detailOf = { PROOF_DETAIL },
            ) { it },
        )
        ConnectionActions(state = state, onAction = onAction, onVerify = onVerify)
    }
}

@Composable
private fun ConnectionActions(state: ShizukuUiState, onAction: () -> Unit, onVerify: () -> Unit) {
    val busy = state.isRequesting || state.isVerifying
    ActionRow {
        state.shizuku.actionLabel?.let { label ->
            TextButton(onClick = onAction, enabled = !busy) {
                Text(if (state.isRequesting) "Asking Shizuku…" else label)
            }
        }
        if (state.isConnected) {
            TextButton(onClick = onVerify, enabled = !busy) {
                Text(if (state.isProven) "Check again" else "Run the check")
            }
        }
    }
    if (state.isVerifying) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Running one command through the shell.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The two accesses the shell can hand over directly.
 *
 * Only two, and the card says so, because the rest of the permission list is either a runtime dialog or a
 * toggle only the user can reach and an app that offers to "grant everything" here would be pretending.
 */
@Composable
private fun GrantsCard(
    state: ShizukuUiState,
    onGrant: (SelfGrantablePermission) -> Unit,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Grant without Settings",
        subtitle = "Two app-ops an ADB-level shell can set for GameCore itself",
        icon = Icons.Filled.Bolt,
        modifier = modifier,
    ) {
        state.grants.forEachIndexed { index, offer ->
            if (index > 0) RowDivider()
            GrantRow(offer = offer, isBusy = state.isGranting, onGrant = { onGrant(offer.access) })
        }
        RowDivider()
        Text(
            text = GRANT_FOOTNOTE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ActionRow {
            TextButton(onClick = { onNavigate(Destination.Permissions) }) { Text("Open Permissions") }
        }
    }
}

@Composable
private fun GrantRow(offer: GrantOffer, isBusy: Boolean, onGrant: () -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = offer.access.userLabel, style = MaterialTheme.typography.titleSmall)
            Text(
                text = offer.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        StatusChip(
            text = if (offer.isGranted) "On" else "Off",
            tone = if (offer.isGranted) Tone.Good else Tone.Warning,
        )
    }
    if (!offer.isGranted) {
        ActionRow {
            TextButton(onClick = onGrant, enabled = !isBusy) {
                Text(if (isBusy) "Setting…" else "Grant with Shizuku")
            }
        }
    }
}

/**
 * §16's setup help, shown only while it is the thing the user needs.
 *
 * Four steps because that is genuinely how many there are, and the fourth is the one every other app
 * leaves out: the service stops at every reboot, and a user who does not know that concludes GameCore
 * broke overnight.
 */
@Composable
private fun SetupCard(onGuide: () -> Unit, modifier: Modifier = Modifier) {
    SectionCard(
        title = "Setting it up",
        subtitle = "Optional. GameCore works without it",
        icon = Icons.Filled.Build,
        modifier = modifier,
    ) {
        SETUP_STEPS.forEachIndexed { index, step ->
            if (index > 0) Spacer(modifier = Modifier.size(8.dp))
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = "${index + 1}.",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = step,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        ActionRow {
            TextButton(onClick = onGuide) { Text("Shizuku's own instructions") }
        }
    }
}

/**
 * The eight controls, each with what it does on this device.
 *
 * The count in the header is the honest version of "Shizuku: connected" — a connected shell on a device
 * whose display driver ignores what gets written through it still leaves this at seven.
 */
@Composable
private fun ControlsCard(state: ShizukuUiState, modifier: Modifier = Modifier) {
    SectionCard(
        title = "What can be changed",
        subtitle = state.controlSummary,
        icon = Icons.Filled.Tune,
        modifier = modifier,
    ) {
        state.controlRows.forEach { ReadoutRow(it) }
    }
}

@Composable
private fun ReadingsCard(state: ShizukuUiState, modifier: Modifier = Modifier) {
    SectionCard(
        title = "What can be measured",
        subtitle = "Reading is a different question from changing",
        icon = Icons.Filled.Insights,
        modifier = modifier,
    ) {
        state.readingRows.forEach { ReadoutRow(it) }
    }
}

/**
 * What GameCore will not do with the shell, listed before anyone has to ask.
 *
 * §24's prohibitions written where the elevated access is being offered, because this is the screen where
 * a user is deciding whether to give an app ADB-level authority and the list is the reason it is safe to.
 */
@Composable
private fun LimitsCard(modifier: Modifier = Modifier) {
    SectionCard(
        title = "What it is never used for",
        icon = Icons.Filled.Shield,
        modifier = modifier,
    ) {
        LIMITS.forEachIndexed { index, limit ->
            if (index > 0) RowDivider()
            Text(text = limit, style = MaterialTheme.typography.bodyMedium)
        }
        RowDivider()
        Text(
            text = SHELL_SCOPE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val PROOF_DETAIL =
    "The uid the shell reported. 2000 is Android's shell user — the same authority `adb shell` has, and " +
        "not root. Nothing else from the command is kept."

private const val GRANT_FOOTNOTE =
    "Everything else GameCore asks for is either a dialog Android must show you or a switch only you can " +
        "reach in Settings. Shizuku cannot grant those, and GameCore does not pretend it can."

private const val OTHER_ACCESS =
    "Most of what GameCore needs has nothing to do with Shizuku. The overlay permission, notifications " +
        "and the battery-optimisation exemption are all granted the ordinary way, and the app is built to " +
        "work with only those."

private val SETUP_STEPS = listOf(
    "Install Shizuku. It is a separate app, free and open-source, and it is the only thing GameCore " +
        "needs from outside Android itself.",
    "Start its service. On Android 11 and later that is done from Shizuku using wireless debugging, " +
        "with no computer involved. Before that it needs a computer running `adb` once.",
    "Come back here and request permission. Shizuku shows its own dialog; GameCore never sees your " +
        "answer except as granted or not.",
    "Expect to repeat step 2 after a reboot. Shizuku's service does not survive one, and nothing an app " +
        "can do will change that — GameCore will say it is disconnected rather than fail quietly.",
)

private val LIMITS = listOf(
    "No root. GameCore holds no code that runs `su`, and the shell it uses ends when Shizuku stops.",
    "No game files, memory or processes. Nothing is read from or written to another app's data, and " +
        "nothing is injected into a running game.",
    "No anti-cheat surface. Every change is a setting Android's own Settings app writes.",
    "No kernel writes. Governors, voltages and thermal limits are read where the device exposes them " +
        "and never set.",
    "No background killing. GameCore does not free memory by closing your other apps, because the " +
        "figure that produces is a number on a screen and not a faster game.",
)

private const val SHELL_SCOPE =
    "The commands GameCore can run are a fixed list compiled into the app: settings reads and writes, " +
        "app-ops for GameCore itself, `dumpsys` for display and frame timing, and `id`. There is no path " +
        "by which text you type becomes part of one."

