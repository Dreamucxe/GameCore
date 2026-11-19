package com.gamecore.ui.themelibrary

import com.gamecore.core.plugin.InstalledPlugin
import com.gamecore.core.plugin.PluginApi
import com.gamecore.core.plugin.PluginGrant
import com.gamecore.core.plugin.PluginManifest
import com.gamecore.core.plugin.PluginOrigin
import com.gamecore.core.plugin.PluginPermission
import com.gamecore.core.plugin.PluginSection
import com.gamecore.core.plugin.RequestedPermission
import com.gamecore.core.plugin.theme.PluginTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Theme Library's two decisions that are easy to get quietly wrong.
 *
 * Which themes a lens shows, and in what order, is pure logic over plain data — no registry, no
 * Compose, no device. That is deliberate: the active theme disappearing under the "Dark" filter, or a
 * search that only matches the theme's name while the user typed the plugin's, are failures nobody
 * would see in a build log. They are visible only as a screen that answers the wrong question.
 */
class ThemeLibraryLogicTest {

    private fun manifest(
        id: String = "com.example.theme",
        name: String = "Example",
        developer: String = "Tester",
        api: Int = PluginApi.CURRENT,
        minHostVersion: Int = 1,
        declares: Set<PluginSection> = setOf(PluginSection.THEME),
    ): PluginManifest = PluginManifest(
        id = id,
        name = name,
        version = 1,
        versionName = "1.0",
        api = api,
        minHostVersion = minHostVersion,
        developer = developer,
        description = "A theme.",
        permissions = listOf(RequestedPermission(PluginPermission.CHANGE_UI, emptySet())),
        declares = declares,
        changelog = "",
    )

    private fun installed(
        manifest: PluginManifest = manifest(),
        enabled: Boolean = true,
        grant: PluginGrant = PluginGrant(
            pluginId = manifest.id,
            granted = mapOf(PluginPermission.CHANGE_UI to emptySet()),
        ),
        suspended: Boolean = false,
        suspensionDetail: String? = null,
        quarantined: Boolean = false,
    ): InstalledPlugin = InstalledPlugin(
        manifest = manifest,
        sha256 = "0".repeat(64),
        origin = PluginOrigin.BUNDLED,
        enabled = enabled,
        suspended = suspended,
        suspensionDetail = suspensionDetail,
        grant = grant,
        quarantined = quarantined,
    )

    private fun theme(
        name: String = "Theme",
        dark: Boolean = true,
        font: PluginTheme.Font = PluginTheme.Font.INTER,
    ): PluginTheme = PluginTheme(
        name = name,
        dark = dark,
        primary = 0xFF0A6FE0.toInt(),
        onPrimary = 0xFFFFFFFF.toInt(),
        background = 0xFF000000.toInt(),
        surface = 0xFF1C1C1E.toInt(),
        onSurface = 0xFFFFFFFF.toInt(),
        accent = 0xFF5E5CE6.toInt(),
        cornerRadiusDp = 20,
        font = font,
        uiScale = 1f,
    )

    @Test
    fun `entries carry eligibility from the plugin's own state`() {
        val on = installed(manifest = manifest(id = "com.example.on", name = "On"))
        val off = installed(manifest = manifest(id = "com.example.off", name = "Off"), enabled = false)
        val ungranted = installed(
            manifest = manifest(id = "com.example.new", name = "New"),
            grant = PluginGrant.none("com.example.new"),
        )
        val entries = ThemeLibraryLogic.entriesFor(
            installed = listOf(on, off, ungranted),
            themes = mapOf(on.id to theme(), off.id to theme(name = "Off"), ungranted.id to theme(name = "New")),
            activePluginId = null,
        )

        assertEquals(3, entries.size)
        val first = entries.first { it.pluginId == on.id }
        assertFalse(first.needsEnable)
        assertFalse(first.needsGrant)
        assertFalse(first.requiresConfirmation)
        assertTrue(first.canApply)

        val disabled = entries.first { it.pluginId == off.id }
        assertTrue(disabled.needsEnable)
        assertFalse(disabled.needsGrant)
        assertTrue(disabled.requiresConfirmation)

        val fresh = entries.first { it.pluginId == ungranted.id }
        assertFalse(fresh.needsEnable)
        assertTrue(fresh.needsGrant)
        assertTrue(fresh.requiresConfirmation)
    }

    @Test
    fun `plugins without a theme are not library entries`() {
        val themed = installed()
        val noTheme = installed(manifest = manifest(id = "com.example.other", declares = setOf(PluginSection.RULES)))
        val unreadable = installed(manifest = manifest(id = "com.example.broken", name = "Broken"))

        val entries = ThemeLibraryLogic.entriesFor(
            installed = listOf(themed, noTheme, unreadable),
            themes = mapOf(themed.id to theme(), unreadable.id to null),
            activePluginId = null,
        )

        assertEquals(listOf(themed.id), entries.map { it.pluginId })
    }

