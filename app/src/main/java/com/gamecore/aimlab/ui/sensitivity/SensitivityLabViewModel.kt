package com.gamecore.aimlab.ui.sensitivity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.aimlab.AimLabRepository
import com.gamecore.aimlab.engine.SensitivityPreset
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
 * Owns the sensitivity profiles and the draft being tuned.
 *
 * Two sources meet in [state], the same split the weapon editor uses. The stored profiles come from
 * [AimLabRepository.sensitivities] and are never copied into local state — the list the screen draws is
 * whatever the database currently holds, so a save or a delete appears because the flow re-emitted and not
 * because this class remembered to update a cached copy. Everything the *user* is doing — which profile is
 * active, the half-finished draft, the pending delete, which branch of the maths the preview is exercising
 * — lives in [local], because none of it is stored and none of it survives the screen.
 *
 * **Nothing about the live preview passes through here.** The pad's pointer stream runs at digitiser rate
 * and its output is read by one readout row and one canvas; routing it through a `StateFlow` would make
 * every finger movement recompose the whole screen, which is exactly what §21 rules out. The screen keeps
 * that in local Compose state and calls the engine directly. What this class supplies the preview is the
 * profile to run — [SensitivityLabState.previewProfile] — and the two flags that pick which pair of
 * sensitivity figures apply.
 *
 * The draft is a [SensitivityDraft] rather than a [com.gamecore.aimlab.engine.SensitivityProfile] because
 * the controls move in whole numbers; see that class for the conversion. The conversion back happens twice
 * — in [save] and [duplicate] — and `normalised()` is applied at that moment, so a value this screen's
 * sliders allowed but the engine does not can never reach the database.
 */
