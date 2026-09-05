package com.gamecore.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gamecore.ui.color.ColorScreen
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.crosshair.CrosshairScreen
import com.gamecore.ui.developer.DeveloperScreen
import com.gamecore.ui.games.GamesScreen
import com.gamecore.ui.games.ProfileEditorScreen
import com.gamecore.ui.home.HomeScreen
import com.gamecore.ui.hud.HudEditorScreen
import com.gamecore.ui.hud.HudScreen
import com.gamecore.ui.overlay.OverlayScreen
import com.gamecore.ui.performance.PerformanceScreen
import com.gamecore.ui.permissions.PermissionsScreen
import com.gamecore.ui.sessions.SessionReportScreen
import com.gamecore.ui.sessions.SessionsScreen
import com.gamecore.ui.settings.NeverCloseScreen
import com.gamecore.ui.settings.SettingsScreen
import com.gamecore.ui.shizuku.ShizukuScreen
import com.gamecore.ui.storage.GameStorageScreen
import com.gamecore.ui.theme.GameCoreTheme
import com.gamecore.ui.tools.ToolsScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The whole app above the screens: the theme, the back stack, and the bar that floats over both.
 *
 * `MainActivity` calls this and nothing else, so what is here is what a single-activity app has to
 * decide once rather than per screen:
 *
 *  - **The theme is the user's, observed.** [RootViewModel] carries the three appearance settings, and
 *    because the Settings screen sits inside this theme, a change to the accent or the UI scale lands on
 *    the screen doing the editing.
 *  - **The window insets are applied here.** `ScreenHeader` applies none deliberately — a screen that
 *    inset itself would inset twice inside a dialog or a preview — so the one place that knows it is
 *    directly under the status bar is this one. The bar takes its own, separately, because it is a
 *    sibling of the content rather than part of it.
 *  - **The bar floats over the content** (§27), which is why every screen ends its scroll with
 *    `ScreenBottomPadding` rather than the bar reserving space in the layout.
 *
 * The bar shows on the five [Destination.Top] routes and slides away everywhere else. That is not
 * decoration: a screen reached with a back arrow has a place to go back to, and offering five tabs on top
 * of it would leave the user unsure which of the two gestures keeps their unsaved profile edit.
 */
@Composable
fun GameCoreRoot(
    viewModel: RootViewModel = hiltViewModel(),
    openAt: StateFlow<Destination?> = remember { MutableStateFlow<Destination?>(null) },
    onOpened: () -> Unit = {},
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val requested by openAt.collectAsStateWithLifecycle()
    val onTab = Destination.top.any { it.route == route }

    // One navigator for both the bar and the screens, so a tab tap and a card tap cannot end up with
    // different back-stack rules for the same destination.
    val open: (Destination) -> Unit = remember(navController) { { navController.open(it) } }

    // A screen an intent asked for, pushed on top of Home rather than replacing it, so the back gesture
    // out of it lands where a launch does instead of closing the app. Cleared through [onOpened] the
    // moment it is acted on: a request that stayed set would reopen the screen the first time the user
    // navigated away from it.
    LaunchedEffect(requested) {
        val destination = requested ?: return@LaunchedEffect
        navController.openFromIntent(destination)
        onOpened()
    }

    GameCoreTheme(settings) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(modifier = Modifier.fillMaxSize()) {
                GameCoreNav(
                    navController = navController,
                    open = open,
                    modifier = Modifier
                        .fillMaxSize()
                        .systemBarsPadding()
                        .displayCutoutPadding(),
                )
                AnimatedVisibility(
                    visible = onTab,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = fadeIn(tween(BAR_IN_MILLIS)) + slideInVertically(tween(BAR_IN_MILLIS)) { it / 2 },
                    exit = fadeOut(tween(BAR_OUT_MILLIS)) + slideOutVertically(tween(BAR_OUT_MILLIS)) { it / 2 },
                ) {
                    FloatingNavBar(currentRoute = route, onSelect = open)
                }
            }
        }
    }
}

