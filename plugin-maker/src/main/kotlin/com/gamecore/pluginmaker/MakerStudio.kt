package com.gamecore.pluginmaker

import com.gamecore.studio.core.StudioSession
import com.gamecore.studio.model.PluginDraft

/**
 * The maker's live studio draft.
 *
 * It lives outside the activity because two things in the same process need it: the editor, which
 * holds the visible copy, and the debug MCP bridge, which can read or replace it while the editor
 * is open. A process-lifetime object is the smallest scope that lets both see the same value; the
 * editor still autosaves it through the ordinary [com.gamecore.studio.core.DraftStore] path, so a
 * draft changed over MCP survives a restart like any other edit.
 *
 * The release app never starts a bridge; this object simply holds the same draft the editor would
 * have held in a `remember` block.
 */
object MakerStudio {
    val session: StudioSession = StudioSession(PluginDraft())
}
