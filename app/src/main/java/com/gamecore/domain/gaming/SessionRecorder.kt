package com.gamecore.domain.gaming

import com.gamecore.core.common.Observed
import com.gamecore.core.model.ColorCorrection
import com.gamecore.core.model.GameSession
import com.gamecore.core.model.LatencyProbe
import com.gamecore.core.model.PerformanceSnapshot
import com.gamecore.core.model.SessionSample
import com.gamecore.core.model.StopReason
import com.gamecore.data.repository.SessionRepository
import com.gamecore.domain.monitoring.LatencyLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the record of the session that is running now.
 *
 * Three decisions define it, and all three exist because a session tracker's failure mode is losing
 * the session it was tracking.
 *
 * **The row exists from the first second.** [begin] inserts before a single sample is taken, so a
 * low-memory kill — which is the normal outcome of a demanding game, not the exceptional one —
 * leaves a row and its samples on disk for `SessionRepository.repairUnfinished` to close. Holding
 * the session in memory until the game exits would be simpler and would lose exactly the sessions
 * worth having.
 *
 * **It does not sample.** Samples arrive through [offer] from whatever is already sampling — in
 * practice `PerformanceMonitor`. This is not a style preference: CPU utilisation is a delta between
 * two `/proc/stat` reads, so a second sampler would move the first one's cursor and both would
 * report nonsense. One sampler, many consumers, and this is a consumer.
 *
 * **Samples are written in batches.** Every commit to an encrypted database re-encrypts the pages it
 * touches, and a commit every two seconds for a three-hour session is a cost the user pays in
 * battery to run an app whose selling point is not costing them anything. [FLUSH_EVERY] samples per
 * write bounds the loss from a kill to half a minute, which `repairUnfinished` then dates honestly
 * from the last sample it finds.
 *
 * Every entry point is serialised through one [Mutex], including the database writes. Contention is
 * not the concern — one service calls all of these — but ordering is: a batch landing after the
 * aggregate that was supposed to include it would leave a row whose averages disagree with its own
 * samples.
 */
