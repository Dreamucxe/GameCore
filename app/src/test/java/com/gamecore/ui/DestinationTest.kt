package com.gamecore.ui

import com.gamecore.aimlab.ui.home.AimLabRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The navigation contract §9 pins down, proved without a device.
 *
 * §9 makes two promises that are easy to break silently. The first is the bottom bar: exactly Home,
 * Games, Aim Lab, Sessions, Settings, in that order — the redesign moved the HUD builder off the bar and
 * gave its slot to Aim Lab, and a reordering or a stray sixth tab is the kind of change that compiles and
 * ships. The second is that every route name, argument and deep link the app had before the redesign is
 * unchanged, so a saved shortcut, a Quick Settings tile or an external intent still lands where it did.
 *
 * These are pure reads of the [Destination] declarations, so they run on the JVM with no emulator. That
 * they run at all is itself part of the check: [Destination.Top] carries an [androidx.compose.ui.graphics.vector.ImageVector]
 * icon, and if that ever pulled in a piece of the Android runtime, this file would be the first to fail.
 */
class DestinationTest {

    // ------------------------------------------------------------------------------ the bottom bar

    @Test
    fun `the bar is exactly the five destinations §9 names, in order`() {
        assertEquals(
            listOf("home", "games", AimLabRoutes.HOME, "sessions", "settings"),
            Destination.top.map { it.route },
        )
    }

    @Test
    fun `the third tab is Aim Lab, not the HUD builder`() {
        assertEquals(Destination.AimLabHome, Destination.top[2])
        // The HUD builder kept its route but is no longer a tab — it is reached, not lived on.
        assertFalse(Destination.Hud is Destination.Top)
    }

    @Test
    fun `every tab carries a visible label, since the bar is read as well as seen`() {
        Destination.top.forEach { tab ->
            assertTrue(tab.route, tab.label.isNotBlank())
        }
    }

    @Test
    fun `Aim Lab's tab label and route are the section's own`() {
        assertEquals("Aim Lab", Destination.AimLabHome.label)
        assertEquals(AimLabRoutes.HOME, Destination.AimLabHome.route)
    }

    // ------------------------------------------------------------------- the bar follows the setting

    @Test
    fun `with Aim Lab on, the bar is the full five`() {
        assertEquals(Destination.top, Destination.topFor(aimLabEnabled = true))
    }

    /**
     * The disabled case is the one that matters: `GameCoreNav` registers Aim Lab's routes only while the
     * section is on, so a tab for it while it is off would be a tap that lands on Home with no reason
     * given. The bar must be four, and the four must be the others in their order.
     */
    @Test
    fun `with Aim Lab off, its tab is dropped and nothing else moves`() {
        val bar = Destination.topFor(aimLabEnabled = false)
        assertEquals(listOf("home", "games", "sessions", "settings"), bar.map { it.route })
        assertFalse("Aim Lab must not be on the bar when off", bar.contains(Destination.AimLabHome))
    }

    @Test
    fun `disabling Aim Lab removes exactly one tab`() {
        assertEquals(
            Destination.top.size - 1,
            Destination.topFor(aimLabEnabled = false).size,
        )
    }

    // --------------------------------------------------------------- routes and deep links unchanged

    /**
     * The exact route strings, spelled out. Not `assertEquals(x.route, x.route)` — the point is to fail
     * if any of these *changes*, so a rename that breaks a saved deep link or a Quick Settings tile is
     * caught here rather than by a user whose shortcut stopped working.
     */
    @Test
    fun `the pre-redesign route names are all still exactly what they were`() {
        assertEquals("home", Destination.Home.route)
        assertEquals("games", Destination.Games.route)
        assertEquals("sessions", Destination.Sessions.route)
        assertEquals("settings", Destination.Settings.route)
        assertEquals("hud", Destination.Hud.route)
        assertEquals("crosshair", Destination.Crosshair.route)
        assertEquals("colour", Destination.Colour.route)
        assertEquals("performance", Destination.Performance.route)
    }

