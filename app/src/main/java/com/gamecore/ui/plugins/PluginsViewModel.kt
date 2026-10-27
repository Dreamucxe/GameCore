package com.gamecore.ui.plugins

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.common.TextSanitizer
import com.gamecore.core.model.AppSettings
import com.gamecore.core.model.PluginSectionFont
import com.gamecore.core.plugin.InstalledPlugin
import com.gamecore.core.plugin.InstallOutcome
import com.gamecore.core.plugin.PluginOrigin
import com.gamecore.core.plugin.PluginPackageLoader
import com.gamecore.core.plugin.PluginPreviewResult
import com.gamecore.core.plugin.PluginPreviewState
import com.gamecore.core.plugin.PluginRegistry
import com.gamecore.core.plugin.RollbackOutcome
import com.gamecore.data.preferences.SecurePreferenceStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.inject.Inject

/**
 * The plugin manager's brain: list what is installed, flip switches, remove things, and hold the one
 * global off switch.
 *
 * It is constructed with a [PluginRegistry] (and the application context, only to read the file the
 * user picked), which is the point. That interface can enumerate plugins, toggle one, preview and
 * install a package, roll one back, quarantine one, remove one and clear the theme; it cannot hand out
 * an elevated shell, a writable grant store, or the raw bytes of an installed package. So the whole of
 * what this screen is able to do to a plugin is the method list on that interface, and a reviewer
 * checking "can the manager screen escalate" reads one file rather than a concrete class's entire
 * surface.
 *
 * Five decisions worth stating:
 *
 *  - **Registry state and transient state are separate flows.** [fromRegistry] is rebuilt from scratch on
 *    every registry emission; [transient] holds the banner text, the preview, and the pending
 *    removal/rollback dialogs. Merging them into one `MutableStateFlow` would mean every registry
 *    emission read the current value and wrote a copy — and a plugin that suspends itself, or a
 *    `refresh()` landing a few milliseconds after the user long-pressed a row, would take the open
 *    confirmation dialog down with it. A dialog the user is reading must not be at the mercy of a
 *    background re-read.
 *  - **Nothing is reported that was not observed (§24).** [setEnabled] and [resetToDefaultTheme] do not
 *    write an optimistic row: the switch moves when the registry emits the plugin with its new state,
 *    so a `setEnabled` the store refused leaves the switch where it was rather than showing the user a
 *    value that is not stored anywhere. [confirmRemove] and [confirmRollback] report what the registry
 *    returned, not what they asked for.
 *  - **A picked package is previewed, never installed.** [previewInstall] reads and validates the file
 *    into a [PluginPreviewState] and stops. [confirmInstall] installs exactly those bytes, so the user's
 *    yes is to the package they were shown. The bytes are held in this ViewModel, not in the UI state,
 *    so a recomposition cannot lose them and a preview cannot be confused with an install.
 *  - **The combine is one coroutine.** Five registry flows, one [combine], one collector. Separate
 *    collectors would recompose the screen up to five times for facts that arrive in the same instant
 *    on load, and would briefly publish rows beside the *previous* active theme — long enough for the
 *    "supplies the current theme" badge to sit on the wrong row.
 *  - **No plugin text is logged.** Nothing here writes a manifest string, a grant or an install detail to
 *    the log; the only place plugin-supplied text goes is a `Text` on the screen, via [rowFor].
 */
