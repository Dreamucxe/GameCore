package com.gamecore.aimlab.ui.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.graphics.vector.ImageVector
import com.gamecore.aimlab.engine.TrainingMode

/**
 * The route-string source of truth for the whole Aim Lab section.
 *
 * This object is the seam between the Aim Lab feature and the app's navigation. The Aim Lab UI never spells
 * a route as a literal — it names one of these constants — and the orchestrator that owns `Destination` and
 * `GameCoreRoot` reads this list to wire the section into the real nav graph. Declaring the strings once,
 * here, is what stops the section and the navigation to it from disagreeing about spelling.
 *
 * ## What the orchestrator must fold into the app's nav graph
 *
 * Every route below is a flat, argument-less destination except [RESULTS], which carries a session id. Add
 * to `com.gamecore.ui.Destination` (as flat `data object`s, in the same shape as the existing rest-routes):
 *
 * ```
 * data object AimLabHome : Destination        { override val route = AimLabRoutes.HOME }
 * data object AimLabFlick : Destination       { override val route = AimLabRoutes.FLICK }
 * data object AimLabTracking : Destination    { override val route = AimLabRoutes.TRACKING }
 * data object AimLabReaction : Destination    { override val route = AimLabRoutes.REACTION }
 * data object AimLabGyro : Destination        { override val route = AimLabRoutes.GYRO }
 * data object AimLabRecoil : Destination      { override val route = AimLabRoutes.RECOIL }
 * data object AimLabMovement : Destination    { override val route = AimLabRoutes.MOVEMENT }
 * data object AimLabPractice : Destination    { override val route = AimLabRoutes.PRACTICE }
 * data object AimLabSensitivity : Destination { override val route = AimLabRoutes.SENSITIVITY }
 * data object AimLabWeapon : Destination      { override val route = AimLabRoutes.WEAPON }
 * data object AimLabControls : Destination    { override val route = AimLabRoutes.CONTROLS }
 * data object AimLabStats : Destination       { override val route = AimLabRoutes.STATS }
 * data object AimLabRecords : Destination     { override val route = AimLabRoutes.RECORDS }
 * data object AimLabHistory : Destination     { override val route = AimLabRoutes.HISTORY }
 * // Results carries the finished session's id, like SessionReport does:
 * data object AimLabResults : Destination {
 *     override val route = "${AimLabRoutes.RESULTS}/{${Destination.ARG_ID}}"
 *     fun routeFor(sessionId: Long): String = "${AimLabRoutes.RESULTS}/$sessionId"
 * }
 * ```
 *
 * And to `com.gamecore.ui.GameCoreRoot` (or `GameCoreNav`), one `composable(...)` per route. The home entry
 * is the section's front door — it takes the two lambdas below and nothing else:
 *
 * ```
 * composable(AimLabRoutes.HOME) {
 *     AimLabHomeScreen(
 *         onOpen = { route -> navController.navigate(route) },
 *         onBack = { navController.popBackStack() },
 *     )
 * }
 * ```
 *
 * The thirteen card destinations and the results screen are added by their owning agents; each mode screen
 * takes the id-less route above, and the session list navigates to `AimLabResults.routeFor(id)`.
 *
 * ## Section gate — `AppSettings.aimLabEnabled` (default `true`)
 *
 * The whole section is gated by a single new setting the orchestrator adds to
 * `com.gamecore.core.model.AppSettings` (`val aimLabEnabled: Boolean = true`). When it is off:
 *  - the Aim Lab entry on the main Home screen (`com.gamecore.ui.home.HomeScreen`) is hidden, and
 *  - none of the routes below are registered in the nav graph, so none is reachable — a stale deep link or
 *    a saved back-stack entry pointing at one lands on Home, exactly as an unknown route already does.
 *
 * The gate lives at the graph level rather than inside these screens: a screen that guarded itself would
 * still be a reachable, allocated destination the user could land on with an empty body.
 */
object AimLabRoutes {

    // --- the front door ---
    /** The Aim Lab HOME screen. The one entry the main Home screen links to, and the section's back-stop. */
    const val HOME = "aimlab-home"

    // --- the seven training modes (map to TrainingMode; see [AimLabDestination.mode]) ---
    const val FLICK = "aimlab-flick"
    const val TRACKING = "aimlab-tracking"
    const val REACTION = "aimlab-reaction"
    const val GYRO = "aimlab-gyro"
    const val RECOIL = "aimlab-recoil"
    const val MOVEMENT = "aimlab-movement"
    const val PRACTICE = "aimlab-practice"

    // --- setup / editors (no TrainingMode) ---
    const val SENSITIVITY = "aimlab-sensitivity"
    const val WEAPON = "aimlab-weapon"
    const val CONTROLS = "aimlab-controls"

    // --- progress (no TrainingMode) ---
    const val STATS = "aimlab-stats"
    const val RECORDS = "aimlab-records"

    /**
     * The list of stored sessions. The only route into [RESULTS], which is why it is a card of its own
     * rather than a tab inside the statistics screen: a session report has to be reachable by a back
     * gesture from somewhere, and "somewhere" is this list.
     */
    const val HISTORY = "aimlab-history"

    // --- the shared result screen, reached with a session id (RESULTS/{id}) ---
    /** Base segment for the run-results route; the real route appends `/{id}`. Not a home card. */
    const val RESULTS = "aimlab-results"

