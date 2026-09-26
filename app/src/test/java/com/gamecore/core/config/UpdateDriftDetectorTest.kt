package com.gamecore.core.config

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pure update-drift decision (spec §A9). The interesting cases are the two ends of "different": a game
 * updated forward, and the odd-but-real backward jump (a downgrade or a device clock rolled back). Both must
 * read as drift, because either way the install no longer matches the timestamp the backup was taken against.
 */
class UpdateDriftDetectorTest {

    @Test
    fun `a null baseline means no baseline has been recorded yet`() {
        assertEquals(UpdateDrift.NO_BASELINE, UpdateDriftDetector.detect(null, 1_000L))
    }

    @Test
    fun `a current timestamp equal to the baseline is unchanged`() {
        assertEquals(UpdateDrift.UNCHANGED, UpdateDriftDetector.detect(1_700_000_000_000L, 1_700_000_000_000L))
    }

    @Test
    fun `a later current timestamp is updated-since`() {
        assertEquals(UpdateDrift.UPDATED_SINCE, UpdateDriftDetector.detect(1_000L, 2_000L))
    }

    @Test
    fun `an earlier current timestamp still counts as updated-since`() {
        // A downgrade or a rolled-back clock: the install is no longer the one the baseline described.
        assertEquals(UpdateDrift.UPDATED_SINCE, UpdateDriftDetector.detect(2_000L, 1_000L))
    }

    @Test
    fun `a zero baseline is a real recorded value, not treated as absent`() {
        // null is "never recorded"; 0L is a genuine (if unusual) timestamp and must match a current 0L.
        assertEquals(UpdateDrift.UNCHANGED, UpdateDriftDetector.detect(0L, 0L))
        assertEquals(UpdateDrift.UPDATED_SINCE, UpdateDriftDetector.detect(0L, 1L))
    }
}
