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

    /** Every migration, in order, for [androidx.room.RoomDatabase.Builder.addMigrations]. */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
