package com.gamecore.core.plugin.settings

import com.gamecore.core.plugin.ApiResult
import com.gamecore.core.plugin.PluginApiFacade
import com.gamecore.core.plugin.PluginAuditLog
import com.gamecore.core.plugin.PluginGrant
import com.gamecore.core.plugin.PluginGrantStore
import com.gamecore.core.plugin.PluginManifest
import com.gamecore.core.plugin.PluginNamespace
import com.gamecore.core.plugin.PluginPermission
import com.gamecore.core.plugin.PluginPermissionGate
import com.gamecore.core.plugin.PluginRateLimiter
import com.gamecore.core.plugin.PluginRuntimeState
import com.gamecore.core.plugin.RequestedPermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 gate (host runtime surface): the typed get/set funnels every call through
 * [PluginApiFacade] (gate -> rate limit -> audit), keys writes into the plugin's own namespace, coerces
 * and clamps against the trusted schema, and falls back to the default on absent or stale data. The
 * grant store is faked in-memory because the real one is device-only.
 */
class PluginSettingsSurfaceTest {

    private class FakeGrantStore : PluginGrantStore {
        val values = HashMap<String, String>()
        override fun readValue(qualifiedKey: String): String? = values[qualifiedKey]
        override fun writeValue(qualifiedKey: String, value: String?) {
            if (value == null) values.remove(qualifiedKey) else values[qualifiedKey] = value
        }
        override fun clearNamespace(pluginId: String) {
            values.keys.filter { it.startsWith(PluginNamespace.of(pluginId)) }.toList().forEach { values.remove(it) }
        }
        override fun grantFor(pluginId: String): PluginGrant = PluginGrant.none(pluginId)
        override fun saveGrant(grant: PluginGrant) {}
        override fun clearGrant(pluginId: String) {}
        override fun isEnabled(pluginId: String): Boolean = true
        override fun setEnabled(pluginId: String, enabled: Boolean) {}
        override fun isSuspended(pluginId: String): Boolean = false
        override fun suspend(pluginId: String, detail: String) {}
        override fun clearSuspension(pluginId: String) {}
        override fun isSystemKillSwitchOn(): Boolean = false
        override fun setSystemKillSwitch(on: Boolean) {}
    }

    private val schema = PluginSettingsSchema(
        schema = 1,
        definitions = listOf(
            SettingDef.IntDef("hint", defaultValue = 120, min = 60, max = 240),
            SettingDef.BoolDef("haptics", defaultValue = true),
            SettingDef.EnumDef("units", options = listOf("metric", "imperial"), defaultValue = "metric"),
            SettingDef.StringDef("label", defaultValue = "", maxLength = 16),
        ),
    )

    private fun facade(): PluginApiFacade =
        PluginApiFacade(PluginPermissionGate(), PluginRateLimiter(now = { 0L }), PluginAuditLog(), now = { 0L })

    private fun manifest(id: String, requested: Boolean): PluginManifest = PluginManifest(
        id = id, name = "P", version = 1, versionName = "1.0", api = 3, minHostVersion = 1,
        developer = "T", description = "",
        permissions = if (requested) listOf(RequestedPermission(PluginPermission.STORE_DATA, emptySet())) else emptyList(),
        declares = emptySet(), changelog = "",
    )

    private fun state(
        id: String = "com.a",
        requested: Boolean = true,
        granted: Boolean = true,
        enabled: Boolean = true,
        suspended: Boolean = false,
        killSwitch: Boolean = false,
    ): PluginRuntimeState {
        val grantMap: Map<PluginPermission, Set<String>> =
            if (granted) mapOf(PluginPermission.STORE_DATA to emptySet()) else emptyMap()
        return PluginRuntimeState.of(
            manifest(id, requested), PluginGrant(id, grantMap),
            enabled = enabled, suspended = suspended, systemKillSwitch = killSwitch,
        )
    }

    @Test
    fun `set then get round-trips through the plugin's own namespace`() {
        val store = FakeGrantStore()
        val s = PluginSettings(facade(), store)
        val st = state("com.a")

        assertEquals(SettingValue.IntValue(100), (s.set(st, schema, "hint", SettingValue.IntValue(100)) as ApiResult.Ok).value)
        assertEquals(SettingValue.IntValue(100), (s.get(st, schema, "hint") as ApiResult.Ok).value)
        // the value lives under the plugin's qualified key, nowhere else
        assertEquals("100", store.values[PluginNamespace.qualify("com.a", "hint")])
    }

