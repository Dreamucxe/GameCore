package com.gamecore.ui.plugins

import com.gamecore.core.plugin.Compatibility
import com.gamecore.core.plugin.InstallOutcome
import com.gamecore.core.plugin.InstalledPlugin
import com.gamecore.core.plugin.PluginApi
import com.gamecore.core.plugin.PluginGrant
import com.gamecore.core.plugin.PluginManifest
import com.gamecore.core.plugin.PluginOrigin
import com.gamecore.core.plugin.PluginPackageLoader
import com.gamecore.core.plugin.PluginPermission
import com.gamecore.core.plugin.PluginSection
import com.gamecore.core.plugin.RequestedPermission
import com.gamecore.core.plugin.RiskTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plugin manager's presentation logic, tested without a device.
 *
 * [rowFor] and [messageFor] are the two places the manager screen can quietly lie: a row that shows a
 * stopped plugin as running, a switch offered for a plugin this build cannot load, or an install that
 * failed with a message that names no cause. None of that throws, none of it crashes, and all of it is
 * invisible until a user is looking at a screen that is wrong — which is why both functions are plain
 * Kotlin over plain data and tested here rather than through a composable.
 *
 * Pure JVM: no Compose, no Android, no Robolectric. Nothing in this file touches a registry, so a
 * failure here is a failure in the mapping and nowhere else.
 */
class PluginsStateTest {

    private fun manifest(
        id: String = "com.example.plugin",
        name: String = "Example",
        versionName: String = "1.0",
        developer: String = "Tester",
        description: String = "Adds a theme.",
        api: Int = PluginApi.CURRENT,
        minHostVersion: Int = 1,
        permissions: List<PluginPermission> = emptyList(),
        declares: Set<PluginSection> = emptySet(),
    ): PluginManifest = PluginManifest(
        id = id,
        name = name,
        version = 1,
        versionName = versionName,
        api = api,
        minHostVersion = minHostVersion,
        developer = developer,
        description = description,
        permissions = permissions.map { RequestedPermission(it, emptySet()) },
        declares = declares,
        changelog = "",
    )

    private fun installed(
        manifest: PluginManifest = manifest(),
        origin: PluginOrigin = PluginOrigin.SIDELOADED,
        enabled: Boolean = false,
        suspended: Boolean = false,
        suspensionDetail: String? = null,
    ): InstalledPlugin = InstalledPlugin(
        manifest = manifest,
        sha256 = "0".repeat(64),
        origin = origin,
        enabled = enabled,
        suspended = suspended,
        suspensionDetail = suspensionDetail,
        grant = PluginGrant.none(manifest.id),
    )

    // ---------------------------------------------------------------- rowFor: the plain mapping

    @Test
    fun `row carries the manifest's own description of itself`() {
        val row = rowFor(installed(manifest(name = "Neon", developer = "Someone", versionName = "2.3")), null)
        assertEquals("com.example.plugin", row.id)
        assertEquals("Neon", row.name)
        assertEquals("Someone", row.developer)
        assertEquals("2.3", row.versionName)
        assertEquals("Adds a theme.", row.summary)
        assertEquals(PluginOrigin.SIDELOADED, row.origin)
    }

    @Test
    fun `row keeps the declared sections so the screen need not re-read the package`() {
        val declared = setOf(PluginSection.THEME, PluginSection.PILL)
        val row = rowFor(installed(manifest(declares = declared)), null)
        assertEquals(declared, row.declares)
    }

    @Test
    fun `a name that sanitises away falls back to the plugin id`() {
        // U+202E is a right-to-left override: a format character, stripped entirely, leaving nothing. A
        // nameless row with a switch on it is the one row a user cannot reason about.
        val row = rowFor(installed(manifest(id = "com.example.blank", name = "‮")), null)
        assertEquals("com.example.blank", row.name)
    }

    // ---------------------------------------------------------------- rowFor: compatibility

    @Test
    fun `an incompatible plugin maps to a row that cannot be enabled`() {
        val tooNew = manifest(api = PluginApi.CURRENT + 1)
        // Enabled in the store, because a build downgrade can leave it that way — the row must still
        // refuse to offer the switch.
        val row = rowFor(installed(tooNew, enabled = true), null)
        assertFalse(row.compatibility.isRunnable)
        assertEquals(Compatibility.TooNew, row.compatibility)
        assertFalse(PluginsUiState(rows = listOf(row)).hasAnyActive)
    }

