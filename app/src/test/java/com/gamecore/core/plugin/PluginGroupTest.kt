package com.gamecore.core.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a group means, proved without a registry or a device.
 *
 * Grouping is the part of the plugin manager that can be wrong in a way nobody notices: a plugin
 * silently missing from the one chip the user was using to find it, a permission group that survives
 * the plugin that justified it, or a custom group that claims a plugin the device no longer has.
 * [PluginGrouping] is pure over the installed list, so every one of those rules is a test here rather
 * than something only visible by installing several plugins and reading the screen carefully.
 */
class PluginGroupTest {

    private fun manifest(
        id: String,
        declares: Set<PluginSection> = emptySet(),
        permissions: List<PluginPermission> = emptyList(),
    ): PluginManifest = PluginManifest(
        id = id,
        name = id,
        version = 1,
        versionName = "1.0",
        api = PluginApi.CURRENT,
        minHostVersion = PluginApi.CURRENT,
        developer = "Tester",
        description = "",
        permissions = permissions.map { RequestedPermission(it, emptySet()) },
        declares = declares,
        changelog = "",
    )

    private fun installed(
        id: String,
        declares: Set<PluginSection> = emptySet(),
        permissions: List<PluginPermission> = emptyList(),
    ): InstalledPlugin {
        val manifest = manifest(id, declares, permissions)
        return InstalledPlugin(
            manifest = manifest,
            sha256 = "0".repeat(64),
            origin = PluginOrigin.SIDELOADED,
            enabled = true,
            grant = PluginGrant.none(manifest.id),
        )
    }

    private fun groupOf(groups: List<PluginGroup>, id: String): PluginGroup? =
        groups.firstOrNull { it.id == id }

    // ------------------------------------------------------------ automatic groups

    @Test
    fun `sections and permissions both produce groups, and one plugin can be in several`() {
        val plugins = listOf(
            // A theme plugin that also adds a Pill module: two section groups and two permission
            // groups at once. This is the many-to-many case the feature exists for.
            installed(
                "com.example.spanning",
                declares = setOf(PluginSection.THEME, PluginSection.PILL),
                permissions = listOf(
                    PluginPermission.CHANGE_UI,
                    PluginPermission.ADD_PILL_MODULES,
                ),
            ),
            installed(
                "com.example.widgets",
                declares = setOf(PluginSection.WIDGETS),
                permissions = listOf(PluginPermission.ADD_DASHBOARD_WIDGETS),
            ),
            installed(
                "com.example.theme-only",
                permissions = listOf(PluginPermission.CHANGE_UI),
            ),
        )

        val groups = PluginGrouping.automatic(plugins)

        assertEquals(setOf("com.example.spanning"), groupOf(groups, "auto.section.theme")?.pluginIds)
        assertEquals(setOf("com.example.spanning"), groupOf(groups, "auto.section.pill")?.pluginIds)
        assertEquals(setOf("com.example.widgets"), groupOf(groups, "auto.section.widgets")?.pluginIds)
        assertEquals(
            setOf("com.example.spanning", "com.example.theme-only"),
            groupOf(groups, "auto.permission.change_ui")?.pluginIds,
        )
        assertEquals(
            setOf("com.example.spanning"),
            groupOf(groups, "auto.permission.add_pill_modules")?.pluginIds,
        )

        // The spanning plugin is a member of four different groups, not filed into one folder.
        assertEquals(4, groups.count { "com.example.spanning" in it.pluginIds })
        assertTrue(groups.all { it.automatic })
        assertTrue(groups.all { it.id.startsWith(PluginGrouping.AUTOMATIC_PREFIX) })
    }

    @Test
    fun `groups with no members are not emitted`() {
        val groups = PluginGrouping.automatic(
            listOf(installed("com.example.plain")),
        )
        assertTrue(groups.isEmpty())
    }

    @Test
    fun `automatic membership follows the installed list, not a stored copy`() {
        val themer = installed("com.example.theme", declares = setOf(PluginSection.THEME))
        assertEquals(
            setOf("com.example.theme"),
            groupOf(PluginGrouping.automatic(listOf(themer)), "auto.section.theme")?.pluginIds,
        )
        // Removing the package removes the group entirely: an empty chip would filter to nothing.
        assertNull(groupOf(PluginGrouping.automatic(emptyList()), "auto.section.theme"))
    }

    // ------------------------------------------------------------ combining with custom groups

    @Test
    fun `custom groups are narrowed to installed plugins and follow the automatic ones`() {
        val installed = listOf(installed("com.example.installed", declares = setOf(PluginSection.THEME)))
        val custom = listOf(
            PluginGroup(
                id = "custom.one",
                name = "Daily drivers",
                pluginIds = setOf("com.example.installed", "com.example.removed"),
            ),
        )

        val all = PluginGrouping.all(installed, custom)

        // The automatic theme group is first, so derived groups do not move around when a user
        // creates and deletes their own.
        assertTrue(all.first().automatic)
        val customGroup = all.last()
        assertFalse(customGroup.automatic)
        assertEquals(setOf("com.example.installed"), customGroup.pluginIds)
        // The stored group itself is untouched; narrowing is a presentation rule.
        assertEquals(
            setOf("com.example.installed", "com.example.removed"),
            custom.single().pluginIds,
        )
    }

    @Test
    fun `custom groups keep their order and can overlap`() {
        val installed = listOf(
            installed("com.example.a"),
            installed("com.example.b"),
        )
        val custom = listOf(
            PluginGroup("custom.first", "First", setOf("com.example.a", "com.example.b")),
            PluginGroup("custom.second", "Second", setOf("com.example.b")),
        )

        val all = PluginGrouping.all(installed, custom)

        assertEquals(listOf("First", "Second"), all.map { it.name })
        assertEquals(2, all.count { "com.example.b" in it.pluginIds })
    }

    // ------------------------------------------------------------ names

    @Test
    fun `a group name is sanitised, collapsed and capped`() {
        assertEquals("Night Mode", PluginGrouping.normaliseName("  Night   Mode  "))
        // A right-to-left override is a format character: it is stripped like it is in every other
        // user-supplied name in the app.
        assertEquals("NightMode", PluginGrouping.normaliseName("Night\u202EMode"))
        assertEquals(
            PluginGrouping.MAX_NAME_LENGTH,
            PluginGrouping.normaliseName("x".repeat(200))?.length,
        )
    }

    @Test
    fun `a name that is blank or sanitises away cannot become a group`() {
        assertNull(PluginGrouping.normaliseName(null))
        assertNull(PluginGrouping.normaliseName(""))
        assertNull(PluginGrouping.normaliseName("   "))
        assertNull(PluginGrouping.normaliseName("\u202E"))
    }
}