    @Test
    fun `an absent key reads back the definition's default`() {
        val s = PluginSettings(facade(), FakeGrantStore())
        assertEquals(SettingValue.BoolValue(true), (s.get(state(), schema, "haptics") as ApiResult.Ok).value)
    }

    @Test
    fun `a write clamps a numeric value to the declared bounds`() {
        val store = FakeGrantStore()
        val s = PluginSettings(facade(), store)
        val st = state("com.a")
        assertEquals(SettingValue.IntValue(240), (s.set(st, schema, "hint", SettingValue.IntValue(9999)) as ApiResult.Ok).value)
        assertEquals("240", store.values[PluginNamespace.qualify("com.a", "hint")])
    }

    @Test
    fun `a read coerces a stale or out-of-range stored value`() {
        val store = FakeGrantStore()
        val s = PluginSettings(facade(), store)
        val st = state("com.a")
        val key = PluginNamespace.qualify("com.a", "hint")!!
        // out of range on disk -> clamped on read
        store.values[key] = "99999"
        assertEquals(SettingValue.IntValue(240), (s.get(st, schema, "hint") as ApiResult.Ok).value)
        // no longer parseable -> falls back to default
        store.values[key] = "not-a-number"
        assertEquals(SettingValue.IntValue(120), (s.get(st, schema, "hint") as ApiResult.Ok).value)
    }

    @Test
    fun `a write of the wrong type is an error`() {
        val s = PluginSettings(facade(), FakeGrantStore())
        assertTrue(s.set(state(), schema, "hint", SettingValue.StringValue("x")) is ApiResult.Error)
    }

    @Test
    fun `an unknown setting id is an error`() {
        val s = PluginSettings(facade(), FakeGrantStore())
        assertTrue(s.get(state(), schema, "nope") is ApiResult.Error)
    }

    @Test
    fun `a write is denied when STORE_DATA is requested but not granted`() {
        val s = PluginSettings(facade(), FakeGrantStore())
        val r = s.set(state(granted = false), schema, "hint", SettingValue.IntValue(100))
        assertEquals(com.gamecore.core.plugin.DenyReason.NOT_GRANTED, (r as ApiResult.Denied).reason)
    }

    @Test
    fun `a call is denied when the manifest never requested STORE_DATA`() {
        val s = PluginSettings(facade(), FakeGrantStore())
        val r = s.get(state(requested = false, granted = false), schema, "hint")
        assertEquals(com.gamecore.core.plugin.DenyReason.NOT_REQUESTED, (r as ApiResult.Denied).reason)
    }

    @Test
    fun `the system kill switch denies every call`() {
        val s = PluginSettings(facade(), FakeGrantStore())
        val r = s.get(state(killSwitch = true), schema, "hint")
        assertEquals(com.gamecore.core.plugin.DenyReason.SYSTEM_KILL_SWITCH, (r as ApiResult.Denied).reason)
    }

    @Test
    fun `writes beyond the rate limit are refused`() {
        val s = PluginSettings(facade(), FakeGrantStore(), writeLimit = 2, windowMs = 1000)
        val st = state("com.a")
        repeat(2) { assertTrue(s.set(st, schema, "hint", SettingValue.IntValue(100)) is ApiResult.Ok) }
        assertTrue(s.set(st, schema, "hint", SettingValue.IntValue(100)) is ApiResult.RateLimited)
    }

    @Test
    fun `one plugin cannot read another plugin's stored value`() {
        val store = FakeGrantStore()
        val s = PluginSettings(facade(), store)
        // plugin A writes
        s.set(state("com.a"), schema, "hint", SettingValue.IntValue(200))
        // plugin B, a distinct namespace, sees only the default
        assertEquals(SettingValue.IntValue(120), (s.get(state("com.b"), schema, "hint") as ApiResult.Ok).value)
        // the stored key belongs to A's namespace and not B's
        val keyA = PluginNamespace.qualify("com.a", "hint")!!
        assertTrue(PluginNamespace.belongsTo("com.a", keyA))
        assertTrue(!PluginNamespace.belongsTo("com.b", keyA))
    }
}
