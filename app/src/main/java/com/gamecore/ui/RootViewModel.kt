package com.gamecore.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.BuildConfig
import com.gamecore.core.common.valueOrNull
import com.gamecore.core.model.AppSettings
import com.gamecore.core.plugin.PluginRegistry
import com.gamecore.core.plugin.theme.PluginTheme
import com.gamecore.core.whatsnew.ReleaseNote
import com.gamecore.core.whatsnew.WhatsNewRegistry
import com.gamecore.core.whatsnew.entriesSince
import com.gamecore.core.whatsnew.shouldShowCard
import com.gamecore.data.icon.AppIconController
import com.gamecore.data.plugin.GlassControl
import com.gamecore.data.plugin.GlassMaterialStore
import com.gamecore.data.preferences.SecurePreferenceStore
import com.gamecore.data.repository.GameProfileRepository
import com.gamecore.data.repository.SessionRepository
import com.gamecore.domain.monitoring.PerformanceMonitor
import com.gamecore.domain.schedule.AppearanceScheduler
import com.gamecore.domain.setup.SetupSignals
import com.gamecore.domain.setup.WizardEntry
import com.gamecore.domain.setup.decideEntry
import com.gamecore.themeengine.AuroraThemeRepository
import com.gamecore.themeengine.ThemeEnginePlugin
import com.gamecore.themeengine.readingsOf
import com.gamecore.ui.theme.GlitchConditions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The appearance settings for the shell that wraps every screen, and the one first-run decision the shell
 * has to make before it draws anything.
 *
 * A composable cannot be injected, so something has to hold the preference store on [GameCoreRoot]'s
 * behalf. It observes rather than reads once, which matters more here than it looks: the theme, the accent
 * and the UI scale are edited on the Settings screen, and that screen is *inside* the theme it is changing.
 * A one-off read would leave the user's new accent showing only after the next launch.
 *
 * No preload call. `MainActivity` runs the startup pass on a coroutine before the first frame, and that is
 * what takes the keystore unwrap off the main thread; asking for it again here would either race with it or
 * pay for it twice.
 */
