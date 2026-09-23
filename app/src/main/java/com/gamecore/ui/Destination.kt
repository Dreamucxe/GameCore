package com.gamecore.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.ui.graphics.vector.ImageVector
import com.gamecore.aimlab.ui.home.AimLabRoutes

/**
 * Every place the app can be, as a sealed set of routes.
 *
 * Routes are declared here rather than as string literals at the call sites so that a destination and
 * the navigation to it cannot disagree about spelling — the compiler resolves `Destination.Sessions.route`
 * and there is no such thing as a typo that only shows up as a silent no-op tap on a user's device.
 *
 * Arguments are built by [route] functions that take the value, so the encoding of an id into a path is
 * also in one place. [GameCoreNav] parses them back out.
 */
sealed interface Destination {

    val route: String

    /**
     * The five that live on the bottom bar: Home, Games, Aim Lab, Sessions, Settings, in that order (§9).
     *
     * Being a [Top] is also how [GameCoreNav] decides *how* to navigate — tabs replace each other and keep
     * their scroll position, everything else is pushed onto a back stack — so this is a statement about
     * behaviour, not only about where an icon is drawn.
     */
    sealed interface Top : Destination {
        val label: String
        val icon: ImageVector
    }

    data object Home : Top {
        override val route = "home"
        override val label = "Home"
        override val icon = Icons.Filled.Home
    }

    data object Games : Top {
        override val route = "games"
        override val label = "Games"
        override val icon = Icons.Filled.SportsEsports
    }

    data object Sessions : Top {
        override val route = "sessions"
        override val label = "Sessions"
        override val icon = Icons.Filled.History
    }

    data object Settings : Top {
        override val route = "settings"
        override val label = "Settings"
        override val icon = Icons.Filled.Settings
    }

    /** The rest, reached from the five above. */

    /*
     * The HUD builder used to hold the third tab. §9 gives that slot to Aim Lab, which is a whole section
     * a user returns to, where the HUD list is somewhere you go once to build a layout and then leave. The
     * route string is untouched, so nothing that names it breaks; what changes is that it is now pushed
     * rather than swapped in, which is why [com.gamecore.ui.hud.HudScreen] grew a back arrow. It keeps all
     * three of its entry points: Home's "Everything else" card, the profile editor's empty state and the
     * Overlays card in Settings.
     */
    data object Hud : Destination {
        override val route = "hud"
    }

    data object Performance : Destination {
        override val route = "performance"
    }

    data object Crosshair : Destination {
        override val route = "crosshair"
    }

    data object Shizuku : Destination {
        override val route = "shizuku"
    }

    data object Permissions : Destination {
        override val route = "permissions"
    }

    /**
     * The first-run setup wizard (spec §A2).
     *
     * Pushed like any other screen rather than given its own navigation graph or a pre-Activity: it is
     * reachable from three places — a fresh install, the Home card, and Settings — and a user who reaches
     * it the third way is in the middle of a session they should be able to back out of. Making it a
     * destination is what keeps the back stack honest about that.
     */
    data object SetupWizard : Destination {
        override val route = "setup-wizard"
    }

    /**
     * The setup health screen (spec §A3): what is set up, what is not, and what each gap costs.
     *
     * Separate from the wizard because it answers a different question. The wizard walks a user through
     * choices once; this says where things stand now, and stays useful long after setup is finished —
     * which is why it lives in Settings and not behind the first-run flag.
     */
    data object SetupHealth : Destination {
        override val route = "setup-health"
    }

    data object Tools : Destination {
        override val route = "tools"
    }

    data object Overlay : Destination {
        override val route = "overlay"
    }

    /** Live gyroscope / accelerometer readings and the optional aim-motion summary. Category: INPUT. */
    data object Motion : Destination {
        override val route = "motion"
    }

    /** The touch heatmap: only the touches this app can legitimately observe. Category: INPUT. */
    data object Touch : Destination {
        override val route = "touch"
    }

    /** Attached controllers, their axes, and the input they are sending. Category: INPUT. */
    data object Controller : Destination {
        override val route = "controller"
    }

    /** GPU and video-codec capabilities as Android actually exposes them. Category: HARDWARE. */
    data object Capability : Destination {
        override val route = "capability"
    }

    /** The shortcut that opens GameCore, and what each way of firing it costs. Category: GAMECORE. */
    data object QuickTrigger : Destination {
        override val route = "quick-trigger"
    }

