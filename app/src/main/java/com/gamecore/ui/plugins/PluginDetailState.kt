package com.gamecore.ui.plugins

import com.gamecore.core.plugin.InstalledPlugin
import com.gamecore.core.plugin.PluginGrant
import com.gamecore.core.plugin.PluginInstallPlan
import com.gamecore.core.plugin.PluginPermission
import com.gamecore.core.plugin.RiskTier
import com.gamecore.core.plugin.settings.PluginSettingsSchema
import com.gamecore.core.plugin.settings.SettingValue
import com.gamecore.core.plugin.theme.PluginTheme
import com.gamecore.core.plugin.ui.PluginScreen

/**
 * What the plugin detail screen shows, and the four rules that decide what the user is allowed to do
 * with it.
 *
 * The rules live here, as pure top-level functions over a [PluginGrant], rather than inside the
 * composable or the ViewModel, for one reason: this is the screen where a permission is taken away,
 * and the consequences of getting its arithmetic wrong are not cosmetic. Dropping a permission while
 * leaving its scopes behind would leave an aperture in the stored grant that the user believes they
 * closed; adding a scope to a permission that is not granted would write a grant the gate reads as
 * half-open. Both are a device test away from being noticed and a JVM test away from being impossible,
 * so they are written as functions a test can call with a hand-built grant and no Android at all.
 *
 * Two shapes in this state exist because of §24's honesty rule and are worth naming:
 *
 *  1. [draft] and [saved] are both held. [saved] is whatever came back from the registry; [draft] is
 *     the user's unsaved edit. Nothing here ever writes [saved] — a screen that moved its own switch
 *     and showed the result would be reporting the tap rather than the stored grant, which is the one
 *     lie a consent screen cannot afford.
 *  2. [enabled] and [suspended] are flags read back from the registry for the same reason, not
 *     booleans this screen owns. A plugin the supervisor stopped mid-session has to read as stopped
 *     even while its switch says the user wants it on, which is why they are two fields.
 *
 * [plugin] is kept whole rather than flattened into strings because the screen renders the manifest's
 * own words for every permission ([PluginPermission.title], [PluginPermission.rationale],
 * [PluginPermission.whatItTouches]) and must not hard-code a description beside a permission id —
 * a label that drifts from the capability it names is consent obtained for the wrong thing.
 */
