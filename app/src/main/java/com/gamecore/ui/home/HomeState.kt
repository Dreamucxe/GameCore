package com.gamecore.ui.home

import com.gamecore.core.model.DeviceCapabilities
import com.gamecore.core.model.OverlayStatus
import com.gamecore.core.model.ShizukuState
import com.gamecore.domain.gaming.GamingState
import com.gamecore.ui.components.PENDING
import com.gamecore.ui.components.Readout
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.readoutOf

/**
 * What the dashboard shows besides the live figures.
 *
 * Deliberately small and already resolved: counts, states and sentences. The screen renders it without
 * asking a second question of anything, which is what keeps the dashboard from being the place where
 * eleven subsystems are queried on every recomposition.
 */
data class HomeUiState(
    val shizuku: ShizukuState = ShizukuState.NOT_INSTALLED,
    val overlay: OverlayStatus = OverlayStatus.OFF,
    val profileCount: Int = 0,
    val gaming: GamingState = GamingState.IDLE,
    val capabilities: DeviceCapabilities = DeviceCapabilities.UNKNOWN,
    val repairedSessions: Int = 0,
    val outstandingRestores: Int = 0,
    val isRestoring: Boolean = false,
    val message: String? = null,
) {
    val hasOverlayPermission: Boolean get() = overlay.hasPermission

    /** True when the launch found something the user has to be told rather than merely know. */
    val hasStartupFindings: Boolean get() = repairedSessions > 0 || outstandingRestores > 0
}

/**
 * The live figures, already formatted.
 *
 * §24A.2: the dashboard consumes strings, so the conversion from
 * [com.gamecore.core.model.PerformanceSnapshot] happens in the ViewModel and the snapshot itself never
 * reaches a composable. [tiles] is a list rather than seven named fields because the dashboard lays them
 * out as a grid and has no reason to address one of them individually — and a list keeps the pending set
 * below identical in shape to a real one, so the grid does not reflow when the first sample lands.
 */
data class HomeReadouts(
    val tiles: List<Readout>,
    /** Set only when the platform is actually throttling. Drawn as a banner, not as a tile. */
    val thermalNote: String? = null,
    val hasSample: Boolean = false,
) {
    companion object {
        /**
         * The grid before the first sample: the right labels, with `…` where the figures will be.
         *
         * Not an empty list and not a spinner. The tiles are the same size and in the same order as
         * they will be a second later, so the dashboard settles once rather than growing into place.
         */
        val AWAITING = HomeReadouts(
            tiles = HomeLabels.ALL.map { readoutOf(label = it, value = PENDING, tone = Tone.Muted) },
        )
    }
}

/** The dashboard's tile labels, in the order §5 lists them. */
internal object HomeLabels {
    const val CPU = "CPU"
    const val MEMORY = "RAM"
    const val BATTERY = "Battery"
    const val TEMPERATURE = "Temperature"
    const val REFRESH = "Refresh rate"
    const val DISPLAY = "Display"
    const val STORAGE = "Storage"

    val ALL = listOf(CPU, MEMORY, BATTERY, TEMPERATURE, REFRESH, DISPLAY, STORAGE)
}
