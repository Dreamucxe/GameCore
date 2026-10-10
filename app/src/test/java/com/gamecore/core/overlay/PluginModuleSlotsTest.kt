package com.gamecore.core.overlay

import com.gamecore.core.plugin.overlay.PluginOverlayModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the two halves of one cap to each other.
 *
 * How many plugin modules may be on screen at once is written down twice, and it has to be. The overlay
 * layer expresses it as windows — [OverlaySlot.PLUGIN_MODULES], because [OverlayWindows] holds one view
 * per slot and the service fills the slots it has. The plugin layer expresses it as a number,
 * [PluginOverlayModule.MAX_MODULES_ON_SCREEN], because the registry has to trim its list before it ever
 * reaches a window and the plugin model deliberately cannot see this enum: nothing under
 * `core.plugin.overlay` imports the overlay layer, which is what lets a module be parsed, stored and
 * previewed in the install dialog by code that never touches a window.
 *
 * So the duplication is the price of that boundary, and this is the test that makes it safe. The failure
 * it exists to prevent is quiet in both directions:
 *
 *  - **Slots raised, registry not.** A fourth slot is added, nothing fills it, and the author of the
 *    fourth module is left debugging a renderer that was never asked to draw it.
 *  - **Registry raised, slots not.** The registry starts handing over four modules and the fourth is
 *    dropped on the floor by a `forEachIndexed` over three slots — the same symptom, with the cause
 *    moved one layer away from where someone would look for it.
 *
 * Neither throws, neither logs, and both look like the plugin's fault.
 */
class PluginModuleSlotsTest {

    @Test
    fun `there is one window per module the registry is allowed to hand over`() {
        assertEquals(
            "OverlaySlot.PLUGIN_MODULES has ${OverlaySlot.PLUGIN_MODULES.size} slots but " +
                "PluginOverlayModule.MAX_MODULES_ON_SCREEN is ${PluginOverlayModule.MAX_MODULES_ON_SCREEN}; " +
                "raising one of these two means raising both",
            PluginOverlayModule.MAX_MODULES_ON_SCREEN,
            OverlaySlot.PLUGIN_MODULES.size,
        )
    }

    @Test
    fun `the slot list is every plugin-module slot in the enum`() {
        // Catches the half-step: a `PLUGIN_MODULE_4` declared in the enum and forgotten in the companion
        // list. The constant above would still agree with the list's size, so the first test would pass
        // while the new window was never drawn into. Derived from the enum rather than restated, so this
        // assertion cannot be satisfied by editing it.
        val declared = OverlaySlot.entries.filter { it.name.startsWith("PLUGIN_MODULE") }
        assertEquals(declared, OverlaySlot.PLUGIN_MODULES)
    }

    @Test
    fun `no slot is listed twice`() {
        // A duplicate would have two indices resolving to one window, so the second module would replace
        // the first on every reconcile and the user would see one of their two modules flicker away.
        assertEquals(OverlaySlot.PLUGIN_MODULES.size, OverlaySlot.PLUGIN_MODULES.toSet().size)
    }

    @Test
    fun `no window GameCore draws itself is a plugin slot`() {
        // The list is what the service hands third-party content, so a GameCore window appearing in it
        // would mean a plugin's tree rendered into the pill's or the panel's slot — replacing a window
        // the user's own settings asked for with one a plugin authored.
        val own = setOf(
            OverlaySlot.BUTTON,
            OverlaySlot.PANEL,
            OverlaySlot.QUICK_SHEET,
            OverlaySlot.PILL,
            OverlaySlot.CROSSHAIR,
            OverlaySlot.HUD,
            OverlaySlot.MAGNIFIER,
            OverlaySlot.REPLAY,
            OverlaySlot.SCOUT,
            OverlaySlot.HUNT,
            OverlaySlot.WHEEL,
            OverlaySlot.DOCK,
            OverlaySlot.DOCK_PANEL,
            OverlaySlot.TRIGGER_PLACEMENT,
            OverlaySlot.TRIGGER_MARKER,
        )
        assertTrue(
            "plugin slots overlap GameCore's own windows: ${OverlaySlot.PLUGIN_MODULES.intersect(own)}",
            OverlaySlot.PLUGIN_MODULES.none { it in own },
        )
    }
}
