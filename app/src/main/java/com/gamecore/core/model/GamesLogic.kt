package com.gamecore.core.model

import com.gamecore.core.common.Formatters

/**
 * The decisions the redesigned Games screen (§5) makes before anything is drawn.
 *
 * Pure Kotlin, no `android.*` and no Compose, for the reason [filterByKind] and [selectHero] are: every
 * function here produces a *claim* — what a profile will do, what state a card is in, what deleting one
 * actually removes — and a claim the user reads belongs in a unit test rather than in a composable that
 * can only be checked by looking at a phone.
 *
 * Two of these exist specifically to stop a class of bug the audit and [profileChips] both warn about:
 *
 *  - [profileClaim] reads `changesNothing` *directly* instead of inferring "this writes nothing" from an
 *    empty chip list. The two deliberately disagree — a brand-new profile raises the floating button and
 *    so has one chip while writing no device setting — and a card that guessed from the chip count would
 *    tell a fresh profile it does nothing at all, which is false in one direction, or tell an overlay-only
 *    profile it changes device settings, which is false in the other.
 *  - [deleteProfileMessage] is the dialog's words, fixed here where a test can hold them to the audit's
 *    finding (§3: the delete is one row of `game_profiles`, no cascade). A dialog that over-claims is the
 *    worst kind of lie this app can tell, because the user acts on it and cannot undo it.
 */

// ------------------------------------------------------------------- what state a profile card is in

/**
 * What a Games card is, in one word.
 *
 * This exists because §10 forbids conveying status by colour or dimming alone. The redesigned card draws a
 * disabled profile at a lower alpha, and alpha is not a signal a screen reader can speak or a user with low
 * vision can distinguish from "this screen is a bit dim" — so the same fact is always printed as [label] in
 * a chip beside it. The enum is the single source for both, which is the same fix the thermal classifier
 * applied to the "red 85.3 °C labelled Normal" split.
 *
 * The order of precedence in [gameCardState] is deliberate: what the device is doing right now outranks
 * what the profile is configured to do.
 */
enum class GameCardState(val label: String) {
    /** The game is in the foreground now. The most useful thing the card can say, so it wins. */
    PLAYING("Playing"),

    /** Enabled and installed: GameCore will act on this profile when its game starts. */
    ON("On"),

    /**
     * Installed, but the user has switched the profile off.
     *
     * The card dims for this state — and stays fully interactive. "Off" describes what GameCore will do
     * when the game starts, not whether the user may still edit, play or delete the profile.
     */
    OFF("Off"),

    /**
     * The game has been uninstalled. The profile is kept (see [GameProfile]) and drawn as dormant.
     *
     * Outranks [OFF] because it explains something the toggle cannot: the profile is inert for a reason
     * that has nothing to do with the switch, and turning the switch on would not change it.
     */
    NOT_INSTALLED("Not installed"),
}

/**
 * Classify one profile row for the card.
 *
 * Precedence, highest first: playing now → game missing → switched off → on. "Playing" is first because it
 * is a fact about this moment and the user is looking at the screen now; "Not installed" beats "Off"
 * because it is the more fundamental explanation of why nothing will happen.
 *
 * @param isEnabled the profile's own switch.
 * @param isInstalled whether the game's package still resolves.
 * @param isPlaying whether the detector currently reports this package in the foreground.
 */
fun gameCardState(isEnabled: Boolean, isInstalled: Boolean, isPlaying: Boolean): GameCardState = when {
    isPlaying -> GameCardState.PLAYING
    !isInstalled -> GameCardState.NOT_INSTALLED
    !isEnabled -> GameCardState.OFF
    else -> GameCardState.ON
}

// --------------------------------------------------------------- what a profile honestly claims to do

/**
 * How much a profile actually does, as the card is allowed to say it.
 *
 * Three states rather than two because [GameProfile.changesNothing] and [profileChips] answer different
 * questions and the card has to be truthful about both at once (see this file's header). A profile that
 * only raises an overlay has a visible effect *and* writes no device setting, and the only honest
 * description of it mentions both halves.
 */
enum class ProfileClaim {
    /**
     * Writes nothing and shows nothing: an empty chip list *and* `changesNothing`. The one case where the
     * card may say the profile does nothing at all.
     */
    DOES_NOTHING,

    /**
     * Something will visibly happen — an overlay is raised, a session is recorded — but no device setting
     * is written, so there is nothing to restore afterwards. This is the state a brand-new profile is in.
     *
     * **Freeing RAM is deliberately not in this list**, even though it writes no device setting either.
     * [GameProfile.changesNothing] counts it as a change, and the reasoning there is the reasoning here:
     * this state's whole payload is a promise that there is nothing to put back, and closing the user's
     * background apps is the one effect in a profile that no restore puts back.
     */
    NO_DEVICE_WRITES,

    /**
     * The profile does something with consequences beyond drawn-on-top UI: it writes device settings and
     * puts them back when the game stops, or — in the single case that is neither an overlay nor a
     * restorable write — it frees RAM by closing other apps.
     *
     * Both belong here because of what the card *does* with this state rather than what the words
     * "writes settings" literally describe: it lets the chips name the effects and adds no caveat, which
     * is correct for a refresh rate and correct for freed RAM. The alternative — a caveat promising a
     * restore — would be wrong for the second.
     */
    WRITES_SETTINGS,
}

/**
 * Decide what the card may claim about a profile.
 *
 * [changesNothing] is taken from [GameProfile.changesNothing] and is **not** re-derived from [chipCount]:
 * that is the whole point of this function. The chip list and `changesNothing` are two different
 * measurements of a profile, and the card needs both to avoid promising a restore that will not happen or
 * denying an overlay that will appear.
 *
 * @param chipCount how many chips [profileChips] produced for this profile.
 * @param changesNothing the profile's own [GameProfile.changesNothing].
 */
