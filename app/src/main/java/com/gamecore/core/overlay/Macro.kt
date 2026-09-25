package com.gamecore.core.overlay

import com.gamecore.core.common.TextSanitizer

/**
 * One user-defined panel macro (§14): a named, ordered set of *existing* [OverlayAction]s that a single tap
 * fires in sequence.
 *
 * A macro is composition, not a new capability. It invents no toggle of its own — every step is an action
 * the panel already has, run through the same handler the panel's own tiles run through, so the surface
 * never decides what a control does (spec §0). What a macro adds is only the *order* and the *count*: "do
 * these, in this order, on one tap".
 *
 * The actions a macro may hold are the ones [OverlayAction.isMacroable] admits — the overlays and device
 * toggles with a real on/off state, plus the one self-contained one-shot. The tiles that open an editor or a
 * chip row, and the actions that leave the overlay behind, are barred there rather than here, so this model
 * never has to reason about an action it cannot deterministically replay. See [OverlayAction.macroBehavior].
 *
 * [id] is GameCore's own, minted by [MacroLibrary.nextId] rather than a database row — macros are a config
 * list in `SecurePreferenceStore`, not a table (spec §14: "No DB change needed"). It exists only so the
 * editor and the codec can name one macro among several across a rename or a reorder.
 */
data class Macro(
    val id: Long,
    val name: String,
    val actions: List<OverlayAction>,
) {
    /**
     * The macro reduced to what a store keeps and a foreign file is trusted to have said: the name stripped
     * of layout-breaking characters and capped ([TextSanitizer.sanitizeName], the boundary every user string
     * crosses), and the actions filtered to the ones a macro may replay, de-duplicated (a toggle forced on
     * twice is the same tap), and capped at [MacroLibrary.MAX_ACTIONS] with the user's order kept.
     */
    fun normalised(): Macro = copy(
        name = TextSanitizer.sanitizeName(name),
        actions = actions.filter { it.isMacroable }.distinct().take(MacroLibrary.MAX_ACTIONS),
    )

    /**
     * A macro the grid may draw: it has a name to label the chip and at least one action to run. A macro
     * that survives an import with neither is dropped rather than shown — a chip with no label or a tap that
     * does nothing is the §32 "button that does nothing" in miniature.
     */
    val isRunnable: Boolean get() = name.isNotBlank() && actions.any { it.isMacroable }
}

/**
 * The pure rules behind the macro list (§14), the sibling of [QuickSheetPins].
 *
 * Every rule is a decision that can be made without a device or a store: how many macros are kept, how many
 * actions one may hold, how the list and a macro's action set are edited, and what an unrunnable macro
 * becomes. Persistence is [MacroCodec]'s and the service's; drawing is the sheet's and the editor's. This
 * object only says what a *valid* macro list is, so it is unit-tested with plain JUnit — no `Context`, no
 * `SharedPreferences`, no running overlay.
 *
 * The editing helpers are no-ops in exactly the cases where the rule they guard would break — a full list, a
 * duplicate, a move off either end — so the editor screen stays honest by disabling the affordance the rule
 * would reject, the same contract [QuickSheetPins] gives the pin editor.
 */
object MacroLibrary {

    /**
     * The most macros that are kept and drawn. Six, matching the quick sheet's own grid cap: the macros are
     * surfaced on the same small surface over a game, and a list of forty chips would bury it. The wall a
     * hand-edited store or an imported file is held to, not just the editor's.
     */
    const val MAX_MACROS: Int = 6

    /**
     * The most actions one macro may hold. Eight, which is every [OverlayAction.isMacroable] action there is,
     * so the cap never stops a user composing all of them into one tap while still bounding a hostile file
     * that repeats actions past the [Macro.normalised] de-dupe could not (it cannot repeat, but a newer build
     * with more macroable actions still gets a wall).
     */
    const val MAX_ACTIONS: Int = 8

