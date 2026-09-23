package com.gamecore.core.model

import com.gamecore.core.common.Formatters

/**
 * The decisions the redesigned Overlay settings screen (§7) makes before anything is drawn.
 *
 * Pure Kotlin, no `android.*` and no Compose, for the same reason [gamesSubtitle]'s neighbours are: every
 * function here produces a *claim* the user reads — what is on screen, where a window ended up, what the
 * pill will look like, how many of its stats this device cannot actually report — and a claim belongs in a
 * unit test rather than in a composable that can only be checked by looking at a phone.
 *
 * Three things in here exist to stop a specific class of dishonesty the redesign is meant to remove:
 *
 *  - [overlayWindowState] settles the chip word and the switch's enabled state from **one** input. The
 *    audit's §7 temperature bug was a word and a colour computed from different places that then
 *    disagreed; a chip reading "Down" beside a switch locked by a game's profile is the same bug in a
 *    different costume, so "blocked" and "locked" are states of the same enum rather than an afterthought
 *    the card adds in a second `if`.
 *  - [overlayPositionSummary] refuses to name a corner. The position is stored as raw pixels from the
 *    top-left (see [OverlayConfig]) and nothing in this layer knows how wide the screen is, so "top
 *    right" would be a guess dressed as a fact. It reports the figures and whether they are still the
 *    ones the window shipped with, which is all that is true without a display to measure against.
 *  - [pillPreviewNote] describes the preview as a preview *of live readings*, and counts the stats that
 *    read nothing on this device rather than letting a tidy row of numbers imply every stat works here.
 *    §0's "no fake data" rule applies to a preview exactly as it applies to a dashboard.
 *
 * The slider helpers ([sliderRange]) are here rather than in the screen because §10 asks for slider values
 * a screen reader can read, and the shared `SliderRow` draws its `valueLabel` and `description` as real
 * text nodes: stating the bounds in the description is what makes the value legible without a sighted
 * user's sense of where the thumb sits in its travel.
 */

// ------------------------------------------------------------------ what state an overlay window is in

/**
 * What one overlay window is doing, in one word.
 *
 * Four states rather than a boolean because the two failure cases are the ones a user needs explaining,
 * and §10 forbids conveying them with a greyed-out switch alone — a disabled control with no word beside
 * it is indistinguishable from a bug. [BLOCKED] and [LOCKED] are the two reasons a switch on this screen
 * will not move, and each has a different remedy: one is a permission the user can grant, the other is a
 * game's profile that will hand control back on its own when the game stops.
 */
enum class OverlayWindowState(val label: String) {
    /** On screen right now, as reported by the overlay service rather than by the request. */
    UP("Up"),

    /** Not on screen, and nothing is stopping it from being. */
    DOWN("Down"),

    /**
     * "Display over other apps" has not been granted, so no window can appear whatever the switch says.
     *
     * Outranks [LOCKED] because it is the more fundamental answer: a profile that wants the pill up still
     * cannot raise it without the permission, so telling the user about the profile first would send them
     * to wait for a game to stop when what they need is a trip to system settings.
     */
    BLOCKED("Blocked"),

    /** A game's profile is driving the overlay, so the user's own switch is held until the game stops. */
    LOCKED("Locked"),
}

/**
 * Classify one overlay window for its card's chip and its switch.
 *
 * Precedence, highest first: it is visible → the permission is missing → a profile is in charge → it is
 * simply off. Visible wins outright because it is a fact about this moment reported by the service that
 * draws it; a window the user can see is never described as blocked, whatever the stale permission read
 * or the profile flag says.
 *
 * @param isVisible from the overlay service's own status, not from the request that asked for it.
 * @param hasPermission this screen's fresh `canDrawOverlays` read.
 * @param isDrivenByProfile true while a game's profile owns the overlay request.
 */
fun overlayWindowState(
    isVisible: Boolean,
    hasPermission: Boolean,
    isDrivenByProfile: Boolean,
): OverlayWindowState = when {
    isVisible -> OverlayWindowState.UP
    !hasPermission -> OverlayWindowState.BLOCKED
    isDrivenByProfile -> OverlayWindowState.LOCKED
    else -> OverlayWindowState.DOWN
}

