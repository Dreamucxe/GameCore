package com.gamecore.domain.gaming

import com.gamecore.core.common.valueOrNull
import com.gamecore.core.model.ChangeOrigin
import com.gamecore.core.model.GameProfile
import com.gamecore.core.model.OptimizationAction
import com.gamecore.core.model.PerformanceSnapshot
import com.gamecore.core.model.ThermalClass
import com.gamecore.core.model.ThermalClassifier
import com.gamecore.core.system.DisplayReader
import com.gamecore.core.system.RefreshRateController
import com.gamecore.core.shizuku.ShizukuManager
import com.gamecore.domain.optimization.OptimizationManager
import com.gamecore.domain.power.BatterySaverAction
import com.gamecore.domain.power.BatterySaverOverrideConfig
import com.gamecore.domain.power.BatterySaverOverrideMachine
import com.gamecore.domain.power.BatterySaverOverrideState
import com.gamecore.domain.power.OverrideStatus
import com.gamecore.domain.thermal.ThermalDownshiftConfig
import com.gamecore.domain.thermal.ThermalDownshiftDecision
import com.gamecore.domain.thermal.ThermalDownshiftMachine
import com.gamecore.domain.thermal.ThermalDownshiftState
import com.gamecore.domain.thermal.DownshiftStatus
import com.gamecore.domain.optimization.OptimizationRequest
import com.gamecore.core.model.RefreshRateOutcome
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The live status of a profile's smart features, for the pill/panel chip. Everything here is a plain
 * value the UI reads; the runner produces it from the two state machines each tick.
 */
data class SmartFeatureStatus(
    /** The thermal auto-cooling chip line, or null when the feature is off for this profile. */
    val thermalLine: String? = null,
    /** The current auto-cooling rate, if one is being held. */
    val autoCoolRateHz: Float? = null,
    /** The full-performance chip line, or null when the feature is off. */
    val fullPerformanceLine: String? = null,
) {
    companion object {
        val NONE = SmartFeatureStatus()
    }
}

/**
 * Runs Part B (thermal auto-downshift) and Part D (battery-saver override) for the length of one game
 * session, driving the two pure state machines from the monitoring loop's snapshots.
 *
 * This is the thin Android seam between the tested decision logic and the device: it does no arithmetic
 * of its own. Each tick it hands the current temperature, thermal class, observed rate and battery-saver
 * state to [ThermalDownshiftMachine] / [BatterySaverOverrideMachine] and performs whatever action they
 * return — a refresh-rate change through [RefreshRateController], a `low_power` write through
 * [OptimizationManager] (which records the restore point in the same journal `RefreshRateController`
 * uses). It never decides *whether* to act; the machines do, and their reasons are the truth this reports.
 *
 * Owned by [GamingCoordinator], which calls [onSessionStart] once, [onTick] on each snapshot, and
 * [onSessionEnd] when the game stops. Everything it changes is unwound by the shared restore journal, so
 * a process death mid-session leaves nothing stuck — the journal's rows are replayed at next launch.
 */
