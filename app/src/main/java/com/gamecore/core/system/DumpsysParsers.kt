package com.gamecore.core.system

import com.gamecore.core.common.DataSource
import com.gamecore.core.common.Observed
import com.gamecore.core.common.TextSanitizer
import com.gamecore.core.model.DisplayMode
import com.gamecore.core.model.FrameTiming
import com.gamecore.core.model.ThermalSensor
import com.gamecore.core.model.ThermalStatus

/**
 * Parsers for the four `dumpsys` sections GameCore reads through Shizuku.
 *
 * These formats are not API. They differ between Android versions and between vendors,
 * and a section that is present on one device is empty on the next. So the rule
 * throughout this file is the one ProcessLens established: **a parse that does not
 * match returns [Observed.Failed], never a guess.** A half-parsed thermal dump would
 * put a fabricated temperature in front of the user, which is worse than telling them
 * the dump could not be read.
 *
 * Nothing here is given the raw text to keep. Callers parse, extract the figure and
 * drop the string — a `dumpsys` dump contains installed package names and window
 * titles, and the security requirements forbid it reaching logcat or the UI.
 */
internal object DumpsysParsers {

    // -------------------------------------------------------------------- thermal

    /**
     * Named sensors from `dumpsys thermalservice`.
     *
     * The gain over `/sys/class/thermal` is the name: the HAL reports `mName=cpu-0-0`
     * where the sysfs zone would be `thermal_zone7`, so a graph can be labelled with
     * something the user recognises. The values are floats in degrees Celsius from the
     * HAL itself, not the magnitude-guessed integers sysfs gives.
     *
     * Both the cached block and the live "Current temperatures from HAL" block use the
     * same `Temperature{…}` token, and the live one comes second, so a later entry for
     * a name replaces an earlier one. `TemperatureThreshold{…}` entries do not match
     * this pattern and are ignored, which is intended — they are static limits, not
     * readings.
     */
    fun parseThermalSensors(text: String): Observed<List<ThermalSensor>> {
        val byName = LinkedHashMap<String, ThermalSensor>()
        for (match in TEMPERATURE.findAll(text)) {
            val celsius = match.groupValues[1].toFloatOrNull() ?: continue
            val type = match.groupValues[2].toIntOrNull()
            val name = match.groupValues[3].trim()
            if (name.isEmpty()) continue
            val deci = Math.round(celsius * 10f)
            // -40 °C to 150 °C. The HAL reports a real float, so this is a sanity
            // bound on a broken HAL rather than the unit heuristic sysfs needs.
            if (deci !in -400..1500) continue
            byName[name] = ThermalSensor(
                label = tidy(name),
                deciCelsius = deci,
                isCpuZone = type == HAL_TYPE_CPU || looksLikeCpu(name),
            )
        }
        return if (byName.isEmpty()) {
            Observed.Failed("The thermal service reported no sensor readings")
        } else {
            Observed.of(
                byName.values.sortedByDescending { it.deciCelsius },
                DataSource.DUMPSYS_SHIZUKU,
            )
        }
    }

    /** `Thermal Status: N`, the same value `PowerManager` returns. */
    fun parseThermalStatus(text: String): Observed<ThermalStatus> {
        val raw = THERMAL_STATUS.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return Observed.Failed("The thermal service did not report a status")
        return ThermalStatus.fromPlatform(raw)
            ?.let { Observed.of(it, DataSource.DUMPSYS_SHIZUKU) }
            ?: Observed.Failed("The thermal service reported an unrecognised status")
    }

    // -------------------------------------------------------------------- display

    /**
     * The active mode id and the mode table from `dumpsys display`.
     *
     * This is the read-back that makes a refresh-rate change reportable. `modeId N`
     * names the mode the display manager has actually adopted, and the
     * `supportedModes [{id=…, fps=…}]` list resolves it to a rate — so a device that
     * accepted a request and stayed where it was is caught here rather than reported
     * as a success.
     *
     * Only the first display device is parsed. GameCore pins the rate of the panel the
     * game is on, and a second display would have its own independent mode.
     */
    fun parseDisplay(text: String): Observed<DisplayDumpFacts> {
        val activeModeId = ACTIVE_MODE.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val modes = MODE_ENTRY.findAll(text).mapNotNull { match ->
            val id = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val width = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            val height = match.groupValues[3].toIntOrNull() ?: return@mapNotNull null
            val fps = match.groupValues[4].toFloatOrNull() ?: return@mapNotNull null
            if (fps < 1f || fps > 480f) return@mapNotNull null
            DisplayMode(id, width, height, fps)
        }.distinctBy { it.modeId }.toList()

        if (activeModeId == null && modes.isEmpty()) {
            return Observed.Failed("The display dump did not contain a mode table")
        }
        return Observed.of(
            DisplayDumpFacts(
                activeModeId = activeModeId,
                modes = modes,
                activeRefreshRate = modes.firstOrNull { it.modeId == activeModeId }?.refreshRate,
            ),
            DataSource.DUMPSYS_SHIZUKU,
        )
    }

