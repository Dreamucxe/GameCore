package com.gamecore.domain.gaming

import com.gamecore.core.common.Formatters
import com.gamecore.core.model.GameProfile
import com.gamecore.core.model.GameSession
import com.gamecore.core.model.PerformanceMode

/**
 * A *starting* profile derived from what a game was actually measured doing (spec §4, feature #3).
 *
 * Pure, and deliberately so: it takes one game's recorded [GameSession]s and returns a [GameProfile]
 * plus a human sentence for every field it filled — no repository, no Android, no clock — so the one
 * thing this feature must never do, invent a number, is a thing a JVM test can pin.
 *
 * The whole feature is honesty. Every field it sets is backed by a measurement named in the sentence
 * beside it; a dimension the sessions did not measure enough to speak to is left `null`, which is
 * [GameProfile]'s own word for "leave it alone". A game with thin or contradictory history gets no
 * suggestion at all rather than a guess dressed as advice — [suggest] returns null, and the caller
 * shows nothing.
 *
 * Nothing here saves or applies anything. The output is an unsaved draft the user opens in the editor.
 */
object ProfileSuggester {

    /**
     * The floor under which no suggestion is offered (spec §4 asks for 3–5; **4** chosen).
     *
     * Four finished sessions with real aggregates is enough that a median is describing the game rather
     * than one loading screen or one thermal fluke, and low enough that a user who has genuinely settled
     * on a game sees the offer within a few evenings rather than never.
     */
    const val MIN_SESSIONS = 4

    /** A single field will not be filled unless at least this many usable sessions measured its input. */
    const val MIN_FIELD_SESSIONS = 3

    /**
     * Frame rate at or below this fraction of the refresh rate the panel was actually running means the
     * display is refreshing faster than the game can draw, so capping it lower costs no frames.
     */
    const val REFRESH_HEADROOM = 0.85f

    /**
     * 60.0 °C. The temperature [com.gamecore.core.model.ThermalClassifier] already calls "hot", and the
     * value the editor defaults a fresh downshift trigger to (`DEFAULT_THERMAL_LIMIT_DECI`), so a
     * suggested trigger is the same number the user would have reached for by hand — not a new one.
     */
    const val HOT_PEAK_DECI = 600

    /** Per-hour battery loss at or above which the display's own draw is worth trading refresh rate for. */
    const val HEAVY_DRAIN_PER_HOUR = 25f

    /** The rates worth capping to. A cap is only offered at a standard rate below the observed refresh. */
    val STANDARD_RATES = listOf(60f, 90f, 120f, 144f)

    /**
     * True when a game may be offered a suggestion: it has no profile yet and enough usable history.
     *
     * "Usable" is the same filter [suggest] derives from, so eligibility never promises a suggestion the
     * derivation cannot then produce for lack of measured fields — that second gate is [suggest] returning
     * null, which the caller treats the same way as ineligible.
     */
    fun isEligible(existingProfile: GameProfile?, sessions: List<GameSession>): Boolean =
        existingProfile == null && usable(sessions).size >= MIN_SESSIONS

    /**
     * A starting profile for [packageName], or null when nothing can be suggested honestly.
     *
     * Null in two cases the caller cannot tell apart and does not need to: the game is not eligible, or it
     * is but no single field had enough measured backing to fill. Either way the caller shows no card.
     *
     * Battery is decided before refresh on purpose. [PerformanceMode.BATTERY_SAVER] already pins the panel
     * to its lowest rate, so a separate [GameProfile.targetRefreshRate] cap beside it would be a second
     * control saying the same thing and able to disagree with it. When drain warrants the mode, the
     * refresh cap is not considered.
     */
    fun suggest(
        packageName: String,
        label: String,
        existingProfile: GameProfile?,
        sessions: List<GameSession>,
    ): SuggestedProfile? {
        if (!isEligible(existingProfile, sessions)) return null
        val pool = usable(sessions)
        var profile = GameProfile.forGame(packageName, label)
        val reasons = mutableListOf<SuggestionReason>()

        val battery = batteryReason(pool)
        if (battery != null) {
            profile = profile.copy(performanceMode = PerformanceMode.BATTERY_SAVER)
            reasons += battery
        } else {
            refreshCap(pool)?.let { (rate, reason) ->
                profile = profile.copy(targetRefreshRate = rate)
                reasons += reason
            }
        }

        thermalReason(pool)?.let { reason ->
            profile = profile.copy(thermalDownshiftEnabled = true, thermalLimitDeciCelsius = HOT_PEAK_DECI)
            reasons += reason
        }

        if (reasons.isEmpty()) return null
        return SuggestedProfile(profile = profile, reasons = reasons, sessionCount = pool.size)
    }

    // ---------------------------------------------------------------------------- per-field derivations

