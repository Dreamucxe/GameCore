package com.gamecore.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.gamecore.aimlab.ui.flick.FlickScreen
import com.gamecore.aimlab.ui.gyro.GyroScreen
import com.gamecore.aimlab.ui.history.HistoryScreen
import com.gamecore.aimlab.ui.input.ApplyAimLabOrientation
import com.gamecore.aimlab.ui.home.AimLabHomeScreen
import com.gamecore.aimlab.ui.hud.ControlEditorScreen
import com.gamecore.aimlab.ui.movement.MovementScreen
import com.gamecore.aimlab.ui.practice.PracticeScreen
import com.gamecore.aimlab.ui.reaction.ReactionScreen
import com.gamecore.aimlab.ui.recoil.RecoilScreen
import com.gamecore.aimlab.ui.records.RecordsScreen
import com.gamecore.aimlab.ui.results.ResultsScreen
import com.gamecore.aimlab.ui.results.ResultsState
import com.gamecore.aimlab.ui.sensitivity.SensitivityLabScreen
import com.gamecore.aimlab.ui.stats.StatisticsScreen
import com.gamecore.aimlab.ui.tracking.TrackingScreen
import com.gamecore.aimlab.ui.weapon.WeaponEditorScreen
import com.gamecore.domain.setup.WizardEntry
import com.gamecore.ui.capability.CapabilityScreen
import com.gamecore.ui.color.ColorScreen
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.controller.ControllerScreen
import com.gamecore.ui.crosshair.CrosshairScreen
import com.gamecore.ui.developer.DeveloperScreen
import com.gamecore.ui.games.GamesScreen
import com.gamecore.ui.games.ProfileEditorScreen
import com.gamecore.ui.home.HomeScreen
import com.gamecore.ui.hud.HudEditorScreen
import com.gamecore.ui.hud.HudScreen
import com.gamecore.ui.media.MediaAccessScreen
import com.gamecore.ui.motion.MotionScreen
import com.gamecore.ui.overlay.OverlayScreen
import com.gamecore.ui.performance.PerformanceScreen
import com.gamecore.ui.permissions.PermissionsScreen
import com.gamecore.ui.quickapps.QuickAppsScreen
import com.gamecore.ui.sessions.SessionReportScreen
import com.gamecore.ui.sessions.SessionsScreen
import com.gamecore.ui.settings.NeverCloseScreen
import com.gamecore.ui.settings.SettingsScreen
import com.gamecore.ui.setup.SetupHealthScreen
import com.gamecore.ui.setup.SetupWizardScreen
import com.gamecore.ui.shizuku.ShizukuScreen
import com.gamecore.ui.storage.GameStorageScreen
import com.gamecore.ui.theme.GameCoreTheme
import com.gamecore.ui.tools.ToolsScreen
import com.gamecore.ui.touch.TouchScreen
import com.gamecore.ui.trigger.QuickTriggerScreen
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
 *    directly under the status bar is this one. The bottom inset is applied once, to the column that holds
 *    the bar and the banner, rather than by either of them: the element that has to clear the gesture area
 *    is whichever of the two is lowest, and that changes with the screen.
 *  - **The bar floats over the content** (§27), which is why every screen ends its scroll with
 *    `ScreenBottomPadding` rather than the bar reserving space in the layout.
 *
 * The bar shows on the [Destination.Top] routes and slides away everywhere else. That is not decoration: a
 * screen reached with a back arrow has a place to go back to, and offering the tabs on top of it would
 * leave the user unsure which of the two gestures keeps their unsaved profile edit.
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
    // §A1. How the setup wizard should present this launch, or null until the facts have loaded. The root
    // acts on exactly one value — a fresh install — and the Home card owns the rest.
    val launchEntry by viewModel.launchEntry.collectAsStateWithLifecycle()
    // The bar Aim Lab's own switch decides the shape of: five tabs while the section is on, four while it
    // is off, so the bar never offers a tab whose route is not in the graph (§9).
    val tabs = Destination.topFor(settings.aimLabEnabled)
    val onTab = tabs.any { it.route == route }

    // One navigator for both the bar and the screens, so a tab tap and a card tap cannot end up with
    // different back-stack rules for the same destination.
    val open: (Destination) -> Unit = remember(navController) { { navController.open(it) } }

    // A screen an intent asked for, pushed on top of Home rather than replacing it, so the back gesture
    // out of it lands where a launch does instead of closing the app. Cleared through [onOpened] the
    // moment it is acted on: a request that stayed set would reopen the screen the first time the user
    // navigated away from it.
    LaunchedEffect(requested) {
        val destination = requested ?: return@LaunchedEffect
        navController.openUnguarded(destination)
        onOpened()
    }

    // §A1. A fresh install is taken straight into the wizard, once. `openUnguarded` for the same reason the
    // intent request above uses it — this is the app moving itself on a cold start, before Home has settled,
    // so the tap guard would drop it — and `launchSingleTop` there stops a second push if this recomposes
    // while the wizard is already on top. The `rememberSaveable` latch is what makes it *once*: it survives
    // rotation and process death, so a user who rotates the phone on the welcome step is not thrown back to
    // it. Completing or dismissing the wizard flips [launchEntry] away from FullWizard, so returning to Home
    // never reopens it; null — the not-yet-loaded state — is deliberately not FullWizard, so the wizard does
    // not flash open over an upgrader's Home before their profiles have been read.
    var routedToWizard by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(launchEntry) {
        if (!routedToWizard && launchEntry == WizardEntry.FullWizard) {
            routedToWizard = true
            navController.openUnguarded(Destination.SetupWizard)
        }
    }

    // Turning Aim Lab on or off changes which routes exist at all, and a NavHost whose graph changes drops
    // its back stack and returns to the start destination. The switch that does it lives on the Settings
    // screen, so without this the user's own tap would throw them out to the dashboard — a rebuild they did
    // not ask for reading as a glitch. Nothing fires on the first composition, or on any other settings
    // change: only a value that differs from the one the graph was last built with counts as a flip.
    var aimLabInGraph by remember { mutableStateOf(settings.aimLabEnabled) }
    LaunchedEffect(settings.aimLabEnabled) {
        if (settings.aimLabEnabled == aimLabInGraph) return@LaunchedEffect
        aimLabInGraph = settings.aimLabEnabled
        navController.openUnguarded(Destination.Settings)
    }

    GameCoreTheme(settings) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(modifier = Modifier.fillMaxSize()) {
                GameCoreNav(
                    navController = navController,
                    open = open,
                    aimLabEnabled = settings.aimLabEnabled,
                    aimLabOrientation = settings.aimLabOrientation,
                    modifier = Modifier
                        .fillMaxSize()
                        .systemBarsPadding()
                        .displayCutoutPadding(),
                )
                // The bar is bottom-aligned and takes the navigation-bar inset, so it clears the gesture
                // area while staying anchored at the bottom where the thumb expects it.
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding(),
                ) {
                    AnimatedVisibility(
                        visible = onTab,
                        enter = fadeIn(tween(BAR_IN_MILLIS)) +
                            slideInVertically(tween(BAR_IN_MILLIS)) { it / 2 },
                        exit = fadeOut(tween(BAR_OUT_MILLIS)) +
                            slideOutVertically(tween(BAR_OUT_MILLIS)) { it / 2 },
                    ) {
                        FloatingNavBar(currentRoute = route, tabs = tabs, onSelect = open)
                    }
                }
            }
        }
    }
}

