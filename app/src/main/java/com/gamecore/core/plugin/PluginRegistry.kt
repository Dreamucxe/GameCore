package com.gamecore.core.plugin

import com.gamecore.core.plugin.content.AutomationRule
import com.gamecore.core.plugin.dashboard.PluginDashboardWidget
import com.gamecore.core.plugin.icon.PluginIconSpec
import com.gamecore.core.plugin.overlay.PluginOverlayModule
import com.gamecore.core.plugin.settings.PluginSettingsSchema
import com.gamecore.core.plugin.settings.SettingValue
import com.gamecore.core.plugin.theme.PluginTheme
import com.gamecore.core.plugin.ui.PluginScreen
import kotlinx.coroutines.flow.StateFlow

/**
 * Where an installed plugin came from.
 *
 * This is not cosmetic. [BUNDLED] plugins ship inside the APK, so their bytes are covered by the app's
 * own signature and they cannot be removed — only turned off. A [SIDELOADED] one arrived as a file the
 * user picked, so it can be deleted, and the manager says so. Nothing else in the system branches on
 * origin: a bundled plugin goes through the same loader, the same validator and the same permission gate
 * as one off the internet, because "we shipped it" is not a security property and treating it as one is
 * how a bundled example becomes the hole.
 */
enum class PluginOrigin(val label: String) {
    BUNDLED("Bundled with GameCore"),
    SIDELOADED("Installed from a file"),
}

/**
 * One plugin as the app currently holds it: what it says about itself, what the user granted it, and
 * whether it is running.
 *
 * [sha256] is the digest of the package the loader actually read, not a value out of the manifest — a
 * plugin cannot state its own hash. It is what the install screen shows and what the marketplace entry
 * is checked against.
 *
 * [enabled] and [suspended] are separate on purpose and both are stored, because they mean different
 * things to the user. Disabled is a choice they made (or the supervisor made for them after a crash
 * loop). Suspended is this run only — the plugin failed repeatedly and was stopped — and it carries
 * [suspensionDetail] so the manager can say what happened instead of showing a dead row.
 *
 * [installedAtMillis] is when the bytes on this device were written, not a date the plugin claims in
 * its manifest. For a sideloaded package that is its file's last-modified time — the moment it was
 * installed, or re-installed over an older copy. For a bundled plugin it is the APK's own timestamp,
 * because those plugins arrive with the app and have no file of their own. Zero means the platform
 * could not report a time; the manager sorts those last rather than inventing a date.
 */
data class InstalledPlugin(
    val manifest: PluginManifest,
    val sha256: String,
    val origin: PluginOrigin,
    val enabled: Boolean,
    val suspended: Boolean = false,
    val suspensionDetail: String? = null,
    val grant: PluginGrant,
    val installedAtMillis: Long = 0L,
) {
    val id: String get() = manifest.id

    val name: String get() = manifest.name

    /** Bundled plugins live in the APK, so there is nothing on disk to delete. */
    val canBeRemoved: Boolean get() = origin != PluginOrigin.BUNDLED

    /** True only when the plugin is both allowed to run and actually running. */
    val isActive: Boolean get() = enabled && !suspended && manifest.compatibility.isRunnable

    /**
     * The state the permission gate and the broker take.
     *
     * [systemKillSwitch] is passed in rather than stored on the plugin because it is one global switch,
     * not a per-plugin field — the registry holds it and hands it down, so a plugin row can never be
     * constructed with the switch silently off.
     */
    fun runtimeState(systemKillSwitch: Boolean): PluginRuntimeState = PluginRuntimeState.of(
        manifest = manifest,
        grant = grant,
        enabled = enabled,
        suspended = suspended,
        systemKillSwitch = systemKillSwitch,
        suspensionDetail = suspensionDetail,
    )
}

/**
 * What the app does with a package the user asked to install.
 *
 * `Rejected` carries the loader's own reason rather than a string, so the install screen can explain
 * the refusal in the loader's terms ("the package has no manifest") instead of "install failed".
 */
sealed interface InstallOutcome {
    data class Installed(val plugin: InstalledPlugin, val replaced: InstalledPlugin?) : InstallOutcome

    data class Rejected(val reason: PluginPackageLoader.Reason, val detail: String?) : InstallOutcome

    /** The package parsed but targets an API or host this build cannot run. */
    data class Incompatible(val compatibility: Compatibility) : InstallOutcome

