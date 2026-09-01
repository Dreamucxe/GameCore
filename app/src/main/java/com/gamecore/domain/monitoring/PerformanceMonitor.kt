package com.gamecore.domain.monitoring

import com.gamecore.core.common.ApplicationScope
import com.gamecore.core.common.DataSource
import com.gamecore.core.common.Observed
import com.gamecore.core.common.Precision
import com.gamecore.core.model.AppSettings
import com.gamecore.core.model.FrameRateCapability
import com.gamecore.core.model.FrameRateSample
import com.gamecore.core.model.HistoryPoint
import com.gamecore.core.model.LatencyProbe
import com.gamecore.core.model.MetricHistory
import com.gamecore.core.model.PerformanceSnapshot
import com.gamecore.core.model.StorageReading
import com.gamecore.core.system.CompositeMetricsReader
import com.gamecore.core.system.DisplayReader
import com.gamecore.core.system.FrameRateProbe
import com.gamecore.core.system.LatencyProber
import com.gamecore.core.system.NetworkReader
import com.gamecore.data.preferences.SecurePreferenceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The live metrics pipeline: one sampling loop, shared by everything that watches.
 *
 * There is exactly one of these and exactly one loop. The dashboard, the performance screen, the
 * floating pill and the session recorder all collect [snapshots], and none of them causes a second
 * read of `/proc/stat` — which matters more than it sounds, because CPU utilisation is a *delta*
 * between two readings and two independent samplers reading the same file at overlapping intervals
 * produce two different, both-wrong answers.
 *
 * The loop runs only while something is collecting. `SharingStarted.WhileSubscribed` with a grace
 * period is what §26 asks for — services and screens that stop needing metrics stop the sampling —
 * and the grace period exists so that rotating a screen or navigating between two tabs that both show
 * stats does not tear down and restart the loop, discarding the sampler's previous reading and making
 * the first tick after every navigation say "waiting for a second sample".
 *
 * Not everything is read at the sampling interval:
 *
 *  - CPU, memory, battery, thermal, network and display are read every tick. They are the cheap ones
 *    — `/proc` text, a cached broadcast, and in-process manager lookups — and they are what changes.
 *    The display is in that list deliberately: the current refresh rate is the one figure a user
 *    pinning a rate is watching, so a cached value would defeat the feature it exists to show.
 *  - Storage is refreshed every [STORAGE_EVERY_TICKS] ticks. `StatFs` is a filesystem call and free
 *    space does not move perceptibly during a game.
 *  - Latency is probed every [LATENCY_EVERY_TICKS] ticks, and only when the user has it switched on
 *    and the device is actually connected. Each probe is a real TCP handshake; one every two seconds
 *    for a two-hour session is 3,600 connections to somebody else's server, which is rude and
 *    pointless.
 *  - Frame timing is only sampled when a game is [watch]ed *and* the probe found a real signal for it.
 *    §24B is explicit that this must not become an estimate, so when there is no signal the field is
 *    [Observed.Restricted] with the reason and no number is produced from anywhere.
 */
