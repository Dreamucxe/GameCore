package com.gamecore.ui.dock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.model.DockActionId
import com.gamecore.core.model.DockActionKind
import com.gamecore.core.model.DockActions
import com.gamecore.data.preferences.SecurePreferenceStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Which controls the floating dock offers, in what order, and the two resets that put it back (§3.7.1,
 * features 4–6).
 *
 * The stored arrangement is the only copy of the truth and this class never holds a second one. Every switch
 * and every arrow is a [SecurePreferenceStore.updateDockActions] call, and the screen redraws from the flow
 * that write lands in — the shape [com.gamecore.ui.quickapps.QuickAppsViewModel] has, for its reason: it makes
 * [com.gamecore.core.model.DockConfig.normalised]'s completion rules unavoidable. The order that comes back is
 * the order the store accepted, complete and duplicate-free, rather than the order this class asked for. A
 * rule enforced only on the way out of a ViewModel is a rule the next caller does not get.
 *
 * ## Why nothing is reimplemented here
 *
 * Reordering, hiding, showing and the eight-control cap all live in [DockActions], which is pure Kotlin with
 * no Android in it and is pinned by plain JUnit. This class calls those four functions and persists the
 * result; it contains no arithmetic over the order of its own. That is deliberate and it is what keeps the
 * customisation screen and the live panel in agreement — the panel resolves its two grids through
 * [DockActions.visibleOfKind] and so does [DockCustomizationUiState.dockToggles], so there is one answer to
 * "what is on the dock" rather than two that drift.
 *
 * ## Why the dock flow is narrowed before it is combined
 *
 * [SecurePreferenceStore.dock] also carries the dock's remembered position, and the overlay service rewrites
 * that on every drag release and every rotation — while this screen may well be open behind a running game.
 * Rebuilding fourteen rows because the user moved the handle is the background work §26 rules out, so the flow
 * is mapped to the two fields this screen actually reads and de-duplicated first. The same narrowing, for the
 * same reason, that [com.gamecore.ui.quickapps.QuickAppsViewModel] applies to the floating button's flow.
 * [SecurePreferenceStore.settings] is narrowed on the same principle: it changes on every slider in the app.
 *
 * There is no draft stage anywhere here and no save button. A dock the user is arranging is a dock they are
 * looking at — often through a second window over a game — so a change that waited for a save would have them
 * tap the handle, see the old panel, and conclude the screen is broken.
 */
