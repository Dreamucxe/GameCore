package com.gamecore.ui.performance

import com.gamecore.core.common.Observed
import com.gamecore.core.model.AppSettings
import com.gamecore.core.model.CapabilityStatus
import com.gamecore.core.model.DeviceCapabilities
import com.gamecore.core.model.FrameRateCapability
import com.gamecore.core.model.OptimizationAction
import com.gamecore.core.model.OptimizationResult
import com.gamecore.core.model.RefreshRateMechanism
import com.gamecore.domain.monitoring.StabilityReport
import com.gamecore.ui.components.PENDING
import com.gamecore.ui.components.Readout
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.readoutOf

/**
 * The performance screen's settled state: what this device can do, and what GameCore has done to it.
 *
 * Separate from [PerformanceLive] because the two have opposite lifetimes. This is assembled from flows
 * that cost nothing to observe and would flash back to "unknown" if they were dropped on a rotation, so
 * it is held hot; the live figures below start and stop the sampler and are collected by the screen.
 */
data class PerformanceUiState(
    val capabilities: DeviceCapabilities = DeviceCapabilities.UNKNOWN,
    val frameRate: FrameRateCapability =
        FrameRateCapability.Unavailable(FrameRateCapability.NO_GAME_SELECTED),
    /** The game whose frame timing is being watched, for the frame-rate card's subtitle. */
    val watchedGameLabel: String = "",
    val stability: Observed<StabilityReport> =
        Observed.awaitingSample("No stability measurement has been taken yet."),
    val isMeasuringStability: Boolean = false,
    /** What each optimization can do on this device right now, asked of the engine rather than assumed. */
    val statuses: Map<OptimizationAction, CapabilityStatus> = emptyMap(),
    /** The outcome of the last thing the user applied from this screen, one entry per change. */
    val results: List<OptimizationResult> = emptyList(),
    val isApplying: Boolean = false,
    val pendingRestores: Int = 0,
    val isRestoring: Boolean = false,
    val sampleIntervalMillis: Long = AppSettings.DEFAULT_SAMPLE_INTERVAL,
    val measureLatency: Boolean = true,
    val backgroundMonitoring: Boolean = false,
    val message: String? = null,
) {

    val supportedRates: List<Float> get() = capabilities.supportedRefreshRates

    val refreshMechanism: RefreshRateMechanism get() = capabilities.refreshRateMechanism

    /**
     * Whether the rate chips do anything.
     *
     * A panel with one rate and a device where every mechanism is denied both end up here, and the card
     * explains which of the two it is rather than offering chips that fail on press.
     */
    val canChangeRefreshRate: Boolean
        get() = refreshMechanism != RefreshRateMechanism.NONE &&
            refreshMechanism != RefreshRateMechanism.WINDOW_PREFERENCE &&
            supportedRates.size > 1

    /** §24B: the chipsets where the standard API is accepted and quietly ignored. */
    val warnsAboutChipset: Boolean
        get() = capabilities.isKnownUnreliableRefreshChipset && !capabilities.hasElevatedAccess

    /** The actions a user can apply from this screen, in the order the card lists them. */
    val deviceActions: List<OptimizationAction>
        get() = OptimizationAction.entries.filter { it.isProfileAction && it !in PROFILE_ONLY_ACTIONS }

    fun statusOf(action: OptimizationAction): CapabilityStatus =
        statuses[action] ?: CapabilityStatus.UNSUPPORTED
}

/**
 * The changes this screen does not offer, because they need a value the screen has no field for.
 *
 * Brightness, volume, rotation and screen timeout are all "set it to *what*" — they belong to a game
 * profile, which has the sliders, and to the tools screen, which has the live controls. Offering them
 * here with an invented default would change the user's device to a number nobody chose.
 *
 * Colour correction is the same argument with a stronger case: it needs a whole preset, it has its
 * own screen with fourteen values and a live preview, and there is no sensible "apply some colour"
 * button. This screen would only be able to guess.
 *
 * The display size is the strongest case of the three. It needs a width and a height, the presets
 * that make it one tap are computed from the panel and live in the panel's own tile, and the override
 * outlives a reboot — so a button here that guessed a size would leave the device stretched after a
 * restart on behalf of a tap the user could not have meant.
 *
 * The core-affinity presets are excluded for a different reason again: they need a *game*. There is no
 * device-wide version of them — the thing they change is one running process — so a button on a screen
 * that is not about any particular game would have nothing to point at.
 */