/**
 * §27's floating bottom navigation: the five top destinations on a plate that clears the system bar.
 *
 * Material's own [NavigationBar] does the work, and it is used rather than a hand-rolled row of buttons
 * for the part that is not visible — the tab role and selected state a screen reader announces, which a
 * row of clickable columns would not have. The plate around it is the card treatment from everywhere else
 * in the app: `surface`, a hairline outline and no shadow, since a shadow is invisible against a
 * near-black scheme and only costs a layer.
 *
 * Its own window insets are zeroed and taken by the plate instead. That is the difference between a bar
 * that floats above the navigation bar and one that is padded from the inside, with its rounded corners
 * disappearing behind the gesture area.
 *
 * [currentRoute] is compared here rather than a [Destination.Top] being handed in, so that while the bar
 * slides away toward a screen that is not a tab, nothing is highlighted — which is the truth.
 */
@Composable
private fun FloatingNavBar(
    currentRoute: String?,
    onSelect: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = ScreenPadding, vertical = BarMargin),
        shape = MaterialTheme.shapes.extraLarge,
        color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        NavigationBar(
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
            windowInsets = WindowInsets(0, 0, 0, 0),
        ) {
            Destination.top.forEach { destination ->
                NavigationBarItem(
                    selected = destination.route == currentRoute,
                    onClick = { onSelect(destination) },
                    // Null description: the label beside it names the tab, and the item merges the two
                    // into one announcement rather than reading the name twice.
                    icon = { Icon(imageVector = destination.icon, contentDescription = null) },
                    label = { Text(text = destination.label, maxLines = 1) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = scheme.primary,
                        selectedTextColor = scheme.primary,
                        indicatorColor = scheme.primaryContainer,
                        unselectedIconColor = scheme.onSurfaceVariant,
                        unselectedTextColor = scheme.onSurfaceVariant,
                    ),
                )
            }
        }
    }
}

/**
 * Every route, wired to the screen that answers it.
 *
 * [open] is handed down rather than the controller itself, so a screen names a [Destination] and the
 * back-stack rules stay in one place — the same boundary §25 draws around system access, applied to
 * navigation. The three routes that carry an argument are the exception, because only the graph knows how
 * a value is encoded into a path: `Destination.routeFor` does the encoding and [navArgument] below
 * declares the type the ViewModel will read back out.
 *
 * `NavType.LongType` on the two id routes is what lets `SavedStateHandle.get<Long>` succeed. Both of
 * those ViewModels also accept a string and parse it, which is the braces to this belt — a route reached
 * from anywhere else still resolves rather than opening an editor on layout zero.
 *
 * The transition is a short cross-fade in both directions. It is also load-bearing: an entry is not
 * `RESUMED` until it settles, and [isSettled] uses exactly that to tell a second tap from a second
 * destination.
 */
@Composable
private fun GameCoreNav(
    navController: NavHostController,
    open: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val back: () -> Unit = remember(navController) { { navController.back() } }

    NavHost(
        navController = navController,
        startDestination = Destination.Home.route,
        modifier = modifier,
        enterTransition = { fadeIn(tween(SCREEN_IN_MILLIS)) },
        exitTransition = { fadeOut(tween(SCREEN_OUT_MILLIS)) },
    ) {
        composable(Destination.Home.route) {
            HomeScreen(onNavigate = open)
        }

        composable(Destination.Games.route) {
            GamesScreen(
                onOpenProfile = { name -> navController.push(Destination.ProfileEditor.routeFor(name)) },
                onNavigate = open,
            )
        }

        composable(Destination.Hud.route) {
            HudScreen(
                onOpenEditor = { id -> navController.push(Destination.HudEditor.routeFor(id)) },
                onNavigate = open,
            )
        }

        composable(Destination.Sessions.route) {
            SessionsScreen(
                onOpenReport = { id -> navController.push(Destination.SessionReport.routeFor(id)) },
                onNavigate = open,
            )
        }

        composable(Destination.Settings.route) {
            SettingsScreen(onNavigate = open)
        }

        composable(Destination.Performance.route) {
            PerformanceScreen(onNavigate = open)
        }

        composable(Destination.Crosshair.route) {
            CrosshairScreen(onBack = back, onNavigate = open)
        }

        // Reached from the overlay panel's colour tile as well as from inside the app, which is why
        // `openFromIntent` exists — see [Destination.Colour].
        composable(Destination.Colour.route) {
            ColorScreen(onBack = back, onNavigate = open)
        }

        composable(Destination.Shizuku.route) {
            ShizukuScreen(onNavigate = open)
        }

        composable(Destination.Permissions.route) {
            PermissionsScreen(onBack = back)
        }

        composable(Destination.Tools.route) {
            ToolsScreen(onBack = back)
        }

        composable(Destination.Developer.route) {
            DeveloperScreen(onBack = back)
        }

        composable(Destination.Overlay.route) {
            OverlayScreen(onBack = back, onNavigate = open)
        }

        composable(Destination.NeverClose.route) {
            NeverCloseScreen(onBack = back)
        }

        composable(Destination.GameStorage.route) {
            GameStorageScreen(onBack = back, onNavigate = open)
        }

        composable(
            route = Destination.ProfileEditor.route,
            arguments = listOf(navArgument(Destination.ARG_PACKAGE) { type = NavType.StringType }),
        ) {
            ProfileEditorScreen(onBack = back, onNavigate = open)
        }

        composable(
            route = Destination.HudEditor.route,
            arguments = listOf(navArgument(Destination.ARG_ID) { type = NavType.LongType }),
        ) {
            HudEditorScreen(onBack = back)
        }

        composable(
            route = Destination.SessionReport.route,
            arguments = listOf(navArgument(Destination.ARG_ID) { type = NavType.LongType }),
        ) {
            SessionReportScreen(onBack = back)
        }
    }
}

