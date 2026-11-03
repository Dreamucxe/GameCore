package com.gamecore.studio.core

import com.gamecore.studio.model.PluginDraft
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The one draft a running studio host is editing.
 *
 * The editor itself does not need this class: [com.gamecore.studio.ui.PluginStudioScreen] takes a
 * draft and hands a new one back, so a host can keep the value wherever it likes. The session
 * exists for the host that has a second reader — the debug MCP bridge — and needs both readers to
 * see the same draft without one overwriting the other.
 *
 * It is deliberately small and synchronous. [update] commits the value before it notifies anyone,
 * so a listener can always read [current] and get the draft it was told about. Listeners run on the
 * thread that called [update]; a UI listener must marshal to the main thread itself, while an HTTP
 * bridge can answer on its own worker thread. A listener that throws is isolated: one broken
 * observer must not make a committed draft look rejected to the caller.
 */
class StudioSession(initial: PluginDraft = PluginDraft()) {

    private val lock = Any()
    private var draft: PluginDraft = initial
    private val listeners = CopyOnWriteArrayList<(PluginDraft) -> Unit>()

    /** The draft as of this call. */
    fun current(): PluginDraft = synchronized(lock) { draft }

    /**
     * Replace the draft and notify observers when it actually changed.
     *
     * Equal values are ignored so the editor's own echo cannot cause a recomposition loop or a
     * second autosave. The new value is returned for convenience.
     */
    fun update(next: PluginDraft): PluginDraft {
        val changed = synchronized(lock) {
            if (next == draft) {
                false
            } else {
                draft = next
                true
            }
        }
        if (changed) {
            listeners.forEach { listener ->
                // A listener is an observer, not part of the commit. Its failure is its own.
                runCatching { listener(next) }
            }
        }
        return next
    }

    /** Register an observer. It is not called for the current value; read [current] first. */
    fun addListener(listener: (PluginDraft) -> Unit) {
        listeners.add(listener)
    }

    /** Remove an observer. Removing one that was never added is a no-op. */
    fun removeListener(listener: (PluginDraft) -> Unit) {
        listeners.remove(listener)
    }
}
