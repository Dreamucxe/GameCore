package com.gamecore.di

import com.gamecore.data.preferences.SharedPreferencesPendingOverrideStore
import com.gamecore.domain.display.DisplayOverrideConfirmer
import com.gamecore.domain.display.DisplayOverrideNotices
import com.gamecore.domain.display.PendingOverridePersistence
import com.gamecore.ui.display.AndroidDisplayOverrideConfirmer
import com.gamecore.ui.display.DisplayOverrideNotifications
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Binds the dead-man's switch's two interfaces to the surfaces this app has.
 *
 * The switch's *decisions* live in the domain package and are pure — that is what lets them be tested
 * on a machine with no display — so the domain owns the interfaces and this module points them at the
 * platform. Two consequences worth naming:
 *
 *  - Nothing in `domain.display` imports android. The controller can write an override, arm the row
 *    and ask for a confirmation without knowing whether the answer arrives as an Activity, a
 *    notification, or neither.
 *  - There is exactly one implementation of each. A second confirmer would be a second way to show
 *    the user a question they have already answered, which is the failure this feature is built to
 *    prevent.
 *
 * [SharedPreferencesPendingOverrideStore] is the storage half and stays injectable as itself, for the
 * same reason every other store in this app is.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DisplayOverrideModule {

    @Binds
    abstract fun pendingOverridePersistence(
        impl: SharedPreferencesPendingOverrideStore,
    ): PendingOverridePersistence

    @Binds
    abstract fun displayOverrideConfirmer(
        impl: AndroidDisplayOverrideConfirmer,
    ): DisplayOverrideConfirmer

    @Binds
    abstract fun displayOverrideNotices(
        impl: DisplayOverrideNotifications,
    ): DisplayOverrideNotices
}