    /** What the display dump could tell us. Any field may be absent on a given build. */
    data class DisplayDumpFacts(
        val activeModeId: Int?,
        val modes: List<DisplayMode>,
        val activeRefreshRate: Float?,
    )

    // ----------------------------------------------------------------- frame data

    /**
     * Real frame timestamps from `dumpsys gfxinfo <pkg> framestats`.
     *
     * The dump's columns are self-describing: each `---PROFILEDATA---` block opens with
     * a header naming them, and the names are read rather than the positions assumed,
     * because the column set has grown across Android versions (`GpuCompleted` arrived
     * in 9, `DequeueBufferDuration` in 7) and a fixed index would silently read the
     * wrong field on a version that inserted one.
     *
     * A zero `Flags` is required. The platform sets it non-zero for a frame whose
     * measurement it considers invalid — the first frame after a layout change, a frame
     * where the window was resized — and its own `gfxinfo` summary excludes those. So
     * do we; including them would report a stutter the user did not see.
     *
     * An empty block is the normal outcome for a game and is reported as a failure to
     * parse rather than as zero frames, because "this engine does not draw through
     * HWUI" is what it means, and the caller turns that into
     * [com.gamecore.core.model.FrameRateCapability.Unavailable].
     */
    fun parseFrameStats(text: String): Observed<List<FrameTiming>> {
        val frames = ArrayList<FrameTiming>(128)
        var flagsIndex = -1
        var intendedIndex = -1
        var completedIndex = -1
        var inBlock = false

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith(PROFILEDATA_MARKER)) {
                inBlock = !inBlock
                if (!inBlock) {
                    flagsIndex = -1
                    intendedIndex = -1
                    completedIndex = -1
                }
                continue
            }
            if (!inBlock || line.isEmpty()) continue

            if (line.startsWith("Flags")) {
                val columns = line.split(',').map { it.trim() }
                flagsIndex = columns.indexOf("Flags")
                intendedIndex = columns.indexOf("IntendedVsync")
                completedIndex = columns.indexOf("FrameCompleted")
                continue
            }
            if (flagsIndex < 0 || intendedIndex < 0 || completedIndex < 0) continue

