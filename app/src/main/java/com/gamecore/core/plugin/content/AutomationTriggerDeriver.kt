package com.gamecore.core.plugin.content

import com.gamecore.core.common.valueOrNull
import com.gamecore.core.model.PerformanceSnapshot
import com.gamecore.core.model.ThermalStatus
import com.gamecore.domain.gaming.GamingState

/**
 * Turns a pair of consecutive observations into the [AutomationRule.Trigger]s that *just*
 * became true, and nothing else.
 *
 * The whole point of this object is that it is **edge-triggered**. A rule fires on a
 * transition — the moment a game launches, the moment the battery drops to a low mark, the
 * moment a charger is plugged in — not on the condition continuing to hold. If the deriver
 * reported THERMAL_HIGH on every tick a device happened to be hot, a "when it gets hot, turn
 * animations off" rule would re-apply its action a few times a second for as long as the heat
 * lasted, which is both a flood through the gated broker and the wrong meaning. So every
 * signal here is computed as a change between a *previous* reading and a *next* one, and a
 * steady state — hot then still hot, playing then still playing — derives the empty list.
 *
 * This is the *decision-feed* half of the runtime, matching [AutomationEngine]: it says which
 * triggers happened, the engine decides which rules those triggers plus the condition snapshot
 * select, and the host runs the chosen actions through [com.gamecore.core.plugin.ShizukuActionBroker],
 * which re-gates every one. Nothing here runs anything or touches a device; it reads two
 * immutable snapshots and returns an enum list. It is pure host-side Kotlin with no Android
 * types, no coroutines and no state of its own, so the entire trigger-edge policy is a plain
 * JVM unit test.
 *
 * The first observation has no predecessor. A null `prev` means "we have only ever seen this
 * one state", and no edge can be asserted from a single sample — a device that is already hot
 * on the very first reading has not *crossed* into hot, it merely started there. So the device
 * half returns nothing when `prev` is null rather than firing on startup state, and the
 * lifecycle half treats a null `prev` as the idle baseline (which is correct: the service's
 * first real [GamingState] after idle genuinely is a launch).
 */
object AutomationTriggerDeriver {

    /**
     * Battery level, in whole percent, at or below which [AutomationRule.Trigger.BATTERY_LOW]
     * fires on the way *down*. Twenty is the mark Android's own battery-saver suggestion uses,
     * and it is a floor the user still has time to act on. The trigger is a downward crossing to
     * at-or-below this value, so it fires once as the level passes the mark and not again while
     * the device stays low; a charge back above it re-arms it.
     */
    const val BATTERY_LOW_PERCENT: Int = 20

    /**
     * The lowest thermal band counted as "high".
     *
     * GameCore keys thermal reactions off the platform's own [ThermalStatus] verdict rather than
     * a raw sensor threshold, because a temperature that is alarming on one phone is ordinary on
     * the next and only the platform knows which is which (see [com.gamecore.core.model.ThermalReading]).
     * [ThermalStatus.SEVERE] is the first band at which the system is actually clamping
     * performance to shed heat — the point a frame-rate drop is the device protecting itself —
     * so it is the honest floor for "the device is now too hot", and it is where
     * [ThermalStatus.warrantsAlert] already turns true.
     */
    val THERMAL_HIGH_FLOOR: ThermalStatus = ThermalStatus.SEVERE

