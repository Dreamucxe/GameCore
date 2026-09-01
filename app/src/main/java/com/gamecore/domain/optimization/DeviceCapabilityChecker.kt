package com.gamecore.domain.optimization

import com.gamecore.core.common.ApplicationScope
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.common.isAvailable
import com.gamecore.core.common.valueOrNull
import com.gamecore.core.model.CapabilityStatus
import com.gamecore.core.model.DetectionAvailability
import com.gamecore.core.model.DetectionRemedy
import com.gamecore.core.model.DeviceCapabilities
import com.gamecore.core.model.FrameRateCapability
import com.gamecore.core.model.OptimizationAction
import com.gamecore.core.permissions.PermissionChecker
import com.gamecore.core.shizuku.ShizukuManager
import com.gamecore.core.system.CompositeMetricsReader
import com.gamecore.core.system.DisplayReader
import com.gamecore.core.system.ForegroundAppWatcher
import com.gamecore.core.system.FrameRateProbe
import com.gamecore.core.system.ProcFsReader
import com.gamecore.core.system.RefreshRateController
import com.gamecore.core.system.ScreenCaptureController
import com.gamecore.core.system.TorchControls
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What this device will actually let GameCore do — §29's checker.
 *
 * Every field of the [DeviceCapabilities] this produces is the result of trying the cheap version of
 * the operation: reading the panel's mode list, asking [RefreshRateController] which mechanism it
 * would use, checking an app-op, asking the shell whether it is alive, parsing one line of
 * `/proc/stat`. Nothing here consults `Build.MANUFACTURER` and nothing infers one capability from
 * another. §24B's MediaTek note is why: a phone that advertises a 120 Hz mode and silently refuses to
 * leave 60 looks exactly like a working one until something reads the rate back, and a checker built
 * on model names would confidently get it wrong.
 *
 * Two deliberate abstentions in the reads it makes:
 *
 *  * **CPU utilisation is not sampled here.** It is a delta between two `/proc/stat` reads, and
 *    [CompositeMetricsReader.readCpu] advances the sampler's baseline. A capability check that called
 *    it would shorten the interval [com.gamecore.domain.monitoring.PerformanceMonitor]'s next figure
 *    covers, so this class reads the raw counters through [ProcFsReader] instead — same file, same
 *    permission question, no cursor to disturb.
 *  * **Frame rate is asked without a package.** [FrameRateProbe.capabilityFor] answers per game —
 *    whether a title draws through HWUI is a fact about its engine, not about the phone — so the
 *    device-wide question is [FrameRateProbe.deviceExposesFrameTiming] instead: whether the platform
 *    exposes any frame timing at all. Answering it with a game's name would produce a "device
 *    capability" that changes when the user switches games.
 *
 * Cached, because a dashboard, a games screen and a Shizuku screen opening in sequence should not each
 * pay for a settings read and a shell round-trip. [invalidate] is called when a permission is granted
 * or Shizuku connects — the two events that make the cached answer wrong — and concurrent callers
 * share one refresh for the same reason [com.gamecore.domain.monitoring.NetworkMonitor] does: two
 * checks racing produce two answers that disagree, and the later one wins by accident.
 */