            val fields = line.split(',')
            if (fields.size <= maxOf(flagsIndex, intendedIndex, completedIndex)) continue
            if (fields[flagsIndex].trim().toLongOrNull() != 0L) continue
            val intended = fields[intendedIndex].trim().toLongOrNull() ?: continue
            val completed = fields[completedIndex].trim().toLongOrNull() ?: continue
            val timing = FrameTiming(intended, completed)
            if (timing.isPlausible) frames += timing
        }

        return if (frames.isEmpty()) {
            Observed.Failed("No frame timing was reported for this package")
        } else {
            Observed.of(frames.sortedBy { it.intendedVsyncNanos }, DataSource.DUMPSYS_SHIZUKU)
        }
    }

    // ------------------------------------------------------------------ top app

    /**
     * The foreground package from `dumpsys activity activities`.
     *
     * The fallback for a user who has not granted usage access but does have Shizuku.
     * Three spellings are tried because the field was renamed twice:
     * `mResumedActivity` on older releases, `topResumedActivity` from Android 10, and a
     * bare `ResumedActivity` in some vendor dumps. The first that matches wins.
     *
     * The extracted token is put through [TextSanitizer.validatePackageName] rather than
     * trusted, because it is about to be compared against the user's saved profiles and
     * a malformed name would either match nothing or, worse, match by prefix.
     */
    fun parseForegroundPackage(text: String): Observed<String> {
        for (pattern in RESUMED_ACTIVITY_PATTERNS) {
            val candidate = pattern.find(text)?.groupValues?.getOrNull(1) ?: continue
            val packageName = candidate.substringBefore('/')
            val valid = TextSanitizer.validatePackageName(packageName)
            if (valid != null) return Observed.of(valid, DataSource.DUMPSYS_SHIZUKU)
        }
        return Observed.Failed("The activity dump did not name a resumed activity")
    }

    // --------------------------------------------------------- surfaceflinger probe

    /**
     * Whether `dumpsys SurfaceFlinger --latency` yields usable frame timing.
     *
     * A capability probe, not a measurement. The documented format is a clock-period
     * header line followed by rows of three nanosecond timestamps; several vendors ship
     * a SurfaceFlinger whose dump is empty, or emits rows of zeroes, and on those the
     * frame-rate reading stays unavailable rather than being derived from whatever the
     * dump did contain.
     *
     * Deliberately returns only a boolean. Even where the dump parses, it describes
     * whichever layer SurfaceFlinger chose to report, which is not reliably the game's —
     * so it is used to answer "does this device expose frame timing at all" and never as
     * the source of a number shown as the game's FPS.
     */
    fun hasUsableFrameLatency(text: String): Boolean {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.size < MIN_LATENCY_ROWS + 1) return false
        // The header is a single number: the display's refresh period in nanoseconds.
        val period = lines.first().toLongOrNull() ?: return false
        if (period !in 1_000_000L..100_000_000L) return false
        var usable = 0
        for (line in lines.drop(1)) {
            val parts = line.split(WHITESPACE)
            if (parts.size < 3) continue
            val timestamps = parts.take(3).mapNotNull { it.toLongOrNull() }
            if (timestamps.size < 3) continue
            // Zero and Long.MAX_VALUE are both SurfaceFlinger's "no data" markers.
            if (timestamps.any { it <= 0L || it == Long.MAX_VALUE }) continue
            usable++
        }
        return usable >= MIN_LATENCY_ROWS
    }

    // ---------------------------------------------------------------------- utils

    /** `cpu-0-0` → "Cpu 0 0". The HAL's own name, tidied, never replaced with a guess. */
    private fun tidy(name: String): String = name
        .replace('_', ' ')
        .replace('-', ' ')
        .split(' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { word ->
            if (word.length <= 1) word.uppercase() else word[0].uppercase() + word.substring(1)
        }
        .take(32)

    private fun looksLikeCpu(name: String): Boolean {
        val lower = name.lowercase()
        return CPU_HINTS.any { lower.contains(it) }
    }

    /** `Temperature{mValue=41.2, mType=0, mName=cpu-0-0, mStatus=0}` */
    private val TEMPERATURE = Regex(
        """Temperature\{mValue=(-?[\d.]+),\s*mType=(-?\d+),\s*mName=([^,}]+)""",
    )

    private val THERMAL_STATUS = Regex("""Thermal Status:\s*(\d+)""")

    /** `mActiveModeId=2`, or `activeModeId 2` depending on the release. */
    private val ACTIVE_MODE = Regex("""(?:mActiveModeId=|activeModeId[ =])(\d+)""")

    /** `{id=1, width=1080, height=2400, fps=120.0` — field order is stable across releases. */
    private val MODE_ENTRY = Regex(
        """\{id=(\d+),\s*width=(\d+),\s*height=(\d+),\s*fps=([\d.]+)""",
    )

    private val RESUMED_ACTIVITY_PATTERNS = listOf(
        Regex("""topResumedActivity[=:]\s*ActivityRecord\{[^}]*?\s([\w.]+/[\w.$]+)"""),
        Regex("""mResumedActivity[=:]\s*ActivityRecord\{[^}]*?\s([\w.]+/[\w.$]+)"""),
        Regex("""ResumedActivity[=:]\s*ActivityRecord\{[^}]*?\s([\w.]+/[\w.$]+)"""),
    )

    private val WHITESPACE = Regex("""\s+""")

    /** `Temperature.TYPE_CPU`. The only HAL type constant GameCore needs to recognise. */
    private const val HAL_TYPE_CPU = 0

    /** Below this many clean rows the dump is not a usable frame source. */
    private const val MIN_LATENCY_ROWS = 8

    /** Opens and closes each frame-timing block in a `gfxinfo framestats` dump. */
    private const val PROFILEDATA_MARKER = "---PROFILEDATA---"

    private val CPU_HINTS = listOf("cpu", "soc", "tsens", "apc", "big", "little", "mtktscpu")
}