// ------------------------------------------------------------------------------------- the header line

/**
 * The line under the "Overlay" title: what is actually on screen.
 *
 * Says "Loading" rather than "Nothing on screen" until the first emission, for the reason [gamesSubtitle]
 * does: the two look identical on a cold start, and telling a user with a pill and a button up that
 * nothing is showing — even for 200 ms — is read as the feature having broken.
 *
 * The permission comes before the count because it is the reason the count is zero. A subtitle reading
 * "Nothing on screen" on a device where GameCore was never allowed to draw is true and useless.
 *
 * @param isLoaded false until the preference store has been read once.
 * @param hasPermission whether "display over other apps" is granted.
 * @param visibleWindows how many of the four overlay windows the service reports as up.
 */
fun overlaySubtitle(isLoaded: Boolean, hasPermission: Boolean, visibleWindows: Int): String = when {
    !isLoaded -> "Loading"
    !hasPermission -> "Not allowed to draw over other apps yet"
    visibleWindows <= 0 -> "Nothing on screen"
    else -> "${Formatters.count(visibleWindows, "window")} on screen"
}

// ------------------------------------------------------------------------------------ where it sits

/**
 * Where a dragged overlay window ended up, without pretending to know the screen it is on.
 *
 * This deliberately does **not** say "top right". [OverlayConfig] stores the position as pixels from the
 * top-left because the pill is dragged in a live overlay and the pixel position *is* what the user chose,
 * and nothing in this layer has a display to measure those pixels against — a corner name derived here
 * would be a guess printed as a fact, and wrong on every device held in the other orientation.
 *
 * So it reports two true things instead: whether the window is still where it started, and the figures it
 * has been moved to. "Where it started" matters because the Position group's only other control is a
 * reset, and a reset button beside a window that has never been moved should read as a no-op rather than
 * as something the user is missing out on.
 *
 * @param x stored horizontal position, in pixels from the left edge.
 * @param y stored vertical position, in pixels from the top edge.
 * @param defaultX the config's own default for [x].
 * @param defaultY the config's own default for [y].
 */
fun overlayPositionSummary(x: Int, y: Int, defaultX: Int, defaultY: Int): String =
    if (x == defaultX && y == defaultY) {
        "Where it starts out — $defaultX, $defaultY"
    } else {
        "Dragged to $x, $y"
    }

/** True when a reset would change nothing, so the action can say so rather than doing nothing quietly. */
fun isAtDefaultPosition(x: Int, y: Int, defaultX: Int, defaultY: Int): Boolean =
    x == defaultX && y == defaultY

// -------------------------------------------------------------------------------- the update interval

/**
 * The pill's refresh interval as the slider's own unit.
 *
 * Tenths of a second, because the slider steps in integers and 100 ms is the smallest step worth having
 * over a range that runs from half a second to ten. The conversion is here rather than in the state class
 * so the round trip — slider position to millis and back to a label — is one tested pair of functions
 * instead of two pieces of arithmetic that can drift apart by a factor of ten.
 */
fun intervalTenths(millis: Long): Int = (millis / TENTH_MILLIS).toInt()

/** The inverse of [intervalTenths], clamped to the config's own bounds so the slider cannot exceed them. */
fun intervalMillis(tenths: Int): Long = (tenths * TENTH_MILLIS)
    .coerceIn(OverlayConfig.MIN_INTERVAL_MILLIS, OverlayConfig.MAX_INTERVAL_MILLIS)

/**
 * The figure printed beside the interval slider.
 *
 * Milliseconds below a second and seconds above it, because "0.5 s" reads as a rounding of something and
 * "500 ms" reads as a choice. The trailing ".0" is dropped for the same reason: a whole number of seconds
 * is how a user thinks about a one-second refresh.
 */
fun intervalLabel(millis: Long): String {
    if (millis < MILLIS_PER_SECOND) return "$millis ms"
    val tenths = intervalTenths(millis)
    return if (tenths % TENTHS_PER_SECOND == 0) {
        "${tenths / TENTHS_PER_SECOND} s"
    } else {
        "${tenths / TENTHS_PER_SECOND}.${tenths % TENTHS_PER_SECOND} s"
    }
}

