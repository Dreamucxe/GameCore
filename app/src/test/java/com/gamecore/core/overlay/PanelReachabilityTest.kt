package com.gamecore.core.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec §10 "reachability": every audited control the full panel is responsible for must be reachable
 * from exactly one tab, and nothing may be silently removed. These are pure-JVM assertions over
 * [PanelReachability]'s data map — no UI, no Android.
 */
class PanelReachabilityTest {

    @Test
    fun everyControlIsReachableFromExactlyOneTab() {
        for (control in PanelReachability.allControls) {
            assertNotNull("no tab for control '$control'", PanelReachability.tabOf(control))
        }
        // "exactly one": no id may appear in two tabs' lists, so the flattened list has no duplicates.
        val flat = PanelReachability.allControls
        assertEquals("a control is assigned to more than one tab", flat.size, flat.toSet().size)
    }

    @Test
    fun allControlsAreSelfConsistent() {
        assertTrue(PanelReachability.everyControlReachableOnce(PanelReachability.allControls))
    }

    @Test
    fun missingExpectedControlFailsReachability() {
        val withPhantom = PanelReachability.allControls + "phantom_control"
        assertFalse(PanelReachability.everyControlReachableOnce(withPhantom))
    }

    @Test
    fun mapControlNotInExpectedFailsReachability() {
        val subset = PanelReachability.allControls.dropLast(1)
        assertFalse(PanelReachability.everyControlReachableOnce(subset))
    }

    @Test
    fun ofRoundTripsKnownNameAndNullsUnknown() {
        assertEquals(PanelTab.DISPLAY, PanelTab.of(PanelTab.DISPLAY.name))
        assertNull(PanelTab.of("NOT_A_REAL_TAB"))
    }

    @Test
    fun defaultTabIsDisplay() {
        assertEquals(PanelTab.DISPLAY, PanelTab.DEFAULT)
    }

    @Test
    fun everyTabHasAtLeastOneControl() {
        for (tab in PanelTab.entries) {
            val controls = PanelReachability.controlsByTab[tab]
            assertNotNull("no controls list for tab $tab", controls)
            assertTrue("tab $tab has no controls", controls!!.isNotEmpty())
        }
    }
}