/**
 * Whether the back stack has finished its last move.
 *
 * An entry reaches `RESUMED` only once the transition into it has settled, which makes this the cheapest
 * honest way to tell a second tap on a row from a request for a second destination. Without it, a double
 * tap on "Session history" pushes the same report twice and the first back press looks like it did
 * nothing, and a tap that lands mid-transition opens a screen the user cannot see they asked for.
 */
private fun NavHostController.isSettled(): Boolean =
    currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED

/**
 * Goes to one destination, with the rules that destination deserves.
 *
 * The five tabs replace each other: everything above Home is popped, the outgoing tab's scroll position
 * is saved and the incoming one's restored, and Home is left underneath so the system back gesture from
 * any tab lands where a launch does. Home is reused rather than stacked on itself.
 *
 * Everything else is pushed, because it was opened *from* somewhere and the back arrow in its header has
 * to have somewhere to return to. That is also why a tab tapped from a detail screen still pops it: the
 * user asked for the HUD list, not for the HUD list on top of a half-edited profile.
 */
private fun NavHostController.open(destination: Destination) {
    if (destination !is Destination.Top) {
        push(destination.route)
        return
    }
    if (!isSettled()) return
    navigate(destination.route) {
        popUpTo(Destination.Home.route) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Pushes one route, id and all, encoded by the [Destination] that owns the encoding. */
private fun NavHostController.push(route: String) {
    if (!isSettled()) return
    navigate(route) { launchSingleTop = true }
}

/**
 * Opens a screen an intent asked for, without the settle guard the taps use.
 *
 * [isSettled] is there to tell a second tap from a second destination, and this is neither: on a cold
 * start the request arrives before Home has finished its cross-fade, so the guard would drop it and the
 * intent the user sent from the overlay would be silently forgotten — a tile that does nothing, which is
 * the §32 failure. `launchSingleTop` covers the only duplicate this can produce, which is the same screen
 * asked for twice while it is already on top.
 */
private fun NavHostController.openFromIntent(destination: Destination) {
    navigate(destination.route) { launchSingleTop = true }
}

/**
 * The back arrow in a screen header.
 *
 * Guarded like the rest, for the same reason: two quick taps would otherwise pop twice and take the user
 * a screen further back than they pressed for. Nothing calls this from a tab, so there is no stack for it
 * to empty and no blank window at the end of one.
 */
private fun NavHostController.back() {
    if (!isSettled()) return
    popBackStack()
}

/** The bar's rise and the cross-fade between screens: short enough to read as a response (§27). */
private const val BAR_IN_MILLIS = 220
private const val BAR_OUT_MILLIS = 140
private const val SCREEN_IN_MILLIS = 200
private const val SCREEN_OUT_MILLIS = 160

/** The gap under the plate, above the navigation bar. `ScreenBottomPadding` is sized to clear both. */
private val BarMargin = 12.dp
