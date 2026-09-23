package com.gamecore.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Hand-written schema migrations, one per version step.
 *
 * There is no `fallbackToDestructiveMigration` in this app, and this object is why. The database
 * holds session history a user cannot regenerate — a two-month record of how their device behaved
 * — and "the schema changed so your history is gone" is not a thing an app gets to do quietly on
 * an update it chose to ship. Every statement here is additive: new table, new nullable columns,
 * no drops and no rewrites.
 *
 * The SQL is written to match what Room generates for the same entities, because Room verifies the
 * schema on open and a column that differs in nullability or affinity throws
 * `IllegalStateException` at that point — on launch, for every user who updates. The exact
 * `CREATE TABLE` text is taken from the exported schema JSON in `app/schemas`, which is what
 * `exportSchema = true` is there for.
 *
 * Column order is not part of the comparison. `ALTER TABLE ADD COLUMN` can only append, so a
 * migrated `game_profiles` has `color_preset_id` last where a fresh install has it in the middle;
 * Room reads `PRAGMA table_info` into a map keyed by name, so both satisfy it.
 */
internal object GameCoreMigrations {

    /**
     * Version 1 → 2: colour correction.
     *
     * Three additions. `color_presets` mirrors `crosshair_presets` — a flat row per preset, one
     * column per slider. `game_profiles.color_preset_id` is nullable with no default because null
     * is the honest value for a profile written before this feature existed: it asked for nothing
     * to be done to the display, and a default of any preset id would silently start correcting
     * the colour of every game the user already had a profile for. The two `sessions` columns are
     * nullable for the same reason — a session recorded last month has no colour reading, and NULL
     * says that where an empty string would read as "no correction was active", a claim this app
     * has no evidence for.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `color_presets` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL, " +
                    "`red_gain` INTEGER NOT NULL, " +
                    "`green_gain` INTEGER NOT NULL, " +
                    "`blue_gain` INTEGER NOT NULL, " +
                    "`gamma_mode` TEXT NOT NULL, " +
                    "`gamma` INTEGER NOT NULL, " +
                    "`red_gamma` INTEGER NOT NULL, " +
                    "`green_gamma` INTEGER NOT NULL, " +
                    "`blue_gamma` INTEGER NOT NULL, " +
                    "`saturation` INTEGER NOT NULL, " +
                    "`contrast` INTEGER NOT NULL, " +
                    "`hue_degrees` INTEGER NOT NULL, " +
                    "`brightness_offset` INTEGER NOT NULL, " +
                    "`vision_filter` TEXT NOT NULL, " +
                    "`invert_colors` INTEGER NOT NULL)",
            )
            db.execSQL("ALTER TABLE `game_profiles` ADD COLUMN `color_preset_id` INTEGER")
            db.execSQL("ALTER TABLE `sessions` ADD COLUMN `color_preset` TEXT")
            db.execSQL("ALTER TABLE `sessions` ADD COLUMN `color_values` TEXT")
        }
    }

    /**
     * Version 2 → 3: per-game display size.
     *
     * One nullable TEXT column holding a `WxH` token, and nullable for a sharper version of the
     * reason `color_preset_id` is. A `wm size` override outlives a reboot and is put back only
     * because a profile carrying one says to put it back; a default here would hand every existing
     * profile a size it never asked for, and the first launch after the update would stretch the
     * display of every game the user already had configured.
     *
     * TEXT rather than two integers so that a width cannot exist without a height. The token is
     * parsed on the way out and an unrecognised one reads as null, which is the same as the column
     * being absent — so a row from a hand-edited database costs the user nothing.
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `game_profiles` ADD COLUMN `display_size` TEXT")
        }
    }

    /**
     * Version 3 → 4: the per-game switch for closing background apps at launch.
     *
     * The first additive column in this file with a default, and the first that is `NOT NULL`. Both
     * follow from what the column means: the other three additions were "which preset, if any", where
     * absence is a complete answer, and this one is "should GameCore close your other apps", where
     * absence is not an answer at all. `DEFAULT 0` is the whole point — every profile that existed
     * before this feature did reads back with the feature off, which is also what a profile created
     * after it reads back as until the user turns it on.
     *
     * The entity field is a plain non-null `Boolean` with no `@ColumnInfo(defaultValue = …)`: the
     * default belongs to this migration, which is where existing rows are filled in, and declaring it
     * on the entity as well would put a `defaultValue` in the exported schema that the migrated table
     * does not carry, failing Room's own schema verification for a value nothing reads.
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `game_profiles` " +
                    "ADD COLUMN `free_ram_on_launch` INTEGER NOT NULL DEFAULT 0",
            )
        }
    }

    /**
     * Version 4 → 5: the per-session latency probe log.
     *
     * Six nullable INTEGER columns on `sessions`, and nullable rather than `DEFAULT 0` for the reason
     * the colour columns are: absence here is a complete answer. A session recorded before this feature
     * existed had no log kept for it, and filling those rows with zeroes would state that no probe
     * failed and none spiked during a session nobody was counting — a measurement invented by a
     * migration. NULL reads back as "no log", which is what happened.
     *
     * `latency_probes` is the presence flag the mapper tests, so all six are written together or not at
     * all. Deliberately six columns and not a table: the samples are already summarised by the time
     * they get here, and a session's probe log is written with the session and read with it.
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `sessions` ADD COLUMN `latency_probes` INTEGER")
            db.execSQL("ALTER TABLE `sessions` ADD COLUMN `latency_failed` INTEGER")
            db.execSQL("ALTER TABLE `sessions` ADD COLUMN `latency_spikes` INTEGER")
            db.execSQL("ALTER TABLE `sessions` ADD COLUMN `latency_worst` INTEGER")
            db.execSQL("ALTER TABLE `sessions` ADD COLUMN `latency_jitter` INTEGER")
            db.execSQL("ALTER TABLE `sessions` ADD COLUMN `latency_failed_run` INTEGER")
        }
    }

    /**
     * Version 5 → 6: the per-game CPU core preset.
     *
     * One nullable TEXT column holding a `CpuAffinityPreset` name, and nullable for the plainest
     * version of the reason `display_size` is: null is not a placeholder here, it is the feature's own
     * default. There is no `LEAVE_TO_OS` member to fill existing rows with, because leaving the
     * scheduler alone is the absence of an instruction rather than an instruction — so a `NOT NULL
     * DEFAULT` here would have had to invent a value for a state the enum deliberately does not name.
     *
     * A name this build does not recognise reads back as null through `Mappers.toModel`, and the
     * profile simply stops choosing cores. That is the safe direction: the failure mode of the wrong
     * answer is a game pinned to cores nobody picked.
     */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `game_profiles` ADD COLUMN `cpu_affinity` TEXT")
        }
    }

    /**
     * Version 6 → 7: the Aim Lab tables.
     *
     * Six new tables, no change to any existing one — the largest single migration in this file and still
     * strictly additive. The `CREATE TABLE`/`CREATE INDEX` text matches what Room generates for the
     * entities in `AimLabEntities.kt`, verified against the exported `7.json` after the first build, so
     * Room's open-time schema check passes. Autogenerated `Long` ids are
     * `INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL`; booleans are `INTEGER NOT NULL`; nullable columns omit
     * `NOT NULL`. The controls table has a composite primary key and the records table a unique index on
     * its four key columns, which is what makes a personal record an upsert rather than a read-then-write.
     *
     * There is nothing to back-fill: a user updating from 6 simply has no Aim Lab history yet, which is the
     * truth, so every table starts empty. No `fallbackToDestructiveMigration`, here as everywhere.
     */
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `aimlab_sessions` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`mode` TEXT NOT NULL, " +
                    "`difficulty` TEXT NOT NULL, " +
                    "`started_at` INTEGER NOT NULL, " +
                    "`ended_at` INTEGER NOT NULL, " +
                    "`weapon_name` TEXT, " +
                    "`sensitivity_name` TEXT, " +
                    "`score` INTEGER NOT NULL, " +
                    "`hits` INTEGER NOT NULL, " +
                    "`shots` INTEGER NOT NULL, " +
                    "`targets_missed` INTEGER NOT NULL, " +
                    "`reaction_attempts` INTEGER NOT NULL, " +
                    "`reaction_fastest` INTEGER NOT NULL, " +
                    "`reaction_slowest` INTEGER NOT NULL, " +
                    "`reaction_avg` REAL NOT NULL, " +
                    "`reaction_median` REAL NOT NULL, " +
                    "`avg_acquire` REAL NOT NULL, " +
                    "`tracking_error_avg` REAL NOT NULL, " +
                    "`time_on_target` REAL NOT NULL, " +
                    "`recoil_compensation` REAL NOT NULL, " +
                    "`gyro_stability` REAL NOT NULL)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_aimlab_sessions_mode` ON `aimlab_sessions` (`mode`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_aimlab_sessions_started_at` " +
                    "ON `aimlab_sessions` (`started_at`)",
            )

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `aimlab_weapons` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL, " +
                    "`category` TEXT NOT NULL, " +
                    "`fire_rate_rpm` INTEGER NOT NULL, " +
                    "`magazine_size` INTEGER NOT NULL, " +
                    "`reload_millis` INTEGER NOT NULL, " +
                    "`ads_time_millis` INTEGER NOT NULL, " +
                    "`movement_penalty` REAL NOT NULL, " +
                    "`spread` REAL NOT NULL, " +
                    "`vertical_per_shot` REAL NOT NULL, " +
                    "`horizontal_per_shot` REAL NOT NULL, " +
                    "`randomness` REAL NOT NULL, " +
                    "`recovery_per_second` REAL NOT NULL, " +
                    "`is_built_in` INTEGER NOT NULL)",
            )

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `aimlab_sensitivities` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL, " +
                    "`preset` TEXT NOT NULL, " +
                    "`camera_sensitivity` REAL NOT NULL, " +
                    "`ads_multiplier` REAL NOT NULL, " +
                    "`gyro_sensitivity` REAL NOT NULL, " +
                    "`gyro_ads_multiplier` REAL NOT NULL, " +
                    "`horizontal_scale` REAL NOT NULL, " +
                    "`vertical_scale` REAL NOT NULL, " +
                    "`deadzone_percent` INTEGER NOT NULL, " +
                    "`smoothing_percent` INTEGER NOT NULL, " +
                    "`response_exponent` REAL NOT NULL, " +
                    "`invert_x` INTEGER NOT NULL, " +
                    "`invert_y` INTEGER NOT NULL)",
            )

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `aimlab_layouts` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL, " +
                    "`created_at` INTEGER NOT NULL, " +
                    "`updated_at` INTEGER NOT NULL)",
            )

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `aimlab_controls` (" +
                    "`layout_id` INTEGER NOT NULL, " +
                    "`role` TEXT NOT NULL, " +
                    "`x_fraction` REAL NOT NULL, " +
                    "`y_fraction` REAL NOT NULL, " +
                    "`width_fraction` REAL NOT NULL, " +
                    "`height_fraction` REAL NOT NULL, " +
                    "`opacity_percent` INTEGER NOT NULL, " +
                    "`shape` TEXT NOT NULL, " +
                    "`enabled` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`layout_id`, `role`))",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_aimlab_controls_layout_id` " +
                    "ON `aimlab_controls` (`layout_id`)",
            )

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `aimlab_records` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`mode` TEXT NOT NULL, " +
                    "`difficulty` TEXT NOT NULL, " +
                    "`weapon_name` TEXT, " +
                    "`metric` TEXT NOT NULL, " +
                    "`value` REAL NOT NULL, " +
                    "`previous_value` REAL, " +
                    "`achieved_at` INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "`index_aimlab_records_mode_difficulty_weapon_name_metric` " +
                    "ON `aimlab_records` (`mode`, `difficulty`, `weapon_name`, `metric`)",
            )
        }
    }

    /**
     * Version 7 → 8: the scoring/rendering generation stamp for the first-person 3D rewrite.
     *
     * The 3D view measures aim in angles and hits by ray–sphere, so its scores are not comparable with
     * the flat 2D arena's (§6). Two additive columns record which engine wrote a row: `scoring_version`
     * on `aimlab_sessions` and on `aimlab_records`. Both are `NOT NULL DEFAULT 1` — every row that
     * already exists was written by the 2D engine, and 1 is `SessionSummary.SCORING_VERSION_2D`, so a
     * user's whole history back-fills to "legacy 2D" without a value being invented for it. New rows
     * write 2 (the 3D generation).
     *
     * The records table's unique key gains `scoring_version`, so a 3D record and a 2D record for the same
     * mode/difficulty/weapon/metric are separate rows that never overwrite each other and are never
     * ranked against one another. The old four-column unique index is dropped and recreated with the
     * fifth column; SQLite has no `ALTER INDEX`, so it is a drop-and-create, which is safe because an
     * index carries no data. Strictly additive to the tables themselves — no row is dropped or rewritten,
     * and there is no `fallbackToDestructiveMigration`, here as everywhere.
     */
    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `aimlab_sessions` ADD COLUMN `scoring_version` INTEGER NOT NULL DEFAULT 1",
            )
            db.execSQL(
                "ALTER TABLE `aimlab_records` ADD COLUMN `scoring_version` INTEGER NOT NULL DEFAULT 1",
            )
            // Re-key the records uniqueness to include the generation, so 2D and 3D records coexist.
            db.execSQL("DROP INDEX IF EXISTS `index_aimlab_records_mode_difficulty_weapon_name_metric`")
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "`index_aimlab_records_mode_difficulty_weapon_name_metric_scoring_version` " +
                    "ON `aimlab_records` (`mode`, `difficulty`, `weapon_name`, `metric`, `scoring_version`)",
            )
        }
    }

    /**
     * Version 8 → 9: per-orientation control placements for landscape support.
     *
     * One additive column, `orientation`, on `aimlab_controls`, `NOT NULL DEFAULT 'PORTRAIT'` — so every
     * control row that already exists becomes its layout's portrait set, which is exactly what it was
     * before landscape existed. No row is dropped or rewritten and no existing layout loses a control.
     *
     * The controls table's primary key was `(layout_id, role)`; it becomes `(layout_id, role,
     * orientation)` so one role can hold a portrait placement and a landscape placement in the same
     * layout. SQLite cannot alter a primary key in place, so the table is rebuilt: create the new table,
     * copy every existing row into it (stamped `PORTRAIT`), drop the old, rename. This is the standard
     * SQLite table-rebuild and it preserves every row — the `INSERT … SELECT` carries the data across
     * before the old table is dropped. The `CREATE TABLE`/index text matches what Room generates for the
     * updated `AimLabControlEntity`, verified against the exported `9.json`.
     */
    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `aimlab_controls_new` (" +
                    "`layout_id` INTEGER NOT NULL, " +
                    "`role` TEXT NOT NULL, " +
                    "`x_fraction` REAL NOT NULL, " +
                    "`y_fraction` REAL NOT NULL, " +
                    "`width_fraction` REAL NOT NULL, " +
                    "`height_fraction` REAL NOT NULL, " +
                    "`opacity_percent` INTEGER NOT NULL, " +
                    "`shape` TEXT NOT NULL, " +
                    "`enabled` INTEGER NOT NULL, " +
                    "`orientation` TEXT NOT NULL DEFAULT 'PORTRAIT', " +
                    "PRIMARY KEY(`layout_id`, `role`, `orientation`))",
            )
            db.execSQL(
                "INSERT INTO `aimlab_controls_new` (" +
                    "`layout_id`, `role`, `x_fraction`, `y_fraction`, `width_fraction`, " +
                    "`height_fraction`, `opacity_percent`, `shape`, `enabled`, `orientation`) " +
                    "SELECT `layout_id`, `role`, `x_fraction`, `y_fraction`, `width_fraction`, " +
                    "`height_fraction`, `opacity_percent`, `shape`, `enabled`, 'PORTRAIT' " +
                    "FROM `aimlab_controls`",
            )
            db.execSQL("DROP TABLE `aimlab_controls`")
            db.execSQL("ALTER TABLE `aimlab_controls_new` RENAME TO `aimlab_controls`")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_aimlab_controls_layout_id` " +
                    "ON `aimlab_controls` (`layout_id`)",
            )
        }
    }

    /**
     * Version 9 → 10: weapon fire modes.
     *
     * Two additive columns on `aimlab_weapons`: `fire_mode` (`NOT NULL DEFAULT 'AUTO'`) and `burst_count`
     * (`NOT NULL DEFAULT 3`). Every weapon that existed before fire modes fired automatically — which is
     * what the loop already did — so an old row reads back unchanged. Strictly additive; no row is dropped
     * or rewritten, no `fallbackToDestructiveMigration`.
     */
    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `aimlab_weapons` ADD COLUMN `fire_mode` TEXT NOT NULL DEFAULT 'AUTO'")
            db.execSQL("ALTER TABLE `aimlab_weapons` ADD COLUMN `burst_count` INTEGER NOT NULL DEFAULT 3")
        }
    }

    /**
     * Version 10 → 11: the 3.5 features' per-profile settings and session-summary columns.
     *
     * The one additive migration for the whole 3.5 run, and it is additive in the strictest sense: every
     * statement is an `ALTER TABLE … ADD COLUMN`, no table is rebuilt, no row is rewritten, no index
     * changes. Every new profile column defaults to the feature being off (or its parameter absent), so a
     * profile written by 3.4 reads back through the mappers exactly as it did — thermal auto-downshift,
     * the network check and "keep full performance" are all off, which is also a new profile's default.
     * The session columns are all nullable with no default: a session recorded before these features ran,
     * or one where the feature was off, has nothing to report and reads NULL, which the mappers turn back
     * into "not recorded" rather than a row of zeroes that would read as a real measurement.
     *
     * The column names and types match [GameProfileEntity] and [SessionEntity] exactly, so Room's own
     * schema validation against `11.json` passes without a rebuild.
     */
    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // --- game_profiles: thermal auto-downshift (§B) ---
            db.execSQL("ALTER TABLE `game_profiles` ADD COLUMN `thermal_downshift_enabled` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `game_profiles` ADD COLUMN `thermal_limit_deci` INTEGER")
            db.execSQL("ALTER TABLE `game_profiles` ADD COLUMN `thermal_status_floor` TEXT")
            db.execSQL("ALTER TABLE `game_profiles` ADD COLUMN `thermal_floor_rate` REAL")
            db.execSQL("ALTER TABLE `game_profiles` ADD COLUMN `thermal_hysteresis_deci` INTEGER")
            db.execSQL("ALTER TABLE `game_profiles` ADD COLUMN `thermal_sustain_hot_millis` INTEGER")
            db.execSQL("ALTER TABLE `game_profiles` ADD COLUMN `thermal_sustain_cool_millis` INTEGER")
            db.execSQL("ALTER TABLE `game_profiles` ADD COLUMN `thermal_min_interval_millis` INTEGER")
            // --- game_profiles: network check (§C) ---
            db.execSQL("ALTER TABLE `game_profiles` ADD COLUMN `network_check_enabled` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `game_profiles` ADD COLUMN `network_prelaunch_warn` INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE `game_profiles` ADD COLUMN `network_alerts_enabled` INTEGER NOT NULL DEFAULT 0")
            // --- game_profiles: full performance (§D) ---
            db.execSQL("ALTER TABLE `game_profiles` ADD COLUMN `full_performance_enabled` INTEGER NOT NULL DEFAULT 0")

            // --- sessions: 3.5 summary columns, all nullable ---
            db.execSQL("ALTER TABLE `sessions` ADD COLUMN `downshift_count` INTEGER")
            db.execSQL("ALTER TABLE `sessions` ADD COLUMN `lowest_rate_hz` REAL")
            // Just the transport for the network side: the session's latency average is already
            // `avg_latency` (v5) and its variability the `latency_*` columns, and this app stores no
            // packet-loss figure it cannot measure. A parallel avg/jitter/loss trio here would only
            // duplicate or invent those, so it is not added.
            db.execSQL("ALTER TABLE `sessions` ADD COLUMN `transport` TEXT")
            db.execSQL("ALTER TABLE `sessions` ADD COLUMN `full_performance_overridden` INTEGER")
            db.execSQL("ALTER TABLE `sessions` ADD COLUMN `system_reenabled_saver` INTEGER")
        }
    }

    /** Every migration, in order, for [androidx.room.RoomDatabase.Builder.addMigrations]. */
    val ALL: Array<Migration> = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
        MIGRATION_8_9,
        MIGRATION_9_10,
        MIGRATION_10_11,
    )
}
