package com.gamecore.data.plugin

import com.gamecore.core.plugin.PluginGrant
import com.gamecore.core.plugin.PluginPermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The storage half of the plugin trust boundary, proved on the JVM.
 *
 * A grant is the authority every plugin API call is checked against, so the rules that decide what a
 * stored grant *means* — and which keys and values a plugin may write — must be provable without a
 * device. That is the whole reason [PluginGrantCodec] is separate from `SecurePluginGrantStore`: the
 * store is `getString`/`putString` around a Keystore-backed file, while everything below is a decision
 * about authority.
 *
 * Each test states one of those decisions: a grant survives a round trip, a permission this build no
 * longer knows is forgotten rather than allowed to invalidate the rest, a damaged entry reads as no
 * consent at all, and a key belongs to exactly one plugin — including in the near-miss case where one
 * plugin id is a prefix of another.
 */
class PluginGrantCodecTest {

    private val pluginId = "com.example.plugin"

    // ------------------------------------------------------------------------------------ grants

    @Test
    fun `round trips permissions and their scopes`() {
        val grant = PluginGrant(
            pluginId = pluginId,
            granted = mapOf(
                PluginPermission.READ_APPROVED_DATA to setOf("sessions", "games"),
                PluginPermission.CHANGE_UI to emptySet(),
            ),
            advancedAccess = false,
        )

        val decoded = PluginGrantCodec.decode(pluginId, PluginGrantCodec.encode(grant))

        assertEquals(pluginId, decoded.pluginId)
        assertEquals(setOf("sessions", "games"), decoded.scopes(PluginPermission.READ_APPROVED_DATA))
        // A granted permission with no scopes is still granted: the empty set is the aperture, not the
        // absence of the permission.
        assertTrue(decoded.has(PluginPermission.CHANGE_UI))
        assertEquals(emptySet<String>(), decoded.scopes(PluginPermission.CHANGE_UI))
        assertFalse(decoded.has(PluginPermission.SYSTEM_ACTIONS))
    }

    @Test
    fun `normalises scopes to what hasScope compares against`() {
        val grant = PluginGrant(
            pluginId = pluginId,
            granted = mapOf(PluginPermission.READ_APPROVED_DATA to setOf("  Sessions ")),
        )

        val decoded = PluginGrantCodec.decode(pluginId, PluginGrantCodec.encode(grant))

        // The bug this rules out: a grant that reads back as granted in the UI and then denies at every
        // scoped call, because the stored scope was never trimmed and lowercased.
        assertTrue(decoded.hasScope(PluginPermission.READ_APPROVED_DATA, "sessions"))
        assertTrue(decoded.hasScope(PluginPermission.READ_APPROVED_DATA, "SESSIONS"))
    }

    @Test
    fun `drops a permission id this build does not know`() {
        val raw = """
            {"permissions":{"read_approved_data":["sessions"],"teleport":["everywhere"],"network":[]},
             "advancedAccess":false}
        """.trimIndent()

        val decoded = PluginGrantCodec.decode(pluginId, raw)

        assertEquals(1, decoded.granted.size)
        assertEquals(setOf("sessions"), decoded.scopes(PluginPermission.READ_APPROVED_DATA))
    }

    @Test
    fun `keeps the advanced access opt in`() {
        val grant = PluginGrant(
            pluginId = pluginId,
            granted = mapOf(PluginPermission.SYSTEM_ACTIONS to setOf("refresh_rate")),
            advancedAccess = true,
        )

        val decoded = PluginGrantCodec.decode(pluginId, PluginGrantCodec.encode(grant))

        assertTrue(decoded.advancedAccess)
        assertTrue(decoded.hasScope(PluginPermission.SYSTEM_ACTIONS, "refresh_rate"))
    }

