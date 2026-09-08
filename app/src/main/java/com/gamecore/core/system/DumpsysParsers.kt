package com.gamecore.core.system

import com.gamecore.core.common.DataSource
import com.gamecore.core.common.Observed
import com.gamecore.core.common.TextSanitizer
import com.gamecore.core.model.AppProcessState
import com.gamecore.core.model.DisplayMode
import com.gamecore.core.model.FrameTiming
import com.gamecore.core.model.ThermalSensor
import com.gamecore.core.model.ThermalStatus

/**
 * Parsers for the `dumpsys` sections GameCore reads through Shizuku.
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

    // ---------------------------------------------------------- running processes

    /**
     * Every package with a live process, and the platform's own word for what each one is doing.
     *
     * Read from `dumpsys activity processes` before any app is closed and read again afterwards,
     * which makes this one function both the enumeration and the verification for the launch-time
     * memory reclaim. Three things about the shape of what comes back are load-bearing.
     *
     * It is keyed by package rather than by process. A package with three processes is one app to the
     * user and one argument to `am kill`, and the state kept against it is the most protective of the
     * three: a music player whose foreground service sits in `com.player:playback` while its UI
     * process is cached must not be closed, and per-process states would have offered the cached half
     * of it as a candidate.
     *
     * A row whose adjustment label is not recognised becomes [AppProcessState.UNKNOWN] rather than
     * being dropped or assumed idle, and an app GameCore cannot describe is one it will not close.
     *
     * A dump that yields no rows at all is [Observed.Failed] and not an empty map. An empty map would
     * read as "nothing is running", which is both false and the reading under which every candidate
     * looks closable.
     */
    fun parseRunningProcesses(text: String): Observed<Map<String, AppProcessState>> {
        val byPackage = LinkedHashMap<String, AppProcessState>()
        for (line in text.lineSequence()) {
            val match = PROCESS_ROW.find(line) ?: continue
            // `com.player:playback` and `com.player` are one app to the user and one argument to a
            // close, so the process suffix goes before the name is validated as a package.
            val processName = match.groupValues[2].substringBefore(':')
            val packageName = TextSanitizer.validatePackageName(processName) ?: continue
            val state = stateFor(match.groupValues[3])
            val known = byPackage[packageName]
            byPackage[packageName] = if (known == null) state else moreProtective(known, state)
        }
        return if (byPackage.isEmpty()) {
            Observed.Failed("The activity dump did not list any running processes")
        } else {
            Observed.of(byPackage, DataSource.DUMPSYS_SHIZUKU)
        }
    }

    /**
     * The pid of one package's main process, from the same dump [parseRunningProcesses] reads.
     *
     * The one thing in this app that turns a package name into a handle on a running process, and it
     * exists because CPU affinity is the only feature that needs one. It shares [PROCESS_ROW] with the
     * parser above deliberately: a second regex over the same output could disagree with the first
     * about what is running, and "GameCore closed an app it says was not running" is the class of bug
     * that would be.
     *
     * *Main* process, exactly. A row qualifies only when its process name equals the package with no
     * `:suffix`, so `com.game:audio` and `com.game:downloader` are matched by nothing here and left
     * where the scheduler put them. That is a deliberate narrowing rather than an omission: the render
     * thread of an Android game lives in the main process, and pinning an audio or download service to
     * the performance cores would be spending them on work that does not need them while claiming to
     * have helped the frame rate. It is also what keeps the change single-valued, so one recorded mask
     * puts it back.
     *
     * Two failures, and neither is a zero:
     *
     *  * no matching row, which is the ordinary case for a game that has not finished starting. The
     *    caller retries or reports it; it does not guess a pid.
     *  * more than one distinct pid for that exact process name, which happens across users — a work
     *    profile or a second user running the same game prints `com.game/u0a234` and
     *    `com.game/u10a234`, and this row shape does not distinguish them. Refusing is the only safe
     *    answer available, because picking either one is a coin flip about whose process GameCore
     *    reaches into, and the wrong side of it is another user's game.
     */
    fun parseMainProcessPid(text: String, packageName: String): Observed<Int> {
        val valid = TextSanitizer.validatePackageName(packageName)
            ?: return Observed.Failed("\"$packageName\" is not a package name")
        val pids = LinkedHashSet<Int>()
        for (line in text.lineSequence()) {
            val match = PROCESS_ROW.find(line) ?: continue
            if (match.groupValues[2] != valid) continue
            val pid = match.groupValues[1].toIntOrNull() ?: continue
            if (pid > 0) pids += pid
        }
        return when (pids.size) {
            0 -> Observed.Failed("$valid has no running process in the activity dump")
            1 -> Observed.of(pids.first(), DataSource.DUMPSYS_SHIZUKU)
            else -> Observed.Failed(
                "$valid is running under more than one user, so which process is the game the " +
                    "user launched cannot be told from this dump",
            )
        }
    }

    /**
     * One `(adjType)` label as one of the six states.
     *
     * Matched by prefix and substring rather than by equality, because the labels compose: a process
     * holding a foreground service and an activity is `fg-service-act` on some releases and
     * `fg-service` on others, and a cached process that once started a service is
     * `cch-started-services`. The `cch` test comes first so that the last one reads as cached, which
     * is what it is — the service it started is gone.
     *
     * `fixed`, `system` and the other labels for a persistent platform process fall through to
     * [AppProcessState.UNKNOWN], which is not a claim that GameCore could not tell what they are. It
     * is that whether an app is part of the system is a package-manager question and not a
     * process-list one, so the filter asks it there and reports
     * [com.gamecore.core.model.ProtectionReason.SYSTEM_APP] rather than this.
     *
     * Anything else unrecognised is UNKNOWN for the reason UNKNOWN protects: the label set grows with
     * each release, and this file will always be behind the newest one.
     */
    private fun stateFor(label: String): AppProcessState = when {
        label.startsWith("cch") || label in IDLE_LABELS -> AppProcessState.CACHED
        label.contains("fg-service") -> AppProcessState.FOREGROUND_SERVICE
        label.contains("top-activity") || label in TOP_LABELS -> AppProcessState.TOP
        label == "home" -> AppProcessState.HOME
        label in PERCEPTIBLE_LABELS -> AppProcessState.PERCEPTIBLE
        label in BACKGROUND_LABELS || label.contains("service") ->
            AppProcessState.BACKGROUND_SERVICE
        else -> AppProcessState.UNKNOWN
    }

    /**
     * The state a package keeps when its processes disagree.
     *
     * Expressed in terms of [AppProcessState.protection] and the enum's own declaration order rather
     * than a second ranking kept in step with it. A state that protects beats one that does not, so
     * an unreadable process protects a package whose other process is merely cached; between two that
     * both protect, or two that both do not, the earlier declaration wins, which is the order that
     * enum documents itself as being in.
     */
    private fun moreProtective(a: AppProcessState, b: AppProcessState): AppProcessState = when {
        (a.protection != null) != (b.protection != null) -> if (a.protection != null) a else b
        a.ordinal <= b.ordinal -> a
        else -> b
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

    /**
     * The tail of one process row: `4021:com.example.app/u0a234 (cch-empty)`.
     *
     * Anchored on the only two tokens that have kept their shape across every release this app
     * supports — `pid:processName/uid`, which is `ProcessRecord.toShortString`, and the adjustment
     * label in brackets after it. Everything printed before them on the row has not kept its shape:
     * the columns for oom adjustment, scheduling group, process state and trim level have been
     * renamed, reordered and added to between Android 8 and 15, and a parser that counted them would
     * read the wrong field on the next release instead of failing where it could be seen.
     *
     * The lazy middle allows for the releases that print a column or two between the two anchors
     * while still preferring the common case where the bracket follows immediately.
     *
     * The pid is captured, and was not until the affinity presets needed it. It was always matched —
     * `pid:` is half of what anchors the row — so capturing it is the difference between discarding a
     * number this parser already found and running a second dump to find it again. Both parsers over
     * this regex therefore see the same rows and agree about what is running, which two regexes could
     * not be relied on to do. Group 1 is the pid, 2 the process name, 3 the adjustment label.
     */
    private val PROCESS_ROW = Regex(
        """(\d+):([A-Za-z][A-Za-z0-9_.]*(?::[A-Za-z0-9_.]+)?)/\S+""" +
            """(?:\s+[^\s()]+)*?\s+\(([a-z][a-z0-9-]{0,31})\)""",
    )

    /** Held in memory and doing nothing, whatever it was doing before. */
    private val IDLE_LABELS = setOf("previous", "previous-expired", "empty")

    /** On screen, or the thing the user is interacting with even if it is not drawing. */
    private val TOP_LABELS = setOf("top-sleeping", "bound-top", "instrumentation")

    /** Something the user can see or hear, or that something on screen is reading from. */
    private val PERCEPTIBLE_LABELS = setOf(
        "imp-fg", "force-fg", "force-imp", "vis-activity", "vis-provider", "pause-activity",
        "stop-activity", "perceptible", "provider", "recent-provider", "heavy", "backup",
    )

    /** Something wants it alive, but nothing the user is looking at depends on it. */
    private val BACKGROUND_LABELS = setOf("service", "service-b", "started-services", "imp-bg")

    /** `Temperature.TYPE_CPU`. The only HAL type constant GameCore needs to recognise. */
    private const val HAL_TYPE_CPU = 0

    /** Below this many clean rows the dump is not a usable frame source. */
    private const val MIN_LATENCY_ROWS = 8

    /** Opens and closes each frame-timing block in a `gfxinfo framestats` dump. */
    private const val PROFILEDATA_MARKER = "---PROFILEDATA---"

    private val CPU_HINTS = listOf("cpu", "soc", "tsens", "apc", "big", "little", "mtktscpu")
}
