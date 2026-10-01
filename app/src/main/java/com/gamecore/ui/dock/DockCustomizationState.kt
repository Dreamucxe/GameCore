package com.gamecore.ui.dock

import com.gamecore.core.model.DockActionId
import com.gamecore.core.model.DockActionKind
import com.gamecore.core.model.DockActions

/**
 * The dock's arrangement, being arranged (§3.7.1, features 4–6).
 *
 * One list and two switches' worth of state, and the shape of it follows
 * [com.gamecore.ui.quickapps.QuickAppsUiState] closely on purpose: both screens are an ordered list of the
 * user's own choices where **order is meaning**, and a user who has learned one should not have to learn a
 * second set of rules. The difference is that the quick-launch row's vocabulary is whatever is installed on
 * the phone, while the dock's is a closed set of fourteen — so there is no picker here and no second list.
 * Every one of the fourteen is always on screen, switched on or switched off, which is what makes "where did
 * Screenshot go" a question this screen can never raise.
 *
 * ## What is deliberately *not* here
 *
 * None of the dock's appearance: size, opacity, panel width, snap-to-edge, vibrate-on-drag and reset-position
 * are all on the Floating dock card in `OverlayScreen`, and they stay there. Two places to change one thing
 * is exactly the split-ownership bug [com.gamecore.core.model.DockConfig]'s own KDoc refuses, and it is why
 * this state carries no [com.gamecore.core.model.DockConfig] fields beyond the two action ones. The only
 * appearance-shaped thing on this screen is the *reset*, and it is here because its mirror is — see
 * [pendingResetAppearance].
 *
 * ## Why the two flags are read rather than offered
 *
 * [isCustomizationEnabled] and [areQuickActionsEnabled] are shown, never switched, from this screen. They are
 * master switches belonging to Settings, and a second copy of either here would be the same two-owners
 * problem one level up. What this state does with them is report the consequence: a dock drawing its shipped
 * arrangement while the user's own is kept, and an action grid that is not drawn at all.
 *
 * Both default to the **permissive** value, which matters for the frame before the store's first emission
 * lands. [com.gamecore.core.overlay.DockSignals] documents the same convention for the same reason — the
 * honest default is "assume it works until told otherwise", because the cost of being wrong the other way is
 * a warning banner that flashes up and then takes itself back down in front of the user. [isLoaded] is the
 * belt to that braces: nothing explanatory is drawn until the real values have arrived.
 *
 * Nothing here is a draft. Every switch and every arrow is written through
 * [com.gamecore.data.preferences.SecurePreferenceStore.updateDockActions] as it happens, for the reason the
 * rest of GameCore's settings screens have no save button.
 */
