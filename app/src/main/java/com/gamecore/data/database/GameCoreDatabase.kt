package com.gamecore.data.database

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The one database.
 *
 * Seven tables, one file, one passphrase. The file itself is encrypted by SQLCipher rather than
 * relying on the app sandbox alone: §24A.5 asks for session data and profiles to be stored
 * encrypted, and on a device where the sandbox has been opened up — the rooted case §24A.13 tells
 * this app to expect and degrade for — an unencrypted Room file is a plain SQLite file readable by
 * anything with a shell. The passphrase lives in [com.gamecore.data.preferences.DatabaseKeyStore],
 * which is Keystore-backed, so the key is not in the encrypted file's own directory.
 *
 * `exportSchema = true` writes the schema JSON into `app/schemas` at build time. It costs nothing
 * and it is the only way a migration for version 2 can be written and tested rather than guessed
 * at, which matters for a database holding history a user cannot regenerate.
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
        SessionEntity::class,
        SessionSampleEntity::class,
        RestorePointEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class GameCoreDatabase : RoomDatabase() {

    abstract fun gameProfiles(): GameProfileDao

    abstract fun hudLayouts(): HudLayoutDao

    abstract fun crosshairPresets(): CrosshairPresetDao

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
