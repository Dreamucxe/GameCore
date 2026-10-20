package com.gamecore.themeengine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything the Theme Engine currently believes, in one immutable value.
 *
 * [resolution] is the **target**: the mode and colour the rules in [AuroraVisualEngine] select from the
 * current readings, exact and unsmoothed. Every figure the calibration screen prints comes from here,
 * so the table says `rgb(148, 90, 180)` and `1.92x` when the reference says it should.
 *
 * The vector the canvas paints is deliberately *not* in this value. It changes on every animation frame,
 * and a screen that collected it here would recompose its whole table sixty times a second to move one
 * colour. It lives in its own flow — [ThemeEnginePlugin.renderVector] — which only the canvas reads.
 */
data class ThemeEngineState(
    val isActive: Boolean = false,
    val preset: CalibrationPreset = AuroraDefaults.preset(),
    val metrics: ThemeEngineMetrics = ThemeEngineMetrics.EMPTY,
    val resolution: AuroraResolution = AuroraVisualEngine.resolve(ThemeEngineMetrics.EMPTY, AuroraDefaults.preset()),
)

/**
 * The Theme Engine's lifecycle hook, and the only place its state lives.
 *
 * It is a plain [Singleton] rather than something Compose owns, because a theme engine that exists only
 * while its screen is composed is not an engine — the backdrop has to survive a rotation, and the
 * calibration screen has to be able to show the same vector the app itself is painting. The screen
 * starts it with the readings it wants it to follow and stops it when it goes away; between those two
 * calls the plugin owns its own collectors, its own clock and its own interpolation.
 *
 * The metrics are a [Flow] rather than a getter on purpose. The caller decides what "live" means — the
 * calibration screen feeds live readings until a slider is touched and the sliders' values after that,
 * and the plugin never has to know which of the two it is being handed. It resolves, smooths and
 * publishes; it reads nothing itself.
 *
 * There are two loops, and they run at different rates for different jobs:
 *
 *  - the **metrics** collector runs at whatever rate the caller's stream emits (about once a second).
 *    Each reading selects a new target vector — a step function of the readings, exact, and the thing
 *    the calibration table reports.
 *  - the **render** loop runs at roughly a frame and walks the painted vector toward that target, so a
 *    step between readings becomes a fluid second of travel rather than a colour that blinks once a
 *    second. This is where the "interpolation vectors" the engine maintains actually smooth anything.
 *
 * Everything here is a [MutableStateFlow.update], so a preset change racing a metrics tick cannot lose
 * either one: last writer wins per field, and neither can publish a state built from a stale copy of
 * the other's fields.
 */
@Singleton
class ThemeEnginePlugin @Inject constructor() {

    private val mutableState = MutableStateFlow(ThemeEngineState())

    val state: StateFlow<ThemeEngineState> = mutableState.asStateFlow()

    private val mutableRender = MutableStateFlow(AuroraRenderVector.of(mutableState.value.resolution.vector))

    private val mutablePainted = MutableStateFlow(mutableState.value.resolution.vector)

    /**
     * Readings that stand in for the live ones while a user drags the calibration sliders.
     *
     * Held by the plugin rather than by the stream, because the engine is now started once for the whole
     * app: the shell feeds live readings and the calibration screen asks for a stand-in on the *same*
     * collector. Null means "the device's own numbers", which is the state the app spends almost all of
     * its time in.
     */
    private val metricOverride = MutableStateFlow<ThemeEngineMetrics?>(null)

    /** The smoothed vector the canvas paints. Changes while a transition runs, then goes quiet. */
    val renderVector: StateFlow<AuroraVector> = mutablePainted.asStateFlow()

    private var metricsJob: Job? = null

    private var renderJob: Job? = null

    /** The most recent reading the caller's stream produced, so clearing an override can restore it. */
    private var lastLive: ThemeEngineMetrics? = null

