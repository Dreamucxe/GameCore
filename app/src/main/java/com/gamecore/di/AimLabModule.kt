package com.gamecore.di

import com.gamecore.aimlab.AimLabRepository
import com.gamecore.data.database.AimLabLayoutDao
import com.gamecore.data.database.AimLabSensitivityDao
import com.gamecore.data.database.AimLabSessionDao
import com.gamecore.data.database.AimLabWeaponDao
import com.gamecore.data.database.GameCoreDatabase
import com.gamecore.data.repository.AimLabRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * DI for the Aim Lab data layer: the four DAOs, and the binding of the repository interface to its impl.
 *
 * DAO providers follow `DatabaseModule` exactly — one unscoped `@Provides` per DAO returning the database
 * accessor. The interface→impl binding is a separate abstract `@Binds` module because a `@Binds` method
 * must be abstract, and mixing it with the `@Provides` object functions in one module is not allowed.
 * Both install into [SingletonComponent]; the impl is `@Singleton`, so the whole app shares one repository
 * and one set of Room-backed flows.
 */
@Module
@InstallIn(SingletonComponent::class)
object AimLabDaoModule {

    @Provides
    fun aimLabSessionDao(database: GameCoreDatabase): AimLabSessionDao = database.aimLabSessions()

    @Provides
    fun aimLabWeaponDao(database: GameCoreDatabase): AimLabWeaponDao = database.aimLabWeapons()

    @Provides
    fun aimLabSensitivityDao(database: GameCoreDatabase): AimLabSensitivityDao =
        database.aimLabSensitivities()

    @Provides
    fun aimLabLayoutDao(database: GameCoreDatabase): AimLabLayoutDao = database.aimLabLayouts()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AimLabModule {

    @Binds
    abstract fun bindAimLabRepository(impl: AimLabRepositoryImpl): AimLabRepository
}