    /** The bytes were readable but could not be stored — no space, or the plugin directory is gone. */
    data class StorageFailed(val detail: String) : InstallOutcome
}

/**
 * The installed-plugin registry: the one thing the UI, the broker and the theme layer all read from.
 *
 * An interface rather than the implementation because of what it keeps out of reach. A ViewModel that
 * holds this can enumerate plugins, flip a switch and remove one; it cannot obtain an `ElevatedShell`,
 * a grant store it could write directly, or the raw bytes of a package. Everything the presentation
 * layer is allowed to do to a plugin is one of the methods below, which is also the list a reviewer has
 * to check rather than the whole surface of a concrete class.
 *
 * All six flows are hot and survive the screens that read them: the active theme has to outlive the
 * manager screen that selected it, and the broker reads [installed] from a service with no UI at all.
 *
 * The suspending methods do their own I/O off the main thread. None of them throw: a failure comes back
 * as a value ([InstallOutcome]) or as no change to the flow, because an exception out of a plugin
 * operation would take down the host, which is the one outcome the whole design exists to prevent.
 */
interface PluginRegistry {

    /** Every plugin the app holds, bundled and sideloaded, in a stable display order. */
    val installed: StateFlow<List<InstalledPlugin>>

    /**
     * The theme of the one active plugin that declares a theme, or null for GameCore's own.
     *
     * One, not a merge: two plugins each setting a primary colour have no defined winner, so the
     * registry keeps a single selection and switching is explicit. Null is the always-available way
     * back, which is why ["reset to default"][resetToDefaultTheme] cannot fail.
     */
    val activeTheme: StateFlow<ActiveTheme?>

    /**
     * Every overlay module the running plugins are currently allowed to draw, newest grant last.
     *
     * A list where [activeTheme] is a single selection, and the difference is not an inconsistency: two
     * themes conflict — there is no defined winner between two plugins each setting a primary colour —
     * whereas two small readouts sitting in different corners of the screen simply coexist. So modules
     * compose and themes arbitrate.
     *
     * The list is already capped at what the overlay layer can physically draw, and already filtered by
     * everything that could make a module not allowed: the kill switch, the plugin being disabled,
     * suspended or incompatible, the absence of a declared `pill` section, a missing
     * [PluginPermission.CREATE_OVERLAY] in the *grant* rather than the manifest, a module whose stats
     * need a data scope the user did not approve, and a `pill.json` that no longer parses. Every one of
     * those degrades to the module simply not being in this list — there is no error state a plugin can
     * put the overlay into, because the overlay never asks a plugin anything; it reads this.
     *
     * Consumed by exactly one branch in the overlay service, which is the whole of the core's knowledge
     * that plugin modules exist.
     */
    val activeOverlayModules: StateFlow<List<ActiveOverlayModule>>

    /**
     * Every dashboard widget the running plugins may draw on the home surface right now, in registry
     * order and already capped at the dashboard's `PluginDashboardCodec.MAX_WIDGETS`.
     *
     * [activeOverlayModules] for the home screen rather than the in-game one, filtered by the same
     * chain for the same reasons: the kill switch, the plugin being disabled, suspended or
     * incompatible, the absence of a declared `widgets` section, a missing
     * [PluginPermission.ADD_DASHBOARD_WIDGETS] in the *grant* rather than the manifest, a widget
     * whose `stat` nodes need a data scope the user did not approve, and a `widget.json` that no
     * longer parses. Every one of those degrades to the widget simply not being in this list — the
     * dashboard never asks a plugin anything; it reads this.
     *
     * A flat list of (plugin, widget) pairs in which one plugin may appear more than once, because
     * the dashboard is a scrolling grid rather than the overlay's three fixed slots: widgets compose
     * the way modules do, and the cap is this list's length across every plugin, not a per-plugin
     * count. Extras past the cap are dropped in registry order and never parsed.
     */
    val activeDashboardWidgets: StateFlow<List<ActiveDashboardWidget>>

