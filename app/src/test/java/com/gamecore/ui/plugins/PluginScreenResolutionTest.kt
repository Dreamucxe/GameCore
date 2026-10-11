package com.gamecore.ui.plugins

import com.gamecore.core.plugin.InstalledPlugin
import com.gamecore.core.plugin.PluginApi
import com.gamecore.core.plugin.PluginGrant
import com.gamecore.core.plugin.PluginManifest
import com.gamecore.core.plugin.PluginOrigin
import com.gamecore.core.plugin.PluginPermission
import com.gamecore.core.plugin.PluginSection
import com.gamecore.core.plugin.RequestedPermission
import com.gamecore.core.plugin.ui.ComponentNode
import com.gamecore.core.plugin.ui.ComponentType
import com.gamecore.core.plugin.ui.PluginScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides whether a plugin screen route may draw a plugin's content, tested with no
 * Android at all — the reason [resolvePluginScreen] is a pure function over plain inputs. A screen
 * drawn one condition past the point it should have been is a trust failure, not a cosmetic one, so
 * every gate gets a case, and the worst-first ordering gets a case of its own: a route reached from a
 * stale back stack after several things changed at once must name the most fundamental reason.
 */
class PluginScreenResolutionTest {

    private val screenId = "overview"

    private fun manifest(
        api: Int = PluginApi.CURRENT,
        declares: Set<PluginSection> = setOf(PluginSection.SCREENS),
        permissions: List<PluginPermission> = listOf(PluginPermission.CHANGE_UI),
    ): PluginManifest = PluginManifest(
        id = "com.example.plugin",
        name = "Example",
        version = 1,
        versionName = "1.0",
        api = api,
        minHostVersion = 1,
        developer = "Tester",
        description = "Adds a screen.",
        permissions = permissions.map { RequestedPermission(it, emptySet()) },
        declares = declares,
        changelog = "",
    )

    private fun plugin(
        manifest: PluginManifest = manifest(),
        enabled: Boolean = true,
        suspended: Boolean = false,
        grant: PluginGrant = PluginGrant(manifest.id, mapOf(PluginPermission.CHANGE_UI to emptySet())),
    ): InstalledPlugin = InstalledPlugin(
        manifest = manifest,
        sha256 = "0".repeat(64),
        origin = PluginOrigin.SIDELOADED,
        enabled = enabled,
        suspended = suspended,
        grant = grant,
    )

    private val screen = PluginScreen(
        id = screenId,
        title = "Overview",
        root = ComponentNode(ComponentType.TEXT, emptyMap()),
        icon = null,
    )

    private fun resolve(
        plugin: InstalledPlugin? = plugin(),
        killSwitchOn: Boolean = false,
        screens: List<PluginScreen> = listOf(screen),
        id: String = screenId,
    ) = resolvePluginScreen(plugin, killSwitchOn, screens, id)

    private fun reasonOf(r: PluginScreenResolution) =
        (r as PluginScreenResolution.Unavailable).reason

    // ------------------------------------------------------------------------------ the happy path

    @Test
    fun `an active, granted plugin with the screen resolves to Available`() {
        val r = resolve()
        assertTrue(r is PluginScreenResolution.Available)
        assertSame(screen, (r as PluginScreenResolution.Available).screen)
    }

    @Test
    fun `the id is matched trimmed and lower-cased`() {
        val r = resolve(id = "  OVERVIEW  ")
        assertTrue(r is PluginScreenResolution.Available)
    }

    // ------------------------------------------------------------------------------ the gates

    @Test
    fun `a null plugin is REMOVED`() {
        assertEquals(PluginScreenResolution.Reason.REMOVED, reasonOf(resolve(plugin = null)))
    }

    @Test
    fun `an incompatible build is INCOMPATIBLE`() {
        // api above the host's CURRENT cannot run here.
        val p = plugin(manifest = manifest(api = PluginApi.CURRENT + 1))
        assertEquals(PluginScreenResolution.Reason.INCOMPATIBLE, reasonOf(resolve(plugin = p)))
    }

    @Test
    fun `the kill switch is KILL_SWITCH`() {
        assertEquals(PluginScreenResolution.Reason.KILL_SWITCH, reasonOf(resolve(killSwitchOn = true)))
    }

    @Test
    fun `a disabled plugin is DISABLED`() {
        assertEquals(
            PluginScreenResolution.Reason.DISABLED,
            reasonOf(resolve(plugin = plugin(enabled = false))),
        )
    }

    @Test
    fun `a suspended plugin is DISABLED`() {
        assertEquals(
            PluginScreenResolution.Reason.DISABLED,
            reasonOf(resolve(plugin = plugin(suspended = true))),
        )
    }

    @Test
    fun `CHANGE_UI absent from the grant is NOT_GRANTED`() {
        assertEquals(
            PluginScreenResolution.Reason.NOT_GRANTED,
            reasonOf(resolve(plugin = plugin(grant = PluginGrant.none("com.example.plugin")))),
        )
    }

    @Test
    fun `a plugin that declares no screens section is NOT_FOUND`() {
        val p = plugin(manifest = manifest(declares = emptySet()))
        assertEquals(PluginScreenResolution.Reason.NOT_FOUND, reasonOf(resolve(plugin = p)))
    }

    @Test
    fun `a screen id that is not in the list is NOT_FOUND`() {
        assertEquals(
            PluginScreenResolution.Reason.NOT_FOUND,
            reasonOf(resolve(id = "settings")),
        )
    }

    // ------------------------------------------------------------------------------ precedence

    @Test
    fun `the kill switch outranks a merely disabled plugin`() {
        val r = resolve(plugin = plugin(enabled = false), killSwitchOn = true)
        assertEquals(PluginScreenResolution.Reason.KILL_SWITCH, reasonOf(r))
    }

    @Test
    fun `removal outranks everything`() {
        val r = resolve(plugin = null, killSwitchOn = true)
        assertEquals(PluginScreenResolution.Reason.REMOVED, reasonOf(r))
    }

    @Test
    fun `the message is the reason's own sentence`() {
        val r = resolve(plugin = null) as PluginScreenResolution.Unavailable
        assertEquals(PluginScreenResolution.Reason.REMOVED.message, r.message)
    }
}
