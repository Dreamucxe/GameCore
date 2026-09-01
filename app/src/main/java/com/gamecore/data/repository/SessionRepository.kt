package com.gamecore.data.repository

import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.model.BatteryDrain
import com.gamecore.core.model.GameSession
import com.gamecore.core.model.SessionSample
import com.gamecore.core.model.SessionSort
import com.gamecore.core.model.SessionStatistics
import com.gamecore.core.model.StopReason
import com.gamecore.data.database.Mappers
import com.gamecore.data.database.SessionDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Recorded sessions, their samples, and the statistics over them.
 *
 * The write path is used by the tracking service and has one property that matters more than any
 * other here: **a session row exists from the moment recording starts**, with `ended_at` null. It is
 * not held in memory and written at the end. If the process dies mid-session — a low-memory kill
 * during a demanding game is the normal case, not the exceptional one — the row and its samples are
 * already on disk and [repairUnfinished] closes them on next launch. The alternative loses the
 * entire session, which is the one outcome a session tracker must not have.
 *
 * Sorting is done here rather than in SQL for two of the four orders. Newest and oldest are indexed
 * column sorts and belong in the query; longest and highest-drain are computed from column pairs,
 * and `ORDER BY (ended_at - started_at)` cannot use an index anyway. Since the list is already
 * materialised for display, sorting it in Kotlin costs nothing and keeps the drain ordering
 * consistent with [BatteryDrain]'s own honesty rules rather than duplicating them in SQL.
 */
