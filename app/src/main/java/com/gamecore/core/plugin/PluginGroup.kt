package com.gamecore.core.plugin

import com.gamecore.core.common.TextSanitizer

/**
 * One way of looking at the installed plugins.
 *
 * A group is deliberately not a permission and not a folder. It is a lens over plugin metadata, and a
 * plugin may be in as many of them as its manifest justifies: a theme plugin that also adds a Pill
 * module and a dashboard widget belongs to the `Theme`, `Game Pill` and `Dashboard` groups at the same
 * time, plus every permission group its manifest asks for. That many-to-many shape is the whole point
 * of the feature — a grouping model that forced one group per plugin would be a folder tree, and a
 * plugin that spans several surfaces cannot honestly be put in one folder.
 *
 * [automatic] groups are derived from the registry on every read and never stored. Their membership
 * follows the installed packages: uninstall the only theme plugin and the `Theme` group disappears.
 * Custom groups are the user's own and are stored in the encrypted plugin store; [pluginIds] is a set
 * rather than one id because a custom group can hold any number of plugins.
 *
 * The id is opaque and stable. Automatic ids begin with [PluginGrouping.AUTOMATIC_PREFIX]; custom ids
 * begin with [PluginGrouping.CUSTOM_PREFIX], so a stored group can never masquerade as a derived one
 * after a hand-edited preference file.
 */
data class PluginGroup(
    val id: String,
    val name: String,
    val pluginIds: Set<String> = emptySet(),
    val automatic: Boolean = false,
) {
    /** True when [pluginId] is a member. A single place for the membership question. */
    fun contains(pluginId: String): Boolean = pluginId in pluginIds

    /** How many installed plugins this group names, after [pluginIds] has been intersected by the caller. */
    val size: Int get() = pluginIds.size
}

/**
 * Derives the automatic groups and validates the custom ones.
 *
 * The derivation is pure and lives here rather than in the registry or a ViewModel because it is a
 * statement about what a group *means*: a permission group is "plugins that asked for this" and a
 * section group is "plugins that ship this surface". Keeping it in one pure function means the manager,
 * the detail screen and a future filter all see the same groups without each re-inventing the rules.
 *
 * Section groups cover the surfaces a user recognises from the rest of the app. Permissions cover the
 * capabilities a plugin asks for. A plugin can therefore be in several groups before the user has
 * created any of their own, which is what makes grouping useful on a fresh install rather than an
 * empty feature waiting to be filled in.
 */
object PluginGrouping {

    /** Prefix of every id this object derives. Never written to the store. */
    const val AUTOMATIC_PREFIX = "auto."

    /** Prefix required of every user-created group id. The store refuses anything else. */
    const val CUSTOM_PREFIX = "custom."

    /** A name long enough to be descriptive, short enough for a chip. */
    const val MAX_NAME_LENGTH = 40

    /** How many custom groups a user may keep. Bounds the encrypted preference value. */
    const val MAX_CUSTOM_GROUPS = 32

    /** How many plugins one custom group may hold. Bounds a single stored group. */
    const val MAX_MEMBERS = 128

    private val SECTION_GROUPS = listOf(
        PluginSection.THEME to "Themes",
        PluginSection.PILL to "Game Pill",
        PluginSection.WIDGETS to "Dashboard",
        PluginSection.RULES to "Automations",
        PluginSection.SCREENS to "Plugin screens",
        PluginSection.ICON to "App icons",
    )

    /**
     * Every group derived from the installed packages, in a stable order.
     *
     * Section groups come first because they name a surface a user can see ("Themes", "Dashboard"),
     * then permission groups in the enum's own order. Only groups with at least one member are
     * returned: an empty chip is a filter that can only produce an empty list, which is exactly the
     * dead control §32 warns about.
     */
    fun automatic(plugins: List<InstalledPlugin>): List<PluginGroup> {
        val result = ArrayList<PluginGroup>()

        SECTION_GROUPS.forEach { (section, label) ->
            val members = plugins
                .asSequence()
                .filter { section in it.manifest.declares }
                .map { it.id }
                .toList()
                .toSortedSet()
            if (members.isNotEmpty()) {
                result += PluginGroup(
                    id = AUTOMATIC_PREFIX + "section." + section.id,
                    name = label,
                    pluginIds = members,
                    automatic = true,
                )
            }
        }

        PluginPermission.entries.forEach { permission ->
            val members = plugins
                .asSequence()
                .filter { it.manifest.requests(permission) }
                .map { it.id }
                .toList()
                .toSortedSet()
            if (members.isNotEmpty()) {
                result += PluginGroup(
                    id = AUTOMATIC_PREFIX + "permission." + permission.id,
                    name = permissionGroupName(permission),
                    pluginIds = members,
                    automatic = true,
                )
            }
        }

        return result
    }

    /**
     * Automatic groups followed by the user's own, with custom membership narrowed to installed ids.
     *
     * Narrowing here rather than in the store is deliberate: a removed plugin's id is cleaned out of
     * the store on uninstall, but a stale id can still arrive from a backup written by an older build
     * or a hand-edited value. A group that named a plugin that is no longer installed would draw a
     * count the filtered list could never match, so the presentation never sees that id.
     */
    fun all(plugins: List<InstalledPlugin>, custom: List<PluginGroup>): List<PluginGroup> {
        val installed = plugins.asSequence().map { it.id }.toSet()
        val safeCustom = custom.map { group ->
            group.copy(pluginIds = group.pluginIds.filterTo(LinkedHashSet()) { it in installed })
        }
        return automatic(plugins) + safeCustom
    }

    /**
     * The name a user typed, or null when it cannot become a group.
     *
     * [TextSanitizer.sanitizeName] is the same treatment every other user-supplied name gets in this
     * app: it strips format characters, collapses whitespace and caps length. Returning null rather
     * than an empty name keeps "Create" from producing a chip with no label.
     */
    fun normaliseName(raw: String?): String? =
        TextSanitizer.sanitizeName(raw, MAX_NAME_LENGTH).trim().takeIf { it.isNotEmpty() }

    /**
     * A permission's short group label.
     *
     * The permission's own title is the language the consent screen uses. The parenthesised part is
     * dropped because "Change device settings (advanced)" does not fit a chip and the tier is already
     * visible on the plugin's own badge.
     */
    private fun permissionGroupName(permission: PluginPermission): String =
        permission.title.substringBefore(" (").trim().ifEmpty { permission.title }
}
