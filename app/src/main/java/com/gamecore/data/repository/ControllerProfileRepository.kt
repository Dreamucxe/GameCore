package com.gamecore.data.repository

import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.controller.ControllerProfile
import com.gamecore.core.controller.ControllerProfileCodec
import com.gamecore.core.controller.ControllerProfileValidator
import com.gamecore.core.controller.ProfileValidation
import com.gamecore.data.database.ControllerLabMappers
import com.gamecore.data.database.ControllerProfileDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** The outcome of writing a profile or a result. Failure is typed, never an exception on the screen. */
sealed interface ControllerWriteResult {
    data class Saved(val id: Long) : ControllerWriteResult
    data class Invalid(val errors: List<String>) : ControllerWriteResult
    data class Failed(val detail: String) : ControllerWriteResult
}

/**
 * The outcome of a profile import. [rejected] counts file entries that failed validation plus rows the
 * database refused; [issues] carries the first few human-readable reasons so the screen can explain them.
 */
data class ControllerImportResult(
    val imported: Int,
    val rejected: Int,
    val issues: List<String> = emptyList(),
    val error: String? = null,
)

/**
 * Saved controller profiles, and their validated transfer format.
 *
 * The only path to the profile table. It validates before writing and before exporting, drops rows this
 * build cannot interpret rather than relabelling them, and turns a database failure into
 * [ControllerWriteResult.Failed] with only the exception class name — never a message that could quote a
 * row. Import is additive: it never deletes or overwrites a profile already on the device, because a file
 * brought from another phone has no authority over local data.
 */
@Singleton
class ControllerProfileRepository @Inject constructor(
    private val dao: ControllerProfileDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    val profiles: Flow<List<ControllerProfile>> = dao.observeAll().map { rows ->
        rows.mapNotNull { row ->
            ControllerLabMappers.toModel(row)?.validated()
        }
    }

    fun forDevice(signature: String): Flow<List<ControllerProfile>> =
        dao.observeForDevice(signature).map { rows ->
            rows.mapNotNull { row -> ControllerLabMappers.toModel(row)?.validated() }
        }

    suspend fun save(profile: ControllerProfile): ControllerWriteResult {
        val now = System.currentTimeMillis()
        val candidate = profile.copy(
            createdAtMillis = if (profile.createdAtMillis <= 0L) now else profile.createdAtMillis,
            updatedAtMillis = now,
        )
        val validated = when (val outcome = ControllerProfileValidator.validate(candidate)) {
            is ProfileValidation.Invalid -> return ControllerWriteResult.Invalid(outcome.errors)
            is ProfileValidation.Valid -> outcome.profile
        }
        return withContext(io) {
            runCatching { dao.upsert(ControllerLabMappers.toEntity(validated)) }
                .fold(
                    onSuccess = { ControllerWriteResult.Saved(it) },
                    onFailure = { ControllerWriteResult.Failed(it.javaClass.simpleName) },
                )
        }
    }

    suspend fun delete(id: Long): ControllerWriteResult {
        if (id <= 0L) return ControllerWriteResult.Invalid(listOf("That profile has no id."))
        return withContext(io) {
            runCatching { dao.delete(id) }
                .fold(
                    onSuccess = { ControllerWriteResult.Saved(id) },
                    onFailure = { ControllerWriteResult.Failed(it.javaClass.simpleName) },
                )
        }
    }

    /** Everything this build can read, as a versioned envelope. Invalid rows are not exported as guesses. */
    suspend fun exportAll(): String = withContext(io) {
        val models = dao.all().mapNotNull { row -> ControllerLabMappers.toModel(row)?.validated() }
        ControllerProfileCodec.encode(models)
    }

    suspend fun exportForDevice(signature: String): String = withContext(io) {
        val models = dao.all()
            .asSequence()
            .filter { it.deviceSignature == signature }
            .mapNotNull { row -> ControllerLabMappers.toModel(row)?.validated() }
            .toList()
        ControllerProfileCodec.encode(models)
    }

    /**
     * Imports every valid entry, keeps every local row, and reports what it could not read.
     *
     * The file is decoded once; each accepted entry is written through [save] so it passes the same
     * validation as one created on this device. A database failure mid-file does not discard the entries
     * already written — they are real rows — but it does make the import report a failure so the user is not
     * told everything landed.
     */
    suspend fun import(text: String): ControllerImportResult {
        val decoded = ControllerProfileCodec.decode(text)
        if (decoded.error != null) {
            return ControllerImportResult(
                imported = 0,
                rejected = decoded.rejectedProfiles,
                issues = decoded.issues,
                error = decoded.error,
            )
        }
        if (decoded.profiles.isEmpty()) {
            return ControllerImportResult(
                imported = 0,
                rejected = decoded.rejectedProfiles,
                issues = decoded.issues,
                error = "The file contained no readable profiles.",
            )
        }

        val now = System.currentTimeMillis()
        var imported = 0
        var rejected = decoded.rejectedProfiles
        var failure: String? = null
        val issues = decoded.issues.toMutableList()
        for (profile in decoded.profiles) {
            when (val outcome = save(profile.copy(createdAtMillis = now, updatedAtMillis = now))) {
                is ControllerWriteResult.Saved -> imported++
                is ControllerWriteResult.Invalid -> {
                    rejected++
                    if (issues.size < MAX_ISSUES) issues += "${profile.name}: ${outcome.errors.first()}"
                }
                is ControllerWriteResult.Failed -> {
                    rejected++
                    failure = outcome.detail
                    if (issues.size < MAX_ISSUES) issues += "${profile.name}: the database refused it."
                }
            }
        }
        return ControllerImportResult(
            imported = imported,
            rejected = rejected,
            issues = issues.take(MAX_ISSUES),
            error = if (failure != null && imported == 0) {
                "The imported profiles could not be saved ($failure)."
            } else {
                null
            },
        )
    }

    private fun ControllerProfile.validated(): ControllerProfile? =
        (ControllerProfileValidator.validate(this) as? ProfileValidation.Valid)?.profile

    private companion object {
        const val MAX_ISSUES = 6
    }
}
