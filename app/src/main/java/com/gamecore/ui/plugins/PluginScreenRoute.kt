package com.gamecore.ui.plugins

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.ui.components.EmptyState
import com.gamecore.ui.plugins.render.PluginNodeTree
import com.gamecore.ui.theme.Spacing

/**
 * A plugin's declarative screen, drawn on a real navigation route under the GLOBAL theme.
 *
 * This is the one plugin surface that is a full navigation destination rather than a widget inside a
 * host screen, so it owns a [Scaffold] and a [TopAppBar] of its own: the screen's title and icon in
 * the bar, a back arrow, and the plugin's node tree below it in a host scroll container. It is drawn
 * under GameCore's active theme on purpose and is **not** wrapped in the plugin's own `houseTheme` —
 * only the two plugin *safety* screens (the manager and the detail/consent screen) are re-themed, so
 * a plugin cannot restyle the chrome the user relies on to turn it off while standing on its content.
 *
 * Everything shown here is read-only by construction ([PluginNodeTree] attaches no gesture), and the
 * `stat` and live-chart `series` nodes resolve through a scope-gated resolver rebuilt each live frame
 * (see [PluginScreenViewModel.resolver]): a figure appears only when its data scope was granted, and
 * draws absent otherwise. The route re-derives its state from the registry every frame, so revoking the
 * grant or the kill switch replaces the content with an honest "unavailable" panel rather than leaving
 * it drawn.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginScreenRoute(
    onBack: () -> Unit,
    viewModel: PluginScreenViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val available = state as? PluginScreenUiState.Available

    // Collected so the monitor keeps sampling while the screen is open, and so the resolver is rebuilt
    // on each live frame — a fresh resolver instance is what makes PluginNodeTree re-read the latest
    // stat and series values rather than freeze at the first frame. The scope prefilter inside the
    // resolver is re-evaluated on each rebuild too, so a grant revoked mid-view stops surfacing the
    // figure on the next frame.
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val resolver = remember(snapshot, history) { viewModel.resolver() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val icon = available?.screen?.icon?.let { pluginScreenIcon(it) }
                    if (icon != null) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier
                                .padding(end = Spacing.sm)
                                .size(22.dp),
                        )
                    }
                    Text(
                        text = available?.screen?.title ?: "Plugin screen",
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        when (val current = state) {
            is PluginScreenUiState.Loading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            is PluginScreenUiState.Available -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(Spacing.lg),
            ) {
                PluginNodeTree(
                    node = current.screen.root,
                    stats = resolver::stats,
                    series = resolver::series,
                )
            }

            is PluginScreenUiState.Unavailable -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    icon = unavailableIcon(current.reason),
                    title = "Screen unavailable",
                    message = current.message,
                )
            }
        }
    }
}

/** The marker glyph for an unavailable state: a hard block for the gated reasons, a warning else. */
private fun unavailableIcon(reason: PluginScreenResolution.Reason): ImageVector = when (reason) {
    PluginScreenResolution.Reason.KILL_SWITCH,
    PluginScreenResolution.Reason.DISABLED,
    PluginScreenResolution.Reason.NOT_GRANTED,
    -> Icons.Filled.Block

    PluginScreenResolution.Reason.REMOVED,
    PluginScreenResolution.Reason.INCOMPATIBLE,
    PluginScreenResolution.Reason.NOT_FOUND,
    -> Icons.Filled.Warning
}

/**
 * The host glyph for a screen or screen-entry [name], or null when the plugin named nothing drawable.
 *
 * A pure, `android`-free-at-the-call mirror of the renderer's private `BUILTIN_ICONS` map and of
 * [com.gamecore.core.plugin.ui.BuiltinIcons.NAMES] — the same twenty names in the same order — so the
 * TopAppBar here and the entry row on the plugin detail screen draw a plugin's icon from one list. It
 * is `internal` for exactly that reuse. Trimmed and lower-cased before the match, as the codec stores
 * every icon id; an unrecognised, blank, or null name is null, which both callers draw as no icon.
 */
internal fun pluginScreenIcon(name: String?): ImageVector? = when (name?.trim()?.lowercase()) {
    "speed" -> Icons.Filled.Speed
    "memory" -> Icons.Filled.Memory
    "thermostat" -> Icons.Filled.Thermostat
    "battery" -> Icons.Filled.BatteryFull
    "bolt" -> Icons.Filled.Bolt
    "timer" -> Icons.Filled.Timer
    "timeline" -> Icons.Filled.Timeline
    "data" -> Icons.Filled.DataUsage
    "insights" -> Icons.Filled.Insights
    "sensors" -> Icons.Filled.Sensors
    "storage" -> Icons.Filled.Storage
    "shield" -> Icons.Filled.Shield
    "extension" -> Icons.Filled.Extension
    "info" -> Icons.Filled.Info
    "warning" -> Icons.Filled.Warning
    "block" -> Icons.Filled.Block
    "check" -> Icons.Filled.CheckCircle
    "dot" -> Icons.Filled.FiberManualRecord
    "game" -> Icons.Filled.SportsEsports
    "settings" -> Icons.Filled.Settings
    else -> null
}