    /**
     * The apps a profile's "free RAM on launch" pass will never close.
     *
     * Reached from Settings and not from the profile editor, even though the switch that gives it a
     * purpose is per-game: the list itself is one list for the whole device, and a per-game screen that
     * edited a global setting would read as a per-game one.
     */
    data object NeverClose : Destination {
        override val route = "never-close"
    }

    /**
     * What each game is holding in cache, and the one part of it GameCore can delete.
     *
     * Reached from Settings rather than from a game's profile, for the same reason [NeverClose] is: the
     * screen measures every game on the device at once, and the delete it offers is not a setting that
     * gets applied when a game launches. Nothing about it belongs to one profile.
     */
    data object GameStorage : Destination {
        override val route = "game-storage"
    }


    /**
     * The apps the control panel's quick-launch row offers, and the order they sit in.
     *
     * Reached from the Overlay screen's control-panel card and from nowhere else — it configures one part
     * of one window, unlike [NeverClose] and [GameStorage], which are device-wide lists that happen to be
     * reached from Settings. Not in [external] either: nothing outside the app has a reason to ask for a
     * screen that only edits a row of icons.
     */
    data object QuickApps : Destination {
        override val route = "quick-apps"
    }

    /**
     * Who wrote this and where to find them. The last row of Settings.
     *
     * Not in [external], and it would be harmless there — the screen holds three of its own addresses and
     * reads nothing from an intent. It stays out because nothing outside the app has a reason to ask for it.
     */
    data object Developer : Destination {
        override val route = "developer"
    }

    /**
     * The colour correction editor: fourteen values, the gamma mode and the preset library.
     *
     * The one destination reachable from outside the app — the overlay panel's colour tile opens it while
     * a game is in front, because the panel has room for three sliders and not for the editor. [EXTERNAL]
     * is the token that travels in the intent; see [fromExternal] for why it is a token and not a route.
     */
    data object Colour : Destination {
        override val route = "colour"

        const val EXTERNAL = "colour"
    }

    /**
     * What the media strip in the overlay panel reads, and the access it needs to read it.
     *
     * The second destination reachable from outside the app, and for the same reason [Colour] is the
     * first: the overlay panel is over a running game and has room for a sentence, while the decision the
     * user is being asked to make — grant notification listener access — deserves the full explanation
     * that only a screen can hold. The strip's "Enable" opens this, and this opens the system page.
     */
    data object MediaAccess : Destination {
        override val route = "media-access"

        const val EXTERNAL = "media-access"
    }

    /** The profile editor. A new profile is `profile/0`, since Room ids start at 1. */
    data object ProfileEditor : Destination {
        override val route = "profile/{$ARG_PACKAGE}"
        fun routeFor(packageName: String): String = "profile/$packageName"
    }

    /** The HUD layout editor for one saved layout. */
    data object HudEditor : Destination {
        override val route = "hud/{$ARG_ID}"
        fun routeFor(layoutId: Long): String = "hud/$layoutId"
    }

    /** One session's report. */
    data object SessionReport : Destination {
        override val route = "session/{$ARG_ID}"
        fun routeFor(sessionId: Long): String = "session/$sessionId"
    }

    // --------------------------------------------------------------------------------- Aim Lab

    /*
     * The Aim Lab section. Flat, like everything above it — thirteen argument-less screens plus one that
     * carries a session id — and the route strings come from `AimLabRoutes` rather than being spelled here,
     * because the section's own UI names those constants when it asks to be navigated.
     *
     * These are the only destinations in this file that are registered conditionally: `GameCoreNav` adds
     * them to the graph only while `AppSettings.aimLabEnabled` is true. Declaring them unconditionally is
     * correct — a `Destination` is a name for a screen, not a promise that the screen is currently in the
     * graph — and a route that is not registered behaves exactly like an unknown one: it lands on Home.
     * That fallback is the safety net rather than the plan: [topFor] is what actually keeps the disabled
     * section off the bar, so the fallback is never the thing a user meets.
     *
     * None of them is in [external]. The section reads sensors and draws a full-screen arena; nothing
     * outside the app has a reason to be able to open one, and the closed map is what keeps that true.
     */