    /**
     * Every plugin automation currently allowed to run, grouped by the plugin that declared it.
     *
     * Unlike [activeOverlayModules] and [activeDashboardWidgets] this is not a surface the user looks
     * at — it is what an automation runner reads to know which rules to weigh against the host's
     * trigger/stat feed. One entry per plugin, not per rule, because every rule a plugin ships
     * dispatches through that plugin's own grant: [ActivePluginAutomation.state] travels with the
     * rules so the runner hands each fired action to the broker under the right runtime state, and
     * the broker re-checks permission on every one. A plugin is here only when it is active, declares
     * a `rules` section, holds [PluginPermission.RUN_AUTOMATIONS] in its *grant*, and has at least
     * one rule that passes `AutomationEngine` validation. Nothing here runs anything — deciding is
     * the engine's job and dispatching is the broker's; this flow only says who is allowed to.
     */
    val activeAutomations: StateFlow<List<ActivePluginAutomation>>

    /**
     * The global off switch. True means no plugin runs, whatever its own state says.
     *
     * Checked first by [PluginPermissionGate], so this is a real kill switch and not a UI filter: with
     * it on, a plugin that is enabled and granted still gets [DenyReason.SYSTEM_KILL_SWITCH] from every
     * call. It exists so a user whose device is misbehaving has one toggle that is guaranteed to stop
     * plugin involvement without having to work out which plugin to blame.
     */
    val killSwitchOn: StateFlow<Boolean>

    /** True once the first load from disk has finished, so the UI can tell "none" from "not yet read". */
    val isLoaded: StateFlow<Boolean>

    /** Reads bundled and sideloaded plugins from disk. Safe to call more than once. */
    suspend fun refresh()

    /**
     * Validates and stores a package.
     *
     * The bytes are capped before this is reached ([PluginPackageLoader.MAX_PACKAGE_BYTES]), and this
     * does not grant anything: a freshly installed plugin holds an empty grant and is disabled until
     * the user grants it on the detail screen. Installing is deliberately not consenting.
     */
    suspend fun install(bytes: ByteArray, origin: PluginOrigin = PluginOrigin.SIDELOADED): InstallOutcome

    /** Deletes a sideloaded plugin and its grant, its namespace keys and its theme selection. No-op for bundled. */
    suspend fun remove(pluginId: String): Boolean

    suspend fun setEnabled(pluginId: String, enabled: Boolean)

    /**
     * Stores the user's grant for a plugin.
     *
     * The registry does not check the grant against the manifest here — [PluginPermissionGate] does that
     * on every call, so a grant that over-reaches (a permission the manifest never requested) is inert
     * rather than trusted. Writing it is recorded, not acted on.
     */
    suspend fun grant(pluginId: String, grant: PluginGrant)

    /** Selects the theme of [pluginId], or clears the selection when it is null or has no theme. */
    suspend fun setActiveTheme(pluginId: String?)

    /**
     * Returns the app to its own theme.
     *
     * Separate from `setActiveTheme(null)` as a named operation because it is the escape hatch the whole
     * theming feature is only safe with: it is reachable from a screen a plugin can never restyle, it
     * writes nothing it could fail to write, and it does not need the offending plugin to still parse.
     */
    suspend fun resetToDefaultTheme()

    /**
     * The launcher icon a plugin is asking for, or null.
     *
     * The same filter chain as [activeTheme] — the plugin must be installed, runnable, enabled, not
     * suspended, in force with the kill switch off, declare an `icon` section, and hold `change_icon`
     * in its grant — and for the same reason: a null here is always reachable, needs nothing from the
     * plugin, and is the app's own icon.
     *
     * Unlike a theme, this is only a *request*. The launcher entry is changed by
     * `AppIconApplier`, which the UI calls once, on an explicit tap; the flow says which slots are on
     * offer, not which one is installed.
     */
    val activeIcon: StateFlow<ActiveIcon?>

    /**
     * Every installed plugin that is allowed to offer an icon right now.
     *
     * The picker needs the offers before the selection, the way a list needs its rows before its
     * chosen row: a user cannot accept an icon they were never shown.
     */
    val iconOffers: StateFlow<List<ActiveIcon>>

    /** Accepts [pluginId]'s icon request. Clears itself if the request cannot be honoured. */
    suspend fun setActiveIcon(pluginId: String?)

    /** Drops the plugin's icon request, returning the app to the user's own slot choice. */
    suspend fun resetToPluginIcon()

    /**
     * The image a plugin attached for a pinned shortcut, or null.
     *
     * Read on demand rather than carried in [ActiveIcon]: the bytes are up to
     * `PluginIconArtwork.MAX_BYTES` and only the one action that pins a shortcut needs them, so a
     * `StateFlow` that re-emitted them on every registry publish would be a megabyte of churn.
     */
    suspend fun iconArtwork(pluginId: String): ByteArray?

