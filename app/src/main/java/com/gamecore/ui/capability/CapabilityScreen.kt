package com.gamecore.ui.capability

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.core.capability.CodecClassification
import com.gamecore.core.capability.CodecEntry
import com.gamecore.core.capability.DisplayCapability
import com.gamecore.core.capability.GraphicsLimit
import com.gamecore.core.capability.GraphicsReport
import com.gamecore.core.capability.VulkanReport
import com.gamecore.core.common.Formatters
import com.gamecore.core.common.Observed
import com.gamecore.core.common.unavailabilityText
import com.gamecore.data.repository.DiagnosticsExport
import com.gamecore.data.repository.DiagnosticsFormat
import com.gamecore.ui.components.ABSENT
import com.gamecore.ui.components.ActionRow
import com.gamecore.ui.components.ChoiceRow
import com.gamecore.ui.components.ClickableCard
import com.gamecore.ui.components.KeyValueRow
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.ObservedRow
import com.gamecore.ui.components.RowDivider
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.StatEntry
import com.gamecore.ui.components.StatStrip
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.TextFieldRow
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.startIntentSafely

/**
 * Codec and GPU capability scanner: what this device's graphics stack and media stack actually report.
 *
 * The rule the whole screen follows is that Android's silence is information. A missing renderer string,
 * a device with no Vulkan, a codec that will not state its maximum frame rate — each of those is shown as
 * the specific reason it is missing. Nothing on this screen is derived from the model name, the chipset,
 * or what a device of this class "normally" supports, because a diagnostics tool that guesses is worse
 * than one that admits it does not know.
 */
@Composable
fun CapabilityScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CapabilityViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val padded = Modifier.padding(horizontal = ScreenPadding)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Codec & GPU scanner",
                subtitle = "Graphics and media capabilities, as this device reports them",
                onBack = onBack,
                action = {
                    if (state.isLoading) {
                        StatusChip("Scanning", Tone.Accent)
                    } else {
                        TextButton(onClick = viewModel::refresh) { Text("Refresh") }
                    }
                },
            )
        }

        state.message?.let { message ->
            item {
                NoteBanner(
                    text = message,
                    tone = Tone.Accent,
                    icon = Icons.Filled.Info,
                    modifier = padded,
                    action = { TextButton(onClick = viewModel::dismissMessage) { Text("OK") } },
                )
            }
        }

        val report = state.report
        if (report == null) {
            item {
                NoteBanner(
                    text = if (state.isLoading) SCANNING else SCAN_FAILED,
                    tone = Tone.Muted,
                    icon = Icons.Filled.Refresh,
                    modifier = padded,
                )
            }
            return@LazyColumn
        }

        item { DeviceCard(report = report, modifier = padded) }
        item { GpuCard(report = report, modifier = padded) }
        item { VulkanCard(vulkan = report.vulkan, modifier = padded) }
        item { DisplayCard(display = report.display, modifier = padded) }
        item { LimitsCard(limits = report.limits, modifier = padded) }
        item { TextureCard(report = report, modifier = padded) }

        item {
            CodecHeaderCard(
                state = state,
                onQuery = viewModel::setQuery,
                onFilter = viewModel::setFilter,
                onClear = viewModel::clearFilters,
                modifier = padded,
            )
        }

        val codecs = state.codecs
        if (codecs != null && codecs !is Observed.Value) {
            item {
                NoteBanner(
                    text = codecs.unavailabilityText() ?: CODECS_UNREADABLE,
                    tone = Tone.Muted,
                    icon = Icons.Filled.Info,
                    modifier = padded,
                )
            }
        } else {
            val visible = state.visibleCodecs
            if (visible.isEmpty()) {
                item {
                    NoteBanner(
                        text = if (state.isFiltered) NO_MATCHES else NO_CODECS,
                        tone = Tone.Muted,
                        icon = Icons.Filled.Info,
                        modifier = padded,
                    )
                }
            } else {
                items(visible.size) { index ->
                    val entry = visible[index]
                    CodecRow(
                        entry = entry,
                        isExpanded = state.expanded == "${entry.codecName}|${entry.mimeType}",
                        onClick = { viewModel.toggleCodec(entry.codecName, entry.mimeType) },
                        modifier = padded,
                    )
                }
            }
        }

        item {
            ExportCard(
                state = state,
                onExport = viewModel::export,
                onShare = {
                    val intent = viewModel.shareIntent()
                    if (intent == null || !context.startIntentSafely(Intent.createChooser(intent, "Share"))) {
                        viewModel.onIntentFailed()
                    }
                },
                modifier = padded,
            )
        }

        item {
            NoteBanner(
                text = HONESTY_NOTE,
                tone = Tone.Muted,
                icon = Icons.Filled.Info,
                modifier = padded,
            )
        }
    }
}