/** One tenth of a second, the interval slider's step. */
const val TENTH_MILLIS = 100L

private const val MILLIS_PER_SECOND = 1_000L
private const val TENTHS_PER_SECOND = 10

/** The interval slider's range, in tenths, derived from [OverlayConfig]'s own bounds rather than retyped. */
val INTERVAL_TENTHS: IntRange =
    intervalTenths(OverlayConfig.MIN_INTERVAL_MILLIS)..intervalTenths(OverlayConfig.MAX_INTERVAL_MILLIS)

// ------------------------------------------------------------------- what the two windows will look like

/**
 * The pill's Style group as one sentence: what the four style settings add up to.
 *
 * §7 asks for a live preview and this is not a substitute for it — the preview above it is the real
 * composable fed by real readings. This is the sentence a screen-reader user gets instead of a picture,
 * and the line a sighted user reads to confirm that the thing they just dragged a slider to is the thing
 * they meant. Assembled from the config rather than from the slider events so it cannot describe a state
 * the store rejected in [OverlayConfig.normalised].
 *
 * Square corners are named rather than reported as "0 dp", because zero is the one value on that slider
 * that changes the shape into a different shape.
 */
fun pillStyleSummary(config: OverlayConfig): String {
    val shape = if (config.isVertical) "A column" else "A strip"
    val labels = if (config.showLabels) "with labels" else "figures only"
    val corners = if (config.cornerRadiusDp == 0) {
        "square corners"
    } else {
        "${config.cornerRadiusDp} dp corners"
    }
    return "$shape $labels · ${config.textSizeSp} sp text · ${config.opacityPercent}% opaque · $corners"
}

/**
 * The button's Size group as one sentence: how big it is, and how much of the game it covers.
 *
 * Both opacities are in here because the pair is the actual decision — the first is the button while the
 * user is looking at it in GameCore, the second is what they will spend a whole session looking at over a
 * game — and a summary that named only the first would describe the state the button is almost never in.
 */
fun buttonSizeSummary(config: FloatingButtonConfig): String =
    "${config.sizeDp} dp · ${config.opacityPercent}% here, fading to " +
        "${config.idleOpacityPercent}% once a game has focus"

/**
 * What the reset action will do to the panel's width, or null when it would do nothing.
 *
 * Null rather than a cheerful "already at the default", because the card uses this to decide whether the
 * action is worth offering at all: §32's rule against buttons that do nothing applies to a reset that is
 * already reset.
 */
fun panelWidthResetNote(widthDp: Int): String? =
    if (widthDp == FloatingButtonConfig.DEFAULT_PANEL_WIDTH_DP) {
        null
    } else {
        "Back to ${FloatingButtonConfig.DEFAULT_PANEL_WIDTH_DP} dp, the width it opens at out of the box."
    }

// ------------------------------------------------------------------------------- the stats on the pill

/** The line under "Stats on the pill": how many of the allowance is used, and that the order is the order. */
fun pillStatsSubtitle(count: Int, max: Int = OverlayConfig.MAX_STATS): String = when {
    count <= 0 -> "Nothing on it yet · room for $max"
    else -> "$count of $max, in drawing order"
}

/**
 * The pill's stats as one sentence, for the screen reader.
 *
 * The preview draws each stat as its own small piece of text, which is one stop per stat for somebody
 * working through the card with a screen reader — and none of those stops says it is a preview, so a
 * reading heard out of context is indistinguishable from a live dashboard value. One phrase that names
 * the thing and then lists it is a single stop that cannot be misread, which is the same reason §5's chip
 * row is collapsed into [chipsSentence].
 *
 * Built from the readings the preview was handed rather than from a parallel summary, so the spoken
 * version cannot drift from the drawn one.
 *
 * @param entries each stat already rendered as the card draws it, label and value together.
 * @return one sentence, or null when there is nothing on the pill to describe.
 */
fun pillPreviewSentence(entries: List<String>): String? =
    if (entries.isEmpty()) null else "Live preview of the pill: ${entries.joinToString(", ")}"