data class PluginDetailUiState(
    val isLoading: Boolean = true,
    /** The plugin is gone: a bad argument, or it was removed from the manager while this screen was open. */
    val notFound: Boolean = false,
    val plugin: InstalledPlugin? = null,
    val plan: PluginInstallPlan? = null,
    val draft: PluginGrant? = null,
    val saved: PluginGrant? = null,
    val enabled: Boolean = false,
    val suspended: Boolean = false,
    val suspensionDetail: String? = null,
    /** True when this plugin is the one currently supplying the app's theme. */
    val isThemeSource: Boolean = false,
    /**
     * True when this plugin holds the automation-history read scope in its *live* granted set — the
     * same gate [com.gamecore.core.plugin.PluginRegistry.automationHistory] applies at the call. The
     * detail screen offers its entry point to the host's automation-history screen only when this is
     * set, so the row never promises a screen the host would answer with "not allowed", and it drops
     * the moment the plugin is disabled, suspended or caught by the kill switch.
     */
    val hasAutomationHistoryAccess: Boolean = false,
    val themePreview: PluginTheme? = null,
    val ruleCount: Int = 0,
    /** Declarative screens this plugin declares, parsed on demand; empty until the sections load. */
    val screens: List<PluginScreen> = emptyList(),
    /**
     * The plugin's declared settings schema, parsed on demand. Null until the sections load, and null
     * for a plugin that declares no settings or whose settings.json no longer parses — the one guard the
     * screen uses to decide whether to draw the settings card at all.
     */
    val settingsSchema: PluginSettingsSchema? = null,
    /**
     * The current value of each declared setting, by id. Populated once the schema loads; a setting the
     * gate refuses to read (no Storage grant) falls back to its schema default here, so a control always
     * has an honest value to show rather than a blank.
     */
    val settingValues: Map<String, SettingValue> = emptyMap(),
    val message: String? = null,
    val pendingRemoval: Boolean = false,
) {
    /**
     * Has the user changed something they have not stored?
     *
     * Equality over the whole grant rather than a dirty flag set by each toggle: a user who turns a
     * permission on and straight back off has changed nothing, and a Save button that stays lit after
     * that is asking them to store a no-op.
     */
    val hasUnsavedChanges: Boolean
        get() {
            val edited = draft ?: return false
            val stored = saved ?: return false
            return edited != stored
        }

    /**
     * True when the draft grants a HIGH-tier permission while the advanced opt-in is off — the one
     * state in which a granted permission does nothing at all.
     *
     * Surfaced rather than silently corrected. [com.gamecore.core.plugin.PluginPermissionGate] denies
     * such a call with `ADVANCED_NOT_ENABLED`, so the grant is real and inert, and a screen that hid
     * that would leave the user believing they had enabled something they had not.
     */
    val requiresAdvancedAccess: Boolean
        get() {
            val currentPlan = plan ?: return false
            val edited = draft ?: return false
            return hasInertHighTierGrant(currentPlan, edited)
        }

    /**
     * Is the plugin in a state where running it would mean running exactly what this screen shows?
     *
     * The three-clause rule, read off this state's own plan and grants — see the top-level
     * [com.gamecore.ui.plugins.canEnable] for each clause and the reason behind it. Null plan or null
     * grants mean nothing has loaded yet, which is not a yes.
     *
     * Null-safe rather than `!!`: this property is read during the frame between a registry emission
     * and the sections load, and a consent screen that threw while deciding what to tell the user
     * about their permissions would be the worst possible place for a crash.
     */
    val canEnable: Boolean
        get() {
            val currentPlan = plan ?: return false
            val edited = draft ?: return false
            val stored = saved ?: return false
            // Qualified deliberately: the file-level rule shares this property's name, and spelling it
            // out in full is what makes it unmistakable that the shared logic is being called rather
            // than this getter recursing into itself.
            return com.gamecore.ui.plugins.canEnable(currentPlan, edited, stored)
        }

    /**
     * May the Enable switch be moved on?
     *
     * Two clauses, and deliberately **not** the same question as [canEnable]. An incompatible plugin
     * cannot run at all, and a granted HIGH-tier permission with the advanced opt-in off is a
     * contradiction the user has to resolve one way or the other first — both are states the switch
     * has no honest "on" position for, so it is off with the reason beside it.
     *
     * An unsaved edit is pointedly absent. [canEnable] does include it, because a plugin running
     * against a grant the user has edited but not stored is not running what the screen shows — but
     * the way out of that is to *store it*, which enabling does on the user's behalf and says so.
     * Blocking the switch instead would make the one-tap path the rule exists to protect unreachable.
     */
    val canTurnOn: Boolean
        get() {
            val currentPlan = plan ?: return false
            if (draft == null) return false
            return currentPlan.canInstall && !requiresAdvancedAccess
        }

    /**
     * May the user actually change these settings, or are the controls shown read-only?
     *
     * Every settings write is funnelled through [PluginPermission.STORE_DATA] at the gate, so without
     * that permission in the *saved* grant every set is denied, and the honest thing is to render the
     * controls disabled over the schema's defaults with a note saying what to grant — not to hide them,
     * and not to let a tap look like it stuck. The draft is pointedly not consulted: a permission the
     * user toggled on but has not stored is not what the gate checks, so a control it would still refuse
     * must not look live. A null saved grant means nothing has loaded yet, which is not a yes.
     */
    val settingsEditable: Boolean
        get() = saved?.has(PluginPermission.STORE_DATA) == true
}

/**
 * Every permission the manifest asked for, in the plan's own HIGH-first order.
 *
 * Read off [PluginInstallPlan.permissionsByTier] rather than off the manifest, because the plan is
 * what the screen renders and a count taken from a second source is a count that can disagree with
 * the list beside it. [PluginInstallPlan.from] has already de-duplicated, so this does not.
 */
internal fun requestedPermissions(plan: PluginInstallPlan): List<PluginPermission> =
    plan.permissionsByTier.values.flatten()

/**
 * Does the draft hold a HIGH-tier grant that the advanced opt-in is not backing?
 *
 * Only permissions the manifest actually requested count: a grant map carrying a permission that was
 * never requested is already inert at the gate (`NOT_REQUESTED`), and letting one of those block the
 * Enable switch would be a dead end the user has no control on this screen to clear.
 */
internal fun hasInertHighTierGrant(plan: PluginInstallPlan, draft: PluginGrant): Boolean {
    if (draft.advancedAccess) return false
    return plan.permissionsByTier[RiskTier.HIGH].orEmpty().any { draft.has(it) }
}