fun profileClaim(chipCount: Int, changesNothing: Boolean): ProfileClaim = when {
    !changesNothing -> ProfileClaim.WRITES_SETTINGS
    chipCount == 0 -> ProfileClaim.DOES_NOTHING
    else -> ProfileClaim.NO_DEVICE_WRITES
}

/**
 * The sentence a card prints under its chips, or null when the chips already say everything.
 *
 * Only the two honest-caveat states get words. [ProfileClaim.WRITES_SETTINGS] returns null because the
 * chips are the message there — a line reading "this profile changes settings" under a row of chips naming
 * those settings is noise.
 */
fun profileClaimNote(claim: ProfileClaim): String? = when (claim) {
    ProfileClaim.DOES_NOTHING ->
        "Nothing set yet. Open Edit to choose what should happen when this game starts."
    ProfileClaim.NO_DEVICE_WRITES ->
        "No device settings are changed, so there is nothing to put back afterwards."
    ProfileClaim.WRITES_SETTINGS -> null
}

// ------------------------------------------------------------------------------------ chip overflow

/**
 * The chips a card shows, and how many it had to hold back.
 *
 * A card is one item in a scrolling list and a profile with a dozen effects would otherwise push the next
 * game off the screen. [overflow] is a count rather than a truncated list because the card prints it as
 * "+3 more" — a number the user can read, not a chip pretending to be an effect.
 */
data class ShownChips(
    val shown: List<ProfileChip>,
    val overflow: Int,
)

/**
 * Cap a chip list at [max], reporting the remainder (§4's "+N" rule, applied to the §5 card).
 *
 * Takes from the front, so [profileChips]' deliberate reading order survives the cut: the display and audio
 * settings a user scans for are the ones kept, and the recording chip is the first to be rolled up. A [max]
 * below 1 is treated as 1 — a card that showed no chips at all and a bare "+7 more" would be worse than
 * showing one and counting the rest.
 */
fun shownChips(chips: List<ProfileChip>, max: Int = DEFAULT_VISIBLE_CHIPS): ShownChips {
    val cap = max.coerceAtLeast(1)
    if (chips.size <= cap) return ShownChips(chips, 0)
    return ShownChips(chips.take(cap), chips.size - cap)
}

/** Six fits two rows of three in the card's chip row without dominating the card. */
const val DEFAULT_VISIBLE_CHIPS = 6

/**
 * The chip row as one sentence, for the screen reader.
 *
 * A row of eight separate pills is eight stops for a screen reader working through a card; one phrase is a
 * single stop that says the same thing. Built from the chips themselves rather than from a parallel summary
 * string so the spoken version cannot drift from the drawn one — which is exactly what a second, shorter
 * list of the profile's fields would eventually do.
 *
 * @return the chip texts joined with commas, or null when there are no chips to describe.
 */
fun chipsSentence(chips: List<ProfileChip>): String? =
    if (chips.isEmpty()) null else chips.joinToString(", ") { it.text }

// ------------------------------------------------------------------------------- the delete dialog

/** The §5 title. Fixed here so the dialog's two halves are written and tested in one place. */
const val DELETE_PROFILE_TITLE = "Delete profile?"

/**
 * The delete dialog's body, held to what the audit found actually happens.
 *
 * **Audit §3:** deleting runs `DELETE FROM game_profiles WHERE package_name = :pkg` and nothing else. There
 * is no cascade. Recorded sessions, their samples, restore points, HUD layouts and crosshair and colour
 * presets all survive, and the game's own files are never in scope — GameCore has no access to them and
 * does not ask for any.
 *
 * So the sentence has to do three things and resist the temptation to do a fourth. It says what is lost
 * (the settings the user chose), it carries §5's required line verbatim ("This will remove the profile from
 * your list."), and it names what is *not* touched — because the user standing over a destructive button
 * with no undo behind it is asking exactly that. It must never imply the game or its saved data is
 * involved: an app that says "this will delete the game's data" when it cannot even see that data has
 * frightened the user into keeping a profile they wanted gone, and has taught them not to believe the next
 * dialog either.
 *
 * @param label the profile's sanitised game label, already cleaned at the boundary by
 *   [com.gamecore.core.common.TextSanitizer].
 */
fun deleteProfileMessage(label: String): String =
    "GameCore will forget the settings you chose for $label. This will remove the profile from your " +
        "list. The game itself and anything it has saved are untouched, and any session already " +
        "recorded for it is kept."

// ------------------------------------------------------------------------------------ header line

/**
 * The line under the "Games" title: how many profiles there are and how many are switched on.
 *
 * Says "Loading" rather than "no profiles" until the first emission, because the two look identical on a
 * cold start and telling a user with eleven profiles that they have none — even for 200 ms — is the kind of
 * flicker that gets read as data loss.
 *
 * The "n on" half is dropped when every profile is on, since "4 profiles · 4 on" is the same fact twice.
 *
 * @param isLoaded false until the repository has emitted once.
 * @param profileCount total profiles.
 * @param enabledCount how many of them have their switch on.
 */
fun gamesSubtitle(isLoaded: Boolean, profileCount: Int, enabledCount: Int): String = when {
    !isLoaded -> "Loading"
    profileCount == 0 -> "Nothing configured yet"
    enabledCount == profileCount -> Formatters.count(profileCount, "profile")
    else -> "${Formatters.count(profileCount, "profile")} · $enabledCount on"
}