/**
 * §27's floating bottom navigation: the top destinations on a plate that clears the system bar.
 *
 * Material's own [NavigationBar] does the work, and it is used rather than a hand-rolled row of buttons
 * for the part that is not visible — the tab role and selected state a screen reader announces, which a
 * row of clickable columns would not have. The plate around it is the card treatment from everywhere else
 * in the app: `surface`, a hairline outline and no shadow, since a shadow is invisible against a
 * near-black scheme and only costs a layer.
 *
 * Its own window insets are zeroed — [NavigationBar] would otherwise pad itself from the inside and its
 * rounded corners would disappear behind the gesture area. The navigation-bar inset it needs is applied by
 * the caller instead, on the column this shares with the ad banner, because the element that has to clear
 * the gesture area is whichever one is lowest — the bar on a tab, the banner on a screen where the bar is
 * gone — and that changes with the screen. The gap that is still applied here is [BarMargin], which is the
 * plate's own breathing room and belongs to it.
 *
 * [currentRoute] is compared here rather than a [Destination.Top] being handed in, so that while the bar
 * slides away toward a screen that is not a tab, nothing is highlighted — which is the truth.
 *
 * [tabs] is passed in rather than read from [Destination.top] here, because the set is not constant: Aim
 * Lab can be switched off, and the bar has to be four items on the frame that happens rather than five
 * with one that goes nowhere. The caller owns the settings, so the caller decides.
 */
