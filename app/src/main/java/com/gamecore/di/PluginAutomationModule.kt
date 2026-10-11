package com.gamecore.di

import com.gamecore.core.plugin.PluginAuditLog
import com.gamecore.core.plugin.PluginAutomationHistoryStore
import com.gamecore.core.plugin.PluginPermissionGate
import com.gamecore.core.plugin.PluginRateLimiter
import com.gamecore.core.plugin.PluginRegistry
import com.gamecore.core.plugin.ShizukuActionBroker
import com.gamecore.core.plugin.content.AutomationEngine
import com.gamecore.core.shizuku.ElevatedShell
import com.gamecore.data.plugin.RoomPluginAutomationHistoryStore
import com.gamecore.domain.gaming.GamingCoordinator
import com.gamecore.domain.monitoring.PerformanceMonitor
import com.gamecore.domain.plugin.PluginAutomationRuntime
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Assembles the plugin *automation* runtime stack — the host-side machinery that watches for the
 * triggers a rule fires on, decides which actions a rule wants, and drives those actions through
 * the one gated path to the device.
 *
 * This is kept apart from [PluginModule] and [PluginRuntimeModule] on purpose, and the separation is
 * the same design the other two modules state in their own notes. [PluginModule] binds the two
 * persistence ports and is deliberately spare; [PluginRuntimeModule] assembles the API *funnel* a
 * plugin call passes through and hands out exactly one object ([com.gamecore.core.plugin.PluginApiFacade])
 * for that purpose. Neither binds a broker, because — as [PluginModule]'s note says — the object that
 * answers a plugin's device-affecting calls is "constructed by the host that owns the data it would
 * expose, with the permission gate between them, rather than being available to anything that can name
 * a type." This module is that host. It is the one place in the graph that holds the
 * [ShizukuActionBroker], and the broker is the one object that holds an [ElevatedShell].
 *
 * Providing the broker here does not loosen that isolation. Plugins are data, not code: nothing a
 * plugin ships is ever injected, so putting the broker in the singleton graph exposes it only to the
 * host collaborators that name it — [PluginAutomationRuntime] and nothing else. The [ElevatedShell] is
 * not bound here either; it is consumed from the binding [ShizukuModule] already owns and reaches no
 * further than the broker's own constructor. A ViewModel that wanted the shell, or the broker, would
 * still have to add a constructor parameter for a type it has no legitimate reason to name, which is
 * exactly the kind of change a diff makes obvious — the architecture's rule checked by the graph's
 * shape rather than by review.
 *
 * The [PluginPermissionGate] is constructed inline rather than bound, matching how
 * [PluginRuntimeModule] builds the facade: it is pure policy with no dependencies and nothing else in
 * the graph needs to name it, so there is no interface to expose. The rate limiter and audit log are
 * injected, not rebuilt, so the broker shares the one process-wide set of rate-limit windows and the
 * one audit trail every other plugin path already writes to.
 */
@Module
@InstallIn(SingletonComponent::class)
object PluginAutomationModule {

    /**
     * The *decision* half of automation. Stateless given its two bounds, so a single instance serves
     * the whole process; it only ever validates a rule and reports which action ids a rule wants to
     * run, never runs anything. The defaults ([AutomationEngine]'s own `maxActionsPerRule` /
     * `maxConditions`) are the bounds that keep a rule from becoming a general program, so they are
     * left as declared rather than overridden here.
     */
    @Provides
    @Singleton
    fun automationEngine(): AutomationEngine = AutomationEngine()

    /**
     * The only path from a plugin to a device-affecting change. Assembled exactly the way
     * [PluginRuntimeModule] assembles the facade: the [PluginPermissionGate] is built inline so it
     * stays out of the graph, while the shared [PluginRateLimiter] and [PluginAuditLog] singletons are
     * injected so the broker rate-limits and audits against the same windows and trail as every other
     * plugin path. The [ElevatedShell] is the broker's — and only the broker's — because the broker is
     * the gate that stands in front of it. The timing and window defaults on the constructor are left
     * as declared; they are policy that belongs with the broker, not with its wiring.
     */
    @Provides
    @Singleton
    fun shizukuActionBroker(
        shell: ElevatedShell,
        rateLimiter: PluginRateLimiter,
        auditLog: PluginAuditLog,
    ): ShizukuActionBroker = ShizukuActionBroker(
        shell = shell,
        gate = PluginPermissionGate(),
        rateLimiter = rateLimiter,
        auditLog = auditLog,
    )

    /**
     * The long-lived driver that ties the stack together: it merges the [GamingCoordinator]'s
     * lifecycle feed and the [PerformanceMonitor]'s device-sample feed, derives a trigger on each edge,
     * asks the [AutomationEngine] which actions the active plugins' rules want for that trigger and the
     * current variables, dispatches each through the gated [ShizukuActionBroker], resolves the live
     * plugins and their runtime state through the [PluginRegistry], and records every outcome in the
     * durable [PluginAutomationHistoryStore]. One instance per process — the host starts it once and it
     * runs for as long as the service that owns it.
     *
     * The constructor's `now` clock is left to its default rather than injected: the stack never binds
     * a `() -> Long`, and the default (`System::currentTimeMillis`, as on the broker and the audit log)
     * is the real wall clock a running service wants. A test that needs a fake clock constructs the
     * runtime directly rather than going through Hilt.
     */
    @Provides
    @Singleton
    fun pluginAutomationRuntime(
        engine: AutomationEngine,
        broker: ShizukuActionBroker,
        registry: PluginRegistry,
        monitor: PerformanceMonitor,
        coordinator: GamingCoordinator,
        history: PluginAutomationHistoryStore,
    ): PluginAutomationRuntime = PluginAutomationRuntime(
        engine = engine,
        broker = broker,
        registry = registry,
        monitor = monitor,
        coordinator = coordinator,
        history = history,
    )
}

/**
 * The port binding for the automation stack, split into its own abstract module the way this app
 * keeps every `@Binds` apart from its `@Provides` (see [PluginModule] versus [PluginRuntimeModule]).
 *
 * [RoomPluginAutomationHistoryStore] is the production, database-backed half of the
 * [PluginAutomationHistoryStore] port; a test binds an in-memory fake instead. Keeping the port an
 * interface, as [PluginAutomationHistoryStore] documents, is what lets the policy and review logic
 * above it stay a plain JVM unit test while the storage engine is confined to the one class a device
 * can exercise.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PluginAutomationBindsModule {

    @Binds
    abstract fun pluginAutomationHistoryStore(
        impl: RoomPluginAutomationHistoryStore,
    ): PluginAutomationHistoryStore
}
