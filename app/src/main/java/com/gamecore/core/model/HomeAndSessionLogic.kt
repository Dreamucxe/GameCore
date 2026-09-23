package com.gamecore.core.model

import com.gamecore.core.common.Formatters

/**
 * The decisions the redesigned Home and Sessions screens make before anything is drawn.
 *
 * Both live here, in pure Kotlin with no `android.*` and no Compose, for the same reason the thermal
 * classifier ([ThermalClassifier]) does: a choice a screen makes is a claim that should be arguable in
 * one place and provable in a unit test, not a `when` buried in a composable that only runs on a device.
 * The Sessions filter (§6) and the Home hero selection (§4) are each a function of their inputs and
 * nothing else — same inputs, same answer, every time.
 *
 * Neither helper touches the heavy model types on purpose. The filter is generic over `T` and asks the
 * caller for a [SessionKind] via a lambda, and the hero selector takes a small [HeroCandidate] the
 * ViewModel maps a [GameProfile] down to. Keeping the concrete session and profile classes out of here
 * is what keeps both trivially testable: a test needs a `Pair` and a lambda, not a database row.
 *
 * The §4 group below it — the header line, the "Automatic" pill's state, the four status tiles and the
 * temperature caption — follows the same rule for the same reason. Every one of them produces words the
 * user reads as a claim about their device, and §10 forbids the screen conveying any of those claims by
 * colour or dimming alone; the word has to exist, so the word is decided here where a test can hold it.
 */

// --------------------------------------------------------------------------- §6 Sessions filter

/**
 * What a recorded session is, for the §6 chip filter.
 *
 * The two kinds are the two things the Sessions screen lists: a game session ([GameSession]) and an
 * Aim Lab run. The ViewModel decides which a row is; this enum is only the vocabulary the filter speaks.
 */
enum class SessionKind {
    /** A session recorded against an installed game — a [GameSession]. */
    GAME,

    /** An Aim Lab training run. */
    AIMLAB,
}

/**
 * The §6 chips, in the order they appear: All / Games / Aim Lab.
 *
 * [ALL] is a distinct member rather than a nullable [SessionKind] so the "show everything" case is a
 * value the UI can bind a chip to and the filter can match on, not the absence of one.
 */
enum class SessionFilterKind {
    /** Every session, whatever its kind. */
    ALL,

    /** Only game sessions ([SessionKind.GAME]). */
    GAMES,

    /** Only Aim Lab runs ([SessionKind.AIMLAB]). */
    AIMLAB,
}

/**
 * Keep the items a §6 chip should show, in the order they came in.
 *
 * Generic over `T` and driven by [kindOf] so it never has to know about the concrete session classes:
 * the ViewModel supplies the mapping — a game session → [SessionKind.GAME], an Aim Lab run →
 * [SessionKind.AIMLAB] — and this function only compares kinds. That is what keeps it pure and testable
 * with nothing heavier than a `Pair` and a lambda.
 *
 * Input order is preserved for every filter (the list is already sorted the way the screen wants it, and
 * a filter must not reorder it). [SessionFilterKind.ALL] returns the list's contents unchanged.
 *
 * @param items the sessions to filter, already in display order.
 * @param filter which chip is selected.
 * @param kindOf classifies one item as a [SessionKind]; supplied by the caller.
 * @return the kept items, in their original relative order.
 */
fun <T> filterByKind(
    items: List<T>,
    filter: SessionFilterKind,
    kindOf: (T) -> SessionKind,
): List<T> = when (filter) {
    SessionFilterKind.ALL -> items.filter { true }
    SessionFilterKind.GAMES -> items.filter { kindOf(it) == SessionKind.GAME }
    SessionFilterKind.AIMLAB -> items.filter { kindOf(it) == SessionKind.AIMLAB }
}

// ------------------------------------------------------------------------------ §4 Home hero

/**
 * The minimum the hero selector needs to know about one profile.
 *
 * Not the whole [GameProfile] on purpose: the §4 decision only reads the package (its identity, and the
 * key into play history) and whether the profile is enabled. The caller maps its profiles down to these,
 * which keeps [selectHero] a pure function of small values rather than something that has to reason about
 * refresh rates and colour presets to pick a hero.
 */
data class HeroCandidate(
    val packageName: String,
    val isEnabled: Boolean,
)

