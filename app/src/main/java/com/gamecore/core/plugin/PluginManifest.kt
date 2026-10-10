package com.gamecore.core.plugin

/**
 * The one slice of GameCore's own data a [PluginPermission.READ_APPROVED_DATA]
 * grant opens.
 *
 * Reading "your data" is not one permission but four apertures, declared
 * individually so a plugin that only draws a session summary never gains sight of
 * the device's thermal sensors. Each is a *read* of GameCore's own records — there
 * is no scope for another app's data because no such read exists anywhere in the
 * plugin API.
 *
 * The id is what travels in the manifest; an unknown scope string is rejected, like
 * an unknown permission.
 */
enum class DataScope(val id: String, val label: String, val description: String) {
    SESSIONS("sessions", "Sessions", "Your recorded play and training sessions."),
    GAMES("games", "Games", "The games you've added and their profiles."),
    DEVICE_STATS("device_stats", "Device stats", "Live performance figures like frame rate and CPU load."),
    THERMALS("thermals", "Thermals", "Temperature and battery readings.");

    companion object {
        private val byId = entries.associateBy { it.id }
        fun fromId(id: String): DataScope? = byId[id.trim().lowercase()]
    }
}

/**
 * A permission a plugin requests, together with any scopes narrowing it.
 *
 * [scopes] is meaningful only for the two scoped permissions — [PluginPermission.READ_APPROVED_DATA]
 * (which [DataScope]) and [PluginPermission.MODIFY_SETTINGS] (which settings areas,
 * carried as free strings validated against the settings catalog downstream). For
 * every other permission it is empty, and the loader rejects a scope list attached
 * to a permission that has no scopes rather than silently dropping it.
 */
data class RequestedPermission(
    val permission: PluginPermission,
    val scopes: Set<String> = emptySet(),
)

/**
 * A fully parsed, validated plugin manifest.
 *
 * Every field here has already survived [PluginManifestCodec]: strings are
 * sanitised and length-capped, the id matches the package grammar, the version is a
 * positive integer, permissions resolve to known enum cases, and unknown top-level
 * keys have already caused a rejection. A `PluginManifest` instance is therefore a
 * value the rest of the system may trust — the trust boundary is the codec, not the
 * consumers.
 *
 * This is the manifest *only*. The theme, pill, screens, profiles, rules and Aim Lab
 * content each have their own model and their own validation pass; the manifest
 * names which of those sections the package claims to carry (via [declares]) so the
 * loader knows what to look for and can reject a section present on disk but
 * undeclared, or declared but absent.
 */
data class PluginManifest(
    val id: String,
    val name: String,
    val version: Int,
    val versionName: String,
    val api: Int,
    val minHostVersion: Int,
    val developer: String,
    val description: String,
    val permissions: List<RequestedPermission>,
    val declares: Set<PluginSection>,
    val changelog: String,
) {
    /** The compatibility verdict for this manifest against the running build. */
    val compatibility: Compatibility get() = PluginApi.compatibility(api, minHostVersion)

    /** The highest risk tier among requested permissions, for the install summary. */
    val highestTier: RiskTier
        get() = permissions.maxOfOrNull { it.permission.tier } ?: RiskTier.LOW

    fun requests(permission: PluginPermission): Boolean =
        permissions.any { it.permission == permission }
}

/**
 * The named sections a `.gcplugin` can carry, each a top-level entry in the package.
 * A section the manifest does not declare must not be present in the zip, and a
 * declared section must be — the loader enforces both so a package's contents and
 * its claims cannot diverge.
 */
enum class PluginSection(val id: String, val entryPrefix: String) {
    THEME("theme", "theme.json"),
    PILL("pill", "pill.json"),
    SCREENS("screens", "screens/"),
    PROFILES("profiles", "profiles/"),
    RULES("rules", "rules/"),
    AIMLAB("aimlab", "aimlab/"),
    STATS("stats", "stats/");

    companion object {
        private val byId = entries.associateBy { it.id }
        fun fromId(id: String): PluginSection? = byId[id.trim().lowercase()]
    }
}
