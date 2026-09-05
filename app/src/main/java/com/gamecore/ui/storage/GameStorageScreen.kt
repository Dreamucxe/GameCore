package com.gamecore.ui.storage

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.core.common.Formatters
import com.gamecore.core.common.Observed
import com.gamecore.core.common.RestrictionReason
import com.gamecore.core.common.shortUnavailabilityText
import com.gamecore.core.model.CacheClearReport
import com.gamecore.core.model.GameStorage
import com.gamecore.ui.Destination
import com.gamecore.ui.components.ActionRow
import com.gamecore.ui.components.ConfirmDialog
import com.gamecore.ui.components.EmptyState
import com.gamecore.ui.components.KeyValueRow
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.OnResume
import com.gamecore.ui.components.PlainCard
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.startIntentSafely

/**
 * How much space each game is holding in cache, and the one part of it GameCore will delete.
 *
 * The screen is built around a distinction the store's cache cleaners do not make. Three figures per
 * game, in this order: what the platform calls cache, how much of that is in shared storage — the only
 * part any command here can reach — and the app's own data, which is where saves and logins are and
 * which is on screen precisely so that the number next to "Clear" can never be confused with it.
 *
 * Everything the feature cannot do is written on the screen rather than left to be discovered. The
 * cache inside a game's private storage is unreachable by a shell running as the shell user, and
 * GameCore has no root path to anything, so every row also offers Android's own storage page — which
 * needs nothing granted and clears the part this cannot.
 */