/**
 * The caption under the preview, which has to say that these are real readings and how many are not.
 *
 * The honest difficulty this sentence solves: a preview of eight stats on a device that can only report
 * five looks like a working pill with three quiet ones. Counting them in the caption is what turns that
 * into information, and the banner beside it names which — see [absentStatsNote].
 *
 * Stats still waiting for the sampler's first tick are deliberately **not** counted here. They render
 * identically to an absent one and mean the opposite, and announcing "three stats read nothing" for the
 * first second of every visit teaches the user to ignore the one line on this card worth reading.
 *
 * @param statCount how many stats are on the pill.
 * @param absentCount how many of them have no reading *and* are not merely awaiting the first sample.
 */
fun pillPreviewNote(statCount: Int, absentCount: Int): String = when {
    statCount <= 0 -> "Nothing on the pill yet, so there is nothing to preview."
    absentCount <= 0 -> "Live readings from this device, drawn exactly as the overlay will draw them."
    absentCount >= statCount ->
        "Live readings from this device. None of them can be read on this phone, so the pill would be " +
            "all placeholders over a game."
    else ->
        "Live readings from this device. $absentCount of $statCount cannot be read on this phone and " +
            "will show a placeholder over a game."
}

/**
 * The banner naming the stats this device reports nothing for, or null when there are none.
 *
 * Names them rather than counting them, because the remedy is the user's: a stat that reads nothing here
 * is one to take off the pill, and they cannot do that from a number. Takes labels rather than
 * [HudStat]s so the caller stays the one place that decides whether to use the long or the short form.
 *
 * @param labels the absent stats' names, in the order they sit on the pill.
 * @param placeholder what the renderer draws in their place.
 */
fun absentStatsNote(labels: List<String>, placeholder: String): String? {
    if (labels.isEmpty()) return null
    return "This device reports no ${joinWords(labels)}. Those read \"$placeholder\" over a game rather " +
        "than a number."
}

/**
 * A list as English rather than as a comma-separated dump.
 *
 * "cpu temperature and frame rate" rather than "cpu temperature, frame rate", because these sentences are
 * read as prose and the last comma is the one that makes a sentence sound like a log line.
 */
fun joinWords(words: List<String>): String = when (words.size) {
    0 -> ""
    1 -> words[0]
    2 -> "${words[0]} and ${words[1]}"
    else -> words.dropLast(1).joinToString(", ") + " and " + words.last()
}

// ------------------------------------------------------------------------------------ the quick apps

/**
 * What the quick-launch row holds, for the row that opens its editor.
 *
 * "None chosen" rather than "0 apps" for the empty case: the row draws nothing at all until something is
 * picked — see [FloatingButtonConfig.showQuickApps] — so the honest first state is an absence rather than
 * a count of zero.
 */
fun quickAppsSummary(count: Int, max: Int = FloatingButtonConfig.MAX_QUICK_APPS): String = when {
    count <= 0 -> "None chosen"
    count >= max -> "$count of $max — full"
    else -> "$count of $max"
}

// --------------------------------------------------------------------------- slider bounds, in words

/**
 * A slider's range as text, for the description under it (§10).
 *
 * Compose's `Slider` announces its position as a percentage of its travel, which is useless for "text
 * size" and actively misleading for "faded opacity". The shared `SliderRow` already draws the current
 * value as a real text node; this puts the two ends of the range in the description beside it, so the
 * figure has a scale to be read against without anyone having to see where the thumb sits.
 *
 * Also stops a second bug the model's own KDoc warns about: a range retyped at the call site is a slider
 * whose top end silently clamps to something else. Every caller here passes the model's own
 * `IntRange` constant, and this function is where that constant becomes the words the user reads.
 */
fun sliderRange(range: IntRange, unit: String = ""): String = when {
    unit.isBlank() -> "${range.first} to ${range.last}"
    // A percent sign binds to its figure, so it is repeated; a unit word takes a space and is only
    // worth saying once. "20% to 100%" and "8 to 20 sp" are both how a person would write it down.
    unit == "%" -> "${range.first}% to ${range.last}%"
    else -> "${range.first} to ${range.last} $unit"
}

/** A slider's description with its bounds appended, so one call site cannot state the range and another not. */
fun sliderDescription(description: String, range: IntRange, unit: String = ""): String =
    "$description Anything from ${sliderRange(range, unit)}."
