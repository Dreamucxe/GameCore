package com.gamecore.data.repository

import com.gamecore.core.controller.ControllerProfile
import com.gamecore.core.controller.ControllerProfileCodec
import com.gamecore.core.controller.ControllerSignature
import com.gamecore.core.controller.ResponseCurveKind
import com.gamecore.data.database.ControllerProfileDao
import com.gamecore.data.database.ControllerProfileEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ControllerProfileRepository]'s contract, proven against an in-memory DAO.
 *
 * The behaviours pinned here are the ones a user can be lied to by: an invalid profile must not be written,
 * a database failure must surface as [ControllerWriteResult.Failed] instead of crashing, import must keep
 * existing rows and accept the valid part of a partly-bad file, and export must not turn a row this build
 * cannot interpret into a plausible-looking profile.
 */
class ControllerProfileRepositoryTest {

    private val signature = ControllerSignature.of(1, 2, listOf(0, 1, 11), 14)

    @Test
    fun `a valid profile is saved with resolved timestamps and appears in the flow`() = runTest {
        val dao = FakeProfileDao()
        val repo = repository(dao)

        val outcome = repo.save(profile())

        val saved = outcome as ControllerWriteResult.Saved
        assertTrue(saved.id > 0L)
        val stored = repo.profiles.first().single()
        assertEquals("Reference", stored.name)
        assertEquals(saved.id, stored.id)
        assertTrue(stored.createdAtMillis > 0L)
        assertTrue(stored.updatedAtMillis >= stored.createdAtMillis)
    }

    @Test
    fun `an invalid profile is rejected before it reaches the database`() = runTest {
        val dao = FakeProfileDao()
        val repo = repository(dao)

        val outcome = repo.save(profile().copy(name = " ", deadzone = 0.9f))

        assertTrue(outcome is ControllerWriteResult.Invalid)
        assertEquals(0, dao.count())
    }

    @Test
    fun `a database failure becomes a typed failure, not a crash`() = runTest {
        val dao = FakeProfileDao().apply { failWrites = true }
        val repo = repository(dao)

        val outcome = repo.save(profile())

        val failed = outcome as ControllerWriteResult.Failed
        assertEquals("IllegalStateException", failed.detail)
        assertEquals(0, dao.count())
    }

    @Test
    fun `editing preserves the original creation time`() = runTest {
        val dao = FakeProfileDao()
        val repo = repository(dao)
        val id = (repo.save(profile().copy(createdAtMillis = 1_000L)) as ControllerWriteResult.Saved).id

        repo.save(profile().copy(id = id, name = "Edited", createdAtMillis = 1_000L))

        val stored = repo.profiles.first().single()
        assertEquals(1_000L, stored.createdAtMillis)
        assertEquals("Edited", stored.name)
        assertEquals("one row is updated in place", 1, dao.count())
    }

    @Test
    fun `import accepts valid entries and keeps local rows`() = runTest {
        val dao = FakeProfileDao()
        val repo = repository(dao)
        repo.save(profile(name = "Local"))

        val text = ControllerProfileCodec.encode(listOf(profile(name = "Imported")))
        val outcome = repo.import(text)

        assertEquals(1, outcome.imported)
        assertEquals(0, outcome.rejected)
        assertNull(outcome.error)
        assertEquals(2, dao.count())
    }

    @Test
    fun `import reports the bad entries and still writes the good one`() = runTest {
        val dao = FakeProfileDao()
        val repo = repository(dao)
        val good = JSONObject(ControllerProfileCodec.encode(listOf(profile())))
            .getJSONArray("profiles").getJSONObject(0)
        val bad = JSONObject(good.toString()).put("deadzone", 0.9)
        val file = JSONObject()
            .put("format", ControllerProfileCodec.FORMAT)
            .put("version", ControllerProfileCodec.VERSION)
            .put("profiles", JSONArray().put(good).put(bad))

        val outcome = repo.import(file.toString())

        assertEquals(1, outcome.imported)
        assertEquals(1, outcome.rejected)
        assertEquals(1, outcome.issues.size)
        assertNull(outcome.error)
        assertEquals(1, dao.count())
    }

    @Test
    fun `an unreadable file is an error and writes nothing`() = runTest {
        val dao = FakeProfileDao()
        val repo = repository(dao)

        val outcome = repo.import("{\"format\":\"something.else\"}")

        assertEquals(0, outcome.imported)
        assertNotNull(outcome.error)
        assertEquals(0, dao.count())
    }