@HiltViewModel
class PluginsViewModel @Inject constructor(
    private val registry: PluginRegistry,
    private val preferences: SecurePreferenceStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    /**
     * The parts of the screen that are this ViewModel's own rather than the registry's.
     *
     * A nested private class instead of several fields so that one `update` call changes them together,
     * and so [state] has exactly one other input to merge.
     */
    private data class Transient(
        val message: String? = null,
        val preview: PluginPreviewState? = null,
        val previewing: Boolean = false,
        val previewError: String? = null,
        val pendingRemoval: PluginRow? = null,
        val pendingRollback: PluginRow? = null,
        val searchQuery: String = "",
        /**
         * The value under the font-size slider while the finger is down.
         *
         * Null means the stored setting is authoritative. The store is written once, on
         * `onValueChangeFinished`, so dragging does not perform an encrypted write per frame.
         */
        val pluginFontScaleDraft: Int? = null,
    )

    /**
     * The bytes the preview described.
     *
     * Kept here rather than in [PluginsUiState] because a byte array is not presentation state and
     * because `confirmInstall` must install exactly what the user approved. Cleared on confirm, cancel
     * and dismissal; it is never held after the operation.
     */
    private var previewBytes: ByteArray? = null

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

    val state: StateFlow<PluginsUiState> = combine(
        fromRegistry,
        transient,
        sort,
        preferences.settings,
    ) { observed, own, order, settings ->
        observed.copy(
            rows = sortedRows(observed.rows, order),
            sort = order,
            searchQuery = own.searchQuery,
            message = own.message,
            preview = own.preview,
            isPreviewing = own.previewing,
            previewError = own.previewError,
            pendingRemoval = own.pendingRemoval,
            pendingRollback = own.pendingRollback,
            pluginSectionFont = settings.pluginSectionFont,
            pluginSectionFontScalePercent = own.pluginFontScaleDraft
                ?: settings.pluginSectionFontScalePercent,
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
     * Records the search text.
     *
     * Only the length and line breaks are changed here. The field is a filter over strings that have
     * already been sanitised into [PluginRow]s, not a value that is stored or handed to a plugin, so
     * trimming it as the user types would delete the space between two words and make a two-term
     * search impossible to enter. A pasted newline becomes a space for the same reason: the field is
     * single-line, and a newline in the query would be invisible.
     */
    fun setSearchQuery(query: String) {
        val cleaned = query
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace('\t', ' ')
            .take(MAX_SEARCH_QUERY_LENGTH)
        transient.update { it.copy(searchQuery = cleaned) }
    }

    fun clearSearch() {
        transient.update { it.copy(searchQuery = "") }
    }

    /**
     * Changes the plugin section's face.
     *
     * The store normalises and persists; the new value reaches the screen through
     * [SecurePreferenceStore.settings], so this method never writes a local copy that could disagree
     * with what was saved. [PluginSectionFont.THEME] is a real choice, not a reset button, and is
     * stored like every other one.
     */
    fun setPluginSectionFont(font: PluginSectionFont) {
        preferences.updateSettings { it.copy(pluginSectionFont = font) }
    }

    /**
     * Moves the draft under the slider without writing the encrypted store on every frame.
     *
     * The draft is part of [Transient] so it survives registry emissions and dialog changes; the
     * screen reads it from [state] exactly like a stored value, which is what lets the thumb move
     * smoothly while the file stays untouched until [commitPluginSectionFontScale].
     */
    fun setPluginSectionFontScale(percent: Int) {
        val clamped = percent.coerceIn(
            AppSettings.MIN_PLUGIN_SECTION_FONT_SCALE,
            AppSettings.MAX_PLUGIN_SECTION_FONT_SCALE,
        )
        transient.update { it.copy(pluginFontScaleDraft = clamped) }
    }

    /** Writes the value the finger left behind and hands authority back to the store. */
    fun commitPluginSectionFontScale() {
        val draft = transient.value.pluginFontScaleDraft ?: return
        preferences.updateSettings { it.copy(pluginSectionFontScalePercent = draft) }
        transient.update { it.copy(pluginFontScaleDraft = null) }
    }

    // ------------------------------------------------------------------------------- previewing

    /**
     * Reads a picked document and turns it into a preview. **Nothing is installed here.**
     *
     * The read is capped while streaming, on [Dispatchers.IO], for the reason the old manager screen had:
     * a content provider is another app, and a stream that never ends must cost the cap rather than the
     * heap. The validated bytes are kept in [previewBytes] so [confirmInstall] installs the exact package
     * the user was shown, not whatever the URI happens to serve on a second read.
     */
    fun previewInstall(uri: Uri) {
        viewModelScope.launch {
            previewBytes = null
            transient.update {
                it.copy(preview = null, previewing = true, previewError = null, message = null)
            }
            when (val read = withContext(Dispatchers.IO) { readPicked(uri) }) {
                is PickedPackage.Read -> when (val result = registry.previewInstall(read.bytes)) {
                    is PluginPreviewResult.Ready -> {
                        previewBytes = read.bytes
                        transient.update {
                            it.copy(
                                previewing = false,
                                preview = result.preview,
                                previewError = null,
                            )
                        }
                    }

                    is PluginPreviewResult.Refused -> transient.update {
                        it.copy(
                            previewing = false,
                            preview = null,
                            previewError = messageFor(result),
                        )
                    }

                    is PluginPreviewResult.Unavailable -> transient.update {
                        it.copy(
                            previewing = false,
                            preview = null,
                            previewError = result.message,
                        )
                    }
                }

                PickedPackage.TooLarge -> transient.update {
                    it.copy(previewing = false, preview = null, previewError = TOO_LARGE_NOTE)
                }

                PickedPackage.Unreadable -> transient.update {
                    it.copy(previewing = false, preview = null, previewError = UNREADABLE_NOTE)
                }
            }
        }
    }

    /**
     * Installs the previewed package.
     *
     * The outcome is whatever [PluginRegistry.install] returned. A quarantine on the copy being replaced
     * is not cleared by an update, and the message says so rather than letting the user believe a new
     * version earned its way back.
     */
    fun confirmInstall() {
        val bytes = previewBytes ?: return
        val preview = transient.value.preview ?: return
        if (!preview.canInstall) return
        previewBytes = null
        transient.update {
            it.copy(preview = null, previewing = false, previewError = null, message = null)
        }
        viewModelScope.launch {
            val outcome = registry.install(bytes, PluginOrigin.SIDELOADED)
            val message = buildString {
                append(messageFor(outcome))
                if (outcome is InstallOutcome.Installed && preview.replaces?.quarantined == true) {
                    append(" It is still quarantined from before, so it stays stopped until you " +
                        "release it.")
                }
            }
            transient.update { it.copy(message = message) }
        }
    }

    /** Throws the preview away. The bytes are dropped with it; nothing was written. */
    fun cancelPreview() {
        previewBytes = null
        transient.update {
            it.copy(preview = null, previewing = false, previewError = null)
        }
    }

    // ------------------------------------------------------------------------------- rollback

    /** Asks before replacing the installed bytes with the stored previous version. */
    fun askRollback(row: PluginRow) {
        if (!row.canRollback) {
            transient.update { it.copy(message = NO_PREVIOUS_VERSION) }
            return
        }
        transient.update { it.copy(pendingRollback = row, message = null) }
    }

    /**
     * Performs the rollback and reports the registry's real outcome.
     *
     * No optimistic row and no optimistic message: [registry.rollback] goes back through the ordinary
     * install path, clears the grant and switches the plugin off, and the installed flow re-emits the
     * version that was actually read back.
     */
    fun confirmRollback() {
        val target = transient.value.pendingRollback ?: return
        transient.update { it.copy(pendingRollback = null) }
        viewModelScope.launch {
            val outcome = registry.rollback(target.id)
            transient.update { it.copy(message = messageFor(outcome)) }
        }
    }

    fun cancelRollback() {
        transient.update { it.copy(pendingRollback = null) }
    }

    // ----------------------------------------------------------------------------- quarantine

    /**
     * Persistently stops a plugin until the user releases it.
     *
     * The success sentence is written only after reading the registry back: if the store could not
     * persist the quarantine — a broken Keystore, a test double that does not implement it — the user is
     * told the stop did not take, rather than shown a badge the next launch would contradict.
     */
    fun quarantine(pluginId: String, name: String) {
        viewModelScope.launch {
            registry.quarantine(pluginId, QUARANTINE_USER_DETAIL)
            val stored = registry.installed.value.firstOrNull { it.id == pluginId }
            val label = TextSanitizer.sanitizeName(name).ifBlank { pluginId }
            val message = if (stored?.quarantined == true) {
                "$label is quarantined. It is listed here but will not load — not after a restart " +
                    "either. Release it when you want to try it again; it stays off until you turn it on."
            } else {
                QUARANTINE_FAILED
            }
            transient.update { it.copy(message = message) }
        }
    }

    /** Lifts the persistent stop. Nothing starts by itself; the plugin remains switched off. */
    fun releaseQuarantine(pluginId: String, name: String) {
        viewModelScope.launch {
            registry.releaseQuarantine(pluginId)
            val stored = registry.installed.value.firstOrNull { it.id == pluginId }
            val label = TextSanitizer.sanitizeName(name).ifBlank { pluginId }
            val message = if (stored != null && !stored.quarantined) {
                "$label is released. It is still off — turn it on when you are ready to try it again."
            } else {
                RELEASE_FAILED
            }
            transient.update { it.copy(message = message) }
        }
    }

    /**
     * Streams up to [PluginPackageLoader.MAX_PACKAGE_BYTES], returning null the moment it is exceeded.
     *
     * Same shape as the old manager helper: the cap is applied to bytes actually read, not to a size the
     * provider claims, and a failure is a typed outcome rather than an exception escaping the coroutine.
     */
    private fun readPicked(uri: Uri): PickedPackage = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val bytes = readCapped(input, PluginPackageLoader.MAX_PACKAGE_BYTES)
            if (bytes == null) PickedPackage.TooLarge else PickedPackage.Read(bytes)
        } ?: PickedPackage.Unreadable
    } catch (_: Throwable) {
        PickedPackage.Unreadable
    }

    private fun readCapped(input: InputStream, maxBytes: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(READ_CHUNK_BYTES)
        var total = 0
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            total += read
            if (total > maxBytes) return null
            out.write(chunk, 0, read)
        }
        return out.toByteArray()
    }

    private sealed interface PickedPackage {
        class Read(val bytes: ByteArray) : PickedPackage

        data object TooLarge : PickedPackage

        data object Unreadable : PickedPackage
    }

    private companion object {

        const val READ_CHUNK_BYTES = 8 * 1024

        /** Long enough for a package id or a phrase, short enough that the filter stays cheap. */
        const val MAX_SEARCH_QUERY_LENGTH = 80

        const val TOO_LARGE_NOTE =
            "That file is larger than a plugin package is allowed to be, so GameCore stopped reading " +
                "part way through instead of loading it. Nothing was installed."

        const val UNREADABLE_NOTE =
            "That file could not be read. The app it came from may have withdrawn access, or the file " +
                "may have been moved since you picked it. Nothing was installed."

        const val NO_PREVIOUS_VERSION =
            "There is no previous version stored for that plugin, so there is nothing to restore."

        const val QUARANTINE_USER_DETAIL = "Quarantined by you from the plugin manager."

        const val QUARANTINE_FAILED =
            "That plugin could not be quarantined. It is still installed and its current stop state is " +
                "unchanged."

        const val RELEASE_FAILED =
            "The quarantine could not be lifted. The plugin is still stopped, and nothing about it was " +
                "changed."

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
