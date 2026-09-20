package com.gamecore.aimlab.ui.hud

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.aimlab.AimLabRepository
import com.gamecore.aimlab.engine.ControlLayout
import com.gamecore.aimlab.engine.ControlRole
import com.gamecore.aimlab.engine.ControlShape
import com.gamecore.aimlab.engine.ControlWidget
import com.gamecore.aimlab.engine.LayoutPreset
import com.gamecore.aimlab.engine.SafeArea
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
 * Owns the layout list and the layout being arranged.
 *
 * The shape is the section's idiom: the stored layouts come from [AimLabRepository.layouts] and are
 * folded into whatever the user is currently doing, which lives in [local]. Nothing the user is doing is
 * persisted as they do it — a drag is not a write — so leaving the screen mid-arrangement loses the
 * arrangement and not the stored layout, and the Save button is the only thing that touches the database.
 *
 * **Every** position this class produces goes through [ControlWidget.clampTo] against the current
 * [ControlEditorState.safeArea], and there is exactly one place it can happen: [editControl] below. Moves,
 * resizes and preset application all funnel through it, which is what makes §12's "a control cannot end
 * up outside the usable screen" a property of the class rather than a rule each entry point has to
 * remember. It is not re-implemented here either — the clamp is the engine's, so the editor and the
 * training surface cannot disagree about where the edge is.
 *
 * Sizes are per control and stay per control. [onWidthPercentChanged] and [onHeightPercentChanged] write
 * to the selected role only, so Shoot and ADS — two separate [ControlWidget]s — are independently sizable
 * exactly as §11 requires. There is no shared "button size" anywhere in this file to accidentally tie
 * them together.
 */
