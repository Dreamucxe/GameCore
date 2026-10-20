package com.gamecore.di

import com.gamecore.themeengine.AuroraThemeRepository
import com.gamecore.themeengine.SharedPreferencesAuroraRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * DI for the Theme Engine's preference layer: the repository interface to its preference-backed
 * implementation, mirroring how every other port in this app is bound.
 *
 * The implementation is `@Singleton` because the engine's save points have to be the same list from
 * every screen that asks, and because the plugin that watches it is a singleton too. The preference
 * file itself is a few hundred bytes; there is nothing here worth a smaller scope.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ThemeEngineModule {

    @Binds
    abstract fun auroraThemeRepository(impl: SharedPreferencesAuroraRepository): AuroraThemeRepository
}