    @Test
    fun `the id-carrying routes keep their argument shape`() {
        assertEquals("session/42", Destination.SessionReport.routeFor(42L))
        assertEquals("profile/com.example.game", Destination.ProfileEditor.routeFor("com.example.game"))
        assertEquals("${AimLabRoutes.RESULTS}/7", Destination.AimLabResults.routeFor(7L))
    }

    /**
     * The intent surface is a closed map, and it stays closed. The two tokens the app has always accepted
     * still resolve to the same destinations, and anything else — including a route string typed as a
     * token — resolves to nothing, which is what keeps an exported activity from being told to open an
     * arbitrary screen.
     */
    @Test
    fun `only the two known external tokens open a screen`() {
        assertEquals(Destination.Colour, Destination.fromExternal("colour"))
        assertEquals(Destination.MediaAccess, Destination.fromExternal("media-access"))
        assertNull(Destination.fromExternal("home"))
        assertNull(Destination.fromExternal("hud"))
        assertNull(Destination.fromExternal("aimlab-home"))
        assertNull(Destination.fromExternal(null))
        assertNull(Destination.fromExternal(""))
    }

    @Test
    fun `an external token is matched after trimming, and never by prefix`() {
        assertEquals(Destination.Colour, Destination.fromExternal("  colour  "))
        assertNull(Destination.fromExternal("colourful"))
    }

    // ------------------------------------------------------------------ every route stays reachable

    /**
     * Every destination the app declares, listed by object rather than by string so this can only be
     * satisfied by the real routes. The audit's §16 reachability guarantee is "every route and entry point
     * still resolves", and `GameCoreNav` registers a `composable` per route — a route that is dropped, or
     * that collides with another, is a screen a user can no longer reach. This list is what the two checks
     * below are run over; keeping it here, referencing the objects, means adding a `Destination` without
     * adding it here is the only way to leave it out, and that omission is itself visible in review.
     */
    private val allDestinations: List<Destination> = listOf(
        // the five tops (§9)
        Destination.Home, Destination.Games, Destination.AimLabHome, Destination.Sessions, Destination.Settings,
        // secondary, argument-less
        Destination.Hud, Destination.Performance, Destination.Crosshair, Destination.Shizuku,
        Destination.Permissions, Destination.Tools, Destination.Overlay, Destination.Motion, Destination.Touch,
        Destination.Controller, Destination.Capability, Destination.QuickTrigger, Destination.NeverClose,
        Destination.GameStorage, Destination.QuickApps, Destination.MacroEditor, Destination.Developer,
        Destination.Colour, Destination.MediaAccess, Destination.BackupRestore,
        // argument-carrying
        Destination.ProfileEditor, Destination.HudEditor, Destination.SessionReport,
        // Aim Lab (registered only while the section is on, but declared unconditionally)
        Destination.AimLabFlick, Destination.AimLabTracking, Destination.AimLabReaction, Destination.AimLabGyro,
        Destination.AimLabRecoil, Destination.AimLabMovement, Destination.AimLabPractice,
        Destination.AimLabSensitivity, Destination.AimLabWeapon, Destination.AimLabControls,
        Destination.AimLabStats, Destination.AimLabRecords, Destination.AimLabHistory, Destination.AimLabResults,
    )

    @Test
    fun `no destination carries a blank route`() {
        // A blank route registers nothing and is navigated to by nobody — a screen that exists and cannot
        // be opened. The tops are checked for a label above; this is the same guarantee for the route itself.
        allDestinations.forEach { dest ->
            assertTrue(dest::class.simpleName ?: "unknown", dest.route.isNotBlank())
        }
    }

    /**
     * No two destinations resolve to the same route. `GameCoreNav` maps a route to one `composable`, so a
     * collision means the second registration wins and the first destination opens the wrong screen — the
     * kind of break that a per-destination test would miss because each object looks correct on its own.
     * The argument routes are templates (`profile/{package}`), so the collision that matters is on the
     * template, which is exactly what `route` holds.
     */
    @Test
    fun `every route is unique, so no destination shadows another`() {
        val byRoute = allDestinations.groupBy { it.route }.filterValues { it.size > 1 }
        assertTrue(
            "routes shared by more than one destination: ${byRoute.mapValues { e -> e.value.map { it::class.simpleName } }}",
            byRoute.isEmpty(),
        )
    }
}
