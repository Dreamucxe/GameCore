package com.gamecore.ui.macros

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.common.TextSanitizer
import com.gamecore.core.overlay.Macro
import com.gamecore.core.overlay.MacroCodec
import com.gamecore.core.overlay.MacroLibrary
import com.gamecore.core.overlay.OverlayAction
import com.gamecore.data.preferences.SecurePreferenceStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The §14 macro editor's state and edits, the sibling of `QuickAppsViewModel`.
 *
 * The stored macro JSON is the single source of truth and this class never holds a second copy: [state] is
 * [SecurePreferenceStore.overlay] decoded, and every edit is one [updateMacros] write the flow redraws from.
 * That is what makes the editor and the live quick sheet impossible to disagree — both are the same
 * [MacroCodec.decode] of the same string.
 *
 * Each edit decodes the macros *inside* the [SecurePreferenceStore.updateOverlay] transform, from the config
 * the store hands it, rather than from a value read a moment earlier. Two quick taps therefore compose
 * instead of racing, and the [MacroLibrary.normalise] that runs before every write is the wall a caller
 * cannot get around: the cap, the de-duplication and the drop of an unrunnable macro are applied on the way
 * to storage, so what comes back is a valid list rather than what was asked for.
 *
 * "Add" mints a *fully valid* default — a name and one action — because [MacroCodec]/[MacroLibrary.normalise]
 * drop a nameless or empty macro on the next read, so a blank draft could never survive to be edited. The
 * same reasoning bars a blank rename here (it would delete the macro being renamed) and is why the screen
 * keeps a macro's last action un-removable. Only [SecurePreferenceStore] is injected: IO lives in the store,
 * and a write is `prefs.edit().apply()`, so there is no dispatcher to hand in.
 */
@HiltViewModel
class MacroEditorViewModel @Inject constructor(
    private val preferences: SecurePreferenceStore,
) : ViewModel() {

    val state: StateFlow<MacroEditorUiState> =
        preferences.overlay
            .map { config -> MacroEditorUiState(isLoaded = true, macros = MacroCodec.decode(config.macrosJson)) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MILLIS), MacroEditorUiState())

    init {
        // Pay the first encrypted read off the main thread, so opening the editor from the overlay card is
        // not the moment the keystore is first touched. Harmless if the flow was already warm.
        viewModelScope.launch { preferences.preload() }
    }

    // ---------------------------------------------------------------------------------- the list

    /**
     * Adds a macro, already valid so it survives the next decode: a friendly default name and one macroable
     * action ([OverlayAction.PILL], the stats pill — every device has it). [MacroLibrary.add] is still the
     * gate, so a full list is a no-op the screen has already disabled Add for.
     */
    fun addMacro() = updateMacros { current ->
        MacroLibrary.add(
            current,
            Macro(id = MacroLibrary.nextId(current), name = defaultName(current), actions = listOf(DEFAULT_ACTION)),
        )
    }

    fun removeMacro(id: Long) = updateMacros { MacroLibrary.remove(it, id) }

    fun moveMacro(id: Long, delta: Int) = updateMacros { MacroLibrary.move(it, id, delta) }

    /**
     * Renames a macro, refusing a name that sanitises to nothing.
     *
     * A blank name makes a macro unrunnable, and the next decode drops an unrunnable macro — so accepting a
     * blank rename would delete the very macro the user is editing. The screen disables Save in that case;
     * this is the belt to its braces, keeping the macro alive with the name it had.
     */
    fun rename(id: Long, name: String) {
        if (TextSanitizer.sanitizeName(name).isBlank()) return
        updateMacros { MacroLibrary.rename(it, id, name) }
    }

    // ---------------------------------------------------------------------------- one macro's actions

    fun addAction(id: Long, action: OverlayAction) = updateMacro(id) { MacroLibrary.addAction(it, action) }

    fun removeAction(id: Long, action: OverlayAction) = updateMacro(id) { MacroLibrary.removeAction(it, action) }

    fun moveAction(id: Long, action: OverlayAction, delta: Int) =
        updateMacro(id) { MacroLibrary.moveAction(it, action, delta) }

    // ------------------------------------------------------------------------------------------ plumbing

    private fun updateMacro(id: Long, transform: (Macro) -> Macro) =
        updateMacros { current -> current.map { if (it.id == id) transform(it) else it } }

    /**
     * The one write path. Decodes the stored macros from the config the store hands the transform (never a
     * value read earlier), applies [transform], normalises, and re-encodes. Decoding inside the transform is
     * what lets two quick edits compose rather than race, and [MacroLibrary.normalise] is applied on every
     * write so the stored JSON is always a valid, capped, de-duplicated list.
     */
    private fun updateMacros(transform: (List<Macro>) -> List<Macro>) {
        preferences.updateOverlay { config ->
            val next = MacroLibrary.normalise(transform(MacroCodec.decode(config.macrosJson)))
            config.copy(macrosJson = MacroCodec.encode(next))
        }
    }

    /**
     * A friendly default name: "Macro 1", "Macro 2", … skipping any number already taken, so a new macro
     * never opens sharing a name with one on screen. Names need not be unique for the model; this is only a
     * kinder starting point than a blank field.
     */
    private fun defaultName(existing: List<Macro>): String {
        val taken = existing.map { it.name }.toSet()
        var n = existing.size + 1
        while ("Macro $n" in taken) n++
        return "Macro $n"
    }

    private companion object {
        /** Keeps the decode flow warm across a config-change recreation, matching the app's other editors. */
        const val SUBSCRIPTION_TIMEOUT_MILLIS = 5_000L

        /** The default macro's one action — the stats pill, present on every device, so the default is runnable. */
        val DEFAULT_ACTION = OverlayAction.PILL
    }
}
