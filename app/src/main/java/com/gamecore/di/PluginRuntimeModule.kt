package com.gamecore.di

import com.gamecore.core.plugin.PluginApiFacade
import com.gamecore.core.plugin.PluginAuditLog
import com.gamecore.core.plugin.PluginGrantStore
import com.gamecore.core.plugin.PluginPermissionGate
import com.gamecore.core.plugin.PluginRateLimiter
import com.gamecore.core.plugin.settings.PluginSettings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the plugin *runtime* collaborators — the funnel every plugin API call passes through, and
 * the first host-side consumer of it (the typed settings surface).
 *
 * These are kept out of [PluginModule] on purpose. That module binds the two persistence ports and is
 * deliberately spare; this one assembles the enforcement pipeline — gate, rate limiter, audit log,
 * facade — as application singletons so there is one audit trail and one set of rate-limit windows for
 * the whole process, not one per injection site.
 *
 * The [PluginPermissionGate] is constructed inline rather than bound: it is pure policy with no
 * dependencies and nothing else in the graph needs to name it, so there is no interface to expose. Every
 * guarantee a plugin call rests on is reachable only by going through [PluginApiFacade], which is the
 * single object this module hands out for that purpose.
 */
@Module
@InstallIn(SingletonComponent::class)
object PluginRuntimeModule {

    @Provides
    @Singleton
    fun pluginRateLimiter(): PluginRateLimiter = PluginRateLimiter()

    @Provides
    @Singleton
    fun pluginAuditLog(): PluginAuditLog = PluginAuditLog()

    @Provides
    @Singleton
    fun pluginApiFacade(
        rateLimiter: PluginRateLimiter,
        auditLog: PluginAuditLog,
    ): PluginApiFacade = PluginApiFacade(
        gate = PluginPermissionGate(),
        rateLimiter = rateLimiter,
        auditLog = auditLog,
    )

    @Provides
    @Singleton
    fun pluginSettings(
        facade: PluginApiFacade,
        store: PluginGrantStore,
    ): PluginSettings = PluginSettings(facade = facade, store = store)
}
