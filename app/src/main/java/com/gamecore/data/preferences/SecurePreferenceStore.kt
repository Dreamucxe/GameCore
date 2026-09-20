package com.gamecore.data.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.model.AccentChoice
import com.gamecore.core.model.AimLabOrientation
import com.gamecore.core.model.AppSettings
import com.gamecore.core.model.ColorCorrection
import com.gamecore.core.model.ColorVisionFilter
import com.gamecore.core.model.CustomCrosshairColours
import com.gamecore.core.model.FloatingButtonConfig
import com.gamecore.core.model.GammaMode
import com.gamecore.core.model.HudStat
import com.gamecore.core.model.OverlayConfig
import com.gamecore.core.model.PanelLayoutStyle
import com.gamecore.core.model.QuickTriggerAction
import com.gamecore.core.model.QuickTriggerMethod
import com.gamecore.core.model.QuickTriggerSettings
import com.gamecore.core.model.RecordingQuality
import com.gamecore.core.model.ThemeChoice
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every setting GameCore remembers, in one encrypted file.
 *
 * Encrypted rather than plain `SharedPreferences` because §24A.5 says so without an exemption for
 * "settings are not sensitive". Some of what lands here is not benign anyway: the list of games
 * with profiles is a list of what the user plays, and the pill's remembered position and stat
 * selection describes their screen. On a device where the sandbox is not intact — §24A.13's case —
 * a plain XML file is world-readable to anything with a shell.
 *
 * The whole file is read once, on first access, and held in memory; every read after that is a field
 * access on a [StateFlow]. That is not premature optimisation: the overlay service reads the pill
 * config on every tick, and Tink's AEAD decrypt per key per read would be a measurable cost inside a
 * loop running underneath a game. Writes go through to disk immediately and update the flow, so there
 * is one source of truth rather than a cache that can drift.
 *
 * "On first access" rather than in the constructor, because opening the file is Keystore work plus a
 * disk read plus a keyset decrypt — on a first launch, key generation in the TEE, which is tens to
 * hundreds of milliseconds. Hilt constructs singletons on whichever thread first asks for one, and
 * for a ViewModel dependency that is the main thread. [preload] exists so the startup path can pay
 * that cost on an IO dispatcher before any screen or overlay needs a value; `by lazy` means a caller
 * that gets there first still gets a correct answer, just a slower one.
 *
 * Values are read back through the models' own `normalised()` rather than trusted. A file that was
 * hand-edited, or written by a build with different bounds, produces a valid config here instead of
 * an overlay positioned off-screen.
 */
