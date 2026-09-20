package com.gamecore.data.repository

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.gamecore.aimlab.engine.ConfigCodec
import com.gamecore.aimlab.engine.ControlLayout
import com.gamecore.aimlab.engine.CsvWriter
import com.gamecore.aimlab.engine.NumberText
import com.gamecore.aimlab.engine.SensitivityProfile
import com.gamecore.aimlab.engine.SessionSummary
import com.gamecore.aimlab.engine.Weapon
import com.gamecore.core.common.Formatters
import com.gamecore.core.common.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes Aim Lab configuration and stats to a local file, and reads a configuration file back.
 *
 * The file mechanics mirror [DiagnosticsExporter] exactly — a file under `filesDir/exports/aimlab`, a
 * `content://` URI from GameCore's own `FileProvider`, pruning to the last few — so the same "no storage
 * permission, one-shot read grant" story holds. What differs is the payload and that it round-trips: a
 * configuration exported as JSON can be imported again, and the validation that makes that safe lives in
 * the engine's [ConfigCodec], which this class simply calls. Nothing leaves the device unless the user
 * picks a share target themselves.
 */
@Singleton
class AimLabExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * Writes the config bundle as JSON, or the session table as CSV/text.
     *
     * JSON is the round-trippable form — weapons, sensitivity profiles and layouts encoded by
     * [ConfigCodec.encode], importable via [importConfig]. CSV/text is a human-readable session report and
     * is not re-importable, which is the honest split: a stats table is for reading, a config file is for
     * moving between devices. [recordCount] counts whatever the format wrote.
     */
    suspend fun exportConfig(
        bundle: ConfigCodec.ConfigBundle,
        sessions: List<SessionSummary>,
        format: DiagnosticsFormat,
    ): AimLabExport = withContext(io) {
        write(format) { writer ->
            when (format) {
                DiagnosticsFormat.JSON -> {
                    writer.write(ConfigCodec.encode(bundle))
                    bundle.weapons.size + bundle.sensitivities.size + bundle.layouts.size
                }
                DiagnosticsFormat.CSV -> {
                    writer.write(sessionsCsv(sessions))
                    sessions.size
                }
                DiagnosticsFormat.TEXT -> {
                    writer.write(sessionsText(sessions))
                    sessions.size
                }
            }
        }
    }

    /**
     * Parses and validates a configuration file. The heavy lifting is [ConfigCodec.decode], which rejects
     * malformed JSON, the wrong schema or kind, an oversized file, out-of-range values and duplicate or
     * unknown ids. This layer only reads the text off the URI and hands it over.
     */
    suspend fun importConfig(text: String): ConfigCodec.ImportResult = withContext(io) {
        ConfigCodec.decode(text)
    }

    // ------------------------------------------------------------------------------ internals

    private fun write(format: DiagnosticsFormat, body: (java.io.Writer) -> Int): AimLabExport = try {
        val directory = File(context.filesDir, DIRECTORY_NAME).apply { if (!exists()) mkdirs() }
        prune(directory)
        val file = File(directory, "gamecore-aimlab-${Formatters.fileTimestamp(now())}.${format.extension}")
        val count = file.bufferedWriter().use { writer -> body(writer) }
        if (count == 0) {
            file.delete()
            AimLabExport.Empty
        } else {
            AimLabExport.Written(
                fileName = file.name,
                sizeBytes = file.length(),
                recordCount = count,
                mimeType = format.mimeType,
                uri = shareUri(file),
            )
        }
    } catch (error: Throwable) {
        AimLabExport.Failed(error.javaClass.simpleName)
    }

    /** Session stats as a CSV, one row per session, with the engine's formula-injection escaping. */
    private fun sessionsCsv(sessions: List<SessionSummary>): String {
        val header = listOf(
            "started", "mode", "difficulty", "weapon", "sensitivity", "score", "hits", "shots",
            "accuracy_percent", "targets_missed", "reaction_avg_ms", "reaction_fastest_ms",
            "tracking_error", "time_on_target", "recoil_compensation", "gyro_stability", "duration_ms",
        )
        val rows = sessions.map { s ->
            listOf(
                Formatters.fileTimestamp(s.startedAtMillis),
                s.mode.label,
                s.difficulty.label,
                s.weaponName.orEmpty(),
                s.sensitivityName.orEmpty(),
                s.score.toString(),
                s.hits.toString(),
                s.shots.toString(),
                s.accuracyPercent.toString(),
                s.targetsMissed.toString(),
                NumberText.fixed(s.reactionStats.averageMillis, 1),
                s.reactionStats.fastestMillis.toString(),
                NumberText.fixed(s.trackingErrorAverage, 4),
                NumberText.fixed(s.timeOnTargetFraction, 3),
                NumberText.fixed(s.recoilCompensation, 3),
                NumberText.fixed(s.gyroStability, 3),
                s.durationMillis.toString(),
            )
        }
        return CsvWriter.document(header, rows)
    }

    private fun sessionsText(sessions: List<SessionSummary>): String {
        if (sessions.isEmpty()) return "No Aim Lab sessions recorded.\n"
        val sb = StringBuilder("GameCore Aim Lab — session history\n\n")
        for (s in sessions) {
            sb.append(Formatters.fileTimestamp(s.startedAtMillis))
                .append("  ").append(s.mode.label)
                .append(" · ").append(s.difficulty.label)
                .append("  score ").append(s.score)
                .append("  acc ").append(s.accuracyPercent).append('%')
                .append('\n')
        }
        return sb.toString()
    }

    private fun prune(directory: File) {
        val prefix = "gamecore-aimlab-"
        directory.listFiles()
            ?.filter { it.isFile && it.name.startsWith(prefix) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
            .drop(KEEP_EXPORTS - 1)
            .forEach { runCatching { it.delete() } }
    }

    private fun shareUri(file: File): Uri? = try {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    } catch (error: Throwable) {
        null
    }

    private fun now(): Long = System.currentTimeMillis()

    private companion object {
        const val DIRECTORY_NAME = "exports/aimlab"
        const val KEEP_EXPORTS = 5
    }
}

/**
 * The outcome of an Aim Lab export. Distinct from [DiagnosticsExport] so [recordCount] can mean "items
 * written" for whichever payload the format produced, rather than being widened onto the diagnostics type.
 */
sealed interface AimLabExport {
    data class Written(
        val fileName: String,
        val sizeBytes: Long,
        val recordCount: Int,
        val mimeType: String,
        val uri: Uri?,
    ) : AimLabExport

    data object Empty : AimLabExport

    /** [detail] is the exception's simple name only — never a path or message. */
    data class Failed(val detail: String) : AimLabExport
}
