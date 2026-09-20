package com.gamecore.ui.touch

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.input.TouchAction
import com.gamecore.core.input.TouchLog
import com.gamecore.core.input.TouchViewMode
import com.gamecore.data.repository.DiagnosticsExport
import com.gamecore.data.repository.DiagnosticsExporter
import com.gamecore.data.repository.DiagnosticsFormat
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Touch capture, from GameCore's own pad and from nowhere else.
 *
 * Two rates again, for the same reason the motion screen has two. A finger dragging across the pad
 * produces move events at the display's rate, and publishing a new state for each one would recompose the
 * heatmap sixty or a hundred and twenty times a second to move one dot. So [onTouch] writes into
 * [TouchLog] — an array write and a list append, no allocation of state — and a pulse coroutine rebuilds
 * the snapshot [REFRESH_MILLIS] apart, and only when something has actually been recorded since the last
 * one. A finger held still costs nothing.
 *
 * The pulse is inside the shared state, so it runs only while the screen is collected: navigating away
 * stops the rebuild loop as well as the capture.
 *
 * Nothing recorded here leaves the device unless the user exports the file and shares it themselves.
 */
@HiltViewModel
class TouchViewModel @Inject constructor(
    private val exporter: DiagnosticsExporter,
) : ViewModel() {

    private val log = TouchLog()

    private val local = MutableStateFlow(TouchUiState())

    /**
     * Bumped on the UI thread by [onTouch]; read by the pulse coroutine.
     *
     * Volatile rather than atomic: this is a change *detector*, not a count. A lost increment under a race
     * would at worst delay one redraw by [REFRESH_MILLIS], and the next touch corrects it.
     */
    @Volatile
    private var revision = 0

    private val pulses = flow {
        var seen = -1
        while (true) {
            val current = revision
            if (current != seen) {
                seen = current
                emit(current)
            }
            delay(REFRESH_MILLIS)
        }
    }

    val state: StateFlow<TouchUiState> = combine(local, pulses) { own, _ ->
        own.copy(
            snapshot = log.snapshot(),
            recentEvents = log.recentEvents(RAW_EVENT_LIMIT),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIBE_GRACE_MILLIS),
        initialValue = TouchUiState(),
    )

    // ------------------------------------------------------------------------------ capture

    /**
     * One touch event from the capture pad.
     *
     * Called from the pad's own pointer filter, so every event here was dispatched to that composable by
     * Android. There is no path in this class that could receive an event from anywhere else.
     *
     * The guard is not only tidiness: while capture is off the pad declines the gesture outright, so the
     * list scrolls under the user's finger instead of the pad swallowing it.
     */
    fun onTouch(
        pointerId: Long,
        action: TouchAction,
        x: Float,
        y: Float,
        pressure: Float?,
        activePointers: Int,
        timestampMillis: Long,
    ) {
        if (!local.value.isCapturing) return
        log.record(
            pointerId = pointerId,
            action = action,
            x = x,
            y = y,
            pressure = pressure,
            activePointers = activePointers,
            timestampMillis = timestampMillis,
        )
        revision++
    }

    fun setCapturing(capturing: Boolean) {
        if (capturing == local.value.isCapturing) return
        local.value = local.value.copy(
            isCapturing = capturing,
            message = if (capturing) null else local.value.message,
        )
    }

    fun setMode(mode: TouchViewMode) {
        if (mode == local.value.mode) return
        local.value = local.value.copy(mode = mode)
    }

    fun clear() {
        log.clear()
        revision++
        local.value = local.value.copy(export = null, message = null)
    }

    // ------------------------------------------------------------------------------- export

    fun export(format: DiagnosticsFormat) {
        if (local.value.isExporting) return
        viewModelScope.launch {
            local.value = local.value.copy(isExporting = true, message = null)
            val result = exporter.exportTouches(snapshot = log.snapshot(), format = format)
            local.value = local.value.copy(
                isExporting = false,
                export = result,
                message = messageFor(result),
            )
        }
    }

    fun shareIntent(): Intent? {
        val written = local.value.export as? DiagnosticsExport.Written ?: return null
        val uri = written.uri ?: return null
        return Intent(Intent.ACTION_SEND)
            .setType(written.mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun onIntentFailed() {
        local.value = local.value.copy(message = NO_SHARE_TARGET)
    }

    fun dismissMessage() {
        local.value = local.value.copy(message = null)
    }

    private fun messageFor(result: DiagnosticsExport): String? = when (result) {
        is DiagnosticsExport.Written -> null
        DiagnosticsExport.Empty -> NOTHING_CAPTURED
        is DiagnosticsExport.Failed -> "That file could not be written (${result.detail})."
    }

    private companion object {
        /** About fifteen redraws a second, which reads as live and is a tenth of the event rate. */
        const val REFRESH_MILLIS = 66L

        const val SUBSCRIBE_GRACE_MILLIS = 500L

        /** How many events the raw view shows. The log keeps more; a screen cannot show more. */
        const val RAW_EVENT_LIMIT = 40

        const val NOTHING_CAPTURED =
            "Nothing captured yet. Turn capture on and draw on the pad."

        const val NO_SHARE_TARGET =
            "No app on this device offered to take that file. It is still saved inside GameCore."
    }
}