/**
 * Grants or revokes one permission.
 *
 * Turning a permission **off drops its scopes with it**, because the scopes live inside the map value
 * and a revoke that kept them would re-open the same apertures the moment the permission was granted
 * again — a user who withheld "thermals", revoked the whole read, then granted the read back would
 * silently be handing over thermals.
 *
 * Turning it **on grants no scopes**. A newly granted scoped permission is open with every aperture
 * shut, which the gate reads as deny for every scoped call; the user opens them one at a time. That is
 * the opt-in rule applied one level down, and it is why this never copies the manifest's requested
 * scope list into the grant.
 *
 * [PluginGrant.advancedAccess] is deliberately untouched in both directions. It is a separate opt-in
 * with its own switch and its own explanation, and a function that cleared it as a side effect of a
 * permission toggle would move a control the user was not looking at.
 */
internal fun togglePermission(
    draft: PluginGrant,
    permission: PluginPermission,
    on: Boolean,
): PluginGrant = when {
    on && draft.has(permission) -> draft
    on -> draft.copy(granted = draft.granted + (permission to emptySet()))
    else -> draft.copy(granted = draft.granted - permission)
}

/**
 * Opens or closes one scope inside a permission that is already granted.
 *
 * A scope on an ungranted permission is refused rather than stored: the gate checks the permission
 * before the scope, so such an entry could never authorise anything, and writing it would leave a
 * grant whose contents do not match the switches that produced it. The permission's key stays in the
 * map when its last scope closes — "granted, no aperture open" is a real state with a real meaning
 * (deny every scoped call) and is not the same thing as ungranted.
 *
 * The scope is normalised to lowercase here because [PluginGrant.hasScope] lowercases what it is
 * asked about; a scope stored with the manifest's capitalisation would never match a lookup.
 */
internal fun toggleScope(
    draft: PluginGrant,
    permission: PluginPermission,
    scope: String,
    on: Boolean,
): PluginGrant {
    val id = scope.trim().lowercase()
    if (id.isEmpty()) return draft
    val current = draft.granted[permission] ?: return draft
    val updated = if (on) current + id else current - id
    if (updated == current) return draft
    return draft.copy(granted = draft.granted + (permission to updated))
}

/**
 * One line saying how much of what the plugin asked for it has.
 *
 * Counts requested permissions, not granted scopes, and says so in those words. A summary that read
 * "limited access" or "mostly granted" would be this screen editorialising about a decision the user
 * is in the middle of making; a fraction is checkable against the switches above it.
 */
internal fun grantSummary(plan: PluginInstallPlan, draft: PluginGrant): String {
    val requested = requestedPermissions(plan)
    if (requested.isEmpty()) return "This plugin requests no permissions."
    val granted = requested.count { draft.has(it) }
    val noun = if (requested.size == 1) "permission" else "permissions"
    return "$granted of ${requested.size} requested $noun granted"
}

/**
 * Is this plugin running under exactly the grant the screen is showing? The rule, in one place.
 *
 * Not "may the switch be tapped" — that is [PluginDetailUiState.canTurnOn], which leaves out the
 * unsaved clause because enabling stores the draft first. This is the stricter question the "Running"
 * card answers out loud, and all three clauses have a different kind of reason behind them:
 *
 *  1. **Compatible.** [PluginInstallPlan.canInstall] is false when this build cannot run the package
 *     at all, and enabling one of those would start a plugin whose sections this app has no code to
 *     interpret. Nothing on this screen clears it; a newer GameCore does.
 *  2. **Nothing unsaved.** `draft == saved` — the switches on screen and the stored grant agree. A
 *     plugin that ran while an edit was pending would be running under the grant the user had before
 *     they touched anything, and the screen would be showing the other one. That is the single state
 *     this function exists to let the screen admit to rather than paper over.
 *  3. **No inert HIGH-tier grant.** A granted HIGH-tier permission with
 *     [PluginGrant.advancedAccess] off is refused on every call by the gate, so starting the plugin
 *     in that state would mean the user had approved device actions, seen no error, and got nothing.
 *     The screen does not resolve it silently: both ways out — turn the advanced opt-in on, or
 *     withhold the permission — are the user's, and it says which two they are.
 *
 * Deliberately not a clause: suspension. A suspended plugin is a this-run failure with its own
 * "Try again", and refusing to let the user turn it off and on again would take away the control they
 * most likely came here for.
 */
internal fun canEnable(plan: PluginInstallPlan, draft: PluginGrant, saved: PluginGrant): Boolean =
    plan.canInstall && draft == saved && !hasInertHighTierGrant(plan, draft)
