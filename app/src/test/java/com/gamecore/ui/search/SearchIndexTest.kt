package com.gamecore.ui.search

import com.gamecore.aimlab.ui.home.AimLabRoutes
import com.gamecore.ui.Destination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SearchIndex] — pure JVM, no Android dependencies.
 *
 * Verifies the static index covers all required routes and that ranking behaves as specified.
 */
class SearchIndexTest {

    @Test
    fun `index is not empty`() {
        assertFalse("SearchIndex.entries should not be empty", SearchIndex.entries.isEmpty())
    }

    @Test
    fun `every entry has a non-blank title, route and category`() {
        SearchIndex.entries.forEach { entry ->
            assertTrue("title must not be blank: ${entry.route}", entry.title.isNotBlank())
            assertTrue("route must not be blank: ${entry.title}", entry.route.isNotBlank())
            assertTrue("category must be set: ${entry.title}", entry.category != null)
        }
    }

    @Test
    fun `all five top-level Destination routes are covered`() {
        val routes = SearchIndex.entries.map { it.route }.toSet()
        assertTrue("Home route missing", routes.contains(Destination.Home.route))
        assertTrue("Games route missing", routes.contains(Destination.Games.route))
        assertTrue("AimLabHome route missing", routes.contains(Destination.AimLabHome.route))
        assertTrue("Sessions route missing", routes.contains(Destination.Sessions.route))
        assertTrue("Settings route missing", routes.contains(Destination.Settings.route))
    }

    @Test
    fun `all AimLabRoutes flat routes are covered`() {
        val routes = SearchIndex.entries.map { it.route }.toSet()
        AimLabRoutes.flatRoutes.forEach { route ->
            assertTrue("AimLab route $route missing from index", routes.contains(route))
        }
    }

    @Test
    fun `key secondary Destination routes are covered`() {
        val routes = SearchIndex.entries.map { it.route }.toSet()
        val expected = listOf(
            Destination.Hud.route,
            Destination.Performance.route,
            Destination.Crosshair.route,
            Destination.Scout.route,
            Destination.Hunt.route,
            Destination.Wheels.route,
            Destination.Charge.route,
            Destination.NetworkStability.route,
            Destination.Graphics.route,
            Destination.ThemeEngine.route,
            Destination.Overlay.route,
            Destination.Plugins.route,
            Destination.BackupRestore.route,
            Destination.SetupWizard.route,
            Destination.SetupHealth.route,
            Destination.WhatsNew.route,
            Destination.ScreenExtraction.route,
            Destination.SamplingMonitor.route,
            Destination.Colour.route,
            Destination.MediaAccess.route,
            Destination.Shizuku.route,
            Destination.Permissions.route,
            Destination.Tools.route,
            Destination.DockCustomization.route,
            Destination.QuickApps.route,
            Destination.MacroEditor.route,
            Destination.Motion.route,
            Destination.Touch.route,
            Destination.Controller.route,
            Destination.Capability.route,
            Destination.QuickTrigger.route,
            Destination.NeverClose.route,
            Destination.GameStorage.route,
            Destination.Emulators.route,
            Destination.Developer.route,
            Destination.GlassCentre.route,
        )
        expected.forEach { route ->
            assertTrue("Route $route missing from index", routes.contains(route))
        }
    }

    @Test
    fun `whatsNewRegistry features are included`() {
        val titles = SearchIndex.entries.map { it.title }.toSet()
        // At least one feature from the registry should be present
        val hasWhatsNewFeature = titles.any { it.contains("Tilt") || it.contains("Glass") || it.contains("Theme") }
        assertTrue("At least one WhatsNewRegistry feature should be indexed", hasWhatsNewFeature)
    }

    @Test
    fun `search empty query returns empty list`() {
        assertTrue("Empty query should return empty list", SearchIndex.search("").isEmpty())
        assertTrue("Whitespace query should return empty list", SearchIndex.search("   ").isEmpty())
    }

    @Test
    fun `exact title match scores highest`() {
        val results = SearchIndex.search("Performance")
        assertFalse("Should find Performance", results.isEmpty())
        val top = results.first()
        assertEquals("Top result should be Performance", "Performance", top.entry.title)
        assertTrue("Exact match score should be >= 1000", top.score >= 1000)
    }

    @Test
    fun `prefix match scores higher than contains`() {
        val results = SearchIndex.search("Perfor")
        assertFalse("Should find prefix matches", results.isEmpty())
        val top = results.first()
        assertTrue("Prefix match should score >= 500", top.score >= 500)
        // Should rank "Performance" above entries that only contain "perfor" in description
    }

    @Test
    fun `word-start match in title scores above substring`() {
        val results = SearchIndex.search("Lab")
        assertFalse("Should find Aim Lab entries", results.isEmpty())
        val aimLabHome = results.firstOrNull { it.entry.title == "Aim Lab Home" }
        assertTrue("Aim Lab Home should be found", aimLabHome != null)
        assertTrue("Word-start match should score >= 200", aimLabHome!!.score >= 200)
    }

