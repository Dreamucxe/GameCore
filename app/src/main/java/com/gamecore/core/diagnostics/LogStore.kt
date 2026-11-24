package com.gamecore.core.diagnostics

import android.content.Context
import com.gamecore.core.common.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The durable history behind the Logs screen.
 *
 * ## Why files rather than a database
 *
 * The writer that matters most is the uncaught-exception handler. It runs on a thread that is already
 * dying, possibly while the Room database's own connection is in an unknown state, and a reporter
 * that needs a coroutine and a database transaction before it can write will lose the one crash the
 * user actually wanted. Appending a line to a file needs neither. The same format is what a person
 * shares: one text file, readable without this app.
 *
 * ## Bounds
 *
 * Every append checks the directory's size and age, not just the record count. A crashing game can
 * produce a stack trace longer than the whole budget, and a store that grows without limit is a
 * denial of service the user did not ask for. [MAX_FILES] days and [MAX_TOTAL_BYTES] is enough for a
 * bug report and small enough to be uninteresting to a quota warning.
 *
 * ## The switch
 *
 * Off means no new record is written, including a crash. The file is the source of truth rather than
 * a preference, because [CrashLog] reads it before Hilt has necessarily finished constructing the
 * graph, and because a diagnostic switch that requires the app to be healthy to turn off is the wrong
 * shape. Existing records are never deleted by turning the switch off; only by Clear.
 */
@Singleton
class LogStore @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    private val directory = File(context.filesDir, DIRECTORY)
    private val enabledFile = File(directory, ENABLED_FILE)
    private val writeLock = Any()

    private val _enabled = MutableStateFlow(readEnabled())
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** True when the previous process wrote a crash before it died. Read once by the screen. */
    val directoryPath: String get() = directory.absolutePath

    /**
     * Writes one record, or does nothing when logging is off.
     *
     * Synchronous by design: this is the call the crash handler makes. It catches its own I/O
     * failures because a log writer that throws during a crash replaces the original stack with its
     * own, which is the one outcome worse than no log.
     */
    fun append(record: LogRecord) {
        if (!_enabled.value) return
        runCatching {
            synchronized(writeLock) {
                if (!directory.exists() && !directory.mkdirs()) return
                val file = File(directory, dayKey(record.atMillis) + EXTENSION)
                file.appendText(LogRecordCodec.encode(record) + "\n")
                prune()
            }
        }
    }

    /** Builds a record from the fields every crash path has, so the three callers cannot drift. */
    fun crashRecord(
        kind: LogKind,
        title: String,
        detail: String,
        packageName: String?,
        atMillis: Long = System.currentTimeMillis(),
    ): LogRecord = LogRecord(
        id = UUID.randomUUID().toString(),
        atMillis = atMillis,
        kind = kind,
        level = LogLevel.ERROR,
        title = title,
        detail = detail,
        packageName = packageName,
        appVersion = appVersion(),
        device = deviceLabel(),
    )

    /** Builds a non-fatal record — a session boundary, a plugin refusal, a system note. */
    fun note(
        kind: LogKind,
        level: LogLevel,
        title: String,
        detail: String,
        packageName: String? = null,
        atMillis: Long = System.currentTimeMillis(),
    ): LogRecord = LogRecord(
        id = UUID.randomUUID().toString(),
        atMillis = atMillis,
        kind = kind,
        level = level,
        title = title,
        detail = detail,
        packageName = packageName,
        appVersion = appVersion(),
        device = deviceLabel(),
    )

    suspend fun setEnabled(on: Boolean): Boolean = withContext(io) {
        runCatching {
            if (!directory.exists()) directory.mkdirs()
            enabledFile.writeText(if (on) "on" else "off")
            _enabled.value = on
            true
        }.getOrDefault(false)
    }

    /**
     * Reads the newest [limit] records, newest first.
     *
     * A malformed line is skipped, not treated as the end of the file: a half-written last line is
     * exactly what a process death leaves behind, and losing every record before it would make the
     * store useless in the one case it exists for.
     */
    suspend fun recent(limit: Int = 300): List<LogRecord> = withContext(io) {
        runCatching {
            val files = logFiles().sortedByDescending { it.name }
            val records = ArrayList<LogRecord>()
            for (file in files) {
                if (records.size >= limit) break
                file.useLines { lines ->
                    lines.forEach { line ->
                        if (records.size >= limit) return@forEach
                        LogRecordCodec.decode(line)?.let(records::add)
                    }
                }
            }
            records.sortedByDescending { it.atMillis }.take(limit)
        }.getOrDefault(emptyList())
    }

    suspend fun clear(): Boolean = withContext(io) {
        runCatching {
            logFiles().forEach { it.delete() }
            true
        }.getOrDefault(false)
    }

    /**
     * Writes [records] to a file a share intent can hand to another app.
     *
     * The `exports/logs` directory is declared to the FileProvider, so the URI is a one-shot read
     * grant rather than a file path the receiving app cannot open.
     */
    suspend fun export(records: List<LogRecord>): File? = withContext(io) {
        runCatching {
            val exports = File(context.filesDir, "exports/logs")
            if (!exports.exists() && !exports.mkdirs()) return@runCatching null
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(exports, "GameCore-logs-$stamp.txt")
            file.writeText(LogRecordCodec.toShareText(records))
            file
        }.getOrNull()
    }

    private fun readEnabled(): Boolean {
        if (!enabledFile.exists()) return true
        return runCatching { enabledFile.readText().trim() != "off" }.getOrDefault(true)
    }

    private fun logFiles(): List<File> =
        directory.listFiles { file -> file.isFile && file.name.endsWith(EXTENSION) }?.toList().orEmpty()

    /**
     * Drops the oldest day files once the store is over either bound.
     *
     * Called under [writeLock] on the writing thread. It walks the directory every append, which is a
     * handful of `stat` calls on an ordinary install; the alternative is a maintenance job that can
     * itself be killed before it runs.
     */
    private fun prune() {
        val cutoff = System.currentTimeMillis() - RETENTION_MILLIS
        logFiles().forEach { file ->
            if (file.lastModified() < cutoff) file.delete()
        }
        val remaining = logFiles().sortedBy { it.name }.toMutableList()
        var total = remaining.sumOf { it.length() }
        while (remaining.size > MAX_FILES && remaining.isNotEmpty()) {
            total -= remaining.first().length()
            remaining.removeAt(0).delete()
        }
        while (total > MAX_TOTAL_BYTES && remaining.isNotEmpty()) {
            total -= remaining.first().length()
            remaining.removeAt(0).delete()
        }
    }

    private fun appVersion(): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName ?: "?"} (${info.longVersionCode})"
    }.getOrDefault("unknown")

    private fun deviceLabel(): String = runCatching {
        "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (API ${android.os.Build.VERSION.SDK_INT})"
    }.getOrDefault("unknown device")

    private fun dayKey(atMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(atMillis))

    private companion object {
        const val DIRECTORY = "logs"
        const val ENABLED_FILE = "enabled"
        const val EXTENSION = ".jsonl"
        const val MAX_FILES = 14
        const val MAX_TOTAL_BYTES = 4L * 1024 * 1024
        const val RETENTION_MILLIS = 14L * 24 * 60 * 60 * 1000
    }
}