    suspend fun setKillSwitch(on: Boolean)

    /** Clears a this-run suspension so a plugin the supervisor stopped can be tried again. */
    suspend fun clearSuspension(pluginId: String)

    /** The parsed sections of one installed plugin, re-read from its stored package. Null when absent. */
    suspend fun sectionsOf(pluginId: String): PluginSections?

    /**
     * Reads one of [pluginId]'s own settings, named [id], decoded against the caller's [schema].
     *
     * The ViewModel holds the parsed [PluginSettingsSchema] (it got it from [sectionsOf]) and passes it
     * back in; the registry never hands the ViewModel the settings store or the typed surface directly,
     * because a screen that could reach either could write a plugin's own namespace without the funnel
     * that gates, rate-limits, clamps and audits every such call. This is the one narrow door instead:
     * the registry owns the surface and builds the runtime state, and the ViewModel gets back only a
     * typed [ApiResult].
     *
     * The outcomes are the typed surface's own: an [ApiResult.Ok] carrying the stored value or the
     * definition's default, an [ApiResult.Error] for an unknown [id], and the matching [ApiResult] for a
     * denied (Storage withheld) or rate-limited call. A [pluginId] that names nothing installed is an
     * [ApiResult.Error] — the same shape an unknown setting already has, so a stale screen degrades to a
     * value rather than a throw.
     */
    suspend fun readPluginSetting(
        pluginId: String,
        schema: PluginSettingsSchema,
        id: String,
    ): ApiResult<SettingValue>

    /**
     * Stores [value] for [pluginId]'s setting [id], coerced against [schema], returning the value that
     * was actually stored — a clamped number, a truncated string — rather than the one that was asked
     * for.
     *
     * [readPluginSetting]'s narrow door for writes, with the typed surface's write outcomes: an
     * [ApiResult.Ok] carrying the coerced value, an [ApiResult.Error] for a wrong-typed value or an
     * unknown [id], and the matching [ApiResult] for a denied or rate-limited call. An unknown [pluginId]
     * is an [ApiResult.Error].
     */
    suspend fun setPluginSetting(
        pluginId: String,
        schema: PluginSettingsSchema,
        id: String,
        value: SettingValue,
    ): ApiResult<SettingValue>

    /**
     * The data scopes [pluginId] has actually been granted right now, as the gate sees them.
     *
     * The registry-level scope backstop for surfaces that resolve a plugin's live figures per id — the
     * plugin screen route resolves each `stat`/`series` only when the [DataScope] it sits under is in
     * this set, which is the same per-id prefilter [activeOverlayModules] and [activeDashboardWidgets]
     * already apply to whole modules and widgets. Derived from the stored grant through
     * [PluginPermissionGate] rather than read off the grant directly, so the kill switch, a disabled or
     * suspended plugin, and a scope the user never approved all collapse to that scope's absence here —
     * the one place the gate lives. An unknown, removed, or inactive plugin yields the empty set, which
     * resolves every live figure to absent rather than to a real reading.
     */
    fun grantedScopesFor(pluginId: String): Set<DataScope>

    /**
     * The user's automation history, newest first, at most [limit] lines, surfaced to the host screen a
     * plugin holding the [DataScope.AUTOMATION_HISTORY] read scope is permitted to have GameCore show.
     *
     * The records are the production automation trail ([PluginAutomationHistoryStore]) — the real runs,
     * not a fixture — and they are GameCore's own record of what ran on the device, already sanitised at
     * the audit boundary so no secret, token, or payload reaches here. They are *host-owned and
     * host-rendered*: this returns them to GameCore's own screen, never to plugin code, which is what
     * keeps a plugin from reading, keeping, or forwarding another plugin's line even as the user reviews
     * the whole trail through it. The scope is a lens the user pointed, not a copy handed over.
     *
     * Gated through [PluginPermissionGate] exactly as [grantedScopesFor] is, so the kill switch and a
     * disabled, suspended, or never-granted plugin all land on [ApiResult.Error] rather than an empty
     * list — the screen can tell "nothing has run yet" from "not allowed to look". An unknown [pluginId]
     * is the same [ApiResult.Error].
     */
    suspend fun automationHistory(pluginId: String, limit: Int = 100): ApiResult<List<AutomationHistoryRecord>>

