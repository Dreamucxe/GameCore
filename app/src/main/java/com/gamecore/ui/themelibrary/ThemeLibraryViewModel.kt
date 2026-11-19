package com.gamecore.ui.themelibrary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.plugin.InstalledPlugin
import com.gamecore.core.plugin.PluginPermission
import com.gamecore.core.plugin.PluginRegistry
import com.gamecore.core.plugin.PluginSection
import com.gamecore.core.plugin.theme.PluginTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The Theme Library's state holder.
 *
 * ## It is a view over the plugin system, not a second one
 *
 * Every theme here is a bundled plugin's `theme.json`, parsed by the same codec and applied through the
 * same `setActiveTheme` the manager's theme switch uses. Nothing is copied into a library-owned store,
 * and there is no second notion of "current theme" that could disagree with the one the app is wearing.
 *
 * ## Loading 254 themes without loading 254 plugins
 *
 * `sectionsOf` parses five sections; the library needs one. `themeOf` is the narrow door added for this
 * screen, and the cache here is keyed by the plugin's digest so a refresh caused by an unrelated switch
 * flip does not re-read every asset. The map is filled once per installed build, on IO, before the grid
 * is published.
 *
 * ## Applying is explicit about what it changes
 *
 * A bundled theme may come from a plugin that is switched off or has never been granted Change the
 * look. Applying it from here therefore has a side effect beyond the repaint, and the screen says so in
 * a confirmation before it happens. An existing grant is preserved — this adds one permission and
 * never replaces the map — and nothing else about the plugin is touched.
 */