    /**
     * Turn a raw macro list — freshly decoded by [MacroCodec], or handed back from an edit — into the one
     * that is stored and drawn. In order: normalise each macro (sanitise its name, drop non-macroable and
     * duplicate actions, cap them), drop the ones with nothing runnable left, collapse duplicate ids keeping
     * the first, and keep at most [MAX_MACROS]. The id de-dupe is what makes an imported file with two
     * macros sharing an id resolve to one rather than to a pair the editor cannot tell apart.
     */
    fun normalise(raw: List<Macro>): List<Macro> =
        raw.asSequence()
            .map { it.normalised() }
            .filter { it.isRunnable }
            .distinctBy { it.id }
            .take(MAX_MACROS)
            .toList()

    /** The next free id for a new macro: one past the largest in use, or 1 for the first. Pure, so the editor's "new macro" is testable. */
    fun nextId(existing: List<Macro>): Long = (existing.maxOfOrNull { it.id } ?: 0L) + 1L

    // ----------------------------------------------------------------- the macro list

    /**
     * Add a macro to the list. A no-op — the list back unchanged — when the list is already full, when the
     * macro has nothing runnable, or when its id is already present (the editor mints ids with [nextId], so a
     * collision means a stale call rather than a real second macro). Otherwise it goes on the end.
     */
    fun add(current: List<Macro>, macro: Macro): List<Macro> {
        val normalised = macro.normalised()
        return when {
            current.size >= MAX_MACROS -> current
            !normalised.isRunnable -> current
            current.any { it.id == normalised.id } -> current
            else -> current + normalised
        }
    }

    /** Remove the macro with this id. Absent id, list unchanged. */
    fun remove(current: List<Macro>, id: Long): List<Macro> = current.filterNot { it.id == id }

    /**
     * Move one macro along the list. [delta] is -1 (towards the front) or +1 (towards the back); the ends are
     * walls, not a wrap — the exact semantics of [QuickSheetPins.move], so a reorder feels the same wherever
     * the user meets one. An absent id or a move off either end returns the list untouched.
     */
    fun move(current: List<Macro>, id: Long, delta: Int): List<Macro> {
        val list = current.toMutableList()
        val from = list.indexOfFirst { it.id == id }
        val to = from + delta
        if (from < 0 || to !in list.indices) return current
        val moved = list.removeAt(from)
        list.add(to, moved)
        return list
    }

    /** Rename one macro, sanitising the new name at the boundary as [Macro.normalised] would. */
    fun rename(current: List<Macro>, id: Long, name: String): List<Macro> =
        current.map { if (it.id == id) it.copy(name = TextSanitizer.sanitizeName(name)) else it }

    // ----------------------------------------------------------------- one macro's actions

    /**
     * Add an action to a macro. A no-op when the macro is already at [MAX_ACTIONS], when the action is not
     * one a macro may replay ([OverlayAction.isMacroable]), or when it is already in the set (a toggle forced
     * on twice is the same tap). Otherwise it goes on the end, where the user who just added it looks.
     */
    fun addAction(macro: Macro, action: OverlayAction): Macro = when {
        macro.actions.size >= MAX_ACTIONS -> macro
        !action.isMacroable -> macro
        action in macro.actions -> macro
        else -> macro.copy(actions = macro.actions + action)
    }

    /** Remove an action from a macro. Absent action, macro unchanged. */
    fun removeAction(macro: Macro, action: OverlayAction): Macro =
        macro.copy(actions = macro.actions - action)

    /** Move one action within a macro, with [move]'s wall-not-wrap semantics on the action list. */
    fun moveAction(macro: Macro, action: OverlayAction, delta: Int): Macro {
        val list = macro.actions.toMutableList()
        val from = list.indexOf(action)
        val to = from + delta
        if (from < 0 || to !in list.indices) return macro
        list.removeAt(from)
        list.add(to, action)
        return macro.copy(actions = list)
    }

    /** The actions the editor may still offer for a macro: macroable, and not already in it. */
    fun addableActions(macro: Macro): List<OverlayAction> =
        OverlayAction.entries.filter { it.isMacroable && it !in macro.actions }
}
