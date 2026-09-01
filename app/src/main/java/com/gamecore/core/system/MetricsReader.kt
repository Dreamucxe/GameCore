package com.gamecore.core.system

import com.gamecore.core.common.AccessLevel
import com.gamecore.core.model.BatteryReading
import com.gamecore.core.model.CpuReading
import com.gamecore.core.model.MemoryReading
import com.gamecore.core.model.StorageReading
import com.gamecore.core.model.ThermalReading

/**
 * The system observation abstraction.
 *
 * Ported from ProcessLens, where the same three-tier shape was applied to process
 * inspection. Two implementations exist here — [StandardMetricsReader], which uses
 * only APIs available to an ordinary app, and [ElevatedMetricsReader], which reads the
 * same facts through Shizuku on devices where SELinux denies the direct read — and
 * [CompositeMetricsReader] composes them: it asks the elevated reader only for the
 * fields the standard one could not obtain, and only when Shizuku is actually running.
 *
 * Two rules make this interface worth having rather than being indirection:
 *
 *  * **Everything above `core.system` sees only this type.** No ViewModel, screen or
 *    service holds a reader implementation, a `ShellCommand` or an [ElevatedShell], so
 *    the architecture's rule that the UI never executes shell commands is a property of
 *    what the graph will hand out rather than a convention.
 *  * **Every method is `suspend` and every implementation confines itself to an IO
 *    dispatcher.** A `/proc` read or a binder call on the main thread is a dropped
 *    frame, and a frame dropped by GameCore is a frame dropped in the game underneath
 *    its overlay.
 *
 * Note what is absent: there is no `freeMemory()`, no `killBackgroundApps()`, and no
 * `setThermalStatus()`. The interface cannot express them, so no future caller can
 * reach for one.
 */
interface MetricsReader {

    /** Which privilege tier this reader speaks for. */
    val accessLevel: AccessLevel

    /**
     * Whether this reader can currently do anything at all. The elevated one returns
     * false when Shizuku is absent or its permission has not been granted, and the
     * composite skips it without paying for a binder round trip.
     */
    suspend fun isAvailable(): Boolean

    /**
     * A CPU reading. Utilisation needs two samples, so the first call after a reset
     * returns `Observed.awaitingSample()` for the percentage fields and real values for
     * everything else.
     */
    suspend fun readCpu(): CpuReading

    suspend fun readMemory(): MemoryReading

    suspend fun readBattery(): BatteryReading

    suspend fun readThermal(): ThermalReading

    suspend fun readStorage(): StorageReading

    /**
     * Discards sampling baselines. Called when the interval changes or sampling is
     * paused: the next delta would otherwise span an interval it is not divided by.
     */
    fun resetSampling()
}