@HiltViewModel
class ThemeLibraryViewModel @Inject constructor(
    private val registry: PluginRegistry,
) : ViewModel() {

    private val local = MutableStateFlow(ThemeLibraryUiState())
    val state: StateFlow<ThemeLibraryUiState> = local.asStateFlow()

    /** Parsed themes, keyed by plugin id and the digest they came from. */
    private val themes = HashMap<String, CachedTheme>()
    private var allEntries: List<ThemeLibraryEntry> = emptyList()
    private var installedSnapshot: List<InstalledPlugin> = emptyList()
    private var activePluginId: String? = null
    private var killSwitch = false

    init {
        viewModelScope.launch {
            combine(
                registry.installed,
                registry.activeTheme,
                registry.killSwitchOn,
                registry.isLoaded,
            ) { installed, active, kill, loaded -> Snapshot(installed, active?.pluginId, kill, loaded) }
                .collectLatest { snapshot ->
                    installedSnapshot = snapshot.installed
                    activePluginId = snapshot.activePluginId
                    killSwitch = snapshot.killSwitch

                    val missing = snapshot.installed.filter {
                        PluginSection.THEME in it.manifest.declares &&
                            themes[it.id]?.sha256 != it.sha256
                    }
                    if (missing.isNotEmpty()) {
                        local.value = local.value.copy(isLoading = true)
                        withContext(Dispatchers.IO) {
                            for (plugin in missing) {
                                themes[plugin.id] = CachedTheme(plugin.sha256, registry.themeOf(plugin.id))
                            }
                        }
                        // Drop themes for plugins that are no longer installed, so a library session
                        // does not hold the bytes of something the user removed.
                        val installedIds = snapshot.installed.mapTo(HashSet()) { it.id }
                        themes.keys.retainAll(installedIds)
                    }
                    publish(isLoaded = snapshot.loaded)
                }
        }
    }

    fun setQuery(query: String) {
        local.value = local.value.copy(query = query)
        publish(isLoaded = true)
    }

    fun clearQuery() = setQuery("")

    fun setFilter(filter: ThemeLibraryFilter) {
        local.value = local.value.copy(filter = filter)
        publish(isLoaded = true)
    }

    /**
     * Applies a theme, or holds the decision when applying would change the plugin's own state.
     *
     * [pluginId] rather than the entry, so a card that was recomposed after the registry emitted cannot
     * apply a stale copy of the plugin's grant. The entry is looked up again at the moment of the tap.
     */
    fun apply(pluginId: String) {
        val entry = allEntries.firstOrNull { it.pluginId == pluginId } ?: return
        if (killSwitch) {
            say("The plugin kill switch is on. Turn it off in Plugins to apply a theme.", warning = true)
            return
        }
        val blocked = entry.blockedReason
        if (blocked != null) {
            say(blocked, warning = true)
            return
        }
        if (entry.requiresConfirmation) {
            local.value = local.value.copy(
                pendingApply = ThemeApplyConfirmation(
                    pluginId = entry.pluginId,
                    themeName = entry.theme.name,
                    pluginName = entry.pluginName,
                    needsEnable = entry.needsEnable,
                    needsGrant = entry.needsGrant,
                ),
            )
            return
        }
        performApply(entry)
    }

    /** The user answered the confirmation with a yes. */
    fun confirmApply() {
        val pending = local.value.pendingApply ?: return
        local.value = local.value.copy(pendingApply = null)
        val entry = allEntries.firstOrNull { it.pluginId == pending.pluginId } ?: return
        performApply(entry)
    }

    fun dismissApply() {
        local.value = local.value.copy(pendingApply = null)
    }

    /**
     * Writes the grant and enabled flag the confirmation promised, then selects the theme.
     *
     * The order matters: `setActiveTheme` validates against the plugin's live state, so granting and
     * enabling first is what makes the selection stick instead of being cleared as impossible. The
     * grant is a copy with one key added, never a fresh grant — a plugin with Storage or overlay
     * access keeps every entry the user already approved.
     */
    private fun performApply(entry: ThemeLibraryEntry) {
        viewModelScope.launch {
            val plugin = registry.installed.value.firstOrNull { it.id == entry.pluginId }
            if (plugin == null) {
                say("That plugin is no longer installed.", warning = true)
                return@launch
            }
            if (!plugin.grant.has(PluginPermission.CHANGE_UI)) {
                registry.grant(
                    entry.pluginId,
                    plugin.grant.copy(
                        granted = plugin.grant.granted + (PluginPermission.CHANGE_UI to emptySet()),
                    ),
                )
            }
            if (!plugin.enabled) registry.setEnabled(entry.pluginId, true)
            registry.setActiveTheme(entry.pluginId)

            val applied = registry.activeTheme.value?.pluginId == entry.pluginId
            say(
                if (applied) {
                    "Applied \"${entry.theme.name}\"."
                } else {
                    "Saved \"${entry.theme.name}\" as your theme, but another appearance rule is " +
                        "in force right now. Check the appearance schedule."
                },
                warning = !applied,
            )
        }
    }

    /** The escape hatch: GameCore's own colours, needing nothing from any plugin. */
    fun reset() {
        viewModelScope.launch {
            registry.resetToDefaultTheme()
            say("Back to GameCore's own theme.")
        }
    }

    fun dismissMessage() {
        local.value = local.value.copy(message = null, messageIsWarning = false)
    }

    private fun say(text: String, warning: Boolean = false) {
        local.value = local.value.copy(message = text, messageIsWarning = warning)
    }

    private fun publish(isLoaded: Boolean) {
        allEntries = ThemeLibraryLogic.entriesFor(installedSnapshot, themes.mapValues { it.value.theme }, activePluginId)
        val visible = ThemeLibraryLogic.visible(allEntries, local.value.query, local.value.filter)
        local.value = local.value.copy(
            isLoading = !isLoaded || (installedSnapshot.any {
                PluginSection.THEME in it.manifest.declares && it.id !in themes
            }),
            entries = visible,
            totalCount = allEntries.size,
            activeThemeName = allEntries.firstOrNull { it.isActive }?.theme?.name,
            killSwitchOn = killSwitch,
        )
    }

    private data class Snapshot(
        val installed: List<InstalledPlugin>,
        val activePluginId: String?,
        val killSwitch: Boolean,
        val loaded: Boolean,
    )

    private data class CachedTheme(val sha256: String, val theme: PluginTheme?)
}
