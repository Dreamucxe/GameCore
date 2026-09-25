package com.gamecore.domain.gaming

import com.gamecore.core.model.GameProfile
import com.gamecore.core.model.GameSession
import com.gamecore.core.model.PerformanceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a suggested profile is allowed to claim (spec §4, feature #3).
 *
 * The rule under test is honesty: every field the suggester fills must be backed by a measurement, and a
 * dimension the history did not measure enough to speak to must stay null. Each test below is a way the
 * feature could quietly start guessing on a device nobody is watching — a cap invented from two menu
 * screens, auto-cooling turned on for a game that never got hot, a suggestion offered before there is
 * enough history to mean anything — pinned so it cannot.
 */
class ProfileSuggesterTest {

    // -------------------------------------------------------------------------------- eligibility

    @Test
    fun `not eligible below the session floor, eligible at it`() {
        val threeOfThem = List(ProfileSuggester.MIN_SESSIONS - 1) { hotDrainingSession(id = it.toLong()) }
        val enough = List(ProfileSuggester.MIN_SESSIONS) { hotDrainingSession(id = it.toLong()) }
        assertFalse(ProfileSuggester.isEligible(existingProfile = null, sessions = threeOfThem))
        assertTrue(ProfileSuggester.isEligible(existingProfile = null, sessions = enough))
    }

    @Test
    fun `a game that already has a profile is never eligible`() {
        val enough = List(ProfileSuggester.MIN_SESSIONS) { hotDrainingSession(id = it.toLong()) }
        val existing = GameProfile.forGame(PKG, LABEL)
        assertFalse(ProfileSuggester.isEligible(existingProfile = existing, sessions = enough))
        assertNull(ProfileSuggester.suggest(PKG, LABEL, existingProfile = existing, sessions = enough))
    }

    @Test
    fun `running and low-sample sessions do not count toward the floor`() {
        // Four rows, but one is still running and one has too few samples to average — two usable, so
        // the game is below the floor of four and gets no suggestion.
        val sessions = listOf(
            hotDrainingSession(id = 1L),
            hotDrainingSession(id = 2L),
            hotDrainingSession(id = 3L, ended = false),
            hotDrainingSession(id = 4L, sampleCount = 3),
        )
        assertFalse(ProfileSuggester.isEligible(existingProfile = null, sessions = sessions))
    }

    // ------------------------------------------------------------------------------ refresh cap

    @Test
    fun `frames well under the panel rate suggest a lower cap with a reason`() {
        // 58 fps on a 120 Hz panel across four sessions: the panel is refreshing twice as fast as the
        // game draws, so 60 Hz loses nothing and saves power.
        val sessions = List(4) { session(id = it.toLong(), avgFrame = 58f, avgRefresh = 120f) }
        val suggestion = ProfileSuggester.suggest(PKG, LABEL, null, sessions)!!
        assertEquals(60f, suggestion.profile.targetRefreshRate)
        val reason = suggestion.reasons.single { it.field == SuggestedField.REFRESH_RATE }
        assertTrue(reason.text.contains("60 Hz"))
        assertTrue(reason.text.isNotBlank())
    }

    @Test
    fun `frames near the panel rate suggest no cap`() {
        // 115 fps on a 120 Hz panel: the game is using the refresh it has. Capping would cost frames, so
        // nothing is suggested and the field is left alone.
        val sessions = List(4) { session(id = it.toLong(), avgFrame = 115f, avgRefresh = 120f) }
        assertNull(ProfileSuggester.suggest(PKG, LABEL, null, sessions))
    }

    @Test
    fun `a cap needs enough sessions that measured both rates`() {
        // Kept non-null by the thermal signal (all four hot), with light-enough battery that saver does
        // not fire and mask the refresh field. Only two sessions measured both rates — one short of the
        // field floor — so the cap stays null even though those two would have supported it.
        val sessions = listOf(
            session(id = 1L, peakTempDeci = 640, avgFrame = 40f, avgRefresh = 120f),
            session(id = 2L, peakTempDeci = 640, avgFrame = 40f, avgRefresh = 120f),
            session(id = 3L, peakTempDeci = 640),
            session(id = 4L, peakTempDeci = 640),
        )
        val suggestion = ProfileSuggester.suggest(PKG, LABEL, null, sessions)!!
        assertNull(suggestion.profile.targetRefreshRate)
        assertTrue(suggestion.profile.thermalDownshiftEnabled)
    }

    // ------------------------------------------------------------------------------ battery saver

    @Test
    fun `heavy drain suggests battery saver and suppresses the separate refresh cap`() {
        // Draining ~30%/hour and drawing few frames on a fast panel. Battery saver already pins the panel
        // low, so the redundant targetRefreshRate cap must not be set beside it.
        val sessions = List(4) {
            session(
                id = it.toLong(),
                batteryStart = 90,
                batteryEnd = 60,
                avgFrame = 55f,
                avgRefresh = 120f,
            )
        }
        val suggestion = ProfileSuggester.suggest(PKG, LABEL, null, sessions)!!
        assertEquals(PerformanceMode.BATTERY_SAVER, suggestion.profile.performanceMode)
        assertNull(suggestion.profile.targetRefreshRate)
        assertTrue(suggestion.reasons.any { it.field == SuggestedField.PERFORMANCE_MODE })
    }

    @Test
    fun `light drain leaves the performance mode alone`() {
        val sessions = List(4) { session(id = it.toLong(), batteryStart = 90, batteryEnd = 82) }
        assertNull(ProfileSuggester.suggest(PKG, LABEL, null, sessions))
    }

    // ------------------------------------------------------------------------------ thermal

    @Test
    fun `a consistently hot game suggests auto-cooling at the measured threshold`() {
        val sessions = List(4) { session(id = it.toLong(), peakTempDeci = 640) }
        val suggestion = ProfileSuggester.suggest(PKG, LABEL, null, sessions)!!
        assertTrue(suggestion.profile.thermalDownshiftEnabled)
        assertEquals(ProfileSuggester.HOT_PEAK_DECI, suggestion.profile.thermalLimitDeciCelsius)
        assertTrue(suggestion.reasons.any { it.field == SuggestedField.THERMAL_DOWNSHIFT })
    }

    @Test
    fun `a cool game is not offered auto-cooling`() {
        val sessions = List(4) { session(id = it.toLong(), peakTempDeci = 380) }
        val cool = ProfileSuggester.suggest(PKG, LABEL, null, sessions)
        // Nothing else qualifies either, so the whole suggestion is null — and crucially not a downshift.
        assertNull(cool)
    }

    @Test
    fun `thin temperature history does not invent auto-cooling`() {
        // Four usable sessions keep the game eligible, but only two measured temperature. One short of the
        // field floor, so no downshift — the two hot readings are not enough to speak for the game.
        val sessions = listOf(
            hotDrainingSession(id = 1L).copy(peakTemperatureDeciCelsius = 700),
            hotDrainingSession(id = 2L).copy(peakTemperatureDeciCelsius = 700),
            hotDrainingSession(id = 3L).copy(peakTemperatureDeciCelsius = null),
            hotDrainingSession(id = 4L).copy(peakTemperatureDeciCelsius = null),
        )
        val suggestion = ProfileSuggester.suggest(PKG, LABEL, null, sessions)!!
        assertFalse(suggestion.profile.thermalDownshiftEnabled)
        assertNull(suggestion.profile.thermalLimitDeciCelsius)
    }

    // ------------------------------------------------------------------------------ nothing to say

    @Test
    fun `eligible but featureless history yields no suggestion`() {
        // Enough usable sessions to be eligible, but every measurable dimension is absent: no frames, no
        // temperature, and battery left alone. There is nothing to suggest, so the card shows nothing.
        val sessions = List(4) { session(id = it.toLong()) }
        assertNull(ProfileSuggester.suggest(PKG, LABEL, null, sessions))
    }

    @Test
    fun `every filled field carries exactly one reason, and the count is the usable total`() {
        val sessions = List(5) {
            session(id = it.toLong(), avgFrame = 50f, avgRefresh = 120f, peakTempDeci = 650)
        }
        val suggestion = ProfileSuggester.suggest(PKG, LABEL, null, sessions)!!
        // Refresh cap + thermal: two filled fields, two reasons, one each — and no field named twice.
        assertEquals(2, suggestion.reasons.size)
        assertEquals(2, suggestion.reasons.map { it.field }.distinct().size)
        assertNotNull(suggestion.reasons.singleOrNull { it.field == SuggestedField.REFRESH_RATE })
        assertNotNull(suggestion.reasons.singleOrNull { it.field == SuggestedField.THERMAL_DOWNSHIFT })
        assertEquals(5, suggestion.sessionCount)
    }

    @Test
    fun `the same history produces the same suggestion`() {
        val sessions = List(4) {
            session(id = it.toLong(), avgFrame = 58f, avgRefresh = 120f, peakTempDeci = 640)
        }
        val first = ProfileSuggester.suggest(PKG, LABEL, null, sessions)
        val second = ProfileSuggester.suggest(PKG, LABEL, null, sessions)
        assertEquals(first, second)
    }

    // ------------------------------------------------------------------------------ fixtures

    private fun session(
        id: Long = 1L,
        durationMillis: Long = HOUR,
        batteryStart: Int = 100,
        batteryEnd: Int? = null,
        wasCharging: Boolean = false,
        avgFrame: Float? = null,
        avgRefresh: Float? = null,
        peakTempDeci: Int? = null,
        sampleCount: Int = 900,
        ended: Boolean = true,
    ) = GameSession(
        id = id,
        packageName = PKG,
        gameLabel = LABEL,
        startedAtMillis = START,
        endedAtMillis = if (ended) START + durationMillis else null,
        batteryStartPercent = batteryStart,
        batteryEndPercent = batteryEnd,
        wasCharging = wasCharging,
        averageFrameRate = avgFrame,
        averageRefreshRate = avgRefresh,
        peakTemperatureDeciCelsius = peakTempDeci,
        sampleCount = sampleCount,
    )

    /** A session that on its own would earn every suggestion: hot, and draining ~30%/hour. */
    private fun hotDrainingSession(
        id: Long,
        ended: Boolean = true,
        sampleCount: Int = 900,
    ) = session(
        id = id,
        batteryStart = 90,
        batteryEnd = 60,
        peakTempDeci = 640,
        ended = ended,
        sampleCount = sampleCount,
    )

    private companion object {
        const val PKG = "com.example.game"
        const val LABEL = "Rocket Racer"
        const val START = 1_700_000_000_000L
        const val HOUR = 3_600_000L
    }
}
