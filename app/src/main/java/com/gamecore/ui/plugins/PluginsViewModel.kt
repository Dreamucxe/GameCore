package com.gamecore.ui.plugins

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.common.TextSanitizer
import com.gamecore.core.plugin.PluginOrigin
import com.gamecore.core.plugin.PluginRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The plugin manager's brain: list what is installed, flip switches, remove things, and hold the one
 * global off switch.
 *
 * It is constructed with a [PluginRegistry] and nothing else, which is the point. That interface can
 * enumerate plugins, toggle one, remove one and clear the theme; it cannot hand out an elevated shell, a
 * writable grant store, or a package's bytes. So the whole of what this screen is able to do to a plugin
 * is the method list on that interface, and a reviewer checking "can the manager screen escalate" reads
 * one file rather than a concrete class's entire surface.
 *
 * Four decisions worth stating:
 *
 *  - **Registry state and transient state are separate flows.** [fromRegistry] is rebuilt from scratch on
 *    every registry emission; [transient] holds the banner text and the pending-removal dialog. Merging
 *    them into one `MutableStateFlow` would mean every registry emission read the current value and wrote
 *    a copy — and a plugin that suspends itself, or a `refresh()` landing a few milliseconds after the
 *    user long-pressed a row, would take the open confirmation dialog down with it. A dialog the user is
 *    reading must not be at the mercy of a background re-read.
 *  - **Nothing is reported that was not observed (§24).** [setEnabled], [clearSuspension] and
 *    [resetToDefaultTheme] do not write an optimistic row: the switch moves when the registry emits the
 *    plugin with its new state, so a `setEnabled` the store refused leaves the switch where it was rather
 *    than showing the user a value that is not stored anywhere. [confirmRemove] is the same shape — it
 *    reports what `remove` returned, not what it asked for.
 *  - **The combine is one coroutine.** Four flows, one [combine], one collector. Four separate collectors
 *    writing into the same state would recompose the screen up to four times for four facts that arrive
 *    in the same instant on load, and would briefly publish a list of rows alongside the *previous*
 *    active theme — long enough for the "supplies the current theme" badge to sit on the wrong row.
 *  - **No plugin text is logged.** Nothing here writes a manifest string, a grant or an install detail to
 *    the log; the only place plugin-supplied text goes is a `Text` on the screen, via [rowFor].
 */
