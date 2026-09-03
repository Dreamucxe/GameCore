package com.gamecore.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A recorded session.
 *
 * Every aggregate is nullable in the column as well as in the model, so "the device did not
 * expose CPU statistics during this session" is stored as NULL and read back as absent. A
 * NOT NULL column with a 0 default would make a session on a locked-down device
 * indistinguishable from a session where the game was never on screen, and the history screen
 * would show a graph of zeroes.
 *
 * Note what is not here: no per-app memory figures, no process lists, no window titles. The
 * session record is about the device while a game was running, not about the game.
 */
@Entity(
    tableName = "sessions",
    indices = [Index("package_name"), Index("started_at")],
)
data class SessionEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long,

    @ColumnInfo(name = "package_name")
    val packageName: String,

    @ColumnInfo(name = "game_label")
    val gameLabel: String,

    @ColumnInfo(name = "started_at")
    val startedAtMillis: Long,

    /** NULL while running. A session left open by a crash is repaired on next launch. */
    @ColumnInfo(name = "ended_at")
    val endedAtMillis: Long?,

    @ColumnInfo(name = "battery_start")
    val batteryStartPercent: Int,

    @ColumnInfo(name = "battery_end")
    val batteryEndPercent: Int?,

    @ColumnInfo(name = "was_charging")
    val wasCharging: Boolean,

    @ColumnInfo(name = "avg_cpu")
    val averageCpuPercent: Float?,

    @ColumnInfo(name = "peak_cpu")
    val peakCpuPercent: Float?,

    @ColumnInfo(name = "avg_memory")
    val averageMemoryPercent: Float?,

    @ColumnInfo(name = "peak_memory")
    val peakMemoryPercent: Float?,

    @ColumnInfo(name = "avg_temperature")
    val averageTemperatureDeciCelsius: Int?,

    @ColumnInfo(name = "peak_temperature")
    val peakTemperatureDeciCelsius: Int?,

    @ColumnInfo(name = "avg_refresh_rate")
    val averageRefreshRate: Float?,

    @ColumnInfo(name = "avg_frame_rate")
    val averageFrameRate: Float?,

    @ColumnInfo(name = "avg_latency")
    val averageLatencyMillis: Int?,

    @ColumnInfo(name = "profile_applied")
    val profileApplied: Boolean,

    @ColumnInfo(name = "sample_count")
    val sampleCount: Int,

    /**
     * [com.gamecore.core.model.StopReason] by name, or NULL while the session is running.
     *
     * Stored as the name rather than the ordinal for the reason the mapper's own documentation
     * gives: an ordinal silently becomes a different reason the first time the enum is reordered,
     * and an unrecognised name is recoverable where a wrong answer is not.
     */
    @ColumnInfo(name = "stop_reason")
    val stopReason: String? = null,

    /**
     * The name of the colour preset that was on screen while this session ran, sanitised on
     * the way in like every other user-supplied string, or NULL when the display was left
     * alone. Added in schema version 2, so NULL is also what every session recorded before
     * the colour feature existed reads back as — which is the truth about those sessions.
     */
    @ColumnInfo(name = "color_preset")
    val colorPreset: String? = null,

    /**
     * The same correction as a [com.gamecore.core.model.ColorCodec] string.
     *
     * The name alone would not survive the preset being renamed or deleted, and a report of a
     * session six weeks old should say what the screen was doing, not what a row that no longer
     * exists is called now. One column rather than fourteen because a session's colour reading
     * is written once and read once, and is never filtered on.
     */
    @ColumnInfo(name = "color_values")
    val colorValues: String? = null,
)

/**
 * One sample point in a session.
 *
 * These are the highest-volume rows in the database by two orders of magnitude — one every
 * couple of seconds for the length of a session — so the row is deliberately narrow and every
 * column is a fixed-width primitive. No text, no enums-as-strings, nothing that needs a
 * converter.
 *
 * [elapsedMillis] rather than an absolute timestamp: a graph's x-axis is time-since-start, and
 * storing the offset means the session's own start is the only clock reference the report needs.
 * It also survives the device's clock being changed mid-session, which an absolute timestamp
 * does not.
 */
@Entity(
    tableName = "session_samples",
    indices = [Index("session_id")],
)
data class SessionSampleEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "session_id")
    val sessionId: Long,

    @ColumnInfo(name = "elapsed_millis")
    val elapsedMillis: Long,

    @ColumnInfo(name = "cpu_percent")
    val cpuPercent: Float?,

    @ColumnInfo(name = "memory_percent")
    val memoryPercent: Float?,

    @ColumnInfo(name = "battery_percent")
    val batteryPercent: Int?,

    @ColumnInfo(name = "temperature")
    val temperatureDeciCelsius: Int?,

    @ColumnInfo(name = "refresh_rate")
    val refreshRate: Float?,

    @ColumnInfo(name = "frame_rate")
    val frameRate: Float?,

    @ColumnInfo(name = "latency_millis")
    val latencyMillis: Int?,
)

/**
 * A setting GameCore changed and has to put back.
 *
 * This table is the reason a profile can be undone after the process dies. Applying a profile
 * writes the previous value here *before* it writes the new one; unwinding reads the rows back
 * and restores them. If GameCore is killed mid-session — by the user, by a low-memory kill, by
 * a crash — the rows survive and the next launch restores them, so a device is never left pinned
 * to 120 Hz by an app that is no longer running.
 *
 * [key] and [namespace] are stored as the strings the settings provider uses rather than as an
 * enum name, so a restore still works for a key that a later version of GameCore has removed
 * from its allow-list. Restoring a value the app itself wrote is safe regardless of whether the
 * app would still write it.
 */
@Entity(
    tableName = "restore_points",
    primaryKeys = ["namespace", "key"],
)
data class RestorePointEntity(
    @ColumnInfo(name = "namespace")
    val namespace: String,

    @ColumnInfo(name = "key")
    val key: String,

    /** The value before GameCore touched it. NULL means the key was unset. */
    @ColumnInfo(name = "previous_value")
    val previousValue: String?,

    /** Which profile caused this, for the UI to explain what is pending. */
    @ColumnInfo(name = "package_name")
    val packageName: String?,

    @ColumnInfo(name = "recorded_at")
    val recordedAtMillis: Long,
)
