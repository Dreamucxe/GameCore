package com.gamecore.data.repository

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.JsonWriter
import androidx.core.content.FileProvider
import com.gamecore.BuildConfig
import com.gamecore.core.capability.CodecEntry
import com.gamecore.core.capability.GraphicsReport
import com.gamecore.core.common.Formatters
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.common.Observed
import com.gamecore.core.common.unavailabilityText
import com.gamecore.core.common.valueOrNull
import com.gamecore.core.input.ControllerDevice
import com.gamecore.core.input.ControllerReader
import com.gamecore.core.input.TouchSnapshot
import com.gamecore.core.sensors.MotionSensorKind
import com.gamecore.core.sensors.MotionSummary
import com.gamecore.core.sensors.MotionTrace
import com.gamecore.core.sensors.SensorDescriptor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.Writer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes the diagnostics modules' data out as a file the user can keep.
 *
 * The same arrangement as [SessionExporter] and for the same reasons: the file lands in GameCore's own
 * `files/exports/diagnostics` directory and is handed out as a `content://` URI with a one-shot read
 * grant, so the app needs no storage permission on any API level, and only the last few exports of each
 * kind are kept so a convenience copy does not become a slow leak. Nothing is uploaded, and there is
 * nowhere for it to be uploaded to — the write, the directory and the share sheet are the whole path.
 *
 * A separate class from [SessionExporter] rather than more methods on it. Its `ExportResult.Written`
 * carries a `sessionCount`, which is the right field for a session history and a wrong one for a
 * gyroscope trace or a codec list; giving this its own result type means no existing export call site
 * changes and the count in each one means what its name says.
 *
 * Three things about the writing worth knowing:
 *
 *  - **It streams.** Every entry point is handed a [Writer] and fills it, rather than building a string
 *    and writing that. A ten-minute recording is thirty thousand samples, and the difference between
 *    streaming it and assembling it is several megabytes of peak heap on a device that may be mid-game.
 *  - **Absences survive.** An [Observed] that is not a reading is written as `{"unavailable": "…"}` in
 *    JSON and as its explanation in the text report — never as a blank, a zero or an omitted key.
 *    §"Never insert fake values": a file that showed `"vulkanApiVersion": 0` would be a fabricated
 *    reading the moment it left the app, and the export is the one place nobody is around to correct it.
 *  - **Numbers are formatted by hand.** `String.format("%.4f")` follows the default locale and emits
 *    `0,4327` in half of Europe, which is a broken CSV and invalid JSON. [fixed] does the same job with
 *    integer arithmetic and no locale involved.
 */