@Singleton
class SessionRepository @Inject constructor(
    private val dao: SessionDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** Finished sessions, newest first. Running sessions are excluded by the query. */
    val sessions: Flow<List<GameSession>> =
        dao.observeFinished().map { rows -> rows.map(Mappers::toModel) }

    val sessionCount: Flow<Int> = dao.observeSessionCount()

    fun sessionsFor(packageName: String): Flow<List<GameSession>> =
        dao.observeForPackage(packageName).map { rows -> rows.map(Mappers::toModel) }

    /** Every finished session, read once. Used by the CSV export, which is not a subscriber. */
    suspend fun snapshot(): List<GameSession> = withContext(io) {
        dao.finished().map(Mappers::toModel)
    }

    suspend fun session(id: Long): GameSession? = withContext(io) {
        dao.byId(id)?.let(Mappers::toModel)
    }

    suspend fun samples(sessionId: Long): List<SessionSample> = withContext(io) {
        dao.samples(sessionId).map(Mappers::toModel)
    }

    /** The packages that appear in history, for the filter dropdown. */
    suspend fun recordedPackages(): List<String> = withContext(io) {
        dao.recordedPackages()
    }

    // ------------------------------------------------------------------ write path

    /**
     * Inserts a starting session and returns its id.
     *
     * Called before the first sample, so the samples have a session to belong to and so the row
     * survives the process. The id is what the tracker holds for the rest of the session.
     */
    suspend fun begin(session: GameSession): Long = withContext(io) {
        dao.insert(Mappers.toEntity(session.copy(id = 0L)))
    }

    /**
     * Writes a batch of samples.
     *
     * Batched because an encrypted database pays page re-encryption per commit, and a commit every
     * two seconds for a two-hour session is measurable in battery — in an app whose whole point is
     * not costing the user performance.
     */
    suspend fun appendSamples(samples: List<SessionSample>) {
        if (samples.isEmpty()) return
        withContext(io) { dao.insertSamples(samples.map(Mappers::toEntity)) }
    }

    /** Updates the row in place: aggregates as they accumulate, `ended_at` when it finishes. */
    suspend fun update(session: GameSession) = withContext(io) {
        dao.update(Mappers.toEntity(session))
    }

    /**
     * Finishes a session, or deletes it if it was too short to mean anything.
     *
     * Returns true if it was kept. The floor is [GameSession.MIN_SAVEABLE_MILLIS] — a fifteen-second
     * "session" is a mis-tap on a game icon, and a history list full of those is a history list
     * nobody reads. Deleting takes the samples with it in one transaction.
     */
    suspend fun finish(session: GameSession, endedAtMillis: Long): Boolean = withContext(io) {
        val duration = (endedAtMillis - session.startedAtMillis).coerceAtLeast(0L)
        if (duration < GameSession.MIN_SAVEABLE_MILLIS) {
            dao.deleteWithSamples(session.id)
            return@withContext false
        }
        dao.update(
            Mappers.toEntity(
                session.copy(
                    endedAtMillis = endedAtMillis,
                    sampleCount = dao.sampleCount(session.id),
                ),
            ),
        )
        true
    }

    /**
     * Closes sessions left open by a process death.
     *
     * Runs on launch. The end time is the last sample's own elapsed offset added to the start —
     * not `now`, which would claim the game was running for however long the phone was off, and not
     * the start time, which would produce a zero-length session. A session with no samples at all
     * cannot be dated and is deleted.
     *
     * Returns how many rows it repaired, for the launch log and for the tests §31 asks for around
     * "app restarted mid-session".
     */
    suspend fun repairUnfinished(): Int = withContext(io) {
        var repaired = 0
        dao.unfinished().forEach { entity ->
            val samples = dao.samples(entity.id)
            val lastOffset = samples.maxOfOrNull { it.elapsedMillis }
            if (lastOffset == null || lastOffset < GameSession.MIN_SAVEABLE_MILLIS) {
                dao.deleteWithSamples(entity.id)
                return@forEach
            }
            val model = Mappers.toModel(entity)
            dao.update(
                Mappers.toEntity(
                    aggregate(model, samples.map(Mappers::toModel)).copy(
                        endedAtMillis = entity.startedAtMillis + lastOffset,
                        // The battery reading from the last sample, when there is one. A repaired
                        // session with no end reading reports no drain rather than a wrong one.
                        batteryEndPercent = samples.lastOrNull()?.batteryPercent
                            ?: entity.batteryEndPercent,
                        sampleCount = samples.size,
                        // Recorded rather than inferred later: the row's duration ends at its last
                        // sample, so it is a floor and the report has to be able to say so.
                        stopReason = StopReason.PROCESS_DEATH,
                    ),
                ),
            )
            repaired++
        }
        repaired
    }

    // ------------------------------------------------------------------ aggregation

    /**
     * Folds a session's samples into its summary columns.
     *
     * Every aggregate is computed over the samples that actually carried that metric, and stays
     * null when none did. `mapNotNull` before averaging is the whole point: a session where CPU was
     * never readable — a locked-down device, or elevated reads switched off — reports no CPU average
     * rather than 0%, and the report shows "not measured" instead of a graph of zeroes.
     *
     * Pure, and takes the samples as an argument, so the arithmetic §31 asks to be tested is
     * testable without a database.
     */
    fun aggregate(session: GameSession, samples: List<SessionSample>): GameSession {
        if (samples.isEmpty()) return session.copy(sampleCount = 0)

        val cpu = samples.mapNotNull { it.cpuPercent }
        val memory = samples.mapNotNull { it.memoryPercent }
        val temperature = samples.mapNotNull { it.temperatureDeciCelsius }
        val refresh = samples.mapNotNull { it.refreshRate }
        val frame = samples.mapNotNull { it.frameRate }
        val latency = samples.mapNotNull { it.latencyMillis }

        return session.copy(
            averageCpuPercent = cpu.averageOrNull(),
            peakCpuPercent = cpu.maxOrNull(),
            averageMemoryPercent = memory.averageOrNull(),
            peakMemoryPercent = memory.maxOrNull(),
            averageTemperatureDeciCelsius = temperature.averageOrNull()?.toInt(),
            peakTemperatureDeciCelsius = temperature.maxOrNull(),
            averageRefreshRate = refresh.averageOrNull(),
            averageFrameRate = frame.averageOrNull(),
            averageLatencyMillis = latency.averageOrNull()?.toInt(),
            sampleCount = samples.size,
        )
    }

    /**
     * The history screen's summary row.
     *
     * Six aggregate queries rather than loading every session: a user with a few hundred sessions
     * would otherwise read tens of thousands of rows to produce six numbers. The drain average uses
     * the query whose WHERE clause encodes [BatteryDrain]'s own rules, so a session GameCore would
     * refuse to quote a rate for individually is not folded into the average either.
     */
    suspend fun statistics(): SessionStatistics = withContext(io) {
        val mostPlayed = dao.mostPlayed()
        SessionStatistics(
            sessionCount = dao.finishedCount(),
            totalPlayTimeMillis = dao.totalPlayTimeMillis(),
            longestSessionMillis = dao.longestSessionMillis(),
            mostPlayedPackage = mostPlayed?.packageName,
            mostPlayedLabel = mostPlayed?.gameLabel,
            mostPlayedMillis = mostPlayed?.totalMillis ?: 0L,
            averageBatteryDrainPercentPerHour =
                dao.averageDrainPercentPerHour(BatteryDrain.MIN_RELIABLE_MILLIS),
            peakTemperatureDeciCelsius = dao.peakTemperatureDeciCelsius(),
        )
    }

    // ---------------------------------------------------------------------- deletes

    suspend fun delete(sessionId: Long) = withContext(io) {
        dao.deleteWithSamples(sessionId)
    }

    /** Settings' "clear history". Sessions and samples, in one transaction; nothing else. */
    suspend fun clearHistory() = withContext(io) {
        dao.clearHistory()
    }

    // -------------------------------------------------------------------- internals

    /**
     * Orders a materialised list.
     *
     * See the class note for why two of these are not SQL. Longest-first uses the stored end time
     * rather than `now`, because every session in this list is finished.
     */
    fun sort(sessions: List<GameSession>, order: SessionSort): List<GameSession> = when (order) {
        SessionSort.NEWEST_FIRST -> sessions.sortedByDescending { it.startedAtMillis }
        SessionSort.OLDEST_FIRST -> sessions.sortedBy { it.startedAtMillis }
        SessionSort.LONGEST_FIRST -> sessions.sortedByDescending { it.durationMillis() }
        // Sessions with no honest rate sort last rather than being dropped: the user asked for an
        // order, not a filter.
        SessionSort.HIGHEST_DRAIN -> sessions.sortedByDescending {
            it.drain()?.takeIf { drain -> drain.isReliable }?.percentPerHour ?: -1f
        }
    }
}

/**
 * Null for an empty list rather than `NaN`, which is what `average()` returns.
 *
 * `@JvmName` because both overloads erase to `averageOrNull(List)` on the JVM. Two functions rather
 * than one over `Number` so neither boxes a whole sample list to compute a mean.
 */
@JvmName("averageOfFloats")
private fun List<Float>.averageOrNull(): Float? =
    if (isEmpty()) null else (sum() / size)

@JvmName("averageOfInts")
private fun List<Int>.averageOrNull(): Float? =
    if (isEmpty()) null else (sum().toFloat() / size)
