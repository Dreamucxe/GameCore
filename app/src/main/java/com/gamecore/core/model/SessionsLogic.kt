package com.gamecore.core.model

import com.gamecore.core.common.Formatters

/**
 * The §6 Sessions screen's decisions, made in pure Kotlin before anything is drawn.
 *
 * The screen redesign is the point at which Sessions stopped listing one thing. The history is now a
 * merge of two repositories that share no table and no model — recorded game sessions
 * ([GameSession], from `SessionRepository`) and Aim Lab runs (`SessionSummary`, from
 * `AimLabRepository`) — and every rule that decides what a merged row *says* is here rather than in a
 * composable, for the same reason [ThermalClassifier] and [filterByKind] are: a claim the UI makes
 * about a measurement should be arguable in one place and provable in a unit test, not a `when`
 * that only runs on a device.
 *
 * Two rules run through the whole file and are worth stating once.
 *
 * **A reading Android did not record is the word [UNAVAILABLE_TEXT] and a reason, never a number.** The
 * six aggregates §6's tiles are built from are all nullable on [GameSession], because a device with
 * no readable memory or processor statistics produces a session with a real duration and null
 * figures — which is a useful record. A `?: 0f` anywhere in this file would turn "this phone does
 * not expose that" into "the game used none of it". The em dash the report screen and the shareable
 * card use is deliberately *not* reused here: a dash sitting in a small tile beside two real
 * percentages reads as a value, and §6 asks for the word.
 *
 * **Nothing here knows about Aim Lab's types.** [gameSessionTiles] takes a [GameSession] because
 * that class already lives in this package, but [aimLabTiles] takes the run's fields one by one. It
 * would be shorter to import `com.gamecore.aimlab.engine.SessionSummary`; it would also make
 * `core.model` depend on a feature package it otherwise knows nothing about, and would mean a test
 * for the accuracy rule had to build a whole training summary to state it.
 */

/** What a §6 tile prints when the device recorded nothing for it. The reason goes in the detail. */
const val UNAVAILABLE_TEXT = "Unavailable"

/**
 * One of the three figures on a session card.
 *
 * [detail] is not optional. For a tile with a reading it is the supporting fact that stops the
 * headline being misread (a peak beside an average, "device-wide" beside a processor figure); for a
 * tile without one it *is* the reason, and a tile reading "Unavailable" with nothing under it is the
 * exact failure this app is built to avoid.
 *
 * [thermal] is set only by the temperature tile, and it carries the whole classification rather than
 * a colour or a severity of this file's own invention. That is what keeps the redesign's original
 * bug — a tile showing 85.3 °C in red beside the word "Normal" — unreachable: the word in [detail]
 * and the colour the screen tints with both come from this one [ThermalClass], so they cannot be
 * sourced separately and disagree.
 */
data class SessionTile(
    val label: String,
    val value: String,
    val detail: String,
    val thermal: ThermalClass? = null,
) {
    /** False when [value] is the [UNAVAILABLE_TEXT] word, which is the only non-reading a tile can hold. */
    val isReading: Boolean get() = value != UNAVAILABLE_TEXT

    /**
     * Whether [detail] has to be drawn, rather than only offered to a screen reader.
     *
     * A card carries three tiles and cannot print three supporting sentences without becoming the
     * report screen, so most details stay in the spoken description. Two cases are not optional:
     *
     * - a tile with no reading, because §6's rule is the word **and the reason**, and "Unavailable"
     *   alone tells a user nothing about whether their device cannot measure it or the session was
     *   too short;
     * - a tile whose temperature is [ThermalClass.WARM] or worse, because that is exactly where the
     *   value gets tinted, and §2 forbids status by colour alone. The word is in [detail], so drawing
     *   it is what keeps the colour legible to someone who cannot see it as a colour.
     */
    val needsDetailOnScreen: Boolean
        get() = !isReading || (thermal != null && thermal.severity >= ThermalClass.WARM.severity)
}

// ------------------------------------------------------------------------------ §6 game tiles

/**
 * §6's three tiles for a recorded game session: peak temperature, average RAM, average processor.
 *
 * Fixed at three and in this order, so scanning a column of cards reads as a table. The battery
 * figure the old list carried is deliberately not among them — §6 names these three, and battery is
 * still on the session report and in the all-time summary above the list, which is where a rate that
 * needs three sentences of qualification belongs.
 *
 * The peak is used for heat and the average for the other two on purpose. A thermal figure is about
 * the worst moment a session reached; processor and memory use are about what the session was
 * typically doing, and their peaks are one-sample spikes that would make every card look identical.
 */
fun gameSessionTiles(session: GameSession): List<SessionTile> = listOf(
    peakTemperatureTile(session),
    averageMemoryTile(session),
    averageProcessorTile(session),
)

