package com.gamecore.ui.network

import com.gamecore.ui.components.Readout

/**
 * The network-stability screen's settled state: what the connection is doing now, what the last
 * stability window found, and what this device will and will not let GameCore read.
 *
 * Presentation only, on purpose. §24A.2 asks that what reaches a composable contain nothing but the
 * fields it draws, so everything here is already a [Readout] string or a plain flag — the raw
 * `NetworkReading`, the `Observed<StabilityReport>` and the honest capability list are consumed on the
 * ViewModel's side of the line and never handed to the screen. That is also what keeps the honesty rules
 * testable: the conversion from a reading that could not be taken to "—" with a reason lives in one place
 * and is a pure function of the snapshot.
 *
 * The absent case survives the conversion the same way it does everywhere else in the app: a figure that
 * is not a real reading arrives as a [Readout] whose value is the absent sentinel and whose detail is the
 * reason, never a zero. A capability the device withholds arrives as [NetworkCapabilityView] with
 * [NetworkCapabilityView.isAvailable] false and the reason spelled out.
 */
data class NetworkStabilityState(
    /** False until the first snapshot has been folded in, so the screen shows a spinner rather than blanks. */
    val isLoaded: Boolean = false,
    /** Whether the user has switched the stability watch on. Written through by the enable switch. */
    val isEnabled: Boolean = false,
    /**
     * Whether latency measurement is allowed at all in Settings. When false the burst never runs, so ping,
     * jitter and probe counts stay unavailable however this screen's own switch is set — the screen says so
     * rather than letting the reason look like a device limit.
     */
    val isLatencyMeasured: Boolean = true,
    val isConnected: Boolean = false,
    /** True while a manual or cadence refresh is in flight, for the header's spinner. */
    val isRefreshing: Boolean = false,
    /** The live connection and its latest stability window, already formatted. Empty before the first fold. */
    val liveRows: List<Readout> = emptyList(),
    /** One honest row per reading this device might expose, each carrying whether it is available and why not. */
    val capabilities: List<NetworkCapabilityView> = emptyList(),
    /**
     * The sentence to show when the last window was measurably unstable — dropped probes or high jitter —
     * from `ConnectionStability.warning`. Null when the connection is steady or has not been measured, so
     * `state.stabilityWarning?.let { … }` is the natural shape at the call site.
     */
    val stabilityWarning: String? = null,
)

/**
 * One capability the device may or may not expose, after the ViewModel has turned the honesty wrapper into
 * something the screen can draw.
 *
 * [reason] is the full-sentence explanation for an unavailable reading, taken from
 * `Observed.unavailabilityText()` so the wording matches the same absence stated anywhere else in the app.
 * It is null exactly when [isAvailable] is true — an available capability needs no excuse.
 */
data class NetworkCapabilityView(
    val title: String,
    val isAvailable: Boolean,
    val reason: String?,
)
