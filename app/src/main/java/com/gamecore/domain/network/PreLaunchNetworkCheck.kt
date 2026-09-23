package com.gamecore.domain.network

import com.gamecore.core.common.valueOrNull
import com.gamecore.core.model.GameProfile
import com.gamecore.domain.monitoring.NetworkMonitor
import com.gamecore.domain.monitoring.PerformanceMonitor
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The pre-launch network check (spec §C4): the thin, time-boxed seam between the pure §C decision
 * ([preLaunchWarning]) and the two Android pieces it needs — the probe burst and the current connectivity.
 *
 * It owns no logic of its own beyond gating and unwrapping. The decision lives in [preLaunchWarning]; the
 * probe burst is [NetworkMonitor.measure], the same one the network screen and the session use, so a launch
 * does not open a second socket or contend with them (that class shares one in-flight burst); and whether
 * there is a connection to probe comes from the snapshot [PerformanceMonitor] already took, never a fresh
 * read (a second read would move the throughput counters the next sample is measured against).
 *
 * Called from the `play()` path of the three launch surfaces (Home, Games, the profile editor). It returns
 * a warning only when the running profile opted in *and* a real measured burst rates the connection poor;
 * in every other case — feature off, no connection, measurement switched off, the burst timing out against
 * [PROBE_BUDGET_MILLIS], or the measurement failing — it returns null and the caller launches without a
 * word. Like every [PreLaunchResult], it never blocks a launch: the caller decides what to do with a
 * warning, and "Launch anyway" is always one of the choices.
 */
@Singleton
class PreLaunchNetworkCheck @Inject constructor(
    private val network: NetworkMonitor,
    private val monitor: PerformanceMonitor,
) {

    /**
     * Measures the connection for [profile] and returns a warning if it rates poor, or null to proceed.
     *
     * The gate is both flags: the check has to be on and the pre-launch warning specifically enabled — a
     * profile that checks the network only for its in-session alert or its pill field is not made to wait at
     * launch. Time-boxed so a slow or unreachable host cannot hold the game hostage: past the budget the
     * burst is abandoned and the launch proceeds, which is [PreLaunchResult.TIMED_OUT]'s "launching anyway".
     */
    suspend fun evaluate(profile: GameProfile): PreLaunchWarning? {
        if (!profile.networkCheckEnabled || !profile.networkPreLaunchWarn) return null
        val connected = monitor.snapshots.value?.network?.isConnected == true
        val observed = withTimeoutOrNull(PROBE_BUDGET_MILLIS) { network.measure(connected = connected) }
        return preLaunchWarning(stability = observed?.valueOrNull?.stability, connected = connected)
    }

    private companion object {
        /**
         * How long a launch waits for the burst before giving up and launching anyway.
         *
         * A burst is five handshakes about 200 ms apart, so a healthy one is well under a second; this is the
         * ceiling for one that is dragging on a slow or unreachable host. Long enough not to abandon a real
         * measurement, short enough that tapping Play never feels like it hung.
         */
        const val PROBE_BUDGET_MILLIS = 2_500L
    }
}
