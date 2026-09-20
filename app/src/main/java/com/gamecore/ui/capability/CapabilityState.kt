package com.gamecore.ui.capability

import com.gamecore.core.capability.CodecClassification
import com.gamecore.core.capability.CodecEntry
import com.gamecore.core.capability.GraphicsReport
import com.gamecore.core.common.Observed
import com.gamecore.data.repository.DiagnosticsExport

/**
 * The capability scanner's state: what this device's graphics stack and codec list actually report.
 *
 * Every field that could be missing is an [Observed], and that is the point of the screen. A GPU that
 * does not expose its driver version, a device with no Vulkan, a codec list that a vendor implementation
 * refuses to enumerate — each of those has a specific answer, and each is shown as that answer rather than
 * as a zero, a dash with no reason, or a plausible-looking string.
 */
data class CapabilityUiState(
    val isLoading: Boolean = true,
    val report: GraphicsReport? = null,
    val codecs: Observed<List<CodecEntry>>? = null,
    val query: String = "",
    val filter: CodecFilter = CodecFilter.ALL,
    val expanded: String? = null,
    val isExporting: Boolean = false,
    val export: DiagnosticsExport? = null,
    val message: String? = null,
) {
    /** The codec list after the search box and the family chips, in a stable order. */
    val visibleCodecs: List<CodecEntry>
        get() {
            val all = (codecs as? Observed.Value)?.value ?: return emptyList()
            return all.asSequence()
                .filter { filter.accepts(it) }
                .filter { query.isBlank() || it.matches(query) }
                .toList()
        }

    val totalCodecs: Int get() = (codecs as? Observed.Value)?.value?.size ?: 0

    val isFiltered: Boolean get() = query.isNotBlank() || filter != CodecFilter.ALL

    val lastExport: DiagnosticsExport.Written? get() = export as? DiagnosticsExport.Written
}

/**
 * The codec filters, as families rather than as MIME strings.
 *
 * "Video" and "Audio" come from the MIME prefix, which is reliable. "Hardware" comes from
 * `isHardwareAccelerated`, which Android only answers from API 29 — below that the classification is
 * UNKNOWN and this filter shows nothing rather than guessing from a codec's name, since a name containing
 * "OMX.qcom" is a convention and not a guarantee.
 */
enum class CodecFilter(val label: String) {
    ALL("All") {
        override fun accepts(entry: CodecEntry) = true
    },
    VIDEO("Video") {
        override fun accepts(entry: CodecEntry) = entry.isVideo
    },
    AUDIO("Audio") {
        override fun accepts(entry: CodecEntry) = entry.isAudio
    },
    DECODERS("Decoders") {
        override fun accepts(entry: CodecEntry) = !entry.isEncoder
    },
    ENCODERS("Encoders") {
        override fun accepts(entry: CodecEntry) = entry.isEncoder
    },
    HARDWARE("Hardware") {
        override fun accepts(entry: CodecEntry) =
            entry.classification == CodecClassification.HARDWARE
    },
    ;

    abstract fun accepts(entry: CodecEntry): Boolean
}
