package com.gamecore.data.repository

import android.net.Uri
import com.gamecore.aimlab.engine.CsvWriter
import com.gamecore.core.common.Formatters
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.export.SampleExport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes one session's raw per-sample time series out as a CSV or JSON file (§19).
 *
 * The delta to [SessionExporter]: that writes one row per session — the measured aggregates — and this
 * writes one row per *sample* of a single session, the series those aggregates were folded from. A
 * sibling class rather than more methods on the aggregate exporter, because the two answer different
 * questions and their result types count different things (sessions there, samples here).
 *
 * The file mechanics — the private `exports` directory, the pruned pile, the `content://` share grant,
 * the exception-to-typed-result funnel — are all [FileShareStore]'s, so this class keeps only the choice
 * of format and the empty-vs-written judgement. The mapping itself is [SampleExport], which is pure and
 * unit-tested; here it is only fed rows and handed a writer.
 */
@Singleton
class SampleExporter @Inject constructor(
    private val sessions: SessionRepository,
    private val files: FileShareStore,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** The two shapes a caller can ask for; the file mechanics are identical, the content is not. */
    enum class Format(val extension: String, val mimeType: String) {
        CSV(extension = "csv", mimeType = "text/csv"),
        JSON(extension = "json", mimeType = "application/json"),
    }

    /**
     * Exports the samples of one session.
     *
     * [SampleExportResult.Empty] — never a failure — for a session that does not exist or recorded no
     * samples, exactly as [SessionExporter] returns [ExportResult.Empty] for empty history: nothing to
     * write is not an error, and it is not written as one.
     *
     * CSV is streamed a row at a time through [FileShareStore.writeStreaming] so a marathon session's tens
     * of thousands of rows never have to be concatenated into one string. JSON is built in memory —
     * `org.json` has no streaming writer, and the optional JSON form is not the one a very long session
     * reaches for.
     */
    suspend fun export(sessionId: Long, format: Format): SampleExportResult = withContext(io) {
        val session = sessions.session(sessionId) ?: return@withContext SampleExportResult.Empty
        val samples = sessions.samples(sessionId)
        if (samples.isEmpty()) return@withContext SampleExportResult.Empty

        val fileName = "gamecore-session-${slug(session.gameLabel)}-" +
            "${Formatters.fileTimestamp(now())}-samples.${format.extension}"
        val accept = FileShareStore.byExtension(".${format.extension}")

        val result = when (format) {
            Format.CSV -> files.writeStreaming(
                subdir = DIRECTORY_NAME,
                fileName = fileName,
                keep = KEEP_EXPORTS,
                accept = accept,
            ) { writer ->
                writer.write(CsvWriter.row(SampleExport.CSV_HEADER))
                writer.write("\n")
                samples.forEach { sample ->
                    writer.write(SampleExport.csvLine(sample))
                    writer.write("\n")
                }
            }
            Format.JSON -> files.writeText(
                subdir = DIRECTORY_NAME,
                fileName = fileName,
                text = SampleExport.json(session, samples),
                keep = KEEP_EXPORTS,
                accept = accept,
            )
        }

        when (result) {
            is FileWriteResult.Stored -> SampleExportResult.Written(
                fileName = result.file.name,
                sizeBytes = result.file.length(),
                sampleCount = samples.size,
                mimeType = format.mimeType,
                uri = result.uri,
            )
            is FileWriteResult.Failed -> SampleExportResult.Failed(result.detail)
        }
    }

    private fun now(): Long = System.currentTimeMillis()

    /**
     * A filesystem-safe fragment of the game's label for the file name.
     *
     * The label comes from the package manager and can hold anything a developer chose — spaces, slashes,
     * colons, non-Latin scripts — none of which belong in a file name handed to an arbitrary share target.
     * Runs of anything that is not a letter or digit collapse to a single dash, the ends are trimmed, and
     * the result is capped; an empty result (a label of only punctuation, or a CJK title stripped away)
     * falls back to a fixed word so the name is never `gamecore-session--<ts>-samples.csv`.
     */
    private fun slug(label: String): String {
        val cleaned = buildString {
            var lastWasDash = false
            for (ch in label) {
                if (ch.isLetterOrDigit() && ch.code < 128) {
                    append(ch.lowercaseChar())
                    lastWasDash = false
                } else if (!lastWasDash) {
                    append('-')
                    lastWasDash = true
                }
            }
        }.trim('-').take(MAX_SLUG_LENGTH).trim('-')
        return cleaned.ifEmpty { FALLBACK_SLUG }
    }

    private companion object {
        const val DIRECTORY_NAME = "exports"
        const val KEEP_EXPORTS = 5
        const val MAX_SLUG_LENGTH = 40
        const val FALLBACK_SLUG = "game"
    }
}

/**
 * What a sample-export attempt did.
 *
 * The same shape as [ExportResult] and [DiagnosticsExport], and a separate type for the same reason those
 * are separate: [Written.sampleCount] counts samples, which is the right field here and the wrong one on a
 * session-history export. [Written.uri] is nullable because a file that exists but could not be granted a
 * URI is still a file, and [Written.mimeType] rides along so the caller can build the share intent without
 * knowing which format it asked for.
 */
sealed interface SampleExportResult {
    data class Written(
        val fileName: String,
        val sizeBytes: Long,
        val sampleCount: Int,
        val mimeType: String,
        val uri: Uri?,
    ) : SampleExportResult

    /** The session has no samples, or no longer exists. Not an error, and not written as one. */
    data object Empty : SampleExportResult

    /** [detail] is an exception class name — never a path or a message, which can carry user data. */
    data class Failed(val detail: String) : SampleExportResult
}
