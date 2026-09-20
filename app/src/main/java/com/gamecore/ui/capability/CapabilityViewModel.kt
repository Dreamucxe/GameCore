package com.gamecore.ui.capability

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.capability.CodecCapabilityReader
import com.gamecore.core.capability.GraphicsCapabilityReader
import com.gamecore.data.repository.DiagnosticsExport
import com.gamecore.data.repository.DiagnosticsExporter
import com.gamecore.data.repository.DiagnosticsFormat
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * GPU and codec capabilities, read once and then left alone.
 *
 * Both readers cache, and this ViewModel leans on that deliberately. A device's renderer string, its
 * Vulkan level and its codec list do not change while the app is open; re-reading them on every resume
 * would mean building an EGL context and walking `MediaCodecList` again for an answer that is already
 * known. So [load] runs once, and the only way to read again is the Refresh button — which exists because
 * a user who has just changed a developer option deserves a way to ask, not because the data goes stale
 * on its own.
 *
 * Reading the GL strings needs a real EGL context, which is slow enough to be worth doing off the main
 * thread; both readers already do their work on the IO dispatcher, so this class only has to not block.
 */
@HiltViewModel
class CapabilityViewModel @Inject constructor(
    private val graphics: GraphicsCapabilityReader,
    private val codecs: CodecCapabilityReader,
    private val exporter: DiagnosticsExporter,
) : ViewModel() {

    private val editing = MutableStateFlow(CapabilityUiState())

    val state: StateFlow<CapabilityUiState> = editing.asStateFlow()

    init {
        load(force = false)
    }

    /**
     * Reads both capability sets into one state update.
     *
     * One update rather than two, so the screen never renders a half-built report where the GPU card has
     * filled in and the codec list is still claiming to be empty.
     */
    fun load(force: Boolean) {
        viewModelScope.launch {
            editing.value = editing.value.copy(isLoading = true, message = null)
            val report = if (force) graphics.refresh() else graphics.report()
            val list = if (force) codecs.refresh() else codecs.codecs()
            editing.value = editing.value.copy(
                isLoading = false,
                report = report,
                codecs = list,
            )
        }
    }

    fun refresh() = load(force = true)

    // ------------------------------------------------------------------------------ filtering

    fun setQuery(query: String) {
        if (query == editing.value.query) return
        editing.value = editing.value.copy(query = query)
    }

    fun setFilter(filter: CodecFilter) {
        if (filter == editing.value.filter) return
        editing.value = editing.value.copy(filter = filter)
    }

    fun clearFilters() {
        editing.value = editing.value.copy(query = "", filter = CodecFilter.ALL)
    }

    /** Opens one codec's detail, or closes it if it was already the open one. */
    fun toggleCodec(codecName: String, mimeType: String) {
        val key = "$codecName|$mimeType"
        editing.value = editing.value.copy(expanded = if (editing.value.expanded == key) null else key)
    }

    // -------------------------------------------------------------------------------- export

    fun export(format: DiagnosticsFormat) {
        val report = editing.value.report ?: return
        val list = editing.value.codecs ?: return
        if (editing.value.isExporting) return
        viewModelScope.launch {
            editing.value = editing.value.copy(isExporting = true, message = null)
            val result = exporter.exportCapabilities(graphics = report, codecs = list, format = format)
            editing.value = editing.value.copy(
                isExporting = false,
                export = result,
                message = messageFor(result),
            )
        }
    }

    fun shareIntent(): Intent? {
        val written = editing.value.export as? DiagnosticsExport.Written ?: return null
        val uri = written.uri ?: return null
        return Intent(Intent.ACTION_SEND)
            .setType(written.mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun onIntentFailed() {
        editing.value = editing.value.copy(message = NO_SHARE_TARGET)
    }

    fun dismissMessage() {
        editing.value = editing.value.copy(message = null)
    }

    private fun messageFor(result: DiagnosticsExport): String? = when (result) {
        is DiagnosticsExport.Written -> null
        DiagnosticsExport.Empty -> NOTHING_TO_EXPORT
        is DiagnosticsExport.Failed -> "That file could not be written (${result.detail})."
    }

    private companion object {
        const val NOTHING_TO_EXPORT =
            "There is nothing to write yet. Wait for the scan to finish, then try again."

        const val NO_SHARE_TARGET =
            "No app on this device offered to take that file. It is still saved inside GameCore."
    }
}