data class DockCustomizationUiState(
    /** False until the stored arrangement and the two master switches have both been read at least once. */
    val isLoaded: Boolean = false,
    /**
     * [com.gamecore.core.model.AppSettings.dockCustomizationEnabled], as read.
     *
     * When off, that flag's KDoc promises the saved order and hidden set are *kept* rather than cleared while
     * the dock draws its shipped arrangement. So the list below stays editable and the screen says what is
     * happening instead — switching the flag back on has to return the user to the dock they built, and a
     * screen that locked itself would imply their arrangement had been taken away.
     */
    val isCustomizationEnabled: Boolean = true,
    /**
     * [com.gamecore.core.model.AppSettings.quickActionsEnabled], as read.
     *
     * When off, the action rows are not drawn as an inert list — that flag's KDoc calls for "a genuine
     * feature disable, not a greyed control", and
     * [com.gamecore.core.overlay.isGatedByQuickActions] spells out why a group-level hide must not be
     * dressed up as fourteen identical reasons. [actionRows] is still populated; it is the *screen* that
     * chooses to explain rather than list, so this state keeps the whole truth and nothing has to be
     * reconstructed if the flag comes back on.
     */
    val areQuickActionsEnabled: Boolean = true,
    /**
     * All fourteen controls in the user's order, hidden ones included, exactly as
     * [com.gamecore.core.model.DockConfig.actionOrder] holds them.
     *
     * The complete vocabulary and not the shown slice, for the reason the model stores it that way: a hidden
     * control keeps its place, so hiding Screenshot, rearranging four things and showing it again brings it
     * back where it was rather than appending it to the end. A screen that listed only the shown ones would
     * have no way to show that, and the user would have to discover it.
     */
    val rows: List<DockActionRow> = emptyList(),
    /**
     * The toggle cells the panel will actually draw, in order — straight from
     * [DockActions.visibleOfKind].
     *
     * Derived through that function rather than by filtering [rows] here, because it is the same call the
     * live panel makes to fill its own toggle grid. A second implementation of "what is on the dock" is how a
     * customisation screen and the thing it customises end up disagreeing, and that disagreement is the one
     * failure this whole screen exists to prevent.
     */
    val dockToggles: List<DockActionId> = emptyList(),
    /** The action chips the panel will draw, in order, from the same [DockActions.visibleOfKind] call. */
    val dockActions: List<DockActionId> = emptyList(),
    /**
     * Whether "Reset dock appearance" is waiting to be confirmed.
     *
     * Its own flag rather than one shared "something is pending", and that separation is the feature. The two
     * resets touch disjoint halves of [com.gamecore.core.model.DockConfig] —
     * [com.gamecore.data.preferences.SecurePreferenceStore.resetDockAppearance] writes size, opacity, panel
     * width, snap, haptics and position; [pendingResetActions]' one writes the order and the hidden set — and
     * each dialog has to be able to name what it is *not* touching. A single flag would mean one dialog
     * trying to describe both, which is the point at which a user stops reading it.
     */
    val pendingResetAppearance: Boolean = false,
    /** Whether "Reset quick actions" is waiting to be confirmed. The mirror of [pendingResetAppearance]. */
    val pendingResetActions: Boolean = false,
) {
    /** The toggle half of [rows], in the user's order — the panel's first grid, arranged. */
    val toggleRows: List<DockActionRow> get() = rows.filter { it.kind == DockActionKind.TOGGLE }

    /** The action half of [rows]. Populated whatever [areQuickActionsEnabled] says — see its KDoc. */
    val actionRows: List<DockActionRow> get() = rows.filter { it.kind == DockActionKind.ACTION }

    /** How many of [DockActions.MAX_VISIBLE] places the dock's two grids are using between them. */
    val onDockCount: Int get() = dockToggles.size + dockActions.size

    /**
     * True when the dock is carrying all [DockActions.MAX_VISIBLE] it can.
     *
     * The cap is enforced in [DockActions.show], which returns the hidden set unchanged at the wall rather
     * than letting [DockActions.visible] truncate the user's last tap. This is what lets the screen disable
     * the remaining switches and say why *before* a tap that would do nothing — §32's rule, and the reason
     * the model put the cap in `show` instead of in `visible`.
     */
    val isFull: Boolean get() = onDockCount >= DockActions.MAX_VISIBLE

    /**
     * Controls the user has switched on that the dock is nevertheless not drawing, because they sit behind
     * [DockActions.MAX_VISIBLE] others.
     *
     * Normally empty, and that is the point of surfacing it rather than trusting it to be. The screen cannot
     * produce this state — [isFull] disables the switches first — but a hand-edited preferences file can, and
     * so can a build that lowers the cap under an arrangement made against the old one. In either case
     * [DockActions.visible] takes the first eight and the rest simply do not appear on the dock. A silently
     * dropped control is precisely the failure this codebase refuses, so the rows are named in words and
     * marked in the list instead.
     */
    val overflowRows: List<DockActionRow> get() = rows.filter { it.isShown && !it.isOnDock }

    /**
     * The sentence that names the dropped controls, or null when nothing is dropped.
     *
     * Names them rather than counting them, because "one control is not shown" sends the user hunting down a
     * list of fourteen for which one it was.
     */
    val overflowNote: String?
        get() = overflowRows.takeIf { it.isNotEmpty() }?.let { dropped ->
            "The dock draws the first ${DockActions.MAX_VISIBLE} controls you switch on, so " +
                "${listSentence(dropped.map { it.label })} " +
                (if (dropped.size == 1) "is" else "are") +
                " switched on but not on it. Move one further up the list, or switch something above it " +
                "off, and it appears."
        }

    /**
     * What the arrangement is doing right now, as one line under the title.
     *
     * The three states that need different actions from the user get different sentences: nothing read yet,
     * customisation switched off so none of this is being drawn, and controls switched on that are being
     * dropped. A bare count in place of any of those would be a figure that is true and useless.
     */
    val summary: String
        get() = when {
            !isLoaded -> "Reading your arrangement"
            !isCustomizationEnabled ->
                "Customisation is off — the dock is drawing its shipped ${DockActionId.DEFAULT_VISIBLE.size}"
            overflowRows.isNotEmpty() ->
                "$onDockCount on the dock, ${overflowRows.size} switched on but past the limit"
            else -> "$onDockCount of ${DockActions.MAX_VISIBLE} places used"
        }
}

/**
 * One of the fourteen controls, as this screen needs to draw it (§3.7.1, features 4–5).
 *
 * A view of a [DockActionId] and never a copy of one: the label, the kind and the two honesty flags are read
 * through [id] rather than duplicated into fields, so nothing here can fall out of step with the enum when a
 * fifteenth control is added. What this class *does* hold is the four facts that depend on the whole
 * arrangement and therefore cannot live on an enum entry — where this control sits, whether it is switched
 * on, whether it survived the cap, and which arrows are worth drawing.
 *
 * Every one of those four is computed by the ViewModel from the stored order, and none of them is trusted
 * back. [DockCustomizationViewModel.setShown] and [DockCustomizationViewModel.move] take an id and re-read
 * the store, so a row left over from a frame the user has already changed cannot write a stale order.
 */
