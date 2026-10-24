package com.gamecore.data.database

import com.gamecore.core.model.SessionEvent
import com.gamecore.core.model.SessionEventKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The event row's round trip, and the one case where dropping data is the correct answer.
 *
 * An unknown kind is what a row written by a newer build looks like to this one. Mapping it onto a
 * nearby known kind would make the timeline state something the row does not say, so the mapper
 * returns null and the repository's `mapNotNull` omits it. Everything else — elapsed offset, detail,
 * value — must survive unchanged.
 */
class SessionEventMapperTest {

    @Test
    fun `maps a known event to the domain and back`() {
        val entity = SessionEventEntity(
            sessionId = 42L,
            elapsedMillis = 90_000L,
            kind = SessionEventKind.FRAME_OUTLIER.name,
            detail = "p99 68 ms · 22% jank",
            value = 68.0,
        )

        val model = Mappers.toModel(entity)!!
        assertEquals(42L, model.sessionId)
        assertEquals(90_000L, model.elapsedMillis)
        assertEquals(SessionEventKind.FRAME_OUTLIER, model.kind)
        assertEquals("p99 68 ms · 22% jank", model.detail)
        assertEquals(68.0, model.value!!, 0.001)

        val roundTrip = Mappers.toEntity(model)
        assertEquals(42L, roundTrip.sessionId)
        assertEquals(90_000L, roundTrip.elapsedMillis)
        assertEquals(SessionEventKind.FRAME_OUTLIER.name, roundTrip.kind)
        assertEquals("p99 68 ms · 22% jank", roundTrip.detail)
        assertEquals(68.0, roundTrip.value!!, 0.001)
    }

    @Test
    fun `drops an event kind this build does not know`() {
        val entity = SessionEventEntity(
            sessionId = 42L,
            elapsedMillis = 1_000L,
            kind = "FUTURE_KIND",
            detail = "written by a newer build",
        )

        assertNull(Mappers.toModel(entity))
    }

    @Test
    fun `keeps an event with no detail or value`() {
        val model = Mappers.toModel(
            SessionEventEntity(
                sessionId = 7L,
                elapsedMillis = 0L,
                kind = SessionEventKind.PROFILE_APPLIED.name,
            ),
        )!!

        assertNull(model.detail)
        assertNull(model.value)
        assertEquals(SessionEventKind.PROFILE_APPLIED, model.kind)
    }

    @Test
    fun `clamps a negative elapsed offset rather than writing one`() {
        val entity = Mappers.toEntity(
            SessionEvent(
                sessionId = 7L,
                elapsedMillis = -5L,
                kind = SessionEventKind.THERMAL_WARNING,
            ),
        )

        assertEquals(0L, entity.elapsedMillis)
    }
}
