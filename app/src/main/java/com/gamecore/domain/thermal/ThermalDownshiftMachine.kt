package com.gamecore.domain.thermal

import com.gamecore.core.model.ThermalClass
import kotlin.math.abs

/**
 * Thermal auto-downshift (spec §B), as a pure state machine on values passed in rather than a running
 * loop with sensors and a timer.
 *
 * While a game session is active and a profile opted in, the display's refresh rate steps *down* one rung
 * when the phone stays hot, and back *up* one rung when it cools — never below the user's floor, and never
 * faster than a minimum interval. Every one of those rules is a statement about time and state, so this is
 * modelled the way [com.gamecore.core.overlay.HoldToConfirm] and
 * [com.gamecore.domain.optimization.WriteLedger] are: the caller (the service, inside the existing ~2 s
 * monitoring loop) owns the immutable [ThermalDownshiftState], calls [evaluate] each tick, performs any
 * [ThermalDownshiftDecision.SetRate] through `RefreshRateController`, and threads the returned state back.
 *
 * What this machine will never claim is that lowering the rate reduced the heat — it reports only what it
 * *did*. Two rules protect the user from a machine that misreads the device:
 *  - A [ThermalClass.CRITICAL] verdict, or a run of missing readings, never causes an *upshift*. Heat that
 *    cannot be measured is treated as heat.
 *  - If the observed rate moves for a reason other than this machine's own last [SetRate] — the user, the
 *    game, or the system changed it — auto control latches [DownshiftStatus.PAUSED_EXTERNAL] for the rest
 *    of the session. It does not fight whoever is on the other end.
 *
 * The `thermalClass` passed in is what [com.gamecore.core.model.ThermalClassifier.classify] already
 * produced for this tick — this machine reuses that one classification and never re-derives a severity.
 */
data class ThermalDownshiftConfig(
    /** Whether this profile opted in. Off is the default. */
    val enabled: Boolean = false,
    /**
     * Downshift when the temperature reaches this (in tenths of a degree C), or null to not trigger on a
     * raw temperature at all and rely on [thermalStatusFloor] instead.
     */
    val temperatureLimitDeciCelsius: Int? = null,
    /**
     * Downshift when the platform's classification reaches this severity or worse (e.g. [ThermalClass.HOT]),
     * or null to not trigger on platform status. At least one of this and [temperatureLimitDeciCelsius]
     * should be set for the feature to do anything.
     */
    val thermalStatusFloor: ThermalClass? = null,
    /** The lowest rate auto control may drop to. Never stepped below. */
    val floorRateHz: Float,
    /** How far below the limit the temperature must fall before a cool is counted — 5 °C by default. */
    val hysteresisDeciCelsius: Int = DEFAULT_HYSTERESIS_DECI,
    /** How long the hot condition must hold before a downshift — 30 s by default. */
    val sustainHotMillis: Long = DEFAULT_SUSTAIN_HOT_MILLIS,
    /** How long the cool condition must hold before an upshift — 90 s by default. */
    val sustainCoolMillis: Long = DEFAULT_SUSTAIN_COOL_MILLIS,
    /** The shortest gap between two rate changes — 60 s by default. */
    val minIntervalMillis: Long = DEFAULT_MIN_INTERVAL_MILLIS,
) {
    companion object {
        const val DEFAULT_HYSTERESIS_DECI = 50
        const val DEFAULT_SUSTAIN_HOT_MILLIS = 30_000L
        const val DEFAULT_SUSTAIN_COOL_MILLIS = 90_000L
        const val DEFAULT_MIN_INTERVAL_MILLIS = 60_000L

        /** Consecutive missing readings before the machine reports [DownshiftStatus.UNAVAILABLE]. */
        const val MISS_LIMIT = 3

        /** Consecutive read-back failures before auto control stops for the session. */
        const val READBACK_FAILURE_LIMIT = 3

        /** A rate this close to another is "the same rate" — panels advertise 119.998 for 120. */
        const val RATE_TOLERANCE = 1.5f
    }
}

/** The chip the pill and panel show, and its human line. */
enum class DownshiftStatus(val label: String) {
    /** Watching; nothing has needed changing. */
    ACTIVE("Auto-cooling armed"),

    /** Currently holding a lowered rate, or actively stepping. */
    COOLING("Auto-cooling"),

    /** The observed rate changed outside this machine; auto control is paused for the session. */
    PAUSED_EXTERNAL("Paused: rate changed elsewhere"),

    /** Readings are missing, or the ladder has fewer than two usable rates, or read-backs keep failing. */
    UNAVAILABLE("Auto-cooling unavailable"),
}

/** What the caller should do this tick. The service performs [SetRate]; this machine only chooses it. */
sealed interface ThermalDownshiftDecision {
    data object NoChange : ThermalDownshiftDecision
    data class SetRate(val targetHz: Float, val reason: String) : ThermalDownshiftDecision
}