    /**
     * Finished sessions whose aggregates are worth reading.
     *
     * [GameSession.hasMeaningfulAggregates] is the same bar the session report uses before it shows an
     * average rather than the raw samples, so a suggestion is built from exactly the sessions the app is
     * already willing to state averages about. A running session (no end time) is excluded: its
     * aggregates and its drain are not final.
     */
    private fun usable(sessions: List<GameSession>): List<GameSession> =
        sessions.filter { it.endedAtMillis != null && it.hasMeaningfulAggregates }

    /**
     * A refresh-rate cap, or null when the panel was not outrunning the game.
     *
     * Reads only the sessions that measured both frame rate and refresh rate; needs at least
     * [MIN_FIELD_SESSIONS] of them so one quiet menu screen cannot decide the cap. The medians resist a
     * single outlier session. A cap is offered only when the game's frames sit well under the refresh the
     * panel was running (see [REFRESH_HEADROOM]) *and* a standard rate exists that clears the frame rate
     * yet still sits below what the panel ran — otherwise there is nothing to gain and it stays null.
     */
    private fun refreshCap(pool: List<GameSession>): Pair<Float, SuggestionReason>? {
        val paired = pool.filter { it.averageFrameRate != null && it.averageRefreshRate != null }
        if (paired.size < MIN_FIELD_SESSIONS) return null
        val frame = median(paired.map { it.averageFrameRate!! }) ?: return null
        val refresh = median(paired.map { it.averageRefreshRate!! }) ?: return null
        if (frame > refresh * REFRESH_HEADROOM) return null
        val cap = STANDARD_RATES.firstOrNull { it >= frame } ?: return null
        if (cap >= refresh) return null
        val text = "Frames averaged ${Math.round(frame)} fps while the screen ran at " +
            "${Formatters.hertz(refresh)} over ${paired.size} sessions — capping to " +
            "${Formatters.hertz(cap)} stops the panel refreshing faster than the game draws."
        return cap to SuggestionReason(SuggestedField.REFRESH_RATE, text)
    }

    /**
     * Battery saver, or null when drain was not consistently heavy.
     *
     * Reads only the drains [com.gamecore.core.model.BatteryDrain] is willing to state as a rate — a
     * charged or too-short session contributes nothing — and needs [MIN_FIELD_SESSIONS] of them. The
     * median per-hour loss must reach [HEAVY_DRAIN_PER_HOUR].
     */
    private fun batteryReason(pool: List<GameSession>): SuggestionReason? {
        val rates = pool.mapNotNull { it.drain()?.takeIf { d -> d.isReliable }?.percentPerHour }
        if (rates.size < MIN_FIELD_SESSIONS) return null
        val rate = median(rates) ?: return null
        if (rate < HEAVY_DRAIN_PER_HOUR) return null
        val text = "Battery fell about ${Math.round(rate)}%/hour over ${rates.size} sessions — " +
            "Battery saver pins the display to its lowest refresh rate, usually the biggest single saving."
        return SuggestionReason(SuggestedField.PERFORMANCE_MODE, text)
    }

    /**
     * Thermal auto-downshift, or null when the game did not run hot.
     *
     * Reads only the sessions that measured a peak temperature; needs [MIN_FIELD_SESSIONS] of them and a
     * median peak at or above [HOT_PEAK_DECI]. The trigger set alongside it is that same threshold, which
     * the sessions demonstrably reach — not a value pulled from nowhere.
     */
    private fun thermalReason(pool: List<GameSession>): SuggestionReason? {
        val peaks = pool.mapNotNull { it.peakTemperatureDeciCelsius }
        if (peaks.size < MIN_FIELD_SESSIONS) return null
        val peak = medianInt(peaks) ?: return null
        if (peak < HOT_PEAK_DECI) return null
        val text = "Peaked around ${Formatters.temperatureShort(peak)} over ${peaks.size} sessions — " +
            "auto-cooling steps the refresh rate down when it reaches " +
            "${Formatters.temperatureShort(HOT_PEAK_DECI)}, then back up as it cools."
        return SuggestionReason(SuggestedField.THERMAL_DOWNSHIFT, text)
    }

    // -------------------------------------------------------------------------------------- statistics

    /** The middle value of a sorted copy — the mean of the two middles for an even count. Null if empty. */
    private fun median(values: List<Float>): Float? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
    }

    private fun medianInt(values: List<Int>): Int? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }
}

/**
 * A suggested starting profile and the one-line reason for every field it filled.
 *
 * [sessionCount] is the M the card names ("Suggested from your last M sessions") — the count of usable
 * sessions the derivation actually read, so the number the user is shown is the number that drove it.
 */
data class SuggestedProfile(
    val profile: GameProfile,
    val reasons: List<SuggestionReason>,
    val sessionCount: Int,
)

/** One filled field, and the measurement-backed sentence explaining it. */
data class SuggestionReason(
    val field: SuggestedField,
    val text: String,
)

/** Which profile field a [SuggestionReason] speaks to. One per field the suggester can fill. */
enum class SuggestedField { REFRESH_RATE, PERFORMANCE_MODE, THERMAL_DOWNSHIFT }
