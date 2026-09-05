package com.gamecore.core.model

/**
 * One recorded gaming session.
 *
 * Every aggregate here is computed from samples that were actually taken, and each is
 * nullable so that "not measured" is representable. A device with no readable CPU stats
 * produces a session with a real duration, a real battery delta and null CPU figures —
 * which is a useful record — rather than a session full of zeroes that reads as an idle
 * game.
 *
 * [sampleCount] is what makes the nulls interpretable: an average over four samples and an
 * average over four hundred are different claims, and the report screen says which it has.
 */
data class GameSession(
    val id: Long = 0L,
    val packageName: String,
    val gameLabel: String,
    val startedAtMillis: Long,
    /** Null while the session is still running. */
    val endedAtMillis: Long? = null,

    val batteryStartPercent: Int,
    val batteryEndPercent: Int? = null,
    /** True if a charger was connected at any point, which invalidates the drain rate. */
    val wasCharging: Boolean = false,

    val averageCpuPercent: Float? = null,
    val peakCpuPercent: Float? = null,
    val averageMemoryPercent: Float? = null,
    val peakMemoryPercent: Float? = null,
    val averageTemperatureDeciCelsius: Int? = null,
    val peakTemperatureDeciCelsius: Int? = null,
    val averageRefreshRate: Float? = null,
    val averageFrameRate: Float? = null,
    val averageLatencyMillis: Int? = null,

    val profileApplied: Boolean = false,
    val sampleCount: Int = 0,
    /** Why recording stopped. Null while running, and for rows written before this was recorded. */
    val stopReason: StopReason? = null,

    /**
     * The name of the colour preset that was on screen, or null when the display was left alone.
     *
     * Null is also what every session recorded before the colour feature existed reads back as,
     * which is the truth about those sessions rather than a claim that no correction was active.
     */
    val colorPresetName: String? = null,

    /**
     * The correction's own values, kept alongside the name because the name does not survive the
     * preset being renamed or deleted. A report of a six-week-old session should say what the
     * screen was doing, not what a row that may no longer exist is called today.
     */
    val colorCorrection: ColorCorrection? = null,

    /**
     * What the latency probes did across the session, or null when there is no log at all.
     *
     * The two absences are different and both are representable. Null means no log was kept — every
     * session recorded before this existed reads back that way, and saying "no probe failed" about
     * those would be inventing a measurement. [LatencyLog.EMPTY] means a log was kept and nothing went
     * into it, which is what an offline session or a session with latency measurement switched off
     * really looks like.
     *
     * [averageLatencyMillis] stays the only average. A second one derived from the log could disagree
     * with it by a millisecond and put two figures for one thing on the same screen.
     */
    val latencyLog: LatencyLog? = null,
) {
    val isRunning: Boolean get() = endedAtMillis == null

    /** True when there is a colour reading to show. See [colorSummary] for the text. */
    val hasColorReading: Boolean get() = colorPresetName != null || colorCorrection != null

    /**
     * The colour row of a session report, or null when the session has no reading.
     *
     * The preset's name leads because it is what the user recognises, and the values follow
     * because the name may since have been renamed onto something else entirely.
     */
    val colorSummary: String?
        get() {
            val name = colorPresetName?.takeIf { it.isNotBlank() }
            val values = colorCorrection?.summary
            return when {
                name != null && values != null -> "$name — $values"
                name != null -> name
                else -> values
            }
        }

    /**
     * Whether [durationMillis] is the length of the session or a floor under it.
     *
     * False when GameCore stopped being able to see the game rather than seeing it close. The report
     * screen reads this to write "at least 42 min" instead of "42 min", which is the difference
     * between a record and a guess presented as one.
     */
    val hasCompleteDuration: Boolean get() = stopReason?.durationIsComplete ?: true

    /** Wall-clock length. For a running session, pass the current time. */
    fun durationMillis(nowMillis: Long = System.currentTimeMillis()): Long =
        ((endedAtMillis ?: nowMillis) - startedAtMillis).coerceAtLeast(0L)

    /**
     * Battery consumption as a rate, or null when it cannot be stated honestly.
     *
     * Delegated to [BatteryDrain] rather than divided here, so the five-minute floor and the
     * charging rule live in exactly one place.
     */
    fun drain(nowMillis: Long = System.currentTimeMillis()): BatteryDrain? {
        val end = batteryEndPercent ?: return null
        return BatteryDrain(
            startPercent = batteryStartPercent,
            endPercent = end,
            elapsedMillis = durationMillis(nowMillis),
            wasCharging = wasCharging,
        )
    }

    /**
     * Whether the aggregates are worth showing as averages.
     *
     * Below this the report shows the samples themselves and no summary: a mean of three
     * readings taken in the first six seconds of a session describes the loading screen.
     */
    val hasMeaningfulAggregates: Boolean get() = sampleCount >= MIN_SAMPLES_FOR_AVERAGES

    companion object {
        const val MIN_SAMPLES_FOR_AVERAGES = 10

        /** Sessions shorter than this are not saved: they are a mis-tap, not a session. */
        const val MIN_SAVEABLE_MILLIS = 15_000L

        fun starting(
            packageName: String,
            gameLabel: String,
            batteryPercent: Int,
            profileApplied: Boolean,
            nowMillis: Long = System.currentTimeMillis(),
        ) = GameSession(
            packageName = packageName,
            gameLabel = gameLabel,
            startedAtMillis = nowMillis,
            batteryStartPercent = batteryPercent,
            profileApplied = profileApplied,
            // Empty rather than null from the first second: a session being recorded now has a probe
            // log, even before a probe has gone out. Null is reserved for the sessions that never had
            // one, so the report can tell "nothing was measured" from "this predates the log".
            latencyLog = LatencyLog.EMPTY,
        )
    }
}

