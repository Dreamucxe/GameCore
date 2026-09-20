package com.gamecore.aimlab.ui.weapon

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.aimlab.AimLabRepository
import com.gamecore.aimlab.engine.Weapon
import com.gamecore.core.common.TextSanitizer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Owns the weapon list and the draft being edited.
 *
 * Two sources meet in [state]. The stored weapons come from [AimLabRepository.weapons] and are never
 * copied into local state — the list the screen draws is whatever the database currently holds, so a
 * save or a delete appears because the flow re-emitted and not because this class remembered to update
 * a cached copy. Everything the *user* is doing — which weapon is open, the half-finished draft, the
 * pending delete confirmation — lives in [local], because none of it is stored and none of it survives
 * the screen.
 *
 * The draft is a [WeaponDraft] rather than a [Weapon] because the editor's controls move in whole
 * numbers; see that class for the conversion. The conversion back happens once, in [save] and
 * [duplicate], and `normalised()` is applied at that moment — §13's requirement, and the reason a value
 * the editor's ranges allow but the engine does not can never reach the database.
 *
 * Built-in weapons are read-only here. [select] records [WeaponEditorState.editingBuiltIn] from the
 * stored row, [save] and [delete] refuse to act on one, and the screen disables their controls; the way
 * to change a built-in is [duplicate], which writes a new user-owned weapon and opens it.
 */
