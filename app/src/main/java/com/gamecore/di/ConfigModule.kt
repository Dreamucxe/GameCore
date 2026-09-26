package com.gamecore.di

import android.content.Context
import android.os.Process
import com.gamecore.core.config.ConfigWorkspace
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Wires the config editor's one non-injectable collaborator, [ConfigWorkspace].
 *
 * The controller, the repository and the shell all reach Hilt through their own `@Inject` constructors
 * or existing bindings; the workspace is the exception because it is built from two runtime facts rather
 * than other objects. Both are read here, once, from the application context:
 *
 * - **Where GameCore's own files live.** `getExternalFilesDir(null)` is the local end of every privileged
 *   copy — the same `/storage/emulated/<user>/Android/data/com.gamecore/files` tree the shell path builder
 *   names for GameCore's package — so the workspace and the shell cannot drift onto different directories.
 *   It is nullable when external storage is unmounted; internal `filesDir` is the honest fallback so DI
 *   never fails, even though the cross-sandbox copy has nothing to talk to until storage returns.
 * - **Which user GameCore runs as.** `Process.myUid() / PER_USER_RANGE` is the emulated-storage user id —
 *   the GameCore side of a copy — recovered the same way the platform assigns it.
 */
@Module
@InstallIn(SingletonComponent::class)
object ConfigModule {

    @Provides
    @Singleton
    fun configWorkspace(@ApplicationContext context: Context): ConfigWorkspace =
        ConfigWorkspace(
            filesRoot = context.getExternalFilesDir(null) ?: context.filesDir,
            ownUserId = Process.myUid() / PER_USER_RANGE,
        )

    /** Android assigns each user a block of this many uids; `uid / this` recovers the user id. */
    private const val PER_USER_RANGE = 100_000
}