@Singleton
class DiagnosticsExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * The gyroscope and accelerometer recording.
     *
     * JSON and CSV carry every recorded sample — the raw telemetry the module offers to export. Text
     * carries the session summary and the sensor descriptions and not the samples, because thirty
     * thousand rows of aligned columns is not a document anyone reads.
     */
    suspend fun exportMotion(
        trace: MotionTrace,
        summary: MotionSummary,
        sensors: List<Pair<MotionSensorKind, Observed<SensorDescriptor>>>,
        format: DiagnosticsFormat,
    ): DiagnosticsExport = withContext(io) {
        if (trace.size == 0 && summary.isEmpty) return@withContext DiagnosticsExport.Empty
        write(KIND_MOTION, format) { out ->
            when (format) {
                DiagnosticsFormat.JSON -> motionJson(out, trace, summary, sensors)
                DiagnosticsFormat.CSV -> motionCsv(out, trace)
                DiagnosticsFormat.TEXT -> motionText(out, trace, summary, sensors)
            }
        }
    }

    /**
     * The touch capture.
     *
     * Coordinates are the fractions the log stored, not pixels, so the file is meaningful without the
     * device's screen geometry travelling with it. What is in here is what GameCore's own window saw:
     * no screen contents, no text, and no other application's events, which Android does not offer and
     * this class does not ask for.
     */
    suspend fun exportTouches(
        snapshot: TouchSnapshot,
        format: DiagnosticsFormat,
    ): DiagnosticsExport = withContext(io) {
        if (snapshot.summary.totalEvents == 0) return@withContext DiagnosticsExport.Empty
        write(KIND_TOUCH, format) { out ->
            when (format) {
                DiagnosticsFormat.JSON -> touchJson(out, snapshot)
                DiagnosticsFormat.CSV -> touchCsv(out, snapshot)
                DiagnosticsFormat.TEXT -> touchText(out, snapshot)
            }
        }
    }

    /**
     * The graphics and codec capability report, together, because that is the one report the scanner shows.
     *
     * CSV is the codec table alone: a GL extension list, a Vulkan block and a display mode list have no
     * shared column shape, and inventing one would make a spreadsheet out of something that is not a
     * table. JSON and text carry everything.
     */
    suspend fun exportCapabilities(
        graphics: GraphicsReport,
        codecs: Observed<List<CodecEntry>>,
        format: DiagnosticsFormat,
    ): DiagnosticsExport = withContext(io) {
        write(KIND_CAPABILITY, format) { out ->
            when (format) {
                DiagnosticsFormat.JSON -> capabilityJson(out, graphics, codecs)
                DiagnosticsFormat.CSV -> codecCsv(out, codecs)
                DiagnosticsFormat.TEXT -> capabilityText(out, graphics, codecs)
            }
        }
    }

    /** The attached controllers, exactly as `InputDevice` described them. No latency figure, here either. */
    suspend fun exportControllers(
        devices: List<ControllerDevice>,
        format: DiagnosticsFormat,
    ): DiagnosticsExport = withContext(io) {
        if (devices.isEmpty()) return@withContext DiagnosticsExport.Empty
        write(KIND_CONTROLLER, format) { out ->
            when (format) {
                DiagnosticsFormat.JSON -> controllerJson(out, devices)
                DiagnosticsFormat.CSV -> controllerCsv(out, devices)
                DiagnosticsFormat.TEXT -> controllerText(out, devices)
            }
        }
    }

    // ---------------------------------------------------------------- file handling

    /**
     * Creates the file, lets [body] fill it, and turns the outcome into a result.
     *
     * [body] returns the number of records it wrote, which is the only thing about the content this
     * layer knows: samples for a trace, events for a capture, codecs for a scan.
     */
    private fun write(
        kind: String,
        format: DiagnosticsFormat,
        body: (Writer) -> Int,
    ): DiagnosticsExport = try {
        val directory = File(context.filesDir, DIRECTORY_NAME).apply {
            if (!exists()) mkdirs()
        }
        prune(directory, kind)
        val file = File(directory, "gamecore-$kind-${Formatters.fileTimestamp(now())}.${format.extension}")
        val records = file.bufferedWriter().use { writer -> body(writer) }
        DiagnosticsExport.Written(
            fileName = file.name,
            sizeBytes = file.length(),
            recordCount = records,
            mimeType = format.mimeType,
            uri = shareUri(file),
        )
    } catch (error: Throwable) {
        // Storage full, a directory that could not be created, a provider that refused the URI.
        DiagnosticsExport.Failed(error.javaClass.simpleName)
    }

    /**
     * Keeps the last few exports of this kind and no more.
     *
     * By kind rather than by directory, so exporting a codec report does not quietly delete the motion
     * trace the user recorded five minutes earlier and has not moved anywhere yet.
     */
    private fun prune(directory: File, kind: String) {
        val prefix = "gamecore-$kind-"
        val existing = directory.listFiles()
            ?.filter { it.isFile && it.name.startsWith(prefix) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        existing.drop(KEEP_EXPORTS - 1).forEach { runCatching { it.delete() } }
    }

    private fun shareUri(file: File): Uri? = try {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    } catch (error: Throwable) {
        null
    }

    private fun now(): Long = System.currentTimeMillis()

    // ---------------------------------------------------------------- motion

    private fun motionJson(
        out: Writer,
        trace: MotionTrace,
        summary: MotionSummary,
        sensors: List<Pair<MotionSensorKind, Observed<SensorDescriptor>>>,
    ): Int {
        val json = JsonWriter(out)
        // Compact rather than indented: the sample array is essentially the whole file, and an indent on
        // thirty thousand rows of seven numbers is megabytes of whitespace.
        json.setIndent("")
        json.beginObject()
        json.header(KIND_MOTION)
        json.name("notice").value(MOTION_NOTICE)
        json.name("sensors").beginArray()
        sensors.forEach { (kind, descriptor) ->
            json.beginObject()
            json.name("kind").value(kind.label)
            json.name("unit").value(kind.unit)
            val found = descriptor.valueOrNull
            if (found == null) {
                json.name("available").value(false)
                json.name("unavailable").value(descriptor.unavailabilityText())
            } else {
                json.name("available").value(true)
                json.name("name").value(found.name)
                json.name("vendor").value(found.vendor)
                json.name("version").value(found.version.toLong())
                json.name("stringType").value(found.stringType)
                json.name("maximumRange").number(found.maximumRange)
                json.name("resolution").number(found.resolution)
                json.name("minDelayMicros").value(found.minDelayMicros.toLong())
                json.name("maxDelayMicros").value(found.maxDelayMicros.toLong())
                json.name("maximumHz").numberOrNull(found.maximumHz)
                json.name("powerMilliAmp").number(found.powerMilliAmp)
                json.name("wakeUp").value(found.isWakeUp)
                json.name("reportingMode").value(found.reportingMode)
            }
            json.endObject()
        }
        json.endArray()
        json.name("summary").beginObject()
        json.name("sampleCount").value(summary.samples)
        json.name("droppedSamples").value(summary.droppedSamples)
        json.name("durationMillis").value(summary.durationMillis)
        json.name("horizontalDegrees").number(summary.horizontalDegrees)
        json.name("verticalDegrees").number(summary.verticalDegrees)
        json.name("averageRotationDegreesPerSecond").number(summary.averageRotationDegreesPerSecond)
        json.name("peakRotationDegreesPerSecond").number(summary.peakRotationDegreesPerSecond)
        json.name("movementIntensity").number(summary.movementIntensity)
        json.name("peakMovementIntensity").number(summary.peakMovementIntensity)
        json.name("directionChanges").value(summary.directionChanges.toLong())
        json.name("stillFraction").number(summary.stillFraction)
        json.endObject()
        json.name("sampleColumns").beginArray()
        MOTION_COLUMNS.forEach { json.value(it) }
        json.endArray()
        json.name("samples").beginArray()
        trace.forEachSample { _, elapsedMillis, gx, gy, gz, ax, ay, az ->
            json.beginArray()
            json.value(rounded(elapsedMillis, 1_000.0))
            json.number(gx)
            json.number(gy)
            json.number(gz)
            json.number(ax)
            json.number(ay)
            json.number(az)
            json.endArray()
        }
        json.endArray()
        json.endObject()
        json.flush()
        return trace.size
    }

    private fun motionCsv(out: Writer, trace: MotionTrace): Int {
        out.write(MOTION_COLUMNS.joinToString(separator = ","))
        out.write("\n")
        // One builder reused for every row. Numbers need no CSV escaping — there is no separator, quote
        // or newline a formatted decimal can contain — so this writes them straight out.
        val row = StringBuilder(96)
        trace.forEachSample { _, elapsedMillis, gx, gy, gz, ax, ay, az ->
            row.setLength(0)
            row.append(fixed(elapsedMillis, 1_000L, 3)).append(',')
                .append(decimal(gx)).append(',')
                .append(decimal(gy)).append(',')
                .append(decimal(gz)).append(',')
                .append(decimal(ax)).append(',')
                .append(decimal(ay)).append(',')
                .append(decimal(az)).append('\n')
            out.write(row.toString())
        }
        return trace.size
    }

    private fun motionText(
        out: Writer,
        trace: MotionTrace,
        summary: MotionSummary,
        sensors: List<Pair<MotionSensorKind, Observed<SensorDescriptor>>>,
    ): Int {
        title(out, "MOTION SENSOR REPORT")
        paragraph(out, MOTION_NOTICE)
        section(out, "SENSORS")
        sensors.forEach { (kind, descriptor) ->
            val found = descriptor.valueOrNull
            if (found == null) {
                field(out, kind.label, descriptor.unavailabilityText().orEmpty())
            } else {
                field(out, kind.label, "${found.name} (${found.vendor})")
                field(out, "  Type", found.stringType)
                field(out, "  Maximum range", "${decimal(found.maximumRange)} ${kind.unit}")
                field(out, "  Resolution", "${decimal(found.resolution)} ${kind.unit}")
                field(
                    out,
                    "  Fastest rate",
                    found.maximumHz?.let { "${decimal(it)} Hz" } ?: "Not reported",
                )
                field(out, "  Reporting mode", found.reportingMode)
                field(out, "  Power", "${decimal(found.powerMilliAmp)} mA")
            }
        }
        section(out, "RECORDING")
        field(out, "Samples", summary.samples.toString())
        field(out, "Dropped samples", summary.droppedSamples.toString())
        field(out, "Duration", Formatters.duration(summary.durationMillis))
        field(out, "Rows in trace", trace.size.toString())
        section(out, "MOVEMENT")
        field(out, "Horizontal rotation", "${decimal(summary.horizontalDegrees)}°")
        field(out, "Vertical rotation", "${decimal(summary.verticalDegrees)}°")
        field(out, "Average rotation", "${decimal(summary.averageRotationDegreesPerSecond)}°/s")
        field(out, "Peak rotation", "${decimal(summary.peakRotationDegreesPerSecond)}°/s")
        field(out, "Movement intensity", "${decimal(summary.movementIntensity)} g")
        field(out, "Peak intensity", "${decimal(summary.peakMovementIntensity)} g")
        field(out, "Direction changes", summary.directionChanges.toString())
        field(out, "Held still", "${percent(summary.stillFraction)} of the recording")
        return trace.size
    }

    // ---------------------------------------------------------------- touch

    private fun touchJson(out: Writer, snapshot: TouchSnapshot): Int {
        val json = JsonWriter(out)
        json.setIndent("")
        json.beginObject()
        json.header(KIND_TOUCH)
        json.name("notice").value(TOUCH_NOTICE)
        json.name("coordinates").value("Fractions of the capture area: x across, y down, both 0..1.")
        val summary = snapshot.summary
        json.name("summary").beginObject()
        json.name("totalTouches").value(summary.totalTouches.toLong())
        json.name("totalSwipes").value(summary.totalSwipes.toLong())
        json.name("totalEvents").value(summary.totalEvents.toLong())
        json.name("droppedEvents").value(summary.droppedEvents.toLong())
        json.name("captureMillis").value(summary.captureMillis)
        json.name("averageTouchDurationMillis").value(summary.averageTouchDurationMillis)
        json.name("longestSwipe").number(summary.longestSwipe)
        json.name("longestSwipeDurationMillis").value(summary.longestSwipeDurationMillis)
        json.name("multiTouchStrokes").value(summary.multiTouchStrokes.toLong())
        json.name("maximumSimultaneousPointers").value(summary.maximumSimultaneousPointers.toLong())
        json.name("touchesPerSecond").numberOrNull(summary.touchDensityPerSecond)
        json.name("busiestCell").valueOrNull(summary.busiestCellLabel)
        json.name("busiestCellShare").numberOrNull(summary.busiestCellShare)
        json.endObject()
        json.name("heat").beginObject()
        json.name("columns").value(snapshot.heatColumns.toLong())
        json.name("rows").value(snapshot.heatRows.toLong())
        json.name("peak").value(snapshot.heatPeak.toLong())
        json.name("cells").beginArray()
        snapshot.heat.forEach { json.value(it.toLong()) }
        json.endArray()
        json.endObject()
        json.name("strokes").beginArray()
        snapshot.strokes.forEach { stroke ->
            json.beginObject()
            json.name("pointerId").value(stroke.pointerId)
            json.name("startMillis").value(stroke.startMillis)
            json.name("endMillis").value(stroke.endMillis)
            json.name("durationMillis").value(stroke.durationMillis)
            json.name("displacement").number(stroke.displacement)
            json.name("pathLength").number(stroke.pathLength)
            json.name("swipe").value(stroke.isSwipe)
            json.name("maximumPointers").value(stroke.maximumPointers.toLong())
            json.name("cancelled").value(stroke.wasCancelled)
            json.name("samples").value(stroke.points.size.toLong())
            json.endObject()
        }
        json.endArray()
        json.name("eventColumns").beginArray()
        TOUCH_COLUMNS.forEach { json.value(it) }
        json.endArray()
        json.name("events").beginArray()
        snapshot.points.forEach { point ->
            json.beginArray()
            json.value(point.sequence.toLong())
            json.value(point.timestampMillis)
            json.value(point.action.label)
            json.value(point.pointerId)
            json.number(point.x)
            json.number(point.y)
            json.numberOrNull(point.pressure)
            json.value(point.activePointers.toLong())
            json.endArray()
        }
        json.endArray()
        json.endObject()
        json.flush()
        return snapshot.points.size
    }

    private fun touchCsv(out: Writer, snapshot: TouchSnapshot): Int {
        out.write(TOUCH_COLUMNS.joinToString(separator = ","))
        out.write("\n")
        snapshot.points.forEach { point ->
            csvLine(
                out,
                point.sequence.toString(),
                point.timestampMillis.toString(),
                point.action.label,
                point.pointerId.toString(),
                decimal(point.x),
                decimal(point.y),
                point.pressure?.let { decimal(it) }.orEmpty(),
                point.activePointers.toString(),
            )
        }
        return snapshot.points.size
    }

    private fun touchText(out: Writer, snapshot: TouchSnapshot): Int {
        val summary = snapshot.summary
        title(out, "TOUCH CAPTURE REPORT")
        paragraph(out, TOUCH_NOTICE)
        section(out, "CAPTURE")
        field(out, "Capture time", Formatters.duration(summary.captureMillis))
        field(out, "Events recorded", summary.totalEvents.toString())
        field(out, "Events dropped", summary.droppedEvents.toString())
        section(out, "TOUCHES")
        field(out, "Touches", summary.totalTouches.toString())
        field(out, "Swipes", summary.totalSwipes.toString())
        field(out, "Average touch length", "${summary.averageTouchDurationMillis} ms")
        field(
            out,
            "Longest swipe",
            "${percent(summary.longestSwipe)} of the width over " +
                "${summary.longestSwipeDurationMillis} ms",
        )
        field(out, "Multi-touch strokes", summary.multiTouchStrokes.toString())
        field(out, "Most fingers at once", summary.maximumSimultaneousPointers.toString())
        field(
            out,
            "Touch density",
            summary.touchDensityPerSecond?.let { "${decimal(it)} per second" }
                ?: "Less than a second captured",
        )
        field(
            out,
            "Busiest area",
            summary.busiestCellLabel?.let { label ->
                val share = summary.busiestCellShare?.let { " (${percent(it)} of touches)" }.orEmpty()
                "$label$share"
            } ?: "Nothing recorded",
        )
        section(out, "HEAT GRID")
        field(out, "Grid", "${snapshot.heatColumns} × ${snapshot.heatRows}")
        field(out, "Busiest cell count", snapshot.heatPeak.toString())
        return snapshot.points.size
    }

    // ---------------------------------------------------------------- capability

    private fun capabilityJson(
        out: Writer,
        graphics: GraphicsReport,
        codecs: Observed<List<CodecEntry>>,
    ): Int {
        val json = JsonWriter(out)
        json.setIndent("  ")
        json.beginObject()
        json.header(KIND_CAPABILITY)
        json.name("notice").value(CAPABILITY_NOTICE)
        json.name("openGl").beginObject()
        json.observed("renderer", graphics.renderer)
        json.observed("vendor", graphics.vendor)
        json.observed("version", graphics.glVersion)
        json.observed("shadingLanguageVersion", graphics.shadingLanguageVersion)
        json.observed("contextClientVersion", graphics.contextClientVersion)
        json.observed("declaredVersion", graphics.declaredGlesVersion)
        json.observed("extensionPack", graphics.extensionPack)
        json.observedInt("compressedTextureFormats", graphics.compressedTextureFormatCount)
        json.observedList("textureCompressionFamilies", graphics.textureCompressionFamilies)
        json.observedList("extensions", graphics.extensions)
        json.name("limits").beginObject()
        val limits = graphics.limits.valueOrNull
        if (limits == null) {
            json.name("unavailable").value(graphics.limits.unavailabilityText())
        } else {
            limits.forEach { limit ->
                json.name(limit.label).beginObject()
                val reading = limit.value
                if (reading == null) {
                    json.name("unavailable").value("The driver did not report this limit.")
                } else {
                    json.name("value").value(reading.toLong())
                    json.name("unit").value(limit.unit)
                }
                json.endObject()
            }
        }
        json.endObject()
        json.endObject()
        json.name("egl").beginObject()
        json.observed("vendor", graphics.eglVendor)
        json.observed("version", graphics.eglVersion)
        json.observed("clientApis", graphics.eglClientApis)
        json.observedList("extensions", graphics.eglExtensions)
        json.endObject()
        json.name("vulkan").beginObject()
        json.name("supported").value(graphics.vulkan.supported)
        json.observed("apiVersion", graphics.vulkan.apiVersion)
        json.observedInt("packedVersion", graphics.vulkan.packedVersion)
        json.observed("hardwareLevel", graphics.vulkan.hardwareLevel)
        json.observed("computeLevel", graphics.vulkan.computeLevel)
        json.observed("deviceProperties", graphics.vulkan.deviceProperties)
        json.observedList("deviceExtensions", graphics.vulkan.deviceExtensions)
        json.endObject()
        json.name("display").beginObject()
        json.observedList("modes", graphics.display.modes)
        json.observedList("hdrTypes", graphics.display.hdrTypes)
        json.observedBoolean("wideColorGamut", graphics.display.wideColorGamut)
        json.endObject()
        json.observedBoolean("lowRamDevice", graphics.lowRamDevice)
        json.name("codecs").beginObject()
        val entries = codecs.valueOrNull
        if (entries == null) {
            json.name("unavailable").value(codecs.unavailabilityText())
        } else {
            json.name("count").value(entries.size.toLong())
            json.name("entries").beginArray()
            entries.forEach { codec -> json.codec(codec) }
            json.endArray()
        }
        json.endObject()
        json.endObject()
        json.flush()
        return codecs.valueOrNull?.size ?: 0
    }

    private fun JsonWriter.codec(codec: CodecEntry) {
        beginObject()
        name("name").value(codec.codecName)
        name("canonicalName").valueOrNull(codec.canonicalName)
        name("mimeType").value(codec.mimeType)
        name("family").value(codec.family)
        name("direction").value(codec.direction)
        // The platform's own answer, and "Not reported" where it did not give one. A codec is never
        // classified from the shape of its name here, however obvious "OMX.google." may look.
        name("classification").value(codec.classification.label)
        name("vendor").valueOrNull(codec.isVendor)
        name("alias").valueOrNull(codec.isAlias)
        name("maxInstances").valueOrNull(codec.maxInstances?.toLong())
        name("secureRequired").value(codec.secureRequired)
        name("colorFormats").value(codec.colorFormatCount.toLong())
        name("profileLevels").beginArray()
        codec.profileLevels.forEach { value(it) }
        endArray()
        name("hdrProfiles").beginArray()
        codec.hdrProfiles.forEach { value(it) }
        endArray()
        val video = codec.video
        if (video != null) {
            name("video").beginObject()
            name("maxWidth").valueOrNull(video.maxWidth?.toLong())
            name("maxHeight").valueOrNull(video.maxHeight?.toLong())
            name("heightAtMaxWidth").valueOrNull(video.heightAtMaxWidth?.toLong())
            name("widthAlignment").valueOrNull(video.widthAlignment?.toLong())
            name("heightAlignment").valueOrNull(video.heightAlignment?.toLong())
            name("minFrameRate").valueOrNull(video.minFrameRate?.toLong())
            name("maxFrameRate").valueOrNull(video.maxFrameRate?.toLong())
            name("minBitrate").valueOrNull(video.minBitrate?.toLong())
            name("maxBitrate").valueOrNull(video.maxBitrate?.toLong())
            name("performancePoints").beginArray()
            video.performancePoints.forEach { value(it) }
            endArray()
            endObject()
        }
        val audio = codec.audio
        if (audio != null) {
            name("audio").beginObject()
            name("maxChannels").valueOrNull(audio.maxChannels?.toLong())
            name("minBitrate").valueOrNull(audio.minBitrate?.toLong())
            name("maxBitrate").valueOrNull(audio.maxBitrate?.toLong())
            name("sampleRates").beginArray()
            audio.sampleRates.forEach { value(it.toLong()) }
            endArray()
            name("sampleRateRanges").beginArray()
            audio.sampleRateRanges.forEach { value(it) }
            endArray()
            endObject()
        }
        endObject()
    }

    private fun codecCsv(out: Writer, codecs: Observed<List<CodecEntry>>): Int {
        out.write(CODEC_COLUMNS.joinToString(separator = ","))
        out.write("\n")
        val entries = codecs.valueOrNull ?: return 0
        entries.forEach { codec ->
            csvLine(
                out,
                codec.codecName,
                codec.canonicalName.orEmpty(),
                codec.mimeType,
                codec.family,
                codec.direction,
                codec.classification.label,
                codec.isVendor?.let { yesNo(it) }.orEmpty(),
                codec.isAlias?.let { yesNo(it) }.orEmpty(),
                codec.maxInstances?.toString().orEmpty(),
                yesNo(codec.secureRequired),
                codec.video?.maxWidth?.toString().orEmpty(),
                codec.video?.maxHeight?.toString().orEmpty(),
                codec.video?.maxFrameRate?.toString().orEmpty(),
                codec.video?.maxBitrate?.toString().orEmpty(),
                codec.audio?.maxChannels?.toString().orEmpty(),
                codec.audio?.maxBitrate?.toString().orEmpty(),
                yesNo(codec.supportsHdr),
                codec.hdrProfiles.joinToString(separator = " | "),
                codec.profileLevels.joinToString(separator = " | "),
            )
        }
        return entries.size
    }

    private fun capabilityText(
        out: Writer,
        graphics: GraphicsReport,
        codecs: Observed<List<CodecEntry>>,
    ): Int {
        title(out, "GRAPHICS & CODEC CAPABILITY REPORT")
        paragraph(out, CAPABILITY_NOTICE)
        section(out, "OPENGL ES")
        field(out, "Renderer", read(graphics.renderer))
        field(out, "Vendor", read(graphics.vendor))
        field(out, "Version", read(graphics.glVersion))
        field(out, "Shading language", read(graphics.shadingLanguageVersion))
        field(out, "Context created at", read(graphics.contextClientVersion))
        field(out, "Declared by the system", read(graphics.declaredGlesVersion))
        field(out, "AEP", read(graphics.extensionPack))
        field(out, "Compressed formats", read(graphics.compressedTextureFormatCount) { it.toString() })
        list(out, "Texture compression", graphics.textureCompressionFamilies)
        val limits = graphics.limits.valueOrNull
        if (limits == null) {
            field(out, "Driver limits", graphics.limits.unavailabilityText().orEmpty())
        } else {
            out.write("  Driver limits\n")
            limits.forEach { limit ->
                field(
                    out,
                    "    ${limit.label}",
                    limit.value?.let { "$it ${limit.unit}".trim() } ?: "Not reported by the driver",
                )
            }
        }
        list(out, "Extensions", graphics.extensions)
        section(out, "EGL")
        field(out, "Vendor", read(graphics.eglVendor))
        field(out, "Version", read(graphics.eglVersion))
        field(out, "Client APIs", read(graphics.eglClientApis))
        list(out, "Extensions", graphics.eglExtensions)
        section(out, "VULKAN")
        field(out, "Supported", yesNo(graphics.vulkan.supported))
        field(out, "API version", read(graphics.vulkan.apiVersion))
        field(out, "Hardware level", read(graphics.vulkan.hardwareLevel))
        field(out, "Compute level", read(graphics.vulkan.computeLevel))
        field(out, "Device properties", read(graphics.vulkan.deviceProperties))
        list(out, "Device extensions", graphics.vulkan.deviceExtensions)
        section(out, "DISPLAY")
        list(out, "Modes", graphics.display.modes)
        list(out, "HDR types", graphics.display.hdrTypes)
        field(out, "Wide colour gamut", read(graphics.display.wideColorGamut) { yesNo(it) })
        field(out, "Low-RAM device", read(graphics.lowRamDevice) { yesNo(it) })
        val entries = codecs.valueOrNull
        if (entries == null) {
            section(out, "CODECS")
            paragraph(out, codecs.unavailabilityText().orEmpty())
            return 0
        }
        section(out, "CODECS (${entries.size})")
        entries.forEach { codec ->
            out.write("  ${codec.codecName}\n")
            field(out, "    Type", "${codec.mimeType} · ${codec.direction} · ${codec.classification.label}")
            codec.canonicalName?.takeIf { it != codec.codecName }?.let { field(out, "    Canonical", it) }
            codec.maxInstances?.let { field(out, "    Concurrent instances", it.toString()) }
            if (codec.secureRequired) field(out, "    Secure", "Secure playback only")
            codec.video?.let { video ->
                video.achievableResolution?.let { field(out, "    Maximum resolution", it) }
                val rates = frameRateRange(video.minFrameRate, video.maxFrameRate)
                if (rates != null) field(out, "    Frame rate", rates)
                val bits = bitrateRange(video.minBitrate, video.maxBitrate)
                if (bits != null) field(out, "    Bitrate", bits)
                if (video.performancePoints.isNotEmpty()) {
                    field(out, "    Performance points", video.performancePoints.joinToString(separator = ", "))
                }
            }
            codec.audio?.let { audio ->
                audio.maxChannels?.let { field(out, "    Channels", it.toString()) }
                if (audio.sampleRateRanges.isNotEmpty()) {
                    field(out, "    Sample rates", audio.sampleRateRanges.joinToString(separator = ", "))
                } else if (audio.sampleRates.isNotEmpty()) {
                    field(out, "    Sample rates", audio.sampleRates.joinToString(separator = ", ") { "$it Hz" })
                }
                val bits = bitrateRange(audio.minBitrate, audio.maxBitrate)
                if (bits != null) field(out, "    Bitrate", bits)
            }
            if (codec.hdrProfiles.isNotEmpty()) {
                field(out, "    HDR", codec.hdrProfiles.joinToString(separator = ", "))
            }
            if (codec.profileLevels.isNotEmpty()) {
                field(out, "    Profiles", codec.profileLevels.joinToString(separator = ", "))
            }
        }
        return entries.size
    }

    // ---------------------------------------------------------------- controllers

    private fun controllerJson(out: Writer, devices: List<ControllerDevice>): Int {
        val json = JsonWriter(out)
        json.setIndent("  ")
        json.beginObject()
        json.header(KIND_CONTROLLER)
        json.name("notice").value(CONTROLLER_NOTICE)
        json.name("devices").beginArray()
        devices.forEach { device ->
            json.beginObject()
            json.name("deviceId").value(device.deviceId.toLong())
            json.name("name").value(device.name)
            json.name("descriptor").value(device.descriptor)
            json.name("vendorId").value(device.vendorHex)
            json.name("productId").value(device.productHex)
            json.name("controllerNumber").value(device.controllerNumber.toLong())
            json.name("classification").value(device.classification)
            json.name("sources").beginArray()
            device.sources.forEach { json.value(it) }
            json.endArray()
            json.name("rawSources").value(device.rawSources.toLong())
            json.name("gamepad").value(device.isGamepad)
            json.name("joystick").value(device.isJoystick)
            json.name("dpad").value(device.isDpad)
            json.name("keyboard").value(device.isKeyboard)
            json.name("keyboardType").value(device.keyboardType)
            json.name("virtual").value(device.isVirtual)
            json.name("buttons").beginArray()
            device.buttons.forEach { json.value(ControllerReader.keyLabel(it)) }
            json.endArray()
            json.name("axes").beginArray()
            device.axes.forEach { axis ->
                json.beginObject()
                json.name("axis").value(axis.axis.toLong())
                json.name("label").value(axis.label)
                json.name("minimum").number(axis.minimum)
                json.name("maximum").number(axis.maximum)
                json.name("flat").number(axis.flat)
                json.name("fuzz").number(axis.fuzz)
                json.name("resolution").number(axis.resolution)
                json.endObject()
            }
            json.endArray()
            json.name("vibration").beginObject()
            json.name("available").value(device.vibration.available)
            json.name("vibrators").value(device.vibration.vibratorCount.toLong())
            json.name("detail").valueOrNull(device.vibration.detail)
            json.endObject()
            json.endObject()
        }
        json.endArray()
        json.endObject()
        json.flush()
        return devices.size
    }

    private fun controllerCsv(out: Writer, devices: List<ControllerDevice>): Int {
        out.write(CONTROLLER_COLUMNS.joinToString(separator = ","))
        out.write("\n")
        devices.forEach { device ->
            csvLine(
                out,
                device.deviceId.toString(),
                device.name,
                device.descriptor,
                device.vendorHex,
                device.productHex,
                device.controllerNumber.toString(),
                device.classification,
                device.sources.joinToString(separator = " | "),
                device.keyboardType,
                yesNo(device.isVirtual),
                device.axes.size.toString(),
                device.buttons.size.toString(),
                yesNo(device.vibration.available),
                device.vibration.vibratorCount.toString(),
                device.axes.joinToString(separator = " | ") { it.label },
                device.buttons.joinToString(separator = " | ") { ControllerReader.keyLabel(it) },
            )
        }
        return devices.size
    }

    private fun controllerText(out: Writer, devices: List<ControllerDevice>): Int {
        title(out, "CONTROLLER REPORT")
        paragraph(out, CONTROLLER_NOTICE)
        devices.forEach { device ->
            section(out, device.name.ifBlank { "Device ${device.deviceId}" }.uppercase())
            field(out, "Device id", device.deviceId.toString())
            field(out, "Descriptor", device.descriptor)
            field(out, "Vendor / product", "${device.vendorHex} / ${device.productHex}")
            field(out, "Controller number", device.controllerNumber.toString())
            field(out, "Classification", device.classification)
            field(out, "Sources", device.sources.joinToString(separator = ", ").ifBlank { "None reported" })
            field(out, "Keyboard type", device.keyboardType)
            field(out, "Virtual", yesNo(device.isVirtual))
            field(
                out,
                "Vibration",
                if (device.vibration.available) {
                    val count = device.vibration.vibratorCount
                    "${yesNo(true)} · $count " + if (count == 1) "vibrator" else "vibrators"
                } else {
                    "Not reported by this device"
                },
            )
            out.write("  Buttons (${device.buttons.size})\n")
            device.buttons.forEach { out.write("    ${ControllerReader.keyLabel(it)}\n") }
            out.write("  Axes (${device.axes.size})\n")
            device.axes.forEach { axis ->
                field(
                    out,
                    "    ${axis.label}",
                    "range ${axis.range} · flat ${decimal(axis.flat)} · fuzz ${decimal(axis.fuzz)}",
                )
            }
        }
        return devices.size
    }

    // ---------------------------------------------------------------- JSON helpers

    /** The same five fields at the top of every export, so a file can be identified without its name. */
    private fun JsonWriter.header(kind: String) {
        name("export").value("gamecore-$kind")
        name("schemaVersion").value(SCHEMA_VERSION.toLong())
        name("generatedAt").value(Formatters.iso8601(now()))
        name("appVersion").value(BuildConfig.VERSION_NAME)
        name("device").value("${Build.MANUFACTURER} ${Build.MODEL}")
        name("android").value("${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    }

    /**
     * An [Observed] as an object that says either what was read or why it could not be.
     *
     * One uniform shape for every reading in the file. A consumer that only wants values reads `.value`
     * and finds it absent; a consumer that wants to know *why* has the sentence GameCore itself shows on
     * screen. Writing a bare null instead would throw that away, and writing a zero would invent a
     * measurement.
     */
    private fun JsonWriter.observed(field: String, observed: Observed<String>) {
        name(field).beginObject()
        val reading = observed.valueOrNull
        if (reading == null) name("unavailable").value(observed.unavailabilityText()) else name("value").value(reading)
        endObject()
    }

    private fun JsonWriter.observedInt(field: String, observed: Observed<Int>) {
        name(field).beginObject()
        val reading = observed.valueOrNull
        if (reading == null) {
            name("unavailable").value(observed.unavailabilityText())
        } else {
            name("value").value(reading.toLong())
        }
        endObject()
    }

    private fun JsonWriter.observedBoolean(field: String, observed: Observed<Boolean>) {
        name(field).beginObject()
        val reading = observed.valueOrNull
        if (reading == null) name("unavailable").value(observed.unavailabilityText()) else name("value").value(reading)
        endObject()
    }

    private fun JsonWriter.observedList(field: String, observed: Observed<List<String>>) {
        name(field).beginObject()
        val reading = observed.valueOrNull
        if (reading == null) {
            name("unavailable").value(observed.unavailabilityText())
        } else {
            name("count").value(reading.size.toLong())
            name("value").beginArray()
            reading.forEach { value(it) }
            endArray()
        }
        endObject()
    }

    private fun JsonWriter.valueOrNull(text: String?) {
        if (text == null) nullValue() else value(text)
    }

    private fun JsonWriter.valueOrNull(flag: Boolean?) {
        if (flag == null) nullValue() else value(flag)
    }

    private fun JsonWriter.valueOrNull(number: Long?) {
        if (number == null) nullValue() else value(number)
    }

    /**
     * A float, rounded, or JSON null.
     *
     * `JsonWriter.value(double)` throws on NaN and infinity — which a sensor is entitled to report — and an
     * export that crashed on one reading would lose the other twenty-nine thousand.
     */
    private fun JsonWriter.number(reading: Float) {
        if (reading.isFinite()) value(rounded(reading.toDouble(), 10_000.0)) else nullValue()
    }

    private fun JsonWriter.numberOrNull(reading: Float?) {
        if (reading == null) nullValue() else number(reading)
    }

    // ---------------------------------------------------------------- text helpers

    private fun title(out: Writer, heading: String) {
        out.write("GAMECORE — $heading\n")
        out.write("=".repeat(heading.length + 11))
        out.write("\n")
        out.write("Generated   ${Formatters.iso8601(now())}\n")
        out.write("Device      ${Build.MANUFACTURER} ${Build.MODEL}\n")
        out.write("Android     ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        out.write("GameCore    ${BuildConfig.VERSION_NAME}\n")
    }

    private fun section(out: Writer, heading: String) {
        out.write("\n$heading\n")
    }

    private fun paragraph(out: Writer, text: String) {
        if (text.isBlank()) return
        out.write("\n$text\n")
    }

    private fun field(out: Writer, label: String, value: String) {
        out.write("  ")
        out.write(label.padEnd(LABEL_WIDTH))
        out.write(value)
        out.write("\n")
    }

    /** A list reading: its count, then one entry per line, or the explanation for why there is none. */
    private fun list(out: Writer, label: String, observed: Observed<List<String>>) {
        val reading = observed.valueOrNull
        if (reading == null) {
            field(out, label, observed.unavailabilityText().orEmpty())
            return
        }
        if (reading.isEmpty()) {
            field(out, label, "None reported")
            return
        }
        out.write("  $label (${reading.size})\n")
        reading.forEach { out.write("    $it\n") }
    }

    private fun read(observed: Observed<String>): String =
        observed.valueOrNull ?: observed.unavailabilityText().orEmpty()

    private fun <T> read(observed: Observed<T>, render: (T) -> String): String {
        val reading = observed.valueOrNull ?: return observed.unavailabilityText().orEmpty()
        return render(reading)
    }

    private fun frameRateRange(minimum: Int?, maximum: Int?): String? = when {
        minimum != null && maximum != null -> "$minimum–$maximum fps"
        maximum != null -> "up to $maximum fps"
        minimum != null -> "from $minimum fps"
        else -> null
    }

    private fun bitrateRange(minimum: Int?, maximum: Int?): String? {
        val low = minimum?.let { kilobits(it) }
        val high = maximum?.let { kilobits(it) }
        return when {
            low != null && high != null -> "$low–$high"
            high != null -> "up to $high"
            low != null -> "from $low"
            else -> null
        }
    }

    private fun kilobits(bitsPerSecond: Int): String = when {
        bitsPerSecond >= 1_000_000 -> "${bitsPerSecond / 1_000_000} Mbps"
        bitsPerSecond >= 1_000 -> "${bitsPerSecond / 1_000} kbps"
        else -> "$bitsPerSecond bps"
    }

    private fun yesNo(flag: Boolean): String = if (flag) "Yes" else "No"

    private fun percent(fraction: Float): String =
        if (fraction.isFinite()) "${Math.round(fraction * 100f)}%" else "—"

    // ---------------------------------------------------------------- CSV helpers

    private fun csvLine(out: Writer, vararg fields: String) {
        for (index in fields.indices) {
            if (index > 0) out.write(",")
            out.write(escape(fields[index]))
        }
        out.write("\n")
    }

    /**
     * Makes one field safe for a CSV reader *and* for a spreadsheet.
     *
     * Identical to [SessionExporter]'s, deliberately: quoting handles the separators a codec name or a
     * controller name can contain, and the leading-character guard stops a field beginning `=`, `+`, `-`
     * or `@` from being executed as a formula when the file is opened. A controller reports whatever
     * string its manufacturer put in its descriptor, and GameCore never audits it.
     */
    private fun escape(field: String): String {
        val guarded = if (field.isNotEmpty() && field.first() in FORMULA_LEADS) "'$field" else field
        val cleaned = guarded.replace('\r', ' ').replace('\n', ' ')
        return if (cleaned.any { it == ',' || it == '"' || it == ';' || it == '\t' }) {
            "\"" + cleaned.replace("\"", "\"\"") + "\""
        } else {
            cleaned
        }
    }

    // ---------------------------------------------------------------- numbers

    private fun decimal(reading: Float): String = fixed(reading.toDouble(), 10_000L, 4)

    /**
     * A fixed-point decimal built from integer arithmetic.
     *
     * Not `String.format`: that follows the default locale, and a device set to German writes `0,4327`,
     * which shifts every later column of a CSV and is not valid JSON either. Returns an empty string for
     * NaN and infinity, which in a CSV is the same blank every other absent reading uses.
     */
    private fun fixed(reading: Double, scale: Long, places: Int): String {
        if (reading.isNaN() || reading.isInfinite()) return ""
        val scaled = Math.round(reading * scale)
        val whole = Math.abs(scaled) / scale
        val fraction = Math.abs(scaled) % scale
        val digits = fraction.toString()
        val builder = StringBuilder(24)
        if (scaled < 0L) builder.append('-')
        builder.append(whole).append('.')
        repeat(places - digits.length) { builder.append('0') }
        builder.append(digits)
        return builder.toString()
    }

    /**
     * Rounds for JSON, in double arithmetic so that the shortest round-tripping form is short.
     *
     * A float widened straight to a double prints as `0.43269997835159302`; rounding in double space and
     * letting `Double.toString` pick the shortest representation prints `0.4327`, which is the same
     * number to the precision a sensor actually has.
     */
    private fun rounded(reading: Double, scale: Double): Double = Math.round(reading * scale) / scale

    private companion object {
        const val DIRECTORY_NAME = "exports/diagnostics"
        const val KEEP_EXPORTS = 5
        const val SCHEMA_VERSION = 1
        const val LABEL_WIDTH = 26

        const val KIND_MOTION = "motion"
        const val KIND_TOUCH = "touch"
        const val KIND_CAPABILITY = "capability"
        const val KIND_CONTROLLER = "controllers"

        /** The characters a spreadsheet reads as the start of a formula. */
        val FORMULA_LEADS = charArrayOf('=', '+', '-', '@')

        val MOTION_COLUMNS = listOf(
            "elapsed_ms",
            "gyro_x_rad_s",
            "gyro_y_rad_s",
            "gyro_z_rad_s",
            "accel_x_m_s2",
            "accel_y_m_s2",
            "accel_z_m_s2",
        )

        val TOUCH_COLUMNS = listOf(
            "sequence",
            "timestamp_ms",
            "action",
            "pointer_id",
            "x_fraction",
            "y_fraction",
            "pressure",
            "active_pointers",
        )

        val CODEC_COLUMNS = listOf(
            "codec",
            "canonical_name",
            "mime_type",
            "family",
            "direction",
            "classification",
            "vendor",
            "alias",
            "max_instances",
            "secure_required",
            "video_max_width",
            "video_max_height",
            "video_max_frame_rate",
            "video_max_bitrate_bps",
            "audio_max_channels",
            "audio_max_bitrate_bps",
            "hdr",
            "hdr_profiles",
            "profile_levels",
        )

        val CONTROLLER_COLUMNS = listOf(
            "device_id",
            "name",
            "descriptor",
            "vendor_id",
            "product_id",
            "controller_number",
            "classification",
            "sources",
            "keyboard_type",
            "virtual",
            "axis_count",
            "button_count",
            "vibration",
            "vibrator_count",
            "axes",
            "buttons",
        )

        const val MOTION_NOTICE =
            "These are gyroscope and accelerometer readings from this device's own sensors: how the " +
                "handset moved, in radians per second and metres per second squared. They are not a " +
                "measurement of aim, accuracy or skill in any game, and nothing here describes what was " +
                "happening on screen."

        const val TOUCH_NOTICE =
            "These are the touch events GameCore's own window received while the capture was running. " +
                "Android gives an app no access to touches in any other app, and GameCore records no " +
                "screen contents, no screenshots and no typed text. Coordinates are fractions of the " +
                "capture area rather than pixels, so this file does not carry the device's screen " +
                "geometry with it."

        const val CAPABILITY_NOTICE =
            "Every line here is what this device reported through the Android graphics and media codec " +
                "APIs. Where the platform exposes nothing, the reason is written in place of the value " +
                "and no figure is estimated, inferred from the hardware name, or filled in."

        const val CONTROLLER_NOTICE =
            "Every field here is what InputDevice reported: the ids the controller itself declared, the " +
                "axis ranges from its driver including the dead zone and error margin it claims, and the " +
                "buttons it answered hasKeys for. There is no latency figure, because Android timestamps " +
                "an input event when the framework receives it and gives no access to when the button " +
                "was physically pressed."
    }
}

/**
 * The file a diagnostics export produces.
 *
 * [extension] and [mimeType] travel together because the share sheet needs both and a `.json` announced
 * as `text/csv` opens in the wrong app.
 */
enum class DiagnosticsFormat(
    val label: String,
    val extension: String,
    val mimeType: String,
) {
    JSON("JSON", "json", "application/json"),
    CSV("CSV", "csv", "text/csv"),
    TEXT("Text", "txt", "text/plain"),
}

/**
 * What an export attempt did.
 *
 * The same shape as [ExportResult] and for the same reasons — [Written.uri] is nullable because a file
 * that exists but could not be granted a URI is still a file — with [Written.recordCount] in place of a
 * session count: samples for a trace, events for a capture, codecs for a scan, devices for a controller
 * report. A separate type rather than a widened one, so no existing export path changes behaviour.
 */
sealed interface DiagnosticsExport {
    data class Written(
        val fileName: String,
        val sizeBytes: Long,
        val recordCount: Int,
        val mimeType: String,
        val uri: Uri?,
    ) : DiagnosticsExport

    /** Nothing recorded yet. Not an error, and it is not written as one. */
    data object Empty : DiagnosticsExport

    /** [detail] is an exception class name — never a path or a message, which can carry user data. */
    data class Failed(val detail: String) : DiagnosticsExport
}
