package com.gamecore.core.plugin

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Part WF-7 gate: the automation history store contract.
 *
 * Exercised against [InMemoryPluginAutomationHistoryStore], the in-memory double the whole
 * test module binds in place of the database-backed store — the same split [PluginGrantStore]
 * uses, where the port is pure and only the device-backed implementation needs a device. The
 * fake is kept honest to the contract here (id assignment, newest-first order, the retention
 * cap) so a test that depends on it elsewhere depends on real behaviour, not a stub.
 */
class PluginAutomationHistoryStoreTest {

    /**
     * A faithful in-memory [PluginAutomationHistoryStore] for tests: a single append-only
     * list, stable monotonically-increasing ids, newest-first reads, and the same
     * oldest-first trim the contract promises past the cap. [maxRetained] is a constructor
     * knob — defaulting to the production [PluginAutomationHistoryStore.MAX_RETAINED] — so the
     * retention test can drive the trim with a handful of lines rather than five hundred,
     * exactly as [PluginAuditLog] exposes its ring capacity for the same reason. Not
     * synchronised and not durable: it is a test double, driven from a single [runBlocking].
     */
    private class InMemoryPluginAutomationHistoryStore(
        private val maxRetained: Int = PluginAutomationHistoryStore.MAX_RETAINED,
    ) : PluginAutomationHistoryStore {

        private val records = ArrayDeque<AutomationHistoryRecord>()
        private var nextId = 1L

        override suspend fun append(entry: AuditEntry, durationMs: Long?) {
            records.addLast(
                AutomationHistoryRecord(
                    id = nextId++,
                    timestampMillis = entry.timestamp,
                    pluginId = entry.pluginId,
                    operation = entry.operation,
                    outcome = entry.outcome.name,
                    detail = entry.detail,
                    durationMillis = durationMs,
                )
            )
            while (records.size > maxRetained) records.removeFirst()
        }

        override suspend fun recent(pluginId: String?, limit: Int): List<AutomationHistoryRecord> {
            if (limit <= 0) return emptyList()
            val scoped = if (pluginId == null) records.toList() else records.filter { it.pluginId == pluginId }
            return scoped.asReversed().take(limit)
        }

        override suspend fun clear(pluginId: String?) {
            if (pluginId == null) records.clear() else records.removeAll { it.pluginId == pluginId }
        }
    }

    private fun entry(
        pluginId: String,
        operation: String = "op",
        outcome: AuditEntry.Outcome = AuditEntry.Outcome.ALLOWED,
        detail: String = "",
        timestamp: Long = 0L,
    ) = AuditEntry(timestamp, pluginId, operation, outcome, detail)

    @Test
    fun `append copies every entry field and the duration onto the stored record`() = runBlocking {
        val store = InMemoryPluginAutomationHistoryStore()
        store.append(entry("p", operation = "setRefreshRate", outcome = AuditEntry.Outcome.CLAMPED, detail = "90", timestamp = 1234L), durationMs = 42L)

        val record = store.recent().single()
        assertTrue("id is assigned by the store", record.id > 0L)
        assertEquals(1234L, record.timestampMillis)
        assertEquals("p", record.pluginId)
        assertEquals("setRefreshRate", record.operation)
        assertEquals("CLAMPED", record.outcome)
        assertEquals("90", record.detail)
        assertEquals(42L, record.durationMillis)
    }

    @Test
    fun `a null duration is preserved as null`() = runBlocking {
        val store = InMemoryPluginAutomationHistoryStore()
        store.append(entry("p"), durationMs = null)
        assertNull(store.recent().single().durationMillis)
    }

    @Test
    fun `each appended record gets a fresh increasing id`() = runBlocking {
        val store = InMemoryPluginAutomationHistoryStore()
        store.append(entry("p"), null)
        store.append(entry("p"), null)
        store.append(entry("p"), null)
        // newest first, so ids descend
        assertEquals(listOf(3L, 2L, 1L), store.recent().map { it.id })
    }

    @Test
    fun `recent with a plugin id returns only that plugin's lines`() = runBlocking {
        val store = InMemoryPluginAutomationHistoryStore()
        store.append(entry("a"), null)
        store.append(entry("b"), null)
        store.append(entry("a"), null)

        assertEquals(listOf("a", "a"), store.recent(pluginId = "a").map { it.pluginId })
        assertEquals(listOf("b"), store.recent(pluginId = "b").map { it.pluginId })
    }

    @Test
    fun `recent with no plugin id reads across every plugin`() = runBlocking {
        val store = InMemoryPluginAutomationHistoryStore()
        store.append(entry("a"), null)
        store.append(entry("b"), null)
        assertEquals(2, store.recent().size)
    }

    @Test
    fun `recent returns newest first`() = runBlocking {
        val store = InMemoryPluginAutomationHistoryStore()
        store.append(entry("p", operation = "first"), null)
        store.append(entry("p", operation = "second"), null)
        store.append(entry("p", operation = "third"), null)
        assertEquals(listOf("third", "second", "first"), store.recent().map { it.operation })
    }

    @Test
    fun `recent respects the limit, keeping the newest`() = runBlocking {
        val store = InMemoryPluginAutomationHistoryStore()
        store.append(entry("p", operation = "first"), null)
        store.append(entry("p", operation = "second"), null)
        store.append(entry("p", operation = "third"), null)

        val two = store.recent(limit = 2)
        assertEquals(listOf("third", "second"), two.map { it.operation })
    }

    @Test
    fun `a non-positive limit returns nothing`() = runBlocking {
        val store = InMemoryPluginAutomationHistoryStore()
        store.append(entry("p"), null)
        assertTrue(store.recent(limit = 0).isEmpty())
    }

    @Test
    fun `clear with a plugin id drops only that plugin's lines`() = runBlocking {
        val store = InMemoryPluginAutomationHistoryStore()
        store.append(entry("a"), null)
        store.append(entry("b"), null)

        store.clear(pluginId = "a")
        assertTrue(store.recent(pluginId = "a").isEmpty())
        assertEquals(listOf("b"), store.recent(pluginId = "b").map { it.pluginId })
    }

    @Test
    fun `clear with no plugin id empties the whole log`() = runBlocking {
        val store = InMemoryPluginAutomationHistoryStore()
        store.append(entry("a"), null)
        store.append(entry("b"), null)

        store.clear()
        assertTrue(store.recent().isEmpty())
    }

    @Test
    fun `the retention cap drops the oldest line first`() = runBlocking {
        val store = InMemoryPluginAutomationHistoryStore(maxRetained = 3)
        store.append(entry("p", operation = "one"), null)
        store.append(entry("p", operation = "two"), null)
        store.append(entry("p", operation = "three"), null)
        store.append(entry("p", operation = "four"), null)

        val kept = store.recent()
        assertEquals(3, kept.size)
        assertEquals(listOf("four", "three", "two"), kept.map { it.operation })
        // the trimmed line's id is never reused — "four" kept its id 4
        assertEquals(4L, kept.first().id)
    }
}
