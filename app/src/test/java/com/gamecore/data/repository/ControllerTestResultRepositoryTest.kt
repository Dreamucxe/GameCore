package com.gamecore.data.repository

import com.gamecore.core.controller.ControllerTestResult
import com.gamecore.core.controller.DriftVerdict
import com.gamecore.core.controller.StickKind
import com.gamecore.data.database.ControllerTestResultDao
import com.gamecore.data.database.ControllerTestResultEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ControllerTestResultRepository]'s contract against an in-memory DAO.
 *
 * History is append-only and must not be editable by handing the repository an id. A record whose stored
 * verdict this build does not know is dropped, not relabelled "Stable". A database failure is a typed
 * failure. Those are the three ways a diagnostics history could quietly lie.
 */
class ControllerTestResultRepositoryTest {

    private val signature = "c1:device"

    @Test
    fun `a valid result is stored and read back as the latest`() = runTest {
        val dao = FakeResultDao()
        val repo = repository(dao)

        val outcome = repo.save(result(startedAt = 5_000L))

        val saved = outcome as ControllerWriteResult.Saved
        assertTrue(saved.id > 0L)
        val latest = repo.latestForDevice(signature)
        assertEquals(saved.id, latest?.id)
        assertEquals(DriftVerdict.STABLE, latest?.verdict)
    }

    @Test
    fun `an existing id is refused so history cannot be rewritten`() = runTest {
        val dao = FakeResultDao()
        val repo = repository(dao)

        val outcome = repo.save(result().copy(id = 3L))

        assertTrue(outcome is ControllerWriteResult.Invalid)
        assertEquals(0, dao.count())
    }

    @Test
    fun `a run without a verdict is refused, even when called directly`() = runTest {
        val dao = FakeResultDao()

        val outcome = repository(dao).save(result(verdict = DriftVerdict.NOT_ENOUGH_DATA))

        assertTrue(outcome is ControllerWriteResult.Invalid)
        assertEquals(0, dao.count())
    }

    @Test
    fun `an inconsistent result is rejected before it reaches the database`() = runTest {
        val outcome = repository(FakeResultDao()).save(result().copy(offsetMagnitude = 0.9f))

        assertTrue(outcome is ControllerWriteResult.Invalid)
    }

    @Test
    fun `a database failure is typed rather than thrown`() = runTest {
        val dao = FakeResultDao().apply { failWrites = true }

        val outcome = repository(dao).save(result())

        assertEquals("IllegalStateException", (outcome as ControllerWriteResult.Failed).detail)
    }

    @Test
    fun `an unknown stored verdict is dropped, not shown as stable`() = runTest {
        val dao = FakeResultDao()
        val repo = repository(dao)
        repo.save(result(startedAt = 1_000L, verdict = DriftVerdict.DRIFT))
        dao.insertRaw(raw(verdict = "STABLE_V2", startedAt = 2_000L))

        val history = repo.observeForDevice(signature).first()

        assertEquals(1, history.size)
        assertEquals(DriftVerdict.DRIFT, history.single().verdict)
    }

    @Test
    fun `latest is null when the only row is unreadable or the read fails`() = runTest {
        val dao = FakeResultDao()
        val repo = repository(dao)
        dao.insertRaw(raw(verdict = "FUTURE", startedAt = 2_000L))
        assertNull(repo.latestForDevice(signature))

        dao.failReads = true
        assertNull(repo.latestForDevice(signature))
    }

    @Test
    fun `history is per device and newest first`() = runTest {
        val dao = FakeResultDao()
        val repo = repository(dao)
        repo.save(result(startedAt = 1_000L, verdict = DriftVerdict.STABLE))
        repo.save(result(startedAt = 3_000L, verdict = DriftVerdict.DRIFT))
        repo.save(result(startedAt = 2_000L, verdict = DriftVerdict.UNSTABLE).copy(deviceSignature = "other"))

        val history = repo.observeForDevice(signature).first()

        assertEquals(listOf(3_000L, 1_000L), history.map { it.startedAtMillis })
    }

