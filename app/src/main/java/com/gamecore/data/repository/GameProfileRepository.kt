package com.gamecore.data.repository

import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.model.GameProfile
import com.gamecore.data.database.GameProfileDao
import com.gamecore.data.database.Mappers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Game profiles, as domain models.
 *
 * The repository exists to keep entities out of everything above it. Nothing in `domain` or `ui`
 * imports `GameProfileEntity`, so §24A.2 — data models exposed to the UI must contain only fields
 * the UI consumes — is enforced by what is reachable rather than by review: the UI cannot render
 * `updated_at` because it never sees it.
 *
 * Every write goes through [Mappers], which is where labels are sanitised. There is no second path
 * into the table, so there is no way to store an unsanitised label.
 */
@Singleton
class GameProfileRepository @Inject constructor(
    private val dao: GameProfileDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * Every profile, re-emitting on change.
     *
     * `map` on the flow rather than converting in the collector: the Games screen, the detection
     * service and the dashboard's profile count all observe this, and conversion belongs in one
     * place regardless of how many collectors there are.
     */
    val profiles: Flow<List<GameProfile>> =
        dao.observeAll().map { rows -> rows.map(Mappers::toModel) }

    /** For the dashboard's count card. A `COUNT(*)` rather than the list's size. */
    val profileCount: Flow<Int> = dao.observeCount()

    /**
     * The profiles the detection service is allowed to act on.
     *
     * Disabled profiles are excluded in SQL. A disabled profile is one the user switched off
     * without deleting — keeping it and filtering it in Kotlin would work, and would also mean the
     * one code path that applies settings to a device is fed by a list containing entries it must
     * remember to skip.
     */
    suspend fun enabledProfiles(): List<GameProfile> = withContext(io) {
        dao.enabled().map(Mappers::toModel)
    }

    suspend fun profileFor(packageName: String): GameProfile? = withContext(io) {
        dao.byPackage(packageName)?.let(Mappers::toModel)
    }

    fun observeProfileFor(packageName: String): Flow<GameProfile?> =
        dao.observeByPackage(packageName).map { it?.let(Mappers::toModel) }

    suspend fun save(profile: GameProfile) = withContext(io) {
        dao.upsert(Mappers.toEntity(profile, System.currentTimeMillis()))
    }

    suspend fun delete(packageName: String) = withContext(io) {
        dao.delete(packageName)
    }

    /**
     * Detaches a deleted HUD layout from every profile that referenced it.
     *
     * Called by [HudLayoutRepository] on delete. Without it a profile would carry an id that
     * resolves to nothing and the overlay would come up empty with no explanation — the same class
     * of silent failure as a control that reports success and does nothing.
     */
    suspend fun clearHudLayoutReferences(layoutId: Long) = withContext(io) {
        dao.clearHudLayout(layoutId)
    }

    suspend fun clearCrosshairReferences(presetId: Long) = withContext(io) {
        dao.clearCrosshairPreset(presetId)
    }
}