@HiltViewModel
class WeaponEditorViewModel @Inject constructor(
    private val repository: AimLabRepository,
) : ViewModel() {

    private val local = MutableStateFlow(WeaponEditorState())

    val state: StateFlow<WeaponEditorState> = combine(
        local,
        repository.weapons,
    ) { editing, weapons ->
        editing.copy(loading = false, weapons = weapons)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MILLIS),
        // loading = true until the weapons flow has answered, so the "no weapons yet" state cannot
        // flash up in front of a list that is about to arrive.
        initialValue = WeaponEditorState(loading = true),
    )

    /**
     * Opens a stored weapon in the editor.
     *
     * Re-read through [AimLabRepository.weapon] rather than taken from the observed list, so the draft
     * is built from the row as it stands right now — the list is a snapshot that may be a frame old, and
     * the draft is what a save will overwrite the row with.
     */
    fun select(id: Long) {
        viewModelScope.launch {
            val weapon = try {
                repository.weapon(id)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                local.update { it.copy(error = "Could not open this weapon.") }
                null
            }
            // Null means the row is gone — deleted under us, or never there. The observed list will
            // already be showing that, so there is nothing to open and nothing to report.
            if (weapon == null) return@launch
            local.update {
                it.copy(
                    draft = WeaponDraft.from(weapon),
                    editingId = weapon.id,
                    editingBuiltIn = weapon.isBuiltIn,
                    confirmingDelete = false,
                    error = null,
                )
            }
        }
    }

    /** Starts a blank draft, which begins at the model's own defaults rather than at zero. */
    fun newWeapon() {
        local.update {
            it.copy(
                draft = WeaponDraft(),
                editingId = null,
                editingBuiltIn = false,
                confirmingDelete = false,
                error = null,
            )
        }
    }

    /**
     * Replaces the draft as the user moves a control.
     *
     * One entry point rather than eleven setters: the screen sends `draft.copy(field = value)`, which
     * keeps each control's call site to one readable line and means a new parameter needs no new method
     * here. Ignored while a built-in is open — that state's controls are already disabled, and this is
     * the second lock so a stray recomposition cannot edit one.
     */
    fun updateDraft(draft: WeaponDraft) {
        local.update { if (it.editingBuiltIn) it else it.copy(draft = draft, error = null) }
    }

    /**
     * Writes the draft, creating the weapon if it is new and amending it if it is not.
     *
     * The name is sanitised before it is stored — it is user-typed text bound for a list row and a mode
     * screen's header — and the whole weapon goes through `normalised()`, so every field lands inside
     * the engine's own clamps whatever the editor allowed. The returned id is adopted as [editingId],
     * which turns a new weapon into a saved one without a second round trip: the editor stays open on
     * what the user just made, and a further save amends it rather than creating a duplicate.
     */
    fun save() {
        val snapshot = local.value
        if (snapshot.editingBuiltIn || snapshot.saving) return
        val name = TextSanitizer.sanitizeName(snapshot.draft.name, WeaponDraft.MAX_NAME_LENGTH)
        if (name.isBlank()) return

        local.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                val weapon = snapshot.draft
                    .copy(name = name)
                    .toWeapon(id = snapshot.editingId ?: NEW_ID, isBuiltIn = false)
                    .normalised()
                val id = repository.saveWeapon(weapon)
                local.update {
                    it.copy(
                        // The stored weapon, not the draft: normalisation may have moved a value, and
                        // the editor must show what was actually written rather than what was asked for.
                        draft = WeaponDraft.from(weapon.copy(id = id)),
                        editingId = id,
                        editingBuiltIn = false,
                        saving = false,
                        error = null,
                    )
                }
            } catch (cancellation: CancellationException) {
                // The screen went away mid-write. Not a failure, and not ours to report.
                throw cancellation
            } catch (failure: Throwable) {
                local.update { it.copy(saving = false, error = "Could not save this weapon.") }
            }
        }
    }

    /**
     * Copies the open weapon into a new, editable one and opens the copy.
     *
     * This is the only way to change a built-in, so the copy is always user-owned (`isBuiltIn = false`)
     * however it started. The name is suffixed and re-sanitised, which also re-applies the length cap —
     * copying "a forty-character name" must not produce a forty-five-character one.
     */
    fun duplicate() {
        val snapshot = local.value
        if (snapshot.editingId == null || snapshot.saving) return

        local.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                val base = TextSanitizer.sanitizeName(snapshot.draft.name, WeaponDraft.MAX_NAME_LENGTH)
                val name = TextSanitizer.sanitizeName(
                    if (base.isBlank()) "Weapon $COPY_SUFFIX" else "$base $COPY_SUFFIX",
                    WeaponDraft.MAX_NAME_LENGTH,
                )
                val copy = snapshot.draft
                    .copy(name = name)
                    .toWeapon(id = NEW_ID, isBuiltIn = false)
                    .normalised()
                val id = repository.saveWeapon(copy)
                local.update {
                    it.copy(
                        draft = WeaponDraft.from(copy.copy(id = id)),
                        editingId = id,
                        // The copy is the user's, so the editor unlocks even though a built-in was open.
                        editingBuiltIn = false,
                        confirmingDelete = false,
                        saving = false,
                        error = null,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                local.update { it.copy(saving = false, error = "Could not duplicate this weapon.") }
            }
        }
    }

    /** Opens the delete confirmation. Nothing is removed until [confirmDelete]. */
    fun requestDelete() {
        local.update { if (it.canDelete) it.copy(confirmingDelete = true) else it }
    }

    /** Closes the confirmation without deleting. */
    fun dismissDelete() {
        local.update { it.copy(confirmingDelete = false) }
    }

    /**
     * Deletes the open weapon and empties the editor.
     *
     * There is no undo behind this — the weapon is the user's own work and nothing keeps a copy — which
     * is why it is only reachable through the confirmation. Afterwards the editor resets to a blank
     * draft rather than holding the deleted weapon's numbers, which would invite a save that silently
     * recreated it.
     */
    fun confirmDelete() {
        val snapshot = local.value
        val id = snapshot.editingId
        if (id == null || snapshot.editingBuiltIn) {
            local.update { it.copy(confirmingDelete = false) }
            return
        }
        local.update { it.copy(confirmingDelete = false, saving = true, error = null) }
        viewModelScope.launch {
            try {
                repository.deleteWeapon(id)
                local.update {
                    it.copy(
                        draft = WeaponDraft(),
                        editingId = null,
                        editingBuiltIn = false,
                        saving = false,
                        error = null,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                local.update { it.copy(saving = false, error = "Could not delete this weapon.") }
            }
        }
    }

    private companion object {
        /** The id a not-yet-stored weapon carries; the repository allocates the real one on insert. */
        const val NEW_ID = 0L

        /** Appended to a duplicated weapon's name so the list never shows two identical rows. */
        const val COPY_SUFFIX = "copy"

        /**
         * How long [state] stays alive after the screen stops collecting — long enough to survive a
         * rotation and a hop into a mode screen and back, so a half-finished draft is not lost to a
         * configuration change. The same grace the rest of the app's screens use.
         */
        const val SUBSCRIPTION_GRACE_MILLIS = 5_000L
    }
}
