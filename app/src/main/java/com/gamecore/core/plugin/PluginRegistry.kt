package com.gamecore.core.plugin

import com.gamecore.core.plugin.content.AutomationRule
import com.gamecore.core.plugin.overlay.PluginOverlayModule
import com.gamecore.core.plugin.theme.PluginTheme
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
 */
data class InstalledPlugin(
    val manifest: PluginManifest,
    val sha256: String,
    val origin: PluginOrigin,
    val enabled: Boolean,
    val suspended: Boolean = false,
    val suspensionDetail: String? = null,
    val grant: PluginGrant,
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
 * All four flows are hot and survive the screens that read them: the active theme has to outlive the
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

    suspend fun setKillSwitch(on: Boolean)

    /** Clears a this-run suspension so a plugin the supervisor stopped can be tried again. */
    suspend fun clearSuspension(pluginId: String)

    /** The parsed sections of one installed plugin, re-read from its stored package. Null when absent. */
    suspend fun sectionsOf(pluginId: String): PluginSections?
}

/**
 * The theme currently in force, with the id of the plugin that supplied it.
 *
 * The id travels with the theme so the UI can name the plugin responsible for how the app looks — which
 * is the first thing a user needs when they want it to stop.
 */
data class ActiveTheme(val pluginId: String, val pluginName: String, val theme: PluginTheme)

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
)