@Singleton
class SmartFeatureRunner @Inject constructor(
    private val refreshRate: RefreshRateController,
    private val displayReader: DisplayReader,
    private val optimizations: OptimizationManager,
    private val shizuku: ShizukuManager,
) {
    private var thermalConfig: ThermalDownshiftConfig? = null
    private var thermalState: ThermalDownshiftState = ThermalDownshiftState.initial(0f)
    private var pendingReadbackRate: Float? = null

    private var saverConfig: BatterySaverOverrideConfig = BatterySaverOverrideConfig(enabled = false)
    private var saverState: BatterySaverOverrideState = BatterySaverOverrideState.initial()

    private var lowestRateSeen: Float? = null
    private var downshiftCount: Int = 0

    private val statusState = MutableStateFlow(SmartFeatureStatus.NONE)

    /** The live chip status the pill and panel read. [SmartFeatureStatus.NONE] whenever nothing is active. */
    val status: StateFlow<SmartFeatureStatus> = statusState.asStateFlow()

    /**
     * Starts the features a profile opted into. Reads the display's supported rates once, seeds the
     * thermal machine at the profile's target rate (or the panel's peak), and asks the battery-saver
     * machine whether to disable the saver — performing its answer immediately.
     *
     * Returns the initial status for the chip. Absent Shizuku, or a profile that opted into neither, this
     * is [SmartFeatureStatus.NONE] and nothing is written.
     */
    suspend fun onSessionStart(profile: GameProfile, snapshot: PerformanceSnapshot?): SmartFeatureStatus {
        resetSession()
        val shizukuAvailable = shizuku.isConnected

        // --- thermal (§B) ---
        if (profile.thermalDownshiftEnabled) {
            val rates = displayReader.supportedRates().valueOrNull.orEmpty()
            val floor = profile.thermalFloorRateHz ?: profile.targetRefreshRate ?: rates.minOrNull() ?: 0f
            thermalConfig = ThermalDownshiftConfig(
                enabled = true,
                temperatureLimitDeciCelsius = profile.thermalLimitDeciCelsius,
                thermalStatusFloor = profile.thermalStatusFloor,
                floorRateHz = floor,
                hysteresisDeciCelsius = profile.thermalHysteresisDeciCelsius
                    ?: ThermalDownshiftConfig.DEFAULT_HYSTERESIS_DECI,
                sustainHotMillis = profile.thermalSustainHotMillis
                    ?: ThermalDownshiftConfig.DEFAULT_SUSTAIN_HOT_MILLIS,
                sustainCoolMillis = profile.thermalSustainCoolMillis
                    ?: ThermalDownshiftConfig.DEFAULT_SUSTAIN_COOL_MILLIS,
                minIntervalMillis = profile.thermalMinIntervalMillis
                    ?: ThermalDownshiftConfig.DEFAULT_MIN_INTERVAL_MILLIS,
            )
            val startRate = profile.targetRefreshRate ?: rates.maxOrNull() ?: floor
            thermalState = ThermalDownshiftState.initial(startRate)
        }

        // --- full performance (§D) ---
        if (profile.fullPerformanceEnabled) {
            saverConfig = BatterySaverOverrideConfig(enabled = true)
            val saverOn = snapshot?.battery?.isPowerSaveMode ?: false
            val thermalClass = snapshot?.let { ThermalClassifier.classify(it).level } ?: ThermalClass.OK
            val (next, action) = BatterySaverOverrideMachine.onSessionStart(
                saverState, saverConfig, SystemClock.elapsedRealtime(),
                saverCurrentlyOn = saverOn, shizukuAvailable = shizukuAvailable, thermalClass = thermalClass,
            )
            saverState = next
            performSaver(action)
        }

        return status()
    }

    /**
     * One monitoring tick. Feeds both machines the snapshot and performs any action they return.
     *
     * The thermal read-back is folded in here: if the previous tick asked for a rate, this tick's
     * observed rate tells the machine whether it took, so a device that ignores the write stops after
     * three failures instead of stepping into a wall.
     */
    suspend fun onTick(snapshot: PerformanceSnapshot): SmartFeatureStatus {
        val now = SystemClock.elapsedRealtime()
        val classification = ThermalClassifier.classify(snapshot)
        val thermalClass = classification.level
        val tempDeci = snapshot.primaryTemperatureDeciCelsius.valueOrNull
        val observedRate = snapshot.display.currentRefreshRate.valueOrNull

        thermalConfig?.let { config ->
            // Fold in the previous request's read-back before deciding again.
            pendingReadbackRate?.let { requested ->
                val applied = observedRate != null && kotlin.math.abs(observedRate - requested) <= 1.5f
                thermalState = ThermalDownshiftMachine.recordReadbackResult(thermalState, applied)
                pendingReadbackRate = null
            }
            val rates = displayReader.supportedRates().valueOrNull.orEmpty()
            val (next, decision) = ThermalDownshiftMachine.evaluate(
                thermalState, config, now, tempDeci, thermalClass, observedRate, rates,
            )
            thermalState = next
            if (decision is ThermalDownshiftDecision.SetRate) {
                val outcome = refreshRate.apply(decision.targetHz)
                pendingReadbackRate = decision.targetHz
                downshiftCountFrom(decision.targetHz)
                // A verified failure is fed straight back; an unverified one waits for the next tick's
                // observed rate (the machine's read-back counter).
                if (outcome is RefreshRateOutcome.NotHonoured) {
                    thermalState = ThermalDownshiftMachine.recordReadbackResult(thermalState, applied = false)
                    pendingReadbackRate = null
                }
            }
        }

        if (saverConfig.enabled) {
            val (next, action) = BatterySaverOverrideMachine.onPoll(
                saverState, saverConfig, now,
                saverCurrentlyOn = snapshot.battery.isPowerSaveMode, thermalClass = thermalClass,
            )
            saverState = next
            performSaver(action)
        }

        return status()
    }

    /**
     * Session end. Restores the battery saver to its captured original (the refresh rate is restored by
     * the shared journal, which already holds the peak/min rows the downshift wrote). Returns the summary
     * the recorder stores.
     */
    suspend fun onSessionEnd(): SmartSessionSummary {
        val (_, action) = BatterySaverOverrideMachine.onSessionEnd(saverState)
        performSaver(action)
        val summary = SmartSessionSummary(
            downshiftCount = if (thermalConfig != null) downshiftCount else null,
            lowestRateHz = if (thermalConfig != null) lowestRateSeen else null,
            fullPerformanceOverridden = if (saverConfig.enabled) {
                saverState.overrideApplied || saverStartedOverridden
            } else {
                null
            },
            systemReenabledSaver = if (saverConfig.enabled) saverState.systemReenabled else null,
        )
        resetSession()
        return summary
    }

    // ------------------------------------------------------------------------ internals

    private var saverStartedOverridden = false

    private fun downshiftCountFrom(targetHz: Float) {
        downshiftCount += 1
        lowestRateSeen = lowestRateSeen?.let { minOf(it, targetHz) } ?: targetHz
    }

    private suspend fun performSaver(action: BatterySaverAction) {
        when (action) {
            is BatterySaverAction.CaptureAndDisable -> {
                saverStartedOverridden = true
                optimizations.apply(
                    OptimizationRequest.of(OptimizationAction.DISABLE_BATTERY_SAVER),
                    origin = ChangeOrigin.AUTOMATIC,
                )
            }
            is BatterySaverAction.Restore -> {
                val request = if (action.toOn) {
                    OptimizationRequest.of(OptimizationAction.ENABLE_BATTERY_SAVER)
                } else {
                    OptimizationRequest.of(OptimizationAction.DISABLE_BATTERY_SAVER)
                }
                optimizations.apply(request, origin = ChangeOrigin.AUTOMATIC)
            }
            BatterySaverAction.DoNothing -> Unit
        }
    }

    private fun status(): SmartFeatureStatus {
        val thermalLine = thermalConfig?.let {
            when (thermalState.status) {
                DownshiftStatus.COOLING -> "Auto-cooling: ${fmt(thermalState.currentRateHz)} Hz"
                else -> thermalState.status.label
            }
        }
        val fullLine = if (saverConfig.enabled) saverStatusLine() else null
        val next = SmartFeatureStatus(
            thermalLine = thermalLine,
            autoCoolRateHz = thermalConfig?.let { thermalState.currentRateHz },
            fullPerformanceLine = fullLine,
        )
        statusState.value = next
        return next
    }

    private fun saverStatusLine(): String = when (saverState.status) {
        OverrideStatus.OVERRIDDEN -> OverrideStatus.OVERRIDDEN.label
        OverrideStatus.PAUSED_SYSTEM_REENABLED -> OverrideStatus.PAUSED_SYSTEM_REENABLED.label
        OverrideStatus.BLOCKED_THERMAL -> OverrideStatus.BLOCKED_THERMAL.label
        OverrideStatus.UNAVAILABLE -> OverrideStatus.UNAVAILABLE.label
        OverrideStatus.READBACK_MISMATCH -> OverrideStatus.READBACK_MISMATCH.label
        OverrideStatus.INACTIVE -> OverrideStatus.INACTIVE.label
    }

    private fun resetSession() {
        thermalConfig = null
        thermalState = ThermalDownshiftState.initial(0f)
        pendingReadbackRate = null
        saverConfig = BatterySaverOverrideConfig(enabled = false)
        saverState = BatterySaverOverrideState.initial()
        saverStartedOverridden = false
        lowestRateSeen = null
        downshiftCount = 0
        statusState.value = SmartFeatureStatus.NONE
    }

    private fun fmt(rate: Float): String =
        if (rate % 1f == 0f) rate.toInt().toString() else rate.toString()
}

/** The smart-feature figures a finished session stores (§B6/§D8). Null fields mean the feature was off. */
data class SmartSessionSummary(
    val downshiftCount: Int? = null,
    val lowestRateHz: Float? = null,
    val fullPerformanceOverridden: Boolean? = null,
    val systemReenabledSaver: Boolean? = null,
)
