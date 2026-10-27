package com.gamecore.core.backup

import com.gamecore.aimlab.engine.Difficulty
import com.gamecore.aimlab.engine.PersonalRecord
import com.gamecore.aimlab.engine.ReactionStats
import com.gamecore.aimlab.engine.RecordMetric
import com.gamecore.aimlab.engine.SessionSummary
import com.gamecore.aimlab.engine.TrainingMode
import com.gamecore.core.common.TextSanitizer
import com.gamecore.core.model.AppSettings
import com.gamecore.core.model.ColorCorrection
import com.gamecore.core.model.ColorPreset
import com.gamecore.core.model.CrosshairPreset
import com.gamecore.core.model.DockActionId
import com.gamecore.core.model.DockActions
import com.gamecore.core.model.DockConfig
import com.gamecore.core.model.FloatingButtonConfig
import com.gamecore.core.model.GameSession
import com.gamecore.core.model.HudDisplayMode
import com.gamecore.core.model.HudLayout
import com.gamecore.core.model.HudStat
import com.gamecore.core.model.HudWidget
import com.gamecore.core.model.LatencyLog
import com.gamecore.core.model.NetworkTransport
import com.gamecore.core.model.OverlayConfig
import com.gamecore.core.model.PillDisplayMode
import com.gamecore.core.model.QuickTriggerSettings
import com.gamecore.core.model.ResolutionScale
import com.gamecore.core.model.StopReason
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The §20 config backup's on-disk shape, and the one place it is written and read.
 *
 * A backup is *configuration and history*: appearance and settings, the overlay windows'
 * configs (the floating button and, since §3.7.1, the dock with the user's own action arrangement), the
 * quick-sheet pins and macros, the HUD layouts, and — handed in already built by the profile
 * layer — the game profiles and the presets they reference. The crosshair and colour presets a profile does
 * *not* reference (the standalone ones the user made but never assigned) travel in their own top-level
 * sections, so a preset is backed up whether or not a profile points at it, and each referenced one still
 * travels exactly once via the profile layer rather than being duplicated here (the orchestrator hands this
 * codec only the standalone remainder).
 *
 * Version 2 adds the measured history the version 1 envelope deliberately left out: every finished game
 * session row (including the thermal / power-saver / refresh-cap readings at session start and the latency
 * and colour summaries), the Aim Lab session summaries, and the personal records derived from them. The
 * high-frequency per-second sample rows still do not travel — they are a graph, not a setting, and keeping
 * them out is what bounds a history backup to a size a user can move between devices. Plugins and the APK
 * are not part of this envelope either; the restore screen says so in plain words.
 *
 * Version 3 adds one optional section: which device the file was written on, so the restore preview can
 * say where a backup came from and name the settings that may not mean the same thing on the device being
 * restored onto. It is optional and *additive in the reading sense* — a v1/v2 file simply has no `device`
 * key and reads as unknown origin, and a v3 file without one does too. History still does not carry the
 * per-second samples or the event timelines; those stay on the device that recorded them, and the restore
 * preview says so rather than implying a move that cannot happen.
 *
 * Version 1 files remain readable: the history sections simply default to empty, so an old file restores
 * every configuration section exactly as it always did and carries no history to merge. The version gate
 * accepts the whole supported range rather than only the newest value.
 *
 * This object is **pure** — `org.json` and the core models only, no Android — so the round trip and the
 * hostile-input rejection below are unit-testable on a JVM with a real `org.json` on the test classpath,
 * exactly as [com.gamecore.core.overlay.MacroCodec] and the Aim Lab `ConfigCodec` are. It holds no overlay
 * types: macros travel as the opaque array `MacroCodec` already produces, carried through untouched, so the
 * orchestrator decodes them through that codec at its boundary and this layer never learns what an action is.
 *
 * [decode] is the mirror image of the internal store codecs' totality. Those self-heal a *trusted* key to
 * fewer items; this reads a *foreign* file and so reports why it refused — wrong format, unknown version,
 * garbage — with a typed [BackupResult.Rejected] rather than a throw or a silent empty. Everything it does
 * accept is put through the model's own `normalised()`, its names through [TextSanitizer], and its enums
 * resolved by name with a fall back to the default, so a value that reaches the orchestrator is already safe.
 */
object BackupCodec {

    /** The envelope's format tag. A file whose `format` is not exactly this is not ours and is refused. */
    const val FORMAT = "gamecore.backup"

    /** The only envelope version this build writes. Version 1 files are still read (see [MIN_VERSION]). */
    const val VERSION = 3

    /** The oldest envelope this build still restores. Version 1 had configuration only. */
    const val MIN_VERSION = 1

    /** A ceiling on array walks, so a pathological hand-edited file cannot be scanned end to end. */
    private const val SCAN_LIMIT = 512

    /**
     * A separate ceiling for history arrays: a long-lived install can genuinely hold thousands of sessions,
     * and [SCAN_LIMIT] exists to bound tiny configuration lists, not a user's history.
     */
    private const val MAX_HISTORY_ENTRIES = 20_000

    // ------------------------------------------------------------------------------------ the result

    /** The outcome of [decode]: a validated payload, or the reason the file was refused. Never a throw. */
    sealed interface BackupResult {
        data class Restored(val data: BackupData) : BackupResult
        data class Rejected(val reason: RejectReason) : BackupResult
    }

    /** Why a file could not be read. Each maps to a plain-language line the restore screen shows. */
    enum class RejectReason {
        /** Nothing to read — an empty or blank file. */
        EMPTY,

        /** Not JSON at all, or a truncated document `org.json` could not parse. */
        MALFORMED,

        /** Valid JSON, but not a GameCore backup — its `format` tag is missing or something else. */
        WRONG_FORMAT,

        /** A GameCore backup written by a build this one does not understand. */
        UNSUPPORTED_VERSION,
    }

    /**
     * A decoded, validated backup, section by section.
     *
     * Every section is nullable and null means *absent from the file*, not *empty* — the distinction a
     * partial or hand-crafted backup turns on. The orchestrator leaves a device's current value untouched
     * where a section is null and applies it where it is present, so restoring a file that carries only
     * macros cannot blank out the user's settings. [layouts], [crosshairs] and [colours] are the *standalone*
     * ones (a referenced preset travels inside [profiles] instead); the orchestrator restores them by
     * create-or-match on content, never by blind insert. [macros] and [profiles] stay as raw `org.json` for
     * the two codecs that own those shapes — `MacroCodec` and the profile layer — to decode at their boundary.
     *
     * [sessions], [aimLabSessions] and [personalRecords] are history and default to empty rather than null:
     * every version 1 file is silent about them, and silence there means "this file has none to merge", not
     * "clear the device's history". They are always additive on restore; no policy deletes rows the file
     * does not mention.
     */
    data class BackupData(
        val settings: AppSettings?,
        val overlay: OverlayConfig?,
        val button: FloatingButtonConfig?,
        /**
         * The dock's own window state — placement, appearance, and the user's action arrangement (§3.7.1,
         * features 4–6). Null for every file written before this release, which is why the orchestrator
         * leaves the device's current dock alone rather than resetting it: a 3.7.0 backup is silent about
         * the dock, and silence is not an instruction to clear it.
         */
        val dock: DockConfig?,
        val colour: ColorCorrection?,
        val layouts: List<HudLayout>?,
        val crosshairs: List<CrosshairPreset>?,
        val colours: List<ColorPreset>?,
        val macros: JSONArray?,
        val profiles: JSONObject?,
        /** Finished game sessions from schema v2 onward. Empty for a version 1 file. */
        val sessions: List<GameSession> = emptyList(),
        /** Aim Lab training summaries from schema v2 onward. Empty for a version 1 file. */
        val aimLabSessions: List<SessionSummary> = emptyList(),
        /** Aim Lab personal records travelling with [aimLabSessions]. Never derived on restore. */
        val personalRecords: List<PersonalRecord> = emptyList(),
        /**
         * The device this file was written on, or null for every version before 3.
         *
         * Absent for v1 and v2 files, and for a v3 file hand-edited or truncated. Null is the honest
         * reading of "unknown origin", which the migration report and the restore screen both treat as
         * "cannot compare" rather than as "same device" — guessing would either invent a compatibility
         * verdict or hide one.
         */
        val device: DeviceSnapshot? = null,
        /** The envelope version the file declared, for the restore preview's copy. */
        val version: Int = VERSION,
    )

    // ------------------------------------------------------------------------------------------ encode

    /**
     * Builds the versioned backup document. Never throws.
     *
     * [layouts], [crosshairs] and [colours] are the *standalone* items only — the orchestrator computes them
     * with [standaloneLayouts] / [standaloneCrosshairs] / [standaloneColours] so anything a profile references
     * is left to travel once inside [profiles]. [macros] is the array `MacroCodec` produced (or an empty one),
     * carried opaque; [profiles] is the `{ "profiles": [...], "presets": {...} }` object the profile layer
     * built, whose two members are lifted to the envelope's top level. Passing empties simply writes empty
     * sections — a backup with no profiles or presets is still a valid backup of everything else.
     *
     * [sessions], [aimLabSessions] and [personalRecords] are always written, even when empty: version 2 is
     * the first envelope that can carry history, so writing the arrays makes "this file has no history" an
     * explicit statement rather than an absence a reader has to guess at.
     */
    fun encode(
        settings: AppSettings,
        overlay: OverlayConfig,
        button: FloatingButtonConfig,
        dock: DockConfig,
        colour: ColorCorrection,
        layouts: List<HudLayout>,
        crosshairs: List<CrosshairPreset>,
        colours: List<ColorPreset>,
        macros: JSONArray,
        profiles: JSONObject,
        sessions: List<GameSession> = emptyList(),
        aimLabSessions: List<SessionSummary> = emptyList(),
        personalRecords: List<PersonalRecord> = emptyList(),
        device: DeviceSnapshot? = null,
    ): String = JSONObject().apply {
        put("format", FORMAT)
        put("version", VERSION)
        put(
            "settings",
            JSONObject().apply {
                put("app", encodeSettings(settings))
                put("overlay", encodeOverlay(overlay))
                put("button", encodeButton(button))
                put("dock", encodeDock(dock))
                put("colour", encodeColour(colour))
            },
        )
        // The profile layer owns the shape of both of these; lift its two members to the top level so the
        // envelope reads flat rather than nesting a profiles object inside a profiles object.
        put("profiles", profiles.optJSONArray("profiles") ?: JSONArray())
        put("presets", profiles.optJSONObject("presets") ?: JSONObject())
        put("layouts", JSONArray().apply { layouts.forEach { put(encodeLayout(it)) } })
        put("crosshairs", JSONArray().apply { crosshairs.forEach { put(encodeCrosshair(it)) } })
        put("colours", JSONArray().apply { colours.forEach { put(encodeColourPreset(it)) } })
        put("macros", macros)
        // Version 2 history. Each array is written even when empty, so a reader can tell a fresh install
        // from a v1 file that predates these sections.
        put("sessions", JSONArray().apply { sessions.forEach { put(encodeSession(it)) } })
        // Optional on purpose. A build that does not know the section, and a file written by one, both
        // read it as absent — so adding it here cannot break a v1/v2 reader that simply looks for a key
        // it does not know about.
        device?.let { put("device", encodeDevice(it)) }
        put("aimLabSessions", JSONArray().apply { aimLabSessions.forEach { put(encodeAimLabSession(it)) } })
        put("personalRecords", JSONArray().apply { personalRecords.forEach { put(encodePersonalRecord(it)) } })
    }.toString()

    // ------------------------------------------------------- standalone selection & content signatures

    /**
     * The layouts a backup carries at its top level: every one whose id a profile does *not* reference, so a
     * referenced layout travels once (embedded with its profile) instead of twice. Pure — the orchestrator
     * computes [referencedIds] from the profiles it is exporting and hands the remainder here.
     */
    fun standaloneLayouts(layouts: List<HudLayout>, referencedIds: Set<Long>): List<HudLayout> =
        layouts.filterNot { it.id in referencedIds }

    /** The crosshair presets no profile references — the standalone ones. See [standaloneLayouts]. */
    fun standaloneCrosshairs(presets: List<CrosshairPreset>, referencedIds: Set<Long>): List<CrosshairPreset> =
        presets.filterNot { it.id in referencedIds }

    /** The colour presets no profile references — the standalone ones. See [standaloneLayouts]. */
    fun standaloneColours(presets: List<ColorPreset>, referencedIds: Set<Long>): List<ColorPreset> =
        presets.filterNot { it.id in referencedIds }

    /**
     * A HUD layout's content as a string, ignoring the layout id, its timestamps and each widget's client id,
     * and order-independent over the widgets — two layouts draw the same HUD when this matches. Mirrors the
     * profile layer's own layout signature so a layout matches the same device row however it arrived, and is
     * stable across a save (which mints a new id and fresh timestamps) so a second merge of the same backup
     * matches what the first one wrote and adds nothing.
     */
    fun hudSignature(layout: HudLayout): String {
        val widgets = layout.widgets.map { it.normalised() }.map { w ->
            "${w.stat.name}:${w.xFraction}:${w.yFraction}:${w.textSizeSp}:" +
                "${w.opacityPercent}:${w.showLabel}:${w.showBackground}:${w.colorArgb}"
        }.sorted()
        return layout.name.trim() + " " + widgets.joinToString("")
    }

    /**
     * A crosshair's content, ignoring its local id and its device-local image path (the bytes do not travel),
     * so an imported geometry matches an existing preset by everything that crossed. Data-class equality is
     * the match, exactly as the profile layer keys its own crosshair map.
     */
    fun crosshairSignature(preset: CrosshairPreset): CrosshairPreset =
        preset.normalised().copy(id = 0L, imagePath = null)

    /** A colour preset's content, ignoring its local id. */
    fun colourSignature(preset: ColorPreset): ColorPreset = preset.normalised().copy(id = 0L)

    private fun encodeSettings(s: AppSettings): JSONObject = JSONObject().apply {
        put("theme", s.theme.name)
        put("accent", s.accent.name)
        put("uiScalePercent", s.uiScalePercent)
        put("useDynamicColour", s.useDynamicColour)
        put("useCustomAccent", s.useCustomAccent)
        if (s.customAccentArgb != null) put("customAccentArgb", s.customAccentArgb)
        put("compactDensity", s.compactDensity)
        put("showBuiltInHomeStats", s.showBuiltInHomeStats)
        put("animations", s.animations.name)
        put("hapticsEnabled", s.hapticsEnabled)
        put("allowElevatedReads", s.allowElevatedReads)
        put("autoApplyProfiles", s.autoApplyProfiles)
        put("showResolutionOverrideNotice", s.showResolutionOverrideNotice)
        put("showConfigEditNotice", s.showConfigEditNotice)
        put("showInstantReplayNotice", s.showInstantReplayNotice)
        put("trackSessions", s.trackSessions)
        put("detectionIntervalMillis", s.detectionIntervalMillis)
        put("sampleIntervalMillis", s.sampleIntervalMillis)
        put("measureLatency", s.measureLatency)
        put("latencyHost", s.latencyHost)
        put("keepScreenOnInGame", s.keepScreenOnInGame)
        put("showThermalWarnings", s.showThermalWarnings)
        put("backgroundMonitoring", s.backgroundMonitoring)
        put("confirmBeforeDiscard", s.confirmBeforeDiscard)
        put("recordingQuality", s.recordingQuality.name)
        put("hasSeenIntroduction", s.hasSeenIntroduction)
        put("setupCompletedVersion", s.setupCompletedVersion)
        put("setupDismissed", s.setupDismissed)
        put("neverKillPackages", JSONArray(s.neverKillPackages))
        put("quickTrigger", encodeQuickTrigger(s.quickTrigger))
        put("aimLabEnabled", s.aimLabEnabled)
        put("aimLabOrientation", s.aimLabOrientation.name)
        put("aimLabHorizontalFovDegrees", s.aimLabHorizontalFovDegrees)
        put("advancedHudEnabled", s.advancedHudEnabled)
        put("networkStabilityEnabled", s.networkStabilityEnabled)
        put("customModulesEnabled", s.customModulesEnabled)
        put("screenExtractionEnabled", s.screenExtractionEnabled)
        put("touchSamplingEnabled", s.touchSamplingEnabled)
        put("volumePointTriggerEnabled", s.volumePointTriggerEnabled)
        put("dockCustomizationEnabled", s.dockCustomizationEnabled)
        put("quickActionsEnabled", s.quickActionsEnabled)
        put("lastSeenWhatsNewVersionCode", s.lastSeenWhatsNewVersionCode)
    }

    private fun encodeQuickTrigger(q: QuickTriggerSettings): JSONObject = JSONObject().apply {
        put("enabled", q.enabled)
        put("method", q.method.name)
        put("action", q.action.name)
        put("windowMillis", q.windowMillis)
        put("shakeSensitivity", q.shakeSensitivity)
        put("passThroughKeys", q.passThroughKeys)
    }

    private fun encodeOverlay(c: OverlayConfig): JSONObject = JSONObject().apply {
        put("showPill", c.showPill)
        put("pillX", c.pillX)
        put("pillY", c.pillY)
        put("stats", JSONArray().apply { c.stats.forEach { put(it.name) } })
        put("updateIntervalMillis", c.updateIntervalMillis)
        put("textSizeSp", c.textSizeSp)
        put("opacityPercent", c.opacityPercent)
        put("cornerRadiusDp", c.cornerRadiusDp)
        put("isVertical", c.isVertical)
        put("showLabels", c.showLabels)
        put("displayMode", c.displayMode.name)
        put("hudDisplayMode", c.hudDisplayMode.name)
        put("quickPins", JSONArray(c.quickPins))
        put("quickAutoClose", c.quickAutoClose)
        // macrosJson is intentionally omitted: the macros travel in the envelope's top-level "macros" array,
        // so they are neither duplicated here nor lost.
    }

    private fun encodeButton(b: FloatingButtonConfig): JSONObject = JSONObject().apply {
        put("show", b.show)
        put("x", b.x)
        put("y", b.y)
        put("sizeDp", b.sizeDp)
        put("opacityPercent", b.opacityPercent)
        put("snapToEdge", b.snapToEdge)
        put("idleOpacityPercent", b.idleOpacityPercent)
        put("hapticFeedback", b.hapticFeedback)
        put("tapOpensPanel", b.tapOpensPanel)
        put("doubleTapForPanel", b.doubleTapForPanel)
        put("panelWidthDp", b.panelWidthDp)
        put("panelLayout", b.panelLayout.name)
        put("showQuickApps", b.showQuickApps)
        put("quickAppPackages", JSONArray(b.quickAppPackages))
        // Only a placed orientation carries a fraction pair; an absent one stays absent rather than 0,0.
        b.portraitXFraction?.let { put("portraitXFraction", it.toDouble()) }
        b.portraitYFraction?.let { put("portraitYFraction", it.toDouble()) }
        b.landscapeXFraction?.let { put("landscapeXFraction", it.toDouble()) }
        b.landscapeYFraction?.let { put("landscapeYFraction", it.toDouble()) }
    }

    /**
     * The dock, including the user's arrangement of its actions.
     *
     * The order and the hidden set travel as arrays of enum *names* — the same identity-by-name contract the
     * preference store uses, so a build that has since renamed or removed an action drops the stale id on the
     * way in instead of resolving it to whatever now sits at that ordinal. `hiddenActions` is written even
     * when empty, because an empty array is a real state ("the user switched everything on") that has to be
     * distinguishable from the key being absent, which means "this file predates dock customisation".
     */
    private fun encodeDock(dk: DockConfig): JSONObject = JSONObject().apply {
        put("show", dk.show)
        put("actionOrder", JSONArray(dk.actionOrder.map { it.name }))
        put("hiddenActions", JSONArray(dk.hiddenActions.sortedBy { it.ordinal }.map { it.name }))
        put("sizeDp", dk.sizeDp)
        put("opacity", dk.opacity.toDouble())
        put("snapToEdge", dk.snapToEdge)
        put("hapticFeedback", dk.hapticFeedback)
        put("panelWidthDp", dk.panelWidthDp)
        put("x", dk.x)
        put("y", dk.y)
        // As with the button: only an orientation the dock has actually been placed in carries a pair.
        dk.portraitXFraction?.let { put("portraitXFraction", it.toDouble()) }
        dk.portraitYFraction?.let { put("portraitYFraction", it.toDouble()) }
        dk.landscapeXFraction?.let { put("landscapeXFraction", it.toDouble()) }
        dk.landscapeYFraction?.let { put("landscapeYFraction", it.toDouble()) }
    }

    private fun encodeColour(c: ColorCorrection): JSONObject = JSONObject().apply {
        put("redGain", c.redGain)
        put("greenGain", c.greenGain)
        put("blueGain", c.blueGain)
        put("gammaMode", c.gammaMode.name)
        put("gamma", c.gamma)
        put("redGamma", c.redGamma)
        put("greenGamma", c.greenGamma)
        put("blueGamma", c.blueGamma)
        put("saturation", c.saturation)
        put("contrast", c.contrast)
        put("hueDegrees", c.hueDegrees)
        put("brightnessOffset", c.brightnessOffset)
        put("visionFilter", c.visionFilter.name)
        put("invertColors", c.invertColors)
    }

    private fun encodeLayout(l: HudLayout): JSONObject = JSONObject().apply {
        put("id", l.id)
        put("name", l.name)
        put("createdAtMillis", l.createdAtMillis)
        put("updatedAtMillis", l.updatedAtMillis)
        put("widgets", JSONArray().apply { l.widgets.forEach { put(encodeWidget(it)) } })
    }

    private fun encodeWidget(w: HudWidget): JSONObject = JSONObject().apply {
        put("id", w.id)
        put("stat", w.stat.name)
        put("xFraction", w.xFraction.toDouble())
        put("yFraction", w.yFraction.toDouble())
        put("textSizeSp", w.textSizeSp)
        put("opacityPercent", w.opacityPercent)
        put("showLabel", w.showLabel)
        put("showBackground", w.showBackground)
        put("colorArgb", w.colorArgb)
    }

    private fun encodeCrosshair(p: CrosshairPreset): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("design", p.design.name)
        put("sizeDp", p.sizeDp)
        put("thicknessDp", p.thicknessDp)
        put("centreGapDp", p.centreGapDp)
        put("opacityPercent", p.opacityPercent)
        put("rotationDegrees", p.rotationDegrees)
        put("colorArgb", p.colorArgb)
        put("showDot", p.showDot)
        put("showOutline", p.showOutline)
        put("xFraction", p.xFraction.toDouble())
        put("yFraction", p.yFraction.toDouble())
        // imagePath is intentionally omitted: the picture's bytes do not travel (exactly as the profile
        // layer's crosshair embedding leaves them out), so a device-local path would mean nothing here.
    }

    private fun encodeColourPreset(p: ColorPreset): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("correction", encodeColour(p.correction))
    }

    // ---------------------------------------------------------------- version 2 history (encode)

    /**
     * One finished game session, every field of the model.
     *
     * Nulls are omitted rather than written as JSON `null`: absence is how this codec represents "the device
     * did not expose that reading", and it is the same convention the session mapper uses. `thermalStatus`,
     * `powerSaver` and `refreshCapped` are carried as their own keys so a restored session says what the
     * device was doing at session start rather than silently losing the reading.
     */
    private fun encodeSession(s: GameSession): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("packageName", s.packageName)
        put("gameLabel", s.gameLabel)
        put("startedAtMillis", s.startedAtMillis)
        s.endedAtMillis?.let { put("endedAtMillis", it) }
        put("batteryStartPercent", s.batteryStartPercent)
        s.batteryEndPercent?.let { put("batteryEndPercent", it) }
        put("wasCharging", s.wasCharging)
        s.averageCpuPercent?.let { put("averageCpuPercent", it.toDouble()) }
        s.peakCpuPercent?.let { put("peakCpuPercent", it.toDouble()) }
        s.averageMemoryPercent?.let { put("averageMemoryPercent", it.toDouble()) }
        s.peakMemoryPercent?.let { put("peakMemoryPercent", it.toDouble()) }
        s.averageTemperatureDeciCelsius?.let { put("averageTemperatureDeciCelsius", it) }
        s.peakTemperatureDeciCelsius?.let { put("peakTemperatureDeciCelsius", it) }
        s.averageRefreshRate?.let { put("averageRefreshRate", it.toDouble()) }
        s.averageFrameRate?.let { put("averageFrameRate", it.toDouble()) }
        s.averageLatencyMillis?.let { put("averageLatencyMillis", it) }
        put("profileApplied", s.profileApplied)
        put("sampleCount", s.sampleCount)
        s.stopReason?.let { put("stopReason", it.name) }
        s.colorPresetName?.let { put("colorPresetName", it) }
        s.colorCorrection?.let { put("colorCorrection", encodeColour(it)) }
        s.latencyLog?.let { put("latencyLog", encodeLatencyLog(it)) }
        s.thermalStatus?.let { put("thermalStatus", it) }
        s.powerSaver?.let { put("powerSaver", it) }
        s.refreshCapped?.let { put("refreshCapped", it) }
        s.downshiftCount?.let { put("downshiftCount", it) }
        s.lowestRateHz?.let { put("lowestRateHz", it.toDouble()) }
        s.transport?.let { put("transport", it.name) }
        s.fullPerformanceOverridden?.let { put("fullPerformanceOverridden", it) }
        s.systemReenabledSaver?.let { put("systemReenabledSaver", it) }
        s.resolutionApplied?.let { put("resolutionApplied", it.name) }
        s.instantReplayUsed?.let { put("instantReplayUsed", it) }
        s.clipsSaved?.let { put("clipsSaved", it) }
    }

    /**
     * The device snapshot, as the version-3 envelope carries it.
     *
     * [DeviceSnapshot.manufacturer] and [DeviceSnapshot.model] are written even when blank is all there
     * was, because a section with no model at all is indistinguishable from "no section" on the way
     * back — and the restore screen needs to tell those apart. Everything else is optional, so a
     * device whose panel could not be read writes a snapshot without panel numbers rather than a
     * `0 × 0` that would fail every comparison in [DeviceMigration].
     */
    private fun encodeDevice(d: DeviceSnapshot): JSONObject = JSONObject().apply {
        put("manufacturer", TextSanitizer.sanitizeName(d.manufacturer, MAX_DEVICE_FIELD_LENGTH))
        put("model", TextSanitizer.sanitizeName(d.model, MAX_DEVICE_FIELD_LENGTH))
        d.sdkInt?.let { put("sdkInt", it) }
        d.panelWidthPixels?.let { put("panelWidth", it) }
        d.panelHeightPixels?.let { put("panelHeight", it) }
        d.peakRefreshRateHz?.let { put("peakRefreshHz", it.toDouble()) }
    }

    /**
     * Reads the device snapshot, or null when the section is absent or carries nothing usable.
     *
     * Null on the empty fields rather than a `DeviceSnapshot("", "")` an empty object would otherwise
     * build: a snapshot with no manufacturer and no model has nothing to compare and reads as absent.
     * Non-negative and finite for the numbers, because a negative panel size is not a size and NaN is
     * not a rate.
     */
    private fun readDevice(o: JSONObject): DeviceSnapshot? {
        val manufacturer =
            TextSanitizer.sanitizeName(o.optString("manufacturer"), MAX_DEVICE_FIELD_LENGTH)
        val model = TextSanitizer.sanitizeName(o.optString("model"), MAX_DEVICE_FIELD_LENGTH)
        if (manufacturer.isBlank() && model.isBlank()) return null
        return DeviceSnapshot(
            manufacturer = manufacturer,
            model = model,
            sdkInt = readIntOrNull(o, "sdkInt"),
            panelWidthPixels = readIntOrNull(o, "panelWidth")?.coerceAtLeast(0),
            panelHeightPixels = readIntOrNull(o, "panelHeight")?.coerceAtLeast(0),
            peakRefreshRateHz = readFloatOrNull(o, "peakRefreshHz")?.coerceAtLeast(0f),
        )
    }

    private fun encodeLatencyLog(l: LatencyLog): JSONObject = JSONObject().apply {
        put("completedProbes", l.completedProbes)
        put("failedProbes", l.failedProbes)
        put("spikes", l.spikes)
        l.worstMillis?.let { put("worstMillis", it) }
        l.jitterMillis?.let { put("jitterMillis", it) }
        put("longestFailureRun", l.longestFailureRun)
    }

    /** One Aim Lab summary; flat, matching the row's fields so nothing is lost in a nested shape. */
    private fun encodeAimLabSession(s: SessionSummary): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("mode", s.mode.name)
        put("difficulty", s.difficulty.name)
        put("startedAtMillis", s.startedAtMillis)
        put("endedAtMillis", s.endedAtMillis)
        s.weaponName?.let { put("weaponName", it) }
        s.sensitivityName?.let { put("sensitivityName", it) }
        put("score", s.score)
        put("hits", s.hits)
        put("shots", s.shots)
        put("targetsMissed", s.targetsMissed)
        put("reactionAttempts", s.reactionStats.attempts)
        put("reactionFastest", s.reactionStats.fastestMillis)
        put("reactionSlowest", s.reactionStats.slowestMillis)
        put("reactionAverage", s.reactionStats.averageMillis.toDouble())
        put("reactionMedian", s.reactionStats.medianMillis.toDouble())
        put("averageAcquireMillis", s.averageAcquireMillis.toDouble())
        put("trackingErrorAverage", s.trackingErrorAverage.toDouble())
        put("timeOnTargetFraction", s.timeOnTargetFraction.toDouble())
        put("recoilCompensation", s.recoilCompensation.toDouble())
        put("gyroStability", s.gyroStability.toDouble())
        put("scoringVersion", s.scoringVersion)
    }

    /** One personal record. Records are restorable evidence, so every field travels, including what it beat. */
    private fun encodePersonalRecord(r: PersonalRecord): JSONObject = JSONObject().apply {
        put("mode", r.mode.name)
        put("difficulty", r.difficulty.name)
        r.weaponName?.let { put("weaponName", it) }
        put("metric", r.metric.name)
        put("value", r.value.toDouble())
        r.previousValue?.let { put("previousValue", it.toDouble()) }
        put("achievedAtMillis", r.achievedAtMillis)
        put("scoringVersion", r.scoringVersion)
    }

    // ------------------------------------------------------------------------------------------ decode

    /**
     * Reads a backup document, reporting why rather than throwing when it cannot.
     *
     * The order of the guards is the order a caller wants the reasons in: an empty file, then a document
     * that is not JSON, then JSON that is not ours, then ours-but-from-a-future-build. Only past those does
     * it read the sections, and even there any surprise degrades to [RejectReason.MALFORMED] rather than
     * escaping — a hostile file cannot make this method throw.
     */
    fun decode(json: String?): BackupResult {
        if (json.isNullOrBlank()) return BackupResult.Rejected(RejectReason.EMPTY)
        val root = try {
            JSONObject(json)
        } catch (_: JSONException) {
            return BackupResult.Rejected(RejectReason.MALFORMED)
        }
        if (root.optString("format") != FORMAT) return BackupResult.Rejected(RejectReason.WRONG_FORMAT)
        val version = root.optInt("version", 0)
        if (version < MIN_VERSION || version > VERSION) {
            return BackupResult.Rejected(RejectReason.UNSUPPORTED_VERSION)
        }
        return try {
            BackupResult.Restored(readBackup(root, version))
        } catch (_: Throwable) {
            BackupResult.Rejected(RejectReason.MALFORMED)
        }
    }

    private fun readBackup(root: JSONObject, version: Int): BackupData {
        val settings = root.optJSONObject("settings")
        return BackupData(
            settings = settings?.optJSONObject("app")?.let(::readSettings),
            overlay = settings?.optJSONObject("overlay")?.let(::readOverlay),
            button = settings?.optJSONObject("button")?.let(::readButton),
            dock = settings?.optJSONObject("dock")?.let(::readDock),
            colour = settings?.optJSONObject("colour")?.let(::readColour),
            layouts = root.optJSONArray("layouts")?.let(::readLayouts),
            crosshairs = root.optJSONArray("crosshairs")?.let(::readCrosshairs),
            colours = root.optJSONArray("colours")?.let(::readColourPresets),
            macros = root.optJSONArray("macros"),
            profiles = readProfiles(root),
            // Absent means empty, never null: v1 is silent about history, and silence is not a command to
            // clear the device's own rows.
            sessions = root.optJSONArray("sessions")?.let(::readSessions) ?: emptyList(),
            aimLabSessions = root.optJSONArray("aimLabSessions")?.let(::readAimLabSessions) ?: emptyList(),
            personalRecords = root.optJSONArray("personalRecords")?.let(::readPersonalRecords) ?: emptyList(),
            device = root.optJSONObject("device")?.let(::readDevice),
            version = version,
        )
    }

    /**
     * The profile layer's bundle, reassembled for it to import — or null if the file carried neither part.
     *
     * This layer never looks inside: it hands `{ "profiles": [...], "presets": {...} }` straight back to
     * the code that built it. Null (both parts absent) is how the orchestrator knows not to call the
     * profile importer at all, distinct from an empty bundle that would import nothing.
     */
    private fun readProfiles(root: JSONObject): JSONObject? {
        val profiles = root.optJSONArray("profiles")
        val presets = root.optJSONObject("presets")
        if (profiles == null && presets == null) return null
        return JSONObject().apply {
            put("profiles", profiles ?: JSONArray())
            put("presets", presets ?: JSONObject())
        }
    }

    /**
     * Reads [AppSettings], every field falling back to the model default when absent and every value put
     * through [AppSettings.normalised] on the way out — a hand-edited interval or scale is clamped, a
     * package name in the never-close list is validated, and an enum name a build does not know resolves to
     * the default rather than throwing.
     */
    private fun readSettings(o: JSONObject): AppSettings {
        val d = AppSettings()
        return AppSettings(
            theme = enumByName(o.optString("theme"), d.theme),
            accent = enumByName(o.optString("accent"), d.accent),
            uiScalePercent = o.optInt("uiScalePercent", d.uiScalePercent),
            useDynamicColour = o.optBoolean("useDynamicColour", d.useDynamicColour),
            useCustomAccent = o.optBoolean("useCustomAccent", d.useCustomAccent),
            customAccentArgb = if (o.has("customAccentArgb") && !o.isNull("customAccentArgb")) {
                o.optInt("customAccentArgb")
            } else {
                d.customAccentArgb
            },
            compactDensity = o.optBoolean("compactDensity", d.compactDensity),
            showBuiltInHomeStats = o.optBoolean("showBuiltInHomeStats", d.showBuiltInHomeStats),
            animations = enumByName(o.optString("animations"), d.animations),
            hapticsEnabled = o.optBoolean("hapticsEnabled", d.hapticsEnabled),
            allowElevatedReads = o.optBoolean("allowElevatedReads", d.allowElevatedReads),
            autoApplyProfiles = o.optBoolean("autoApplyProfiles", d.autoApplyProfiles),
            showResolutionOverrideNotice =
                o.optBoolean("showResolutionOverrideNotice", d.showResolutionOverrideNotice),
            showConfigEditNotice =
                o.optBoolean("showConfigEditNotice", d.showConfigEditNotice),
            showInstantReplayNotice =
                o.optBoolean("showInstantReplayNotice", d.showInstantReplayNotice),
            trackSessions = o.optBoolean("trackSessions", d.trackSessions),
            detectionIntervalMillis = o.optLong("detectionIntervalMillis", d.detectionIntervalMillis),
            sampleIntervalMillis = o.optLong("sampleIntervalMillis", d.sampleIntervalMillis),
            measureLatency = o.optBoolean("measureLatency", d.measureLatency),
            latencyHost = o.optString("latencyHost", d.latencyHost),
            keepScreenOnInGame = o.optBoolean("keepScreenOnInGame", d.keepScreenOnInGame),
            showThermalWarnings = o.optBoolean("showThermalWarnings", d.showThermalWarnings),
            backgroundMonitoring = o.optBoolean("backgroundMonitoring", d.backgroundMonitoring),
            confirmBeforeDiscard = o.optBoolean("confirmBeforeDiscard", d.confirmBeforeDiscard),
            recordingQuality = enumByName(o.optString("recordingQuality"), d.recordingQuality),
            hasSeenIntroduction = o.optBoolean("hasSeenIntroduction", d.hasSeenIntroduction),
            setupCompletedVersion = o.optInt("setupCompletedVersion", d.setupCompletedVersion),
            setupDismissed = o.optBoolean("setupDismissed", d.setupDismissed),
            neverKillPackages = readStrings(o.optJSONArray("neverKillPackages")),
            quickTrigger = o.optJSONObject("quickTrigger")?.let(::readQuickTrigger) ?: d.quickTrigger,
            aimLabEnabled = o.optBoolean("aimLabEnabled", d.aimLabEnabled),
            aimLabOrientation = enumByName(o.optString("aimLabOrientation"), d.aimLabOrientation),
            aimLabHorizontalFovDegrees = o.optInt("aimLabHorizontalFovDegrees", d.aimLabHorizontalFovDegrees),
            advancedHudEnabled = o.optBoolean("advancedHudEnabled", d.advancedHudEnabled),
            networkStabilityEnabled = o.optBoolean("networkStabilityEnabled", d.networkStabilityEnabled),
            customModulesEnabled = o.optBoolean("customModulesEnabled", d.customModulesEnabled),
            screenExtractionEnabled = o.optBoolean("screenExtractionEnabled", d.screenExtractionEnabled),
            touchSamplingEnabled = o.optBoolean("touchSamplingEnabled", d.touchSamplingEnabled),
            volumePointTriggerEnabled = o.optBoolean("volumePointTriggerEnabled", d.volumePointTriggerEnabled),
            dockCustomizationEnabled = o.optBoolean("dockCustomizationEnabled", d.dockCustomizationEnabled),
            quickActionsEnabled = o.optBoolean("quickActionsEnabled", d.quickActionsEnabled),
            lastSeenWhatsNewVersionCode =
                o.optInt("lastSeenWhatsNewVersionCode", d.lastSeenWhatsNewVersionCode),
        ).normalised()
    }

    private fun readQuickTrigger(o: JSONObject): QuickTriggerSettings {
        val d = QuickTriggerSettings()
        return QuickTriggerSettings(
            enabled = o.optBoolean("enabled", d.enabled),
            method = enumByName(o.optString("method"), d.method),
            action = enumByName(o.optString("action"), d.action),
            windowMillis = o.optLong("windowMillis", d.windowMillis),
            shakeSensitivity = o.optInt("shakeSensitivity", d.shakeSensitivity),
            passThroughKeys = o.optBoolean("passThroughKeys", d.passThroughKeys),
        ).normalised()
    }

    private fun readOverlay(o: JSONObject): OverlayConfig {
        val d = OverlayConfig()
        return OverlayConfig(
            showPill = o.optBoolean("showPill", d.showPill),
            pillX = o.optInt("pillX", d.pillX),
            pillY = o.optInt("pillY", d.pillY),
            stats = o.optJSONArray("stats")?.let(::readStats) ?: d.stats,
            updateIntervalMillis = o.optLong("updateIntervalMillis", d.updateIntervalMillis),
            textSizeSp = o.optInt("textSizeSp", d.textSizeSp),
            opacityPercent = o.optInt("opacityPercent", d.opacityPercent),
            cornerRadiusDp = o.optInt("cornerRadiusDp", d.cornerRadiusDp),
            isVertical = o.optBoolean("isVertical", d.isVertical),
            showLabels = o.optBoolean("showLabels", d.showLabels),
            displayMode = PillDisplayMode.of(o.optString("displayMode")),
            hudDisplayMode = HudDisplayMode.of(o.optString("hudDisplayMode")),
            quickPins = readStrings(o.optJSONArray("quickPins")),
            quickAutoClose = o.optBoolean("quickAutoClose", d.quickAutoClose),
            // Macros live at the envelope's top level; this config carries none of its own.
            macrosJson = "",
        ).normalised()
    }

    private fun readButton(o: JSONObject): FloatingButtonConfig {
        val d = FloatingButtonConfig()
        return FloatingButtonConfig(
            show = o.optBoolean("show", d.show),
            x = o.optInt("x", d.x),
            y = o.optInt("y", d.y),
            sizeDp = o.optInt("sizeDp", d.sizeDp),
            opacityPercent = o.optInt("opacityPercent", d.opacityPercent),
            snapToEdge = o.optBoolean("snapToEdge", d.snapToEdge),
            idleOpacityPercent = o.optInt("idleOpacityPercent", d.idleOpacityPercent),
            hapticFeedback = o.optBoolean("hapticFeedback", d.hapticFeedback),
            tapOpensPanel = o.optBoolean("tapOpensPanel", d.tapOpensPanel),
            doubleTapForPanel = o.optBoolean("doubleTapForPanel", d.doubleTapForPanel),
            panelWidthDp = o.optInt("panelWidthDp", d.panelWidthDp),
            panelLayout = enumByName(o.optString("panelLayout"), d.panelLayout),
            showQuickApps = o.optBoolean("showQuickApps", d.showQuickApps),
            quickAppPackages = readStrings(o.optJSONArray("quickAppPackages")),
            portraitXFraction = readFractionOrNull(o, "portraitXFraction"),
            portraitYFraction = readFractionOrNull(o, "portraitYFraction"),
            landscapeXFraction = readFractionOrNull(o, "landscapeXFraction"),
            landscapeYFraction = readFractionOrNull(o, "landscapeYFraction"),
        ).normalised()
    }

    /**
     * The dock, with the arrangement resolved by name and the rest clamped by the model.
     *
     * An absent `actionOrder` or `hiddenActions` falls back to the shipped defaults rather than to "nothing
     * shown" — the same absent-versus-empty rule the preference store follows, so restoring a backup written
     * by 3.7.0 (which had no dock section at all) or a hand-trimmed file leaves the user with the five
     * controls the dock shipped with instead of an empty panel. Unknown names drop in
     * [DockActions.parseOrder], which then re-appends every id the file was missing, so the order that comes
     * out is always complete whatever went in.
     */
    private fun readDock(o: JSONObject): DockConfig {
        val d = DockConfig()
        return DockConfig(
            show = o.optBoolean("show", d.show),
            actionOrder = o.optJSONArray("actionOrder")
                ?.let { DockActions.parseOrder(readStrings(it)) }
                ?: DockActionId.DEFAULT_ORDER,
            hiddenActions = o.optJSONArray("hiddenActions")
                ?.let { DockActions.parseHidden(readStrings(it)) }
                ?: DockActionId.DEFAULT_HIDDEN,
            sizeDp = o.optInt("sizeDp", d.sizeDp),
            opacity = o.optDouble("opacity", d.opacity.toDouble()).toFloat(),
            snapToEdge = o.optBoolean("snapToEdge", d.snapToEdge),
            hapticFeedback = o.optBoolean("hapticFeedback", d.hapticFeedback),
            panelWidthDp = o.optInt("panelWidthDp", d.panelWidthDp),
            x = o.optInt("x", d.x),
            y = o.optInt("y", d.y),
            portraitXFraction = readFractionOrNull(o, "portraitXFraction"),
            portraitYFraction = readFractionOrNull(o, "portraitYFraction"),
            landscapeXFraction = readFractionOrNull(o, "landscapeXFraction"),
            landscapeYFraction = readFractionOrNull(o, "landscapeYFraction"),
        ).normalised()
    }

    private fun readColour(o: JSONObject): ColorCorrection {
        val d = ColorCorrection()
        return ColorCorrection(
            redGain = o.optInt("redGain", d.redGain),
            greenGain = o.optInt("greenGain", d.greenGain),
            blueGain = o.optInt("blueGain", d.blueGain),
            gammaMode = enumByName(o.optString("gammaMode"), d.gammaMode),
            gamma = o.optInt("gamma", d.gamma),
            redGamma = o.optInt("redGamma", d.redGamma),
            greenGamma = o.optInt("greenGamma", d.greenGamma),
            blueGamma = o.optInt("blueGamma", d.blueGamma),
            saturation = o.optInt("saturation", d.saturation),
            contrast = o.optInt("contrast", d.contrast),
            hueDegrees = o.optInt("hueDegrees", d.hueDegrees),
            brightnessOffset = o.optInt("brightnessOffset", d.brightnessOffset),
            visionFilter = enumByName(o.optString("visionFilter"), d.visionFilter),
            invertColors = o.optBoolean("invertColors", d.invertColors),
        ).normalised()
    }

    /**
     * Reads the HUD layouts. A layout's `id` is kept as the file-local handle it is — the orchestrator
     * assigns a real row id on import — while its name is sanitised (it is drawn into an overlay window)
     * and its widgets are capped, deduplicated by id and each individually normalised. A layout whose name
     * sanitises to nothing is given a plain default rather than dropped, so a user does not silently lose one.
     */
    private fun readLayouts(array: JSONArray): List<HudLayout> {
        val out = ArrayList<HudLayout>()
        val scan = minOf(array.length(), SCAN_LIMIT)
        for (i in 0 until scan) {
            val o = array.optJSONObject(i) ?: continue
            out += HudLayout(
                id = o.optLong("id", 0L),
                name = TextSanitizer.sanitizeName(o.optString("name"), HudLayout.MAX_NAME_LENGTH)
                    .ifBlank { DEFAULT_LAYOUT_NAME },
                widgets = readWidgets(o.optJSONArray("widgets")),
                createdAtMillis = o.optLong("createdAtMillis", 0L),
                updatedAtMillis = o.optLong("updatedAtMillis", 0L),
            )
        }
        return out
    }

    private fun readWidgets(array: JSONArray?): List<HudWidget> {
        if (array == null) return emptyList()
        val out = ArrayList<HudWidget>()
        val seen = HashSet<String>()
        val scan = minOf(array.length(), SCAN_LIMIT)
        for (i in 0 until scan) {
            if (out.size >= HudLayout.MAX_WIDGETS) break
            val o = array.optJSONObject(i) ?: continue
            // A widget the renderer cannot name (unknown stat) or address (a duplicate or blank id) is
            // dropped, matching how the store codecs drop an item they cannot make sense of.
            val stat = hudStatByName(o.optString("stat")) ?: continue
            val id = o.optString("id").ifBlank { "$i" }
            if (!seen.add(id)) continue
            out += HudWidget(
                id = id,
                stat = stat,
                xFraction = o.optDouble("xFraction", DEFAULT_WIDGET_FRACTION).toFloat(),
                yFraction = o.optDouble("yFraction", DEFAULT_WIDGET_FRACTION).toFloat(),
                textSizeSp = o.optInt("textSizeSp", HudWidget.DEFAULT_TEXT_SIZE_SP),
                opacityPercent = o.optInt("opacityPercent", DEFAULT_WIDGET_OPACITY),
                showLabel = o.optBoolean("showLabel", true),
                showBackground = o.optBoolean("showBackground", true),
                colorArgb = o.optInt("colorArgb", HudWidget.DEFAULT_COLOR),
            ).normalised()
        }
        return out
    }

    /**
     * Reads the standalone crosshair presets. The name is sanitised (it is drawn into an overlay window), the
     * design and every numeric field fall back to the model default and are clamped by [CrosshairPreset.normalised],
     * and the image path is dropped — a custom-image preset restores without its picture, exactly as it does
     * through the profile layer, rather than carrying a path that points nowhere on another device.
     */
    private fun readCrosshairs(array: JSONArray): List<CrosshairPreset> {
        val out = ArrayList<CrosshairPreset>()
        val d = CrosshairPreset.default()
        val scan = minOf(array.length(), SCAN_LIMIT)
        for (i in 0 until scan) {
            val o = array.optJSONObject(i) ?: continue
            out += CrosshairPreset(
                id = o.optLong("id", 0L),
                name = TextSanitizer.sanitizeName(o.optString("name"), CrosshairPreset.MAX_NAME_LENGTH)
                    .ifBlank { DEFAULT_CROSSHAIR_NAME },
                design = enumByName(o.optString("design"), d.design),
                sizeDp = o.optInt("sizeDp", d.sizeDp),
                thicknessDp = o.optInt("thicknessDp", d.thicknessDp),
                centreGapDp = o.optInt("centreGapDp", d.centreGapDp),
                opacityPercent = o.optInt("opacityPercent", d.opacityPercent),
                rotationDegrees = o.optInt("rotationDegrees", d.rotationDegrees),
                colorArgb = o.optInt("colorArgb", d.colorArgb),
                showDot = o.optBoolean("showDot", d.showDot),
                showOutline = o.optBoolean("showOutline", d.showOutline),
                xFraction = o.optDouble("xFraction", d.xFraction.toDouble()).toFloat(),
                yFraction = o.optDouble("yFraction", d.yFraction.toDouble()).toFloat(),
                imagePath = null,
            ).normalised()
        }
        return out
    }

    /**
     * Reads the standalone colour presets. The name is sanitised and the correction is read through the same
     * [readColour] the live colour section uses, so every value is clamped by [ColorCorrection.normalised] and
     * an unknown gamma mode or vision filter falls back to its default rather than throwing.
     */
    private fun readColourPresets(array: JSONArray): List<ColorPreset> {
        val out = ArrayList<ColorPreset>()
        val scan = minOf(array.length(), SCAN_LIMIT)
        for (i in 0 until scan) {
            val o = array.optJSONObject(i) ?: continue
            out += ColorPreset(
                id = o.optLong("id", 0L),
                name = TextSanitizer.sanitizeName(o.optString("name"), ColorPreset.MAX_NAME_LENGTH)
                    .ifBlank { DEFAULT_COLOUR_NAME },
                correction = o.optJSONObject("correction")?.let(::readColour) ?: ColorCorrection(),
            ).normalised()
        }
        return out
    }

    // ----------------------------------------------------------------- version 2 history (decode)

    /**
     * Reads the finished game sessions.
     *
     * A row without a valid package name, a start time or a finish time is dropped: history is a record of a
     * session that actually ran to an end, and a hand-edited row cannot be allowed to create a permanently
     * "running" session. Every aggregate keeps its null, every enum is resolved by name with an unknown one
     * dropped rather than defaulted, and the text fields are sanitised because they are drawn in the app.
     */
    private fun readSessions(array: JSONArray): List<GameSession> {
        val out = ArrayList<GameSession>()
        val scan = minOf(array.length(), MAX_HISTORY_ENTRIES)
        for (i in 0 until scan) {
            val o = array.optJSONObject(i) ?: continue
            val packageName = TextSanitizer.validatePackageName(o.optString("packageName")) ?: continue
            val started = readLongOrNull(o, "startedAtMillis") ?: continue
            val ended = readLongOrNull(o, "endedAtMillis") ?: continue
            if (started <= 0L || ended < started) continue
            out += GameSession(
                id = o.optLong("id", 0L),
                packageName = packageName,
                gameLabel = TextSanitizer.sanitizeName(o.optString("gameLabel"))
                    .ifBlank { packageName },
                startedAtMillis = started,
                endedAtMillis = ended,
                batteryStartPercent = o.optInt("batteryStartPercent", 0).coerceIn(0, 100),
                batteryEndPercent = readIntOrNull(o, "batteryEndPercent")?.coerceIn(0, 100),
                wasCharging = o.optBoolean("wasCharging", false),
                averageCpuPercent = readFloatOrNull(o, "averageCpuPercent"),
                peakCpuPercent = readFloatOrNull(o, "peakCpuPercent"),
                averageMemoryPercent = readFloatOrNull(o, "averageMemoryPercent"),
                peakMemoryPercent = readFloatOrNull(o, "peakMemoryPercent"),
                averageTemperatureDeciCelsius = readIntOrNull(o, "averageTemperatureDeciCelsius"),
                peakTemperatureDeciCelsius = readIntOrNull(o, "peakTemperatureDeciCelsius"),
                averageRefreshRate = readFloatOrNull(o, "averageRefreshRate"),
                averageFrameRate = readFloatOrNull(o, "averageFrameRate"),
                averageLatencyMillis = readIntOrNull(o, "averageLatencyMillis"),
                profileApplied = o.optBoolean("profileApplied", false),
                sampleCount = o.optInt("sampleCount", 0).coerceAtLeast(0),
                stopReason = o.optString("stopReason").let { name ->
                    StopReason.entries.firstOrNull { it.name == name }
                },
                colorPresetName = o.optString("colorPresetName").takeIf { it.isNotBlank() },
                colorCorrection = o.optJSONObject("colorCorrection")?.let(::readColour),
                latencyLog = o.optJSONObject("latencyLog")?.let(::readLatencyLog),
                thermalStatus = o.optString("thermalStatus").takeIf { it.isNotBlank() },
                powerSaver = readBooleanOrNull(o, "powerSaver"),
                refreshCapped = readBooleanOrNull(o, "refreshCapped"),
                downshiftCount = readIntOrNull(o, "downshiftCount")?.coerceAtLeast(0),
                lowestRateHz = readFloatOrNull(o, "lowestRateHz")?.takeIf { it in 1f..480f },
                transport = o.optString("transport").let { name ->
                    NetworkTransport.entries.firstOrNull { it.name == name }
                },
                fullPerformanceOverridden = readBooleanOrNull(o, "fullPerformanceOverridden"),
                systemReenabledSaver = readBooleanOrNull(o, "systemReenabledSaver"),
                resolutionApplied = o.optString("resolutionApplied").let { name ->
                    ResolutionScale.entries.firstOrNull { it.name == name }
                },
                instantReplayUsed = readBooleanOrNull(o, "instantReplayUsed"),
                clipsSaved = readIntOrNull(o, "clipsSaved")?.coerceAtLeast(0),
            )
        }
        return out
    }

    private fun readLatencyLog(o: JSONObject): LatencyLog = LatencyLog(
        completedProbes = o.optInt("completedProbes", 0).coerceAtLeast(0),
        failedProbes = o.optInt("failedProbes", 0).coerceAtLeast(0),
        spikes = o.optInt("spikes", 0).coerceAtLeast(0),
        worstMillis = readIntOrNull(o, "worstMillis"),
        jitterMillis = readIntOrNull(o, "jitterMillis"),
        longestFailureRun = o.optInt("longestFailureRun", 0).coerceAtLeast(0),
    )

    /**
     * Reads the Aim Lab summaries. An unknown mode is dropped rather than guessed, because a summary is
     * grouped by mode for records and history; a difficulty falls back the way the row mapper's does.
     */
    private fun readAimLabSessions(array: JSONArray): List<SessionSummary> {
        val out = ArrayList<SessionSummary>()
        val scan = minOf(array.length(), MAX_HISTORY_ENTRIES)
        for (i in 0 until scan) {
            val o = array.optJSONObject(i) ?: continue
            val mode = TrainingMode.fromName(o.optString("mode")) ?: continue
            val started = readLongOrNull(o, "startedAtMillis") ?: continue
            val ended = readLongOrNull(o, "endedAtMillis") ?: continue
            if (started <= 0L || ended < started) continue
            out += SessionSummary(
                id = o.optLong("id", 0L),
                mode = mode,
                difficulty = Difficulty.fromName(o.optString("difficulty")),
                startedAtMillis = started,
                endedAtMillis = ended,
                weaponName = o.optString("weaponName").takeIf { it.isNotBlank() },
                sensitivityName = o.optString("sensitivityName").takeIf { it.isNotBlank() },
                score = o.optInt("score", 0).coerceAtLeast(0),
                hits = o.optInt("hits", 0).coerceAtLeast(0),
                shots = o.optInt("shots", 0).coerceAtLeast(0),
                targetsMissed = o.optInt("targetsMissed", 0).coerceAtLeast(0),
                reactionStats = readReactionStats(o),
                averageAcquireMillis = readFloatOrNull(o, "averageAcquireMillis") ?: 0f,
                trackingErrorAverage = readFloatOrNull(o, "trackingErrorAverage") ?: 0f,
                timeOnTargetFraction = readFloatOrNull(o, "timeOnTargetFraction")?.coerceIn(0f, 1f) ?: 0f,
                recoilCompensation = readFloatOrNull(o, "recoilCompensation") ?: 0f,
                gyroStability = readFloatOrNull(o, "gyroStability") ?: 0f,
                scoringVersion = o.optInt("scoringVersion", SessionSummary.SCORING_VERSION_3D),
            )
        }
        return out
    }

    private fun readReactionStats(o: JSONObject): ReactionStats = ReactionStats(
        attempts = o.optInt("reactionAttempts", 0).coerceAtLeast(0),
        fastestMillis = o.optLong("reactionFastest", 0L).coerceAtLeast(0L),
        slowestMillis = o.optLong("reactionSlowest", 0L).coerceAtLeast(0L),
        averageMillis = readFloatOrNull(o, "reactionAverage") ?: 0f,
        medianMillis = readFloatOrNull(o, "reactionMedian") ?: 0f,
    )

    /** Reads personal records, dropping a row whose metric or mode this build does not know. */
    private fun readPersonalRecords(array: JSONArray): List<PersonalRecord> {
        val out = ArrayList<PersonalRecord>()
        val scan = minOf(array.length(), MAX_HISTORY_ENTRIES)
        for (i in 0 until scan) {
            val o = array.optJSONObject(i) ?: continue
            val mode = TrainingMode.fromName(o.optString("mode")) ?: continue
            val metric = RecordMetric.fromName(o.optString("metric")) ?: continue
            out += PersonalRecord(
                mode = mode,
                difficulty = Difficulty.fromName(o.optString("difficulty")),
                weaponName = o.optString("weaponName").takeIf { it.isNotBlank() },
                metric = metric,
                value = readFloatOrNull(o, "value") ?: continue,
                previousValue = readFloatOrNull(o, "previousValue"),
                achievedAtMillis = o.optLong("achievedAtMillis", 0L).coerceAtLeast(0L),
                scoringVersion = o.optInt("scoringVersion", SessionSummary.SCORING_VERSION_3D),
            )
        }
        return out
    }

    // ------------------------------------------------------------------------------------------ helpers

    /** An enum value by its stored `name`, or [default] for an absent, blank or unknown one. */
    private inline fun <reified T : Enum<T>> enumByName(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default

    /** [HudStat] has no `of`; resolve by name and return null (drop the widget) for an unknown one. */
    private fun hudStatByName(name: String?): HudStat? =
        HudStat.entries.firstOrNull { it.name == name }

    /** The non-blank strings of an array, in order. Emptiness (or absence) collapses to an empty list. */
    private fun readStrings(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val out = ArrayList<String>()
        val scan = minOf(array.length(), SCAN_LIMIT)
        for (i in 0 until scan) {
            val s = array.optString(i, "")
            if (s.isNotBlank()) out += s
        }
        return out
    }

    /** The [HudStat]s of an array, unknown names dropped; [OverlayConfig.normalised] dedupes and caps. */
    private fun readStats(array: JSONArray?): List<HudStat> {
        if (array == null) return emptyList()
        val out = ArrayList<HudStat>()
        val scan = minOf(array.length(), SCAN_LIMIT)
        for (i in 0 until scan) {
            hudStatByName(array.optString(i, ""))?.let { out += it }
        }
        return out
    }

    /** A stored position fraction, or null when the key is absent — half a pair cannot place a window. */
    private fun readFractionOrNull(o: JSONObject, key: String): Float? =
        if (o.has(key) && !o.isNull(key)) o.optDouble(key).toFloat() else null

    /** A nullable integer that distinguishes an absent key from a stored zero. */
    private fun readIntOrNull(o: JSONObject, key: String): Int? =
        if (o.has(key) && !o.isNull(key)) o.optInt(key) else null

    /** A nullable long that distinguishes an absent key from a stored zero. */
    private fun readLongOrNull(o: JSONObject, key: String): Long? =
        if (o.has(key) && !o.isNull(key)) o.optLong(key) else null

    /** A finite nullable float; NaN and infinities are not measurements and are dropped. */
    private fun readFloatOrNull(o: JSONObject, key: String): Float? {
        if (!o.has(key) || o.isNull(key)) return null
        val value = o.optDouble(key, Double.NaN)
        return if (value.isFinite()) value.toFloat() else null
    }

    /** A nullable boolean that distinguishes an absent reading from a recorded `false`. */
    private fun readBooleanOrNull(o: JSONObject, key: String): Boolean? =
        if (o.has(key) && !o.isNull(key)) o.optBoolean(key) else null

    /** What a nameless layout becomes, rather than being dropped for the sake of a blank field. */
    private const val DEFAULT_LAYOUT_NAME = "Layout"

    /**
     * The longest a manufacturer or model string may run in the backup file.
     *
     * These are the fields the restore screen prints as "Coming from <device>", so they are bounded by
     * the same sanitiser every other printed name uses rather than by a bespoke limit — a hand-edited
     * file with a 40 KB model string should not become a 40 KB notification.
     */
    private const val MAX_DEVICE_FIELD_LENGTH = 48

    /** The same fallback for a nameless standalone preset, mirroring each model's own default name. */
    private const val DEFAULT_CROSSHAIR_NAME = "Crosshair"
    private const val DEFAULT_COLOUR_NAME = "Colour"

    /** The model's own widget defaults, mirrored here for the read fallback (the data class inlines them). */
    private const val DEFAULT_WIDGET_FRACTION = 0.05
    private const val DEFAULT_WIDGET_OPACITY = 85
}
