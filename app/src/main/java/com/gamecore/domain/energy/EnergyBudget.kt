package com.gamecore.domain.energy

import com.gamecore.core.model.BatteryDrain
import com.gamecore.core.model.GameSession
import kotlin.math.roundToLong

/**
 * The result of estimating a game's energy budget from its recorded sessions.
 *
 * Three states, and the UI must show all three distinctly:
 *
 *  - [HasData]: a median rate with the number of sessions that produced it and the total measured
 *    time. The projection uses the median so one bad session does not move the number, and the
 *    required floor of [MIN_USABLE_SESSIONS] and [MIN_MEASURED_MINUTES] keeps the projection from
 *    claiming precision it cannot have. If [currentPercent] is supplied, the remaining minutes are
 *    computed from it; otherwise the rate is shown with "per full charge" so the user can do the
 *    math themselves.
 *  - [NotEnoughData]: the engine refused to project. [reason] says exactly what is missing — too
 *    few usable sessions, not enough measured time, no sessions at all, etc. The UI prints this
 *    reason; it never prints a fabricated number.
 *  - [NoSessions]: the game has no finished sessions. Also honest and distinct from "sessions exist
 *    but are not usable".
 *
 * The engine never invents milliamp-hours or design capacity. Android does not expose a reliable
 * design capacity, and a fake one would produce a fake number. Percent per hour is the only figure
 * the device can honestly report.
 */
sealed interface EnergyEstimate {
    data class HasData(
        val medianPercentPerHour: Float,
        val usableSessions: Int,
        val totalMeasuredMinutes: Long,
        /** Projected minutes at the median rate from the supplied current level, or null when unavailable. */
        val projectedMinutes: Long?,
    ) : EnergyEstimate

    data class NotEnoughData(val reason: String) : EnergyEstimate

    object NoSessions : EnergyEstimate
}

/**
 * Estimates a game's energy budget from its finished sessions.
 *
 * Pure JVM, deterministic, no Android. The rules are the product:
 *
 *  - **Only sessions with a real drain count.** [BatteryDrain.percentPerHour] is null when the
 *    session was charging, under five minutes, or missing an end level — exactly the cases where a
 *    rate would be noise. [GameSession.drain()] already encodes this, so the engine just filters on
 *    it.
 *  - **Median, not mean.** A single heavy session on a hot day should not drag the projection down
 *    for weeks. The median is the 50th percentile of the usable sessions' rates.
 *  - **Floors, not fudge factors.** At least [MIN_USABLE_SESSIONS] sessions and at least
 *    [MIN_MEASURED_MINUTES] of measured time are required. Below that the engine returns
 *    [NotEnoughData] with the specific reason, and the UI says "not enough data yet — need X more
 *    sessions / Y more minutes". No "approximately" and no fake precision.
 *  - **Projection is honest about the current level.** When the caller supplies the current battery
 *    percentage, the remaining minutes are `currentPercent / medianPercentPerHour * 60`. When the
 *    level is unavailable (the caller may not have it, or it may be stale), the engine returns the
 *    rate and the UI prints "X% per hour · per full charge".
 *
 * A game's history does not record which profile was used, only [GameSession.profileApplied].
 * Profiles that share a game share the same history. That is a limitation of the session model and
 * the copy says so; the engine does not pretend to split them.
 */
object EnergyBudget {

    /** Two usable sessions is the minimum for a median to mean anything. */
    const val MIN_USABLE_SESSIONS = 2

    /** Twenty minutes of measured time is the minimum to average over. */
    const val MIN_MEASURED_MINUTES = 20

    /**
     * Estimates the budget from the finished sessions for one game.
     *
     * [currentPercent] is the battery level right now, or null when the caller does not have it.
     * It is only used for the projection; the rate and the floors are independent of it.
     */
    fun estimate(sessions: List<GameSession>, currentPercent: Int?): EnergyEstimate {
        if (sessions.isEmpty()) return EnergyEstimate.NoSessions

        val usable = sessions
            .filter { it.drain()?.percentPerHour != null }
            .map { it.drain()!! }
        if (usable.size < MIN_USABLE_SESSIONS) {
            return EnergyEstimate.NotEnoughData(
                "Need ${MIN_USABLE_SESSIONS - usable.size} more session${if (MIN_USABLE_SESSIONS - usable.size > 1) "s" else ""} " +
                    "with a real battery drain (not charging, at least five minutes, both levels recorded)."
            )
        }
        val totalMinutes = usable.sumOf { it.elapsedMillis / 60_000L }
        if (totalMinutes < MIN_MEASURED_MINUTES) {
            val need = MIN_MEASURED_MINUTES - totalMinutes
            return EnergyEstimate.NotEnoughData(
                "Need $need more minute${if (need > 1) "s" else ""} of measured play time across " +
                    "usable sessions."
            )
        }

        val rates = usable.map { it.percentPerHour!! }.sorted()
        // With an even number of sessions there is no single middle value; the median is the mean of
        // the two middle rates. Taking rates[size / 2] alone picks the upper of the pair, which biases
        // every projection upward — the exact error the median exists to avoid.
        val median = if (rates.size % 2 == 1) {
            rates[rates.size / 2]
        } else {
            (rates[rates.size / 2 - 1] + rates[rates.size / 2]) / 2f
        }
        val projected = currentPercent?.let { remaining ->
            ((remaining.toFloat() / median) * 60f).roundToLong().coerceAtLeast(0L)
        }

        return EnergyEstimate.HasData(
            medianPercentPerHour = median,
            usableSessions = usable.size,
            totalMeasuredMinutes = totalMinutes,
            projectedMinutes = projected,
        )
    }

    /**
     * The sentence the UI prints when the engine refuses, or null when there is a projection.
     *
     * [SessionReportUiState] or the profile editor can call this to get the same text the engine
     * would return, so the copy is consistent.
     */
    fun explainsRefusal(sessions: List<GameSession>, currentPercent: Int?): String? {
        val result = estimate(sessions, currentPercent)
        return when (result) {
            is EnergyEstimate.NotEnoughData -> result.reason
            EnergyEstimate.NoSessions -> "No sessions recorded for this game yet."
            else -> null
        }
    }
}