@HiltViewModel
class DockCustomizationViewModel @Inject constructor(
    private val preferences: SecurePreferenceStore,
) : ViewModel() {

    /**
     * The part of the state this ViewModel owns rather than observes: the two confirmations in flight.
     *
     * Two booleans and not one, because the two resets are the one feature on this screen whose whole point is
     * that they are *separate* — see [DockCustomizationUiState.pendingResetAppearance]. Held here rather than
     * in the store because a half-answered dialog is not a preference: it must not survive the screen being
     * left, and it must never reach the file the overlay service reads.
     */
    private data class LocalState(
        val pendingResetAppearance: Boolean = false,
        val pendingResetActions: Boolean = false,
    )

    private val local = MutableStateFlow(LocalState())

    /**
     * The stored arrangement, and nothing else from the dock's config.
     *
     * The pair is the whole of what this screen reads out of [com.gamecore.core.model.DockConfig], so
     * [distinctUntilChanged] makes a position write from a drag in a game a no-op here instead of fourteen
     * recomputed rows. Both components compare structurally — a `List` and a `Set` of enum entries — so the
     * de-duplication is genuine and not a reference check that always sees a new object.
     */
    private val arrangement = preferences.dock
        .map { it.actionOrder to it.hiddenActions }
        .distinctUntilChanged()

    /**
     * The two master switches this screen reports but never sets.
     *
     * Narrowed for [arrangement]'s reason turned up a notch: [SecurePreferenceStore.settings] carries every
     * preference in the app, so an unnarrowed collect here would rebuild this screen's rows while the user
     * dragged an unrelated slider on another screen.
     */
    private val flags = preferences.settings
        .map { it.dockCustomizationEnabled to it.quickActionsEnabled }
        .distinctUntilChanged()

    val state: StateFlow<DockCustomizationUiState> = combine(
        arrangement,
        flags,
        local,
    ) { (order, hidden), (customisationEnabled, quickActionsEnabled), own ->
        DockCustomizationUiState(
            isLoaded = true,
            isCustomizationEnabled = customisationEnabled,
            areQuickActionsEnabled = quickActionsEnabled,
            rows = rowsFor(order, hidden),
            // The panel's own two grid resolutions, called here rather than re-derived from `rows`, so the
            // summary on this screen cannot disagree with what the dock draws. See the class KDoc.
            dockToggles = DockActions.visibleOfKind(order, hidden, DockActionKind.TOGGLE),
            dockActions = DockActions.visibleOfKind(order, hidden, DockActionKind.ACTION),
            pendingResetAppearance = own.pendingResetAppearance,
            pendingResetActions = own.pendingResetActions,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MILLIS),
        DockCustomizationUiState(),
    )

    /**
     * The fourteen controls as rows, in the user's order, with the four arrangement-dependent facts resolved.
     *
     * Three lists are computed once here and shared by every row, rather than each row answering its own
     * questions: the completed order, the switched-on slice of it, and the slice the cap actually leaves on
     * the dock. The distinction between the last two is the honest-cap case —
     * [DockCustomizationUiState.overflowRows] — and it only exists because [DockActions.visible] is asked
     * separately from the plain hidden-set filter rather than being assumed to be the same thing.
     *
     * [DockActions.normaliseOrder] is called even though the store has already normalised what it emitted.
     * It is idempotent, it costs one pass over fourteen entries, and it means the positions drawn on screen
     * are indices into a list that is known complete here — not one that is complete because of something
     * another file promised.
     */
    private fun rowsFor(order: List<DockActionId>, hidden: Set<DockActionId>): List<DockActionRow> {
        val complete = DockActions.normaliseOrder(order)
        val switchedOn = complete.filterNot { it in hidden }
        val onDock = DockActions.visible(complete, hidden)
        return complete.map { id ->
            val index = switchedOn.indexOf(id)
            DockActionRow(
                id = id,
                isShown = index >= 0,
                isOnDock = id in onDock,
                position = if (index >= 0) index + 1 else null,
                // Both arrows are about the switched-on neighbours, because that is what DockActions.move
                // swaps with; see DockActionRow.canMoveEarlier.
                canMoveEarlier = index > 0,
                canMoveLater = index >= 0 && index < switchedOn.lastIndex,
            )
        }
    }

    // ------------------------------------------------------------------------------- the arrangement

    /**
     * Switches one control on or off, leaving its position alone.
     *
     * The position survives a hide because [DockActions.hide] does not touch the order — which is what brings
     * a control back where it was when the user switches it on again, instead of appending it to the end of
     * fourteen.
     *
     * Switching on at the cap is a no-op in [DockActions.show], and this returns without writing when it is.
     * Two reasons, and the second is the important one: a write that stores what is already stored is an
     * encrypted-file round trip for nothing, and a switch that flicked itself back off with no explanation
     * would be worse than one the screen had disabled in the first place. The screen disables it — see
     * [DockCustomizationUiState.isFull] — so arriving here at the wall means something upstream was stale, and
     * the honest response is to change nothing rather than to half-accept it.
     *
     * The order and hidden set are read from [SecurePreferenceStore.dock] rather than from [state], and that
     * is not interchangeable: [state] is shared with [SharingStarted.WhileSubscribed], so its `value` falls
     * back to a default [DockCustomizationUiState] whenever nothing is collecting. Reading the store means an
     * edit is always applied to what is actually stored.
     */
    fun setShown(id: DockActionId, shown: Boolean) {
        val dock = preferences.dock.value
        val order = DockActions.normaliseOrder(dock.actionOrder)
        val hidden = if (shown) {
            DockActions.show(order, dock.hiddenActions, id)
        } else {
            DockActions.hide(dock.hiddenActions, id)
        }
        if (hidden == dock.hiddenActions) return
        preferences.updateDockActions(order, hidden)
    }

    /**
     * Moves one switched-on control one place along the panel, [delta] being -1 towards the front and +1
     * towards the back.
     *
     * [DockActions.move] does the walking, and it walks past anything switched off so the arrow moves the
     * control one *visible* place rather than trading places with something invisible. The ends are walls
     * rather than a wrap, which is the behaviour of every other reorder in the app.
     *
     * A move that changes nothing — off either end, or a control that is switched off — is returned by that
     * function as the order it was given, and this writes nothing in that case. The screen already disables
     * the arrow at each end, so an unchanged order arriving here means a stale row; accepting it would be a
     * silent no-op write to the keystore, which is what
     * [com.gamecore.ui.quickapps.QuickAppsViewModel.move] guards against for the same reason.
     */
    fun move(id: DockActionId, delta: Int) {
        val dock = preferences.dock.value
        val order = DockActions.normaliseOrder(dock.actionOrder)
        val reordered = DockActions.move(order, dock.hiddenActions, id, delta)
        if (reordered == order) return
        preferences.updateDockActions(reordered, dock.hiddenActions)
    }

    // ------------------------------------------------------------------------------------ the resets

    /**
     * Asks before putting the dock's appearance back, because the user's size, opacity and parked position are
     * their own work and nothing in GameCore keeps a copy.
     *
     * Always asks, whatever `confirmBeforeDiscard` says, for the reason
     * [com.gamecore.ui.settings.SettingsViewModel.askClearHistory] always does: there is no undo behind it.
     */
    fun askResetAppearance() {
        local.value = local.value.copy(pendingResetAppearance = true)
    }

    /**
     * Puts size, opacity, panel width, snap-to-edge, vibrate-on-drag and the remembered position back to the
     * shipped values. Touches no part of the arrangement.
     *
     * Two separate resets for two separate halves of the dock, and that narrowness is feature 6 rather than an
     * implementation detail: someone who dragged the handle somewhere awkward should not lose the eight
     * controls they arranged to get it back. [SecurePreferenceStore.resetDockAppearance] writes only the
     * appearance keys, so the separation is real at the file as well as in the copy — and
     * [com.gamecore.core.model.DockConfig.withDefaultAppearance] deliberately leaves `show` alone too, since a
     * dock that vanished while the user was looking at it reads as a crash rather than as a reset.
     */
    fun confirmResetAppearance() {
        preferences.resetDockAppearance()
        local.value = local.value.copy(pendingResetAppearance = false)
    }

    /** Asks before discarding the arrangement. [askResetAppearance]'s mirror, and undoable by neither. */
    fun askResetActions() {
        local.value = local.value.copy(pendingResetActions = true)
    }

    /**
     * Puts the order and the hidden set back to the shipped [DockActionId.DEFAULT_VISIBLE] five. Touches
     * nothing about how the dock looks or where it sits.
     *
     * [SecurePreferenceStore.resetDockActions] writes two keys, so size, opacity, panel width, snap, haptics
     * and every remembered fraction survive — a user resetting which buttons are on the dock has not asked to
     * have the dock moved or resized, and the dialog says so before this runs.
     */
    fun confirmResetActions() {
        preferences.resetDockActions()
        local.value = local.value.copy(pendingResetActions = false)
    }

    /**
     * Drops whichever confirmation was in flight.
     *
     * One cancel for both, where the asks and confirms are deliberately separate: dismissing is the same act
     * whichever dialog it was, and only one of them can be open at a time because each is opened by a tap on
     * the screen behind it. Clearing both is what makes that true rather than assumed.
     */
    fun cancelPending() {
        local.value = local.value.copy(
            pendingResetAppearance = false,
            pendingResetActions = false,
        )
    }

    private companion object {
        /** Long enough to survive a rotation, short enough to stop collecting when the screen is left. */
        const val SUBSCRIPTION_GRACE_MILLIS = 5_000L
    }
}
