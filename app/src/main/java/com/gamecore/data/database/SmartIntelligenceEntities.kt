package com.gamecore.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One completed benchmark capture, added in schema version 23.
 *
 * The table stores the summary, the per-window frame rates the comparison needs, and the names of
 * every metric that could not be measured. It deliberately does **not** store the raw sampling
 * points: those are high-frequency readings whose only use is the summary, and keeping a row per
 * two seconds for every run would grow without bound while adding nothing a later screen can use.
 *
 * Every metric column is nullable, and NULL means the source did not report it. A zero would be a
 * claim — "the device used 0% CPU" — and this app is built around not making claims it cannot
 * support. [unavailable] is a newline-separated list of the reasons behind the NULLs, so the
 * explanation survives the process that captured it.
 *
 * [fpsWindows] is a comma-separated list of real per-window average frame rates in capture order.
 * It is the sample set the Welch test compares; the summary's average alone has no variance and
 * cannot support a statistical verdict. It is empty when frame timing was unavailable, never
 * filled with an estimate.
 */
@Entity(
    tableName = "smart_benchmarks",
    indices = [Index("started_at"), Index("game_package")],
)
data class SmartBenchmarkEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long,

    /** A [com.gamecore.core.model.BenchmarkProfile] name. */
    @ColumnInfo(name = "profile")
    val profile: String,

    @ColumnInfo(name = "game_package")
    val gamePackage: String?,

    @ColumnInfo(name = "game_label")
    val gameLabel: String?,

    /** An [com.gamecore.core.model.OptimizationGoal] name; unknown names read as the default. */
    @ColumnInfo(name = "goal")
    val goal: String,

    @ColumnInfo(name = "started_at")
    val startedAtMillis: Long,

    @ColumnInfo(name = "ended_at")
    val endedAtMillis: Long,

    @ColumnInfo(name = "sample_count")
    val sampleCount: Int,

    @ColumnInfo(name = "duration_ms")
    val durationMillis: Long,

    @ColumnInfo(name = "avg_fps")
    val averageFps: Float?,

    @ColumnInfo(name = "lowest_window_fps")
    val lowestWindowFps: Float?,

    @ColumnInfo(name = "one_percent_low_fps")
    val onePercentLowFps: Float?,

    @ColumnInfo(name = "frame_variation_percent")
    val frameVariationPercent: Float?,

    @ColumnInfo(name = "p95_frame_ms")
    val p95FrameMillis: Float?,

    @ColumnInfo(name = "p99_frame_ms")
    val p99FrameMillis: Float?,

    @ColumnInfo(name = "jank_percent")
    val jankPercent: Float?,

    @ColumnInfo(name = "avg_cpu")
    val averageCpuPercent: Float?,

    @ColumnInfo(name = "peak_cpu")
    val peakCpuPercent: Float?,

    @ColumnInfo(name = "cpu_source")
    val cpuSource: String?,

    @ColumnInfo(name = "avg_gpu")
    val averageGpuPercent: Float?,

    @ColumnInfo(name = "peak_gpu")
    val peakGpuPercent: Float?,

    @ColumnInfo(name = "gpu_source")
    val gpuSource: String?,

    @ColumnInfo(name = "avg_memory")
    val averageMemoryPercent: Float?,

    @ColumnInfo(name = "peak_memory")
    val peakMemoryPercent: Float?,

    @ColumnInfo(name = "memory_source")
    val memorySource: String?,

    @ColumnInfo(name = "avg_temperature")
    val averageTemperatureDeciCelsius: Int?,

    @ColumnInfo(name = "peak_temperature")
    val peakTemperatureDeciCelsius: Int?,

    @ColumnInfo(name = "temperature_source")
    val temperatureSource: String?,

    @ColumnInfo(name = "start_battery")
    val startBatteryPercent: Int?,

    @ColumnInfo(name = "end_battery")
    val endBatteryPercent: Int?,

    @ColumnInfo(name = "battery_drain_per_hour")
    val batteryDrainPerHour: Float?,

    @ColumnInfo(name = "battery_source")
    val batterySource: String?,

    @ColumnInfo(name = "avg_refresh_rate")
    val averageRefreshRateHz: Float?,

    @ColumnInfo(name = "refresh_rate_source")
    val refreshRateSource: String?,

    @ColumnInfo(name = "fps_source")
    val fpsSource: String?,

    /** Reasons for every absent metric, newline-separated. Never empty as a placeholder. */
    @ColumnInfo(name = "unavailable")
    val unavailable: String,

    /** Comma-separated real per-window average FPS values, in capture order. */
    @ColumnInfo(name = "fps_windows")
    val fpsWindows: String,

    @ColumnInfo(name = "baseline_run_id")
    val baselineRunId: Long?,
)

/**
 * One decision the user made about a recommendation, and what the measurement said afterwards.
 *
 * This is the entire "learning" store: a local log, one row per event, never uploaded and never
 * joined with anything that identifies a person. It exists so confidence can be adjusted by what
 * actually happened on this device instead of by an assumption about devices in general.
 *
 * [beforeValue] and [afterValue] are in the unit named by [metricName], or NULL when the event was
 * a decision with no measurement attached (a dismissal, an application before any run). They are
 * nullable for the same reason every other metric here is: "not measured" must remain
 * distinguishable from zero.
 */
@Entity(
    tableName = "smart_learning_records",
    indices = [Index("action_key"), Index("game_package"), Index("created_at")],
)
data class SmartLearningRecordEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long,

    /** The stable key of a [com.gamecore.core.model.RecommendationAction]. */
    @ColumnInfo(name = "action_key")
    val actionKey: String,

    @ColumnInfo(name = "game_package")
    val gamePackage: String?,

    /** An [com.gamecore.core.model.OptimizationGoal] name. */
    @ColumnInfo(name = "goal")
    val goal: String,

    /** A [com.gamecore.core.model.LearningDecision] name. */
    @ColumnInfo(name = "decision")
    val decision: String,

    /** A [com.gamecore.core.model.LearningOutcome] name. */
    @ColumnInfo(name = "outcome")
    val outcome: String,

    @ColumnInfo(name = "metric_name")
    val metricName: String?,

    @ColumnInfo(name = "before_value")
    val beforeValue: Float?,

    @ColumnInfo(name = "after_value")
    val afterValue: Float?,

    @ColumnInfo(name = "note")
    val note: String?,

    /**
     * The benchmark run a measured outcome came from, when it came from one.
     *
     * It makes recording a comparison idempotent: the same stored run can be inspected again after
     * the process restarts without adding a second outcome that would double-count its evidence.
     */
    @ColumnInfo(name = "source_run_id")
    val sourceRunId: Long?,

    @ColumnInfo(name = "created_at")
    val createdAtMillis: Long,
)
