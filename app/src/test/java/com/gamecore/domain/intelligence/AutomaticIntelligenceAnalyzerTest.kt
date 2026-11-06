package com.gamecore.domain.intelligence

import com.gamecore.core.model.IntelligenceAnalysisFrequency
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticIntelligenceAnalyzerTest {

    @Test
    fun `manual frequency is never due`() {
        assertFalse(intelligenceAnalysisIsDue(IntelligenceAnalysisFrequency.MANUAL, 0L, DAY))
    }

    @Test
    fun `daily waits a full day and every launch does not`() {
        assertFalse(intelligenceAnalysisIsDue(IntelligenceAnalysisFrequency.DAILY, DAY, 2 * DAY - 1L))
        assertTrue(intelligenceAnalysisIsDue(IntelligenceAnalysisFrequency.DAILY, DAY, 2 * DAY))
        assertTrue(intelligenceAnalysisIsDue(IntelligenceAnalysisFrequency.EVERY_GAME_LAUNCH, DAY, DAY))
    }

    @Test
    fun `missing history and backwards clock allow the next eligible automatic run`() {
        assertTrue(intelligenceAnalysisIsDue(IntelligenceAnalysisFrequency.WEEKLY, 0L, 1L))
        assertTrue(intelligenceAnalysisIsDue(IntelligenceAnalysisFrequency.DAILY, DAY, DAY - 1L))
    }

    private companion object {
        const val DAY = 24L * 60L * 60L * 1_000L
    }
}
