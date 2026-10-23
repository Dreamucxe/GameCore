package com.gamecore.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The retention boundary, proved without a database.
 *
 * Two promises are load-bearing here and both are about what "90 days" means. Zero (and any negative
 * value) must never produce a cutoff, because "keep forever" is the default and a bug that turns it into
 * "delete everything" would erase a user's history on the day they open the app. And a session that ended
 * exactly N days ago must be kept: the comparison is strictly-before so the setting means N days, not
 * N days and a millisecond.
 *
 * Nothing in this file touches personal records — [RetentionPolicy] has no concept of one — and that
 * absence is deliberate: the records survive by simply never being in scope for a purge.
 */
class RetentionPolicyTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `the day is exactly 24 hours`() {
        assertEquals(86_400_000L, RetentionPolicy.MILLIS_PER_DAY)
    }

    @Test
    fun `zero keeps everything by producing no cutoff`() {
        assertNull(RetentionPolicy.purgeBefore(now, 0))
    }

    @Test
    fun `a negative window reads as keep forever rather than cutting somewhere in the future`() {
        assertNull(RetentionPolicy.purgeBefore(now, -7))
    }

    @Test
    fun `a window turns into an instant exactly N days before now`() {
        assertEquals(now - 30L * RetentionPolicy.MILLIS_PER_DAY, RetentionPolicy.purgeBefore(now, 30))
    }

    @Test
    fun `a session ending exactly at the cutoff is kept`() {
        val cutoff = requireNotNull(RetentionPolicy.purgeBefore(now, 90))

        // Exactly N days old is not older than N days; deleting it would make the setting mean N−ε.
        assertFalse(RetentionPolicy.isExpired(cutoff, cutoff))
    }

    @Test
    fun `a session ending one millisecond before the cutoff is expired`() {
        val cutoff = requireNotNull(RetentionPolicy.purgeBefore(now, 90))

        assertTrue(RetentionPolicy.isExpired(cutoff - 1, cutoff))
    }

    @Test
    fun `a session ending one millisecond after the cutoff is kept`() {
        val cutoff = requireNotNull(RetentionPolicy.purgeBefore(now, 90))

        assertFalse(RetentionPolicy.isExpired(cutoff + 1, cutoff))
    }

    @Test
    fun `an absurd window does not overflow into a cutoff in the future`() {
        // An Int's worth of days is a Long multiplication; the only failure mode that matters is wrapping
        // past zero and making everything expired. The cutoff must stay below now.
        val cutoff = RetentionPolicy.purgeBefore(now, Int.MAX_VALUE)

        assertTrue(cutoff != null && cutoff < now)
    }
}