@Composable
private fun DeviceCard(report: GraphicsReport, modifier: Modifier = Modifier) {
    SectionCard(
        title = "Device",
        modifier = modifier,
        subtitle = report.deviceSummary,
        icon = Icons.Filled.PhoneAndroid,
    ) {
        KeyValueRow(label = "Android", value = report.androidSummary)
        ObservedRow(label = "Declared OpenGL ES", observed = report.declaredGlesVersion) { it }
        ObservedRow(label = "AEP extension pack", observed = report.extensionPack) { it }
        ObservedRow(label = "Low-RAM device", observed = report.lowRamDevice) { if (it) "Yes" else "No" }
    }
}

@Composable
private fun GpuCard(report: GraphicsReport, modifier: Modifier = Modifier) {
    SectionCard(
        title = "GPU",
        modifier = modifier,
        subtitle = "From a real EGL context on this device",
        icon = Icons.Filled.Memory,
    ) {
        ObservedRow(label = "Renderer", observed = report.renderer, tone = Tone.Accent) { it }
        ObservedRow(label = "Vendor", observed = report.vendor) { it }
        ObservedRow(label = "OpenGL ES", observed = report.glVersion) { it }
        ObservedRow(label = "Shading language", observed = report.shadingLanguageVersion) { it }
        ObservedRow(label = "Context created as", observed = report.contextClientVersion) { it }
        Spacer(modifier = Modifier.height(10.dp))
        RowDivider()
        Spacer(modifier = Modifier.height(10.dp))
        ObservedRow(label = "EGL vendor", observed = report.eglVendor) { it }
        ObservedRow(label = "EGL version", observed = report.eglVersion) { it }
        ObservedRow(label = "EGL client APIs", observed = report.eglClientApis) { it }
        ObservedRow(label = "GL extensions", observed = report.extensions) { "${it.size}" }
        ObservedRow(label = "EGL extensions", observed = report.eglExtensions) { "${it.size}" }
    }
}

/**
 * Vulkan, which is present or is not — there is no middle answer worth printing.
 *
 * A device without the Vulkan feature flag gets the absence stated, not an empty card that reads like a
 * loading failure.
 */