private val PROFILE_ONLY_ACTIONS = setOf(
    OptimizationAction.SET_BRIGHTNESS,
    OptimizationAction.SET_MEDIA_VOLUME,
    OptimizationAction.LOCK_ROTATION,
    OptimizationAction.EXTEND_SCREEN_TIMEOUT,
    OptimizationAction.ENABLE_DO_NOT_DISTURB,
    OptimizationAction.APPLY_COLOR_CORRECTION,
    OptimizationAction.SET_DISPLAY_SIZE,
    OptimizationAction.SET_CPU_AFFINITY,
)

/**
 * One sample, already turned into strings and series.
 *
 * §24A.2 in its most literal form: a [com.gamecore.core.model.PerformanceSnapshot] carries per-core
 * frequencies, a sensor list, byte counters and a battery voltage, and this screen consumes about
 * twenty formatted figures. The conversion happens once in the ViewModel, so no composable holds the
 * whole device and the honesty rules are testable as pure functions.
 *
 * The series are plain float lists because that is what [com.gamecore.ui.components.LineGraph] draws.
 * They come from [com.gamecore.core.model.MetricHistory], which drops absent readings rather
 * than substituting zeroes — a graph with a gap in it is honest and a graph that dips to zero when a
 * sensor stops answering is not.
 */
data class PerformanceLive(
    val tiles: List<Readout>,
    val cpuSeries: List<Float> = emptyList(),
    val memorySeries: List<Float> = emptyList(),
    val temperatureSeries: List<Float> = emptyList(),
    val frameRateSeries: List<Float> = emptyList(),
    val cpuRows: List<Readout> = emptyList(),
    /** One row per core, from whichever per-core source the device exposes. Empty when neither does. */
    val coreRows: List<Readout> = emptyList(),
    val batteryRows: List<Readout> = emptyList(),
    val thermalRows: List<Readout> = emptyList(),
    val networkRows: List<Readout> = emptyList(),
    val displayRows: List<Readout> = emptyList(),
    /** The measured frame figures, present only when a real per-frame source produced them. */
    val frameRateRows: List<Readout> = emptyList(),
    /**
     * Why the last frame-rate read produced nothing, when the capability says it should have.
     *
     * A source can exist and still return an empty window — a game that stopped drawing, a dump the
     * vendor truncated — and that is a different sentence from "this device cannot measure it".
     */
    val frameRateNote: String? = null,
    /** Set only while the platform is actually throttling. */
    val thermalNote: String? = null,
    /** For highlighting the rate the panel is running now among the chips it offers. */
    val currentRefreshRate: Float? = null,
    /** Passed to the stability burst, which refuses to probe a network that is not there. */
    val isConnected: Boolean = false,
    val hasSample: Boolean = false,
) {
    companion object {
        /**
         * The screen before the first sample: the right labels with `…` where the figures go.
         *
         * Same shape as a real one, so the page settles once instead of growing into place. The graphs
         * draw their own "collecting samples" message from an empty series, which is why there is
         * nothing here for them.
         */
        val AWAITING = PerformanceLive(
            tiles = PerformanceLabels.TILES.map {
                readoutOf(label = it, value = PENDING, tone = Tone.Muted)
            },
        )
    }
}

/** Tile labels, in the order the grid lays them out. */
internal object PerformanceLabels {
    const val CPU = "CPU"
    const val MEMORY = "RAM"
    const val TEMPERATURE = "Temperature"
    const val BATTERY = "Battery"

    val TILES = listOf(CPU, MEMORY, TEMPERATURE, BATTERY)
}