@Singleton
class SessionRecorder @Inject constructor(
    private val sessions: SessionRepository,
) {

    private val mutex = Mutex()

    /** Samples taken but not yet written. Only ever touched under [mutex]. */
    private val pending = mutableListOf<SessionSample>()

    /**
     * The running fold of this session's probe outcomes. Only ever touched under [mutex].
     *
     * Separate from [pending] because it is not a batch waiting for a write: it is the whole log, folded
     * to a handful of counters, and it reaches disk as six columns on the session row at the next flush.
     */
    private var latency = LatencyLogger.EMPTY

    private val state = MutableStateFlow<GameSession?>(null)

    /**
     * The session being recorded, or null.
     *
     * Carries the aggregates as they stand at the last flush, so the notification and the Home card
     * can show a live duration and drain without either of them querying anything.
     */
    val active: StateFlow<GameSession?> = state.asStateFlow()

    val isRecording: Boolean get() = state.value != null

    /**
     * Opens a session for [packageName] and records [opening] as its first sample.
     *
     * [opening] is a parameter rather than a read for the honesty reason and the practical one: the
     * starting battery level and whether a charger is attached both have to come from the same
     * instant the session starts, and this class is not allowed to sample (see the class notes).
     *
     * [colorPresetName] and [colorCorrection] are the colour reading for the row, and both are
     * parameters for the same reason: whether the display was actually corrected is something the
     * caller watched happen, and this class reading the device would be reading it later and getting a
     * different answer. Null in both means the display was left alone — which is also what every
     * session recorded before the colour feature existed reads back as.
     *
     * If a different game is somehow already being recorded, that session is finished as a switch
     * rather than abandoned. That is recovery from a caller's mistake, not a supported flow — leaving
     * it open would put a row with a null `ended_at` on disk for the next launch to repair, and
     * `PROCESS_DEATH` is not what happened.
     */
    suspend fun begin(
        packageName: String,
        gameLabel: String,
        profileApplied: Boolean,
        opening: PerformanceSnapshot,
        nowMillis: Long = System.currentTimeMillis(),
        colorPresetName: String? = null,
        colorCorrection: ColorCorrection? = null,
    ): GameSession = mutex.withLock {
        state.value?.let { running ->
            if (running.packageName == packageName) return@withLock running
            finishLocked(running, StopReason.SWITCHED_GAME, nowMillis)
        }
        // After the switch above, not before: the outgoing session's log belongs to the row that was
        // just written, and clearing it first would file this game's probes under the last game.
        latency = LatencyLogger.EMPTY

        val started = GameSession.starting(
            packageName = packageName,
            gameLabel = gameLabel,
            batteryPercent = opening.battery.levelPercent,
            profileApplied = profileApplied,
            nowMillis = nowMillis,
        ).copy(
            wasCharging = opening.battery.isCharging,
            colorPresetName = colorPresetName,
            colorCorrection = colorCorrection,
        )

        val session = started.copy(id = sessions.begin(started))
        // Dated from the snapshot's own capture time, not zero. The opening sample is taken after the
        // game was detected — the first read of storage and frame-timing capability is not free — and
        // stamping it at zero would put a sample slightly ahead of the session's start behind it,
        // which is the sort of small dishonesty that makes a graph's first point wrong.
        pending += opening.toSample(
            session.id,
            elapsedMillis = (opening.capturedAtMillis - nowMillis).coerceAtLeast(0L),
        )
        state.value = session
        session
    }

    /**
     * Adds one sample to the session, writing the batch out when it is full.
     *
     * Does nothing if no session is running, so a monitor that keeps emitting for a few seconds after
     * a game exits cannot append samples to a session that has already been closed.
     *
     * The two battery fields are latched here rather than at the end. [GameSession.wasCharging] is
     * sticky by definition — a charger connected for one minute of an hour invalidates the drain rate
     * for the whole hour — and `batteryEndPercent` is kept current so a live drain figure exists
     * before the session ends.
     */
    suspend fun offer(
        snapshot: PerformanceSnapshot,
        nowMillis: Long = System.currentTimeMillis(),
    ): Unit = mutex.withLock {
        val session = state.value ?: return
        val elapsed = (nowMillis - session.startedAtMillis).coerceAtLeast(0L)
        pending += snapshot.toSample(session.id, elapsed)
        state.value = session.copy(
            batteryEndPercent = snapshot.battery.levelPercent,
            wasCharging = session.wasCharging || snapshot.battery.isCharging,
        )
        if (pending.size >= FLUSH_EVERY) flushLocked()
    }

    /**
     * Adds one latency probe's outcome to the session's log.
     *
     * Separate from [offer] because a probe and a sample arrive at different rates: the monitor probes
     * roughly every fifth tick, and the sample in between carries the last probe's result again so the
     * dashboard has something to show. Folding samples would therefore count one handshake five times
     * and could not tell a probe that failed from a tick that never probed — which is the whole reason
     * this feature is not a query over `session_samples`.
     *
     * The fold is arithmetic, so it costs nothing to do under the same lock as everything else, and the
     * result is published to [active] immediately. It reaches disk at the next flush, which gives the
     * log the same half-minute durability bound as the samples rather than a write per probe.
     *
     * Does nothing when no session is running, for [offer]'s reason: the monitor's loop outlives the
     * game by a few seconds and those probes belong to no session.
     */
    suspend fun offerProbe(probe: Observed<LatencyProbe>): Unit = mutex.withLock {
        val session = state.value ?: return
        latency = latency.record(probe)
        state.value = session.copy(latencyLog = latency.log)
    }

    /**
     * Writes what is buffered and brings the row's aggregates up to date.
     *
     * Worth calling when the service is being taken down for any reason it can see coming — a stop
     * command, a trim-memory callback — because the samples in [pending] are the only ones not yet
     * on disk.
     */
    suspend fun flush(): Unit = mutex.withLock { flushLocked() }

    /**
     * Stamps the smart-feature summary (§B6/§D8) onto the running session, just before it is finished.
     *
     * These figures are not sampled per tick — they are the session-long totals the [SmartFeatureRunner]
     * holds and reports once at the end — so they are set on the state here rather than accumulated in
     * [offer]. `aggregate` in [finishLocked] recomputes the sampled averages from disk and copies the rest
     * of the row through unchanged, so a value set here survives to the finished row. Null fields mean the
     * feature was off, which the mappers store as NULL rather than a zero.
     */
    suspend fun recordSmartSummary(summary: SmartSessionSummary): Unit = mutex.withLock {
        val session = state.value ?: return@withLock
        state.value = session.copy(
            downshiftCount = summary.downshiftCount,
            lowestRateHz = summary.lowestRateHz,
            fullPerformanceOverridden = summary.fullPerformanceOverridden,
            systemReenabledSaver = summary.systemReenabledSaver,
        )
    }

    /**
     * Stamps the network summary (§C7) onto the running session, just before it is finished.
     *
     * The transport is not sampled per row — it is the one the [NetworkAlertMonitor] latched over the
     * session — so it is set here rather than in [offer], the same way [recordSmartSummary] sets the
     * thermal and saver totals. `aggregate` in [finishLocked] recomputes only the sampled averages and
     * copies the rest of the row through, so a transport set here survives to the finished row. A null
     * transport means the network check was off, which the mapper stores as NULL rather than a guess.
     */
    suspend fun recordNetworkSummary(summary: NetworkSessionSummary): Unit = mutex.withLock {
        val session = state.value ?: return@withLock
        state.value = session.copy(transport = summary.transport)
    }

    /**
     * Closes the session and returns it, or null if it was too short to keep.
     *
     * [reason] is stored on the row. It is not decoration: `SessionRepository.finish` decides whether
     * fifteen seconds of play is worth a history entry, and the report screen reads
     * [GameSession.hasCompleteDuration] to decide whether to print the duration as a figure or as a
     * floor. A session ended by [StopReason.DETECTION_LOST] genuinely does not know how long the game
     * ran for, and says so.
     */
    suspend fun end(
        reason: StopReason,
        nowMillis: Long = System.currentTimeMillis(),
    ): GameSession? = mutex.withLock {
        val session = state.value ?: return null
        state.value = null
        finishLocked(session, reason, nowMillis)
    }

    // ------------------------------------------------------------------------ under the lock

    /**
     * Writes the buffer, then recomputes the aggregates from every sample on disk.
     *
     * Read back rather than accumulated in memory for two reasons. The averaging rule — mean over the
     * samples that actually carried that metric, null when none did — lives in
     * `SessionRepository.aggregate`, and a second copy of it here would be a second thing to keep
     * right. And the samples on disk are the real set: after a repair, or after a batch this instance
     * did not write, memory would be missing rows the row's own averages should include.
     */
    private suspend fun flushLocked() {
        val session = state.value ?: return
        writePendingLocked()
        val aggregated = sessions.aggregate(session, sessions.samples(session.id))
        sessions.update(aggregated)
        state.value = aggregated
    }

    private suspend fun finishLocked(
        session: GameSession,
        reason: StopReason,
        nowMillis: Long,
    ): GameSession? {
        writePendingLocked()
        val samples = sessions.samples(session.id)
        val finished = sessions.aggregate(session, samples).copy(
            stopReason = reason,
            // The last sample's own reading, falling back to the latched one. A session with no
            // samples at all reports no end level, and so no drain, rather than the start level —
            // which would read as a game that used no battery.
            batteryEndPercent = samples.lastOrNull()?.batteryPercent ?: session.batteryEndPercent,
        )
        val kept = sessions.finish(finished, nowMillis)
        return if (kept) finished.copy(endedAtMillis = nowMillis) else null
    }

    private suspend fun writePendingLocked() {
        if (pending.isEmpty()) return
        sessions.appendSamples(pending.toList())
        pending.clear()
    }

    private companion object {
        /** Samples per database write: half a minute at the default two-second interval. */
        const val FLUSH_EVERY = 15
    }
}
