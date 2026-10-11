package com.gamecore.ui.plugins

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The navigation entry point for the automation-history screen: binds the ViewModel and feeds the
 * stateless [PluginAutomationHistoryScreen] its state and the actions that change it.
 *
 * Split from the screen for the same reason the rest of this package keeps its reducers out of Compose:
 * everything with behaviour — the gated read, the denied-vs-empty decision, the in-memory filtering —
 * lives in the ViewModel and the pure state file and is tested on the JVM, while the screen is a
 * function of state the Compose preview can drive with literals. The ViewModel is a defaulted parameter
 * so the NavHost calls this as `PluginAutomationHistoryRoute(onBack = ...)` and a test or preview can
 * pass its own.
 */
@Composable
fun PluginAutomationHistoryRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PluginAutomationHistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PluginAutomationHistoryScreen(
        state = state,
        onBack = onBack,
        onSearchChange = viewModel::onSearchChange,
        onOutcomeFilterChange = viewModel::onOutcomeFilterChange,
        onToggleSort = viewModel::onToggleSort,
        onConfirmClear = viewModel::onConfirmClear,
        modifier = modifier,
    )
}