@Singleton
class DeviceCapabilityChecker @Inject constructor(
    private val optimizations: OptimizationManager,
    private val metrics: CompositeMetricsReader,
    private val procFs: ProcFsReader,
    private val displayReader: DisplayReader,
    private val refreshRate: RefreshRateController,
    private val frameRateProbe: FrameRateProbe,
    private val foreground: ForegroundAppWatcher,
    private val torch: TorchControls,
    private val screenCapture: ScreenCaptureController,
    private val permissions: PermissionChecker,
    private val shizuku: ShizukuManager,
    @IoDispatcher private val io: CoroutineDispatcher,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val state = MutableStateFlow(DeviceCapabilities.UNKNOWN)
    private val mutex = Mutex()

    @Volatile
    private var inFlight: Deferred<DeviceCapabilities>? = null

    /**
     * Whether the published answer is known to be out of date.
     *
     * Separate from [DeviceCapabilities.checkedAtMillis] rather than expressed by zeroing it: that
     * field means "when this was established", and a snapshot carrying real values while claiming it
     * was never checked would be a lie told to make the cache logic shorter.
     */
    @Volatile
    private var stale = true

    /**
     * The last completed check.
     *
     * Starts at [DeviceCapabilities.UNKNOWN], which claims nothing is supported. A screen bound to
     * this shows controls appearing as the check establishes them, rather than showing controls that
     * vanish when it turns out they never worked.
     */
    val capabilities: StateFlow<DeviceCapabilities> = state.asStateFlow()

    /**
     * The cached answer, re-established when it is stale or older than [maxAgeMillis].
     *
     * What a screen calls when it opens. The default age is generous because none of these facts
     * changes on its own: a panel does not gain a refresh rate and a permission does not appear
     * without the user going to Settings, and the two events that *do* change an answer both call
     * [invalidate].
     */
    suspend fun current(
        maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
        nowMillis: Long = System.currentTimeMillis(),
    ): DeviceCapabilities {
        val cached = state.value
        val fresh = !stale &&
            cached.checkedAtMillis > 0L &&
            nowMillis - cached.checkedAtMillis in 0..maxAgeMillis
        return if (fresh) cached else refresh()
    }

    /**
     * Establishes every capability now, and publishes the result to [capabilities].
     *
     * Callers that arrive while a check is running wait for that one rather than starting a second.
     * Two concurrent checks would produce two answers assembled from reads taken at different
     * moments, and the one that happened to finish last would win — the same race
     * [com.gamecore.domain.monitoring.NetworkMonitor] avoids for the same reason.
     */
    suspend fun refresh(): DeviceCapabilities {
        val job = mutex.withLock {
            inFlight ?: scope
                .async { check() }
                .also { started ->
                    inFlight = started
                    started.invokeOnCompletion { inFlight = null }
                }
        }
        return job.await()
    }

    /**
     * Marks the cached answer as no longer trustworthy and re-establishes it in the background.
     *
     * Called after a permission grant or a Shizuku connection. The published snapshot is deliberately
     * *not* reset to [DeviceCapabilities.UNKNOWN] first: a screen bound to [capabilities] would show
     * every control disappearing and reappearing, which reads as a bug rather than as a refresh. The
     * previous answer stands — it was true a moment ago — until a truer one replaces it.
     */
    fun invalidate() {
        stale = true
        scope.launch { refresh() }
    }

    // ------------------------------------------------------------------------- the check

    /**
     * Every probe, run once, assembled into one snapshot.
     *
     * The groups run concurrently. That is safe rather than clever: the elevated shell serialises its
     * own execution behind a lock, so the shell-backed probes queue instead of interleaving, and the
     * rest — `/proc` reads, the display's mode list, an app-op check, a camera-manager query — touch
     * nothing shared. Sequentially this is eleven optimizer statuses plus seven reads on a cold
     * shell, which is long enough for the dashboard to render a screen full of "not supported".
     *
     * Nothing here throws: every probe returns an [com.gamecore.core.common.Observed], an outcome
     * type or a boolean, so a device that refuses one read produces one negative field rather than
     * an empty snapshot.
     */
    private suspend fun check(): DeviceCapabilities = withContext(io) {
        val shizukuState = shizuku.refresh()
        val shellLive = shizuku.isConnected

        coroutineScope {
            val statuses = async { optimizations.statuses(CONTROL_ACTIONS) }
            val mechanism = async { refreshRate.mechanism() }
            val rates = async { displayReader.supportedRates().valueOrNull.orEmpty() }
            val unreliableChipset = async { refreshRate.isKnownUnreliableChipset() }
            val thermal = async { metrics.readThermal() }
            val frameTiming = async { frameRateCapability(shellLive) }
            val detection = async { foreground.availability() }
            val torchAvailable = async { torch.isAvailable() }
            val rootState = async { shizuku.rootState() }

            // Raw counters, not CompositeMetricsReader.readCpu(): see the class KDoc.
            val cpuTimesReadable = async { procFs.readSystemCpuTimes().isAvailable }
            val frequenciesReadable = async {
                procFs.readCoreFrequencies(Runtime.getRuntime().availableProcessors()).isAvailable
            }

            val control = statuses.await()
            val thermalReading = thermal.await()
            val supportedRates = rates.await()

            DeviceCapabilities(
                shizuku = shizukuState,
                root = rootState.await(),
                effectiveAccess = shizuku.accessLevel,
                refreshRateMechanism = mechanism.await(),
                supportedRefreshRates = supportedRates,
                hasVariableRefreshRate = supportedRates.size >= 2,
                isKnownUnreliableRefreshChipset = unreliableChipset.await(),
                brightnessControl = control.statusOf(OptimizationAction.SET_BRIGHTNESS),
                rotationControl = control.statusOf(OptimizationAction.LOCK_ROTATION),
                screenTimeoutControl = control.statusOf(OptimizationAction.EXTEND_SCREEN_TIMEOUT),
                cpuUsageReadable = cpuTimesReadable.await(),
                perCoreFrequenciesReadable = frequenciesReadable.await(),
                thermalStatusSupported = thermalReading.statusSupported,
                temperatureReadable = thermalReading.cpuTemperatureDeciCelsius.isAvailable,
                frameRate = frameTiming.await(),
                gameDetection = detectionStatus(detection.await()),
                overlay = granted(permissions.hasOverlayPermission()),
                doNotDisturbControl = control.statusOf(OptimizationAction.ENABLE_DO_NOT_DISTURB),
                volumeControl = control.statusOf(OptimizationAction.SET_MEDIA_VOLUME),
                torch = supported(torchAvailable.await()),
                screenCapture = supported(screenCapture.isSupported()),
                batterySaverControl = control.statusOf(OptimizationAction.ENABLE_BATTERY_SAVER),
                animationScaleControl = control.statusOf(OptimizationAction.DISABLE_ANIMATIONS),
                checkedAtMillis = System.currentTimeMillis(),
            )
        }.also { established ->
            state.value = established
            stale = false
        }
    }

    // ------------------------------------------------------------------------ translation

    /**
     * The optimizer's answer for one action, pessimistic when it is missing.
     *
     * A missing key would be a bug in [CONTROL_ACTIONS] rather than a device limitation, and
     * [CapabilityStatus.UNSUPPORTED] is the reading that hides a control instead of offering one that
     * cannot work.
     */
    private fun Map<OptimizationAction, CapabilityStatus>.statusOf(
        action: OptimizationAction,
    ): CapabilityStatus = this[action] ?: CapabilityStatus.UNSUPPORTED

    /**
     * Whether frame timing exists on this device at all, said in the only terms that are true.
     *
     * There is no honest device-wide "yes" here. A per-game read is the only route to a real frame
     * rate, so the best case is still [FrameRateCapability.NO_GAME_SELECTED] — "ask again with a
     * game" — and the capability screen prints that rather than a number. §24B's FPS paragraph is
     * this method: the temptation is to return something the UI can turn into a figure, and there is
     * nothing to return.
     */
    private suspend fun frameRateCapability(shellLive: Boolean): FrameRateCapability = when {
        !shellLive -> FrameRateCapability.Unavailable(FrameRateCapability.NEEDS_SHIZUKU)
        frameRateProbe.deviceExposesFrameTiming() ->
            FrameRateCapability.Unavailable(FrameRateCapability.NO_GAME_SELECTED)
        // The whole-device dump is empty on this build, which several vendors ship. Not a final no:
        // the per-game route runs off a different dump and is still tried when a game starts.
        else -> FrameRateCapability.Unavailable(NO_DEVICE_FRAME_TIMING)
    }

    /**
     * Foreground detection, whose two blockers lead to two different buttons.
     *
     * [DetectionRemedy] already distinguishes them, which is why this is a mapping rather than a
     * judgement: usage access is a Settings toggle, and the Shizuku path is a setup screen.
     */
    private fun detectionStatus(availability: DetectionAvailability): CapabilityStatus =
        when (availability) {
            is DetectionAvailability.Available -> CapabilityStatus.AVAILABLE
            is DetectionAvailability.Unavailable -> when (availability.remedy) {
                DetectionRemedy.GRANT_USAGE_ACCESS -> CapabilityStatus.REQUIRES_PERMISSION
                DetectionRemedy.START_SHIZUKU -> CapabilityStatus.REQUIRES_SHIZUKU
            }
        }

    /** A permission the user can grant in Settings, so the "no" is actionable. */
    private fun granted(held: Boolean): CapabilityStatus =
        if (held) CapabilityStatus.AVAILABLE else CapabilityStatus.REQUIRES_PERMISSION

    /**
     * Hardware or platform presence, where "no" is final.
     *
     * A phone with no flash unit and a build with no `MediaProjection` service are both
     * [CapabilityStatus.UNSUPPORTED] rather than [CapabilityStatus.REQUIRES_PERMISSION]: there is
     * nothing to grant, and a button implying otherwise would send the user to a screen that cannot
     * help. Screen capture is deliberately measured as service presence and not as held consent —
     * `MediaProjection` consent is per-recording by design, so treating its absence as a missing
     * capability would show the feature as unavailable right up until the moment it is used.
     */
    private fun supported(present: Boolean): CapabilityStatus =
        if (present) CapabilityStatus.AVAILABLE else CapabilityStatus.UNSUPPORTED

    companion object {
        /**
         * Five minutes. None of these facts changes without something calling [invalidate], so the
         * age limit is a backstop for the events GameCore does not observe — a permission revoked
         * from Settings while the app sat in the background is the common one.
         */
        const val DEFAULT_MAX_AGE_MILLIS = 5 * 60 * 1000L

        private const val NO_DEVICE_FRAME_TIMING =
            "This Android build reports no frame timing for the device as a whole. GameCore still " +
                "checks per game when one starts, since a few builds expose it there and nowhere else."

        /**
         * The seven actions [DeviceCapabilities] carries a status for.
         *
         * Not [OptimizationAction.entries]: each status can cost a settings read and a shell probe,
         * and the four omitted actions would add nothing. The refresh-rate trio is answered by
         * [RefreshRateController.mechanism] instead, and `DISABLE_BATTERY_SAVER` writes the same key
         * as `ENABLE_BATTERY_SAVER`, so its status is the same by construction.
         */
        private val CONTROL_ACTIONS = listOf(
            OptimizationAction.SET_BRIGHTNESS,
            OptimizationAction.LOCK_ROTATION,
            OptimizationAction.EXTEND_SCREEN_TIMEOUT,
            OptimizationAction.ENABLE_DO_NOT_DISTURB,
            OptimizationAction.SET_MEDIA_VOLUME,
            OptimizationAction.ENABLE_BATTERY_SAVER,
            OptimizationAction.DISABLE_ANIMATIONS,
        )
    }
}
