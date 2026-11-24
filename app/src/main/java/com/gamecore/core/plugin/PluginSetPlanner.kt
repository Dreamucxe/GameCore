package com.gamecore.core.plugin

/**
 * What a per-game plugin set would do if it were applied right now.
 *
 * Pure and separated from the applier for the same reason the health verdict is: the decision is the
 * part that can be wrong in a way nobody notices — enabling a quarantined plugin, quietly granting
 * nothing but leaving a plugin half-working, or claiming it applied a set that had no eligible
 * members. The applier only performs the plan and persists what it switched on.
 */
data class PluginSetPlan(
    val groupId: String,
    val groupName: String,
    val gamePackage: String?,
    val toEnable: List<String> = emptyList(),
    val alreadyOn: List<String> = emptyList(),
    val skipped: List<PluginSetSkip> = emptyList(),
) {
    val hasWork: Boolean get() = toEnable.isNotEmpty()

    val summary: String
        get() = when {
            toEnable.isEmpty() && alreadyOn.isEmpty() -> "The set had nothing it could switch on."
            toEnable.isEmpty() -> "Every eligible plugin in the set was already on."
            toEnable.size == 1 -> "Switched on 1 plugin from the set."
            else -> "Switched on ${toEnable.size} plugins from the set."
        }
}

/** One member a set deliberately did not touch, and why. */
data class PluginSetSkip(
    val pluginId: String,
    val name: String,
    val reason: PluginSetSkipReason,
)

/**
 * Why a member was left alone.
 *
 * [UNGRANTED] is the central safety rule: a set may switch a plugin on, never grant it a permission.
 * A plugin that asks for something the user has not granted is not started by a set, because the
 * resulting half-working plugin is worse than an honest skip.
 */
enum class PluginSetSkipReason(val label: String) {
    MISSING("No longer installed"),
    QUARANTINED("Quarantined — release it first"),
    SUSPENDED("Stopped for this run"),
    UNSUPPORTED("This build cannot run it"),
    UNGRANTED("Permissions are not granted"),
    GLOBAL_SWITCH("The global plugin switch is on"),
    ;

    val isSafetyBlock: Boolean
        get() = this == QUARANTINED || this == SUSPENDED || this == UNSUPPORTED || this == GLOBAL_SWITCH
}

/**
 * Turns a bound group and the installed list into the exact enable calls an applier should make.
 *
 * Members are sorted by plugin id so the same set always produces the same plan; the skipped list keeps
 * the group's own order, because that is the order the user built and the order the review should read.
 */
object PluginSetPlanner {

    fun plan(
        group: PluginGroup,
        installed: List<InstalledPlugin>,
        killSwitchOn: Boolean = false,
    ): PluginSetPlan {
        val byId = installed.associateBy { it.id }
        val toEnable = ArrayList<String>()
        val alreadyOn = ArrayList<String>()
        val skipped = ArrayList<PluginSetSkip>()

        group.pluginIds.forEach { id ->
            val plugin = byId[id]
            val name = plugin?.name?.takeIf { it.isNotBlank() } ?: id
            val reason = when {
                plugin == null -> PluginSetSkipReason.MISSING
                plugin.quarantined -> PluginSetSkipReason.QUARANTINED
                plugin.suspended -> PluginSetSkipReason.SUSPENDED
                !plugin.manifest.compatibility.isRunnable -> PluginSetSkipReason.UNSUPPORTED
                killSwitchOn -> PluginSetSkipReason.GLOBAL_SWITCH
                plugin.enabled -> null
                !isUngrantedSafe(plugin) -> PluginSetSkipReason.UNGRANTED
                else -> null
            }

            when {
                plugin == null || reason != null -> skipped += PluginSetSkip(id, name, reason!!)
                plugin.enabled -> alreadyOn += id
                else -> toEnable += id
            }
        }

        return PluginSetPlan(
            groupId = group.id,
            groupName = group.name,
            gamePackage = group.gamePackage,
            toEnable = toEnable.sorted(),
            alreadyOn = alreadyOn,
            skipped = skipped,
        )
    }

    /**
     * True when switching this plugin on cannot leave it running against a grant the user did not give.
     *
     * The check is over the manifest's requests, not over the grant alone, so a grant map that somehow
     * holds a permission the manifest did not ask for cannot make a plugin look ready. Advanced system
     * actions need their separate opt-in too, exactly as [PluginPermissionGate] requires on every call.
     */
    fun isUngrantedSafe(plugin: InstalledPlugin): Boolean =
        plugin.manifest.permissions.all { requested ->
            val permission = requested.permission
            plugin.grant.has(permission) &&
                (permission != PluginPermission.SYSTEM_ACTIONS || plugin.grant.advancedAccess)
        }
}
