package com.gamecore.ui.graphics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.common.Observed
import com.gamecore.core.common.valueOrNull
import com.gamecore.core.model.DisplaySize
import com.gamecore.core.model.DisplaySizeOutcome
import com.gamecore.core.model.ResolutionScale
import com.gamecore.core.model.ResolutionScaleChoice
import com.gamecore.core.shizuku.ElevatedShell
import com.gamecore.core.shizuku.ShellCommand
import com.gamecore.core.shizuku.WritableSetting
import com.gamecore.domain.display.DisplaySizeController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The Graphics screen's state and the two device levers behind it: render resolution and the frame-rate
 * cap. Both are the honest kind of "lower the graphics to run lighter" — fewer pixels, or a lower
 * ceiling on the refresh rate — and neither reaches inside a game's renderer. GameCore cannot replace a
 * game's textures or filtering, and this screen does not pretend to; it changes what the *system* draws
 * and reports exactly what the device did back.
 *
 * The render-resolution half is [DisplaySizeController], unchanged — the same `wm size` lever a game
 * profile already drives, surfaced here as a standing control rather than a per-game one. The cap half
 * writes `peak_refresh_rate` through the elevated shell: a cap is a ceiling on how high the display may
 * refresh, which is the one brokered lever that bounds frame rate, and `0` releases it. Both need the
 * elevated shell, and both say so plainly when it is absent rather than drawing a dead control.
 */
@HiltViewModel
class GraphicsViewModel @Inject constructor(
    private val displaySize: DisplaySizeController,
    private val shell: ElevatedShell,
) : ViewModel() {

    private val state = MutableStateFlow(GraphicsUiState())
    val uiState: StateFlow<GraphicsUiState> = state.asStateFlow()

    init {
        refresh()
    }

    /**
     * Reads the current display size and refresh cap back from the device, so the screen opens showing
     * what is actually set rather than a default. Everything here is a read; nothing is changed.
     */
    fun refresh() {
        viewModelScope.launch {
            state.update { it.copy(isLoading = true) }
            val sizeState = displaySize.state()
            val physical = sizeState.valueOrNull?.physical
            val override = sizeState.valueOrNull?.override
            val choices = physical?.let { ResolutionScale.optionsFor(it) }.orEmpty()
            // Which preset is active now: FULL when nothing is overridden, else the preset whose size
            // matches the override, or null for a custom size GameCore did not set.
            val activeScale = when {
                physical == null -> null
                override == null -> ResolutionScale.FULL
                else -> ResolutionScale.of(override, physical)
            }
            val shellReady = shell.isAvailable()
            val cap = if (shellReady) readCap() else null
            state.update {
                it.copy(
                    isLoading = false,
                    shellAvailable = shellReady,
                    shellReason = if (shellReady) null else SHELL_REASON,
                    nativeSizeLabel = physical?.label,
                    resolutionChoices = choices,
                    activeScale = activeScale,
                    fpsCap = cap,
                    sizeUnreadable = physical == null,
                )
            }
        }
    }

    /**
     * Applies a render-resolution preset, or clears the override when [scale] is [ResolutionScale.FULL].
     *
     * The outcome is read back from the display, never assumed: `wm size` exits zero whether or not the
     * panel moved, so [DisplaySizeController] re-reads and this surfaces that verdict — Applied,
     * NotHonoured, or a reason — as the screen's message. Nothing here claims success the device did not
     * confirm.
     */
    fun setResolution(scale: ResolutionScale) {
        if (!state.value.shellAvailable) {
            state.update { it.copy(message = SHELL_REASON) }
            return
        }
        viewModelScope.launch {
            state.update { it.copy(isApplying = true, message = null) }
            val outcome = displaySize.apply(scale)
            state.update { it.copy(isApplying = false, message = messageFor(outcome)) }
            refresh()
        }
    }

    /**
     * Sets the frame-rate cap, or releases it when [hz] is null.
     *
     * The cap is written to `peak_refresh_rate`: a hard ceiling on how high the display refreshes, so a
     * game cannot push frames above it, which is the honest way to bound frame rate without touching the
     * game. `0` is the release value the platform defines, so a null here writes `0` and the display is
     * free to run to its own peak again.
     */
    fun setFpsCap(hz: Int?) {
        if (!state.value.shellAvailable) {
            state.update { it.copy(message = SHELL_REASON) }
            return
        }
        viewModelScope.launch {
            state.update { it.copy(isApplying = true, message = null) }
            val value = (hz ?: 0).toFloat()
            val command = ShellCommand.putSetting(WritableSetting.PEAK_REFRESH_RATE, value)
            if (command == null) {
                state.update { it.copy(isApplying = false, message = "That cap is not a value the display accepts.") }
                return@launch
            }
            val result = shell.execute(command)
            val message = if (result.isSuccess) {
                if (hz == null) "Frame-rate cap released. The display may run to its own peak again." else "Frame rate capped at $hz Hz."
            } else {
                "The cap could not be set (${result.failureReason()})."
            }
            state.update { it.copy(isApplying = false, message = message) }
            refresh()
        }
    }

    fun dismissMessage() = state.update { it.copy(message = null) }

    /** Reads `peak_refresh_rate` back, mapping 0 / unset / unparseable to "no cap" (null). */
    private suspend fun readCap(): Int? {
        val result = shell.execute(ShellCommand.getSetting(WritableSetting.PEAK_REFRESH_RATE))
        val raw = result.singleValue()?.toFloatOrNull() ?: return null
        return if (raw <= 0f) null else raw.toInt()
    }

    private fun messageFor(outcome: DisplaySizeOutcome): String = when (outcome) {
        is DisplaySizeOutcome.Applied -> "Resolution set to ${outcome.size.label}."
        is DisplaySizeOutcome.Restored -> "Resolution back to the panel's own ${outcome.size.label}."
        is DisplaySizeOutcome.NotHonoured ->
            "The display accepted the request and stayed where it was. Some panels decline a resolution change."
        is DisplaySizeOutcome.AppliedUnverified ->
            "The change was sent but could not be read back (${outcome.reason})."
        is DisplaySizeOutcome.SizeUnsupported -> "That size is not one this display accepts (${outcome.detail})."
        is DisplaySizeOutcome.RequiresAccess -> SHELL_REASON
        is DisplaySizeOutcome.Failed -> "The change failed (${outcome.detail})."
    }

    private companion object {
        const val SHELL_REASON =
            "This needs the elevated shell (Shizuku). Connect it under Settings to change resolution or the frame cap."
    }
}

/**
 * Everything the Graphics screen draws. A pure projection: resolution and cap are read from the device
 * on open and after every change, so the screen never shows a value it only requested.
 */
data class GraphicsUiState(
    val isLoading: Boolean = true,
    val isApplying: Boolean = false,
    val shellAvailable: Boolean = false,
    val shellReason: String? = null,
    val nativeSizeLabel: String? = null,
    val resolutionChoices: List<ResolutionScaleChoice> = emptyList(),
    val activeScale: ResolutionScale? = null,
    val sizeUnreadable: Boolean = false,
    /** The current cap in Hz, or null for "no cap". */
    val fpsCap: Int? = null,
    val message: String? = null,
)