    @Test
    fun `an empty but valid export is an error on import rather than a silent success`() = runTest {
        val outcome = repository(FakeProfileDao()).import(ControllerProfileCodec.encode(emptyList()))

        assertEquals(0, outcome.imported)
        assertNotNull(outcome.error)
    }

    @Test
    fun `export round-trips the stored profiles without ids`() = runTest {
        val dao = FakeProfileDao()
        val repo = repository(dao)
        repo.save(profile(name = "First"))
        repo.save(profile(name = "Second"))

        val decoded = ControllerProfileCodec.decode(repo.exportAll())

        assertNull(decoded.error)
        assertEquals(setOf("First", "Second"), decoded.profiles.map { it.name }.toSet())
        assertTrue(decoded.profiles.all { it.id == 0L })
    }

    @Test
    fun `export drops a row this build cannot interpret instead of guessing a curve`() = runTest {
        val dao = FakeProfileDao()
        val repo = repository(dao)
        repo.save(profile())
        dao.insertRaw(
            ControllerProfileEntity(
                id = 0L,
                name = "Future",
                deviceSignature = signature,
                deviceName = "Pad",
                deadzone = 0.1f,
                curve = "BEZIER",
                exponent = 2f,
                driftOffset = 0.08f,
                driftJitter = 0.02f,
                notes = "",
                createdAtMillis = 1L,
                updatedAtMillis = 1L,
            ),
        )

        val decoded = ControllerProfileCodec.decode(repo.exportAll())

        assertEquals(1, decoded.profiles.size)
        assertEquals("Reference", decoded.profiles.single().name)
    }

    @Test
    fun `forDevice filters by the capability signature`() = runTest {
        val dao = FakeProfileDao()
        val repo = repository(dao)
        repo.save(profile(name = "Mine"))
        repo.save(profile(name = "Other").copy(deviceSignature = ControllerSignature.of(9, 9, listOf(0), 1)))

        val mine = repo.forDevice(signature).first()

        assertEquals(1, mine.size)
        assertEquals("Mine", mine.single().name)
    }

    @Test
    fun `deleting an absent id is rejected and a write failure is typed`() = runTest {
        val dao = FakeProfileDao()
        val repo = repository(dao)

        assertTrue(repo.delete(0L) is ControllerWriteResult.Invalid)
        dao.failWrites = true
        assertTrue(repo.delete(4L) is ControllerWriteResult.Failed)
    }

    private fun TestScope.repository(dao: FakeProfileDao) =
        ControllerProfileRepository(dao, UnconfinedTestDispatcher(testScheduler))

    private fun profile(name: String = "Reference") = ControllerProfile(
        name = name,
        deviceSignature = signature,
        deviceName = "Test pad",
        deadzone = 0.08f,
        curve = ResponseCurveKind.EXPONENTIAL,
        exponent = 2f,
        driftOffsetThreshold = 0.08f,
        driftJitterThreshold = 0.02f,
        notes = "reference",
        createdAtMillis = 0L,
        updatedAtMillis = 0L,
    )

    /** In-memory profile storage with Room's autoincrement/REPLACE behaviour and re-published flows. */
    private class FakeProfileDao : ControllerProfileDao {
        private var nextId = 1L
        private val rows = LinkedHashMap<Long, ControllerProfileEntity>()
        private val flow = MutableStateFlow<List<ControllerProfileEntity>>(emptyList())
        var failWrites = false

        private fun publish() {
            flow.value = rows.values.sortedByDescending { it.updatedAtMillis }
        }

        override fun observeAll(): Flow<List<ControllerProfileEntity>> = flow

        override fun observeForDevice(signature: String): Flow<List<ControllerProfileEntity>> =
            flow.map { list -> list.filter { it.deviceSignature == signature } }

        override suspend fun recent(limit: Int): List<ControllerProfileEntity> =
            flow.value.take(limit)

        override suspend fun byId(id: Long): ControllerProfileEntity? = rows[id]

        override suspend fun all(): List<ControllerProfileEntity> = rows.values.toList()

        override suspend fun count(): Int = rows.size

        override suspend fun upsert(profile: ControllerProfileEntity): Long {
            if (failWrites) throw IllegalStateException("write refused")
            val id = if (profile.id == 0L) nextId++ else profile.id
            rows[id] = profile.copy(id = id)
            publish()
            return id
        }

        override suspend fun delete(id: Long) {
            if (failWrites) throw IllegalStateException("write refused")
            rows.remove(id)
            publish()
        }

        fun insertRaw(entity: ControllerProfileEntity) {
            val id = if (entity.id == 0L) nextId++ else entity.id
            rows[id] = entity.copy(id = id)
            publish()
        }
    }
}
