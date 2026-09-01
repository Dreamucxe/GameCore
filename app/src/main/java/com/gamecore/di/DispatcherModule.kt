package com.gamecore.di

import com.gamecore.core.common.ApplicationScope
import com.gamecore.core.common.DefaultDispatcher
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.common.MainDispatcher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * Dispatchers, injected rather than referenced statically.
 *
 * `Dispatchers.IO` written inline in a sampler is untestable: a unit test cannot
 * substitute a deterministic scheduler for it, and the sampling logic — which is
 * where the arithmetic that must not be wrong lives — would then only be exercisable
 * on a device. Injecting them means every sampler, repository and controller in this
 * app can be driven by `StandardTestDispatcher` in a JVM test and still run on the
 * right pool in production.
 */
@Module
@InstallIn(SingletonComponent::class)
object DispatcherModule {

    /** Blocking file and binder reads: `/proc`, `/sys`, `dumpsys`, PackageManager, Room. */
    @Provides
    @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

    /**
     * Parsing, aggregation and graph downsampling. Distinct from IO because these are
     * CPU-bound: on the unbounded IO pool a burst of them would spawn threads on a
     * device that is currently trying to render a game, which is the one thing this
     * app must not cost the user.
     */
    @Provides
    @DefaultDispatcher
    fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    /**
     * `Dispatchers.Main.immediate` rather than `Main`. The overlay windows are added
     * and moved on the main thread, and `immediate` skips a post when the caller is
     * already there — one frame of latency that would otherwise show as the floating
     * button lagging behind the finger dragging it.
     */
    @Provides
    @MainDispatcher
    fun mainDispatcher(): CoroutineDispatcher = Dispatchers.Main.immediate

    /**
     * Application-lifetime scope, for work that must outlive whatever started it.
     *
     * Two cases justify it, and both are correctness rather than convenience: writing
     * the final rows of a session after the tracking service has been told to stop,
     * and restoring the device settings a profile changed after the game that
     * triggered it has exited. Tying either to a screen's scope would leave a user's
     * refresh rate pinned because they happened to swipe GameCore away.
     *
     * `SupervisorJob` so one failure does not cancel the rest: a settings write that
     * throws on an unusual vendor build must not take down the session writer with it.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(@IoDispatcher io: CoroutineDispatcher): CoroutineScope =
        CoroutineScope(SupervisorJob() + io)
}