@HiltViewModel
class SensitivityLabViewModel @Inject constructor(
    private val repository: AimLabRepository,
) : ViewModel() {

    private val local = MutableStateFlow(SensitivityLabState())

    val state: StateFlow<SensitivityLabState> = combine(
        local,
        repository.sensitivities,
    ) { editing, profiles ->
        editing.copy(loading = false, profiles = profiles)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MILLIS),
        // loading = true until the profiles flow has answered, so "no profiles yet" cannot flash up in
        // front of a list that is about to arrive.
        initialValue = SensitivityLabState(loading = true),
    )

    // ------------------------------------------------------------------------------ opening

    /**
     * Makes a stored profile the active one: it is loaded into the editor, and the preview pad and the
     * response curve start running its numbers on the next frame.
     *
     * Re-read through [AimLabRepository.sensitivity] rather than taken from the observed list, so the draft
     * is built from the row as it stands right now — the list is a snapshot that may be a frame old, and
     * the draft is what a save will overwrite the row with.
     */
    fun setActive(id: Long) {
        viewModelScope.launch {
            val profile = try {
                repository.sensitivity(id)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                local.update { it.copy(error = "Could not open this profile.") }
                null
            }
            // Null means the row is gone — deleted under us, or never there. The observed list is already
            // showing that, so there is nothing to open and nothing to report.
            if (profile == null) return@launch
            local.update {
                it.copy(
                    draft = SensitivityDraft.from(profile),
                    activeId = profile.id,
                    confirmingDelete = false,
                    error = null,
                )
            }
        }
    }

    /** Starts a blank draft, which begins at the model's own defaults rather than at zero. */
    fun newProfile() {
        local.update {
            it.copy(
                draft = SensitivityDraft(),
                activeId = null,
                confirmingDelete = false,
                error = null,
            )
        }
    }

    // ------------------------------------------------------------------------------ editing

    /**
     * Replaces the draft as the user moves a control.
     *
     * One entry point rather than eleven setters: the screen sends `draft.copy(field = value)`, which keeps
     * each control's call site to one readable line and means a new parameter needs no new method here.
     * [SensitivityDraft.reconciled] runs on the way in, so a profile that no longer matches the preset it
     * is labelled with stops claiming to be one.
     */
    fun updateDraft(draft: SensitivityDraft) {
        local.update { it.copy(draft = draft.reconciled(), error = null) }
    }

    /** Applies a named starting point to the draft. Nothing is written until the user saves. */
    fun applyPreset(preset: SensitivityPreset) {
        local.update { it.copy(draft = it.draft.withPreset(preset), error = null) }
    }

    /**
     * Switches the preview between hip-fire and ADS.
     *
     * Not a property of a profile — it is which of the profile's two multipliers the engine applies to an
     * input — so it lives here with the screen's other preview controls and is never saved.
     */
    fun setPreviewAiming(aiming: Boolean) {
        local.update { it.copy(previewAiming = aiming) }
    }

    /** Switches the preview between the camera pair and the gyro pair of sensitivity figures. */
    fun setPreviewGyro(gyro: Boolean) {
        local.update { it.copy(previewGyro = gyro) }
    }

    // ------------------------------------------------------------------------------ writing

    /**
     * Writes the draft, creating the profile if it is new and amending it if it is not.
     *
     * The name is sanitised before it is stored — it is user-typed text bound for a list row, a drill's
     * setup card and a saved session's summary — and the whole profile goes through `normalised()`. The
     * returned id is adopted as the active one, which turns a new profile into a saved one without a second
     * round trip: the lab stays open on what the user just made, and a further save amends it rather than
     * creating a duplicate.
     */
    fun save() {
        val snapshot = local.value
        if (snapshot.saving) return
        val name = TextSanitizer.sanitizeName(snapshot.draft.name, SensitivityDraft.MAX_NAME_LENGTH)
        if (name.isBlank()) return

        local.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                val profile = snapshot.draft
                    .copy(name = name)
                    .toProfile(id = snapshot.activeId ?: SensitivityDraft.NEW_ID)
                    .normalised()
                val id = repository.saveSensitivity(profile)
                local.update {
                    it.copy(
                        // The stored profile, not the draft: normalisation may have moved a value, and the
                        // lab must show what was actually written rather than what was asked for.
                        draft = SensitivityDraft.from(profile.copy(id = id)),
                        activeId = id,
                        saving = false,
                        error = null,
                    )
                }
            } catch (cancellation: CancellationException) {
                // The screen went away mid-write. Not a failure, and not ours to report.
                throw cancellation
            } catch (failure: Throwable) {
                local.update { it.copy(saving = false, error = "Could not save this profile.") }
            }
        }
    }

    /**
     * Copies the active profile into a new one and makes the copy active.
     *
     * This is how most profiles after the first get made: a user who has a curve they like tries a variant
     * of it rather than building one from defaults. The name is suffixed and re-sanitised, which also
     * re-applies the length cap — copying a forty-character name must not produce a forty-five-character
     * one.
     */
    fun duplicate() {
        val snapshot = local.value
        if (snapshot.activeId == null || snapshot.saving) return

        local.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                val base = TextSanitizer.sanitizeName(
                    snapshot.draft.name,
                    SensitivityDraft.MAX_NAME_LENGTH,
                )
                val name = TextSanitizer.sanitizeName(
                    if (base.isBlank()) "Profile $COPY_SUFFIX" else "$base $COPY_SUFFIX",
                    SensitivityDraft.MAX_NAME_LENGTH,
                )
                val copy = snapshot.draft
                    .copy(name = name)
                    .toProfile(id = SensitivityDraft.NEW_ID)
                    .normalised()
                val id = repository.saveSensitivity(copy)
                local.update {
                    it.copy(
                        draft = SensitivityDraft.from(copy.copy(id = id)),
                        activeId = id,
                        confirmingDelete = false,
                        saving = false,
                        error = null,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                local.update { it.copy(saving = false, error = "Could not duplicate this profile.") }
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
     * Deletes the active profile and empties the lab.
     *
     * There is no undo behind this — the profile is the user's own work and nothing keeps a copy — which is
     * why it is only reachable through the confirmation. Afterwards the draft resets to defaults rather
     * than holding the deleted profile's numbers, which would invite a save that silently recreated it.
     */
    fun confirmDelete() {
        val id = local.value.activeId
        if (id == null) {
            local.update { it.copy(confirmingDelete = false) }
            return
        }
        local.update { it.copy(confirmingDelete = false, saving = true, error = null) }
        viewModelScope.launch {
            try {
                repository.deleteSensitivity(id)
                local.update {
                    it.copy(
                        draft = SensitivityDraft(),
                        activeId = null,
                        saving = false,
                        error = null,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                local.update { it.copy(saving = false, error = "Could not delete this profile.") }
            }
        }
    }

    private companion object {
        /** Appended to a duplicated profile's name so the list never shows two identical rows. */
        const val COPY_SUFFIX = "copy"

        /**
         * How long [state] stays alive after the screen stops collecting — long enough to survive a
         * rotation and a hop into a drill and back, so a half-finished draft is not lost to a configuration
         * change. The same grace the rest of the app's editors use.
         */
        const val SUBSCRIPTION_GRACE_MILLIS = 5_000L
    }
}