    /**
     * The lifecycle edges between two [GamingState]s.
     *
     * `playing` (which package is in front) drives [AutomationRule.Trigger.GAME_LAUNCHED] on a
     * null→package edge and [AutomationRule.Trigger.GAME_CLOSED] on a package→null edge.
     * `startedAtMillis` (the session clock's origin, 0 while idle) drives
     * [AutomationRule.Trigger.SESSION_STARTED] on a 0→set edge and
     * [AutomationRule.Trigger.SESSION_ENDED] on a set→0 edge.
     *
     * The two are derived from their own fields on purpose. On today's [GamingState] the session
     * clock is set and cleared together with `playing`, so in practice a real launch surfaces
     * both GAME_LAUNCHED and SESSION_STARTED on the same tick and a real close both GAME_CLOSED
     * and SESSION_ENDED — a rule keys off whichever trigger it declared. GameCore has no distinct
     * "a session is being recorded" boolean on this state (recording lives in a separate
     * recorder), so conflating the two here would be inventing a signal; reading each trigger
     * from its own field instead keeps both correct, and keeps them correct if the host ever
     * opens a session clock without a tracked package or vice-versa.
     */
    fun lifecycleTriggers(prev: GamingState?, next: GamingState): List<AutomationRule.Trigger> {
        val out = ArrayList<AutomationRule.Trigger>(2)

        val wasGame = prev.gameOpen()
        val isGame = next.gameOpen()
        if (!wasGame && isGame) out += AutomationRule.Trigger.GAME_LAUNCHED
        if (wasGame && !isGame) out += AutomationRule.Trigger.GAME_CLOSED

        val wasSession = prev.sessionOpen()
        val isSession = next.sessionOpen()
        if (!wasSession && isSession) out += AutomationRule.Trigger.SESSION_STARTED
        if (wasSession && !isSession) out += AutomationRule.Trigger.SESSION_ENDED

        return out
    }

    /**
     * The device edges between two [PerformanceSnapshot]s.
     *
     * Each is a crossing, asserted only when a prior reading exists to cross *from*:
     *
     *  * [AutomationRule.Trigger.THERMAL_HIGH] — the thermal band rose from below
     *    [THERMAL_HIGH_FLOOR] into it or above. A snapshot whose status could not be read is
     *    treated as not-high, so an unreadable→high step fires once (the device is known hot now)
     *    and a high→unreadable step does not (absence is not a drop).
     *  * [AutomationRule.Trigger.BATTERY_LOW] — the level fell from above [BATTERY_LOW_PERCENT] to
     *    at or below it. A single downward crossing, even across a jump; already-low stays silent.
     *  * [AutomationRule.Trigger.CHARGER_CONNECTED] — charging went from false to true.
     *
     * A null `prev` is the first observation and yields nothing: there is no baseline to have
     * crossed, so a device that is already hot, already low or already charging on the first
     * sample does not fire — only an actual transition after it does.
     */
    fun deviceTriggers(prev: PerformanceSnapshot?, next: PerformanceSnapshot): List<AutomationRule.Trigger> {
        if (prev == null) return emptyList()
        val out = ArrayList<AutomationRule.Trigger>(3)

        if (!prev.thermalHigh() && next.thermalHigh()) out += AutomationRule.Trigger.THERMAL_HIGH

        val before = prev.battery.levelPercent
        val now = next.battery.levelPercent
        if (before > BATTERY_LOW_PERCENT && now <= BATTERY_LOW_PERCENT) {
            out += AutomationRule.Trigger.BATTERY_LOW
        }

        if (!prev.battery.isCharging && next.battery.isCharging) {
            out += AutomationRule.Trigger.CHARGER_CONNECTED
        }

        return out
    }

    /** A tracked game is in front. A null state (no observation yet) is idle, not open. */
    private fun GamingState?.gameOpen(): Boolean = this?.playing != null

    /** The session clock is running. 0 (or no state yet) is idle; any positive origin is open. */
    private fun GamingState?.sessionOpen(): Boolean = (this?.startedAtMillis ?: 0L) > 0L

    /**
     * Whether this snapshot's thermal band is at or above [THERMAL_HIGH_FLOOR]. A status that is
     * not a real reading counts as not-high: an unknown temperature is not evidence of heat, and
     * firing on it would be inventing the signal the [com.gamecore.core.model.ThermalReading]
     * contract refuses to invent.
     */
    private fun PerformanceSnapshot.thermalHigh(): Boolean {
        val status = thermal.status.valueOrNull ?: return false
        return status.level >= THERMAL_HIGH_FLOOR.level
    }
}
