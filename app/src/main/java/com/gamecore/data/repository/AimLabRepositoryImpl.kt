package com.gamecore.data.repository

import com.gamecore.aimlab.AimLabRepository
import com.gamecore.aimlab.engine.ControlLayout
import com.gamecore.aimlab.engine.Difficulty
import com.gamecore.aimlab.engine.LayoutPreset
import com.gamecore.aimlab.engine.PersonalRecord
import com.gamecore.aimlab.engine.PersonalRecordBook
import com.gamecore.aimlab.engine.FireMode
import com.gamecore.aimlab.engine.RecoilSpec
import com.gamecore.aimlab.engine.SensitivityPreset
import com.gamecore.aimlab.engine.SensitivityProfile
import com.gamecore.aimlab.engine.SessionSummary
import com.gamecore.aimlab.engine.TrainingMode
import com.gamecore.aimlab.engine.Weapon
import com.gamecore.aimlab.engine.WeaponCategory
import com.gamecore.core.common.IoDispatcher
import com.gamecore.data.database.AimLabLayoutDao
import com.gamecore.data.database.AimLabMappers
import com.gamecore.data.database.AimLabSensitivityDao
import com.gamecore.data.database.AimLabSessionDao
import com.gamecore.data.database.AimLabWeaponDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Room-backed implementation of the Aim Lab's one persistence seam.
 *
 * Follows the house repository pattern exactly: `@Singleton`, constructor-injected DAOs and an
 * `@IoDispatcher`, observed lists exposed as `Flow`s mapped on the flow, one-shots wrapped in
 * `withContext(io)`. Everything durable in Aim Lab passes through here, and nothing high-frequency does —
 * [saveSession] takes a finished summary, never a stream.
 *
 * The record logic lives in the engine ([PersonalRecordBook]); this class only reads the current record
 * for a key, asks the book whether a candidate beats it, and upserts the winner. That keeps the
 * comparison — including the lower-is-better direction for reaction time — testable without a database.
 */