data class DockActionRow(
    val id: DockActionId,
    /**
     * Whether the user has this control switched on — that is, whether it is *out* of
     * [com.gamecore.core.model.DockConfig.hiddenActions].
     *
     * Switched on is not the same as on the dock; see [isOnDock]. Keeping the two apart is what lets the
     * screen say "you switched this on and it is still not there, and here is why".
     */
    val isShown: Boolean,
    /**
     * Whether the dock will actually draw it — switched on *and* inside [DockActions.MAX_VISIBLE].
     *
     * Availability deliberately does not enter this. A control this device cannot perform still goes on the
     * dock and is drawn there dimmed with a reason ([com.gamecore.core.overlay.DockUnavailableReason]), which
     * is the opposite of the quick sheet's rule and is documented as such on
     * [com.gamecore.core.model.DockConfig.visibleActions]: a screen that hid what the panel shows would be
     * the two surfaces disagreeing again.
     */
    val isOnDock: Boolean,
    /**
     * Its place among the switched-on controls, counting from one, or null when it is switched off.
     *
     * Numbered rather than left to the list order alone, for [com.gamecore.ui.quickapps.ChosenQuickApp]'s
     * reason: the panel lays its grids out across the screen while this list runs down it, so without a
     * figure the user has to infer that higher here means earlier there. A number above
     * [DockActions.MAX_VISIBLE] is the readable form of [DockCustomizationUiState.overflowNote] — position
     * nine, of eight places.
     */
    val position: Int?,
    /**
     * Whether the up arrow is worth offering: there is a switched-on control above this one.
     *
     * About the switched-*on* neighbours and not the raw list, because [DockActions.move] swaps with the next
     * shown control and walks past hidden ones. An arrow enabled against a hidden neighbour would appear to
     * do nothing, which is the bug that function's `hidden` parameter exists to rule out — and the arrow has
     * to agree with it or the screen puts §32's dead button back.
     */
    val canMoveEarlier: Boolean,
    /** Whether the down arrow is worth offering. The mirror of [canMoveEarlier]. */
    val canMoveLater: Boolean,
) {
    /** The user-facing name, from the enum, so this screen and the panel call it the same thing. */
    val label: String get() = id.label

    /** Which of the panel's two grids it belongs in — a property of the control, not of the arrangement. */
    val kind: DockActionKind get() = id.kind

    /**
     * What putting this control on the dock actually gets the user, in one or two sentences.
     *
     * Composed from [DockActionId.isAlwaysAvailable] and [DockActionId.opensAppScreen] rather than written
     * out fourteen times, so a control added to the enum arrives with the right description and there is no
     * table here to go stale.
     *
     * Both facts are stated, never just the first, because two of the fourteen carry both and they are the
     * two the user most needs warned about. The second sentence is the one this screen exists to say out
     * loud: the dock's promise is a control that works *without leaving the game*, and three of its fourteen
     * break that promise. Discovering which three by tapping one mid-match is not an acceptable way to find
     * out.
     */
    val description: String
        get() = buildString {
            append(if (id.isAlwaysAvailable) NEEDS_NOTHING else NEEDS_SOMETHING)
            if (id.opensAppScreen) {
                append(' ')
                append(LEAVES_THE_GAME)
            }
        }
}

/**
 * Two or more names run together the way a sentence needs them — "Hunt and Extract", "Hunt, Scout and
 * Extract" — rather than the way a list prints.
 *
 * Here rather than in [com.gamecore.core.common.Formatters] because nothing else in the app needs it yet, and
 * a shared formatter added speculatively is a shared formatter nobody can change. The reason it exists at all
 * is that [DockCustomizationUiState.overflowNote] is a sentence the user has to *read* to act on: a bare
 * `joinToString` would put "Hunt and Scout and Extract" in front of them, which reads as a fault in the app
 * and quietly undermines the one note on this screen that must be believed.
 */
private fun listSentence(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> names.first()
    2 -> "${names[0]} and ${names[1]}"
    else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
}

/**
 * The description for a control that depends on nothing beyond the overlay permission the dock already holds.
 *
 * Phrased as the honest hint [DockActionId.isAlwaysAvailable]'s KDoc says it is, and not as a guarantee:
 * real capability is the service's to decide and is surfaced on the panel as a dimmed cell with a reason.
 */
private const val NEEDS_NOTHING =
    "Needs nothing beyond permission to draw over other apps, which the dock already has."

/**
 * The description for a control whose press depends on the device or on the moment.
 *
 * The three causes are named instead of left as "may be unavailable", because each has a different fix and
 * only the user can apply any of them — see [com.gamecore.core.overlay.DockUnavailableReason], which is where
 * the panel's own wording for each of these comes from.
 */
private const val NEEDS_SOMETHING =
    "Needs something this device or this moment may not have — screen capture, an elevated shell, a saved " +
        "layout, a trigger set up for the game in front — so the dock draws it dimmed with the reason " +
        "rather than failing on the tap."

/** The warning the three [DockActionId.opensAppScreen] controls carry. See [DockActionRow.description]. */
private const val LEAVES_THE_GAME =
    "Pressing it leaves the game for one of GameCore's own screens: the game keeps running behind, but the " +
        "match does not wait for you."