/**
 * The hottest reading the session saw, classified by the one shared classifier.
 *
 * The sensor is assumed to be a CPU-class zone, and that assumption is stated here because it cannot
 * be checked: a session row stores whatever [PerformanceSnapshot.primaryTemperatureDeciCelsius]
 * produced — the CPU zone where a device exposes one, the battery sensor otherwise — and does not
 * record which it was. Recording it would be a schema change, which the redesign forbids. So the
 * same threshold set the live tiles use for the same number is used here, rather than inventing a
 * third set for history.
 *
 * The severity word is appended to the detail only from `WARM` upward, matching the rule the live
 * tiles already follow: below that the colour is neutral and the word would be a "Normal" on every
 * card that a user learns to stop reading. Where the colour means something, the word is beside it.
 */
private fun peakTemperatureTile(session: GameSession): SessionTile {
    val peak = session.peakTemperatureDeciCelsius
    val classification = ThermalClassifier.classify(peak, ThermalSensorType.CPU)
    if (peak == null) {
        return SessionTile(
            label = "Peak heat",
            value = UNAVAILABLE_TEXT,
            detail = "No temperature sensor was readable while this session was recorded.",
            thermal = ThermalClass.UNAVAILABLE,
        )
    }
    val word = classification.label.takeIf {
        classification.level.severity >= ThermalClass.WARM.severity
    }
    val average = session.averageTemperatureDeciCelsius
        ?.let { "average ${Formatters.temperature(it)}" }
    val detail = listOfNotNull(word, average)
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" · ")
        ?: "The highest single reading taken during this session."
    return SessionTile(
        label = "Peak heat",
        value = Formatters.temperature(peak),
        detail = detail,
        thermal = classification.level,
    )
}

/**
 * Average memory use, or the reason there is no honest average.
 *
 * Two different absences, and they get different sentences. A null aggregate means the device never
 * gave a readable figure; too few samples means it did, but not enough of them to average — see
 * [GameSession.hasMeaningfulAggregates]. Printing the mean of six readings taken in a session's
 * first ten seconds beside a figure averaged over an hour would present a loading screen and a
 * play session as the same kind of number.
 */
private fun averageMemoryTile(session: GameSession): SessionTile = averageTile(
    label = "Avg. RAM",
    average = session.averageMemoryPercent,
    peak = session.peakMemoryPercent,
    peakPrefix = "Peak",
    peakSuffix = "of this device's memory",
    absentReason = "Memory use was not recorded for this session.",
    session = session,
)

/**
 * Average processor use, carrying the one qualification that changes what it means.
 *
 * "device-wide" is not decoration. GameCore measures the whole device's processor load, not the
 * game's share of it, and a card that printed "62%" unqualified beside a game's name would be read
 * as a claim about that game.
 */
private fun averageProcessorTile(session: GameSession): SessionTile = averageTile(
    label = "Avg. CPU",
    average = session.averageCpuPercent,
    peak = session.peakCpuPercent,
    peakPrefix = "Peak",
    peakSuffix = "device-wide",
    absentReason = "Processor use was not readable on this device.",
    session = session,
)

/** The shared shape of the two sampled averages: the figure, its peak, or the reason for neither. */
private fun averageTile(
    label: String,
    average: Float?,
    peak: Float?,
    peakPrefix: String,
    peakSuffix: String,
    absentReason: String,
    session: GameSession,
): SessionTile = when {
    average == null -> SessionTile(label = label, value = UNAVAILABLE_TEXT, detail = absentReason)
    !session.hasMeaningfulAggregates -> SessionTile(
        label = label,
        value = UNAVAILABLE_TEXT,
        detail = "Only ${Formatters.count(session.sampleCount, "sample")} — too few to average.",
    )
    else -> SessionTile(
        label = label,
        value = Formatters.percentValue(average),
        detail = peak
            ?.let { "$peakPrefix ${Formatters.percentValue(it)}, $peakSuffix" }
            ?: "Averaged over ${Formatters.count(session.sampleCount, "sample")}, $peakSuffix",
    )
}

// ------------------------------------------------------------------------------ §6 Aim Lab tiles

/**
 * §6's three tiles for an Aim Lab run: its mode, its score, its accuracy.
 *
 * The same three slots a game session fills with heat, memory and processor use, holding the fields
 * a training run actually has. The honesty rule does not relax because the numbers come from
 * GameCore's own engine rather than from a sensor: an unscored mode has no score and a run in which
 * nothing was fired has no accuracy, and both say [UNAVAILABLE_TEXT] rather than printing the zero the
 * stored row happens to contain.
 *
 * @param modeLabel the training mode's own label.
 * @param difficultyLabel the difficulty's own label, shown under the mode.
 * @param isScored whether this mode produces a score at all (free practice does not).
 * @param score the stored score.
 * @param hits shots that landed.
 * @param shots shots fired.
 * @param accuracyPercent accuracy as the engine computes it, passed in rather than divided here so
 *   the list and the results screen cannot round it differently.
 * @param isLegacyScoring true for a run recorded by the older 2D arena, whose scores are not
 *   comparable with the 3D engine's.
 */