@HiltViewModel
class PluginsViewModel @Inject constructor(
    private val registry: PluginRegistry,
) : ViewModel() {

    /**
     * The parts of the screen that are this ViewModel's own rather than the registry's.
     *
     * A nested private class instead of two fields so that one `update` call changes them together, and
     * so [state] has exactly one other input to merge.
     */
    private data class Transient(
        val message: String? = null,
        val pendingRemoval: PluginRow? = null,
    )

    private val fromRegistry = MutableStateFlow(PluginsUiState())

    private val transient = MutableStateFlow(Transient())

    /**
     * The user's chosen order for the list.
     *
     * Screen state, not registry state: sorting changes nothing about a plugin, so it is held here and
     * applied to the rows on the way out. Like the session history's order control it starts at the
     * default each time the screen is opened rather than being stored — the choice is about this
     * reading of the list, not a preference the app has to remember between runs.
     */
    private val sort = MutableStateFlow(PluginSort.NAME)

    val state: StateFlow<PluginsUiState> = combine(fromRegistry, transient, sort) { observed, own, order ->
        observed.copy(
            rows = sortedRows(observed.rows, order),
            sort = order,
            message = own.message,
            pendingRemoval = own.pendingRemoval,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PluginsUiState())

    init {
        viewModelScope.launch {
            combine(
                registry.installed,
                registry.activeTheme,
                registry.killSwitchOn,
                registry.isLoaded,
            ) { installed, theme, killSwitch, isLoaded ->
                PluginsUiState(
                    isLoaded = isLoaded,
                    rows = installed.map { rowFor(it, theme?.pluginId) },
                    killSwitchOn = killSwitch,
                    activeThemeName = theme?.let { TextSanitizer.sanitizeName(it.pluginName) }
                        ?.takeIf { it.isNotBlank() },
                )
            }.collect { fromRegistry.value = it }
        }
        refresh()
    }

    /**
     * Re-reads the plugin directory.
     *
     * Worth offering as a button rather than running on every resume: it is disk I/O over every installed
     * package, and the one thing that genuinely changes behind this screen's back is a file the user put
     * there with another app — which is a deliberate act they can follow with a deliberate tap.
     */
    fun refresh() {
        viewModelScope.launch { registry.refresh() }
    }

    /**
     * Records the user's choice for one plugin.
     *
     * No message and no optimistic row: the switch is bound to the registry's copy, so it moves when the
     * store has it and stays put when the store refused. That is the §24 reason the obvious
     * `rows.map { if (it.id == id) it.copy(enabled = enabled) else it }` is not here.
     */
    fun setEnabled(pluginId: String, enabled: Boolean) {
        viewModelScope.launch { registry.setEnabled(pluginId, enabled) }
    }

    /** Puts the removal confirmation up, or explains why there is nothing to confirm. */
    fun askRemove(row: PluginRow) {
        if (!row.canBeRemoved) {
            // A bundled plugin's bytes are inside the APK, so a "remove" dialog would be a question with
            // no honest yes. §32: say so rather than offering a button that cannot act.
            transient.update { it.copy(message = CANNOT_REMOVE_BUNDLED) }
            return
        }
        transient.update { it.copy(pendingRemoval = row, message = null) }
    }

    /**
     * Deletes the plugin the dialog named.
     *
     * The dialog is taken down first, because the delete is now under way and leaving the confirm button
     * on screen offers it a second time. The *message* waits for what `remove` returned.
     */
    fun confirmRemove() {
        val target = transient.value.pendingRemoval ?: return
        transient.update { it.copy(pendingRemoval = null) }
        viewModelScope.launch {
            val removed = registry.remove(target.id)
            val note = if (removed) {
                "${target.name} is gone, and so is everything it had stored."
            } else {
                REMOVE_FAILED
            }
            transient.update { it.copy(message = note) }
        }
    }

    fun cancelRemove() {
        transient.update { it.copy(pendingRemoval = null) }
    }

    /**
     * The global off switch.
     *
     * Both messages are spelled out because this is the one control on the screen whose effect is not
     * visible in the rows: every per-plugin switch stays exactly where the user left it while the kill
     * switch is on, and a user who does not know that reads the unchanged rows as the switch having done
     * nothing.
     */
    fun setKillSwitch(on: Boolean) {
        viewModelScope.launch {
            registry.setKillSwitch(on)
            transient.update { it.copy(message = if (on) KILL_SWITCH_ON else KILL_SWITCH_OFF) }
        }
    }

    /**
     * Returns the app to its own theme.
     *
     * The one operation on this screen that can be reported flatly without hedging: [PluginRegistry]
     * defines it as clearing a selection rather than writing anything that could fail, and it does not
     * need the offending plugin to still parse. That is exactly why the escape hatch is this operation
     * and not `setActiveTheme(null)`.
     */
    fun resetToDefaultTheme() {
        viewModelScope.launch {
            registry.resetToDefaultTheme()
            transient.update { it.copy(message = THEME_RESET) }
        }
    }

    /**
     * Lets a plugin the supervisor stopped be tried again.
     *
     * No message: the suspension badge disappearing from the row when the registry re-emits is the
     * confirmation, and a banner saying "cleared" over a row still showing the badge would be the lie.
     */
    fun clearSuspension(pluginId: String) {
        viewModelScope.launch { registry.clearSuspension(pluginId) }
    }

    fun dismissMessage() {
        transient.update { it.copy(message = null) }
    }

    /**
     * Reorders the list.
     *
     * Only the presentation changes: no plugin is touched, no registry call is made, and a switch
     * flipped while the list was in another order stays flipped. The rows the screen draws come back
     * through [sortedRows], so the order is applied in exactly one place.
     */
    fun setSort(order: PluginSort) {
        sort.value = order
    }

    /**
     * Validates and stores a package the user picked.
     *
     * [PluginOrigin.SIDELOADED] is passed explicitly rather than left to the default, because "bundled"
     * is a claim about where bytes came from and only the app's own start-up loader is in a position to
     * make it. A file the user chose is sideloaded whatever its manifest says about itself — and since
     * nothing in the system grants a bundled plugin anything extra, the distinction is here for the
     * sentence the row prints, not for a privilege.
     *
     * The bytes are already capped by the caller; the registry caps them again.
     */
    fun installFromBytes(bytes: ByteArray) {
        viewModelScope.launch {
            val outcome = registry.install(bytes, PluginOrigin.SIDELOADED)
            transient.update { it.copy(message = messageFor(outcome)) }
        }
    }

    private companion object {

        const val KILL_SWITCH_ON =
            "Plugins are off. Nothing any plugin asked for runs while this is on — no theme, no Pill " +
                "module, no overlay, no automation — whatever each plugin's own switch says, because the " +
                "permission gate refuses every call before it looks at the plugin. This is the one " +
                "toggle guaranteed to stop plugin involvement without having to work out which plugin " +
                "to blame first."

        const val KILL_SWITCH_OFF =
            "Plugins are allowed again. Each one now does what its own switch says, which is where they " +
                "were before — turning this off grants nothing on its own."

        const val THEME_RESET =
            "GameCore's own theme is back. The plugin that supplied the previous one is untouched and " +
                "still installed; it is simply no longer the one in force."

        const val CANNOT_REMOVE_BUNDLED =
            "That plugin ships inside GameCore, so there is no file to delete. Turning it off stops it " +
                "completely — it is the same permission gate either way."

        const val REMOVE_FAILED =
            "That plugin could not be removed. Its files are still on the device, so it is still listed " +
                "— nothing was half-deleted."
    }
}
