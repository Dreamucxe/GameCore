package com.gamecore.core.plugin

/**
 * Everything the install/preview screen needs to let a user make an informed yes/no, computed
 * once from a parsed [PluginManifest] and the package's verified hash.
 *
 * The preview is the last human checkpoint before a plugin is trusted, so it is built to show
 * the things that actually carry risk rather than marketing: the permissions grouped by risk
 * tier (so a plugin asking for the one HIGH permission cannot bury it under a list of LOW
 * ones), the compatibility verdict (which *blocks* install when unsupported rather than
 * letting the user proceed into a broken load), the developer and version, and the hash the
 * install will be pinned to. It is a pure value — no Android, no I/O — so the exact grouping
 * and the "can this be installed" decision are unit-tested, not just visually checked.
 */
data class PluginInstallPlan(
    val id: String,
    val name: String,
    val developer: String,
    val versionName: String,
    val description: String,
    val changelog: String,
    val sha256: String,
    val compatibility: Compatibility,
    val declaredSections: Set<PluginSection>,
    val permissionsByTier: Map<RiskTier, List<PluginPermission>>,
    val highestTier: RiskTier?,
) {
    /**
     * May this plugin be installed from this preview? Only when the host can actually run it.
     * An unsupported plugin's install button is disabled; the preview still renders so the
     * user can see *why* (update GameCore, or the plugin is too old).
     */
    val canInstall: Boolean get() = compatibility.isRunnable

    /** True when the plugin requests the single HIGH-tier permission (device actions). */
    val requestsAdvancedAccess: Boolean
        get() = permissionsByTier[RiskTier.HIGH]?.isNotEmpty() == true

    companion object {
        fun from(manifest: PluginManifest, sha256: String): PluginInstallPlan {
            val perms = manifest.permissions.map { it.permission }.distinct()
            val byTier = perms.groupBy { it.tier }
                .toSortedMap(compareByDescending { it.ordinal }) // HIGH first
            val highest = perms.maxByOrNull { it.tier.ordinal }?.tier
            return PluginInstallPlan(
                id = manifest.id,
                name = manifest.name,
                developer = manifest.developer,
                versionName = manifest.versionName,
                description = manifest.description,
                changelog = manifest.changelog,
                sha256 = sha256,
                compatibility = PluginApi.compatibility(manifest.api, manifest.minHostVersion),
                declaredSections = manifest.declares,
                permissionsByTier = byTier,
                highestTier = highest,
            )
        }
    }
}