@Singleton
class SecurePreferenceStore @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * Null if the encrypted file cannot be opened.
     *
     * Losing the Keystore entry means losing preferences, which is annoying and not fatal — unlike
     * the database key, there is nothing here that cannot be re-chosen. When this is null the store
     * serves defaults and silently drops writes rather than throwing, because a settings file that
     * will not open should not stop the app from launching.
     */
    private val prefs: SharedPreferences? by lazy { openPreferences() }

    private val settingsState: MutableStateFlow<AppSettings> by lazy {
        MutableStateFlow(readSettings())
    }
    private val overlayState: MutableStateFlow<OverlayConfig> by lazy {
        MutableStateFlow(readOverlay())
    }
    private val buttonState: MutableStateFlow<FloatingButtonConfig> by lazy {
        MutableStateFlow(readButton())
    }
    private val colorState: MutableStateFlow<ColorCorrection> by lazy {
        MutableStateFlow(readColor())
    }

    val settings: StateFlow<AppSettings> get() = settingsState.asStateFlow()
    val overlay: StateFlow<OverlayConfig> get() = overlayState.asStateFlow()
    val floatingButton: StateFlow<FloatingButtonConfig> get() = buttonState.asStateFlow()

    /**
     * Where the user left the colour sliders, preset or no preset.
     *
     * Kept apart from [activeColorPresetId] because they answer different questions. The id says
     * which saved preset the values came from; this says what the values *are* — including after
     * the user has dragged three sliders away from that preset without saving. Reopening the
     * overlay panel has to show the second, or every adjustment made in a game is lost the moment
     * the panel closes.
     */
    val colorCorrection: StateFlow<ColorCorrection> get() = colorState.asStateFlow()

    /** True when preferences are not persisting, so Settings can say so instead of lying. */
    val isPersisting: Boolean get() = prefs != null

    /**
     * Opens the file and populates the flows, off the main thread.
     *
     * Idempotent — `by lazy` is synchronised, so calling this while a screen is already reading is
     * safe and the second caller waits rather than duplicating the work.
     */
    suspend fun preload() = withContext(io) {
        settingsState.value
        overlayState.value
        buttonState.value
        colorState.value
        Unit
    }

    // ------------------------------------------------------------------- settings

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val updated = transform(settingsState.value).normalised()
        settingsState.value = updated
        writeSettings(updated)
    }

    /**
     * Reads the settings block.
     *
     * Enum values are stored and read by name, and an unrecognised name falls back to the default
     * rather than throwing — the same rule the database mappers follow, for the same reason: a
     * newer build's value in an older build's file is a normal thing to encounter, not corruption.
     */
    private fun readSettings(): AppSettings {
        val p = prefs ?: return AppSettings()
        val defaults = AppSettings()
        return AppSettings(
            theme = enumOrDefault(p.getString(KEY_THEME, null), ThemeChoice.entries, defaults.theme),
            accent = enumOrDefault(p.getString(KEY_ACCENT, null), AccentChoice.entries, defaults.accent),
            uiScalePercent = p.getInt(KEY_UI_SCALE, defaults.uiScalePercent),
            useDynamicColour = p.getBoolean(KEY_DYNAMIC_COLOUR, defaults.useDynamicColour),
            allowElevatedReads = p.getBoolean(KEY_ALLOW_ELEVATED, defaults.allowElevatedReads),
            autoApplyProfiles = p.getBoolean(KEY_AUTO_APPLY, defaults.autoApplyProfiles),
            trackSessions = p.getBoolean(KEY_TRACK_SESSIONS, defaults.trackSessions),
            detectionIntervalMillis = p.getLong(KEY_DETECT_INTERVAL, defaults.detectionIntervalMillis),
            sampleIntervalMillis = p.getLong(KEY_SAMPLE_INTERVAL, defaults.sampleIntervalMillis),
            measureLatency = p.getBoolean(KEY_MEASURE_LATENCY, defaults.measureLatency),
            latencyHost = p.getString(KEY_LATENCY_HOST, null) ?: defaults.latencyHost,
            keepScreenOnInGame = p.getBoolean(KEY_KEEP_SCREEN_ON, defaults.keepScreenOnInGame),
            showThermalWarnings = p.getBoolean(KEY_THERMAL_WARNINGS, defaults.showThermalWarnings),
            backgroundMonitoring = p.getBoolean(KEY_BACKGROUND_MONITOR, defaults.backgroundMonitoring),
            confirmBeforeDiscard = p.getBoolean(KEY_CONFIRM_DISCARD, defaults.confirmBeforeDiscard),
            recordingQuality = enumOrDefault(
                p.getString(KEY_RECORDING_QUALITY, null),
                RecordingQuality.entries,
                defaults.recordingQuality,
            ),
            hasSeenIntroduction = p.getBoolean(KEY_SEEN_INTRO, defaults.hasSeenIntroduction),
            neverKillPackages = readNeverKill(p),
            quickTrigger = readQuickTrigger(p),
            aimLabEnabled = p.getBoolean(KEY_AIMLAB_ENABLED, defaults.aimLabEnabled),
            aimLabOrientation = enumOrDefault(
                p.getString(KEY_AIMLAB_ORIENTATION, null),
                AimLabOrientation.entries,
                defaults.aimLabOrientation,
            ),
            aimLabHorizontalFovDegrees = p.getInt(KEY_AIMLAB_FOV, defaults.aimLabHorizontalFovDegrees),
        ).normalised()
    }

    /**
     * The quick-trigger block. Same enum-by-name rule as everything above it.
     *
     * A method this build does not recognise falls back to the default rather than disabling the
     * trigger, because the user's answer to "is the shortcut on" and their answer to "which key"
     * are separate decisions and only one of them can go stale.
     */
    private fun readQuickTrigger(p: SharedPreferences): QuickTriggerSettings {
        val defaults = QuickTriggerSettings()
        return QuickTriggerSettings(
            enabled = p.getBoolean(KEY_TRIGGER_ENABLED, defaults.enabled),
            method = enumOrDefault(
                p.getString(KEY_TRIGGER_METHOD, null),
                QuickTriggerMethod.entries,
                defaults.method,
            ),
            action = enumOrDefault(
                p.getString(KEY_TRIGGER_ACTION, null),
                QuickTriggerAction.entries,
                defaults.action,
            ),
            windowMillis = p.getLong(KEY_TRIGGER_WINDOW, defaults.windowMillis),
            shakeSensitivity = p.getInt(KEY_TRIGGER_SHAKE, defaults.shakeSensitivity),
            passThroughKeys = p.getBoolean(KEY_TRIGGER_PASS_THROUGH, defaults.passThroughKeys),
        ).normalised()
    }

    private fun writeSettings(value: AppSettings) {
        prefs?.edit()?.apply {
            putString(KEY_THEME, value.theme.name)
            putString(KEY_ACCENT, value.accent.name)
            putInt(KEY_UI_SCALE, value.uiScalePercent)
            putBoolean(KEY_DYNAMIC_COLOUR, value.useDynamicColour)
            putBoolean(KEY_ALLOW_ELEVATED, value.allowElevatedReads)
            putBoolean(KEY_AUTO_APPLY, value.autoApplyProfiles)
            putBoolean(KEY_TRACK_SESSIONS, value.trackSessions)
            putLong(KEY_DETECT_INTERVAL, value.detectionIntervalMillis)
            putLong(KEY_SAMPLE_INTERVAL, value.sampleIntervalMillis)
            putBoolean(KEY_MEASURE_LATENCY, value.measureLatency)
            putString(KEY_LATENCY_HOST, value.latencyHost)
            putBoolean(KEY_KEEP_SCREEN_ON, value.keepScreenOnInGame)
            putBoolean(KEY_THERMAL_WARNINGS, value.showThermalWarnings)
            putBoolean(KEY_BACKGROUND_MONITOR, value.backgroundMonitoring)
            putBoolean(KEY_CONFIRM_DISCARD, value.confirmBeforeDiscard)
            putString(KEY_RECORDING_QUALITY, value.recordingQuality.name)
            putBoolean(KEY_SEEN_INTRO, value.hasSeenIntroduction)
            putString(KEY_NEVER_KILL, value.neverKillPackages.joinToString(SEPARATOR))
            putBoolean(KEY_TRIGGER_ENABLED, value.quickTrigger.enabled)
            putString(KEY_TRIGGER_METHOD, value.quickTrigger.method.name)
            putString(KEY_TRIGGER_ACTION, value.quickTrigger.action.name)
            putLong(KEY_TRIGGER_WINDOW, value.quickTrigger.windowMillis)
            putInt(KEY_TRIGGER_SHAKE, value.quickTrigger.shakeSensitivity)
            putBoolean(KEY_TRIGGER_PASS_THROUGH, value.quickTrigger.passThroughKeys)
            putBoolean(KEY_AIMLAB_ENABLED, value.aimLabEnabled)
            putString(KEY_AIMLAB_ORIENTATION, value.aimLabOrientation.name)
            putInt(KEY_AIMLAB_FOV, value.aimLabHorizontalFovDegrees)
        }?.apply()
    }

    /**
     * The never-close list, as package names joined by a separator.
     *
     * The same shape as the pill's stat list and for the same reason — order is the order the user
     * added them, which is the order the editor shows. `AppSettings.normalised()` validates every
     * entry, so nothing that fails to be a package name survives a read even if it is sitting in the
     * file. Absent or empty reads back as an empty list rather than a default, because a default
     * never-close list would be GameCore deciding which of the user's apps matter.
     */
    private fun readNeverKill(p: SharedPreferences): List<String> =
        p.getString(KEY_NEVER_KILL, null)
            ?.split(SEPARATOR)
            ?.filter { it.isNotBlank() }
            .orEmpty()

    // -------------------------------------------------------------------- overlay

    fun updateOverlay(transform: (OverlayConfig) -> OverlayConfig) {
        val updated = transform(overlayState.value).normalised()
        overlayState.value = updated
        writeOverlay(updated)
    }

    /**
     * Records where the user dragged the pill to.
     *
     * Separate from [updateOverlay] because it is called on drag-release from the overlay service,
     * many times a session, and it has no business rewriting the stat list and interval each time.
     */
    fun updatePillPosition(x: Int, y: Int) {
        val updated = overlayState.value.copy(pillX = x, pillY = y)
        overlayState.value = updated
        prefs?.edit()?.putInt(KEY_PILL_X, x)?.putInt(KEY_PILL_Y, y)?.apply()
    }

    private fun readOverlay(): OverlayConfig {
        val p = prefs ?: return OverlayConfig()
        val defaults = OverlayConfig()
        return OverlayConfig(
            showPill = p.getBoolean(KEY_SHOW_PILL, defaults.showPill),
            pillX = p.getInt(KEY_PILL_X, defaults.pillX),
            pillY = p.getInt(KEY_PILL_Y, defaults.pillY),
            stats = readStats(p) ?: defaults.stats,
            updateIntervalMillis = p.getLong(KEY_PILL_INTERVAL, defaults.updateIntervalMillis),
            textSizeSp = p.getInt(KEY_PILL_TEXT_SIZE, defaults.textSizeSp),
            opacityPercent = p.getInt(KEY_PILL_OPACITY, defaults.opacityPercent),
            cornerRadiusDp = p.getInt(KEY_PILL_CORNER, defaults.cornerRadiusDp),
            isVertical = p.getBoolean(KEY_PILL_VERTICAL, defaults.isVertical),
            showLabels = p.getBoolean(KEY_PILL_LABELS, defaults.showLabels),
        ).normalised()
    }

    private fun writeOverlay(value: OverlayConfig) {
        prefs?.edit()?.apply {
            putBoolean(KEY_SHOW_PILL, value.showPill)
            putInt(KEY_PILL_X, value.pillX)
            putInt(KEY_PILL_Y, value.pillY)
            putString(KEY_PILL_STATS, value.stats.joinToString(SEPARATOR) { it.name })
            putLong(KEY_PILL_INTERVAL, value.updateIntervalMillis)
            putInt(KEY_PILL_TEXT_SIZE, value.textSizeSp)
            putInt(KEY_PILL_OPACITY, value.opacityPercent)
            putInt(KEY_PILL_CORNER, value.cornerRadiusDp)
            putBoolean(KEY_PILL_VERTICAL, value.isVertical)
            putBoolean(KEY_PILL_LABELS, value.showLabels)
        }?.apply()
    }

    /**
     * The stat list, as names joined by a separator.
     *
     * A `StringSet` would be the obvious `SharedPreferences` type and is wrong here: a set has no
     * order, and the order of these is the left-to-right order of the pill. Unrecognised names are
     * dropped; an empty result returns null so the caller falls back to the default set rather than
     * rendering a pill with nothing in it.
     */
    private fun readStats(p: SharedPreferences): List<HudStat>? {
        val raw = p.getString(KEY_PILL_STATS, null) ?: return null
        val parsed = raw.split(SEPARATOR)
            .mapNotNull { name -> HudStat.entries.firstOrNull { it.name == name } }
        return parsed.ifEmpty { null }
    }

    // ------------------------------------------------------------- floating button

    fun updateFloatingButton(transform: (FloatingButtonConfig) -> FloatingButtonConfig) {
        val updated = transform(buttonState.value).normalised()
        buttonState.value = updated
        writeButton(updated)
    }

    /** Drag-release from the overlay service, for the same reason as [updatePillPosition]. */
    fun updateButtonPosition(x: Int, y: Int) {
        val updated = buttonState.value.copy(x = x, y = y)
        buttonState.value = updated
        prefs?.edit()?.putInt(KEY_BUTTON_X, x)?.putInt(KEY_BUTTON_Y, y)?.apply()
    }

    /**
     * Records where the user let go of the panel's resize grip.
     *
     * Narrow for the same reason the two position writers are, and for one more: the panel is open
     * while this is written, and [updateFloatingButton] would rewrite the button's x and y from a
     * state the service may be a frame ahead of.
     */
    fun updatePanelWidth(widthDp: Int) {
        val clamped = widthDp.coerceIn(FloatingButtonConfig.PANEL_WIDTH_RANGE)
        val updated = buttonState.value.copy(panelWidthDp = clamped)
        buttonState.value = updated
        prefs?.edit()?.putInt(KEY_BUTTON_PANEL_WIDTH, clamped)?.apply()
    }

    /**
     * Switches the panel between its two shapes, from the panel itself.
     *
     * Narrow for exactly the reason [updatePanelWidth] is, and the case is the same one: the tile that
     * calls this is inside the open panel, so the button's x and y are whatever the service last dragged
     * them to, and [updateFloatingButton] would write them back from a state it may be a frame behind on.
     * The stored width is left alone on purpose — the split layout does not use it, and the user gets the
     * panel they had sized when they switch back.
     */
    fun updatePanelLayout(style: PanelLayoutStyle) {
        val updated = buttonState.value.copy(panelLayout = style)
        buttonState.value = updated
        prefs?.edit()?.putString(KEY_BUTTON_PANEL_LAYOUT, style.name)?.apply()
    }

    /**
     * Sets the quick-launch row's apps, in the order given.
     *
     * Narrow for the third time and the same reason [updatePanelWidth] and [updatePanelLayout] are: the
     * picker can be open while a game is running, which means the overlay service may be dragging the
     * button at the same moment, and a whole-config write from this screen would carry a stale x and y
     * back to disk over the position the finger just left.
     *
     * Normalised through the model rather than trusted from the caller, so the cap and the package-name
     * check apply to the write as well as to the read — the picker enforces both already, and a rule
     * enforced only in the UI is a rule the next caller does not get.
     */
    fun updateQuickApps(packages: List<String>) {
        val updated = buttonState.value.copy(quickAppPackages = packages).normalised()
        buttonState.value = updated
        prefs?.edit()
            ?.putString(KEY_BUTTON_QUICK_APPS, updated.quickAppPackages.joinToString(SEPARATOR))
            ?.apply()
    }

    private fun readButton(): FloatingButtonConfig {
        val p = prefs ?: return FloatingButtonConfig()
        val defaults = FloatingButtonConfig()
        return FloatingButtonConfig(
            show = p.getBoolean(KEY_BUTTON_SHOW, defaults.show),
            x = p.getInt(KEY_BUTTON_X, defaults.x),
            y = p.getInt(KEY_BUTTON_Y, defaults.y),
            sizeDp = p.getInt(KEY_BUTTON_SIZE, defaults.sizeDp),
            opacityPercent = p.getInt(KEY_BUTTON_OPACITY, defaults.opacityPercent),
            snapToEdge = p.getBoolean(KEY_BUTTON_SNAP, defaults.snapToEdge),
            idleOpacityPercent = p.getInt(KEY_BUTTON_IDLE_OPACITY, defaults.idleOpacityPercent),
            hapticFeedback = p.getBoolean(KEY_BUTTON_HAPTIC, defaults.hapticFeedback),
            panelWidthDp = p.getInt(KEY_BUTTON_PANEL_WIDTH, defaults.panelWidthDp),
            panelLayout = enumOrDefault(
                p.getString(KEY_BUTTON_PANEL_LAYOUT, null),
                PanelLayoutStyle.entries,
                defaults.panelLayout,
            ),
            showQuickApps = p.getBoolean(KEY_BUTTON_QUICK_APPS_SHOW, defaults.showQuickApps),
            quickAppPackages = readQuickApps(p),
        ).normalised()
    }

    /**
     * The quick-launch row's packages, in the order the user arranged them.
     *
     * A separated string and not a `StringSet`, for the reason [readNeverKill] gives and one more that
     * is specific to this list: a set has no order, and here the order *is* the setting — the row is a
     * set of positions under a thumb. `FloatingButtonConfig.normalised()` validates every entry and
     * caps the count, so a hand-edited file cannot put arbitrary text under an icon or forty icons over
     * a game. Absent reads back empty, never a default: which apps matter is not GameCore's guess.
     */
    private fun readQuickApps(p: SharedPreferences): List<String> =
        p.getString(KEY_BUTTON_QUICK_APPS, null)
            ?.split(SEPARATOR)
            ?.filter { it.isNotBlank() }
            .orEmpty()

    private fun writeButton(value: FloatingButtonConfig) {
        prefs?.edit()?.apply {
            putBoolean(KEY_BUTTON_SHOW, value.show)
            putInt(KEY_BUTTON_X, value.x)
            putInt(KEY_BUTTON_Y, value.y)
            putInt(KEY_BUTTON_SIZE, value.sizeDp)
            putInt(KEY_BUTTON_OPACITY, value.opacityPercent)
            putBoolean(KEY_BUTTON_SNAP, value.snapToEdge)
            putInt(KEY_BUTTON_IDLE_OPACITY, value.idleOpacityPercent)
            putBoolean(KEY_BUTTON_HAPTIC, value.hapticFeedback)
            putInt(KEY_BUTTON_PANEL_WIDTH, value.panelWidthDp)
            putString(KEY_BUTTON_PANEL_LAYOUT, value.panelLayout.name)
            putBoolean(KEY_BUTTON_QUICK_APPS_SHOW, value.showQuickApps)
            putString(KEY_BUTTON_QUICK_APPS, value.quickAppPackages.joinToString(SEPARATOR))
        }?.apply()
    }

    // ----------------------------------------------------------------- colour

    /**
     * Records a slider movement.
     *
     * Called on every change from both the full editor and the overlay panel's three quick
     * sliders, which is many times per drag. `SharedPreferences.apply()` is asynchronous and
     * batched by the framework, so this is a memory write plus an enqueued commit rather than a
     * disk write per frame — but note what it is *not* doing: it does not apply anything to the
     * display. Storing what the user chose and asking the settings provider to honour it are two
     * different operations, and only one of them can fail.
     */
    fun updateColorCorrection(transform: (ColorCorrection) -> ColorCorrection) {
        val updated = transform(colorState.value).normalised()
        colorState.value = updated
        writeColor(updated)
    }

    /** Replaces the stored values outright, for "load this preset into the sliders". */
    fun setColorCorrection(correction: ColorCorrection) = updateColorCorrection { correction }

    /**
     * Which saved preset the sliders currently reflect, or null for values the user built by hand.
     *
     * Set alongside [setColorCorrection] when a preset is loaded, and cleared by the editor as soon
     * as a slider moves — a chip still showing "Night" highlighted after the user has warmed it by
     * another 20% is a label that has stopped being true.
     */
    var activeColorPresetId: Long?
        get() = prefs?.getLong(KEY_ACTIVE_COLOR, -1L)?.takeIf { it > 0L }
        set(value) {
            prefs?.edit()?.putLong(KEY_ACTIVE_COLOR, value ?: -1L)?.apply()
        }

    private fun readColor(): ColorCorrection {
        val p = prefs ?: return ColorCorrection.NEUTRAL
        val defaults = ColorCorrection.NEUTRAL
        return ColorCorrection(
            redGain = p.getInt(KEY_COLOR_RED, defaults.redGain),
            greenGain = p.getInt(KEY_COLOR_GREEN, defaults.greenGain),
            blueGain = p.getInt(KEY_COLOR_BLUE, defaults.blueGain),
            gammaMode = enumOrDefault(
                p.getString(KEY_COLOR_GAMMA_MODE, null),
                GammaMode.entries,
                defaults.gammaMode,
            ),
            gamma = p.getInt(KEY_COLOR_GAMMA, defaults.gamma),
            redGamma = p.getInt(KEY_COLOR_RED_GAMMA, defaults.redGamma),
            greenGamma = p.getInt(KEY_COLOR_GREEN_GAMMA, defaults.greenGamma),
            blueGamma = p.getInt(KEY_COLOR_BLUE_GAMMA, defaults.blueGamma),
            saturation = p.getInt(KEY_COLOR_SATURATION, defaults.saturation),
            contrast = p.getInt(KEY_COLOR_CONTRAST, defaults.contrast),
            hueDegrees = p.getInt(KEY_COLOR_HUE, defaults.hueDegrees),
            brightnessOffset = p.getInt(KEY_COLOR_BRIGHTNESS, defaults.brightnessOffset),
            visionFilter = enumOrDefault(
                p.getString(KEY_COLOR_FILTER, null),
                ColorVisionFilter.entries,
                defaults.visionFilter,
            ),
            invertColors = p.getBoolean(KEY_COLOR_INVERT, defaults.invertColors),
        ).normalised()
    }

    private fun writeColor(value: ColorCorrection) {
        prefs?.edit()?.apply {
            putInt(KEY_COLOR_RED, value.redGain)
            putInt(KEY_COLOR_GREEN, value.greenGain)
            putInt(KEY_COLOR_BLUE, value.blueGain)
            putString(KEY_COLOR_GAMMA_MODE, value.gammaMode.name)
            putInt(KEY_COLOR_GAMMA, value.gamma)
            putInt(KEY_COLOR_RED_GAMMA, value.redGamma)
            putInt(KEY_COLOR_GREEN_GAMMA, value.greenGamma)
            putInt(KEY_COLOR_BLUE_GAMMA, value.blueGamma)
            putInt(KEY_COLOR_SATURATION, value.saturation)
            putInt(KEY_COLOR_CONTRAST, value.contrast)
            putInt(KEY_COLOR_HUE, value.hueDegrees)
            putInt(KEY_COLOR_BRIGHTNESS, value.brightnessOffset)
            putString(KEY_COLOR_FILTER, value.visionFilter.name)
            putBoolean(KEY_COLOR_INVERT, value.invertColors)
        }?.apply()
    }

    // ----------------------------------------------------------- crosshair + layout

    /**
     * The active crosshair preset and HUD layout, when no game profile is in charge.
     *
     * These are the "manual" selections — what the crosshair screen's preview toggle and the HUD
     * screen's overlay toggle use. A running profile overrides them for the duration of a session
     * and puts them back afterwards, which is why they are stored rather than derived: after a
     * session ends there has to be something to go back to.
     */
    var activeCrosshairPresetId: Long?
        get() = prefs?.getLong(KEY_ACTIVE_CROSSHAIR, -1L)?.takeIf { it > 0L }
        set(value) {
            prefs?.edit()?.putLong(KEY_ACTIVE_CROSSHAIR, value ?: -1L)?.apply()
        }

    var activeHudLayoutId: Long?
        get() = prefs?.getLong(KEY_ACTIVE_HUD, -1L)?.takeIf { it > 0L }
        set(value) {
            prefs?.edit()?.putLong(KEY_ACTIVE_HUD, value ?: -1L)?.apply()
        }

    var showCrosshairOverlay: Boolean
        get() = prefs?.getBoolean(KEY_SHOW_CROSSHAIR, false) ?: false
        set(value) {
            prefs?.edit()?.putBoolean(KEY_SHOW_CROSSHAIR, value)?.apply()
        }

    var showHudOverlay: Boolean
        get() = prefs?.getBoolean(KEY_SHOW_HUD, false) ?: false
        set(value) {
            prefs?.edit()?.putBoolean(KEY_SHOW_HUD, value)?.apply()
        }

    /**
     * The colours the user mixed in the crosshair picker, most recent first.
     *
     * A plain `var` like the two above rather than a fifth `MutableStateFlow`, because nothing observes
     * this: the crosshair screen reads it when it opens and after it adds one, and the overlay's
     * quick-pick row reads it when the panel opens. A flow would exist to push a change to a screen that
     * caused the change.
     *
     * Every rule about ordering, opacity and the cap lives in [CustomCrosshairColours], which both the
     * getter and the setter go through — so a list that got into the file by some other route still reads
     * back clean, and the same is true of one written by a caller that skipped [CustomCrosshairColours
     * .remember].
     */
    var customCrosshairColours: List<Int>
        get() = CustomCrosshairColours.decode(prefs?.getString(KEY_CUSTOM_CROSSHAIR_COLOURS, null))
        set(value) {
            prefs?.edit()
                ?.putString(KEY_CUSTOM_CROSSHAIR_COLOURS, CustomCrosshairColours.encode(value))
                ?.apply()
        }

    /**
     * Whether the user has already been asked to exempt GameCore from battery optimisation.
     *
     * Stored so §24B's prompt happens when overlay or session tracking is switched on and then not
     * again. A dialog that reappears every launch is how users learn to dismiss dialogs without
     * reading them.
     */
    var hasAskedBatteryExemption: Boolean
        get() = prefs?.getBoolean(KEY_ASKED_BATTERY, false) ?: false
        set(value) {
            prefs?.edit()?.putBoolean(KEY_ASKED_BATTERY, value)?.apply()
        }

    /**
     * Resets everything to defaults.
     *
     * Settings only — not the database. "Clear history" and "reset settings" are separate actions
     * in Settings because they destroy different things, and one button doing both would delete
     * session history for someone who wanted their accent colour back.
     */
    fun resetToDefaults() {
        prefs?.edit()?.clear()?.commit()
        settingsState.value = AppSettings()
        overlayState.value = OverlayConfig()
        buttonState.value = FloatingButtonConfig()
        colorState.value = ColorCorrection.NEUTRAL
    }

    // --------------------------------------------------------------------- internals

    private fun openPreferences(): SharedPreferences? = runCatching {
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            MasterKey.Builder(context, MASTER_KEY_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrNull()

    private fun <T : Enum<T>> enumOrDefault(name: String?, values: List<T>, fallback: T): T =
        values.firstOrNull { it.name == name } ?: fallback

    private companion object {
        const val FILE_NAME = "gamecore-settings"

        /** Distinct from the database key's alias: losing one must not take the other with it. */
        const val MASTER_KEY_ALIAS = "gamecore_prefs_master_key"

        /** `|` cannot appear in an enum constant name, so it needs no escaping. */
        const val SEPARATOR = "|"

        const val KEY_THEME = "theme"
        const val KEY_ACCENT = "accent"
        const val KEY_UI_SCALE = "ui_scale"
        const val KEY_DYNAMIC_COLOUR = "dynamic_colour"
        const val KEY_ALLOW_ELEVATED = "allow_elevated"
        const val KEY_AUTO_APPLY = "auto_apply"
        const val KEY_TRACK_SESSIONS = "track_sessions"
        const val KEY_DETECT_INTERVAL = "detect_interval"
        const val KEY_SAMPLE_INTERVAL = "sample_interval"
        const val KEY_MEASURE_LATENCY = "measure_latency"
        const val KEY_LATENCY_HOST = "latency_host"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_THERMAL_WARNINGS = "thermal_warnings"
        const val KEY_BACKGROUND_MONITOR = "background_monitor"
        const val KEY_CONFIRM_DISCARD = "confirm_discard"
        const val KEY_RECORDING_QUALITY = "recording_quality"
        const val KEY_SEEN_INTRO = "seen_intro"
        const val KEY_NEVER_KILL = "never_kill"
        const val KEY_TRIGGER_ENABLED = "trigger_enabled"
        const val KEY_TRIGGER_METHOD = "trigger_method"
        const val KEY_TRIGGER_ACTION = "trigger_action"
        const val KEY_TRIGGER_WINDOW = "trigger_window"
        const val KEY_TRIGGER_SHAKE = "trigger_shake"
        const val KEY_TRIGGER_PASS_THROUGH = "trigger_pass_through"
        const val KEY_AIMLAB_ENABLED = "aimlab_enabled"
        const val KEY_AIMLAB_ORIENTATION = "aimlab_orientation"
        const val KEY_AIMLAB_FOV = "aimlab_horizontal_fov"

        const val KEY_SHOW_PILL = "pill_show"
        const val KEY_PILL_X = "pill_x"
        const val KEY_PILL_Y = "pill_y"
        const val KEY_PILL_STATS = "pill_stats"
        const val KEY_PILL_INTERVAL = "pill_interval"
        const val KEY_PILL_TEXT_SIZE = "pill_text_size"
        const val KEY_PILL_OPACITY = "pill_opacity"
        const val KEY_PILL_CORNER = "pill_corner"
        const val KEY_PILL_VERTICAL = "pill_vertical"
        const val KEY_PILL_LABELS = "pill_labels"

        const val KEY_BUTTON_SHOW = "button_show"
        const val KEY_BUTTON_X = "button_x"
        const val KEY_BUTTON_Y = "button_y"
        const val KEY_BUTTON_SIZE = "button_size"
        const val KEY_BUTTON_OPACITY = "button_opacity"
        const val KEY_BUTTON_SNAP = "button_snap"
        const val KEY_BUTTON_IDLE_OPACITY = "button_idle_opacity"
        const val KEY_BUTTON_HAPTIC = "button_haptic"
        const val KEY_BUTTON_PANEL_WIDTH = "button_panel_width"
        const val KEY_BUTTON_PANEL_LAYOUT = "button_panel_layout"
        const val KEY_BUTTON_QUICK_APPS_SHOW = "button_quick_apps_show"
        const val KEY_BUTTON_QUICK_APPS = "button_quick_apps"

        const val KEY_ACTIVE_CROSSHAIR = "active_crosshair"
        const val KEY_ACTIVE_HUD = "active_hud"
        const val KEY_SHOW_CROSSHAIR = "show_crosshair"
        const val KEY_SHOW_HUD = "show_hud"
        const val KEY_CUSTOM_CROSSHAIR_COLOURS = "custom_crosshair_colours"
        const val KEY_ASKED_BATTERY = "asked_battery"

        const val KEY_ACTIVE_COLOR = "active_color"
        const val KEY_COLOR_RED = "color_red"
        const val KEY_COLOR_GREEN = "color_green"
        const val KEY_COLOR_BLUE = "color_blue"
        const val KEY_COLOR_GAMMA_MODE = "color_gamma_mode"
        const val KEY_COLOR_GAMMA = "color_gamma"
        const val KEY_COLOR_RED_GAMMA = "color_red_gamma"
        const val KEY_COLOR_GREEN_GAMMA = "color_green_gamma"
        const val KEY_COLOR_BLUE_GAMMA = "color_blue_gamma"
        const val KEY_COLOR_SATURATION = "color_saturation"
        const val KEY_COLOR_CONTRAST = "color_contrast"
        const val KEY_COLOR_HUE = "color_hue"
        const val KEY_COLOR_BRIGHTNESS = "color_brightness"
        const val KEY_COLOR_FILTER = "color_filter"
        const val KEY_COLOR_INVERT = "color_invert"
    }
}