/**
 * The outcome of the §4 hero decision.
 *
 * A [Profile] names the package to feature; [Empty] is the honest answer when there is nothing to feature
 * (no candidates, or none enabled and none ever played) — the Home screen shows its empty state rather
 * than being handed a package it then has to null-check.
 */
sealed interface HeroSelection {
    /** Feature this package. */
    data class Profile(val packageName: String) : HeroSelection

    /** Nothing to feature. */
    data object Empty : HeroSelection
}

/**
 * Pick the Home hero, per §4, from the candidate profiles and the play history.
 *
 * The precedence is exact and each rule carries its § reference:
 *
 *  1. §4 — **Most recently played wins.** Among candidates that appear in [lastPlayedMillisByPackage],
 *     the one with the highest timestamp is featured. This rule reads history alone and does **not**
 *     look at [HeroCandidate.isEnabled]: the phone last put down is the one the user most likely wants
 *     to pick back up, enabled profile or not. A candidate absent from the map has never been played and
 *     cannot win here. Ties (equal timestamps) go to whichever tied candidate is earliest in [candidates],
 *     so the result is stable for a given input order.
 *  2. §4 — **Otherwise, the first enabled candidate** in [candidates] order. This is the fallback for a
 *     fresh install or a set of profiles none of which has ever been played: with no history to go on,
 *     the first profile the user has actually switched on is featured.
 *  3. §4 — **Otherwise [HeroSelection.Empty].** No candidates at all, or none enabled and none in history.
 *
 * @param candidates the profiles eligible to be the hero, in the order the caller wants ties and the
 *   rule-2 fallback resolved.
 * @param lastPlayedMillisByPackage last-played timestamp per package from session history; higher is more
 *   recent. A package missing from the map is treated as never played, and packages in the map that are
 *   not among [candidates] are ignored.
 */
fun selectHero(
    candidates: List<HeroCandidate>,
    lastPlayedMillisByPackage: Map<String, Long>,
): HeroSelection {
    // Rule 1 (§4): most recently played among the candidates, history only, enabled ignored.
    // maxByOrNull keeps the FIRST maximum it meets, so an equal-timestamp tie resolves to the candidate
    // earliest in the list — the stable answer the KDoc promises.
    val mostRecentlyPlayed = candidates
        .filter { lastPlayedMillisByPackage.containsKey(it.packageName) }
        .maxByOrNull { lastPlayedMillisByPackage.getValue(it.packageName) }
    if (mostRecentlyPlayed != null) {
        return HeroSelection.Profile(mostRecentlyPlayed.packageName)
    }

    // Rule 2 (§4): no history to go on — the first enabled candidate, in list order.
    val firstEnabled = candidates.firstOrNull { it.isEnabled }
    if (firstEnabled != null) {
        return HeroSelection.Profile(firstEnabled.packageName)
    }

    // Rule 3 (§4): nothing to feature.
    return HeroSelection.Empty
}

/**
 * One finished session reduced to the two fields the hero decision reads.
 *
 * The same shape of value as [HeroCandidate] and for the same reason: [selectHero] wants a
 * `Map<String, Long>`, a [GameSession] carries thirty-odd fields, and a helper that took the real type
 * would need a database row to test. The ViewModel maps its history down to these.
 */
data class PlayRecord(
    val packageName: String,
    val startedAtMillis: Long,
)

/**
 * When each package was last played, from the recorded history — the map [selectHero] takes.
 *
 * Written as an explicit maximum rather than `associate { it.packageName to it.startedAtMillis }`
 * because that idiom keeps the *last* entry for a duplicate key, and whether that is the newest session
 * or the oldest depends entirely on how the caller happened to sort the list. The repository's flow is
 * newest-first today; a screen that sorted it oldest-first for its own list would silently hand Home the
 * wrong hero. Taking the maximum makes the answer independent of input order, which is what a pure
 * function should be.
 *
 * @param plays every recorded session, in any order. A package may appear many times.
 * @return the highest [PlayRecord.startedAtMillis] seen per package; empty for empty input.
 */
fun lastPlayedByPackage(plays: List<PlayRecord>): Map<String, Long> = buildMap {
    plays.forEach { play ->
        val known = get(play.packageName)
        if (known == null || play.startedAtMillis > known) put(play.packageName, play.startedAtMillis)
    }
}