/**
 * Why a recorded session ended.
 *
 * [DETECTION_LOST] and [PROCESS_DEATH] are the two that earn this enum. Usage access can be revoked
 * while a game runs, Shizuku can die, and Android can kill a foreground service under memory
 * pressure — in all three cases GameCore stopped watching while the game carried on. Those sessions
 * look identical to a session the user closed cleanly, and they are not the same fact: their
 * durations are lower bounds. [durationIsComplete] is what keeps the two apart in the report.
 */
enum class StopReason(val label: String, val durationIsComplete: Boolean) {
    LEFT_FOREGROUND("Game closed", durationIsComplete = true),
    SWITCHED_GAME("Switched to another game", durationIsComplete = true),
    STOPPED_BY_USER("Stopped by you", durationIsComplete = true),

    /** The foreground app became unreadable and stayed that way. The game may have run on. */
    DETECTION_LOST("GameCore could no longer see the foreground app", durationIsComplete = false),

    /** GameCore was killed mid-session; the row was closed from its last sample on next launch. */
    PROCESS_DEATH("GameCore was stopped by the system", durationIsComplete = false),
    ;
}

/**
 * One point in a session's graph.
 *
 * Deliberately small and flat: a session of an hour at one sample every two seconds is 1,800
 * of these, and they are written to the database while a game is running. Nullable primitives
 * rather than `Observed<T>` — the reason for an absence is a live-UI concern, and storing it
 * per sample would multiply the row size for information no graph draws.
 */
data class SessionSample(
    val sessionId: Long,
    val elapsedMillis: Long,
    val cpuPercent: Float? = null,
    val memoryPercent: Float? = null,
    val batteryPercent: Int? = null,
    val temperatureDeciCelsius: Int? = null,
    val refreshRate: Float? = null,
    val frameRate: Float? = null,
    val latencyMillis: Int? = null,
)

/**
 * Aggregates over many sessions, for the history screen's summary.
 *
 * Computed by the database rather than in memory — a user with six months of history should
 * not load every session to see a total.
 */
data class SessionStatistics(
    val sessionCount: Int = 0,
    val totalPlayTimeMillis: Long = 0L,
    val longestSessionMillis: Long = 0L,
    val mostPlayedPackage: String? = null,
    val mostPlayedLabel: String? = null,
    val mostPlayedMillis: Long = 0L,
    val averageBatteryDrainPercentPerHour: Float? = null,
    val peakTemperatureDeciCelsius: Int? = null,
) {
    val hasData: Boolean get() = sessionCount > 0

    companion object {
        val EMPTY = SessionStatistics()
    }
}

/** How the history list is ordered. Sort is done in SQL, not after loading. */
enum class SessionSort(val label: String) {
    NEWEST_FIRST("Newest first"),
    OLDEST_FIRST("Oldest first"),
    LONGEST_FIRST("Longest first"),
    HIGHEST_DRAIN("Highest battery use"),
}