/**
 * The state the caller threads through a session. Immutable; [evaluate] and [recordReadbackResult] return
 * a new copy.
 *
 * [currentRateHz] is the rate auto control believes is set — seeded from the profile's configured rate at
 * session start ([initial]) and updated on each [ThermalDownshiftDecision.SetRate]. [lastSetRateHz] is the
 * last rate this machine itself requested, which is what [evaluate] compares the observed rate against to
 * detect an external change.
 */
data class ThermalDownshiftState(
    val currentRateHz: Float,
    val lastSetRateHz: Float?,
    val hotSinceMillis: Long?,
    val coolSinceMillis: Long?,
    val lastChangeMillis: Long?,
    val paused: Boolean,
    val consecutiveMisses: Int,
    val readbackFailures: Int,
    val status: DownshiftStatus,
) {
    companion object {
        /** Auto control starts from the profile's configured rate (spec §B5). */
        fun initial(startRateHz: Float): ThermalDownshiftState = ThermalDownshiftState(
            currentRateHz = startRateHz,
            lastSetRateHz = null,
            hotSinceMillis = null,
            coolSinceMillis = null,
            lastChangeMillis = null,
            paused = false,
            consecutiveMisses = 0,
            readbackFailures = 0,
            status = DownshiftStatus.ACTIVE,
        )
    }
}

object ThermalDownshiftMachine {

    /**
     * One tick. Returns the state to carry forward and what to do to the display.
     *
     * @param temperatureDeciCelsius the current reading, or null when it could not be read.
     * @param thermalClass the classification [com.gamecore.core.model.ThermalClassifier] already produced.
     * @param observedRateHz the rate the display is actually at, or null when it could not be read.
     * @param supportedRatesAtOrAboveFloor the ladder — this display's real rates, filtered to `>= floor`,
     *   in any order; the machine sorts them. Fewer than two means there is nothing to step between.
     */
    fun evaluate(
        state: ThermalDownshiftState,
        config: ThermalDownshiftConfig,
        nowMillis: Long,
        temperatureDeciCelsius: Int?,
        thermalClass: ThermalClass,
        observedRateHz: Float?,
        supportedRatesAtOrAboveFloor: List<Float>,
    ): Pair<ThermalDownshiftState, ThermalDownshiftDecision> {
        if (!config.enabled || state.paused) {
            return state to ThermalDownshiftDecision.NoChange
        }

        // The ladder, high→low, with only rates at or above the floor. Fewer than two rungs is nothing to
        // step between: report unavailable and touch nothing.
        val ladder = supportedRatesAtOrAboveFloor
            .filter { it >= config.floorRateHz - ThermalDownshiftConfig.RATE_TOLERANCE }
            .distinct()
            .sortedDescending()
        if (ladder.size < 2) {
            return state.copy(status = DownshiftStatus.UNAVAILABLE) to ThermalDownshiftDecision.NoChange
        }

        // An external change: the panel is at a rate this machine did not last request. Latch paused and
        // stop, so the user/game/system that moved it is not fought. Only checked once the machine has
        // actually set something — before that, whatever the panel starts at is not "external".
        val last = state.lastSetRateHz
        if (observedRateHz != null && last != null && !near(observedRateHz, last)) {
            return state.copy(paused = true, status = DownshiftStatus.PAUSED_EXTERNAL) to
                ThermalDownshiftDecision.NoChange
        }

        // Whether the device reads as hot this tick, and whether that reading exists at all.
        val hotByTemp = config.temperatureLimitDeciCelsius?.let { limit ->
            temperatureDeciCelsius != null && temperatureDeciCelsius >= limit
        } ?: false
        val hotByStatus = config.thermalStatusFloor?.let { floor ->
            thermalClass != ThermalClass.UNAVAILABLE && thermalClass.severity >= floor.severity
        } ?: false
        val critical = thermalClass == ThermalClass.CRITICAL
        val hot = hotByTemp || hotByStatus || critical

        val missing = temperatureDeciCelsius == null && thermalClass == ThermalClass.UNAVAILABLE
        if (missing) {
            // Hold. A missing reading never upshifts, and after a run of them the machine says so. The hot
            // timer is cleared (we cannot claim it is hot) but the cool timer is NOT started (we cannot
            // claim it is cool either), so a gap in readings can never let a cool complete.
            val misses = state.consecutiveMisses + 1
            val status = if (misses >= ThermalDownshiftConfig.MISS_LIMIT) DownshiftStatus.UNAVAILABLE
            else state.status
            return state.copy(
                consecutiveMisses = misses,
                hotSinceMillis = null,
                coolSinceMillis = null,
                status = status,
            ) to ThermalDownshiftDecision.NoChange
        }

        // A real reading resets the miss counter and clears an UNAVAILABLE that misses caused.
        var s = state.copy(
            consecutiveMisses = 0,
            status = if (state.status == DownshiftStatus.UNAVAILABLE) DownshiftStatus.ACTIVE else state.status,
        )

        val intervalPassed = s.lastChangeMillis == null ||
            nowMillis - s.lastChangeMillis!! >= config.minIntervalMillis

        if (hot) {
            // Track how long it has been hot; a cool timer cannot survive a hot tick.
            val hotSince = s.hotSinceMillis ?: nowMillis
            s = s.copy(hotSinceMillis = hotSince, coolSinceMillis = null)
            val sustained = nowMillis - hotSince >= config.sustainHotMillis
            val atFloor = near(s.currentRateHz, ladder.last())
            if (sustained && intervalPassed && !atFloor) {
                val target = nextLower(ladder, s.currentRateHz)
                if (target != null) {
                    return s.copy(
                        currentRateHz = target,
                        lastSetRateHz = target,
                        lastChangeMillis = nowMillis,
                        // Re-arm the sustain timer from now, so the next step also waits the full sustain.
                        hotSinceMillis = nowMillis,
                        status = DownshiftStatus.COOLING,
                    ) to ThermalDownshiftDecision.SetRate(
                        target,
                        "Lowering the refresh rate to ${fmt(target)} Hz while the device stays hot.",
                    )
                }
            }
            return s to ThermalDownshiftDecision.NoChange
        }

        // Not hot. An upshift needs the temperature at or below (limit − hysteresis) sustained for the cool
        // time — and a CRITICAL class never gets here because it forces `hot`. If there is no temperature
        // limit configured (status-only triggering), "cool enough" is simply "not hot", which the branch
        // above already excluded, so cool is tracked from the first not-hot tick.
        val coolEnough = when (val limit = config.temperatureLimitDeciCelsius) {
            null -> true
            else -> temperatureDeciCelsius != null &&
                temperatureDeciCelsius <= limit - config.hysteresisDeciCelsius
        }
        if (!coolEnough) {
            // In the hysteresis band: neither hot nor cool. Hold both timers cleared so a hover around the
            // limit never accumulates toward either edge.
            return s.copy(hotSinceMillis = null, coolSinceMillis = null) to ThermalDownshiftDecision.NoChange
        }

        val coolSince = s.coolSinceMillis ?: nowMillis
        s = s.copy(hotSinceMillis = null, coolSinceMillis = coolSince)
        val sustained = nowMillis - coolSince >= config.sustainCoolMillis
        val atTop = near(s.currentRateHz, ladder.first())
        if (sustained && intervalPassed && !atTop) {
            val target = nextHigher(ladder, s.currentRateHz)
            if (target != null) {
                val backAtTop = near(target, ladder.first())
                return s.copy(
                    currentRateHz = target,
                    lastSetRateHz = target,
                    lastChangeMillis = nowMillis,
                    coolSinceMillis = nowMillis,
                    status = if (backAtTop) DownshiftStatus.ACTIVE else DownshiftStatus.COOLING,
                ) to ThermalDownshiftDecision.SetRate(
                    target,
                    "Raising the refresh rate to ${fmt(target)} Hz now the device has cooled.",
                )
            }
        }
        return s to ThermalDownshiftDecision.NoChange
    }