    @Test
    fun `reads a damaged entry as no consent at all`() {
        // Half-written, truncated, and the wrong JSON shape entirely. None of these may throw on a path
        // that runs while a plugin screen is being built, and none may read as a grant.
        for (raw in listOf("{not json", """{"permissions":{"read_approved_data":["sessions"]""", "[]", "")) {
            val decoded = PluginGrantCodec.decode(pluginId, raw)
            assertEquals(pluginId, decoded.pluginId)
            assertEquals(emptyMap<PluginPermission, Set<String>>(), decoded.granted)
            assertFalse(decoded.advancedAccess)
        }
        assertEquals(emptyMap<PluginPermission, Set<String>>(), PluginGrantCodec.decode(pluginId, null).granted)
    }

    // ------------------------------------------------------------------------------------ keys

    @Test
    fun `refuses a key outside the plugin's namespace`() {
        assertFalse(PluginGrantCodec.isAcceptableKey(pluginId, "plugin.com.other.plugin.size"))
        // Not namespaced at all, and the name of a real GameCore setting.
        assertFalse(PluginGrantCodec.isAcceptableKey(pluginId, "theme"))
        assertFalse(PluginGrantCodec.isAcceptableKey(pluginId, "plugin.size"))

        assertTrue(PluginGrantCodec.isAcceptableKey(pluginId, "plugin.com.example.plugin.size"))
    }

    @Test
    fun `refuses a key the grammar does not admit`() {
        val namespace = "plugin.$pluginId."

        assertFalse(PluginGrantCodec.isAcceptableKey(pluginId, namespace + "window size"))
        assertFalse(PluginGrantCodec.isAcceptableKey(pluginId, namespace + "window/size"))
        // An empty or dotted segment, which a dotted-key store could read as traversal.
        assertFalse(PluginGrantCodec.isAcceptableKey(pluginId, namespace + ".size"))
        assertFalse(PluginGrantCodec.isAcceptableKey(pluginId, namespace + "a..b"))
        assertFalse(PluginGrantCodec.isAcceptableKey(pluginId, namespace))
        assertFalse(PluginGrantCodec.isAcceptableKey(pluginId, namespace + "a".repeat(129)))

        assertTrue(PluginGrantCodec.isAcceptableKey(pluginId, namespace + "window.size-2_a"))
    }

    @Test
    fun `refuses a near miss namespace in both directions`() {
        // The case a prefix check alone gets wrong: plugin `a.bc` and a key inside `a.b`'s namespace.
        assertFalse(PluginGrantCodec.isAcceptableKey("a.bc", "plugin.a.b.size"))
        assertFalse(PluginGrantCodec.isAcceptableKey("a.b", "plugin.a.bc.size"))

        assertTrue(PluginGrantCodec.isAcceptableKey("a.b", "plugin.a.b.size"))
        assertTrue(PluginGrantCodec.isAcceptableKey("a.bc", "plugin.a.bc.size"))
    }

    @Test
    fun `groups a qualified key by the plugin it could belong to`() {
        // What the store counts a write against when it is handed a key and no plugin id.
        assertEquals("plugin.com.example.", PluginGrantCodec.budgetGroupOf("plugin.com.example.plugin.size"))
        assertEquals(
            PluginGrantCodec.budgetGroupOf("plugin.com.example.plugin.a"),
            PluginGrantCodec.budgetGroupOf("plugin.com.example.plugin.b"),
        )

        // Not a namespaced key: a GameCore setting name, a bare prefix, an id with one segment, and a
        // key with a traversal segment all fail to resolve to a group, which is how the store refuses
        // the write.
        assertNull(PluginGrantCodec.budgetGroupOf("allow_elevated"))
        assertNull(PluginGrantCodec.budgetGroupOf("plugin."))
        assertNull(PluginGrantCodec.budgetGroupOf("plugin.single.size"))
        assertNull(PluginGrantCodec.budgetGroupOf("plugin.com.example..size"))
    }

    // ------------------------------------------------------------------------------------ values

    @Test
    fun `refuses a value past the length cap`() {
        assertTrue(PluginGrantCodec.isAcceptableValue(""))
        assertTrue(PluginGrantCodec.isAcceptableValue("a".repeat(PluginGrantCodec.MAX_VALUE_LENGTH)))
        assertFalse(PluginGrantCodec.isAcceptableValue("a".repeat(PluginGrantCodec.MAX_VALUE_LENGTH + 1)))
    }
}