    /**
     * Erase the user's whole automation history, as the "clear" control on that same host screen invokes
     * it — a user action, confirmed on screen, gated by the identical [DataScope.AUTOMATION_HISTORY]
     * check so a plugin without the granted scope cannot reach it. It clears every line, not only the
     * requesting plugin's, because the screen reviews the whole trail; a plugin cannot clear history
     * silently, since it has no way to drive this without the user's on-screen confirmation. Returns
     * [ApiResult.Ok] once the log is cleared, or [ApiResult.Error] for a denied call or unknown [pluginId].
     */
    suspend fun clearAutomationHistory(pluginId: String): ApiResult<Unit>
}

/**
 * The theme currently in force, with the id of the plugin that supplied it.
 *
 * The id travels with the theme so the UI can name the plugin responsible for how the app looks — which
 * is the first thing a user needs when they want it to stop.
 */
data class ActiveTheme(val pluginId: String, val pluginName: String, val theme: PluginTheme)

/**
 * One plugin's launcher-icon request, with the plugin that made it.
 *
 * The id and name travel with the request for [ActiveTheme]'s reason, and one more: the picker has to
 * show *which plugin* is offering an icon before the user accepts it, and "the icon" alone does not
 * say that.
 *
 * [spec] is the validated `icon.json` — a slot from the closed set the app ships, and optionally a
 * path to artwork for a pinned shortcut. Neither of those is a launcher icon the package supplied,
 * because Android has no such thing; [com.gamecore.core.plugin.icon.AppIconSlot] explains what the
 * system will and will not accept.
 */
data class ActiveIcon(
    val pluginId: String,
    val pluginName: String,
    val spec: PluginIconSpec,
)

/**
 * One overlay module in force, with the plugin that supplied it.
 *
 * The id and name travel with the module for the same reason they travel with [ActiveTheme]: the first
 * thing a user needs when something unexpected is sitting on top of their game is which plugin put it
 * there. They are also what the overlay service audits against, so a module that misbehaves is
 * attributable to a plugin rather than to "the overlay".
 */
data class ActiveOverlayModule(
    val pluginId: String,
    val pluginName: String,
    val module: PluginOverlayModule,
)

/**
 * One dashboard widget in force, with the plugin that supplied it.
 *
 * [ActiveOverlayModule] for the home surface: the id and name travel with the widget for the same
 * reason they travel with a module — the first thing a user needs when an unexpected tile appears on
 * their dashboard is which plugin put it there, and it is what the dashboard host attributes a
 * misbehaving widget to rather than to "the dashboard". A plugin appears in more than one of these
 * when it ships more than one widget; each pairing stands on its own.
 */
data class ActiveDashboardWidget(
    val pluginId: String,
    val pluginName: String,
    val widget: PluginDashboardWidget,
)

/**
 * The automations one plugin is allowed to run, with the runtime state they dispatch under.
 *
 * Where [ActiveOverlayModule] and [ActiveDashboardWidget] each carry one drawable thing, this
 * carries a plugin's whole validated rule set, because automations are weighed as a group against
 * the same trigger and the actions they fire all go through one grant. [state] is held rather than
 * re-derived at dispatch time so a runner cannot evaluate a rule under one plugin's state and fire
 * its action under another's: the state, the name and the rules are one indivisible record of "this
 * plugin, these rules, this authority". [rules] has already been filtered to those that validate, so
 * a consumer never has to decide whether a rule is well-formed — only whether its trigger fired.
 */
data class ActivePluginAutomation(
    val pluginId: String,
    val pluginName: String,
    val state: PluginRuntimeState,
    val rules: List<AutomationRule>,
)

/**
 * The declared content of one plugin, already validated.
 *
 * Parsed on demand rather than held in [InstalledPlugin], because the manager screen needs a name and a
 * switch for every plugin while only the detail screen and the runtime need the content — and keeping
 * every plugin's parsed sections in memory for the sake of a list row is how a plugin system starts
 * costing the host its own memory budget.
 */
data class PluginSections(
    val theme: PluginTheme? = null,
    val rules: List<AutomationRule> = emptyList(),
    val overlayModule: PluginOverlayModule? = null,
    val settings: PluginSettingsSchema? = null,
    val screens: List<PluginScreen> = emptyList(),
)