    @Test
    fun `the active theme sorts first and the rest alphabetically`() {
        val a = installed(manifest = manifest(id = "com.example.a", name = "Alpha"))
        val b = installed(manifest = manifest(id = "com.example.b", name = "beta"))
        val c = installed(manifest = manifest(id = "com.example.c", name = "Charlie"))
        val entries = ThemeLibraryLogic.entriesFor(
            installed = listOf(a, b, c),
            themes = mapOf(
                a.id to theme(name = "Zulu"),
                b.id to theme(name = "beta"),
                c.id to theme(name = "Alpha"),
            ),
            activePluginId = a.id,
        )

        assertEquals(listOf(a.id, c.id, b.id), entries.map { it.pluginId })
        assertTrue(entries.first().isActive)
    }

    @Test
    fun `search covers the fields a person actually types`() {
        val plugin = installed(
            manifest = manifest(
                id = "com.dream.liquidglass",
                name = "Liquid Glass",
                developer = "Dream",
            ),
        )
        val entry = ThemeLibraryLogic.entriesFor(
            installed = listOf(plugin),
            themes = mapOf(plugin.id to theme(name = "Liquid Glass — Inter", dark = true)),
            activePluginId = null,
        ).single()

        listOf("liquid", "DREAM", "com.dream", "inter", "dark", "glass").forEach { needle ->
            assertTrue("expected \"$needle\" to match", ThemeLibraryLogic.matches(entry, needle))
        }
        assertFalse(ThemeLibraryLogic.matches(entry, "light"))
        assertFalse(ThemeLibraryLogic.matches(entry, "nonexistent"))
        assertTrue("a blank query matches everything", ThemeLibraryLogic.matches(entry, "   "))
    }

    @Test
    fun `lenses filter by mode and selection without hiding the active theme`() {
        val dark = installed(manifest = manifest(id = "com.example.dark", name = "Dark"))
        val light = installed(manifest = manifest(id = "com.example.light", name = "Light"))
        val entries = ThemeLibraryLogic.entriesFor(
            installed = listOf(dark, light),
            themes = mapOf(
                dark.id to theme(name = "Night", dark = true),
                light.id to theme(name = "Day", dark = false),
            ),
            activePluginId = light.id,
        )

        assertEquals(listOf(dark.id), ThemeLibraryLogic.visible(entries, "", ThemeLibraryFilter.DARK).map { it.pluginId })
        assertEquals(listOf(light.id), ThemeLibraryLogic.visible(entries, "", ThemeLibraryFilter.LIGHT).map { it.pluginId })
        assertEquals(listOf(light.id), ThemeLibraryLogic.visible(entries, "", ThemeLibraryFilter.ACTIVE).map { it.pluginId })
        assertEquals(2, ThemeLibraryLogic.visible(entries, "", ThemeLibraryFilter.ALL).size)

        // The active theme is still findable under the lens that describes it; the point is that
        // the lens is a view, not that it is widened.
        assertEquals(listOf(light.id), ThemeLibraryLogic.visible(entries, "day", ThemeLibraryFilter.ALL).map { it.pluginId })
    }

    @Test
    fun `incompatible outranks quarantine and suspension`() {
        val incompatible = installed(
            manifest = manifest(id = "com.example.new", api = PluginApi.CURRENT + 1),
            quarantined = true,
            suspended = true,
        )
        val quarantined = installed(
            manifest = manifest(id = "com.example.quarantined"),
            quarantined = true,
            suspended = true,
        )
        val suspended = installed(
            manifest = manifest(id = "com.example.suspended"),
            suspended = true,
            suspensionDetail = "3 crashes",
        )
        val entries = ThemeLibraryLogic.entriesFor(
            installed = listOf(incompatible, quarantined, suspended),
            themes = mapOf(
                incompatible.id to theme(name = "New"),
                quarantined.id to theme(name = "Quarantined"),
                suspended.id to theme(name = "Suspended"),
            ),
            activePluginId = null,
        ).associateBy { it.pluginId }

        assertEquals(
            "This plugin needs a newer version of GameCore.",
            entries.getValue(incompatible.id).blockedReason,
        )
        assertTrue(entries.getValue(quarantined.id).blockedReason!!.startsWith("Quarantined"))
        assertTrue(entries.getValue(suspended.id).blockedReason!!.contains("3 crashes"))
        entries.values.forEach {
            assertFalse("a blocked theme cannot be applied", it.canApply)
            assertFalse("a blocked theme is not a confirmation question", it.requiresConfirmation)
        }
        assertNull(
            ThemeLibraryLogic.entriesFor(
                installed = listOf(installed()),
                themes = mapOf("com.example.theme" to theme()),
                activePluginId = null,
            ).single().blockedReason,
        )
    }

    @Test
    fun `the grant sentence names every change and nothing else`() {
        assertEquals(
            "Nothing about the plugin changes.",
            ThemeLibraryLogic.grantSentence(needsEnable = false, needsGrant = false, pluginName = "Example"),
        )
        assertEquals(
            "This will turn on \"Example\".",
            ThemeLibraryLogic.grantSentence(needsEnable = true, needsGrant = false, pluginName = "Example"),
        )
        assertEquals(
            "This will grant it Change the look.",
            ThemeLibraryLogic.grantSentence(needsEnable = false, needsGrant = true, pluginName = "Example"),
        )
        assertEquals(
            "This will turn on \"Example\" and grant it Change the look.",
            ThemeLibraryLogic.grantSentence(needsEnable = true, needsGrant = true, pluginName = "Example"),
        )
    }
}
