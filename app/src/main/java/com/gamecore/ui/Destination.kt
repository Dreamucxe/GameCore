package com.gamecore.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.ui.graphics.vector.ImageVector

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

    /** The five that live on the bottom bar. §27 asks for exactly these, in this order. */
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

    data object Hud : Top {
        override val route = "hud"
        override val label = "HUD"
        override val icon = Icons.Filled.GridView
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

    data object Tools : Destination {
        override val route = "tools"
    }

    data object Overlay : Destination {
        override val route = "overlay"
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

    companion object {
        const val ARG_ID = "id"
        const val ARG_PACKAGE = "package"

        val top: List<Top> = listOf(Home, Games, Hud, Sessions, Settings)

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
        private val external: Map<String, Destination> = mapOf(Colour.EXTERNAL to Colour)

        /** The destination an intent asked for, or null for anything unrecognised. */
        fun fromExternal(token: String?): Destination? = external[token?.trim()]
    }
}
