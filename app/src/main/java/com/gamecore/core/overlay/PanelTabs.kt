package com.gamecore.core.overlay

/**
 * The four tabs of the full panel (spec §5), and — the load-bearing part — the pure map of which audited
 * control lives under each one.
 *
 * The spec's hard promise for the full panel is "every control from the audit is reachable, nothing
 * removed". That is a statement about *coverage*, and coverage is exactly the kind of thing that rots
 * silently: a control gets added to the audit, nobody assigns it a tab, and it quietly vanishes from the
 * panel with no error. So the tab→controls assignment lives here as data, not scattered through the
 * composable's `when` branches, and [PanelReachability.everyControlReachableOnce] lets a unit test assert
 * the invariant on the JVM without building a UI (spec §10 "reachability").
 *
 * These are stable ids, not display strings: they are the contract between the audit, the reachability
 * test, and the panel. Renaming a label never moves a control; only editing this map does.
 */
enum class PanelTab(val label: String) {
    DISPLAY("Display"),
    OVERLAYS("Overlays"),
    CAPTURE("Capture"),
    SESSION("Session"),
    ;

    companion object {
        /** The tab the panel opens on. */
        val DEFAULT: PanelTab = DISPLAY

        /**
         * Parse a stored tab id back to a tab, or null for one this build does not know — the
         * [QuickToggle.of] discipline, so a settings file written by a newer build falls back to
         * [DEFAULT] at the call site rather than crashing.
         */
        fun of(name: String?): PanelTab? = entries.firstOrNull { it.name == name }
    }
}

/**
 * The audited controls the full panel must surface, each assigned to exactly one [PanelTab].
 *
 * The lists mirror spec §5's tab descriptions and the control inventory in `GC_Pill_Audit.md`:
 *  - Display: brightness + the rows that open a value/sub-view (colour, gamma/contrast/hue, aspect ratio,
 *    refresh rate, rotation).
 *  - Overlays: the stats pill, crosshair, HUD and their layout controls.
 *  - Capture: screenshot, record, torch.
 *  - Session: silence/DND, end session, open GameCore, and the media row.
 *
 * Ids are stable strings shared with the reachability test. The panel composable renders a tab by looking
 * its controls up here, so a control cannot be drawn under a tab it was not assigned to, and cannot be
 * forgotten without [everyControlReachableOnce] going red.
 */
object PanelReachability {

    val controlsByTab: Map<PanelTab, List<String>> = mapOf(
        PanelTab.DISPLAY to listOf(
            "brightness",
            "colour",
            "gamma_contrast_hue",
            "aspect_ratio",
            "refresh_rate",
            "rotation",
        ),
        PanelTab.OVERLAYS to listOf(
            "stats_pill",
            "crosshair",
            "hud",
            "overlay_layout",
        ),
        PanelTab.CAPTURE to listOf(
            "screenshot",
            "record",
            "torch",
        ),
        PanelTab.SESSION to listOf(
            "silence_dnd",
            "end_session",
            "open_gamecore",
            "media_row",
        ),
    )

    /** Every audited control the panel is responsible for, across all tabs. */
    val allControls: List<String> = controlsByTab.values.flatten()

    /** The tab a control is reachable from, or null if it is not assigned to any (a coverage hole). */
    fun tabOf(controlId: String): PanelTab? =
        controlsByTab.entries.firstOrNull { controlId in it.value }?.key

    /**
     * True when [expected] is covered exactly: every expected control is assigned to some tab, and no
     * control is assigned to two tabs (which would be reachable twice, an ambiguity the test also catches).
     * The reachability test passes the audit's own control list as [expected].
     */
    fun everyControlReachableOnce(expected: Collection<String>): Boolean {
        val flat = allControls
        val noDuplicates = flat.size == flat.toSet().size
        val coversAll = flat.toSet() == expected.toSet()
        return noDuplicates && coversAll
    }
}
