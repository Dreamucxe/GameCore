package com.gamecore.core.plugin

/**
 * What the user actually approved for one installed plugin.
 *
 * A plugin's manifest *requests* permissions; a [PluginGrant] records which of those the
 * user *granted*, with the scopes they approved and — for the one high-risk permission —
 * whether the separate "Advanced plugin access" gate was also opened. A request is not a
 * grant: a plugin may ship asking for [PluginPermission.SYSTEM_ACTIONS] and run for months
 * with it ungranted, every broker call denied, until the user opts in. The grant, not the
 * manifest, is the authority every API call is checked against.
 *
 * Scopes are stored per permission and are a *subset* of what the manifest requested — the
 * consent screen can let the user approve "sessions" while withholding "thermals" even
 * though the plugin asked for both. An empty scope set on a scoped permission means the
 * permission is granted but no aperture is open, which the gate treats as deny for every
 * scoped call.
 */
data class PluginGrant(
    val pluginId: String,
    val granted: Map<PluginPermission, Set<String>>,
    /** The extra opt-in that [PluginPermission.SYSTEM_ACTIONS] needs on top of its grant. */
    val advancedAccess: Boolean = false,
) {
    fun has(permission: PluginPermission): Boolean = granted.containsKey(permission)

    fun scopes(permission: PluginPermission): Set<String> = granted[permission].orEmpty()

    fun hasScope(permission: PluginPermission, scope: String): Boolean =
        granted[permission]?.contains(scope.trim().lowercase()) == true

    companion object {
        fun none(pluginId: String) = PluginGrant(pluginId, emptyMap(), advancedAccess = false)
    }
}

/**
 * The outcome of a permission check. Never a boolean passed around bare: a denial carries
 * *why*, so the audit log and the developer tools can show the real reason rather than a
 * generic "denied", and so a caller cannot accidentally treat "not granted" and "scope
 * withheld" and "plugin suspended" as the same thing.
 */
sealed interface PermissionDecision {
    data object Allow : PermissionDecision
    data class Deny(val reason: DenyReason, val detail: String = "") : PermissionDecision

    val isAllowed: Boolean get() = this is Allow
}

enum class DenyReason {
    NOT_REQUESTED,       // the manifest never asked for it — a plugin trying to exceed itself
    NOT_GRANTED,         // requested, but the user did not grant it
    SCOPE_WITHHELD,      // granted, but not for this scope
    ADVANCED_NOT_ENABLED,// SYSTEM_ACTIONS granted but the extra opt-in is off
    PLUGIN_DISABLED,     // the plugin is disabled
    PLUGIN_SUSPENDED,    // auto-suspended after rate-limit or repeated failures
    SYSTEM_KILL_SWITCH,  // the user revoked all plugin system access
}

/**
 * The single checkpoint every plugin API call passes through before it does anything.
 *
 * This is the enforcement half of the permission system; [PluginPermission] and
 * [PluginGrant] are the vocabulary, this is the guard that speaks it. The rule the whole
 * platform leans on is here and nowhere else: **a plugin may only do what its grant allows,
 * checked on every call, with no path that trusts the manifest, the plugin's own claims, or
 * a previous check.** There is deliberately no "already validated" fast path — the cost of
 * a map lookup is nothing next to the cost of a plugin acting outside its grant because some
 * caller forgot to re-check.
 *
 * The gate is pure policy. It holds no Android types and does no IO; it is handed a
 * [PluginRuntimeState] snapshot (enabled/suspended, kill-switch, the grant) and returns a
 * decision. Persisting grants and runtime state is [PluginGrantStore]'s job, injected
 * above; keeping them apart means every rule below is a JVM unit test with a hand-built
 * state, and the Android-backed store is the only thing that needs a device.
 */
class PluginPermissionGate {

    /**
     * May [state]'s plugin exercise [permission] (optionally for [scope])?
     *
     * Order matters: the global kill switch and the plugin's own enabled/suspended status
     * are checked before the grant, because a revoked system or a suspended plugin denies
     * everything regardless of what was once granted. Then the manifest request (a plugin
     * cannot be granted what it never asked for — a grant map that somehow lists an
     * un-requested permission is itself a bug, caught here), then the grant, then — for the
     * high-risk permission — the advanced opt-in, then the scope.
     */
    fun check(
        state: PluginRuntimeState,
        permission: PluginPermission,
        scope: String? = null,
    ): PermissionDecision {
        if (state.systemKillSwitch) {
            return PermissionDecision.Deny(DenyReason.SYSTEM_KILL_SWITCH)
        }
        if (!state.enabled) {
            return PermissionDecision.Deny(DenyReason.PLUGIN_DISABLED)
        }
        if (state.suspended) {
            return PermissionDecision.Deny(DenyReason.PLUGIN_SUSPENDED, state.suspensionDetail.orEmpty())
        }
        if (!state.manifestRequests(permission)) {
            return PermissionDecision.Deny(DenyReason.NOT_REQUESTED, permission.id)
        }
        if (!state.grant.has(permission)) {
            return PermissionDecision.Deny(DenyReason.NOT_GRANTED, permission.id)
        }
        if (permission == PluginPermission.SYSTEM_ACTIONS && !state.grant.advancedAccess) {
            return PermissionDecision.Deny(DenyReason.ADVANCED_NOT_ENABLED, permission.id)
        }
        if (scope != null) {
            if (!state.grant.hasScope(permission, scope)) {
                return PermissionDecision.Deny(DenyReason.SCOPE_WITHHELD, scope)
            }
        }
        return PermissionDecision.Allow
    }

