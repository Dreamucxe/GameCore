package com.gamecore.core.plugin.dev

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Part L gate: developer test mode always expires and auto-reverts. */
class DeveloperTestModeTest {

    @Test
    fun `a test session is active before its deadline`() {
        var t = 0L
        val dev = DeveloperTestMode(now = { t })
        dev.start("com.example.p")
        t = 30_000
        assertEquals("com.example.p", dev.activePluginId())
        assertTrue(dev.remainingMs() in 29_000..31_000)
    }

    @Test
    fun `a test session auto-expires after the 60s window`() {
        var t = 0L
        val dev = DeveloperTestMode(now = { t })
        dev.start("com.example.p")
        t = DeveloperTestMode.TEST_WINDOW_MS
        assertNull(dev.activePluginId())
        assertEquals(0L, dev.remainingMs())
    }

    @Test
    fun `pollExpiry reports the reverted plugin exactly once`() {
        var t = 0L
        val dev = DeveloperTestMode(now = { t })
        dev.start("com.example.p")
        assertNull(dev.pollExpiry())
        t = DeveloperTestMode.TEST_WINDOW_MS + 1
        assertEquals("com.example.p", dev.pollExpiry())
        assertNull(dev.pollExpiry()) // idempotent
    }

    @Test
    fun `stop reverts immediately`() {
        val dev = DeveloperTestMode(now = { 0L })
        dev.start("com.example.p")
        dev.stop()
        assertNull(dev.activePluginId())
        assertFalse(dev.isActiveAt(0L))
    }

    @Test
    fun `starting a new test replaces the previous one`() {
        var t = 0L
        val dev = DeveloperTestMode(now = { t })
        dev.start("a")
        t = 10_000
        dev.start("b")
        assertEquals("b", dev.activePluginId())
    }
}
