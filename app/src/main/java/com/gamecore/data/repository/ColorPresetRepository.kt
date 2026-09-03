package com.gamecore.data.repository

import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.model.ColorPreset
import com.gamecore.data.database.ColorPresetDao
import com.gamecore.data.database.Mappers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Colour presets, as domain models.
 *
 * DAO plumbing plus two rules, and both of them come from the fact that a preset id is stored on
 * game profiles.
 *
 * **Deleting a preset detaches it from every profile first.** A profile pointing at a row that no
 * longer exists would apply nothing and count as having applied something, and the game before it
 * would keep owning the screen's colour. That is the silent-failure shape this app is built to
 * avoid, so the reference is cleared in the same call.
 *
 * **The seven shipped presets are ordinary rows.** They are seeded once, into an empty table, and
 * from then on they are editable, renameable and deletable like any other — which is what makes
 * them starting points rather than a fixed menu. Nothing re-seeds them behind the user's back on
 * later launches; [seedDefaultsIfEmpty] does nothing at all once the table has anything in it,
 * including when what it has is one preset the user made after deleting the rest.
 *
 * There is no cap on how many a user can save. The only limit is the storage the rows sit in.
 */
@Singleton
class ColorPresetRepository @Inject constructor(
    private val dao: ColorPresetDao,
    private val profiles: GameProfileRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * Every preset, re-emitting on change.
     *
     * Observed by the colour screen, the overlay panel's chip row and the profile editor's preset
     * dropdown at once, so the conversion is here rather than in three collectors.
     */
    val presets: Flow<List<ColorPreset>> =
        dao.observeAll().map { rows -> rows.map(Mappers::toModel) }

    suspend fun preset(id: Long): ColorPreset? = withContext(io) {
        dao.byId(id)?.let(Mappers::toModel)
    }

    suspend fun all(): List<ColorPreset> = withContext(io) {
        dao.all().map(Mappers::toModel)
    }

    /** Returns the stored id, which differs from the argument's when it was 0 (a new preset). */
    suspend fun save(preset: ColorPreset): Long = withContext(io) {
        dao.insert(Mappers.toEntity(preset))
    }

    /**
     * Saves a copy under a new name, which is what "Save as preset" does from the editor.
     *
     * Explicitly zeroes the id so a user who opened "Night", moved a slider and saved gets an
     * eighth preset rather than a rewritten "Night". Overwriting is [save] with the id kept, and
     * the two are different enough intents that the screen should not be able to confuse them by
     * forgetting to reset a field.
     */
    suspend fun saveAsNew(name: String, preset: ColorPreset): Long =
        save(preset.copy(id = 0L, name = name))

    /**
     * Deletes a preset and every profile's reference to it.
     *
     * The order matters on a slow device: the row goes first so a profile activating during the
     * delete cannot read a preset that is about to vanish, and the detach follows so no profile is
     * left holding the id.
     */
    suspend fun delete(id: Long) {
        withContext(io) { dao.delete(id) }
        profiles.clearColorReferences(id)
    }

    /**
     * Creates the shipped presets if the table is empty, and reports whether it did.
     *
     * Called from the colour screen and from the overlay panel's first expansion rather than at
     * process start, for the reason [CrosshairRepository.seedDefaultsIfEmpty] gives: a first launch
     * showing an empty list with an "add" button is worse than showing seven presets to try, and
     * seeding on demand keeps seven inserts out of `Application.onCreate` for a user who never
     * opens the feature.
     */
    suspend fun seedDefaultsIfEmpty(): Boolean = withContext(io) {
        if (dao.count() > 0) return@withContext false
        ColorPreset.builtIns().forEach { preset -> dao.insert(Mappers.toEntity(preset)) }
        true
    }

    /**
     * Puts the shipped presets back, keeping whatever the user has made.
     *
     * The "restore built-in presets" action. Matches on name, because that is the only handle a
     * user has on them — a "Night" they edited into something else is left alone rather than
     * silently reverted, and a "Night" they deleted comes back. Returns how many were re-added, so
     * the screen can say "nothing was missing" rather than claiming to have done something.
     */
    suspend fun restoreMissingDefaults(): Int = withContext(io) {
        val existing = dao.all().map { it.name.trim().lowercase() }.toSet()
        val missing = ColorPreset.builtIns()
            .filter { it.name.trim().lowercase() !in existing }
        missing.forEach { preset -> dao.insert(Mappers.toEntity(preset)) }
        missing.size
    }
}