    @Test
    fun `a plugin needing a newer host is incompatible rather than merely off`() {
        val tooOldHost = manifest(minHostVersion = PluginApi.HOST_VERSION_CODE + 1)
        val row = rowFor(installed(tooOldHost, enabled = true), null)
        assertEquals(Compatibility.HostTooOld, row.compatibility)
        assertFalse(row.compatibility.isRunnable)
    }

    @Test
    fun `a supported enabled plugin is reported as active`() {
        val row = rowFor(installed(enabled = true), null)
        assertTrue(row.compatibility.isRunnable)
        assertTrue(PluginsUiState(rows = listOf(row)).hasAnyActive)
    }

    @Test
    fun `the kill switch means no plugin is active whatever its own switch says`() {
        val row = rowFor(installed(enabled = true), null)
        val state = PluginsUiState(rows = listOf(row), killSwitchOn = true)
        assertTrue(state.hasPlugins)
        assertFalse(state.hasAnyActive)
        // The user's own choice is kept, not erased — only its effect is suspended.
        assertTrue(state.rows.single().enabled)
    }

    // ---------------------------------------------------------------- rowFor: suspension

    @Test
    fun `a suspended plugin carries its detail`() {
        val row = rowFor(
            installed(enabled = true, suspended = true, suspensionDetail = "Its theme failed to parse"),
            null,
        )
        assertTrue(row.suspended)
        assertEquals("Its theme failed to parse", row.suspensionDetail)
        assertFalse(PluginsUiState(rows = listOf(row)).hasAnyActive)
    }

    @Test
    fun `a suspension with no detail carries null rather than an empty badge`() {
        val row = rowFor(installed(suspended = true, suspensionDetail = "   "), null)
        assertTrue(row.suspended)
        assertNull(row.suspensionDetail)
    }

    // ---------------------------------------------------------------- rowFor: theme and origin

    @Test
    fun `the theme-source row is the only one flagged`() {
        val themer = installed(manifest(id = "com.example.theme", name = "Theme"))
        val other = installed(manifest(id = "com.example.other", name = "Other"))
        assertTrue(rowFor(themer, "com.example.theme").isThemeSource)
        assertFalse(rowFor(other, "com.example.theme").isThemeSource)
    }

    @Test
    fun `no active theme flags nothing`() {
        assertFalse(rowFor(installed(), null).isThemeSource)
    }

    @Test
    fun `a bundled row reports that it cannot be removed`() {
        val row = rowFor(installed(origin = PluginOrigin.BUNDLED), null)
        assertFalse(row.canBeRemoved)
        assertEquals(PluginOrigin.BUNDLED, row.origin)
    }

    @Test
    fun `a sideloaded row can be removed`() {
        assertTrue(rowFor(installed(origin = PluginOrigin.SIDELOADED), null).canBeRemoved)
    }

    // ---------------------------------------------------------------- rowFor: risk tier

    @Test
    fun `a plugin that asks for nothing reports no risk tier at all`() {
        // Not LOW: a badge saying "Low risk" would claim an assessment that never had to happen.
        assertNull(rowFor(installed(manifest(permissions = emptyList())), null).highestTier)
    }

    @Test
    fun `the row reports the highest tier any requested permission carries`() {
        val row = rowFor(
            installed(
                manifest(
                    permissions = listOf(
                        PluginPermission.CHANGE_UI,
                        PluginPermission.SYSTEM_ACTIONS,
                        PluginPermission.ADD_PILL_MODULES,
                    ),
                ),
            ),
            null,
        )
        assertEquals(RiskTier.HIGH, row.highestTier)
    }

    // ---------------------------------------------------------------- state: loaded vs empty

    @Test
    fun `an unread registry is not the same state as an empty one`() {
        val unread = PluginsUiState()
        assertFalse(unread.isLoaded)
        assertFalse(unread.hasPlugins)

        val empty = PluginsUiState(isLoaded = true)
        assertTrue(empty.isLoaded)
        assertFalse(empty.hasPlugins)
    }

    // ---------------------------------------------------------------- messageFor: every outcome

    @Test
    fun `every install outcome produces text that is not blank`() {
        val outcomes: List<InstallOutcome> = listOf(
            InstallOutcome.Installed(installed(), null),
            InstallOutcome.Installed(installed(), installed()),
            InstallOutcome.Rejected(PluginPackageLoader.Reason.MALFORMED, null),
            InstallOutcome.Rejected(PluginPackageLoader.Reason.MALFORMED, "entry 4"),
            InstallOutcome.Incompatible(Compatibility.TooNew),
            InstallOutcome.StorageFailed("no space"),
        )
        outcomes.forEach { outcome ->
            assertTrue(outcome.toString(), messageFor(outcome).isNotBlank())
        }
    }

