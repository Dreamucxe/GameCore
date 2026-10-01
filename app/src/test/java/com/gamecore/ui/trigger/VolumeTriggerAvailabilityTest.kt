package com.gamecore.ui.trigger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one rule the volume-button trigger turns on: it fires only with BOTH the accessibility service and
 * Shizuku, and when it cannot fire it says which of the two is missing rather than a blank "unavailable".
 *
 * Pinned here rather than on a device because [describe] is a pure function of two booleans, and a silent
 * change to its wording — or to which gap it names first — is exactly the kind of dishonest control the
 * app's §24 honesty rule exists to prevent.
 */
class VolumeTriggerAvailabilityTest {

    @Test
    fun `both grants present is usable with no reason`() {
        val a = VolumeTriggerAvailability(accessibilityEnabled = true, shizukuGranted = true)
        assertTrue(a.isUsable)
        assertNull(describe(a))
        assertNull(a.reason)
    }

    @Test
    fun `accessibility missing names the accessibility service`() {
        val a = VolumeTriggerAvailability(accessibilityEnabled = false, shizukuGranted = true)
        assertFalse(a.isUsable)
        assertEquals("Enable GameCore's accessibility service to read volume keys", describe(a))
    }

    @Test
    fun `shizuku missing names shizuku`() {
        val a = VolumeTriggerAvailability(accessibilityEnabled = true, shizukuGranted = false)
        assertFalse(a.isUsable)
        assertEquals("Grant Shizuku to inject taps", describe(a))
    }

    @Test
    fun `both missing names both, accessibility first`() {
        val a = VolumeTriggerAvailability(accessibilityEnabled = false, shizukuGranted = false)
        assertFalse(a.isUsable)
        val reason = requireNotNull(describe(a))
        assertTrue("names accessibility", reason.contains("accessibility service"))
        assertTrue("names Shizuku", reason.contains("Shizuku"))
        assertTrue(
            "accessibility should be named before Shizuku",
            reason.indexOf("accessibility") < reason.indexOf("Shizuku"),
        )
    }

    /** The convenience property must never drift from the helper it delegates to. */
    @Test
    fun `reason property is exactly describe for every combination`() {
        for (accessibility in listOf(true, false)) {
            for (shizuku in listOf(true, false)) {
                val a = VolumeTriggerAvailability(accessibility, shizuku)
                assertEquals(describe(a), a.reason)
            }
        }
    }
}
