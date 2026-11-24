package com.gamecore.core.diagnostics

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What a diagnostic record is about.
 *
 * The set is deliberately small and closed. A log screen that groups by an open-ended tag is a log
 * screen where every feature invents its own vocabulary and the filter stops meaning anything.
 */
enum class LogKind(val id: String, val label: String) {
    APP_CRASH("app_crash", "GameCore crash"),
    GAME_CRASH("game_crash", "Game crash"),
    ANR("anr", "Not responding"),
    GAME_SESSION("game_session", "Game session"),
    PLUGIN("plugin", "Plugin"),
    SYSTEM("system", "System"),
    ;

    companion object {
        fun fromId(raw: String?): LogKind =
            entries.firstOrNull { it.id == raw?.trim() } ?: SYSTEM
    }
}

/** How serious one record is. Kept separate from [LogKind] so a filter can mean either axis. */
enum class LogLevel(val id: String, val label: String) {
    INFO("info", "Info"),
    WARNING("warning", "Warning"),
    ERROR("error", "Error"),
    ;

    companion object {
        fun fromId(raw: String?): LogLevel =
            entries.firstOrNull { it.id == raw?.trim() } ?: INFO
    }
}

/**
 * One durable diagnostic record.
 *
 * [detail] is the part a person copies into a bug report: a stack trace, a session summary, or a
 * plugin's refusal. It is capped when encoded rather than when rendered, so a runaway stack cannot
 * make one record larger than the whole store's budget.
 *
 * [appVersion] and [device] are captured when the record is written, not read back at display time.
 * A log is evidence about the build that produced it; labelling an old crash with today's version
 * would be worse than leaving the field blank.
 */
data class LogRecord(
    val id: String,
    val atMillis: Long,
    val kind: LogKind,
    val level: LogLevel,
    val title: String,
    val detail: String,
    val packageName: String? = null,
    val appVersion: String = "",
    val device: String = "",
) {
    val shortTime: String
        get() = SimpleDateFormat("MMM d, HH:mm:ss", Locale.US).format(Date(atMillis))
}

/**
 * The on-disk shape of one record, one JSON object per line.
 *
 * A line-oriented format rather than a database table on purpose: the crash writer runs on a dying
 * process and must not open SQLite, and a user sharing one crash should be able to send a file that
 * is readable without GameCore. Unknown keys are ignored on read, so a future field can be added
 * without making every older record unreadable.
 */
object LogRecordCodec {

    private const val KEY_ID = "id"
    private const val KEY_AT = "at"
    private const val KEY_KIND = "kind"
    private const val KEY_LEVEL = "level"
    private const val KEY_TITLE = "title"
    private const val KEY_DETAIL = "detail"
    private const val KEY_PACKAGE = "package"
    private const val KEY_APP_VERSION = "appVersion"
    private const val KEY_DEVICE = "device"

    /** Enough for a stack trace from a game engine, small enough that one record cannot fill the store. */
    const val MAX_DETAIL_LENGTH = 32_000

    fun encode(record: LogRecord): String = JSONObject()
        .put(KEY_ID, record.id)
        .put(KEY_AT, record.atMillis)
        .put(KEY_KIND, record.kind.id)
        .put(KEY_LEVEL, record.level.id)
        .put(KEY_TITLE, record.title.take(240))
        .put(KEY_DETAIL, record.detail.take(MAX_DETAIL_LENGTH))
        .put(KEY_PACKAGE, record.packageName)
        .put(KEY_APP_VERSION, record.appVersion)
        .put(KEY_DEVICE, record.device)
        .toString()

    fun decode(line: String): LogRecord? {
        if (line.isBlank()) return null
        return runCatching {
            val value = JSONObject(line)
            val id = value.optString(KEY_ID).trim()
            val at = value.optLong(KEY_AT, 0L)
            if (id.isEmpty() || at <= 0L) return@runCatching null
            val title = value.optString(KEY_TITLE).trim()
            if (title.isEmpty()) return@runCatching null
            LogRecord(
                id = id,
                atMillis = at,
                kind = LogKind.fromId(value.optString(KEY_KIND)),
                level = LogLevel.fromId(value.optString(KEY_LEVEL)),
                title = title,
                detail = value.optString(KEY_DETAIL).take(MAX_DETAIL_LENGTH),
                packageName = value.optString(KEY_PACKAGE).trim().takeIf { it.isNotEmpty() },
                appVersion = value.optString(KEY_APP_VERSION).trim(),
                device = value.optString(KEY_DEVICE).trim(),
            )
        }.getOrNull()
    }

    /**
     * The text a person copies or shares.
     *
     * A header first, because a file of stack traces with no build or device line is the thing a
     * developer cannot act on. Records keep their own order, which is the caller's order — the local
     * store hands them back newest first.
     */
    fun toShareText(records: List<LogRecord>): String {
        val exported = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return buildString {
            appendLine("GameCore diagnostic logs")
            appendLine("Exported: $exported")
            appendLine("Records: ${records.size}")
            if (records.isNotEmpty()) {
                appendLine("Build: ${records.first().appVersion}")
                appendLine("Device: ${records.first().device}")
            }
            records.forEach { record ->
                appendLine()
                appendLine("----------------------------------------------------------------")
                appendLine(
                    "[${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(record.atMillis))}] " +
                        "${record.kind.label} · ${record.level.label}",
                )
                appendLine(record.title)
                if (!record.packageName.isNullOrBlank()) appendLine("Package: ${record.packageName}")
                if (record.detail.isNotBlank()) {
                    appendLine()
                    appendLine(record.detail)
                }
            }
        }
    }
}