    /**
     * Records whether the last [ThermalDownshiftDecision.SetRate] actually took, from the caller's
     * read-back (`RefreshRateController` returns Applied / NotHonoured / …). A success clears the counter;
     * [ThermalDownshiftConfig.READBACK_FAILURE_LIMIT] consecutive failures latch the machine paused, since
     * a device that keeps ignoring the write is one auto control cannot drive.
     */
    fun recordReadbackResult(state: ThermalDownshiftState, applied: Boolean): ThermalDownshiftState =
        if (applied) {
            state.copy(readbackFailures = 0)
        } else {
            val failures = state.readbackFailures + 1
            if (failures >= ThermalDownshiftConfig.READBACK_FAILURE_LIMIT) {
                state.copy(readbackFailures = failures, paused = true, status = DownshiftStatus.UNAVAILABLE)
            } else {
                state.copy(readbackFailures = failures)
            }
        }

    private fun nextLower(ladderHighToLow: List<Float>, current: Float): Float? {
        val i = indexNear(ladderHighToLow, current)
        return when {
            i < 0 -> ladderHighToLow.firstOrNull { it < current - RATE_EPS }
            i < ladderHighToLow.lastIndex -> ladderHighToLow[i + 1]
            else -> null
        }
    }

    private fun nextHigher(ladderHighToLow: List<Float>, current: Float): Float? {
        val i = indexNear(ladderHighToLow, current)
        return when {
            i < 0 -> ladderHighToLow.lastOrNull { it > current + RATE_EPS }
            i > 0 -> ladderHighToLow[i - 1]
            else -> null
        }
    }

    private fun indexNear(ladder: List<Float>, rate: Float): Int =
        ladder.indexOfFirst { near(it, rate) }

    private fun near(a: Float, b: Float): Boolean = abs(a - b) <= ThermalDownshiftConfig.RATE_TOLERANCE

    private fun fmt(rate: Float): String =
        if (rate % 1f == 0f) rate.toInt().toString() else rate.toString()

    private const val RATE_EPS = 0.01f
}
