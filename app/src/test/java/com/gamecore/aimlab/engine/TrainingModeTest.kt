package com.gamecore.aimlab.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two axes every session is filed under, and the stored keys that must never drift.
 *
 * A [TrainingMode] name is written into Room and into every personal-record key, so it is an on-disk
 * format: renaming one orphans a user's history. The names are therefore asserted literally here, while
 * the labels — the part that is free to change — are only required to exist and be distinct. The other
 * load-bearing property is defensive parsing: a stored name that no longer exists must come back as null
 * (mode) or as the Normal baseline (difficulty), never as a thrown [IllegalArgumentException] from
 * `valueOf` on a screen the user is looking at. [scored] is what decides whether a run is eligible for a
 * personal record at all, so the split is pinned rather than assumed.
 */
class TrainingModeTest {

    @Test
    fun `the stored mode names are exactly the seven the app files sessions under`() {
        assertEquals(
            listOf("FLICK", "TRACKING", "REACTION", "GYRO", "RECOIL", "MOVEMENT", "FREE_PRACTICE"),
            TrainingMode.entries.map { it.name },
        )
    }

    @Test
    fun `every mode carries its own non-blank label`() {
        val labels = TrainingMode.entries.map { it.label }
        for (label in labels) assertTrue("blank label", label.isNotBlank())
        assertEquals(TrainingMode.entries.size, labels.distinct().size)
        assertEquals("Flick training", TrainingMode.FLICK.label)
        assertEquals("Free practice", TrainingMode.FREE_PRACTICE.label)
    }

    @Test
    fun `free practice is the only mode that does not produce a comparable score`() {
        assertFalse(TrainingMode.FREE_PRACTICE.scored)
        val scored = TrainingMode.entries.filter { it.scored }
        assertEquals(6, scored.size)
        assertEquals(
            listOf(
                TrainingMode.FLICK,
                TrainingMode.TRACKING,
                TrainingMode.REACTION,
                TrainingMode.GYRO,
                TrainingMode.RECOIL,
                TrainingMode.MOVEMENT,
            ),
            scored,
        )
    }

    @Test
    fun `every mode round-trips through its stored name`() {
        for (mode in TrainingMode.entries) {
            assertEquals(mode, TrainingMode.fromName(mode.name))
        }
    }

    @Test
    fun `an unrecognised mode name is dropped rather than thrown on`() {
        assertNull(TrainingMode.fromName(null))
        assertNull(TrainingMode.fromName(""))
        assertNull(TrainingMode.fromName("SNIPER_DRILL"))
        // The match is on the exact stored name, not the label and not a case-insensitive guess.
        assertNull(TrainingMode.fromName("flick"))
        assertNull(TrainingMode.fromName("FLICK "))
        assertNull(TrainingMode.fromName(TrainingMode.FLICK.label))
    }

    @Test
    fun `the stored difficulty names are the five steps the app offers`() {
        assertEquals(
            listOf("EASY", "NORMAL", "HARD", "EXTREME", "CUSTOM"),
            Difficulty.entries.map { it.name },
        )
        val labels = Difficulty.entries.map { it.label }
        for (label in labels) assertTrue("blank label", label.isNotBlank())
        assertEquals(Difficulty.entries.size, labels.distinct().size)
    }

    @Test
    fun `every difficulty round-trips through its stored name`() {
        for (level in Difficulty.entries) {
            assertEquals(level, Difficulty.fromName(level.name))
        }
    }

    @Test
    fun `an unrecognised difficulty name falls back to normal rather than throwing`() {
        assertEquals(Difficulty.NORMAL, Difficulty.fromName(null))
        assertEquals(Difficulty.NORMAL, Difficulty.fromName(""))
        assertEquals(Difficulty.NORMAL, Difficulty.fromName("NIGHTMARE"))
        assertEquals(Difficulty.NORMAL, Difficulty.fromName("easy"))
        assertEquals(Difficulty.NORMAL, Difficulty.fromName(Difficulty.EASY.label))
    }
}
