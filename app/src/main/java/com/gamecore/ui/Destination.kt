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
    }
}
