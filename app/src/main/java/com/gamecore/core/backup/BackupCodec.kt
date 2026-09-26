package com.gamecore.core.backup

import com.gamecore.core.common.TextSanitizer
import com.gamecore.core.model.AppSettings
import com.gamecore.core.model.ColorCorrection
import com.gamecore.core.model.ColorPreset
import com.gamecore.core.model.CrosshairPreset
import com.gamecore.core.model.FloatingButtonConfig
import com.gamecore.core.model.HudLayout
import com.gamecore.core.model.HudStat
import com.gamecore.core.model.HudWidget
import com.gamecore.core.model.OverlayConfig
import com.gamecore.core.model.PillDisplayMode
import com.gamecore.core.model.QuickTriggerSettings
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The §20 config backup's on-disk shape, and the one place it is written and read.
 *
 * A backup is *configuration*, never session history: appearance and settings, the overlay windows'
 * configs, the quick-sheet pins and macros, the HUD layouts, and — handed in already built by the profile
 * layer — the game profiles and the presets they reference. The crosshair and colour presets a profile does
 * *not* reference (the standalone ones the user made but never assigned) travel in their own top-level
 * sections, so a preset is backed up whether or not a profile points at it, and each referenced one still
 * travels exactly once via the profile layer rather than being duplicated here (the orchestrator hands this
 * codec only the standalone remainder). What a user has *measured* (sessions, Aim Lab runs) is deliberately
 * left out; it is a log, not a setting, and a restore that overwrote it would be destroying the very thing
 * the user opened the app to keep.
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

    /** The only envelope version this build reads or writes. Any other value is [RejectReason.UNSUPPORTED_VERSION]. */
    const val VERSION = 1

    /** A ceiling on array walks, so a pathological hand-edited file cannot be scanned end to end. */
    private const val SCAN_LIMIT = 512

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
     */
    data class BackupData(
        val settings: AppSettings?,
        val overlay: OverlayConfig?,
        val button: FloatingButtonConfig?,
        val colour: ColorCorrection?,
        val layouts: List<HudLayout>?,
        val crosshairs: List<CrosshairPreset>?,
        val colours: List<ColorPreset>?,
        val macros: JSONArray?,
        val profiles: JSONObject?,
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
     */
    fun encode(
        settings: AppSettings,
        overlay: OverlayConfig,
        button: FloatingButtonConfig,
        colour: ColorCorrection,
        layouts: List<HudLayout>,
        crosshairs: List<CrosshairPreset>,
        colours: List<ColorPreset>,
        macros: JSONArray,
        profiles: JSONObject,
    ): String = JSONObject().apply {
        put("format", FORMAT)
        put("version", VERSION)
        put(
            "settings",
            JSONObject().apply {
                put("app", encodeSettings(settings))
                put("overlay", encodeOverlay(overlay))
                put("button", encodeButton(button))
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
        put("animations", s.animations.name)
        put("hapticsEnabled", s.hapticsEnabled)
        put("allowElevatedReads", s.allowElevatedReads)
        put("autoApplyProfiles", s.autoApplyProfiles)
        put("showResolutionOverrideNotice", s.showResolutionOverrideNotice)
        put("showConfigEditNotice", s.showConfigEditNotice)
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
        if (root.optInt("version", 0) != VERSION) {
            return BackupResult.Rejected(RejectReason.UNSUPPORTED_VERSION)
        }
        return try {
            BackupResult.Restored(readBackup(root))
        } catch (_: Throwable) {
            BackupResult.Rejected(RejectReason.MALFORMED)
        }
    }

    private fun readBackup(root: JSONObject): BackupData {
        val settings = root.optJSONObject("settings")
        return BackupData(
            settings = settings?.optJSONObject("app")?.let(::readSettings),
            overlay = settings?.optJSONObject("overlay")?.let(::readOverlay),
            button = settings?.optJSONObject("button")?.let(::readButton),
            colour = settings?.optJSONObject("colour")?.let(::readColour),
            layouts = root.optJSONArray("layouts")?.let(::readLayouts),
            crosshairs = root.optJSONArray("crosshairs")?.let(::readCrosshairs),
            colours = root.optJSONArray("colours")?.let(::readColourPresets),
            macros = root.optJSONArray("macros"),
            profiles = readProfiles(root),
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
            animations = enumByName(o.optString("animations"), d.animations),
            hapticsEnabled = o.optBoolean("hapticsEnabled", d.hapticsEnabled),
            allowElevatedReads = o.optBoolean("allowElevatedReads", d.allowElevatedReads),
            autoApplyProfiles = o.optBoolean("autoApplyProfiles", d.autoApplyProfiles),
            showResolutionOverrideNotice =
                o.optBoolean("showResolutionOverrideNotice", d.showResolutionOverrideNotice),
            showConfigEditNotice =
                o.optBoolean("showConfigEditNotice", d.showConfigEditNotice),
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

    /** What a nameless layout becomes, rather than being dropped for the sake of a blank field. */
    private const val DEFAULT_LAYOUT_NAME = "Layout"

    /** The same fallback for a nameless standalone preset, mirroring each model's own default name. */
    private const val DEFAULT_CROSSHAIR_NAME = "Crosshair"
    private const val DEFAULT_COLOUR_NAME = "Colour"

    /** The model's own widget defaults, mirrored here for the read fallback (the data class inlines them). */
    private const val DEFAULT_WIDGET_FRACTION = 0.05
    private const val DEFAULT_WIDGET_OPACITY = 85
}
