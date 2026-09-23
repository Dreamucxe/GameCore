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

    /**
     * The colour correction to apply for this game, or null to leave the screen alone.
     *
     * Nullable for the same reason as the two ids above, and added in schema version 2 — which
     * is why the migration adds it as a plain nullable INTEGER with no default: a profile that
     * existed before the colour feature did wanted nothing done to the display, and null is
     * exactly that.
     */
    @ColumnInfo(name = "color_preset_id")
    val colorPresetId: Long?,

    /**
     * The display size to stretch to while this game runs as a `WxH` token, or null to leave
     * the display alone.
     *
     * One TEXT column rather than two nullable integers, because a width without a height is not
     * a size and two columns can express that. The token is
     * [com.gamecore.core.model.DisplaySize.argument] — the same string `wm size` takes — parsed
     * back through [com.gamecore.core.model.DisplaySize.parse], which returns null for anything
     * it does not recognise. Added in schema version 3, nullable with no default for the reason
     * `color_preset_id` was: a profile written before this existed asked for nothing.
     */
    @ColumnInfo(name = "display_size")
    val displaySize: String?,

    @ColumnInfo(name = "performance_mode")
    val performanceMode: String,

    @ColumnInfo(name = "use_shizuku")
    val useShizukuOptimizations: Boolean,

    /**
     * Whether to close background apps when this game starts.
     *
     * Added in schema version 4, and the first additive column here that is not nullable: "leave
     * the user's other apps alone" is a real default rather than an absence of instruction, so the
     * migration adds it as `INTEGER NOT NULL DEFAULT 0` and every profile written before the
     * feature existed reads back as off — which is also what it is for a new profile.
     */
    @ColumnInfo(name = "free_ram_on_launch")
    val freeRamOnLaunch: Boolean,

    @ColumnInfo(name = "track_session")
    val trackSession: Boolean,

    /**
     * The CPU core group to restrict this game's process to, by
     * [com.gamecore.core.model.CpuAffinityPreset] name, or null to leave the scheduler alone.
     *
     * Added in schema version 6, nullable with no default, and the nullability is the design rather
     * than a migration convenience: "leave it to the OS" is the absence of an instruction, so there
     * is no `LEAVE_TO_OS` member to store and no row that could hold one. A profile written before
     * this feature existed reads back as null, which is precisely what it meant.
     *
     * Parsed back the way every enum in this table is — matched against
     * `CpuAffinityPreset.entries` by name, never `valueOf` — so a name this build has dropped reads
     * as null and the profile stops pinning cores instead of crashing on load.
     */
    @ColumnInfo(name = "cpu_affinity")
    val cpuAffinity: String?,

    // ---- 3.5 additions (schema v11). All default OFF/absent so every existing profile reads exactly as
    // it did before the feature existed — the same discipline free_ram_on_launch and cpu_affinity follow.

    /** Thermal auto-downshift opt-in (§B). Off for every pre-3.5 profile. */
    @ColumnInfo(name = "thermal_downshift_enabled")
    val thermalDownshiftEnabled: Boolean = false,

    /** Trigger by temperature limit (tenths °C), or null to trigger on platform status alone. */
    @ColumnInfo(name = "thermal_limit_deci")
    val thermalLimitDeciCelsius: Int? = null,

    /** Trigger at or above this [com.gamecore.core.model.ThermalClass] name, or null for temperature only. */
    @ColumnInfo(name = "thermal_status_floor")
    val thermalStatusFloor: String? = null,

    /** The rate the ladder will not step below (Hz), or null to use the profile's target as the floor. */
    @ColumnInfo(name = "thermal_floor_rate")
    val thermalFloorRateHz: Float? = null,

    /** Hysteresis in tenths °C; null falls back to the machine's 5 °C default. */
    @ColumnInfo(name = "thermal_hysteresis_deci")
    val thermalHysteresisDeciCelsius: Int? = null,

    @ColumnInfo(name = "thermal_sustain_hot_millis")
    val thermalSustainHotMillis: Long? = null,

    @ColumnInfo(name = "thermal_sustain_cool_millis")
    val thermalSustainCoolMillis: Long? = null,

    @ColumnInfo(name = "thermal_min_interval_millis")
    val thermalMinIntervalMillis: Long? = null,

    /** Network check opt-in (§C). Off for every pre-3.5 profile. */
    @ColumnInfo(name = "network_check_enabled")
    val networkCheckEnabled: Boolean = false,

    /** Whether the pre-launch check warns on a poor result (§C4). */
    @ColumnInfo(name = "network_prelaunch_warn")
    val networkPreLaunchWarn: Boolean = true,

    /** In-session poor-network alerts (§C5). */
    @ColumnInfo(name = "network_alerts_enabled")
    val networkAlertsEnabled: Boolean = false,

    /** "Keep full performance" opt-in (§D). Off for every pre-3.5 profile. */
    @ColumnInfo(name = "full_performance_enabled")
    val fullPerformanceEnabled: Boolean = false,

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

/**
 * A colour preset's row. Flat, one column per slider, for the same reason
 * [CrosshairPresetEntity] is flat and the opposite reason to the session's colour column.
 *
 * A preset is queried, listed, renamed and edited field by field, so its fields are
 * columns: the picker sorts on them, and a future "which of my presets asks for gamma"
 * is a `WHERE`, not fourteen string splits. The session's reading of the *same* fourteen
 * values is one [com.gamecore.core.model.ColorCodec] string, because that one is written
 * once with a session row and read once by a report.
 *
 * Both gamma sets are stored at once — the combined slider and the three per-channel
 * ones — with [gammaMode] recording which the user was looking at. Storing only the
 * active set would lose the other on every mode switch, and a preset that forgets half
 * its values when you glance at the other tab is not a preset.
 */
@Entity(tableName = "color_presets")
data class ColorPresetEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "red_gain")
    val redGain: Int,

    @ColumnInfo(name = "green_gain")
    val greenGain: Int,

    @ColumnInfo(name = "blue_gain")
    val blueGain: Int,

    /** The enum name, as with every other stored enum here. */
    @ColumnInfo(name = "gamma_mode")
    val gammaMode: String,

    @ColumnInfo(name = "gamma")
    val gamma: Int,

    @ColumnInfo(name = "red_gamma")
    val redGamma: Int,

    @ColumnInfo(name = "green_gamma")
    val greenGamma: Int,

    @ColumnInfo(name = "blue_gamma")
    val blueGamma: Int,

    @ColumnInfo(name = "saturation")
    val saturation: Int,

    @ColumnInfo(name = "contrast")
    val contrast: Int,

    @ColumnInfo(name = "hue_degrees")
    val hueDegrees: Int,

    @ColumnInfo(name = "brightness_offset")
    val brightnessOffset: Int,

    @ColumnInfo(name = "vision_filter")
    val visionFilter: String,

    @ColumnInfo(name = "invert_colors")
    val invertColors: Boolean,
)