@Composable
fun GameStorageScreen(
    onBack: () -> Unit,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GameStorageViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Coming back from Android's own storage page is the one case where the figures on screen are
    // known to be wrong, and it is also the case this screen sends the user into.
    OnResume { if (state.isLoaded) viewModel.refresh() }

    val open: (Intent?) -> Unit = { intent ->
        if (intent != null && !context.startIntentSafely(intent)) viewModel.onIntentFailed()
    }
    val setUpShizuku: () -> Unit = { onNavigate(Destination.Shizuku) }

    state.confirming?.let { row ->
        ConfirmDialog(
            title = "Clear ${row.label}'s cache?",
            message = CONFIRM_MESSAGE,
            confirmLabel = "Clear cache",
            onConfirm = { viewModel.clear(row) },
            onDismiss = viewModel::dismissConfirmation,
        )
    }

    val padded = Modifier.padding(horizontal = ScreenPadding)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            ScreenHeader(
                title = "Game storage",
                subtitle = subtitleFor(state),
                onBack = onBack,
                action = {
                    IconButton(onClick = viewModel::refresh, enabled = !state.isRefreshing) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Measure again",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        }

        item {
            PlainCard(modifier = padded) {
                Text(text = WHAT_THIS_CLEARS, style = MaterialTheme.typography.bodyMedium)
            }
        }

        state.message?.let { message ->
            item {
                NoteBanner(
                    text = message,
                    tone = Tone.Warning,
                    icon = Icons.Filled.Info,
                    modifier = padded,
                    action = {
                        TextButton(onClick = viewModel::dismissMessage) { Text("Dismiss") }
                    },
                )
            }
        }

        if (state.needsUsageAccess) {
            item {
                NoteBanner(
                    text = NEEDS_USAGE_ACCESS,
                    tone = Tone.Warning,
                    icon = Icons.Filled.Info,
                    modifier = padded,
                    action = {
                        TextButton(onClick = { open(viewModel.usageAccessIntent()) }) { Text("Grant") }
                    },
                )
            }
        }

        if (state.isLoaded && !state.canClear) {
            item {
                NoteBanner(
                    text = NEEDS_SHIZUKU,
                    tone = Tone.Muted,
                    icon = Icons.Filled.Info,
                    modifier = padded,
                    action = {
                        TextButton(onClick = setUpShizuku) { Text("Set up") }
                    },
                )
            }
        }

        if (state.isRefreshing && !state.isLoaded) {
            item { Measuring(modifier = padded) }
        }

        if (state.isEmpty) {
            item {
                EmptyState(
                    icon = Icons.Filled.CleaningServices,
                    title = "No games to measure",
                    message = "This lists apps that declare themselves games and apps you have made a " +
                        "GameCore profile for. Make a profile for a game and it will appear here.",
                )
            }
        }

        items(state.rows, key = { it.packageName }) { row ->
            StorageRow(
                row = row,
                isClearing = state.isClearing(row),
                report = state.reportFor(row),
                canClear = state.canClear,
                onClear = { viewModel.askToClear(row) },
                onOpenAppInfo = { open(viewModel.appStorageIntent(row)) },
                onSetUpShizuku = setUpShizuku,
                modifier = padded,
            )
        }
    }
}

/** Shown only for the first measurement, when there is nothing on screen yet to keep. */
@Composable
private fun Measuring(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = "Measuring each game's storage…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One game: what it is holding, and the two things that can be done about it.
 *
 * The three figures are always all three, in the same order, even when a device answers for only one of
 * them. A row that showed "Cache 1.2 GB" and hid the rest would read as an offer to delete 1.2 GB, and
 * the button underneath deletes the second figure.
 *
 * Both actions are on every row. "Clear cache" is GameCore's, needs Shizuku, and reaches shared storage;
 * Android's storage page needs nothing, is where the rest of the cache lives, and is also the only place
 * clearing an app's data is possible at all — which this app deliberately cannot do.
 */
@Composable
private fun StorageRow(
    row: GameStorage,
    isClearing: Boolean,
    report: CacheClearReport?,
    canClear: Boolean,
    onClear: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onSetUpShizuku: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlainCard(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.label,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = row.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Why this row is on the screen at all, in one word. A profile is the user having said this
            // is a game, which outranks the manifest, so it wins when a row is both.
            val why = when {
                row.hasProfile -> "Profile"
                row.isDeclaredGame -> "Game"
                else -> null
            }
            if (why != null) {
                Spacer(modifier = Modifier.width(8.dp))
                StatusChip(text = why, tone = if (row.hasProfile) Tone.Accent else Tone.Muted)
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        KeyValueRow(label = "Cache", value = readingText(row.cacheBytes))
        KeyValueRow(
            label = "In shared storage",
            value = readingText(row.clearableCacheBytes),
            tone = Tone.Accent,
        )
        KeyValueRow(
            label = "App data, never touched",
            value = readingText(row.untouchedBytes),
            tone = Tone.Muted,
        )

        if (report != null) {
            Spacer(modifier = Modifier.height(8.dp))
            // A clear that was never attempted because the shell is down is the one report that comes
            // with somewhere to go, so it carries the route rather than leaving the user to find it.
            val needsShizuku = report is CacheClearReport.NotAttempted && report.needsShizuku
            NoteBanner(
                text = report.message,
                tone = toneFor(report),
                action = if (needsShizuku) {
                    { TextButton(onClick = onSetUpShizuku) { Text("Set up") } }
                } else {
                    null
                },
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        ActionRow {
            // A measured cache under the threshold is the one case worth refusing: the delete would
            // work and would free nothing worth the confirmation dialog. An unmeasured cache is not
            // that case — the clear does not need the figure, so it stays offered.
            val nothingToGain = row.measuredCache != null && !row.isWorthClearing
            TextButton(onClick = onClear, enabled = canClear && !isClearing && !nothingToGain) {
                if (isClearing) {
                    CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(if (isClearing) "Clearing" else "Clear cache")
            }
            TextButton(onClick = onOpenAppInfo) { Text("Android's page") }
        }
    }
}

/**
 * One storage figure as a line of text, decided by what the reading is rather than by what it says.
 *
 * Branching on the reason rather than reusing [shortUnavailabilityText] for everything is what lets the
 * two restrictions the user can act on read as instructions — grant usage access, or nothing to do, this
 * device is too old — instead of as the same shrug. Anything else falls back to the generic sentence.
 */
private fun readingText(reading: Observed<Long>): String = when (reading) {
    is Observed.Value -> Formatters.bytes(reading.value)
    is Observed.Failed -> "Could not be read"
    is Observed.Restricted -> when (reading.reason) {
        RestrictionReason.PERMISSION_REQUIRED -> "Needs usage access"
        RestrictionReason.NOT_SUPPORTED_ON_API_LEVEL -> "Not shown on this Android version"
        RestrictionReason.NOT_PRESENT_ON_DEVICE -> "Not available"
        else -> reading.shortUnavailabilityText() ?: "Not available"
    }
}

/**
 * The colour of the sentence under a row after a clear.
 *
 * A clear that freed nothing is deliberately [Tone.Muted] and not [Tone.Good]: it succeeded, and saying
 * so in green next to an unchanged figure is the kind of reassurance this screen exists to avoid.
 */
private fun toneFor(report: CacheClearReport): Tone = when (report) {
    is CacheClearReport.Cleared -> if ((report.freed ?: 0L) > 0L) Tone.Good else Tone.Muted
    is CacheClearReport.Failed -> Tone.Danger
    is CacheClearReport.NotAttempted -> Tone.Warning
}

/**
 * The header's second line: how much was found, and across how many games.
 *
 * The total is what was measured and nothing more, so on a device that answered for some rows and not
 * others it is a floor. The count says how many games are listed, not how many were measured, which is
 * why the two numbers are allowed to disagree.
 */
private fun subtitleFor(state: GameStorageUiState): String = when {
    !state.isLoaded -> "Measuring…"
    state.rows.isEmpty() -> "Nothing to measure"
    else -> {
        val games = Formatters.count(state.rows.size, "game")
        state.measuredTotal
            ?.let { "${Formatters.bytes(it)} of cache across $games" }
            ?: "$games, none of them measurable"
    }
}

private const val WHAT_THIS_CLEARS =
    "Clearing removes a game's cache from shared storage — the downloaded assets and temporary files " +
        "it can rebuild. Saves, logins and settings live in the app's own data, which nothing here " +
        "touches, and the figure for it is shown on every row so you can see it stay put.\n\n" +
        "Close the game first. A running game rewrites its cache as it plays, so clearing underneath " +
        "it frees less than it looks like it should, and the game may stutter while it rebuilds."

private const val CONFIRM_MESSAGE =
    "This deletes the cache GameCore can reach in shared storage. The game will rebuild what it needs, " +
        "which may mean a slower first load or re-downloading assets. Saves and logins are in the app's " +
        "own data and are not touched."

private const val NEEDS_USAGE_ACCESS =
    "Sizes are missing because Android only tells an app how much storage another app is using when " +
        "usage access is granted. Clearing still works without it; the result just cannot be measured."

private const val NEEDS_SHIZUKU =
    "Clearing another app's cache needs Shizuku, which is not running. The sizes below are still real, " +
        "and Android's own page for each game clears more than GameCore can."