    /**
     * Every route the graph registers, home first. The flat list the orchestrator iterates to be sure it
     * has added a `composable(...)` for each — [RESULTS] excluded because it takes an argument and is added
     * by hand (see the KDoc above).
     */
    val flatRoutes: List<String> = listOf(
        HOME,
        FLICK, TRACKING, REACTION, GYRO, RECOIL, MOVEMENT, PRACTICE,
        SENSITIVITY, WEAPON, CONTROLS,
        STATS, RECORDS, HISTORY,
    )
}

/**
 * The thirteen entries the HOME grid shows, each with what it is called, why the user would tap it, the icon
 * it wears and — for the seven training modes — which [TrainingMode] it launches.
 *
 * [mode] is the source of truth the runtime needs: a non-null value means "this card starts a run in that
 * mode", and the mode screen the route resolves to hands that mode to the training loop. The three editors
 * and the three progress screens carry `null` — they configure or report, they do not run.
 *
 * The order here is the order the home screen draws them in, grouped by [AimLabGroup].
 */
enum class AimLabDestination(
    val route: String,
    val label: String,
    val description: String,
    val icon: ImageVector,
    val group: AimLabGroup,
    val mode: TrainingMode? = null,
) {
    FLICK(
        route = AimLabRoutes.FLICK,
        label = "Flick training",
        description = "Snap onto targets that appear and vanish.",
        icon = Icons.Filled.Adjust,
        group = AimLabGroup.TRAIN,
        mode = TrainingMode.FLICK,
    ),
    TRACKING(
        route = AimLabRoutes.TRACKING,
        label = "Tracking training",
        description = "Keep your aim on a target that keeps moving.",
        icon = Icons.Filled.Timeline,
        group = AimLabGroup.TRAIN,
        mode = TrainingMode.TRACKING,
    ),
    REACTION(
        route = AimLabRoutes.REACTION,
        label = "Reaction test",
        description = "Tap the moment a target shows. Measures response time.",
        icon = Icons.Filled.Bolt,
        group = AimLabGroup.TRAIN,
        mode = TrainingMode.REACTION,
    ),
    GYRO(
        route = AimLabRoutes.GYRO,
        label = "Gyro training",
        description = "Aim by tilting the device. Needs a gyroscope.",
        icon = Icons.Filled.Sensors,
        group = AimLabGroup.TRAIN,
        mode = TrainingMode.GYRO,
    ),
    RECOIL(
        route = AimLabRoutes.RECOIL,
        label = "Recoil training",
        description = "Pull down against a weapon's kick to hold the pattern.",
        icon = Icons.Filled.Layers,
        group = AimLabGroup.TRAIN,
        mode = TrainingMode.RECOIL,
    ),
    MOVEMENT(
        route = AimLabRoutes.MOVEMENT,
        label = "Movement training",
        description = "Hit targets while strafing. Aim under movement.",
        icon = Icons.Filled.Speed,
        group = AimLabGroup.TRAIN,
        mode = TrainingMode.MOVEMENT,
    ),
    PRACTICE(
        route = AimLabRoutes.PRACTICE,
        label = "Free practice",
        description = "An open sandbox. Not scored, but a run with shots in it is saved to history.",
        icon = Icons.Filled.TouchApp,
        group = AimLabGroup.TRAIN,
        mode = TrainingMode.FREE_PRACTICE,
    ),
    SENSITIVITY(
        route = AimLabRoutes.SENSITIVITY,
        label = "Sensitivity lab",
        description = "Tune camera and gyro sensitivity, curves and deadzone.",
        icon = Icons.Filled.Tune,
        group = AimLabGroup.SETUP,
    ),
    WEAPON(
        route = AimLabRoutes.WEAPON,
        label = "Weapon editor",
        description = "Build fictional weapons: fire rate, recoil, spread.",
        icon = Icons.Filled.Build,
        group = AimLabGroup.SETUP,
    ),
    CONTROLS(
        route = AimLabRoutes.CONTROLS,
        label = "Control / HUD editor",
        description = "Place the on-screen shoot, aim and stick controls.",
        icon = Icons.Filled.GridView,
        group = AimLabGroup.SETUP,
    ),
    STATS(
        route = AimLabRoutes.STATS,
        label = "Statistics",
        description = "Your trends across every session you have run.",
        icon = Icons.Filled.Insights,
        group = AimLabGroup.PROGRESS,
    ),
    RECORDS(
        route = AimLabRoutes.RECORDS,
        label = "Personal records",
        description = "Your best result in each mode and difficulty.",
        icon = Icons.Filled.EmojiEvents,
        group = AimLabGroup.PROGRESS,
    ),
    HISTORY(
        route = AimLabRoutes.HISTORY,
        label = "Session history",
        description = "Every session you have run. Open one for its full report.",
        icon = Icons.Filled.History,
        group = AimLabGroup.PROGRESS,
    ),
    ;

    companion object {
        /** The home entries in one group, in display order. */
        fun inGroup(group: AimLabGroup): List<AimLabDestination> = entries.filter { it.group == group }
    }
}

/** The three sections the home grid groups its thirteen entries under. */
enum class AimLabGroup(val title: String) {
    TRAIN("Training"),
    SETUP("Setup"),
    PROGRESS("Progress"),
}