@HiltViewModel
class ControlEditorViewModel @Inject constructor(
    private val repository: AimLabRepository,
) : ViewModel() {

    private val local = MutableStateFlow(ControlEditorState())

    val state: StateFlow<ControlEditorState> = combine(
        local,
        repository.layouts,
    ) { editing, layouts ->
        editing.copy(loading = false, layouts = layouts)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MILLIS),
        // loading = true until the layouts flow has answered, so "no layouts yet" cannot appear in front
        // of a list that is one frame away.
        initialValue = ControlEditorState(loading = true),
    )

    // ------------------------------------------------------------------------------ opening a layout

    /**
     * Opens a stored layout for arranging.
     *
     * Re-read through [AimLabRepository.layout] rather than taken from the observed list: the list is a
     * snapshot that may be a frame old, and this draft is what a later save will overwrite the row with.
     * It is clamped on the way in, because the layout may have been arranged on a different device — or
     * on this one in the other orientation — and a control that is outside *this* safe area has to be
     * pulled back in before the user sees it, not silently moved the next time they touch it.
     */
    fun select(id: Long) {
        viewModelScope.launch {
            val layout = try {
                repository.layout(id)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                local.update { it.copy(error = "Could not open this layout.") }
                null
            }
            // Null means the row is gone — deleted under us, or never there. The observed list is already
            // showing that, so there is nothing to open and nothing to report.
            if (layout == null) return@launch
            local.update { current ->
                val clamped = layout.clampedTo(current.safeArea)
                current.copy(
                    draft = clamped,
                    editingId = layout.id,
                    selectedRole = defaultRole(clamped),
                    arranging = false,
                    draggingRole = null,
                    // A clamp on open is the editor correcting the stored layout for this screen, which
                    // is a change the user has not saved yet — so it counts as unsaved work.
                    dirty = clamped != layout,
                    confirmingDelete = false,
                    error = null,
                )
            }
        }
    }

    /**
     * Starts a new, unsaved layout from the three-finger preset.
     *
     * Three fingers rather than an empty screen: a layout with no controls in it cannot be dragged into
     * existence — there would be nothing to drag — so the honest starting point is the smallest arrangement
     * that is actually playable, which the user then moves, resizes and switches off from.
     *
     * The stored identity is dropped, which is what makes this "new" rather than "reset": a save writes a
     * second layout and leaves whatever was open before exactly as it was.
     */
    fun newLayout() {
        local.update { current ->
            val built = ControlLayout.preset(LayoutPreset.THREE_FINGER).clampedTo(current.safeArea)
            current.copy(
                draft = built,
                editingId = null,
                selectedRole = defaultRole(built),
                arranging = false,
                draggingRole = null,
                dirty = true,
                confirmingDelete = false,
                error = null,
            )
        }
    }

    /**
     * Applies a finger-count preset.
     *
     * With a layout open this replaces its controls and keeps its name and its stored identity, which is
     * §12's "reset to a preset": the user keeps the layout they have been using and starts its
     * arrangement again. With nothing open it starts a new, unsaved layout named after the preset.
     * Either way nothing is written until Save — a mis-tapped preset costs the arrangement on screen, not
     * the stored one.
     */
    fun applyPreset(preset: LayoutPreset) {
        local.update { current ->
            val built = ControlLayout.preset(preset).clampedTo(current.safeArea)
            val draft = current.draft
            val next = if (draft == null) {
                built
            } else {
                // A new layout still wearing a preset's own name follows the preset it is switched to; a
                // name the user typed is theirs and is never overwritten. `id` is carried either way, so
                // resetting a stored layout amends that row instead of quietly creating a second copy.
                val untouched = current.editingId == null &&
                    ControlEditorState.PRESETS.any { it.label == draft.name }
                draft.copy(
                    name = if (untouched) built.name else draft.name,
                    controls = built.controls,
                )
            }
            current.copy(
                draft = next,
                selectedRole = defaultRole(next),
                draggingRole = null,
                dirty = true,
                error = null,
            )
        }
    }

    /** Renames the open layout. Sanitised on save, where the trim cannot eat the space being typed. */
    fun onNameChanged(name: String) {
        local.update { current ->
            val draft = current.draft ?: return@update current
            current.copy(draft = draft.copy(name = name), dirty = true, error = null)
        }
    }

    /**
     * Takes the device's real usable region, measured by the screen from the window insets.
     *
     * Re-clamping here rather than only on save is what makes a rotation safe: a layout arranged in one
     * orientation may put a control where the other one has a cutout, and the correction has to be
     * visible in the preview at the moment it happens rather than applied behind the user's back later.
     */
    fun onSafeAreaChanged(safeArea: SafeArea) {
        local.update { current ->
            if (current.safeArea == safeArea) return@update current
            val draft = current.draft ?: return@update current.copy(safeArea = safeArea)
            val clamped = draft.clampedTo(safeArea)
            current.copy(
                safeArea = safeArea,
                draft = clamped,
                dirty = current.dirty || clamped != draft,
            )
        }
    }

    // ------------------------------------------------------------------------------ direct manipulation

    /** Switches the whole screen over to the drag surface. Only meaningful with a layout open. */
    fun startArranging() {
        local.update { if (it.draft == null) it else it.copy(arranging = true) }
    }

    /** Leaves the drag surface for the panel. The arrangement is kept; it is still unsaved. */
    fun stopArranging() {
        local.update { it.copy(arranging = false, draggingRole = null) }
    }

    /** Picks the control the per-control card edits, from the selector row. */
    fun onControlSelected(role: ControlRole) {
        local.update { it.copy(selectedRole = role, error = null) }
    }

    /**
     * A finger has landed on a control in the preview.
     *
     * Grabbing is also selecting: the control under the thumb becomes the one the sliders act on, so
     * there is no separate "now choose it in the list" step after moving something.
     */
    fun onControlGrabbed(role: ControlRole) {
        local.update { it.copy(selectedRole = role, draggingRole = role, error = null) }
    }

    /**
     * Moves a control's centre to a point the preview reported, in arena fractions.
     *
     * The point arrives unclamped — the surface reports where the finger is, which is its job — and
     * [editControl] puts it through the engine's clamp. That is why dragging a button at the edge of the
     * screen slides it along the edge instead of letting it leave: the finger keeps going, the control
     * does not.
     */
    fun onControlMoved(role: ControlRole, xFraction: Float, yFraction: Float) {
        editControl(role) { it.copy(xFraction = xFraction, yFraction = yFraction) }
    }

    /** The last finger has come off. The arrangement is already in the draft; this only clears the glow. */
    fun onControlReleased() {
        local.update { it.copy(draggingRole = null) }
    }

    // ------------------------------------------------------------------------------ the selected control

    /** Width only, for the selected control only — see the class comment on independent sizing. */
    fun onWidthPercentChanged(percent: Int) = editSelected {
        it.copy(widthFraction = ControlEditorState.fractionOf(percent))
    }

    /** Height only, for the selected control only. */
    fun onHeightPercentChanged(percent: Int) = editSelected {
        it.copy(heightFraction = ControlEditorState.fractionOf(percent))
    }

    fun onOpacityChanged(percent: Int) = editSelected { it.copy(opacityPercent = percent) }

    fun onShapeSelected(shape: ControlShape) = editSelected { it.copy(shape = shape) }

    /**
     * Switches a control on or off.
     *
     * A switched-off control is not deleted: it stays in the layout with its position and size intact, is
     * not drawn on the training surface, and can be switched back on from the selector row. Removing it
     * would make "off" a destructive action wearing a switch.
     */
    fun onEnabledChanged(enabled: Boolean) = editSelected { it.copy(enabled = enabled) }

    private inline fun editSelected(crossinline transform: (ControlWidget) -> ControlWidget) {
        val role = local.value.selectedRole ?: return
        editControl(role, transform)
    }

    /**
     * The one place a control changes, and therefore the one place the clamp has to be applied.
     *
     * The controls are rebuilt in place rather than through [ControlLayout.withControl], which filters the
     * role out and appends it: that would reshuffle the list on every slider tick, and the list's order is
     * both the draw order and the order the selector row shows. A button must not jump to the end of the
     * row because the user widened it.
     *
     * Re-clamping on a resize and not only on a move is deliberate. Growing a control near an edge pushes
     * its body past the safe area even though its centre never moved, and [ControlWidget.clampTo]
     * subtracts the new half-extents before it coerces, so the centre is nudged inwards by exactly enough.
     */
    private inline fun editControl(
        role: ControlRole,
        crossinline transform: (ControlWidget) -> ControlWidget,
    ) {
        local.update { current ->
            val draft = current.draft ?: return@update current
            if (draft.controls.none { it.role == role }) return@update current
            val controls = draft.controls.map { control ->
                if (control.role == role) transform(control).clampTo(current.safeArea) else control
            }
            current.copy(draft = draft.copy(controls = controls), dirty = true, error = null)
        }
    }

    // ------------------------------------------------------------------------------ persistence

    /**
     * Writes the open layout, creating it if it is new and amending it if it is not.
     *
     * The name is sanitised here rather than per keystroke, and the whole layout is clamped once more
     * before it goes out: the safe area may have changed since the last edit, and what is stored has to be
     * a layout that is legal on the device it was arranged on. The returned id is adopted as
     * [ControlEditorState.editingId], which turns a new layout into a stored one without a second round
     * trip — the editor stays open on what the user just made and a further save amends it.
     */
    fun save() {
        val snapshot = local.value
        val draft = snapshot.draft ?: return
        if (snapshot.saving) return
        val name = TextSanitizer.sanitizeName(draft.name, ControlLayout.MAX_NAME_LENGTH)
        if (name.isBlank()) return

        local.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                val layout = draft
                    .copy(id = snapshot.editingId ?: NEW_ID, name = name)
                    .clampedTo(snapshot.safeArea)
                val id = repository.saveLayout(layout)
                local.update {
                    it.copy(
                        // The stored layout, not the draft: the clamp may have moved something, and the
                        // editor must show what was written rather than what was asked for.
                        draft = layout.copy(id = id),
                        editingId = id,
                        saving = false,
                        dirty = false,
                        error = null,
                    )
                }
            } catch (cancellation: CancellationException) {
                // The screen went away mid-write. Not a failure, and not ours to report.
                throw cancellation
            } catch (failure: Throwable) {
                local.update { it.copy(saving = false, error = "Could not save this layout.") }
            }
        }
    }

    /**
     * Copies the open layout into a new one and opens the copy.
     *
     * The suffix is re-sanitised along with the name so copying a maximum-length name does not produce an
     * over-length one. The copy carries the arrangement as it stands on screen, unsaved changes included —
     * duplicating is how a user branches an arrangement they are part-way through without overwriting the
     * one they started from.
     */
    fun duplicate() {
        val snapshot = local.value
        val draft = snapshot.draft ?: return
        if (snapshot.editingId == null || snapshot.saving) return

        local.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                val base = TextSanitizer.sanitizeName(draft.name, ControlLayout.MAX_NAME_LENGTH)
                val name = TextSanitizer.sanitizeName(
                    if (base.isBlank()) "Layout $COPY_SUFFIX" else "$base $COPY_SUFFIX",
                    ControlLayout.MAX_NAME_LENGTH,
                )
                val copy = draft
                    .copy(id = NEW_ID, name = name)
                    .clampedTo(snapshot.safeArea)
                val id = repository.saveLayout(copy)
                local.update {
                    it.copy(
                        draft = copy.copy(id = id),
                        editingId = id,
                        confirmingDelete = false,
                        saving = false,
                        dirty = false,
                        error = null,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                local.update { it.copy(saving = false, error = "Could not duplicate this layout.") }
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
     * Deletes the open layout and empties the editor.
     *
     * There is no undo behind this — the arrangement is the user's own work and nothing keeps a copy —
     * which is why it is only reachable through the confirmation. Afterwards the editor holds no draft
     * rather than the deleted one's controls, which would invite a save that silently recreated it.
     */
    fun confirmDelete() {
        val snapshot = local.value
        val id = snapshot.editingId
        if (id == null) {
            local.update { it.copy(confirmingDelete = false) }
            return
        }
        local.update { it.copy(confirmingDelete = false, saving = true, error = null) }
        viewModelScope.launch {
            try {
                repository.deleteLayout(id)
                local.update {
                    it.copy(
                        draft = null,
                        editingId = null,
                        selectedRole = null,
                        arranging = false,
                        draggingRole = null,
                        saving = false,
                        dirty = false,
                        error = null,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                local.update { it.copy(saving = false, error = "Could not delete this layout.") }
            }
        }
    }

    /**
     * Which control the editor opens on.
     *
     * Shoot when there is one, because it is the control every layout is built around and the one a user
     * opening a layout is most likely to reach for first. Otherwise the first enabled control, and only
     * then a disabled one — opening on something invisible in the preview would look like nothing was
     * selected at all.
     */
    private fun defaultRole(layout: ControlLayout): ControlRole? =
        layout.controls.firstOrNull { it.role == ControlRole.SHOOT }?.role
            ?: layout.enabledControls.firstOrNull()?.role
            ?: layout.controls.firstOrNull()?.role

    private companion object {
        /** The id a not-yet-stored layout carries; the repository allocates the real one on insert. */
        const val NEW_ID = 0L

        /** Appended to a duplicated layout's name so the list never shows two identical rows. */
        const val COPY_SUFFIX = "copy"

        /**
         * How long [state] stays alive after the screen stops collecting — long enough to survive a
         * rotation, so an arrangement in progress is not lost to a configuration change. The same grace
         * the weapon editor uses, and for the same reason: unsaved work is on screen.
         */
        const val SUBSCRIPTION_GRACE_MILLIS = 5_000L
    }
}
