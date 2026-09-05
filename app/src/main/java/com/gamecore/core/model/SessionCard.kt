package com.gamecore.core.model

import com.gamecore.core.common.Formatters

/**
 * The shareable card for one session: what it says, decided here rather than while drawing it.
 *
 * §23's card is a PNG, and a PNG is the one thing this app produces that leaves the device. That is the
 * reason the whole of it is decided in a pure model with a unit test around it, and the renderer only
 * turns strings into pixels: a report on screen can be re-read next to the app that qualifies it, while a
 * card lands in a chat window with nothing beside it. Every figure on it has to be defensible on its own.
 *
 * So the rules the report screen follows apply here with less room to explain themselves. A reading the
 * device could not take is [ABSENT] — never a zero, and never quietly dropped so that the card looks
 * complete. A duration that is a floor is marked as one. A battery rate is only quoted when
 * [BatteryDrain] says it may be. And the sample count travels with the figures, because four samples and
 * four hundred are different claims and the card is where that distinction is easiest to lose.
 *
 * [gameLabel] is the one string here that GameCore did not write. It comes from the package manager,
 * which means an app author chose it, so it is cleaned and clamped before it is drawn — a label with a
 * newline in it would break the layout of an image the user is about to send to somebody.
 */
data class SessionCard(
    val gameLabel: String,
    val subtitle: String,
    /** Exactly four, in a fixed order, so two cards for the same game read the same way. */
    val figures: List<CardFigure>,
    val caption: String,
    val footer: String = FOOTER,
) {
    companion object {
        /** The em dash the report screen uses, for the same reason and with the same meaning. */
        const val ABSENT = "—"

        /** Long enough for a real game name, short enough to draw at title size on one line. */
        const val MAX_LABEL_CHARS = 34

        const val FOOTER =
            "Measured on this device by GameCore. Nothing was measured inside the game, and nothing " +
                "here was uploaded anywhere."

        private const val BATTERY_LABEL = "Battery drain"

        private val WHITESPACE_RUN = Regex("\\s+")

        fun from(session: GameSession, nowMillis: Long = System.currentTimeMillis()): SessionCard {
            val elapsed = session.durationMillis(nowMillis)
            val figures = listOf(
                duration(session, elapsed),
                frameRate(session),
                heat(session),
                battery(session.drain(nowMillis)),
            )
            return SessionCard(
                gameLabel = cleanLabel(session.gameLabel),
                subtitle = Formatters.dateTime(session.startedAtMillis),
                figures = figures,
                caption = caption(session, figures.any { it.value == ABSENT }),
            )
        }

        /**
         * Always a reading, because the clock is the one thing no device withholds.
         *
         * The `≥` is the report screen's own shorthand for a session whose end was inferred rather than
         * observed, and the caption says what it means — a symbol alone on a shared image is a puzzle.
         */
        private fun duration(session: GameSession, elapsed: Long): CardFigure {
            val text = Formatters.durationCoarse(elapsed)
            return CardFigure(
                label = "Duration",
                value = if (session.hasCompleteDuration) text else "≥ $text",
                isReading = true,
            )
        }

        /**
         * Frame rate, which most sessions will not have.
         *
         * GameCore measures frames in its own window and refuses to infer a game's rate from anything
         * else (§24B), so a dash here is the normal case rather than a fault, and it must stay a dash. A
         * zero would read as a frozen game.
         */
        private fun frameRate(session: GameSession): CardFigure {
            val fps = session.averageFrameRate?.takeIf { it > 0f && !it.isNaN() }
            return CardFigure(
                label = "Avg. frame rate",
                value = fps?.let { "${Formatters.hertzValue(it)} fps" } ?: ABSENT,
                isReading = fps != null,
            )
        }

        private fun heat(session: GameSession): CardFigure {
            val deci = session.averageTemperatureDeciCelsius
            return CardFigure(
                label = "Avg. heat",
                value = deci?.let { Formatters.temperature(it) } ?: ABSENT,
                isReading = deci != null,
            )
        }

        /**
         * A rate where one may be quoted, points lost where it may not, a dash where neither exists.
         *
         * The three cases are different facts, and the label changes with them rather than the value
         * quietly changing unit under a fixed heading. A charger connected during the session makes a
         * rate meaningless — the battery went up — so "Charging" is printed instead, dimmed, because it
         * explains the tile rather than measuring it. Under [BatteryDrain.MIN_RELIABLE_MILLIS] the rate is
         * withheld for the same reason the report withholds it, but the points lost are still a real
         * subtraction, so the tile becomes the figure it can support instead of going blank.
         *
         * `%/h` rather than the report's `% / hour`: the same figure, in the width a tile has for it.
         */
        private fun battery(drain: BatteryDrain?): CardFigure = when {
            drain == null -> CardFigure(BATTERY_LABEL, ABSENT, isReading = false)
            drain.wasCharging -> CardFigure(BATTERY_LABEL, "Charging", isReading = false)
            else -> when (val perHour = drain.percentPerHour) {
                null -> CardFigure("Battery used", "${drain.pointsLost}%", isReading = true)
                else -> CardFigure(
                    label = BATTERY_LABEL,
                    value = Formatters.percentValue(perHour, decimals = 1) + "/h",
                    isReading = true,
                )
            }
        }

        /**
         * What the four figures rest on, in the space of a sentence or two.
         *
         * This is the part a card cannot do without. The figures above are averages, and an average of
         * six samples taken in the first minute describes a loading screen — so the count is stated
         * rather than implied, and the two caveats that change how the numbers should be read are stated
         * with it.
         */
        private fun caption(session: GameSession, hasAbsent: Boolean): String = buildString {
            if (session.isRunning) {
                append("Still being recorded, so these are the figures so far. ")
            }
            append(
                if (session.hasMeaningfulAggregates) {
                    "Averaged over ${Formatters.count(session.sampleCount, "sample")} taken while the " +
                        "game was in the foreground."
                } else {
                    "${Formatters.count(session.sampleCount, "sample")} — too few to average, so these " +
                        "are close to the readings themselves."
                },
            )
            if (!session.hasCompleteDuration) {
                append(" Recording was interrupted, so the length is a lower bound.")
            }
            if (hasAbsent) append(" A dash is a reading this device could not take, not a zero.")
        }

        /**
         * The game's own name, made safe to draw.
         *
         * An app label is chosen by whoever wrote that app, and GameCore never audits it. Control
         * characters and line breaks are replaced rather than escaped, because this string is drawn into
         * an image rather than parsed by anything — the risk is a card whose title overruns its panel or
         * pushes the figures off the bottom, not code execution. A label that cleans up to nothing at
         * all still needs a title, so it gets one.
         */
        private fun cleanLabel(raw: String): String {
            val collapsed = raw
                .map { if (it.isISOControl()) ' ' else it }
                .joinToString(separator = "")
                .replace(WHITESPACE_RUN, " ")
                .trim()
            return when {
                collapsed.isEmpty() -> "Unnamed game"
                collapsed.length <= MAX_LABEL_CHARS -> collapsed
                else -> collapsed.take(MAX_LABEL_CHARS - 1).trimEnd() + "…"
            }
        }
    }
}

/**
 * One tile on the card.
 *
 * [isReading] is what the renderer dims: a value that is not a measurement of the label above it — a
 * dash, or "Charging" where a drain rate would go — is drawn quieter than one that is, so the card can be
 * read at a glance without mistaking an explanation for a figure.
 */
data class CardFigure(
    val label: String,
    val value: String,
    val isReading: Boolean,
)