    @Test
    fun `clearing a device keeps another device's history`() = runTest {
        val dao = FakeResultDao()
        val repo = repository(dao)
        repo.save(result(startedAt = 1_000L))
        repo.save(result(startedAt = 2_000L).copy(deviceSignature = "other"))

        val outcome = repo.clearForDevice(signature)

        assertTrue(outcome is ControllerWriteResult.Saved)
        assertEquals(1, dao.count())
        assertNull(repo.latestForDevice(signature))
        assertEquals("other", repo.observeRecent().first().single().deviceSignature)
    }

    @Test
    fun `deleting an invalid id is rejected and a failure is typed`() = runTest {
        val dao = FakeResultDao()
        val repo = repository(dao)

        assertTrue(repo.delete(0L) is ControllerWriteResult.Invalid)
        dao.failWrites = true
        assertTrue(repo.delete(1L) is ControllerWriteResult.Failed)
    }

    private fun TestScope.repository(dao: FakeResultDao) =
        ControllerTestResultRepository(dao, UnconfinedTestDispatcher(testScheduler))

    private fun result(
        startedAt: Long = 1_000L,
        verdict: DriftVerdict = DriftVerdict.STABLE,
    ) = ControllerTestResult(
        deviceSignature = signature,
        deviceName = "Test pad",
        stick = StickKind.LEFT,
        startedAtMillis = startedAt,
        durationMillis = 10_000L,
        sampleCount = 300,
        offsetX = 0.03f,
        offsetY = 0.04f,
        offsetMagnitude = 0.05f,
        jitter = 0.01f,
        stabilityPercent = 97f,
        peakMagnitude = 0.07f,
        verdict = verdict,
        offsetThreshold = 0.08f,
        jitterThreshold = 0.02f,
    )

    private fun raw(verdict: String, startedAt: Long) = ControllerTestResultEntity(
        id = 0L,
        deviceSignature = signature,
        deviceName = "Test pad",
        stick = "LEFT",
        startedAtMillis = startedAt,
        durationMillis = 10_000L,
        sampleCount = 300,
        offsetX = 0.03f,
        offsetY = 0.04f,
        offsetMagnitude = 0.05f,
        jitter = 0.01f,
        stabilityPercent = 97f,
        peakMagnitude = 0.07f,
        verdict = verdict,
        offsetThreshold = 0.08f,
        jitterThreshold = 0.02f,
    )

    private class FakeResultDao : ControllerTestResultDao {
        private var nextId = 1L
        private val rows = ArrayList<ControllerTestResultEntity>()
        private val flow = MutableStateFlow<List<ControllerTestResultEntity>>(emptyList())
        var failWrites = false
        var failReads = false

        private fun publish() {
            flow.value = rows.sortedByDescending { it.startedAtMillis }
        }

        override fun observeForDevice(signature: String, limit: Int): Flow<List<ControllerTestResultEntity>> =
            flow.map { list -> list.filter { it.deviceSignature == signature }.take(limit) }

        override fun observeRecent(limit: Int): Flow<List<ControllerTestResultEntity>> =
            flow.map { list -> list.take(limit) }

        override suspend fun latestForDevice(signature: String): ControllerTestResultEntity? {
            if (failReads) throw IllegalStateException("read refused")
            return rows.filter { it.deviceSignature == signature }.maxByOrNull { it.startedAtMillis }
        }

        override suspend fun allForDevice(signature: String): List<ControllerTestResultEntity> =
            rows.filter { it.deviceSignature == signature }

        override suspend fun count(): Int = rows.size

        override suspend fun insert(result: ControllerTestResultEntity): Long {
            if (failWrites) throw IllegalStateException("write refused")
            val id = nextId++
            rows += result.copy(id = id)
            publish()
            return id
        }

        override suspend fun delete(id: Long) {
            if (failWrites) throw IllegalStateException("write refused")
            rows.removeAll { it.id == id }
            publish()
        }

        override suspend fun clearForDevice(signature: String) {
            if (failWrites) throw IllegalStateException("write refused")
            rows.removeAll { it.deviceSignature == signature }
            publish()
        }

        override suspend fun clearAll() {
            if (failWrites) throw IllegalStateException("write refused")
            rows.clear()
            publish()
        }

        fun insertRaw(entity: ControllerTestResultEntity) {
            rows += entity.copy(id = nextId++)
            publish()
        }
    }
}
