package com.gamecore.core.export

import com.gamecore.aimlab.engine.CsvWriter
import com.gamecore.core.model.GameSession
import com.gamecore.core.model.SessionSample
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The §19 per-sample export mapper, asserted on content the same way [com.gamecore.core.overlay.ApplyDiff]'s
 * test asserts its line: on the bytes the file will actually carry, not on a paraphrase of them.
 *
 * The rule under test is the aggregate exporter's honesty rule carried down to one sample: **blank, never
 * zero**, for a reading a sample did not take. In CSV that is an empty cell between two commas; in JSON it
 * is a key that is simply not present, which says "not measured" rather than a `0` that reads as a measured
 * nothing. Only the elapsed time is on every sample, because every sample has a time.
 *
 * These tests are only meaningful because a real `org.json` is on the unit-test classpath — the stub inside
 * `android.jar` would let the JSON assertions pass without a character being parsed.
 */
class SampleExportTest {

    private val session = GameSession(
        id = 42L,
        packageName = "com.example.game",
        gameLabel = "Example Game",
        startedAtMillis = 1_700_000_000_000L,
        batteryStartPercent = 80,
    )

    /** The header and every row carry the same number of columns, or a reader shifts every field. */
    @Test
    fun `every row has one cell per header column`() {
        val full = SessionSample(
            sessionId = 42L,
            elapsedMillis = 2_000L,
            cpuPercent = 12.34f,
            memoryPercent = 55.5f,
            batteryPercent = 79,
            temperatureDeciCelsius = 324,
            refreshRate = 120f,
            frameRate = 59.9f,
            latencyMillis = 24,
        )
        val lines = SampleExport.csv(listOf(full)).trimEnd('\n').split('\n')
        val headerCols = lines.first().split(',').size
        assertEquals(SampleExport.CSV_HEADER.size, headerCols)
        lines.drop(1).forEach { row ->
            assertEquals("row column count matches header", headerCols, row.split(',').size)
        }
    }

    @Test
    fun `a full sample formats each reading to the exporter's own precision`() {
        val sample = SessionSample(
            sessionId = 42L,
            elapsedMillis = 2_000L,
            cpuPercent = 12.34f,
            memoryPercent = 55.5f,
            batteryPercent = 79,
            temperatureDeciCelsius = 324,
            refreshRate = 120f,
            frameRate = 59.94f,
            latencyMillis = 24,
        )
        val cells = SampleExport.csvLine(sample).split(',')
        assertEquals("2000", cells[0])   // elapsed_ms, raw
        assertEquals("2.0", cells[1])    // elapsed_seconds, one decimal
        assertEquals("12.3", cells[2])   // cpu_percent, one decimal
        assertEquals("55.5", cells[3])
        assertEquals("79", cells[4])     // battery_percent, integer
        assertEquals("32.4", cells[5])   // temperature deci → celsius
        assertEquals("120.0", cells[6])
        assertEquals("59.9", cells[7])
        assertEquals("24", cells[8])     // latency_ms, integer
    }

    @Test
    fun `a reading a sample never took is a blank cell, never a zero`() {
        val bare = SessionSample(sessionId = 42L, elapsedMillis = 4_000L)
        val cells = SampleExport.csvLine(bare).split(',')
        assertEquals("4000", cells[0])
        assertEquals("4.0", cells[1])
        // Every optional reading is empty — not "0", which would read as a measurement of nothing.
        listOf(2, 3, 4, 5, 6, 7, 8).forEach { column ->
            assertEquals("column $column is blank", "", cells[column])
        }
    }

    @Test
    fun `csv is a header line and then one line per sample, in order`() {
        val a = SessionSample(sessionId = 42L, elapsedMillis = 0L, cpuPercent = 10f)
        val b = SessionSample(sessionId = 42L, elapsedMillis = 2_000L, cpuPercent = 20f)
        val expected = CsvWriter.row(SampleExport.CSV_HEADER) + "\n" +
            SampleExport.csvLine(a) + "\n" +
            SampleExport.csvLine(b) + "\n"
        assertEquals(expected, SampleExport.csv(listOf(a, b)))
    }

    @Test
    fun `an empty session is a header and no rows`() {
        assertEquals(CsvWriter.row(SampleExport.CSV_HEADER) + "\n", SampleExport.csv(emptyList()))
    }

    @Test
    fun `json wraps the samples in a versioned envelope that names the session once`() {
        val samples = listOf(
            SessionSample(sessionId = 42L, elapsedMillis = 0L, cpuPercent = 10f),
            SessionSample(sessionId = 42L, elapsedMillis = 2_000L, cpuPercent = 20f),
        )
        val root = JSONObject(SampleExport.json(session, samples))
        assertEquals(SampleExport.FORMAT, root.getString("format"))
        assertEquals(SampleExport.VERSION, root.getInt("version"))
        val meta = root.getJSONObject("session")
        assertEquals("Example Game", meta.getString("game"))
        assertEquals("com.example.game", meta.getString("package"))
        assertEquals(2, meta.getInt("sample_count"))
        assertEquals(2, root.getJSONArray("samples").length())
    }

    @Test
    fun `json omits the key for a reading a sample never took, rather than writing a zero`() {
        val bare = SessionSample(sessionId = 42L, elapsedMillis = 6_000L)
        val obj = JSONObject(SampleExport.json(session, listOf(bare)))
            .getJSONArray("samples")
            .getJSONObject(0)
        // The one thing every sample has.
        assertEquals(6_000L, obj.getLong("elapsed_ms"))
        // Everything not measured is absent — a reader sees "no such reading", not a false zero.
        listOf(
            "cpu_percent", "memory_percent", "battery_percent", "temperature_celsius",
            "refresh_rate_hz", "frame_rate_fps", "latency_ms",
        ).forEach { key ->
            assertFalse("$key should be absent, not zero", obj.has(key))
        }
    }

    @Test
    fun `json numbers do not trail float widening noise`() {
        val sample = SessionSample(
            sessionId = 42L,
            elapsedMillis = 0L,
            cpuPercent = 12.3f,
            temperatureDeciCelsius = 324,
        )
        val obj = JSONObject(SampleExport.json(session, listOf(sample)))
            .getJSONArray("samples")
            .getJSONObject(0)
        assertEquals(12.3, obj.getDouble("cpu_percent"), 0.0001)
        assertEquals(32.4, obj.getDouble("temperature_celsius"), 0.0001)
        // Rounded to one decimal, so the raw string is clean rather than 12.30000019.
        assertTrue(obj.getDouble("cpu_percent") < 12.31)
    }
}
