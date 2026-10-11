package com.gamecore.ui.plugins

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.model.MetricHistory
import com.gamecore.core.model.PerformanceSnapshot
import com.gamecore.core.plugin.InstalledPlugin
import com.gamecore.core.plugin.PluginRegistry
import com.gamecore.core.plugin.ui.PluginScreen
import com.gamecore.domain.monitoring.PerformanceMonitor
import com.gamecore.ui.Destination
import com.gamecore.ui.plugins.render.PluginScreenStatResolver
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * What a plugin screen route should draw right now: the spinner, the screen, or an honest reason it
 * cannot be shown. One of exactly three shapes, so the route never has to invent an intermediate.
 */
sealed interface PluginScreenUiState {

    /** The first registry read, or this package's sections, has not come back yet. */
    data object Loading : PluginScreenUiState

    /** The screen is live and permitted; here it is, already validated by the codec. */
    data class Available(val screen: PluginScreen) : PluginScreenUiState

    /** The screen may not be drawn; [message] is the sentence the route shows the user. */
    data class Unavailable(val reason: PluginScreenResolution.Reason) : PluginScreenUiState {
        val message: String get() = reason.message
    }

    companion object {
        /** Lowers a [PluginScreenResolution] to the state the route renders. */
        fun from(resolution: PluginScreenResolution): PluginScreenUiState = when (resolution) {
            is PluginScreenResolution.Available -> Available(resolution.screen)
            is PluginScreenResolution.Unavailable -> Unavailable(resolution.reason)
        }
    }
}

/**
 * The state holder behind a single plugin-screen route: it resolves whether [Destination.ARG_SCREEN_ID]
 * of [Destination.ARG_PLUGIN_ID] may be drawn, and keeps resolving it live as the registry changes.
 *
 * The two ids arrive as nav arguments — from outside — so a blank or stale pair resolves to an honest
 * "unavailable" state rather than throwing, exactly as [PluginDetailViewModel] treats its own id. A
 * route reached from a dead back-stack entry shows a reason, never a crash.
 *
 * Why the gating lives in [resolvePluginScreen] and not in the parse: the screens list comes from
 * [PluginRegistry.sectionsOf], which parses whatever a package *declares* regardless of whether it is
 * enabled or granted (the detail screen reads the same sections to preview a disabled plugin's theme).
 * The access decision — kill switch off, plugin active, "Change the look" in the *stored grant* — is
 * applied here, on every emission of [PluginRegistry.installed] and [PluginRegistry.killSwitchOn], so
 * revoking the grant or flipping the kill switch closes an open screen on the next frame rather than
 * leaving the plugin on a surface the user believes they shut. That is §24's honesty rule: the screen
 * reports the store, it does not hold the last good copy.
 *
 * Sections are read once per package digest and cached ([sectionsFor]), the same discipline
 * [PluginDetailViewModel] uses, because the installed flow also fires for an enable or a suspension and
 * re-parsing a package on each of those would be waste. Until this package's sections are in, the route
 * holds [PluginScreenUiState.Loading] rather than flash "no longer part of the plugin" at a screen that
 * is a frame away from loading — but an early-gate refusal (disabled, kill-switched, ungranted,
 * incompatible, removed) needs no sections and is surfaced at once.
 *
 * The screen's data path is a double lock, the same one the overlay and dashboard surfaces carry. The
 * whole screen is already behind the plugin's `CHANGE_UI` grant (see [resolvePluginScreen]); on top of
 * that, every `stat` and live-chart `series` node is resolved per id through [PluginScreenStatResolver],
 * which surfaces a reading only when the plugin was granted the [com.gamecore.core.plugin.DataScope]
 * that stat sits under. An ungranted scope resolves to null, which the renderer draws as **absent** —
 * never as a real figure. The resolver reads [registry] for the granted scopes rather than touching a
 * grant directly, so the registry stays the one place the permission gate lives.
 */