@HiltViewModel
class RootViewModel @Inject constructor(
    private val preferences: SecurePreferenceStore,
    profiles: GameProfileRepository,
    sessions: SessionRepository,
    plugins: PluginRegistry,
    performance: PerformanceMonitor,
    private val themeEngine: ThemeEnginePlugin,
    private val auroraRepository: AuroraThemeRepository,
    private val appIcons: AppIconController,
    private val glassMaterials: GlassMaterialStore,
    private val appearanceScheduler: AppearanceScheduler,
) : ViewModel() {

    /**
     * The engine's current colour, as an ARGB int, or null when there is nothing to paint.
     *
     * Deliberately the *target* vector and not the smoothed one the canvas draws. The shell's colours
     * changing is a whole-app recomposition; following the sixty-a-second painted vector would recompose
     * every screen to chase a fading transition. The target moves only when a reading crosses a mode
     * edge, which is about once a second at most.
     *
     * It is exposed whether or not the override is on: [GameCoreTheme] decides that, so the switch takes
     * effect on the next frame without this flow having to be rebuilt around it.
     */
    val engineTint: StateFlow<Int?> = themeEngine.state
        .map { state ->
            val (r, g, b) = state.resolution.vector.rgb
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val settings: StateFlow<AppSettings> = preferences.settings

    /**
     * The plugin theme in force, or null for GameCore's own — the shell's second appearance input.
     *
     * Passed straight through from the registry rather than resolved here, because the registry has
     * already done the only hard part: a theme reaches this flow only from a plugin that is installed,
     * enabled, not suspended, compatible, holds `change_ui` in its *granted* permissions, and whose
     * `theme.json` parsed. Everything that fails any of those is null, and null is GameCore's own theme.
     * So there is no failure case for this flow to represent and no state in which the app has no theme.
     *
     * Not folded into [settings]: the plugin theme is not a user preference and is not stored with them.
     * Keeping it a separate input is what lets [GameCoreTheme][com.gamecore.ui.theme.GameCoreTheme] stay a
     * pure function of both — and what makes "use GameCore's own theme" a change to one of them only.
     */
    val pluginTheme: StateFlow<PluginTheme?> = plugins.activeTheme
        .map { it?.theme }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * The schema-4 glass material in force, with the Control Center's own value already applied — or
     * null when the app is not wearing glass.
     *
     * Passed straight through from [GlassMaterialStore] for the same reason [pluginTheme] is passed
     * straight through from the registry: the store has already done the hard part, which is
     * resolving a theme's block, the plugin's own intensity setting and the user's slider into one
     * value. The shell does two things with it and no arithmetic: it hands the fraction to
     * [com.gamecore.ui.theme.GameCoreTheme] so every panel in the app is drawn from the material, and
     * it draws [com.gamecore.ui.theme.GlassBackdrop] from the material itself.
     *
     * Eager for the same reason as everything else here: a glass theme's panels have to be glass on
     * the first frame they are drawn, not one frame later.
     */
    val glass: StateFlow<GlassControl?> = glassMaterials.state

    private val mutableGlitchConditions = MutableStateFlow(GlitchConditions.None)

    /**
     * The device state the active theme's glitch block is allowed to react to — or [GlitchConditions.None].
     *
     * Eager only in the sense that the collector starts with the ViewModel; the sampler behind it is
     * only subscribed while a theme that actually asked for reactive motion is in force. That matters
     * because [PerformanceMonitor.snapshots] is shared with `WhileSubscribed`, so an always-on collector
     * here would keep the CPU sampler running for every user in every session, including the ones who
     * never install a theme. `collectLatest` cancels the inner subscription the moment the active theme
     * stops caring, and the loop stops with it.
     *
     * Two readings and no more — see [GlitchConditions] for why. Both are optional: the first sample of
     * a session has no CPU delta to report and the platform may hide its thermal status, and "no reading"
     * is deliberately treated as "nothing wrong" rather than as an overload.
     */
    val glitchConditions: StateFlow<GlitchConditions> = mutableGlitchConditions.asStateFlow()

    /**
     * Starts the Theme Engine for the life of the app, not for the life of a screen.
     *
     * This is what lets the engine's colour reach every screen: the shell holds the only instance, feeds
     * it the monitor's readings, and never stops it. A screen that wants to calibrate the engine now asks
     * it for a stand-in reading instead of starting its own — see [ThemeEnginePlugin.setMetricOverride].
     *
     * Started eagerly rather than on first use because the engine's render loop is what smooths a colour
     * change; starting it when the user first opens Settings → Theme Engine would make the app's first
     * recolour a jump instead of a transition.
     */
    init {
        // The root exists for the whole UI session, so it is the right owner for the scheduler's
        // lifetime. Starting here rather than in Application keeps the heavy gaming/profile graph
        // off the process-start path; the first resolution lands before the user can reach a screen
        // that would disagree with it.
        appearanceScheduler.start()
        themeEngine.start(
            viewModelScope,
            performance.snapshots.map { readingsOf(it).metrics },
            auroraRepository.activePreset.value,
        )
        // One pass per launch, before anything the user can tap: drop a plugin icon request that no
        // longer resolves (the plugin was disabled or uninstalled while the app was closed) and make
        // sure the launcher is wearing the slot the user chose. Idempotent — the applier returns
        // immediately when the requested slot is already in force — so a launch that changed nothing
        // costs one binder read.
        viewModelScope.launch { appIcons.reconcile() }
        viewModelScope.launch {
            auroraRepository.activePreset.collect { themeEngine.setPreset(it) }
        }
        // Reacts to load only while a glitch theme with `hardwareReactive` on is active. The inner
        // collect is cancelled by `collectLatest` when that stops being true, which releases the
        // shared sampler rather than leaving it spinning behind a theme that no longer uses it.
        viewModelScope.launch {
            plugins.activeTheme
                .map { it?.theme?.glitch }
                .distinctUntilChanged()
                .collectLatest { glitch ->
                    if (glitch == null || !glitch.hardwareReactive) {
                        mutableGlitchConditions.value = GlitchConditions.None
                        return@collectLatest
                    }
                    performance.snapshots.collect { snapshot ->
                        mutableGlitchConditions.value = GlitchConditions(
                            cpuPercent = snapshot?.cpu?.overallPercent?.valueOrNull,
                            thermalLevel = snapshot?.thermal?.status?.valueOrNull?.level ?: 0,
                        )
                    }
                }
        }
    }

    /**
     * How the setup wizard should present itself this launch (spec §A1), or null until the facts it reads
     * have loaded. [GameCoreRoot] acts only on [WizardEntry.FullWizard] — a fresh install, taken straight
     * into the wizard; the Home card owns [WizardEntry.HomeCard], and [WizardEntry.Nothing] is nothing to do.
     *
     * Null is the initial value on purpose: it is the difference between "not decided yet" and "decided:
     * nothing", and the root routes on neither of those. The three sources the decision reads all emit a
     * real value on subscription — `settings` is seeded synchronously from the store, and the two Room flows
     * emit their first query result — so [combine]'s first emission is already a settled decision rather than
     * a guess over half-loaded state. That is what stops an upgrader with existing profiles from being
     * mistaken for a fresh install for one frame and having their Home screen taken over.
     */
    val launchEntry: StateFlow<WizardEntry?> = combine(
        preferences.settings,
        profiles.profiles,
        sessions.sessionCount,
    ) { settings, saved, sessionCount ->
        decideEntry(
            SetupSignals(
                hasCompletedVersion = settings.setupCompletedVersion.takeIf { it > 0 },
                currentVersion = BuildConfig.VERSION_CODE,
                dismissed = settings.setupDismissed,
                hasProfiles = saved.isNotEmpty(),
                hasSessions = sessionCount > 0,
            ),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * The releases to announce on this launch, newest first, or empty when there is nothing to show — the
     * one-time What's New card (spec §3.7 Updates) reads this and nothing else.
     *
     * First-install is not re-derived here: it is exactly [launchEntry]'s fresh-install verdict, so
     * [shouldShowCard] is told `isFirstInstall = true` only when the wizard decision is
     * [WizardEntry.FullWizard]. A fresh install has nothing to catch up on, so the card is suppressed — which
     * is also what stops it firing over first-run setup. While the decision is still loading ([launchEntry]
     * is null) this stays empty for the same reason the wizard waits: not-yet-decided is not first-install,
     * and the card must not flash over an upgrader's Home before their profiles have been read.
     */
    val whatsNewLaunch: StateFlow<List<ReleaseNote>> = combine(
        preferences.settings,
        launchEntry,
    ) { settings, entry ->
        val lastSeen = settings.lastSeenWhatsNewVersionCode
        val isFirstInstall = entry == WizardEntry.FullWizard
        if (entry != null && shouldShowCard(lastSeen, BuildConfig.VERSION_CODE, isFirstInstall)) {
            entriesSince(WhatsNewRegistry.releases, lastSeen)
        } else {
            emptyList()
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Records that the user has seen this version's What's New card, so it never returns (spec §3.7). Writes
     * `lastSeenWhatsNewVersionCode = BuildConfig.VERSION_CODE`; that flips [whatsNewLaunch] to empty on the
     * spot, because the store updates its `settings` flow synchronously, and keeps it empty on every later
     * launch of this version.
     */
    fun markWhatsNewSeen() {
        preferences.updateSettings { it.copy(lastSeenWhatsNewVersionCode = BuildConfig.VERSION_CODE) }
    }
}
