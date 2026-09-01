package com.gamecore.di

import com.gamecore.core.system.CompositeMetricsReader
import com.gamecore.core.system.MetricsReader
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Binds the one reader the app is allowed to see.
 *
 * [CompositeMetricsReader] is the only [MetricsReader] in the graph. The standard and
 * elevated readers are reachable only through it — the elevated one is not even
 * `@Inject`-constructed, because a reader whose Shizuku dependency was never checked has
 * no business being obtainable — so a ViewModel asking for a [MetricsReader] gets
 * routing, fallback and the honest-absence behaviour for free and cannot opt out of them.
 *
 * The composite is also injectable as itself, for the settings layer that has to tell it
 * when the user switches elevated reads off and the Shizuku screen that invalidates the
 * route after a permission grant. Neither of those is part of reading a metric, so
 * neither belongs on the interface.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SystemModule {

    @Binds
    abstract fun metricsReader(impl: CompositeMetricsReader): MetricsReader
}