/**
 * The line under the "GameCore" title (§4.1): what is being played, or how many profiles there are.
 *
 * Precedence matches [gamesSubtitle]'s reasoning with one addition at the top. A game being tracked
 * *right now* outranks the profile count, because it is a fact about this moment and the user looking at
 * the screen can check it against what is on their device; a count is the same every time they look.
 *
 * "Loading" until the first emission, for the same reason the Games header says it: a cold start shows an
 * empty list and "No game profiles yet" for a couple of hundred milliseconds, and telling a user with
 * eleven profiles that they have none — even that briefly — reads as data loss rather than as a load.
 *
 * @param isLoaded false until the profile repository has emitted once.
 * @param profileCount how many profiles exist.
 * @param playingLabel the label of the game being tracked, or null when nothing is.
 */
fun homeSubtitle(isLoaded: Boolean, profileCount: Int, playingLabel: String?): String = when {
    !playingLabel.isNullOrBlank() -> "Playing $playingLabel"
    !isLoaded -> "Loading"
    profileCount == 0 -> "No game profiles yet"
    else -> Formatters.count(profileCount, "game profile")
}

/**
 * What the §4.1 "Automatic" pill says, and whether it is asking for attention.
 *
 * Three states rather than two, because the two inputs fail in different ways and the user can only fix
 * one of them. [MANUAL] is a setting the user chose and is not a problem; [BLIND] is the combination
 * where they have switched automatic application *on* and nothing on the device can tell when a game
 * starts, so the feature is silently inert — the one case worth marking.
 *
 * [label] exists so the pill is never colour alone (§10): the word is the signal and the tint only
 * agrees with it.
 */
enum class WatchState(val label: String) {
    /** Auto-apply is on and something can see a game start. The feature works. */
    WATCHING("Watching"),

    /** Auto-apply is off. Profiles are applied when the user taps Apply, and that is deliberate. */
    MANUAL("Manual"),

    /** Auto-apply is on but nothing can detect a game. Configured and inert — the attention case. */
    BLIND("Not watching"),
    ;

    /** True only for [BLIND]: the state the user has not chosen and would want to fix. */
    val needsAttention: Boolean get() = this == BLIND
}

/**
 * Decide the pill's state (§4.1).
 *
 * The order matters: a user who has switched automatic application off gets [MANUAL] whether or not
 * detection is available, because with the feature off the detector's state is not a fact about their
 * device that they need to act on. Only when they have asked for automatic behaviour does the absence of
 * detection become news.
 *
 * @param autoApply the stored "apply a profile when its game starts" setting.
 * @param detectionAvailable whether foreground-app detection is actually possible right now.
 */
fun watchState(autoApply: Boolean, detectionAvailable: Boolean): WatchState = when {
    !autoApply -> WatchState.MANUAL
    detectionAvailable -> WatchState.WATCHING
    else -> WatchState.BLIND
}

/**
 * The four things the §4.5 status section reports on, in the order it draws them.
 *
 * [label] is here rather than in the composable so the tile's title and the tile's value are decided
 * together — the same reasoning as [GameCardState]: a screen that owned half of a statement would
 * eventually be edited into disagreeing with the half it did not own.
 */
enum class HomeStatusKind(val label: String) {
    SHIZUKU("Shizuku"),
    OVERLAYS("Overlays"),
    OPTIMIZATIONS("Optimizations"),
    PROFILES("Game profiles"),
}

/**
 * One §4.5 tile: what it is about, what it currently says, and whether it wants the user.
 *
 * [needsAttention] is a flag rather than a colour because §10 requires the screen to print a word beside
 * whatever tint it applies. The screen draws the word from this flag; it does not re-derive the
 * condition, so the marked tiles and the reasons below cannot drift apart.
 */
data class HomeStatus(
    val kind: HomeStatusKind,
    val value: String,
    val needsAttention: Boolean,
)