fun aimLabTiles(
    modeLabel: String,
    difficultyLabel: String,
    isScored: Boolean,
    score: Int,
    hits: Int,
    shots: Int,
    accuracyPercent: Int,
    isLegacyScoring: Boolean,
): List<SessionTile> = listOf(
    SessionTile(label = "Mode", value = modeLabel, detail = difficultyLabel),
    when {
        !isScored -> SessionTile(
            label = "Score",
            value = UNAVAILABLE_TEXT,
            detail = "$modeLabel is not scored.",
        )
        else -> SessionTile(
            label = "Score",
            value = score.toString(),
            detail = if (isLegacyScoring) {
                "Recorded by the older 2D arena, so it is not comparable with 3D runs."
            } else {
                "Points from this run."
            },
        )
    },
    when {
        shots <= 0 -> SessionTile(
            label = "Accuracy",
            value = UNAVAILABLE_TEXT,
            detail = "No shots were fired in this run.",
        )
        else -> SessionTile(
            label = "Accuracy",
            value = "$accuracyPercent%",
            detail = "$hits of ${Formatters.count(shots, "shot")}",
        )
    },
)

/**
 * The three tiles of one card as a single sentence, for a screen reader.
 *
 * A tile is three separate pieces of text laid out in a column, and a reader that walks them one by one
 * announces nine fragments per card — "PEAK HEAT", "47.1°C", "Warm · average 40.2°C" — in a list where
 * the user is trying to find one session. Collapsing the strip into one stop is the same treatment the
 * §5 profile chips get, and for the same reason.
 *
 * The details are kept rather than dropped, because they are where every "Unavailable" says what it
 * could not measure. A sentence that read "Avg. CPU Unavailable" and stopped would hide exactly the half
 * this app exists to tell.
 */
fun tilesSentence(tiles: List<SessionTile>): String =
    tiles.joinToString(" ") { tile ->
        val detail = tile.detail.trim()
        val ending = if (detail.endsWith('.')) detail else "$detail."
        "${tile.label} ${tile.value}. $ending"
    }

// ------------------------------------------------------------------------------ ordering

/**
 * The facts an ordering needs about one row, whichever repository it came from.
 *
 * The merged list cannot be handed to `SessionRepository.sort`: that function takes
 * [GameSession]s, and half of this screen's rows are not one. Rather than teach the repository
 * about Aim Lab — it is a data class away from a schema it does not own — the ViewModel maps both
 * kinds down to this, which is the whole of what [SessionSort] actually reads.
 *
 * [batteryPercentPerHour] is null for every Aim Lab run and for the game sessions
 * [BatteryDrain] refuses to quote a rate for. Both are the same fact here — there is no rate — and
 * both sort last under [SessionSort.HIGHEST_DRAIN] rather than being dropped, because the user
 * asked for an order and not a filter.
 */
data class SessionOrder(
    val startedAtMillis: Long,
    val durationMillis: Long,
    val batteryPercentPerHour: Float?,
)

/**
 * Orders a merged list without knowing what is in it.
 *
 * Generic over `T` and driven by [orderOf], the same shape [filterByKind] uses and for the same
 * reason: the sort is a function of three numbers, and making it one keeps it testable with a
 * `Pair` and a lambda instead of two databases.
 *
 * `sortedBy` is stable, so rows that tie — two sessions started in the same millisecond, or every
 * run under [SessionSort.HIGHEST_DRAIN] — keep the order they arrived in rather than shuffling
 * between recompositions.
 */
fun <T> sortSessions(
    items: List<T>,
    order: SessionSort,
    orderOf: (T) -> SessionOrder,
): List<T> = when (order) {
    SessionSort.NEWEST_FIRST -> items.sortedByDescending { orderOf(it).startedAtMillis }
    SessionSort.OLDEST_FIRST -> items.sortedBy { orderOf(it).startedAtMillis }
    SessionSort.LONGEST_FIRST -> items.sortedByDescending { orderOf(it).durationMillis }
    SessionSort.HIGHEST_DRAIN -> items.sortedByDescending {
        orderOf(it).batteryPercentPerHour ?: NO_DRAIN_RATE
    }
}

/** Below any real rate, so a row without one sorts last instead of above a session that drained 0.0%/h. */
private const val NO_DRAIN_RATE = -1f

// ------------------------------------------------------------------------------ §6 filter chips

/** One §6 chip: which kind it selects, what it says, and how many rows it would leave. */
data class KindChip(
    val kind: SessionFilterKind,
    val label: String,
    val count: Int,
)

