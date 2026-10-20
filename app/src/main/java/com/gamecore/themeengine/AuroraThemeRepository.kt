package com.gamecore.themeengine

import android.content.Context
import com.gamecore.core.common.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the Theme Engine's save points live.
 *
 * A small, local, favourite-shaped interface rather than a database: a calibration preset is one row of
 * numbers a user saves by name, the whole set is smaller than most single settings blobs, and it has to
 * be readable at app start before any screen asks for it. SharedPreferences is exactly the right size
 * of tool; Room would be a table, a migration and a DAO for a file that fits in a few hundred bytes.
 *
 * The interface exists for the same reason the rest of this app's repositories do: the calibration
 * screen and the plugin can be tested, and later pointed at another store, without either of them
 * knowing what a `SharedPreferences` is. Nothing here is Android-specific.
 */
interface AuroraThemeRepository {

    /** The built-in preset first, then the user's save points, oldest first. */
    val presets: StateFlow<List<CalibrationPreset>>

    /** The preset currently in force. Never null: the built-in default stands in for "nothing selected". */
    val activePreset: StateFlow<CalibrationPreset>

    /** Inserts or replaces one preset, normalised. Returns the stored copy. */
    suspend fun upsert(preset: CalibrationPreset): CalibrationPreset

    /** Removes a save point. The built-in default cannot be removed. */
    suspend fun delete(id: String)

    /** Puts one preset in force, or returns to the built-in default when [id] is null or unknown. */
    suspend fun select(id: String?)
}

/**
 * The native store: one JSON array and one id in the app's private preferences.
 *
 * Writes are serialised by a mutex and done on the IO dispatcher. Two saves racing used to be the
 * classic way to lose one of them with this shape — read the array, append in memory, write it back —
 * so the whole read-modify-write happens under one lock, and the in-memory state is only published
 * after the write returned. A save point the store refused is not one the screen shows.
 */
@Singleton
class SharedPreferencesAuroraRepository @Inject constructor(
    @ApplicationContext context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : AuroraThemeRepository {

    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val lock = Mutex()

    private val storedState = MutableStateFlow(AuroraPresetCodec.decode(preferences.getString(KEY_PRESETS, null)))

    private val activeIdState = MutableStateFlow(
        preferences.getString(KEY_ACTIVE, null)?.takeIf { it.isNotBlank() },
    )

    private val presetsState =
        MutableStateFlow(allPresets(storedState.value, activeIdState.value))

    private val activeState =
        MutableStateFlow(resolveActive(storedState.value, activeIdState.value))

    override val presets: StateFlow<List<CalibrationPreset>> = presetsState.asStateFlow()

    override val activePreset: StateFlow<CalibrationPreset> = activeState.asStateFlow()

    override suspend fun upsert(preset: CalibrationPreset): CalibrationPreset =
        withContext(io) {
            lock.withLock { upsertLocked(preset) }
        }

    private fun upsertLocked(preset: CalibrationPreset): CalibrationPreset {
        val normalised = preset.normalised()
        val next = storedState.value
            .filterNot { it.id == normalised.id }
            .plus(normalised)
            .take(AuroraBounds.MAX_PRESETS)
        if (!writeLocked(next, activeIdState.value)) return normalised
        // The user saved this one by hand, so it is the one they mean to be looking at.
        activeIdState.value = normalised.id
        preferences.edit().putString(KEY_ACTIVE, normalised.id).apply()
        publish(next, normalised.id)
        return normalised
    }

    override suspend fun delete(id: String) = withContext(io) {
        lock.withLock {
            // The built-in preset is the way back from any bad save point; deleting it would leave the
            // engine with no fallback at all.
            if (id == AuroraDefaults.DEFAULT_PRESET_ID) return@withLock
            val wasActive = activeIdState.value == id
            val next = storedState.value.filterNot { it.id == id }
            val nextActive = if (wasActive) null else activeIdState.value
            if (!writeLocked(next, nextActive)) return@withLock
            activeIdState.value = nextActive
            publish(next, nextActive)
        }
    }

    override suspend fun select(id: String?) = withContext(io) {
        lock.withLock {
            val resolved = id?.takeIf { candidate -> allPresets(storedState.value, null).any { it.id == candidate } }
            preferences.edit().putString(KEY_ACTIVE, resolved).apply()
            activeIdState.value = resolved
            publish(storedState.value, resolved)
        }
    }

    /** Writes the array; false when the platform refused the write, in which case nothing is published. */
    private fun writeLocked(presets: List<CalibrationPreset>, activeId: String?): Boolean {
        val committed = preferences.edit()
            .putString(KEY_PRESETS, AuroraPresetCodec.encode(presets))
            .putString(KEY_ACTIVE, activeId)
            .commit()
        if (committed) storedState.value = presets
        return committed
    }

    private fun publish(stored: List<CalibrationPreset>, activeId: String?) {
        presetsState.value = allPresets(stored, activeId)
        activeState.value = resolveActive(stored, activeId)
    }

    private fun allPresets(stored: List<CalibrationPreset>, activeId: String?): List<CalibrationPreset> {
        // A user may legitimately replace the built-in "Balanced" preset; the built-in copy only
        // appears when nothing with that id has been saved.
        val base = if (stored.any { it.id == AuroraDefaults.DEFAULT_PRESET_ID }) {
            emptyList()
        } else {
            listOf(AuroraDefaults.preset())
        }
        val ordered = base + stored
        // The selected one is not reordered: chips that jump when chosen are the same complaint as a
        // plugin row that moves under a finger.
        return ordered.distinctBy { it.id }
    }

    private fun resolveActive(stored: List<CalibrationPreset>, activeId: String?): CalibrationPreset =
        allPresets(stored, activeId).firstOrNull { it.id == activeId } ?: AuroraDefaults.preset()

    private companion object {
        const val PREFERENCES_NAME = "gamecore.themeengine"
        const val KEY_PRESETS = "presets"
        const val KEY_ACTIVE = "active"
    }
}