@Singleton
class PerformanceMonitor @Inject constructor(
    private val metrics: CompositeMetricsReader,
    private val displayReader: DisplayReader,
    private val networkReader: NetworkReader,
    private val frameRateProbe: FrameRateProbe,
    private val latencyProber: LatencyProber,
    private val preferences: SecurePreferenceStore,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val historyState = MutableStateFlow(MetricHistory.EMPTY)
    private val trendState = MutableStateFlow(BatteryTrend.INITIAL)
    private val capabilityState = MutableStateFlow<FrameRateCapability>(
        FrameRateCapability.Unavailable(FrameRateCapability.NO_GAME_SELECTED),
    )

    /** The rolling window for the graphs. Cleared when the loop stops, not when a screen closes. */
    val history: StateFlow<MetricHistory> = historyState.asStateFlow()

    /**
     * Battery level over the window this loop has been running, for §21's live drain rate.
     *
     * Deliberately *not* cleared by [clearHistory]. The graph window belongs to a session; this
     * belongs to the device, and a five-minute floor is unreachable for anything that restarts every
     * time a screen closes. [BatteryTrend]'s own gap rule handles the discontinuity that comes with
     * outliving the loop.
     */
    val batteryTrend: StateFlow<BatteryTrend> = trendState.asStateFlow()

    /** What the frame-rate field of a snapshot can possibly be, and why. */
    val frameRateCapability: StateFlow<FrameRateCapability> = capabilityState.asStateFlow()

    @Volatile
    private var watched: String? = null

    @Volatile
    private var cachedStorage: StorageReading? = null

    @Volatile
    private var cachedLatency: Observed<LatencyProbe> =
        Observed.awaitingSample("Waiting for the first latency probe.")

    /**
     * The shared stream. Null until the first sample completes.
     *
     * Null rather than a snapshot full of "unavailable": a screen that has been open for 40
     * milliseconds and one that has established the device reports nothing are different states, and
     * the first should show a placeholder rather than a page of dashes.
     */
    val snapshots: StateFlow<PerformanceSnapshot?> = sampleStream()
        .onCompletion { onLoopStopped() }
        .stateIn(scope, SharingStarted.WhileSubscribed(IDLE_GRACE_MILLIS), null)

    /**
     * Names the game whose frame timing should be sampled, or null for none.
     *
     * Runs the capability probe once here rather than per tick: it is a `dumpsys` round trip, and
     * whether a given game draws through HWUI is a fact about that game, not about this second.
     */
    suspend fun watch(packageName: String?) {
        if (watched == packageName) return
        watched = packageName
        frameRateProbe.reset()
        capabilityState.value = frameRateProbe.capabilityFor(packageName)
    }

    /**
     * One sample, outside the shared loop.
     *
     * For the places that need a reading without subscribing to a stream — the session recorder's
     * final sample, a pull-to-refresh. It shares the cached storage reading but does not disturb the
     * CPU sampler's cursor beyond what any read does, because [CompositeMetricsReader] holds one
     * sampler: a one-shot read between two loop ticks makes the next tick's delta cover a shorter
     * interval than the nominal one. That is a real inaccuracy in the sampled figure, which is why
     * this is used for the occasional read and not as an alternative to collecting [snapshots].
     */
    suspend fun sampleOnce(): PerformanceSnapshot =
        capture(tick = FORCED_TICK, settings = preferences.settings.value)

    /**
     * Drops the graph window. Called when a session ends, so the next one starts empty.
     *
     * The graph window only; [batteryTrend] survives, for the reason given on that property.
     */
    fun clearHistory() {
        historyState.value = MetricHistory.EMPTY
    }

    // -------------------------------------------------------------------------- internals

    /**
     * The loop itself.
     *
     * `resetSampling` on both delta-based readers at the top: the loop may be starting after a gap
     * during which the CPU counters kept moving, and a delta across that gap describes the whole gap
     * rather than one interval. Discarding the cursor costs one tick of "waiting for a second sample"
     * and buys a first figure that means what it says.
     *
     * The interval is read from settings on every iteration rather than captured once, so changing it
     * in Settings takes effect on the next tick without restarting the loop — a restart would throw
     * away the sampler cursor and produce exactly the artefact the reset above exists to avoid.
     */
    private fun sampleStream(): Flow<PerformanceSnapshot> = flow {
        metrics.resetSampling()
        networkReader.resetSampling()
        var tick = 0L
        while (true) {
            val settings = preferences.settings.value
            val snapshot = capture(tick, settings)
            historyState.value = historyState.value.add(snapshot.toHistoryPoint())
            // Dated from the snapshot rather than from `now`: assembling one costs a shell round trip
            // on some devices, and the battery was read at the start of it.
            trendState.value = trendState.value.update(snapshot.battery, snapshot.capturedAtMillis)
            emit(snapshot)
            tick++
            delay(settings.sampleIntervalMillis)
        }
    }

    private suspend fun capture(tick: Long, settings: AppSettings): PerformanceSnapshot {
        val network = networkReader.read()
        return PerformanceSnapshot(
            capturedAtMillis = System.currentTimeMillis(),
            cpu = metrics.readCpu(),
            memory = metrics.readMemory(),
            battery = metrics.readBattery(),
            thermal = metrics.readThermal(),
            storage = storageFor(tick),
            display = displayReader.read(),
            network = network,
            frameRate = frameRateFor(),
            latency = latencyFor(tick, settings, network.isConnected),
            accessLevel = metrics.accessLevel,
        )
    }

    private suspend fun storageFor(tick: Long): StorageReading {
        val cached = cachedStorage
        if (cached != null && tick % STORAGE_EVERY_TICKS != 0L) return cached
        return metrics.readStorage().also { cachedStorage = it }
    }

    /**
     * The frame-rate field, and the §24B rule in code.
     *
     * There is one path to a number here and it requires [FrameRateCapability.GameFrameStats] — real
     * frame timestamps for the watched package. Every other branch returns a [Observed.Restricted]
     * carrying the capability's own explanation. Nothing infers a rate from the refresh rate, from
     * GameCore's own window, or from how long a tick took.
     */
    private suspend fun frameRateFor(): Observed<FrameRateSample> {
        val capability = capabilityState.value
        val target = watched
        if (capability !is FrameRateCapability.GameFrameStats || target == null) {
            return if (capability is FrameRateCapability.Unavailable &&
                capability.reason == FrameRateCapability.NEEDS_SHIZUKU
            ) {
                Observed.needsElevation(capability.explanation)
            } else {
                Observed.notPresent(capability.explanation)
            }
        }
        val sample = frameRateProbe.sample(target)
            ?: return Observed.Failed("This game's frame timing came back empty.")
        return if (!sample.isReliable) {
            Observed.awaitingSample("Not enough frames in the window to state a rate yet.")
        } else {
            Observed.of(sample, DataSource.DUMPSYS_SHIZUKU, Precision.SAMPLED)
        }
    }

    /**
     * The latency field.
     *
     * Not probed on the first tick: a handshake against an unreachable host blocks for the whole
     * timeout, and doing that inside the first sample would delay the dashboard's first paint by
     * seconds on a device with no route. The first probe lands on tick [LATENCY_EVERY_TICKS] and the
     * field says "waiting" until then, which is true.
     */
    private suspend fun latencyFor(
        tick: Long,
        settings: AppSettings,
        connected: Boolean,
    ): Observed<LatencyProbe> {
        if (!settings.measureLatency) {
            return Observed.samplingDisabled("Latency measurement is switched off in Settings.")
                .also { cachedLatency = it }
        }
        if (!connected) {
            return Observed.notPresent("No network connection to measure.")
                .also { cachedLatency = it }
        }
        if (tick == 0L || tick % LATENCY_EVERY_TICKS != 0L) return cachedLatency
        return latencyProber.probe(host = settings.latencyHost).also { cachedLatency = it }
    }

    /**
     * Resets the sampling state when the last collector goes away.
     *
     * The history is kept: a user who navigates from the performance screen to Settings and back
     * within the grace period expects their graph to still be there. The caches are dropped because
     * the next loop starts with `resetSampling` anyway and a stale storage figure surviving a
     * suspend-resume cycle would be shown as current.
     */
    private fun onLoopStopped() {
        cachedStorage = null
        cachedLatency = Observed.awaitingSample("Waiting for the first latency probe.")
    }

    private fun PerformanceSnapshot.toHistoryPoint(): HistoryPoint = HistoryPoint(
        atMillis = capturedAtMillis,
        cpuPercent = (cpu.overallPercent as? Observed.Value)?.value,
        memoryPercent = memory.usedPercent,
        temperatureDeciCelsius = (primaryTemperatureDeciCelsius as? Observed.Value)?.value,
        frameRate = (frameRate as? Observed.Value)?.value?.averageFps,
        latencyMillis = (latency as? Observed.Value)?.value?.millis,
    )

    private companion object {
        /**
         * How long the loop keeps running after the last collector leaves.
         *
         * Long enough to cover a configuration change and a tab switch, short enough that backgrounding
         * the app stops the sampling well inside the window where Android would care.
         */
        const val IDLE_GRACE_MILLIS = 4_000L

        /** ~30 s at the default interval. */
        const val STORAGE_EVERY_TICKS = 15L

        /** ~10 s at the default interval. */
        const val LATENCY_EVERY_TICKS = 5L

        /** A tick value no modulo test matches, so [sampleOnce] reuses caches rather than refreshing. */
        const val FORCED_TICK = -1L
    }
}
