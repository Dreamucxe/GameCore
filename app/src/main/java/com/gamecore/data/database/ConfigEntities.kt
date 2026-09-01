package com.gamecore.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A game profile as stored.
 *
 * A separate type from [com.gamecore.core.model.GameProfile] rather than annotating the model,
 * for two reasons that both bite in practice. The domain model uses nullability to mean "leave
 * this setting alone", and Room's `@Entity` on the same class would force the storage schema to
 * follow every refactor of that meaning. And the mapping is where the sanitisation happens:
 * [com.gamecore.data.database.Mappers] runs the label through [TextSanitizer] on the way in, so
 * there is no path from a package manager label to a stored row that skips it.
 *
 * [packageName] is the primary key. One profile per game is the whole model — a second profile
 * for the same package could not be chosen between at launch time without asking the user in
 * the middle of starting a game.
 */
@Entity(tableName = "game_profiles")
data class GameProfileEntity(
    @PrimaryKey
    @ColumnInfo(name = "package_name")
    val packageName: String,

    @ColumnInfo(name = "label")
    val label: String,

    @ColumnInfo(name = "is_enabled")
    val isEnabled: Boolean,

    @ColumnInfo(name = "target_refresh_rate")
    val targetRefreshRate: Float?,

    @ColumnInfo(name = "brightness_percent")
    val brightnessPercent: Int?,

    /** Stored as the enum name; a renamed constant is a migration, which is the point. */
    @ColumnInfo(name = "rotation_lock")
    val rotationLock: String?,

    @ColumnInfo(name = "screen_timeout_millis")
    val screenTimeoutMillis: Long?,

    @ColumnInfo(name = "media_volume_percent")
    val mediaVolumePercent: Int?,

    @ColumnInfo(name = "enable_dnd")
    val enableDoNotDisturb: Boolean,

    @ColumnInfo(name = "show_floating_button")
    val showFloatingButton: Boolean,

    @ColumnInfo(name = "show_performance_pill")
    val showPerformancePill: Boolean,

    @ColumnInfo(name = "show_crosshair")
    val showCrosshair: Boolean,

    @ColumnInfo(name = "hud_layout_id")
    val hudLayoutId: Long?,

    @ColumnInfo(name = "crosshair_preset_id")
    val crosshairPresetId: Long?,

    @ColumnInfo(name = "performance_mode")
    val performanceMode: String,

    @ColumnInfo(name = "use_shizuku")
    val useShizukuOptimizations: Boolean,

    @ColumnInfo(name = "track_session")
    val trackSession: Boolean,

    @ColumnInfo(name = "updated_at")
    val updatedAtMillis: Long,
)

/**
 * A HUD layout's own row. Its widgets are separate rows in [HudWidgetEntity].
 *
 * Widgets are not stored as a JSON blob in this row, which is the tempting shortcut. A blob
 * cannot be queried, cannot be migrated field-by-field, and — the reason that actually matters
 * here — would put a hand-parsed structure in the path between an imported layout file and the
 * overlay renderer. Rows go through Room's own type checking instead.
 */
@Entity(tableName = "hud_layouts")
data class HudLayoutEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "created_at")
    val createdAtMillis: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAtMillis: Long,
)

/**
 * One widget on one layout.
 *
 * `onDelete = CASCADE` is deliberately *not* declared as a foreign key. SQLCipher honours
 * foreign keys only when `PRAGMA foreign_keys` is on, and Room turns it on per-connection —
 * which means a cascade that works in a test can silently not fire on a connection opened by a
 * different code path. The DAO deletes a layout's widgets explicitly inside the same
 * transaction, which does not depend on a pragma being set.
 */
@Entity(
    tableName = "hud_widgets",
    indices = [Index("layout_id")],
)
data class HudWidgetEntity(
    @PrimaryKey
    @ColumnInfo(name = "widget_id")
    val widgetId: String,

    @ColumnInfo(name = "layout_id")
    val layoutId: Long,

    @ColumnInfo(name = "stat")
    val stat: String,

    @ColumnInfo(name = "x_fraction")
    val xFraction: Float,

    @ColumnInfo(name = "y_fraction")
    val yFraction: Float,

    @ColumnInfo(name = "text_size_sp")
    val textSizeSp: Int,

    @ColumnInfo(name = "opacity_percent")
    val opacityPercent: Int,

    @ColumnInfo(name = "show_label")
    val showLabel: Boolean,

    @ColumnInfo(name = "show_background")
    val showBackground: Boolean,

    @ColumnInfo(name = "color_argb")
    val colorArgb: Int,
)

/** A crosshair preset's row. Flat: every field is a primitive the renderer reads directly. */
@Entity(tableName = "crosshair_presets")
data class CrosshairPresetEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "design")
    val design: String,

    @ColumnInfo(name = "size_dp")
    val sizeDp: Int,

    @ColumnInfo(name = "thickness_dp")
    val thicknessDp: Int,

    @ColumnInfo(name = "centre_gap_dp")
    val centreGapDp: Int,

    @ColumnInfo(name = "opacity_percent")
    val opacityPercent: Int,

    @ColumnInfo(name = "rotation_degrees")
    val rotationDegrees: Int,

    @ColumnInfo(name = "color_argb")
    val colorArgb: Int,

    @ColumnInfo(name = "show_dot")
    val showDot: Boolean,

    @ColumnInfo(name = "show_outline")
    val showOutline: Boolean,

    @ColumnInfo(name = "x_fraction")
    val xFraction: Float,

    @ColumnInfo(name = "y_fraction")
    val yFraction: Float,

    @ColumnInfo(name = "image_path")
    val imagePath: String?,
)
