package com.gamecore.core.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scheduler's two rules worth pinning: a daily window's edge cases, and the precedence between
 * a game override, a time window and the user's manual choice.
 *
 * The codec is tested through the same object the preference store uses, not through a hand-built
 * JSON fixture alone. A round trip catches the failure that matters in the field: a schedule the
 * editor wrote and the next launch cannot read back.
 */
class AppearanceScheduleTest {

    private val window = AppearanceWindow(
        id = "night",
        label = "Night",
        startMinute = 22 * 60,
        endMinute = 7 * 60,
        theme = ThemeTarget.Plugin("nightshift"),
        dashboard = DashboardTarget.PluginDashboard,
    )

    @Test
    fun `an overnight window contains late evening and early morning but not noon`() {
        assertTrue(window.contains(23 * 60))
        assertTrue(window.contains(0))
        assertTrue(window.contains(6 * 60 + 59))
        assertFalse(window.contains(7 * 60))
        assertFalse(window.contains(12 * 60))
    }

    @Test
    fun `equal start and end mean all day`() {
        val allDay = window.copy(startMinute = 0, endMinute = 0)

        assertTrue(allDay.contains(0))
        assertTrue(allDay.contains(12 * 60))
        assertTrue(allDay.contains(23 * 60 + 59))
    }

    @Test
    fun `a game override wins over a window for both surfaces`() {
        val schedule = AppearanceSchedule(
            enabled = true,
            windows = listOf(window),
            gameOverrides = listOf(
                GameAppearanceOverride(
                    packageName = "com.example.game",
                    label = "Example",
                    theme = ThemeTarget.Default,
                    dashboard = DashboardTarget.BuiltIn,
                ),
            ),
        )

        val resolved = resolveAppearance(
            schedule = schedule,
            minuteOfDay = 23 * 60,
            gamePackage = "com.example.game",
            availableThemeIds = setOf("nightshift"),
        )

        assertEquals(ThemeTarget.Default, resolved.theme)
        assertEquals(DashboardTarget.BuiltIn, resolved.dashboard)
        assertEquals(AppearanceSource.GAME, resolved.source)
    }

    @Test
    fun `a game override that names only a dashboard leaves the window theme in force`() {
        val schedule = AppearanceSchedule(
            enabled = true,
            windows = listOf(window),
            gameOverrides = listOf(
                GameAppearanceOverride(
                    packageName = "com.example.game",
                    label = "Example",
                    dashboard = DashboardTarget.BuiltIn,
                ),
            ),
        )

        val resolved = resolveAppearance(
            schedule = schedule,
            minuteOfDay = 23 * 60,
            gamePackage = "com.example.game",
            availableThemeIds = setOf("nightshift"),
        )

        assertEquals(ThemeTarget.Plugin("nightshift"), resolved.theme)
        assertEquals(DashboardTarget.BuiltIn, resolved.dashboard)
        assertEquals(AppearanceSource.GAME, resolved.source)
    }

    @Test
    fun `the window wins over manual and an unavailable plugin target falls through`() {
        val schedule = AppearanceSchedule(
            enabled = true,
            windows = listOf(window.copy(theme = ThemeTarget.Plugin("missing"))),
        )

        val resolved = resolveAppearance(
            schedule = schedule,
            minuteOfDay = 23 * 60,
            gamePackage = null,
            availableThemeIds = setOf("nightshift"),
        )

        assertNull("An unavailable target must not be applied", resolved.theme)
        assertEquals(DashboardTarget.PluginDashboard, resolved.dashboard)
        assertEquals(AppearanceSource.WINDOW, resolved.source)
    }

    @Test
    fun `an unavailable game theme falls through to the window and reports the window source`() {
        val schedule = AppearanceSchedule(
            enabled = true,
            windows = listOf(window),
            gameOverrides = listOf(
                GameAppearanceOverride(
                    packageName = "com.example.game",
                    label = "Example",
                    theme = ThemeTarget.Plugin("disabled"),
                ),
            ),
        )

        val resolved = resolveAppearance(
            schedule = schedule,
            minuteOfDay = 23 * 60,
            gamePackage = "com.example.game",
            availableThemeIds = setOf("nightshift"),
        )

        assertEquals(ThemeTarget.Plugin("nightshift"), resolved.theme)
        assertEquals(AppearanceSource.WINDOW, resolved.source)
    }

    @Test
    fun `a disabled schedule resolves to manual even inside a matching window`() {
        val schedule = AppearanceSchedule(enabled = false, windows = listOf(window))

        val resolved = resolveAppearance(
            schedule = schedule,
            minuteOfDay = 23 * 60,
            gamePackage = null,
            availableThemeIds = setOf("nightshift"),
        )

        assertEquals(ResolvedAppearance(), resolved)
    }

    @Test
    fun `the codec round trips windows and game overrides`() {
        val schedule = AppearanceSchedule(
            enabled = true,
            windows = listOf(window),
            gameOverrides = listOf(
                GameAppearanceOverride(
                    packageName = "com.example.game",
                    label = "Example",
                    theme = ThemeTarget.Default,
                    dashboard = DashboardTarget.BuiltIn,
                ),
            ),
        )

        val decoded = AppearanceScheduleCodec.decode(AppearanceScheduleCodec.encode(schedule))

        assertEquals(schedule, decoded)
    }

    @Test
    fun `the codec drops a malformed row rather than repairing it into all day`() {
        val raw = """
            {
              "format": "gamecore.appearance.schedule",
              "version": 1,
              "enabled": true,
              "windows": [
                {"id": "bad", "label": "Bad", "start": 1440, "end": 0},
                {"id": "string", "label": "String", "start": "bad", "end": 60},
                {"id": "good", "label": "Good", "start": 60, "end": 120, "theme": "default"}
              ],
              "games": [
                {"package": "not a package", "label": "Bad"},
                {"package": "com.example.game", "label": "Good", "dashboard": "plugin"}
              ]
            }
        """.trimIndent()

        val decoded = AppearanceScheduleCodec.decode(raw)

        assertEquals(1, decoded.windows.size)
        assertEquals("good", decoded.windows.single().id)
        assertEquals(1, decoded.gameOverrides.size)
        assertEquals("com.example.game", decoded.gameOverrides.single().packageName)
    }

    @Test
    fun `an unknown format version is ignored instead of guessed at`() {
        val decoded = AppearanceScheduleCodec.decode(
            """{"format":"gamecore.appearance.schedule","version":99,"enabled":true}""",
        )

        assertEquals(AppearanceSchedule(), decoded)
    }
}
