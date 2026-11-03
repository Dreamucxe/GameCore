package com.gamecore.studio.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.gamecore.studio.model.PluginDraft

/**
 * The plugin editor.
 *
 * One screen, two hosts: the standalone Plugin Maker app and GameCore's own "Create plugin"
 * entry. The screen owns the whole flow — the form, the live preview, the validation and the
 * save-file launcher — so a host only has to hold the draft and say where to go afterwards.
 *
 * The contract is deliberately small:
 *
 *  - [draft] is the state and [onDraftChange] is the only way to change it. The screen never
 *    mutates the draft in place, so a host can keep it in a view model, in a saved state, or
 *    nowhere at all.
 *  - The screen saves through the storage access framework, which means the user picks the
 *    file name and the folder. No storage permission is needed on any Android version, and
 *    the file lands wherever the user expects to find it.
 *  - [onSaved] is called with the file name once the package is written; [onOpenGameCore] is
 *    called when the user asks to jump to GameCore.
 *
 * A host that wants the editor in a different shape — a dialog, a separate navigation
 * destination — can pass its own `modifier` and wrap it in whatever chrome it likes.
 */
@Composable
fun PluginStudioScreen(
    draft: PluginDraft,
    onDraftChange: (PluginDraft) -> Unit,
    modifier: Modifier = Modifier,
    onSaved: (fileName: String) -> Unit = {},
    /** Offered only by a host that is not already GameCore; null hides the jump button. */
    onOpenGameCore: (() -> Unit)? = null,
    /** When the host shows the studio as a dialog, this closes it. Null hides the Close action. */
    onClose: (() -> Unit)? = null,
) {
    // The editor is implemented in StudioEditor.kt; this entry point is the stable surface.
    StudioEditor(
        draft = draft,
        onDraftChange = onDraftChange,
        modifier = modifier,
        onSaved = onSaved,
        onOpenGameCore = onOpenGameCore,
        onClose = onClose,
    )
}