    /**
     * The section's front door, and the third tab (§9).
     *
     * It is a [Top] even though the section can be switched off, because what a destination *is* does not
     * change with a setting. What changes is whether the bar offers it: [topFor] drops it while Aim Lab is
     * disabled, so the bar never shows a tab whose route is not in the graph.
     */
    data object AimLabHome : Top {
        override val route = AimLabRoutes.HOME
        override val label = "Aim Lab"
        override val icon = Icons.Filled.TrackChanges
    }

    data object AimLabFlick : Destination {
        override val route = AimLabRoutes.FLICK
    }

    data object AimLabTracking : Destination {
        override val route = AimLabRoutes.TRACKING
    }

    data object AimLabReaction : Destination {
        override val route = AimLabRoutes.REACTION
    }

    data object AimLabGyro : Destination {
        override val route = AimLabRoutes.GYRO
    }

    data object AimLabRecoil : Destination {
        override val route = AimLabRoutes.RECOIL
    }

    data object AimLabMovement : Destination {
        override val route = AimLabRoutes.MOVEMENT
    }

    data object AimLabPractice : Destination {
        override val route = AimLabRoutes.PRACTICE
    }

    data object AimLabSensitivity : Destination {
        override val route = AimLabRoutes.SENSITIVITY
    }

    data object AimLabWeapon : Destination {
        override val route = AimLabRoutes.WEAPON
    }

    data object AimLabControls : Destination {
        override val route = AimLabRoutes.CONTROLS
    }

    data object AimLabStats : Destination {
        override val route = AimLabRoutes.STATS
    }

    data object AimLabRecords : Destination {
        override val route = AimLabRoutes.RECORDS
    }

    data object AimLabHistory : Destination {
        override val route = AimLabRoutes.HISTORY
    }

    /**
     * One Aim Lab session's report, reached from the session list with that session's row id.
     *
     * Encoded the same way [SessionReport] is, and read back out the same way: [GameCoreNav] declares the
     * argument as a `Long`, and the ViewModel accepts a string as well, so a route that arrives from
     * anywhere else still resolves rather than opening a report on session zero.
     */
    data object AimLabResults : Destination {
        override val route = "${AimLabRoutes.RESULTS}/{$ARG_ID}"
        fun routeFor(sessionId: Long): String = "${AimLabRoutes.RESULTS}/$sessionId"
    }

    companion object {
        const val ARG_ID = "id"
        const val ARG_PACKAGE = "package"

        /**
         * The bottom bar, in order: Home, Games, Aim Lab, Sessions, Settings (§9).
         *
         * Prefer [topFor] at any call site that can see the settings — this list is the full set, and the
         * full set is only correct while Aim Lab is enabled.
         */
        val top: List<Top> = listOf(Home, Games, AimLabHome, Sessions, Settings)

        /**
         * The bar as it should actually be drawn.
         *
         * Aim Lab is the one section a user can switch off, and `GameCoreNav` registers its routes only
         * while it is on. A tab for an unregistered route is not a small cosmetic wrong: tapping it would
         * land the user on Home with no explanation of why the thing they asked for did not open. So when
         * the section is off the bar is four tabs, not five with a dead one — and the slot comes back the
         * moment the switch does, because the bar is derived from the setting rather than stored.
         *
         * Nothing else is conditional. The other four are always present, always in this order.
         */
        fun topFor(aimLabEnabled: Boolean): List<Top> =
            if (aimLabEnabled) top else top.filterNot { it == AimLabHome }

        /** A new profile: the editor opens with nothing selected and asks for an app. */
        const val NEW_PROFILE = "new"

        /** A new HUD layout. Not a real row id, so it cannot collide with one. */
        const val NEW_LAYOUT = 0L

        /**
         * The destinations GameCore is willing to be opened *at* by an intent, and nothing else.
         *
         * [MainActivity] is exported — it has to be, it is the launcher activity — so the extra that
         * carries this arrives from wherever the sender likes. A closed map is what makes that safe: the
         * only thing an intent can do is name one of these tokens, and anything else lands on Home. No
         * route string, no id, no package name and no path is ever taken from an intent, so there is
         * nothing for a caller to smuggle a value through.
         *
         * A token rather than the route itself, so that a route can be renamed — or given an argument —
         * without changing what outside callers are allowed to ask for.
         */
        private val external: Map<String, Destination> = mapOf(
            Colour.EXTERNAL to Colour,
            MediaAccess.EXTERNAL to MediaAccess,
        )

        /** The destination an intent asked for, or null for anything unrecognised. */
        fun fromExternal(token: String?): Destination? = external[token?.trim()]
    }
}