    @Test
    fun `an installed plugin is reported as off until its permissions are granted`() {
        val text = messageFor(InstallOutcome.Installed(installed(manifest(name = "Neon")), null))
        assertTrue(text, text.contains("Neon"))
        assertTrue(text, text.contains("off"))
        assertTrue(text, text.contains("permission"))
        // Points at the detail screen, which is where the decision is actually made.
        assertTrue(text, text.contains("Open it"))
    }

    @Test
    fun `replacing an existing copy is said out loud`() {
        val fresh = InstallOutcome.Installed(installed(), null)
        val over = InstallOutcome.Installed(installed(), installed())
        assertFalse(messageFor(fresh) == messageFor(over))
        assertTrue(messageFor(over).contains("already here"))
    }

    @Test
    fun `an install whose name sanitises away still names the plugin by its id`() {
        val blank = installed(manifest(id = "com.example.blank", name = "‮"))
        val text = messageFor(InstallOutcome.Installed(blank, null))
        assertTrue(text, text.contains("com.example.blank"))
    }

    // ---------------------------------------------------------------- messageFor: rejections

    @Test
    fun `every rejection reason gets its own distinct explanation`() {
        val reasons = PluginPackageLoader.Reason.entries
        val texts = reasons.map { messageFor(InstallOutcome.Rejected(it, null)) }
        texts.forEachIndexed { index, text ->
            assertTrue(reasons[index].name, text.isNotBlank())
        }
        // Distinct, not merely present: one shared "that file is not a valid plugin" would leave a
        // developer with an undeclared section and a user with a truncated download unable to tell
        // which of the two happened.
        assertEquals(reasons.size, texts.toSet().size)
    }

    @Test
    fun `the size and count limits are quoted from the loader rather than retyped`() {
        val tooBig = messageFor(InstallOutcome.Rejected(PluginPackageLoader.Reason.PACKAGE_TOO_LARGE, null))
        assertTrue(tooBig, tooBig.contains("${PluginPackageLoader.MAX_PACKAGE_BYTES / (1024 * 1024)} MB"))

        val tooMany = messageFor(InstallOutcome.Rejected(PluginPackageLoader.Reason.TOO_MANY_ENTRIES, null))
        assertTrue(tooMany, tooMany.contains("${PluginPackageLoader.MAX_ENTRIES}"))
    }

    @Test
    fun `a rejection detail is appended when there is one`() {
        val text = messageFor(
            InstallOutcome.Rejected(PluginPackageLoader.Reason.UNSAFE_PATH, "../../etc/passwd"),
        )
        assertTrue(text, text.contains("../../etc/passwd"))
    }

    @Test
    fun `a hostile rejection detail is sanitised before it is shown`() {
        // The detail is the offending entry's name — attacker-controlled text out of a zip, and the one
        // string on the screen that never passed through the manifest codec.
        val text = messageFor(
            InstallOutcome.Rejected(PluginPackageLoader.Reason.UNSAFE_PATH, "safe‮name.json"),
        )
        assertFalse(text, text.contains('‮'))
        assertTrue(text, text.contains("safename.json"))
    }

    @Test
    fun `a rejection with a blank detail leaves no empty brackets behind`() {
        val text = messageFor(InstallOutcome.Rejected(PluginPackageLoader.Reason.EMPTY, "  "))
        assertFalse(text, text.contains("()"))
        assertEquals(messageFor(InstallOutcome.Rejected(PluginPackageLoader.Reason.EMPTY, null)), text)
    }

    // ---------------------------------------------------------------- messageFor: the other two

    @Test
    fun `an incompatible package quotes the compatibility verdict verbatim`() {
        Compatibility.entries.filter { !it.isRunnable }.forEach { verdict ->
            val text = messageFor(InstallOutcome.Incompatible(verdict))
            assertTrue(verdict.name, text.contains(verdict.reason))
            assertTrue(verdict.name, text.contains("not installed"))
        }
    }

    @Test
    fun `a storage failure says nothing was installed and what to do`() {
        val text = messageFor(InstallOutcome.StorageFailed("ENOSPC"))
        assertTrue(text, text.contains("room"))
        assertTrue(text, text.contains("Nothing was installed"))
        assertTrue(text, text.contains("ENOSPC"))
    }
}