/** The word a §6 chip prints. [SessionFilterKind] is vocabulary and carries no user-facing text. */
fun kindLabel(kind: SessionFilterKind): String = when (kind) {
    SessionFilterKind.ALL -> "All"
    SessionFilterKind.GAMES -> "Games"
    SessionFilterKind.AIMLAB -> "Aim Lab"
}

/**
 * The three §6 chips, always all three, each carrying its real count.
 *
 * Always all three even when one of them is empty, because a chip row whose membership changed with
 * the contents would leave a user who has never opened Aim Lab unable to learn that the screen also
 * lists training runs — and would make the row's width jump the first time one was recorded.
 *
 * The counts are of the whole history rather than of the current selection. They are what makes the
 * chips worth having: a chip reading "Aim Lab · 12" answers the question before it is tapped.
 */
fun kindChips(gameCount: Int, aimLabCount: Int): List<KindChip> = listOf(
    KindChip(SessionFilterKind.ALL, kindLabel(SessionFilterKind.ALL), gameCount + aimLabCount),
    KindChip(SessionFilterKind.GAMES, kindLabel(SessionFilterKind.GAMES), gameCount),
    KindChip(SessionFilterKind.AIMLAB, kindLabel(SessionFilterKind.AIMLAB), aimLabCount),
)

/** The heading of the empty state a chip leaves behind when it matches nothing. */
fun emptyKindTitle(kind: SessionFilterKind): String = when (kind) {
    SessionFilterKind.ALL -> "Nothing recorded yet"
    SessionFilterKind.GAMES -> "No game sessions yet"
    SessionFilterKind.AIMLAB -> "No Aim Lab runs yet"
}

/**
 * What the user would have to do to fill an empty chip, said plainly.
 *
 * Each kind is recorded by something different, so a shared "nothing here" sentence would be wrong
 * for at least one of them: a game session happens on its own once a profile exists, while an Aim
 * Lab run has to be started by hand.
 */
fun emptyKindMessage(kind: SessionFilterKind): String = when (kind) {
    SessionFilterKind.ALL ->
        "A session is recorded while a game you have a profile for is in the foreground, and an " +
            "Aim Lab run is recorded when you finish one. Neither has happened yet."
    SessionFilterKind.GAMES ->
        "A session is recorded on its own while a game you have a profile for is in the " +
            "foreground: how long you played, and whatever else this device lets GameCore measure."
    SessionFilterKind.AIMLAB ->
        "Finish a training run in Aim Lab and it is kept here alongside your game sessions."
}

/**
 * What "Clear" on this screen actually deletes, said before it happens.
 *
 * The sentence changes with [aimLabCount] because the claim does. Sessions is now a merged view, and the
 * button at the top of it clears the *game* history only — the Aim Lab repository is read from here and
 * never written to. A user looking at a list that includes twelve training runs, pressing a button
 * labelled "Clear" above it, has every reason to expect those runs to go too, so the dialog says plainly
 * that they will not. Where there are none, the caveat is left out rather than raising a question about
 * a feature the user may never have opened.
 */
fun clearHistoryMessage(aimLabCount: Int): String {
    val base = "Every recorded game session and all of its samples are deleted, and the totals go back " +
        "to zero. This cannot be undone."
    if (aimLabCount <= 0) return base
    val runs = Formatters.count(aimLabCount, "Aim Lab run")
    val verb = if (aimLabCount == 1) "stays" else "stay"
    return "$base $runs $verb untouched: Aim Lab keeps its own history, and this button does not " +
        "clear it."
}

// ------------------------------------------------------------------------------ the line under the title

/**
 * The subtitle under "Sessions" — the one line that says what the list below currently is.
 *
 * A pure function of the counts rather than a `when` in the header, because it is the screen's only
 * summary of its own state and it has five cases that are easy to get subtly wrong: loading, empty,
 * recording, filtered, and plain. The filtered case prints both numbers ("7 of 23") so a user who
 * has forgotten a chip is selected can see that the list is a subset rather than the history
 * shrinking.
 *
 * @param isLoading true until the first read of the totals has come back.
 * @param liveLabel the game being recorded right now, or null.
 * @param shown how many rows the list is drawing.
 * @param total how many rows exist across both repositories.
 * @param isFiltered whether any chip — kind or game — is narrowing the list.
 */
fun sessionsSubtitle(
    isLoading: Boolean,
    liveLabel: String?,
    shown: Int,
    total: Int,
    isFiltered: Boolean,
): String = when {
    isLoading -> "Reading the history"
    liveLabel != null -> "Recording $liveLabel now"
    total == 0 -> "Nothing recorded yet"
    isFiltered -> "$shown of $total recorded on this device"
    else -> "$total recorded on this device"
}