@Singleton
class AimLabRepositoryImpl @Inject constructor(
    private val sessionDao: AimLabSessionDao,
    private val weaponDao: AimLabWeaponDao,
    private val sensitivityDao: AimLabSensitivityDao,
    private val layoutDao: AimLabLayoutDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) : AimLabRepository {

    // ------------------------------------------------------------------ sessions & records

    override val sessions: Flow<List<SessionSummary>> =
        sessionDao.observeSessions().map { rows -> rows.map(AimLabMappers::toModel) }

    override val records: Flow<List<PersonalRecord>> =
        sessionDao.observeRecords().map { rows -> rows.mapNotNull(AimLabMappers::toModel) }

    override val sessionCount: Flow<Int> = sessionDao.observeSessionCount()

    override suspend fun saveSession(summary: SessionSummary): Long = withContext(io) {
        if (!summary.isValid) return@withContext 0L
        val id = sessionDao.insert(AimLabMappers.toEntity(summary))
        updateRecords(summary)
        id
    }

    /**
     * Challenges every record the finished session is eligible for, and stores the ones it beats.
     *
     * The eligible metrics come from [PersonalRecordBook.candidatesFor], so a mode is never challenged with
     * a metric it did not measure. For each, the current stored record (if any) is read, [PersonalRecordBook]
     * decides — honouring [com.gamecore.aimlab.engine.RecordMetric.higherIsBetter] — and a winner is upserted
     * against the unique key so it replaces the previous holder in place.
     */
    private suspend fun updateRecords(summary: SessionSummary) {
        for ((metric, value) in PersonalRecordBook.candidatesFor(summary)) {
            val existing = sessionDao.record(
                mode = summary.mode.name,
                difficulty = summary.difficulty.name,
                weapon = summary.weaponName,
                metric = metric.name,
                scoringVersion = summary.scoringVersion,
            )?.let(AimLabMappers::toModel)
            // Only ever challenge a record of the same scoring generation, so a 3D session never ranks
            // against a legacy 2D record and vice versa (§6).
            val winner = PersonalRecordBook.challenge(
                current = existing,
                mode = summary.mode,
                difficulty = summary.difficulty,
                weaponName = summary.weaponName,
                metric = metric,
                candidateValue = value,
                achievedAtMillis = summary.endedAtMillis,
                scoringVersion = summary.scoringVersion,
            ) ?: continue
            sessionDao.upsertRecord(AimLabMappers.toEntity(winner))
        }
    }

    override suspend fun recentSessions(limit: Int): List<SessionSummary> = withContext(io) {
        sessionDao.recent(limit).map(AimLabMappers::toModel)
    }

    override suspend fun sessionsFor(mode: TrainingMode): List<SessionSummary> = withContext(io) {
        sessionDao.forMode(mode.name).map(AimLabMappers::toModel)
    }

    /**
     * Removes one session row and nothing else.
     *
     * Records are deliberately left standing. A record is keyed by (mode, difficulty, weapon, metric) and
     * holds no session id, so dropping a session orphans nothing — but a record that session set keeps its
     * value. Recomputing it is not possible: [com.gamecore.data.database.AimLabRecordEntity.previousValue]
     * and the moment it was achieved do not survive a rebuild from the remaining sessions, which is the very
     * reason records are stored rather than derived. Wiping records happens only with the whole history, in
     * [clearHistory].
     */
    override suspend fun deleteSession(id: Long) = withContext(io) {
        sessionDao.delete(id)
    }

    override suspend fun clearHistory() = withContext(io) {
        sessionDao.clearAll()
    }

    // ------------------------------------------------------------------------------ weapons

    override val weapons: Flow<List<Weapon>> =
        weaponDao.observeWeapons().map { rows -> rows.map(AimLabMappers::toModel) }

    override suspend fun weapon(id: Long): Weapon? = withContext(io) {
        weaponDao.weapon(id)?.let(AimLabMappers::toModel)
    }

    override suspend fun saveWeapon(weapon: Weapon): Long = withContext(io) {
        weaponDao.insert(AimLabMappers.toEntity(weapon))
    }

    override suspend fun deleteWeapon(id: Long) = withContext(io) {
        weaponDao.delete(id)
    }

    // ------------------------------------------------------------------------- sensitivity

    override val sensitivities: Flow<List<SensitivityProfile>> =
        sensitivityDao.observeSensitivities().map { rows -> rows.map(AimLabMappers::toModel) }

    override suspend fun sensitivity(id: Long): SensitivityProfile? = withContext(io) {
        sensitivityDao.sensitivity(id)?.let(AimLabMappers::toModel)
    }

    override suspend fun saveSensitivity(profile: SensitivityProfile): Long = withContext(io) {
        sensitivityDao.insert(AimLabMappers.toEntity(profile))
    }

    override suspend fun deleteSensitivity(id: Long) = withContext(io) {
        sensitivityDao.delete(id)
    }

    // ------------------------------------------------------------------------------ layouts

    /**
     * The list source now reads each layout *with its controls* through the one relation query, so the
     * list's "N of M controls enabled" count is real saved rows, not an empty stand-in (§bug-fix). Any
     * layout that comes back empty — a row that lost its controls in an older build — is healed from its
     * finger-count preset for display so the list never shows "0 of 0" for a layout that has a preset to
     * fall back to; the healed rows are persisted lazily on the next load below, not from this Flow.
     */
    override val layouts: Flow<List<ControlLayout>> =
        layoutDao.observeLayoutsWithControls().map { rows ->
            rows.map { row ->
                val model = AimLabMappers.toModel(row)
                if (model.isEmpty) model.healed() else model
            }
        }

    override suspend fun layout(id: Long): ControlLayout? = withContext(io) {
        val row = layoutDao.layoutWithControls(id) ?: return@withContext null
        val model = AimLabMappers.toModel(row)
        if (!model.isEmpty) return@withContext model
        // Self-heal on load (§3): a layout that lost its controls is rebuilt from its finger-count preset,
        // keeping its name and id, and written back so the repair is permanent and non-destructive.
        val healed = model.healed()
        saveLayout(healed)
        healed
    }

    override suspend fun saveLayout(layout: ControlLayout): Long = withContext(io) {
        val now = System.currentTimeMillis()
        val entity = AimLabMappers.toEntity(layout, now)
        // Save the layout and every control row (both orientations) in one Room transaction (§bug-fix):
        // the controls are inserted under the layout's resolved id in the same transaction as the layout,
        // so a saved layout can never end up with zero control rows.
        val resolvedId = if (entity.id == 0L) 0L else entity.id
        val controls = AimLabMappers.controlEntities(layout, resolvedId)
        layoutDao.save(entity, controls)
    }

    override suspend fun deleteLayout(id: Long) = withContext(io) {
        layoutDao.deleteWithControls(id)
    }

    // -------------------------------------------------------------------------- seeding

    /**
     * Populates the built-in weapons, the three sensitivity presets and a default layout — but only the
     * ones that are missing, and only when asked.
     *
     * Called from the Aim Lab home screen the first time it opens, never at process start (§ "initialize
     * only when the user opens it"): a user who never touches Aim Lab pays nothing for its defaults. Each
     * table is checked independently so a user who deleted every weapon does not get the sensitivity
     * presets re-seeded on top of their own.
     */
    override suspend fun seedDefaultsIfEmpty() = withContext(io) {
        if (weaponDao.count() == 0) {
            builtInWeapons().forEach { weaponDao.insert(AimLabMappers.toEntity(it)) }
        }
        if (sensitivityDao.count() == 0) {
            defaultSensitivities().forEach { sensitivityDao.insert(AimLabMappers.toEntity(it)) }
        }
        if (layoutDao.count() == 0) {
            // Seed through the one save path so the default layout persists both orientations' controls.
            saveLayout(ControlLayout.preset(LayoutPreset.THREE_FINGER))
        }
    }

    /**
     * One training weapon per category, marked built-in so the editor offers "duplicate to edit" rather
     * than editing them in place. These are training presets, not real-world weapon data (§7).
     */
    private fun builtInWeapons(): List<Weapon> = listOf(
        Weapon(
            name = "Sidearm", category = WeaponCategory.PISTOL, isBuiltIn = true,
            fireRateRpm = 400, magazineSize = 12, reloadMillis = 1_400L, adsTimeMillis = 180L,
            spread = 0.006f, fireMode = FireMode.SINGLE,
            recoil = RecoilSpec(verticalPerShot = 0.012f, horizontalPerShot = 0.006f, randomness = 0.2f, recoveryPerSecond = 3.5f),
        ),
        Weapon(
            name = "Compact SMG", category = WeaponCategory.SMG, isBuiltIn = true,
            fireRateRpm = 900, magazineSize = 30, reloadMillis = 1_800L, adsTimeMillis = 160L,
            spread = 0.014f, fireMode = FireMode.AUTO,
            recoil = RecoilSpec(verticalPerShot = 0.016f, horizontalPerShot = 0.012f, randomness = 0.45f, recoveryPerSecond = 2.5f),
        ),
        Weapon(
            name = "Service Rifle", category = WeaponCategory.ASSAULT_RIFLE, isBuiltIn = true,
            fireRateRpm = 600, magazineSize = 30, reloadMillis = 2_200L, adsTimeMillis = 240L,
            spread = 0.010f, fireMode = FireMode.AUTO,
            recoil = RecoilSpec(verticalPerShot = 0.020f, horizontalPerShot = 0.008f, randomness = 0.3f, recoveryPerSecond = 2.5f),
        ),
        Weapon(
            name = "Support LMG", category = WeaponCategory.LMG, isBuiltIn = true,
            fireRateRpm = 750, magazineSize = 100, reloadMillis = 4_500L, adsTimeMillis = 360L,
            movementPenalty = 0.7f, spread = 0.018f, fireMode = FireMode.AUTO,
            recoil = RecoilSpec(verticalPerShot = 0.024f, horizontalPerShot = 0.016f, randomness = 0.5f, recoveryPerSecond = 1.8f),
        ),
        Weapon(
            name = "Pump Shotgun", category = WeaponCategory.SHOTGUN, isBuiltIn = true,
            fireRateRpm = 70, magazineSize = 6, reloadMillis = 3_500L, adsTimeMillis = 260L,
            spread = 0.060f, fireMode = FireMode.SINGLE,
            recoil = RecoilSpec(verticalPerShot = 0.040f, horizontalPerShot = 0.010f, randomness = 0.4f, recoveryPerSecond = 4.0f),
        ),
        Weapon(
            name = "Marksman Rifle", category = WeaponCategory.SNIPER, isBuiltIn = true,
            fireRateRpm = 55, magazineSize = 5, reloadMillis = 3_000L, adsTimeMillis = 500L,
            movementPenalty = 0.8f, spread = 0.002f, fireMode = FireMode.SINGLE,
            recoil = RecoilSpec(verticalPerShot = 0.055f, horizontalPerShot = 0.004f, randomness = 0.15f, recoveryPerSecond = 3.0f),
        ),
        // A burst-fire trainer, so the burst mode is exercised out of the box.
        Weapon(
            name = "Burst Carbine", category = WeaponCategory.ASSAULT_RIFLE, isBuiltIn = true,
            fireRateRpm = 800, magazineSize = 30, reloadMillis = 2_100L, adsTimeMillis = 220L,
            spread = 0.008f, fireMode = FireMode.BURST, burstCount = 3,
            recoil = RecoilSpec(verticalPerShot = 0.022f, horizontalPerShot = 0.007f, randomness = 0.25f, recoveryPerSecond = 3.0f),
        ),
    )

    private fun defaultSensitivities(): List<SensitivityProfile> =
        listOf(SensitivityPreset.LOW, SensitivityPreset.MEDIUM, SensitivityPreset.HIGH).map { preset ->
            SensitivityProfile(
                name = preset.label,
                preset = preset,
                cameraSensitivity = preset.camera,
                gyroSensitivity = preset.gyro,
            )
        }
}