    /** Convenience for call sites that only need the boolean after logging the decision. */
    fun isAllowed(state: PluginRuntimeState, permission: PluginPermission, scope: String? = null): Boolean =
        check(state, permission, scope).isAllowed
}

/**
 * An immutable snapshot of everything the gate needs about one plugin at the moment of a
 * call: its granted permissions, which permissions its manifest requested (so a grant can
 * never exceed the request), and the three kill-states. Snapshotting rather than holding
 * live references means a check cannot race a concurrent enable/disable mid-decision.
 */
data class PluginRuntimeState(
    val grant: PluginGrant,
    val requestedPermissions: Set<PluginPermission>,
    val enabled: Boolean,
    val suspended: Boolean,
    val systemKillSwitch: Boolean,
    val suspensionDetail: String? = null,
) {
    fun manifestRequests(permission: PluginPermission): Boolean = permission in requestedPermissions

    companion object {
        /**
         * Build a state from a manifest + grant + flags, the usual path.
         *
         * [suspensionDetail] stays nullable because it is threaded straight from
         * [InstalledPlugin.suspensionDetail], which is null for a plugin that has never
         * been suspended — the ordinary case. The gate is what turns an absent detail
         * into the non-null detail string a [PermissionDecision.Deny] carries.
         */
        fun of(
            manifest: PluginManifest,
            grant: PluginGrant,
            enabled: Boolean,
            suspended: Boolean,
            systemKillSwitch: Boolean,
            suspensionDetail: String? = null,
        ) = PluginRuntimeState(
            grant = grant,
            requestedPermissions = manifest.permissions.map { it.permission }.toSet(),
            enabled = enabled,
            suspended = suspended,
            systemKillSwitch = systemKillSwitch,
            suspensionDetail = suspensionDetail,
        )
    }
}

/**
 * Derives the storage namespace a plugin is confined to.
 *
 * Each plugin gets its own key space; one plugin can neither read nor overwrite another's
 * stored values, because the gate prefixes every key with a namespace derived from the
 * plugin id and refuses a key that tries to escape it. This is the storage analogue of the
 * path-safety check in the loader: the plugin supplies a key, we treat it as untrusted, and
 * we confine it. The namespace is derived, not stored by the plugin, so a plugin cannot
 * choose another plugin's namespace.
 */
object PluginNamespace {

    const val MAX_KEY_LENGTH = 128
    private const val PREFIX = "plugin."

    /** The storage prefix for a plugin's own key space. */
    fun of(pluginId: String): String = "$PREFIX$pluginId."

    /**
     * The fully-qualified storage key for a plugin's [key], or null if the key is unsafe.
     *
     * A plugin's key must be a simple name: letters, digits, `_`, `-`, `.`, within the
     * length cap, and with no `.` or `..` segment that could, on a dotted-key store, be
     * read as traversal. Anything else is rejected rather than sanitised — the plugin gets
     * a clear failure, not a silently rewritten key that might collide.
     */
    fun qualify(pluginId: String, key: String?): String? {
        if (key.isNullOrEmpty() || key.length > MAX_KEY_LENGTH) return null
        if (!KEY_GRAMMAR.matches(key)) return null
        if (key.split('.').any { it == "." || it == ".." || it.isEmpty() }) return null
        return of(pluginId) + key
    }

    /** Does a fully-qualified key belong to this plugin's namespace? Guards cross-plugin reads. */
    fun belongsTo(pluginId: String, qualifiedKey: String): Boolean =
        qualifiedKey.startsWith(of(pluginId))

    private val KEY_GRAMMAR = Regex("^[A-Za-z0-9_.\\-]+$")
}

/**
 * The persistence port for grants, runtime flags, and per-plugin namespaced values. The app
 * binds this to an [EncryptedSharedPreferences]-backed implementation; tests bind an
 * in-memory fake. Defining it as an interface here keeps [PluginPermissionGate] and the rest
 * of the policy free of Android, and means the encryption lives in exactly one place.
 */
interface PluginGrantStore {
    fun grantFor(pluginId: String): PluginGrant
    fun saveGrant(grant: PluginGrant)
    fun clearGrant(pluginId: String)

    fun isEnabled(pluginId: String): Boolean
    fun setEnabled(pluginId: String, enabled: Boolean)

    fun isSuspended(pluginId: String): Boolean
    fun suspend(pluginId: String, detail: String)
    fun clearSuspension(pluginId: String)

    /** The global "Revoke all plugin system access" state. */
    fun isSystemKillSwitchOn(): Boolean
    fun setSystemKillSwitch(on: Boolean)

    /** Namespaced value storage. Keys must already be qualified via [PluginNamespace.qualify]. */
    fun readValue(qualifiedKey: String): String?
    fun writeValue(qualifiedKey: String, value: String?)
    fun clearNamespace(pluginId: String)
}
