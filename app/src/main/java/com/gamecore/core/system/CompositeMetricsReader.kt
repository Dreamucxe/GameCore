package com.gamecore.core.system

import com.gamecore.core.common.AccessLevel
import com.gamecore.core.model.BatteryReading
import com.gamecore.core.model.CpuReading
import com.gamecore.core.model.MemoryReading
import com.gamecore.core.model.StorageReading
import com.gamecore.core.model.ThermalReading
import com.gamecore.core.shizuku.ShizukuShell
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one [MetricsReader] the rest of GameCore is given.
 *
 * Everything above `core.system` — the monitoring pipeline, the overlay services, every
 * ViewModel — is written against [MetricsReader] and never learns which reader answered.
 * That is what makes "the UI never executes shell commands" a property of the dependency
 * graph rather than a convention: there is no shell reachable from above this class.
 *
 * Routing is resolved lazily and re-resolved on demand, because access genuinely changes
 * while the app runs. A user grants Shizuku permission mid-session, or starts the service
 * after a reboot, or revokes it. A route fixed at construction would be wrong within
 * minutes on a normal device.
 *
 * The elevated reader is used only where it adds something. It delegates battery and
 * storage straight back to the standard reader, so switching route does not change how
 * those are obtained and cannot change what they say.
 */
@Singleton
class CompositeMetricsReader @Inject constructor(
    private val standard: StandardMetricsReader,
    private val shizuku: ShizukuShell,
    private val procFs: ProcFsReader,
    private val samplerFactory: CpuSamplerFactory,
) : MetricsReader {

    private val routeLock = Mutex()

    /** Null means "not yet resolved", not "standard". */
    private var active: MetricsReader? = null

    /** Whether the user permits elevated reads at all. Their setting, not a capability. */
    @Volatile
    private var allowElevated: Boolean = true

    /**
     * The elevated reader keeps its own baselines, because its jiffy counts arrive by a
     * different mechanism than `/proc` and a delta across the two would divide the gap
     * between two mechanisms by the sampling interval.
     */
    private val elevatedSampler by lazy { samplerFactory.create() }

    override val accessLevel: AccessLevel
        get() = active?.accessLevel ?: AccessLevel.NORMAL

    /** Always. The standard reader has no dependency that can be absent. */
    override suspend fun isAvailable(): Boolean = true

    /**
     * Applies the user's preference.
     *
     * Switching elevated reads off is not the same as Shizuku being unavailable, and the
     * two are not conflated anywhere: this is the user's choice and it is reversible in
     * one tap, whereas an absent Shizuku is a fact about the device. A change discards
     * the resolved route rather than leaving a reader in place that the setting forbids.
     */
    suspend fun setElevatedAllowed(allowed: Boolean) = routeLock.withLock {
        if (allowElevated != allowed) {
            allowElevated = allowed
            active = null
        }
    }

    /** Forces re-resolution: after a permission grant, or a manual refresh. */
    suspend fun invalidate() = routeLock.withLock {
        active = null
    }

    /**
     * Resolves the route, preferring elevated when it actually works.
     *
     * "Actually works" means [ShizukuShell.isAvailable] answered yes — which checks the
     * binder and the permission, not whether the Shizuku app is installed. A granted
     * permission that survived a reboot without the service is the common trap, and
     * routing every read through a shell that always fails would be worse than not
     * routing through one at all.
     *
     * Both readers get a fresh sampler on a route change, because the baseline held by
     * whichever one was previously active describes a different mechanism.
     */
    private suspend fun reader(): MetricsReader {
        active?.let { return it }
        return routeLock.withLock {
            active?.let { return@withLock it }
            val resolved = resolve()
            active = resolved
            resolved
        }
    }

    private suspend fun resolve(): MetricsReader {
        if (allowElevated && shizuku.isAvailable()) {
            elevatedSampler.reset()
            standard.resetSampling()
            return ElevatedMetricsReader(
                shell = shizuku,
                delegate = standard,
                procFs = procFs,
                cpuSampler = elevatedSampler,
            )
        }
        return standard
    }

    override fun resetSampling() {
        // Both, unconditionally: a reset arrives when the interval changed or sampling
        // was paused, and the route may change before the next tick.
        standard.resetSampling()
        elevatedSampler.reset()
    }

    // ------------------------------------------------------------------ delegation

    override suspend fun readCpu(): CpuReading = reader().readCpu()

    override suspend fun readMemory(): MemoryReading = reader().readMemory()

    override suspend fun readBattery(): BatteryReading = reader().readBattery()

    override suspend fun readThermal(): ThermalReading = reader().readThermal()

    override suspend fun readStorage(): StorageReading = reader().readStorage()
}