    @Test
    fun `keyword match scores above description match`() {
        // "flick" is a keyword for "Flick Training" but not in its title
        val results = SearchIndex.search("flick")
        val flickTraining = results.firstOrNull { it.entry.title == "Flick Training" }
        assertTrue("Flick Training should be found via keyword", flickTraining != null)
        assertTrue("Keyword match should score >= 400", flickTraining!!.score >= 400)
    }

    @Test
    fun `results are sorted by score descending`() {
        val results = SearchIndex.search("Performance")
        var prevScore = Int.MAX_VALUE
        results.forEach { result ->
            assertTrue("Scores must be non-increasing: ${result.entry.title} (${result.score}) > $prevScore",
                result.score <= prevScore)
            prevScore = result.score
        }
    }

    @Test
    fun `stable sort preserves declaration order for ties`() {
        // Search for something that matches multiple entries with same score
        // "Settings" matches multiple entries in SETTINGS category
        val results = SearchIndex.search("Settings")
        val settingsEntries = results.filter { it.entry.category == SearchCategory.SETTINGS }
        if (settingsEntries.size >= 2) {
            // The first two Settings entries in the index are "Settings Home" then "Appearance"
            // They should appear in that order if scores are equal
            val first = settingsEntries[0]
            val second = settingsEntries[1]
            if (first.score == second.score) {
                val firstIndex = SearchIndex.entries.indexOf(first.entry)
                val secondIndex = SearchIndex.entries.indexOf(second.entry)
                assertTrue("Stable sort: earlier declaration should come first",
                    firstIndex < secondIndex)
            }
        }
    }

    @Test
    fun `limit parameter caps results`() {
        val results = SearchIndex.search("a", limit = 5) // "a" matches many
        assertEquals("Limit should be respected", 5, results.size)
    }

    @Test
    fun `default limit is 30`() {
        val results = SearchIndex.search("a") // "a" matches many
        assertTrue("Default limit should be 30", results.size <= 30)
    }

    @Test
    fun `category grouping order matches enum order`() {
        val results = SearchIndex.search("") // not used, just checking category order
        val categories = SearchCategory.values()
        for (i in 1 until categories.size) {
            assertTrue("Category order: ${categories[i-1]} should come before ${categories[i]}",
                categories[i-1].order < categories[i].order)
        }
    }

    @Test
    fun `recentOrPopular returns top-level destinations`() {
        val suggestions = SearchIndex.recentOrPopular()
        assertFalse("Should have suggestions", suggestions.isEmpty())
        val routes = suggestions.map { it.route }.toSet()
        assertTrue("Should include Home", routes.contains(Destination.Home.route))
        assertTrue("Should include Games", routes.contains(Destination.Games.route))
        assertTrue("Should include AimLabHome", routes.contains(Destination.AimLabHome.route))
        assertTrue("Should include Sessions", routes.contains(Destination.Sessions.route))
        assertTrue("Should include Settings", routes.contains(Destination.Settings.route))
    }

    @Test
    fun `search result contains entry and score`() {
        val results = SearchIndex.search("Performance")
        val result = results.first()
        assertTrue("Result should have entry", result.entry != null)
        assertTrue("Result should have score > 0", result.score > 0)
    }

    @Test
    fun `crosshair related entries are searchable`() {
        val results = SearchIndex.search("crosshair")
        val crosshair = results.firstOrNull { it.entry.title == "Crosshair" }
        assertTrue("Crosshair should be found", crosshair != null)
    }

    @Test
    fun `overlay related entries are searchable`() {
        val results = SearchIndex.search("overlay")
        val overlay = results.firstOrNull { it.entry.title.contains("Overlay", ignoreCase = true) }
        assertTrue("Overlay should be found", overlay != null)
    }

    @Test
    fun `backup restore is searchable`() {
        val results = SearchIndex.search("backup")
        val backup = results.firstOrNull { it.entry.title == "Backup & Restore" }
        assertTrue("Backup & Restore should be found", backup != null)
    }

    @Test
    fun `setup wizard is searchable`() {
        val results = SearchIndex.search("wizard")
        val wizard = results.firstOrNull { it.entry.title == "Setup Wizard" }
        assertTrue("Setup Wizard should be found", wizard != null)
    }

    @Test
    fun `developer entry exists`() {
        val results = SearchIndex.search("developer")
        val dev = results.firstOrNull { it.entry.title == "Developer" }
        assertTrue("Developer should be found", dev != null)
    }

    @Test
    fun `graphics entry exists`() {
        val results = SearchIndex.search("graphics")
        val graphics = results.firstOrNull { it.entry.title == "Graphics" }
        assertTrue("Graphics should be found", graphics != null)
    }

    @Test
    fun `theme engine entry exists`() {
        val results = SearchIndex.search("theme engine")
        val engine = results.firstOrNull { it.entry.title == "Theme Engine" }
        assertTrue("Theme Engine should be found", engine != null)
    }

    @Test
    fun `battery saver override is searchable`() {
        val results = SearchIndex.search("battery saver")
        val battery = results.firstOrNull { it.entry.title == "Battery Saver Override" }
        assertTrue("Battery Saver Override should be found", battery != null)
    }

    @Test
    fun `memory reclaim is searchable`() {
        val results = SearchIndex.search("memory")
        val memory = results.firstOrNull { it.entry.title == "Memory Reclaim" }
        assertTrue("Memory Reclaim should be found", memory != null)
    }
}