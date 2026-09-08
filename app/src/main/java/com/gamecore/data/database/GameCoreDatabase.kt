package com.gamecore.data.database

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The one database.
 *
 * Eight tables, one file, one passphrase. The file itself is encrypted by SQLCipher rather than
 * relying on the app sandbox alone: §24A.5 asks for session data and profiles to be stored
 * encrypted, and on a device where the sandbox has been opened up — the rooted case §24A.13 tells
 * this app to expect and degrade for — an unencrypted Room file is a plain SQLite file readable by
 * anything with a shell. The passphrase lives in [com.gamecore.data.preferences.DatabaseKeyStore],
 * which is Keystore-backed, so the key is not in the encrypted file's own directory.
 *
 * `exportSchema = true` writes the schema JSON into `app/schemas` at build time. It costs nothing
 * and it is what made version 2's migration writable rather than guessable, which matters for a
 * database holding history a user cannot regenerate.
 *
 * **Version 2** adds the `color_presets` table, `game_profiles.color_preset_id`, and the two
 * colour columns on `sessions`. [GameCoreMigrations.MIGRATION_1_2] is registered in
 * [com.gamecore.di.DatabaseModule] and is a real migration — there is no
 * `fallbackToDestructiveMigration` anywhere in this app, because a user who installs an update
 * should not lose six months of sessions to a schema change.
 *
 * **Version 3** adds `game_profiles.display_size`, one nullable TEXT column. No new table and no
 * session columns: a display size is a request a profile carries, and the reading worth keeping
 * about one is whether it was honoured, which the restore ledger already records.
 * [GameCoreMigrations.MIGRATION_2_3].
 *
 * **Version 4** adds `game_profiles.free_ram_on_launch`, one `NOT NULL DEFAULT 0` integer, for the
 * per-game switch that closes background apps when a game starts. No new table: what one pass did is
 * reported to the user while the game is running and is not history worth keeping — a count of apps
 * closed a fortnight ago says nothing about the device today, and storing which of the user's apps
 * were closed and when would be a log of their app usage that this app has no reason to hold.
 * [GameCoreMigrations.MIGRATION_3_4].
 *
 * **Version 5** adds six nullable integer columns on `sessions` for the latency probe log — how many
 * probes went out, how many did not complete, how many came back far above the session's own average,
 * the worst reading, the jitter and the longest unbroken run of failures. Columns rather than a table
 * because they are a summary by the time they arrive, and nullable because absence is the truth about
 * every session recorded before the log existed. [GameCoreMigrations.MIGRATION_4_5].
 *
 * **Version 6** adds `game_profiles.cpu_affinity`, one nullable TEXT column holding a
 * `CpuAffinityPreset` name, for the per-game core preset. Nullable and with no default because the
 * feature's own "leave it to Android" state is the absence of a value rather than a member of the enum.
 * No session columns: which cores a game was allowed on is not a measurement, and the one thing worth
 * remembering about it — that GameCore still owes a process its previous mask — is a restore-ledger row
 * that clears itself. [GameCoreMigrations.MIGRATION_5_6].
 *
 * There are no `@TypeConverter`s registered anywhere in this class, deliberately. Enums are stored
 * as their names and parsed back defensively in [Mappers]; a converter would move that parsing
 * into generated code where the fallback for an unrecognised name is a thrown exception rather
 * than a sane default.
 */
@Database(
    entities = [
        GameProfileEntity::class,
        HudLayoutEntity::class,
        HudWidgetEntity::class,
        CrosshairPresetEntity::class,
        ColorPresetEntity::class,
        SessionEntity::class,
        SessionSampleEntity::class,
        RestorePointEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class GameCoreDatabase : RoomDatabase() {

    abstract fun gameProfiles(): GameProfileDao

    abstract fun hudLayouts(): HudLayoutDao

    abstract fun crosshairPresets(): CrosshairPresetDao

    abstract fun colorPresets(): ColorPresetDao

    abstract fun sessions(): SessionDao

    abstract fun restorePoints(): RestorePointDao

    companion object {
        /**
         * The database file name.
         *
         * No `.db` suffix on purpose: several backup and file-manager heuristics treat `*.db` as
         * a database worth special handling, and this file is not readable without the key
         * anyway. `android:allowBackup="false"` (§24A.11) already keeps it out of cloud backups;
         * this just avoids advertising it.
         */
        const val FILE_NAME = "gamecore-store"
    }
}