@HiltViewModel
class PluginScreenViewModel @Inject constructor(
    private val registry: PluginRegistry,
    private val monitor: PerformanceMonitor,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** The plugin this route is about, trimmed; blank resolves to "removed", never a throw. */
    private val pluginId: String =
        savedStateHandle.get<String>(Destination.ARG_PLUGIN_ID)?.trim().orEmpty()

    /** The screen within that plugin; matched trimmed and lower-cased, as the codec stores ids. */
    private val screenId: String =
        savedStateHandle.get<String>(Destination.ARG_SCREEN_ID)?.trim().orEmpty()

    private val _state = MutableStateFlow<PluginScreenUiState>(PluginScreenUiState.Loading)
    val state: StateFlow<PluginScreenUiState> = _state.asStateFlow()

    /**
     * The live sample feed, re-exposed so the route (which holds only this ViewModel) can subscribe to
     * it and rebuild the resolver each tick.
     *
     * Collecting this in composition is load-bearing the same way it is for the dashboard widgets:
     * [PerformanceMonitor.snapshots] samples only while something collects it, so the subscription is
     * what keeps the loop running while a screen is open, and the read is what recomposes the read-only
     * tree each tick so a figure moves rather than freezing at the first frame.
     */
    val snapshot: StateFlow<PerformanceSnapshot?> = monitor.snapshots

    /** The rolling window a live chart series is drawn from, re-exposed for the same reason as [snapshot]. */
    val history: StateFlow<MetricHistory> = monitor.history

    /**
     * A fresh, scope-gated resolver over the current frame.
     *
     * Rebuilt by the route whenever [snapshot] or [history] changes so the trees re-read live values.
     * The granted scopes come from [PluginRegistry.grantedScopesFor] — the registry-level backstop, so
     * this surface carries the same per-id scope prefilter the overlay and dashboard already have, and
     * never reads a grant directly. A stat whose [com.gamecore.core.plugin.DataScope] was not granted
     * resolves to null and draws absent.
     */
    fun resolver(): PluginScreenStatResolver = PluginScreenStatResolver(
        grantedScopes = registry.grantedScopesFor(pluginId),
        snapshot = monitor.snapshots.value,
        history = monitor.history.value,
    )

    /** The package digest whose sections have already been read; null until the first read lands. */
    private var sectionsFor: String? = null

    /** This package's parsed screens, or null while the read for [sectionsFor] is still in flight. */
    private var loadedScreens: List<PluginScreen>? = null

    init {
        observe()
    }

    /**
     * One collector over the two things that can revoke a screen plus the first-read flag. All three
     * belong to one frame — which plugin (with its stored grant), whether plugin access is switched
     * off, and whether the first disk read has finished — so they are combined rather than collected
     * apart, where they would land as intermediate states that were never true together.
     */
    private fun observe() {
        viewModelScope.launch {
            combine(
                registry.installed,
                registry.killSwitchOn,
                registry.isLoaded,
            ) { plugins, killSwitchOn, loaded ->
                Triple(plugins.firstOrNull { it.id == pluginId }, killSwitchOn, loaded)
            }.collect { (plugin, killSwitchOn, loaded) ->
                onRegistryChanged(plugin, killSwitchOn, loaded)
            }
        }
    }

    /** Rebuilds the state from one registry emission, loading this package's sections once. */
    private fun onRegistryChanged(plugin: InstalledPlugin?, killSwitchOn: Boolean, loaded: Boolean) {
        if (plugin == null) {
            sectionsFor = null
            loadedScreens = null
        } else if (sectionsFor != plugin.sha256) {
            sectionsFor = plugin.sha256
            loadedScreens = null
            val digest = plugin.sha256
            // Off the collector: parsing a package is disk work, and holding the collect lambda in it
            // would stall every later emission behind one read. Re-reads the live registry values when
            // it lands so a change that arrived while parsing is not painted over with stale ones.
            viewModelScope.launch {
                val parsed = registry.sectionsOf(pluginId)?.screens.orEmpty()
                if (sectionsFor == digest) {
                    loadedScreens = parsed
                    render(
                        registry.installed.value.firstOrNull { it.id == pluginId },
                        registry.killSwitchOn.value,
                        registry.isLoaded.value,
                    )
                }
            }
        }
        render(plugin, killSwitchOn, loaded)
    }

    /**
     * Lowers one emission to a [PluginScreenUiState].
     *
     * Before the first disk read, a null plugin means "not read yet", so the spinner holds. After it,
     * a null plugin is genuinely removed and the resolver says so. When the plugin is present but its
     * sections are not read yet, an early-gate refusal (anything but [NOT_FOUND]) is shown at once,
     * while a would-be [NOT_FOUND] holds the spinner — the screen may be a frame from loading, and
     * flashing "no longer part of the plugin" at it would be a lie.
     */
    private fun render(plugin: InstalledPlugin?, killSwitchOn: Boolean, loaded: Boolean) {
        if (!loaded) {
            _state.value = PluginScreenUiState.Loading
            return
        }
        val screens = loadedScreens
        if (plugin != null && screens == null) {
            val provisional = resolvePluginScreen(plugin, killSwitchOn, emptyList(), screenId)
            _state.value = if (
                provisional is PluginScreenResolution.Unavailable &&
                provisional.reason != PluginScreenResolution.Reason.NOT_FOUND
            ) {
                PluginScreenUiState.from(provisional)
            } else {
                PluginScreenUiState.Loading
            }
            return
        }
        _state.value = PluginScreenUiState.from(
            resolvePluginScreen(plugin, killSwitchOn, screens.orEmpty(), screenId),
        )
    }
}
