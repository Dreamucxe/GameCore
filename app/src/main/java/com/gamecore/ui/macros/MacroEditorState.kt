package com.gamecore.ui.macros

import com.gamecore.core.overlay.Macro
import com.gamecore.core.overlay.MacroLibrary

/**
 * What the §14 macro editor draws, derived entirely from the stored macro list.
 *
 * The screen is a *render-store*, not a working-copy editor: it shows exactly the macros the store holds
 * (as decoded and normalised by [com.gamecore.core.overlay.MacroCodec]), and every edit writes straight
 * back through it. So this state carries no draft and no in-progress macro — the list here is the same list
 * the quick sheet draws, which is what keeps the two from ever disagreeing. [isLoaded] is the one thing not
 * derivable from [macros]: an empty list before the first read and an empty list after the user deleted the
 * last macro look identical, and only one of them should say "No macros yet".
 *
 * The sibling of `QuickAppsState`, and for the same reasons: [isFull] disables the Add affordance at the
 * model's own [MacroLibrary.MAX_MACROS] rather than at a number retyped here, and [summary] is the one line
 * the header subtitle shows so the screen never has to phrase the count in two places.
 */
data class MacroEditorUiState(
    val isLoaded: Boolean = false,
    val macros: List<Macro> = emptyList(),
) {
    /** True once the list is at the cap; the screen disables Add, matching [MacroLibrary.add]'s own no-op. */
    val isFull: Boolean get() = macros.size >= MacroLibrary.MAX_MACROS

    /** The header subtitle: the count against its ceiling, or a first-run / empty line. */
    val summary: String
        get() = when {
            !isLoaded -> "Reading your macros…"
            macros.isEmpty() -> "No macros yet"
            else -> "${macros.size} of ${MacroLibrary.MAX_MACROS}"
        }
}