@Composable
private fun VulkanCard(vulkan: VulkanReport, modifier: Modifier = Modifier) {
    SectionCard(
        title = "Vulkan",
        modifier = modifier,
        icon = Icons.Filled.Layers,
        action = {
            StatusChip(
                text = if (vulkan.supported) "AVAILABLE" else "UNAVAILABLE",
                tone = if (vulkan.supported) Tone.Good else Tone.Muted,
            )
        },
    ) {
        if (!vulkan.supported) {
            Text(
                text = NO_VULKAN,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        ObservedRow(label = "API version", observed = vulkan.apiVersion, tone = Tone.Accent) { it }
        ObservedRow(label = "Packed version", observed = vulkan.packedVersion) { it.toString() }
        ObservedRow(label = "Hardware level", observed = vulkan.hardwareLevel) { it }
        ObservedRow(label = "Compute level", observed = vulkan.computeLevel) { it }
        ObservedRow(label = "Device", observed = vulkan.deviceProperties) { it }
        ObservedRow(label = "Device extensions", observed = vulkan.deviceExtensions) { "${it.size}" }

        val extensions = (vulkan.deviceExtensions as? Observed.Value)?.value
        if (!extensions.isNullOrEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            ChipCloud(extensions.take(EXTENSION_PREVIEW))
            if (extensions.size > EXTENSION_PREVIEW) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "${extensions.size - EXTENSION_PREVIEW} more in the export.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DisplayCard(display: DisplayCapability, modifier: Modifier = Modifier) {
    SectionCard(
        title = "Display",
        modifier = modifier,
        subtitle = "Modes and colour, from the display this app is on",
        icon = Icons.Filled.Visibility,
    ) {
        ObservedRow(label = "Wide colour gamut", observed = display.wideColorGamut) {
            if (it) "Supported" else "Not supported"
        }
        ObservedRow(label = "HDR types", observed = display.hdrTypes) {
            if (it.isEmpty()) "None reported" else it.joinToString(", ")
        }
        val modes = (display.modes as? Observed.Value)?.value
        if (modes.isNullOrEmpty()) {
            ObservedRow(label = "Modes", observed = display.modes) { "${it.size}" }
        } else {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "MODES",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            modes.forEach { mode ->
                Text(
                    text = "•  $mode",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
        }
    }
}

/** The driver's own ceilings — texture size, render buffer, work-group counts. */
@Composable
private fun LimitsCard(limits: Observed<List<GraphicsLimit>>, modifier: Modifier = Modifier) {
    SectionCard(
        title = "GL limits",
        modifier = modifier,
        subtitle = "Queried from the driver, not assumed",
        icon = Icons.Filled.Storage,
    ) {
        val values = (limits as? Observed.Value)?.value
        if (values.isNullOrEmpty()) {
            Text(
                text = limits.unavailabilityText() ?: LIMITS_EMPTY,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        values.forEach { limit ->
            val reading = limit.value
            KeyValueRow(
                label = limit.label,
                value = if (reading == null) "UNAVAILABLE" else "$reading${limit.unit}",
                tone = if (reading == null) Tone.Muted else Tone.Neutral,
            )
        }
    }
}

@Composable
private fun TextureCard(report: GraphicsReport, modifier: Modifier = Modifier) {
    SectionCard(
        title = "Texture compression",
        modifier = modifier,
        subtitle = "The formats this GPU can sample directly",
        icon = Icons.Filled.Layers,
    ) {
        ObservedRow(label = "Compressed formats", observed = report.compressedTextureFormatCount) {
            it.toString()
        }
        val families = (report.textureCompressionFamilies as? Observed.Value)?.value
        if (families.isNullOrEmpty()) {
            ObservedRow(label = "Families", observed = report.textureCompressionFamilies) {
                if (it.isEmpty()) "None detected" else it.joinToString(", ")
            }
        } else {
            Spacer(modifier = Modifier.height(10.dp))
            ChipCloud(families)
        }
    }
}

/** Search, family chips, and how much of the list is being shown. */
@Composable
private fun CodecHeaderCard(
    state: CapabilityUiState,
    onQuery: (String) -> Unit,
    onFilter: (CodecFilter) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Codecs",
        modifier = modifier,
        subtitle = "From MediaCodecList on this device",
        icon = Icons.Filled.MusicNote,
        action = {
            StatusChip(
                text = "${state.visibleCodecs.size} / ${state.totalCodecs}",
                tone = if (state.isFiltered) Tone.Accent else Tone.Muted,
            )
        },
    ) {
        TextFieldRow(
            label = "Search",
            value = state.query,
            onValueChange = onQuery,
            placeholder = "H.264, HEVC, AV1, VP9, opus…",
        )
        Spacer(modifier = Modifier.height(8.dp))
        ChoiceRow(
            options = CodecFilter.entries.toList(),
            selected = state.filter,
            onSelect = onFilter,
            label = { it.label },
            perRow = 3,
        )
        if (state.isFiltered) {
            Spacer(modifier = Modifier.height(8.dp))
            ActionRow { OutlinedButton(onClick = onClear) { Text("Clear filters") } }
        }
    }
}

/**
 * One codec, collapsed to what identifies it and expandable to everything Android will say about it.
 *
 * Collapsed by default because a mid-range phone lists well over a hundred of these, and a screen that
 * opened with every profile and level of every one of them is a wall rather than a tool.
 */
@Composable
private fun CodecRow(
    entry: CodecEntry,
    isExpanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ClickableCard(onClick = onClick, modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.codecName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "${entry.mimeType} · ${entry.direction}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusChip(
                text = entry.classification.label,
                tone = when (entry.classification) {
                    CodecClassification.HARDWARE -> Tone.Good
                    CodecClassification.SOFTWARE -> Tone.Neutral
                    CodecClassification.UNKNOWN -> Tone.Muted
                },
            )
        }

        if (!isExpanded) return@ClickableCard

        Spacer(modifier = Modifier.height(10.dp))
        RowDivider()
        Spacer(modifier = Modifier.height(10.dp))
        CodecDetail(entry)
    }
}

@Composable
private fun CodecDetail(entry: CodecEntry) {
    Column {
        entry.canonicalName?.let { KeyValueRow(label = "Canonical name", value = it) }
        KeyValueRow(label = "Family", value = entry.family)
        entry.isVendor?.let { KeyValueRow(label = "Vendor codec", value = if (it) "Yes" else "No") }
        entry.isAlias?.let { KeyValueRow(label = "Alias", value = if (it) "Yes" else "No") }
        entry.maxInstances?.let { KeyValueRow(label = "Concurrent instances", value = it.toString()) }
        KeyValueRow(label = "Colour formats", value = entry.colorFormatCount.toString())
        if (entry.secureRequired) {
            KeyValueRow(label = "Secure decoder", value = "Required", tone = Tone.Warning)
        }

        entry.video?.let { video ->
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "VIDEO",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            KeyValueRow(
                label = "Maximum size",
                value = if (video.maxWidth != null && video.maxHeight != null) {
                    Formatters.resolution(video.maxWidth, video.maxHeight)
                } else {
                    "UNAVAILABLE"
                },
                tone = if (video.maxWidth == null) Tone.Muted else Tone.Accent,
            )
            video.achievableResolution?.let {
                KeyValueRow(label = "At maximum width", value = it)
            }
            KeyValueRow(label = "Frame rate", value = rangeOf(video.minFrameRate, video.maxFrameRate, " fps"))
            KeyValueRow(label = "Bitrate", value = rangeOf(video.minBitrate, video.maxBitrate, " bps"))
            if (video.widthAlignment != null && video.heightAlignment != null) {
                KeyValueRow(
                    label = "Alignment",
                    value = "${video.widthAlignment} × ${video.heightAlignment}",
                )
            }
            video.performancePoints.forEach { point ->
                Text(
                    text = "•  $point",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
        }

        entry.audio?.let { audio ->
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "AUDIO",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            KeyValueRow(label = "Channels", value = audio.maxChannels?.toString() ?: "UNAVAILABLE")
            KeyValueRow(label = "Bitrate", value = rangeOf(audio.minBitrate, audio.maxBitrate, " bps"))
            if (audio.sampleRates.isNotEmpty()) {
                KeyValueRow(
                    label = "Sample rates",
                    value = audio.sampleRates.joinToString(", ") { "$it" },
                )
            }
            audio.sampleRateRanges.forEach { range ->
                KeyValueRow(label = "Sample rate range", value = range)
            }
        }

        if (entry.hdrProfiles.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "HDR",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            ChipCloud(entry.hdrProfiles)
        }

        if (entry.profileLevels.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "PROFILES AND LEVELS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            entry.profileLevels.forEach { level ->
                Text(
                    text = "•  $level",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
        }
    }
}

@Composable
private fun ExportCard(
    state: CapabilityUiState,
    onExport: (DiagnosticsFormat) -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Capability report",
        modifier = modifier,
        subtitle = "Everything on this screen, in one file",
        icon = Icons.Filled.Storage,
    ) {
        StatStrip(
            entries = listOf(
                StatEntry("Codecs", state.totalCodecs.toString()),
                StatEntry("Vulkan", if (state.report?.vulkan?.supported == true) "Yes" else "No"),
                StatEntry(
                    label = "GL",
                    value = (state.report?.glVersion as? Observed.Value)?.value?.take(GL_LABEL_LIMIT)
                        ?: ABSENT,
                ),
            ),
        )
        Spacer(modifier = Modifier.height(10.dp))
        ActionRow {
            OutlinedButton(
                onClick = { onExport(DiagnosticsFormat.JSON) },
                enabled = state.report != null && !state.isExporting,
            ) { Text("JSON") }
            OutlinedButton(
                onClick = { onExport(DiagnosticsFormat.CSV) },
                enabled = state.report != null && !state.isExporting,
            ) { Text("CSV") }
            OutlinedButton(
                onClick = { onExport(DiagnosticsFormat.TEXT) },
                enabled = state.report != null && !state.isExporting,
            ) { Text("Text") }
        }
        state.lastExport?.let { written ->
            Spacer(modifier = Modifier.height(10.dp))
            KeyValueRow(
                label = written.fileName,
                value = Formatters.bytes(written.sizeBytes),
                tone = Tone.Good,
            )
            if (written.uri != null) {
                Spacer(modifier = Modifier.height(6.dp))
                ActionRow { OutlinedButton(onClick = onShare) { Text("Share") } }
            }
        }
    }
}

// ------------------------------------------------------------------------------------ helpers

/**
 * A wrapping row of read-only chips, for extension and profile lists that have no fixed length.
 *
 * Chunked into fixed columns rather than flowed. [StatusChip] takes no modifier, so each one is boxed to
 * give it a share of the row — which also keeps the columns lined up down the card instead of ragged.
 */
@Composable
private fun ChipCloud(values: List<String>) {
    values.chunked(CHIPS_PER_ROW).forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            row.forEach { value ->
                Box(modifier = Modifier.weight(1f)) { StatusChip(text = value, tone = Tone.Muted) }
            }
            repeat(CHIPS_PER_ROW - row.size) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

/** A range where either end may be missing, said as "UNAVAILABLE" rather than filled in. */
private fun rangeOf(low: Int?, high: Int?, unit: String): String = when {
    low != null && high != null && low == high -> "$low$unit"
    low != null && high != null -> "$low – $high$unit"
    high != null -> "up to $high$unit"
    low != null -> "from $low$unit"
    else -> "UNAVAILABLE"
}

private const val EXTENSION_PREVIEW = 12

/** Two chips a row keeps a long GL extension name readable on a narrow phone. */
private const val CHIPS_PER_ROW = 2

private const val GL_LABEL_LIMIT = 12

private const val SCANNING =
    "Reading the graphics stack. This builds a real EGL context and walks the codec list once, then caches " +
        "the answer for as long as the app is running."

private const val SCAN_FAILED =
    "The capability scan did not produce a report. Refresh to try again."

private const val NO_VULKAN =
    "This device does not declare the Vulkan feature to Android, so there is no Vulkan driver here to " +
        "question. GameCore reports that rather than filling the card with values from a device that does."

private const val LIMITS_EMPTY =
    "The driver returned no limits. They are queried with glGetIntegerv against a live context; a device " +
        "that answers nothing is reported as answering nothing."

private const val CODECS_UNREADABLE =
    "MediaCodecList could not be read on this device."

private const val NO_CODECS =
    "MediaCodecList returned no entries on this device."

private const val NO_MATCHES =
    "No codec matches that search. Clear the filters to see the whole list."

private const val HONESTY_NOTE =
    "Everything above came from OpenGL ES, EGL, the Vulkan feature flags and MediaCodecList on this " +
        "device. Where Android does not expose a value, the row says UNAVAILABLE instead of showing a " +
        "number — GameCore never fills a gap with a figure from a spec sheet or from a similar device."
