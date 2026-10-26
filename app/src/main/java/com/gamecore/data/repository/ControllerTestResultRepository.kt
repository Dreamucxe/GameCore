package com.gamecore.data.repository

import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.controller.ControllerTestResult
import com.gamecore.core.controller.ControllerTestResultValidator
import com.gamecore.core.controller.TestResultValidation
import com.gamecore.data.database.ControllerLabMappers
import com.gamecore.data.database.ControllerTestResultDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Saved drift-run summaries.
 *
 * A result is written only after [ControllerTestResultValidator] accepts it, and a row that no longer
 * validates is dropped from a read rather than shown with repaired numbers. Saving is append-only: an id on
 * an incoming result is rejected, because a measurement is history and editing one after the fact would
 * make the history worth less than the screen implies.
 */
@Singleton
class ControllerTestResultRepository @Inject constructor(
    private val dao: ControllerTestResultDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    fun observeForDevice(signature: String, limit: Int = DEFAULT_HISTORY): Flow<List<ControllerTestResult>> =
        dao.observeForDevice(signature, limit.coerceIn(1, MAX_HISTORY)).map { rows ->
            rows.mapNotNull { row ->
                ControllerLabMappers.toModel(row)?.validated()
            }
        }

    fun observeRecent(limit: Int = DEFAULT_HISTORY): Flow<List<ControllerTestResult>> =
        dao.observeRecent(limit.coerceIn(1, MAX_HISTORY)).map { rows ->
            rows.mapNotNull { row ->
                ControllerLabMappers.toModel(row)?.validated()
            }
        }

    suspend fun latestForDevice(signature: String): ControllerTestResult? = withContext(io) {
        runCatching {
            dao.latestForDevice(signature)?.let { row ->
                ControllerLabMappers.toModel(row)?.validated()
            }
        }.getOrNull()
    }

    suspend fun save(result: ControllerTestResult): ControllerWriteResult {
        if (result.id != 0L) {
            return ControllerWriteResult.Invalid(listOf("A new result must not carry an existing id."))
        }
        if (!result.verdict.isConclusive) {
            return ControllerWriteResult.Invalid(
                listOf("Only a completed measurement can be saved; this run has no verdict."),
            )
        }
        val validated = when (val outcome = ControllerTestResultValidator.validate(result)) {
            is TestResultValidation.Invalid -> return ControllerWriteResult.Invalid(outcome.errors)
            is TestResultValidation.Valid -> outcome.result
        }
        return withContext(io) {
            runCatching { dao.insert(ControllerLabMappers.toEntity(validated)) }
                .fold(
                    onSuccess = { ControllerWriteResult.Saved(it) },
                    onFailure = { ControllerWriteResult.Failed(it.javaClass.simpleName) },
                )
        }
    }

    suspend fun delete(id: Long): ControllerWriteResult {
        if (id <= 0L) return ControllerWriteResult.Invalid(listOf("That result has no id."))
        return withContext(io) {
            runCatching { dao.delete(id) }
                .fold(
                    onSuccess = { ControllerWriteResult.Saved(id) },
                    onFailure = { ControllerWriteResult.Failed(it.javaClass.simpleName) },
                )
        }
    }

    suspend fun clearForDevice(signature: String): ControllerWriteResult {
        if (signature.isBlank()) return ControllerWriteResult.Invalid(listOf("That device has no signature."))
        return withContext(io) {
            runCatching { dao.clearForDevice(signature) }
                .fold(
                    onSuccess = { ControllerWriteResult.Saved(0L) },
                    onFailure = { ControllerWriteResult.Failed(it.javaClass.simpleName) },
                )
        }
    }

    private fun ControllerTestResult.validated(): ControllerTestResult? =
        (ControllerTestResultValidator.validate(this) as? TestResultValidation.Valid)?.result

    companion object {
        const val DEFAULT_HISTORY = 50
        const val MAX_HISTORY = 200
    }
}
