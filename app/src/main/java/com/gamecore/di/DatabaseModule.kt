package com.gamecore.di

import android.content.Context
import androidx.room.Room
import com.gamecore.data.database.ColorPresetDao
import com.gamecore.data.database.CrosshairPresetDao
import com.gamecore.data.database.GameCoreDatabase
import com.gamecore.data.database.GameCoreMigrations
import com.gamecore.data.database.GameProfileDao
import com.gamecore.data.database.HudLayoutDao
import com.gamecore.data.database.RestorePointDao
import com.gamecore.data.database.SessionDao
import com.gamecore.data.preferences.DatabaseKeyStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import javax.inject.Singleton

/**
 * Builds the encrypted database.
 *
 * Three things happen here that are not Room boilerplate, and each of them is a failure mode this
 * app would otherwise hit on a real device.
 *
 * **The native library is loaded explicitly.** SQLCipher 4.x does not call `System.loadLibrary` in
 * a static initialiser — verified against the artifact: no class in `net.zetetic.database.sqlcipher`
 * references `loadLibrary`, and `SQLiteConnection`'s native methods are bound by `JNI_OnLoad` in
 * `libsqlcipher.so`. Without an explicit load the first query throws `UnsatisfiedLinkError` from a
 * background thread, which surfaces as an unexplained crash rather than as a database error.
 *
 * **A lost Keystore key is recovered from rather than crashed on.** If the passphrase file cannot be
 * opened, the existing database file is undecryptable — not corrupt, just permanently unreadable.
 * Deleting it and starting again is the only outcome that leaves a working app, so that is what
 * happens, once, before the builder runs.
 *
 * **The passphrase array is not reused.** [DatabaseKeyStore.passphrase] returns a fresh copy per
 * call because SQLCipher zeroes the array after keying the connection.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun database(
        @ApplicationContext context: Context,
        keyStore: DatabaseKeyStore,
    ): GameCoreDatabase {
        loadNativeLibrary()

        val passphrase = keyStore.passphrase()

        // Checked after `passphrase()`, which is what sets the flag. A key that is gone means the
        // file on disk can never be opened again; keeping it would fail every launch identically.
        if (keyStore.wasKeyLost) {
            deleteDatabaseFiles(context)
        }

        return Room.databaseBuilder(
            context,
            GameCoreDatabase::class.java,
            GameCoreDatabase.FILE_NAME,
        )
            .openHelperFactory(SupportOpenHelperFactory(passphrase))
            // Real migrations, no destructive fallback. `GameCoreMigrations.ALL` is additive
            // statement by statement, so a user updating from the version that had no colour
            // feature keeps every session and profile they had.
            .addMigrations(*GameCoreMigrations.ALL)
            .build()
    }

    @Provides
    fun gameProfileDao(database: GameCoreDatabase): GameProfileDao = database.gameProfiles()

    @Provides
    fun hudLayoutDao(database: GameCoreDatabase): HudLayoutDao = database.hudLayouts()

    @Provides
    fun crosshairPresetDao(database: GameCoreDatabase): CrosshairPresetDao =
        database.crosshairPresets()

    @Provides
    fun colorPresetDao(database: GameCoreDatabase): ColorPresetDao = database.colorPresets()

    @Provides
    fun sessionDao(database: GameCoreDatabase): SessionDao = database.sessions()

    @Provides
    fun restorePointDao(database: GameCoreDatabase): RestorePointDao = database.restorePoints()

    /**
     * Loads `libsqlcipher.so`.
     *
     * Not wrapped in `runCatching`: if the native library is missing the app cannot store anything,
     * and swallowing the error here would move the crash to the first query — a stack trace pointing
     * at a DAO rather than at the real cause. The AAR ships `arm64-v8a` and `armeabi-v7a`, which are
     * the two ABIs this build filters to, so a failure means a broken install rather than an
     * unsupported device.
     */
    private fun loadNativeLibrary() {
        System.loadLibrary("sqlcipher")
    }

    /**
     * Removes the database and its sidecar files.
     *
     * The `-wal` and `-shm` files matter: leaving a write-ahead log next to a freshly created
     * database means SQLite tries to replay pages encrypted with a key that no longer exists.
     */
    private fun deleteDatabaseFiles(context: Context) {
        val base = context.getDatabasePath(GameCoreDatabase.FILE_NAME)
        listOf(base, File("${base.path}-wal"), File("${base.path}-shm"), File("${base.path}-journal"))
            .forEach { file -> runCatching { if (file.exists()) file.delete() } }
    }
}
