package com.gamecore.core.plugin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Part H gate: the supervisor's crash-loop and runtime-failure guards. */
class PluginSupervisorTest {

    private class FakeStore : PluginGrantStore {
        val enabled = HashMap<String, Boolean>()
        val suspended = HashMap<String, String?>()
        override fun grantFor(pluginId: String) = PluginGrant.none(pluginId)
        override fun saveGrant(grant: PluginGrant) {}
        override fun clearGrant(pluginId: String) {}
        override fun isEnabled(pluginId: String) = enabled[pluginId] ?: true
        override fun setEnabled(pluginId: String, enabled: Boolean) { this.enabled[pluginId] = enabled }
        override fun isSuspended(pluginId: String) = suspended[pluginId] != null
        override fun suspend(pluginId: String, detail: String) { suspended[pluginId] = detail }
        override fun clearSuspension(pluginId: String) { suspended.remove(pluginId) }
        override fun isSystemKillSwitchOn() = false
        override fun setSystemKillSwitch(on: Boolean) {}
        override fun readValue(qualifiedKey: String): String? = null
        override fun writeValue(qualifiedKey: String, value: String?) {}
        override fun clearNamespace(pluginId: String) {}
    }

    @Test
    fun `two consecutive startup crashes disables the plugin`() {
        val store = FakeStore()
        val sup = PluginSupervisor(store)
        assertFalse(sup.onStartupCrashed("p"))
        assertTrue(sup.onStartupCrashed("p"))
        assertFalse(store.isEnabled("p"))
    }

    @Test
    fun `a clean start resets the crash counter`() {
        val store = FakeStore()
        val sup = PluginSupervisor(store)
        sup.onStartupCrashed("p")
        sup.onStartupSucceeded("p")
        assertFalse(sup.onStartupCrashed("p")) // back to 1, not 2
        assertTrue(store.isEnabled("p"))
    }

    @Test
    fun `enough runtime failures in the window suspends the plugin`() {
        var t = 0L
        val store = FakeStore()
        val sup = PluginSupervisor(
            store,
            now = { t },
            failureThreshold = 3,
            failureWindowMs = 1000,
            quarantineThreshold = 100,
        )
        assertFalse(sup.onRuntimeFailure("p", "x"))
        assertFalse(sup.onRuntimeFailure("p", "x"))
        assertTrue(sup.onRuntimeFailure("p", "x"))
        assertTrue(store.isSuspended("p"))
    }

    @Test
    fun `failures spread beyond the window do not suspend`() {
        var t = 0L
        val store = FakeStore()
        val sup = PluginSupervisor(
            store,
            now = { t },
            failureThreshold = 3,
            failureWindowMs = 1000,
            quarantineThreshold = 100,
        )
        sup.onRuntimeFailure("p", "x")
        t = 2000
        sup.onRuntimeFailure("p", "x")
        t = 4000
        assertFalse(sup.onRuntimeFailure("p", "x"))
        assertFalse(store.isSuspended("p"))
    }
}
