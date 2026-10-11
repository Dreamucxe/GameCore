package com.gamecore.ui.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The navigation entry point for the Dashboard Composer: binds the ViewModel and feeds the stateless
 * [DashboardComposerScreen] its state and the actions that change it.
 *
 * Split from the screen for the reason the rest of the dashboard package keeps its decisions out of Compose:
 * everything with behaviour — the live registry/monitor projection, the pure [reconcile], the immediate
 * persistence of each edit — lives in [DashboardComposerViewModel] and the pure state file and is tested on
 * the JVM, while the screen is a function of state a Compose preview can drive with literals.
 *
 * Three flows are collected here rather than in the screen. [DashboardComposerViewModel.state] is the whole
 * screen. [DashboardComposerViewModel.snapshot] and [DashboardComposerViewModel.history] are the live sample
 * feed and the rolling window: collecting them is load-bearing twice over, as it keeps the sampler running
 * while the screen is open and recomposes the plugin widgets' live figures each tick rather than freezing
 * them at the first frame. The ViewModel is a defaulted parameter so the NavHost calls this as
 * `DashboardComposerRoute(onBack = ...)` while a test or preview passes its own.
 */
@Composable
fun DashboardComposerRoute(
    onBack: () -> Unit,
    viewModel: DashboardComposerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    DashboardComposerScreen(
        state = state,
        snapshot = snapshot,
        history = history,
        onBack = onBack,
        onSetEditing = viewModel::setEditing,
        onAddBuiltin = viewModel::addBuiltin,
        onAddPluginWidget = viewModel::addPluginWidget,
        onRemove = viewModel::remove,
        onSetVisible = viewModel::setVisible,
        onMoveUp = viewModel::moveUp,
        onMoveDown = viewModel::moveDown,
        stats = viewModel::stats,
        series = viewModel::series,
    )
}
