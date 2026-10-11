package com.gamecore.ui.plugins

import com.gamecore.core.plugin.AutomationHistoryRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure reducers behind the automation-history screen, pinned on the JVM with hand-built records and
 * no Compose, Android or coroutines. A filter that quietly matched the wrong field, a sort that was not
 * stable, or an outcome label that dropped an unrecognised name are all invisible on a screenshot and a
 * plain assertion away from being caught, so they are caught here.
 */
class PluginAutomationHistoryStateTest {

    private fun record(
        id: Long = 1L,
        timestampMillis: Long = id,
        pluginId: String = "com.example.plugin",
        operation: String = "op",
        outcome: String = "ALLOWED",
        detail: String = "",
        durationMillis: Long? = null,
    ) = AutomationHistoryRecord(
        id = id,
        timestampMillis = timestampMillis,
        pluginId = pluginId,
        operation = operation,
        outcome = outcome,
        detail = detail,
        durationMillis = durationMillis,
    )
    @Test
    fun `outcomeLabel words known names, title-cases unknown, and falls back on blank`() {
        assertEquals("Allowed", outcomeLabel("ALLOWED"))
        assertEquals("Rate limited", outcomeLabel("RATE_LIMITED"))
        // An outcome written by a newer build is still readable, never dropped.
        assertEquals("Quarantined pending review", outcomeLabel("QUARANTINED_PENDING_REVIEW"))
        assertEquals("Other", outcomeLabel(""))
        assertEquals("Other", outcomeLabel("   "))
    }

    @Test
    fun `isKnownOutcome tracks the audit enum names exactly`() {
        assertTrue(isKnownOutcome("ALLOWED"))
        assertTrue(isKnownOutcome("RATE_LIMITED"))
        assertFalse(isKnownOutcome("WAT"))
        assertFalse(isKnownOutcome("allowed"))
    }

    @Test
    fun `outcomesPresent lists known outcomes first in enum order then unknown names sorted`() {
        val records = listOf(
            record(id = 1, outcome = "ERROR"),
            record(id = 2, outcome = "ZEBRA"),
            record(id = 3, outcome = "ALLOWED"),
            record(id = 4, outcome = "APPLE"),
            record(id = 5, outcome = "ALLOWED"),
        )
        // ALLOWED precedes ERROR by enum order; the two unknowns follow, sorted A to Z; no duplicates.
        assertEquals(listOf("ALLOWED", "ERROR", "APPLE", "ZEBRA"), outcomesPresent(records))
    }
    @Test
    fun `filterByOutcome keeps everything on null and exact-matches the stored string otherwise`() {
        val records = listOf(
            record(id = 1, outcome = "ALLOWED"),
            record(id = 2, outcome = "DENIED"),
            record(id = 3, outcome = "ALLOWED"),
        )
        assertEquals(3, filterByOutcome(records, null).size)
        assertEquals(listOf(1L, 3L), filterByOutcome(records, "ALLOWED").map { it.id })
        // Exact match on the stored name: a differently-cased string is not the same outcome.
        assertTrue(filterByOutcome(records, "allowed").isEmpty())
    }

    @Test
    fun `matchesSearch is case-insensitive over operation, detail and plugin id, and blank matches all`() {
        val r = record(operation = "StartOverlay", detail = "clamped to 240", pluginId = "com.acme.tool")
        assertTrue(matchesSearch(r, ""))
        assertTrue(matchesSearch(r, "   "))
        assertTrue(matchesSearch(r, "startoverlay"))
        assertTrue(matchesSearch(r, "CLAMPED"))
        assertTrue(matchesSearch(r, "acme"))
        assertFalse(matchesSearch(r, "nope"))
        // The outcome is not one of the searched fields.
        val s = record(operation = "x", detail = "y", pluginId = "z", outcome = "ALLOWED")
        assertFalse(matchesSearch(s, "allowed"))
    }

    @Test
    fun `filterBySearch keeps all on a blank query and narrows otherwise`() {
        val records = listOf(record(id = 1, operation = "alpha"), record(id = 2, operation = "beta"))
        assertEquals(2, filterBySearch(records, "   ").size)
        assertEquals(listOf(2L), filterBySearch(records, "BET").map { it.id })
    }
    @Test
    fun `sortByTimestamp orders by timestamp then id, stable in both directions`() {
        val a = record(id = 10, timestampMillis = 100)
        val b = record(id = 11, timestampMillis = 200)
        val c = record(id = 12, timestampMillis = 200) // shares b's millisecond
        val input = listOf(a, b, c)
        // Newest first: the 200s precede the 100; within the tie the higher (later) id leads.
        assertEquals(listOf(12L, 11L, 10L), sortByTimestamp(input, newestFirst = true).map { it.id })
        // Oldest first: the 100 leads; within the tie the lower (earlier) id leads.
        assertEquals(listOf(10L, 11L, 12L), sortByTimestamp(input, newestFirst = false).map { it.id })
    }

    @Test
    fun `visibleRecords filters by outcome, then by search, then sorts`() {
        val records = listOf(
            record(id = 1, timestampMillis = 100, outcome = "ALLOWED", operation = "startOverlay"),
            record(id = 2, timestampMillis = 200, outcome = "DENIED", operation = "startOverlay"),
            record(id = 3, timestampMillis = 300, outcome = "ALLOWED", operation = "stopOverlay"),
            record(id = 4, timestampMillis = 400, outcome = "ALLOWED", operation = "startOverlay"),
        )
        val state = PluginAutomationHistoryUiState(
            phase = LoadPhase.Ready,
            records = records,
            query = "start",
            outcomeFilter = "ALLOWED",
            newestFirst = true,
        )
        // ALLOWED -> {1,3,4}; "start" -> {1,4}; newest first -> [4,1].
        assertEquals(listOf(4L, 1L), visibleRecords(state).map { it.id })
    }

    @Test
    fun `totalCount and hasRecords read the loaded set, not the visible one`() {
        val empty = PluginAutomationHistoryUiState(phase = LoadPhase.Ready)
        assertEquals(0, empty.totalCount)
        assertFalse(empty.hasRecords)
        val loaded = PluginAutomationHistoryUiState(
            phase = LoadPhase.Ready,
            records = listOf(record(id = 1), record(id = 2)),
            query = "nomatch",
        )
        assertEquals(2, loaded.totalCount)
        assertTrue(loaded.hasRecords)
        // The counts stand even when the search hides every row.
        assertTrue(visibleRecords(loaded).isEmpty())
    }

}
