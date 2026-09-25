package com.gamecore.data.repository

import android.net.Uri
import com.gamecore.core.common.Formatters
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.model.GameSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes the session history out as a CSV the user can open elsewhere.
 *
 * §23's export, and it exports what is actually stored: one row per session with the aggregates that
 * were measured, and an empty field where a reading was never taken. Nothing is filled in, averaged
 * across sessions, or reconstructed — an empty cell in this file means the same thing an em dash means
 * on the report screen.
 *
 * The file lands in GameCore's own `files/exports` directory and is handed out as a `content://` URI
 * with a one-shot read grant, which is why the app asks for no storage permission on any API level.
 */
@Singleton
class SessionExporter @Inject constructor(
    private val sessions: SessionRepository,
    private val files: FileShareStore,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    suspend fun export(): ExportResult = withContext(io) {
        val rows = sessions.snapshot()
        if (rows.isEmpty()) return@withContext ExportResult.Empty
        val fileName = "gamecore-sessions-${Formatters.fileTimestamp(now())}.csv"
        when (
            val result = files.writeText(
                subdir = DIRECTORY_NAME,
                fileName = fileName,
                text = buildCsv(rows),
                keep = KEEP_EXPORTS,
                accept = FileShareStore.byExtension(EXTENSION),
            )
        ) {
            is FileWriteResult.Stored -> ExportResult.Written(
                fileName = result.file.name,
                sizeBytes = result.file.length(),
                sessionCount = rows.size,
                uri = result.uri,
            )
            is FileWriteResult.Failed -> ExportResult.Failed(result.detail)
        }
    }

    private fun now(): Long = System.currentTimeMillis()

    /**
     * One header line, then one line per session, oldest first.
     *
     * Oldest first because a spreadsheet's own chart of a column reads left to right in time, and the
     * history screen's newest-first order is a browsing convenience that does not survive the export.
     */
    private fun buildCsv(rows: List<GameSession>): String {
        val out = StringBuilder()
        out.append(HEADER).append('\n')
        rows.sortedBy { it.startedAtMillis }.forEach { session ->
            out.append(lineFor(session)).append('\n')
        }
        return out.toString()
    }

    private fun lineFor(session: GameSession): String {
        val drain = session.drain()
        return listOf(
            Formatters.iso8601(session.startedAtMillis),
            session.endedAtMillis?.let { Formatters.iso8601(it) }.orEmpty(),
            session.gameLabel,
            session.packageName,
            (session.durationMillis() / 1_000L).toString(),
            if (session.hasCompleteDuration) "complete" else "lower bound",
            session.stopReason?.name.orEmpty(),
            session.batteryStartPercent.toString(),
            session.batteryEndPercent?.toString().orEmpty(),
            drain?.pointsLost?.toString().orEmpty(),
            drain?.percentPerHour?.let { oneDecimal(it) }.orEmpty(),
            if (session.wasCharging) "yes" else "no",
            oneDecimal(session.averageCpuPercent),
            oneDecimal(session.peakCpuPercent),
            oneDecimal(session.averageMemoryPercent),
            oneDecimal(session.peakMemoryPercent),
            deciCelsius(session.averageTemperatureDeciCelsius),
            deciCelsius(session.peakTemperatureDeciCelsius),
            oneDecimal(session.averageRefreshRate),
            oneDecimal(session.averageFrameRate),
            session.averageLatencyMillis?.toString().orEmpty(),
            // Blank rather than 0 for a session with no log, for the reason every other absence here is
            // blank: a zero in `latency_probes_failed` is a claim that nothing failed, and a session
            // recorded before the log existed makes no such claim.
            session.latencyLog?.completedProbes?.toString().orEmpty(),
            session.latencyLog?.failedProbes?.toString().orEmpty(),
            session.latencyLog?.spikes?.toString().orEmpty(),
            session.latencyLog?.worstMillis?.toString().orEmpty(),
            session.latencyLog?.jitterMillis?.toString().orEmpty(),
            session.latencyLog?.longestFailureRun?.toString().orEmpty(),
            session.latencyLog?.verdict?.name.orEmpty(),
            if (session.profileApplied) "yes" else "no",
            session.sampleCount.toString(),
        ).joinToString(separator = ",") { escape(it) }
    }

    /**
     * Makes one field safe for a CSV reader *and* for a spreadsheet.
     *
     * Two separate problems. Quoting handles commas, quotes and newlines, which a game label can
     * contain and which would otherwise shift every later column on that row. The leading-character
     * check is §24A.4: a label of `=cmd|…` or `+HYPERLINK(…)` is treated as a formula by Excel,
     * LibreOffice and Sheets on open, so a prefix that a user chose for their own game shortcut
     * becomes code running in the reader's spreadsheet. Prefixing a single quote makes it text.
     * GameCore reads app labels from the package manager and never audits them, so this is applied
     * to every field rather than to the ones expected to be risky.
     */
    private fun escape(field: String): String {
        val guarded = if (field.isNotEmpty() && field.first() in FORMULA_LEADS) "'$field" else field
        val cleaned = guarded.replace('\r', ' ').replace('\n', ' ')
        return if (cleaned.any { it == ',' || it == '"' || it == ';' || it == '\t' }) {
            "\"" + cleaned.replace("\"", "\"\"") + "\""
        } else {
            cleaned
        }
    }

    /** An empty field for an absent reading, never a zero. A blank cell is not a measurement. */
    private fun oneDecimal(value: Float?): String = value?.let { oneDecimal(it) } ?: ""

    private fun oneDecimal(value: Float): String {
        val rounded = kotlin.math.round(value * 10f).toInt()
        return "${rounded / 10}.${kotlin.math.abs(rounded % 10)}"
    }

    private fun deciCelsius(value: Int?): String = value?.let { "${it / 10}.${kotlin.math.abs(it % 10)}" } ?: ""

    private companion object {
        const val DIRECTORY_NAME = "exports"
        const val EXTENSION = ".csv"
        const val KEEP_EXPORTS = 5

        /** The characters a spreadsheet reads as the start of a formula. */
        val FORMULA_LEADS = charArrayOf('=', '+', '-', '@')

        const val HEADER =
            "started,ended,game,package,duration_seconds,duration_kind,stop_reason," +
                "battery_start_percent,battery_end_percent,battery_points_lost,battery_percent_per_hour," +
                "was_charging,cpu_average_percent,cpu_peak_percent,memory_average_percent," +
                "memory_peak_percent,temperature_average_celsius,temperature_peak_celsius," +
                "refresh_rate_average_hz,frame_rate_average_fps,latency_average_ms," +
                "latency_probes_completed,latency_probes_failed,latency_spikes,latency_worst_ms," +
                "latency_jitter_ms,latency_longest_failure_run,connection_verdict,profile_applied," +
                "sample_count"
    }
}

/**
 * What an export attempt did.
 *
 * [Written.uri] is nullable on purpose: the file exists and is listed either way, and a provider that
 * refuses a URI should not turn a successful write into a failure. The Settings screen offers the
 * share sheet only when there is something to share.
 */
sealed interface ExportResult {
    data class Written(
        val fileName: String,
        val sizeBytes: Long,
        val sessionCount: Int,
        val uri: Uri?,
    ) : ExportResult

    /** Nothing recorded yet. Not an error, and it is not written as one. */
    data object Empty : ExportResult

    /** [detail] is an exception class name — never a path or a message, which can carry user data. */
    data class Failed(val detail: String) : ExportResult
}