/**
 * Build the four §4.5 tiles from the real states behind them.
 *
 * Each [HomeStatus.needsAttention] rule is deliberately narrow, and the narrowness is the point. This
 * app's honesty contract says an absent optional is not a fault: Shizuku not being installed is a choice
 * (and GameCore is explicit that everything Android allows an ordinary app to do works without it), and a
 * device that exposes one refresh rate is not broken. Marking those would put a warning on a perfectly
 * healthy phone every time it was opened, and a warning that is always there is a warning nobody reads.
 *
 * So attention is raised only where the user has started something and it has not finished:
 *
 *  - **Shizuku** — the service is running and GameCore's permission has not been granted. The user
 *    installed it and started it; the last step is one tap on the Shizuku screen. An uninstalled or
 *    stopped Shizuku is not marked, because nothing is half-done.
 *  - **Overlays** — a window is wanted but "display over other apps" has not been granted, so the
 *    overlay the user switched on cannot appear. Permission missing with nothing switched on is not
 *    marked: nothing is being prevented.
 *  - **Optimizations** — the device probe found controls but none of them is usable. "0 of 8" is the
 *    state where every profile the user writes will do nothing, which is worth saying out loud. A probe
 *    that has not run yet (a zero control count) is not marked, because it has no finding.
 *  - **Game profiles** — there are none. Not a fault, but it is the one state in which the whole app
 *    does nothing, and the tile is the way to the screen that fixes it.
 *
 * @param shizuku the current Shizuku state; its own [ShizukuState.label] is the tile's value.
 * @param overlay the overlay controller's status; its [OverlayStatus.summary] is the tile's value.
 * @param usableControlCount how many device controls the capability probe found usable.
 * @param controlCount how many it knows about at all; 0 means it has not produced a finding yet.
 * @param profileCount how many game profiles exist.
 */
fun homeStatuses(
    shizuku: ShizukuState,
    overlay: OverlayStatus,
    usableControlCount: Int,
    controlCount: Int,
    profileCount: Int,
): List<HomeStatus> = listOf(
    HomeStatus(
        kind = HomeStatusKind.SHIZUKU,
        value = shizuku.label,
        needsAttention = shizuku == ShizukuState.RUNNING_PERMISSION_UNKNOWN ||
            shizuku == ShizukuState.RUNNING_PERMISSION_DENIED,
    ),
    HomeStatus(
        kind = HomeStatusKind.OVERLAYS,
        value = overlay.summary,
        needsAttention = !overlay.hasPermission && overlay.anythingVisible,
    ),
    HomeStatus(
        kind = HomeStatusKind.OPTIMIZATIONS,
        value = "$usableControlCount of $controlCount",
        needsAttention = controlCount > 0 && usableControlCount == 0,
    ),
    HomeStatus(
        kind = HomeStatusKind.PROFILES,
        value = profileCount.toString(),
        needsAttention = profileCount == 0,
    ),
)

/**
 * The caption under the §4.3 temperature figure: which sensor, the classifier's word, and — when the
 * platform reports one — its own throttling level by name.
 *
 * §4.3 asks for "the classification word plus the real platform thermal status when available", and the
 * two are deliberately both printed even when they differ. They are different measurements: [level] is
 * this app's reading of a number from a sensor, [platform] is `PowerManager.getCurrentThermalStatus()`'s
 * verdict on the whole device. A phone can sit at 62 °C ("Hot" by the sensor) while the platform is still
 * reporting `NONE`, and the honest caption says exactly that rather than picking a winner — which is the
 * mistake that produced the "85.3 °C in red, labelled Normal" bug in the first place. The difference now
 * is that both words are printed *and* the colour comes from [level] alone, so nothing contradicts
 * anything: the tile says "CPU sensor · Hot · System: Normal" and every word of that is true.
 *
 * The platform half is omitted rather than filled in when the device does not report it — §0's no-fake-
 * data rule. Below API 29 there is no such reading, and a caption that said "System: Normal" on a device
 * that never answered the question would be inventing a reassurance.
 *
 * @param sensor which sensor produced the figure, so the caption names what is 62 °C.
 * @param level the shared classifier's verdict, already computed by [ThermalClassifier.classify].
 * @param platform the platform's own status, or null when the device does not report one.
 */
fun temperatureCaption(
    sensor: ThermalSensorType,
    level: ThermalClass,
    platform: ThermalStatus?,
): String = listOfNotNull(
    when (sensor) {
        ThermalSensorType.CPU -> "CPU sensor"
        ThermalSensorType.BATTERY -> "Battery sensor"
    },
    level.label,
    platform?.let { "System: ${it.label}" },
).joinToString(" · ")