    /**
     * Stands in for the live readings, or clears the stand-in with null.
     *
     * Applied on the spot rather than on the next live emission: a slider the user is dragging must move
     * the backdrop under their finger, and the live stream only speaks about once a second.
     */
    fun setMetricOverride(metrics: ThemeEngineMetrics?) {
        metricOverride.value = metrics
        val reading = metrics ?: lastLive ?: return
        ingest(reading)
    }

    /**
     * Attaches the engine to a stream of readings.
     *
     * Re-entrant by design: the screen may be re-entered, or its preset may be reloaded, and starting
     * twice must not leave two collectors advancing one state. The previous jobs are cancelled first.
     *
     * The painted vector is snapped to the first target rather than blended from the previous session's
     * colour: reopening the screen should show the backdrop the readings imply, not a one-second fade
     * from a colour the device was wearing ten minutes ago.
     */
    fun start(
        scope: CoroutineScope,
        metrics: Flow<ThemeEngineMetrics>,
        preset: CalibrationPreset,
    ) {
        metricsJob?.cancel()
        renderJob?.cancel()

        val initialPreset = preset.normalised()
        val initialResolution = AuroraVisualEngine.resolve(mutableState.value.metrics, initialPreset)
        mutableState.update {
            it.copy(
                isActive = true,
                preset = initialPreset,
                resolution = initialResolution,
            )
        }
        mutableRender.value = AuroraRenderVector.of(initialResolution.vector)
        mutablePainted.value = initialResolution.vector

        metricsJob = scope.launch {
            metrics.collect { reading ->
                lastLive = reading
                ingest(metricOverride.value ?: reading)
            }
        }

        // A stand-in already in force (the user was on the calibration screen before the shell restarted
        // the engine) applies immediately instead of waiting for the next live reading to arrive.
        metricOverride.value?.let { ingest(it) }

        renderJob = scope.launch {
            var lastNanos = System.nanoTime()
            while (true) {
                delay(FRAME_MILLIS)
                val now = System.nanoTime()
                val elapsedMillis = (now - lastNanos) / NANOS_PER_MILLI
                lastNanos = now
                tick(elapsedMillis)
            }
        }
    }

    /**
     * Detaches from the stream.
     *
     * The last frame stays in the flows so a returning screen can redraw it, but the render loop stops:
     * an inactive engine must not hold a frame-rate timer open behind a screen the user has left.
     */
    fun stop() {
        metricsJob?.cancel()
        metricsJob = null
        renderJob?.cancel()
        renderJob = null
        mutableState.update { it.copy(isActive = false) }
    }

    /**
     * Changes the calibration in force without interrupting the stream.
     *
     * The target jumps; the painted vector keeps blending toward it, which is what makes changing
     * presets a transition rather than a cut.
     */
    fun setPreset(preset: CalibrationPreset) {
        val normalised = preset.normalised()
        mutableState.update { current ->
            current.copy(
                preset = normalised,
                resolution = AuroraVisualEngine.resolve(current.metrics, normalised),
            )
        }
    }

    /**
     * One reading.
     *
     * A reading is a step: whatever arrived before it is no longer true, so the target is replaced
     * outright and the render loop spreads the difference over the frames that follow. The reported
     * state changes on this call; the painted vector does not, which is exactly the separation the
     * calibration table relies on.
     */
    internal fun ingest(reading: ThemeEngineMetrics) {
        mutableState.update { current ->
            val normalised = reading.normalised()
            current.copy(
                metrics = normalised,
                resolution = AuroraVisualEngine.resolve(normalised, current.preset),
            )
        }
    }

    /**
     * One animation frame: moves the painted vector one step toward the current target.
     *
     * Pure with respect to time — the same delta always produces the same step — so a test can drive
     * a transition to convergence without a frame clock.
     */
    internal fun tick(dtMillis: Long) {
        val target = mutableState.value.resolution.vector
        val next = blendVector(mutableRender.value, target, dtMillis)
        if (next != mutableRender.value) {
            mutableRender.value = next
            val painted = next.painted()
            if (painted != mutablePainted.value) mutablePainted.value = painted
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L

        /** Roughly sixty frames a second; the render loop does not need a display-synced clock. */
        const val FRAME_MILLIS = 16L
    }
}