@Composable
private fun FloatingNavBar(
    currentRoute: String?,
    tabs: List<Destination.Top>,
    onSelect: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
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
            tabs.forEach { destination ->
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
 *
 * [aimLabEnabled] is the one thing here that changes the *shape* of the graph rather than what a route
 * resolves to. See the section at the bottom for why the switch is applied here and nowhere else.
 */
@Composable
private fun GameCoreNav(
    navController: NavHostController,
    open: (Destination) -> Unit,
    aimLabEnabled: Boolean,
    aimLabOrientation: com.gamecore.core.model.AimLabOrientation,
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
            HomeScreen(
                onNavigate = open,
                aimLabEnabled = aimLabEnabled,
                onOpenProfile = { name -> navController.push(Destination.ProfileEditor.routeFor(name)) },
            )
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
                onBack = back,
            )
        }

        composable(Destination.Sessions.route) {
            SessionsScreen(
                onOpenReport = { id -> navController.push(Destination.SessionReport.routeFor(id)) },
                // The history merges both repositories, so a row can be an Aim Lab run, and a run's report
                // is Aim Lab's own results screen. That route is only registered while Aim Lab is enabled;
                // the screen already knows that and stops offering the tap when it is off.
                onOpenAimLabRun = { id -> navController.push(Destination.AimLabResults.routeFor(id)) },
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
        // `openUnguarded` exists — see [Destination.Colour].
        composable(Destination.Colour.route) {
            ColorScreen(onBack = back, onNavigate = open)
        }

        // The other one an intent can ask for, from the panel's media strip. See [Destination.MediaAccess].
        composable(Destination.MediaAccess.route) {
            MediaAccessScreen(onBack = back, onNavigate = open)
        }

        composable(Destination.Shizuku.route) {
            ShizukuScreen(onNavigate = open)
        }

        composable(Destination.Permissions.route) {
            PermissionsScreen(onBack = back)
        }

        // §A2. The wizard pops itself rather than taking a back callback: every way out of it — Finish,
        // Skip setup, or backing out of the confirm — means the same thing to the stack, and a screen
        // with three exits that all do one thing should express that once.
        composable(Destination.SetupWizard.route) {
            SetupWizardScreen(onFinished = back)
        }

        // §A3. `onAddGame` is the one thing this screen cannot do with `onNavigate`: the "no games yet"
        // row has to land on a *new* profile, which is a parameterised route rather than a Destination.
        composable(Destination.SetupHealth.route) {
            SetupHealthScreen(
                onBack = back,
                onNavigate = open,
                onAddGame = {
                    navController.push(Destination.ProfileEditor.routeFor(Destination.NEW_PROFILE))
                },
            )
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

        composable(Destination.Motion.route) {
            MotionScreen(onBack = back)
        }

        composable(Destination.Touch.route) {
            TouchScreen(onBack = back)
        }

        composable(Destination.Controller.route) {
            ControllerScreen(onBack = back)
        }

        composable(Destination.Capability.route) {
            CapabilityScreen(onBack = back)
        }

        composable(Destination.QuickTrigger.route) {
            QuickTriggerScreen(onBack = back)
        }

        composable(Destination.QuickApps.route) {
            QuickAppsScreen(onBack = back)
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

        // ------------------------------------------------------------------------------- Aim Lab
        //
        // §38's master switch, applied where it can actually disable something. With the setting off the
        // section's fifteen routes are never added to the graph, so there is nothing to land on: a saved
        // back-stack entry or a stale link naming one resolves to nothing and the user ends up on Home,
        // exactly as an unknown route already does. Guarding inside each screen instead would leave every
        // one of them reachable and allocated, which is hiding the UI rather than disabling the feature.
        //
        // The Aim Lab home screen is handed the route string itself rather than a `Destination`, because
        // the section names its own routes (`AimLabRoutes`) and a grid of thirteen cards would otherwise
        // need a second mapping from card to destination that could only ever disagree with the first.
        if (aimLabEnabled) {
            composable(Destination.AimLabHome.route) {
                // No back arrow: this is a tab now (§9), and the bar is underneath it. A header arrow on a
                // tab would offer a second, different way out of a screen the user did not arrive at from
                // anywhere in particular.
                AimLabHomeScreen(onOpen = { route -> navController.push(route) })
            }

            // Every training screen forces the user's Aim Lab orientation while it is on top and restores
            // the previous one on exit (§1); the HUD editor forces its own orientation while editing, so it
            // is not wrapped here. The rest of GameCore keeps whatever orientation it had.
            composable(Destination.AimLabFlick.route) {
                ApplyAimLabOrientation(aimLabOrientation); FlickScreen(onBack = back)
            }
            composable(Destination.AimLabTracking.route) {
                ApplyAimLabOrientation(aimLabOrientation); TrackingScreen(onBack = back)
            }
            composable(Destination.AimLabReaction.route) {
                ApplyAimLabOrientation(aimLabOrientation); ReactionScreen(onBack = back)
            }
            composable(Destination.AimLabGyro.route) {
                ApplyAimLabOrientation(aimLabOrientation); GyroScreen(onBack = back)
            }
            composable(Destination.AimLabRecoil.route) {
                ApplyAimLabOrientation(aimLabOrientation); RecoilScreen(onBack = back)
            }
            composable(Destination.AimLabMovement.route) {
                ApplyAimLabOrientation(aimLabOrientation); MovementScreen(onBack = back)
            }
            composable(Destination.AimLabPractice.route) {
                ApplyAimLabOrientation(aimLabOrientation); PracticeScreen(onBack = back)
            }

            composable(Destination.AimLabSensitivity.route) { SensitivityLabScreen(onBack = back) }
            composable(Destination.AimLabWeapon.route) { WeaponEditorScreen(onBack = back) }
            composable(Destination.AimLabControls.route) { ControlEditorScreen(onBack = back) }

            composable(Destination.AimLabStats.route) { StatisticsScreen(onBack = back) }

            // `onTrain` is left at its default, which is `onBack`: records is reached from the Aim Lab
            // home screen, so going back is going to the list of modes the user is being invited to run.
            composable(Destination.AimLabRecords.route) { RecordsScreen(onBack = back) }

            composable(Destination.AimLabHistory.route) {
                HistoryScreen(
                    onOpenSession = { id -> navController.push(Destination.AimLabResults.routeFor(id)) },
                    onBack = back,
                )
            }

            composable(
                route = Destination.AimLabResults.route,
                arguments = listOf(navArgument(Destination.ARG_ID) { type = NavType.LongType }),
            ) { entry ->
                // The ViewModel reads the same id out of its `SavedStateHandle`; this is the argument the
                // screen's own parameter takes, and the fallback is the sentinel rather than zero so that a
                // route somehow arriving without an id says "that session is not here" instead of opening
                // a report on whatever row id zero would match.
                ResultsScreen(
                    sessionId = entry.arguments?.getLong(Destination.ARG_ID) ?: ResultsState.MISSING_ID,
                    onBack = back,
                )
            }
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
 * Goes to one screen without the settle guard the taps use.
 *
 * Both callers are the app moving itself rather than answering a tap, and [isSettled] is there to tell a
 * second tap from a second destination. On a cold start an intent's request arrives before Home has
 * finished its cross-fade, and a graph rebuilt under the Aim Lab switch has just reset the back stack — in
 * either case the guard would drop the move and the screen the user is owed would be silently forgotten,
 * which is the §32 failure. `launchSingleTop` covers the only duplicate either can produce, which is the
 * same screen asked for twice while it is already on top.
 */
private fun NavHostController.openUnguarded(destination: Destination) {
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
