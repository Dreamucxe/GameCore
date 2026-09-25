package com.gamecore.core.export

import com.gamecore.aimlab.engine.CsvWriter
import com.gamecore.aimlab.engine.NumberText
import com.gamecore.core.common.Formatters
import com.gamecore.core.model.GameSession
import com.gamecore.core.model.SessionSample
import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns a session's raw per-sample time series into the CSV or JSON a user exports (§19).
 *
 * The aggregate exporter ([com.gamecore.data.repository.SessionExporter]) writes one row per session; this
 * writes one row per *sample* for a single session — the series a graph is drawn from — so the two are the
 * summary and the detail of the same recording.
 *
 * Pure and android-free so the mapping is unit-tested on the JVM, the same shape [CsvWriter] and
 * [com.gamecore.aimlab.engine.ConfigCodec] follow. `org.json` lives in `android.jar` at runtime and is
 * supplied as a real implementation on the test classpath, so no `android.*` import appears here.
 *
 * The honesty rule the aggregate exporter states is kept to the letter: **blank, never zero**, for a
 * reading a sample never took. In CSV that is an empty cell; in JSON it is an absent key, which is the
 * same claim — this sample carries no such measurement — rather than a `0` that reads as one that measured
 * nothing. Only [SessionSample.elapsedMillis] is always present, because every sample has a time.
 */
object SampleExport {

    const val FORMAT = "gamecore.samples"
    const val VERSION = 1

    /** The columns, in the order a spreadsheet reads them left to right. */
    val CSV_HEADER = listOf(
        "elapsed_ms",
        "elapsed_seconds",
        "cpu_percent",
        "memory_percent",
        "battery_percent",
        "temperature_celsius",
        "refresh_rate_hz",
        "frame_rate_fps",
        "latency_ms",
    )

    /** One header line then one line per sample, in the order they were recorded. */
    fun csv(samples: List<SessionSample>): String =
        CsvWriter.document(CSV_HEADER, samples.map(::csvRow))

    /**
     * One escaped CSV line for a single sample, with no trailing newline.
     *
     * The row primitive the streaming exporter writes one sample at a time, so a marathon session's tens
     * of thousands of rows never have to exist as one concatenated string. [csv] builds the same lines the
     * same way, so what a test asserts and what the file contains cannot drift apart.
     */
    fun csvLine(sample: SessionSample): String = CsvWriter.row(csvRow(sample))

    private fun csvRow(sample: SessionSample): List<String> = listOf(
        sample.elapsedMillis.toString(),
        NumberText.fixed(sample.elapsedMillis / 1_000f, decimals = 1),
        oneDecimal(sample.cpuPercent),
        oneDecimal(sample.memoryPercent),
        sample.batteryPercent?.toString().orEmpty(),
        deciCelsius(sample.temperatureDeciCelsius),
        oneDecimal(sample.refreshRate),
        oneDecimal(sample.frameRate),
        sample.latencyMillis?.toString().orEmpty(),
    )

    /**
     * The JSON form: a versioned envelope, the session named once, then the samples as an array.
     *
     * The session context is written once at the top rather than repeated on every sample the way a
     * flat CSV would have to — the file is one session's series, and its name says which. A reading a
     * sample never took is left out of that sample's object; the reader sees the key is absent, not a
     * zero it would have to know to distrust.
     */
    fun json(session: GameSession, samples: List<SessionSample>): String {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        root.put(
            "session",
            JSONObject().apply {
                put("game", session.gameLabel)
                put("package", session.packageName)
                put("started", Formatters.iso8601(session.startedAtMillis))
                put("sample_count", samples.size)
            },
        )
        root.put("samples", JSONArray().apply { samples.forEach { put(jsonSample(it)) } })
        return root.toString()
    }

    private fun jsonSample(sample: SessionSample): JSONObject = JSONObject().apply {
        put("elapsed_ms", sample.elapsedMillis)
        sample.cpuPercent?.let { put("cpu_percent", round1(it)) }
        sample.memoryPercent?.let { put("memory_percent", round1(it)) }
        sample.batteryPercent?.let { put("battery_percent", it) }
        sample.temperatureDeciCelsius?.let { put("temperature_celsius", it / 10.0) }
        sample.refreshRate?.let { put("refresh_rate_hz", round1(it)) }
        sample.frameRate?.let { put("frame_rate_fps", round1(it)) }
        sample.latencyMillis?.let { put("latency_ms", it) }
    }

    /** An empty cell for an absent reading, never a zero — a blank cell is not a measurement. */
    private fun oneDecimal(value: Float?): String =
        value?.let { NumberText.fixed(it, decimals = 1) } ?: ""

    /** Deci-Celsius to one-decimal Celsius, matching the aggregate exporter's own conversion. */
    private fun deciCelsius(value: Int?): String =
        value?.let { "${it / 10}.${kotlin.math.abs(it % 10)}" } ?: ""

    /** One-decimal double for JSON, so a float widened to double does not trail `0000019` behind it. */
    private fun round1(value: Float): Double = Math.round(value * 10.0) / 10.0
}
