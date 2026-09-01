package com.gamecore.data.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Game profiles.
 *
 * [observeAll] returns a `Flow` because the Games screen and the detection service both need to
 * react to a profile being edited, and a service polling the database every few seconds to see
 * whether the user changed something is the alternative.
 *
 * Every query is parameterised. Room would not compile a string-concatenated one, which is the
 * main reason the database layer is Room rather than raw SQLCipher statements.
 */
@Dao
interface GameProfileDao {

    @Query("SELECT * FROM game_profiles ORDER BY label COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<GameProfileEntity>>

    @Query("SELECT * FROM game_profiles WHERE is_enabled = 1")
    suspend fun enabled(): List<GameProfileEntity>

    @Query("SELECT * FROM game_profiles WHERE package_name = :packageName LIMIT 1")
    suspend fun byPackage(packageName: String): GameProfileEntity?

    @Query("SELECT * FROM game_profiles WHERE package_name = :packageName LIMIT 1")
    fun observeByPackage(packageName: String): Flow<GameProfileEntity?>

    @Query("SELECT COUNT(*) FROM game_profiles")
    fun observeCount(): Flow<Int>

    @Upsert
    suspend fun upsert(profile: GameProfileEntity)

    @Query("DELETE FROM game_profiles WHERE package_name = :packageName")
    suspend fun delete(packageName: String)

    /**
     * Clears a HUD layout reference from every profile that used it.
     *
     * Called when a layout is deleted. Without it a profile would hold an id that resolves to
     * nothing and the overlay would come up empty with no explanation — the same class of
     * silent failure as a control that reports success and does nothing.
     */
    @Query("UPDATE game_profiles SET hud_layout_id = NULL WHERE hud_layout_id = :layoutId")
    suspend fun clearHudLayout(layoutId: Long)

    @Query("UPDATE game_profiles SET crosshair_preset_id = NULL WHERE crosshair_preset_id = :presetId")
    suspend fun clearCrosshairPreset(presetId: Long)
}

/**
 * HUD layouts and their widgets.
 *
 * The write methods are `@Transaction` because a layout and its widgets are one thing from the
 * user's point of view: a save that inserted the layout row and then failed on the widgets would
 * leave a named empty layout in the list.
 */
@Dao
interface HudLayoutDao {

    @Query("SELECT * FROM hud_layouts ORDER BY updated_at DESC")
    fun observeLayouts(): Flow<List<HudLayoutEntity>>

    @Query("SELECT * FROM hud_layouts WHERE id = :id LIMIT 1")
    suspend fun layout(id: Long): HudLayoutEntity?

    @Query("SELECT * FROM hud_widgets WHERE layout_id = :layoutId")
    suspend fun widgets(layoutId: Long): List<HudWidgetEntity>

    @Query("SELECT * FROM hud_widgets")
    suspend fun allWidgets(): List<HudWidgetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLayout(layout: HudLayoutEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWidgets(widgets: List<HudWidgetEntity>)

    @Query("DELETE FROM hud_widgets WHERE layout_id = :layoutId")
    suspend fun deleteWidgets(layoutId: Long)

    @Query("DELETE FROM hud_layouts WHERE id = :id")
    suspend fun deleteLayout(id: Long)

    /**
     * Replaces a layout's widgets wholesale.
     *
     * A diff would be fewer statements and more ways to be wrong: the builder lets a user drag,
     * resize, add and delete in one editing session, and reconciling that against stored rows
     * has no advantage over deleting eleven rows and writing twelve.
     */
    @Transaction
    suspend fun save(layout: HudLayoutEntity, widgets: List<HudWidgetEntity>): Long {
        val id = insertLayout(layout)
        val resolved = if (layout.id == 0L) id else layout.id
        deleteWidgets(resolved)
        if (widgets.isNotEmpty()) {
            insertWidgets(widgets.map { it.copy(layoutId = resolved) })
        }
        return resolved
    }

    @Transaction
    suspend fun deleteWithWidgets(id: Long) {
        deleteWidgets(id)
        deleteLayout(id)
    }
}

@Dao
interface CrosshairPresetDao {

    @Query("SELECT * FROM crosshair_presets ORDER BY id ASC")
    fun observeAll(): Flow<List<CrosshairPresetEntity>>

    @Query("SELECT * FROM crosshair_presets WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): CrosshairPresetEntity?

    @Query("SELECT COUNT(*) FROM crosshair_presets")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(preset: CrosshairPresetEntity): Long

    @Query("DELETE FROM crosshair_presets WHERE id = :id")
    suspend fun delete(id: Long)

    @Delete
    suspend fun delete(preset: CrosshairPresetEntity)
}
