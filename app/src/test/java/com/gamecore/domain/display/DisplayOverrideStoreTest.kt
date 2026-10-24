package com.gamecore.domain.display

import com.gamecore.core.model.DisplaySize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pending record, and the store that keeps it.
 *
 * A map stands in for `SharedPreferences`, so every case here runs on a JVM with no emulator, no
 * Robolectric and no instrumented run. That matters because this is the one file the app reads at
 * process start, before the encrypted database is keyed, and the format is the part most likely to be
 * wrong — a row that cannot be read is a display that cannot be put back.
 */
class DisplayOverrideStoreTest {

    private val applied = 1_000_000_000L

    private fun store() = DisplayOverrideStore(InMemoryPendingOverridePersistence())

    // ────────────────────────────────────────────────────────────── round trip

    @Test
    fun `a recorded row reads back with everything it was given`() {
        val store = store()

        val row = store.record(
            previousValue = "1080x2400",
            requested = DisplaySize(720, 1600),
            appliedAtMillis = applied,
        )

        val read = store.pending()
        assertEquals("1080x2400", read?.previousValue)
        assertEquals(DisplaySize(720, 1600), DisplaySize(read!!.requestedWidth, read.requestedHeight))
        assertEquals(applied, read.appliedAtMillis)
        assertEquals(row.token, read.token)
        assertTrue(read.hasRecordedPrevious)
        assertEquals("1080 × 2400", read.previousLabel)
        assertEquals("720 × 1600", read.requestedLabel)
    }

    @Test
    fun `a row with no previous override reads back as null, not as a missing value`() {
        val store = store()

        store.record(previousValue = null, requested = DisplaySize(720, 1600), appliedAtMillis = applied)

        val read = store.pending()
        assertNull(read?.previousValue)
        assertFalse(read!!.hasRecordedPrevious)
        // The restore point's own rule: null means `wm size reset`, not "nothing was recorded".
        assertNull(DisplayOverrideGuard().previousSize(read))
    }

    @Test
    fun `two applications get two tokens`() {
        val store = store()

        val first = store.record("1080x2400", DisplaySize(720, 1600), applied)
        val second = store.record("1080x2400", DisplaySize(720, 1600), applied + 1L)

        assertFalse(first.token == second.token)
        // The second record is the one waiting: the user is asked about the change they just made.
        assertEquals(second.token, store.pending()?.token)
        assertEquals(applied + 1L, store.pending()?.appliedAtMillis)
    }

    @Test
    fun `clearing removes the row`() {
        val store = store()
        store.record("1080x2400", DisplaySize(720, 1600), applied)

        store.clear()

        assertNull(store.pending())
        assertFalse(store.hasUnreadableRow())
    }

    // ───────────────────────────────────────────────────── unreadable storage

    @Test
    fun `a stored size that will not parse reads as nothing rather than as a guess`() {
        val persistence = InMemoryPendingOverridePersistence()
        persistence.write(StoredPendingOverride(null, "not-a-size", applied, "tok"))
        val store = DisplayOverrideStore(persistence)

        assertNull(store.pending())
        // But a row *is* there, so the caller can say so instead of reporting a clean slate.
        assertTrue(store.hasUnreadableRow())
    }

    @Test
    fun `a row with no token is not a question anyone can answer`() {
        val persistence = InMemoryPendingOverridePersistence()
        persistence.write(StoredPendingOverride("1080x2400", "720x1600", applied, ""))
        val store = DisplayOverrideStore(persistence)

        assertNull(store.pending())
        assertTrue(store.hasUnreadableRow())
    }

    @Test
    fun `a row with a zero timestamp is not counted from`() {
        val persistence = InMemoryPendingOverridePersistence()
        persistence.write(StoredPendingOverride("1080x2400", "720x1600", 0L, "tok"))
        val store = DisplayOverrideStore(persistence)

        assertNull(store.pending())
        assertTrue(store.hasUnreadableRow())
    }

    @Test
    fun `an empty store has no row and nothing unreadable`() {
        val store = store()

        assertNull(store.pending())
        assertFalse(store.hasUnreadableRow())
    }

    // ──────────────────────────────────────────────────────────── token checks

    @Test
    fun `only the waiting token matches`() {
        val store = store()
        val row = store.record("1080x2400", DisplaySize(720, 1600), applied)

        assertTrue(store.matches(row.token))
        assertFalse(store.matches("some-other-token"))
        assertFalse(store.matches(null))
    }

    @Test
    fun `a token stops matching once the row has been answered`() {
        val store = store()
        val row = store.record("1080x2400", DisplaySize(720, 1600), applied)

        store.clear()

        // A notification action arriving after the answer must be inert.
        assertFalse(store.matches(row.token))
    }

    /** A `SharedPreferences` stand-in: exactly the four fields, and nothing else. */
    private class InMemoryPendingOverridePersistence : PendingOverridePersistence {
        private var stored: StoredPendingOverride? = null

        override fun read(): StoredPendingOverride? = stored

        override fun write(row: StoredPendingOverride) {
            stored = row
        }

        override fun clear() {
            stored = null
        }
    }
}
