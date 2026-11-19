package com.gamecore.ui.themelibrary

import com.gamecore.core.plugin.InstalledPlugin
import com.gamecore.core.plugin.PluginPermission
import com.gamecore.core.plugin.PluginSection
import com.gamecore.core.plugin.theme.PluginTheme

/**
 * One theme the app can wear, as the library draws it.
 *
 * The theme is the plugin's *offer*, not the active selection. Whether it may be applied is the two
 * flags beside it: a theme from a plugin that is off, or that has never been granted Change the look,
 * is still worth previewing — that is how a user decides whether to grant it — but applying it is a
 * decision with a visible consequence, which is why [requiresConfirmation] is derived here rather than
 * discovered after the tap.
 */
data class ThemeLibraryEntry(
    val pluginId: String,
    val pluginName: String,
    val developer: String,
    val theme: PluginTheme,
    val isActive: Boolean,
    val needsEnable: Boolean,
    val needsGrant: Boolean,
    /**
     * Why this theme cannot be applied at all, or null when it can.
     *
     * Incompatibility, quarantine and suspension are the three the library cannot repair: none of them
     * is a switch this screen owns. They are stated on the card and the action becomes a route to
     * Plugins rather than a button that would silently fail.
     */
    val blockedReason: String? = null,
) {
    /** True when applying would change the plugin's own state, so the user must say yes first. */
    val requiresConfirmation: Boolean
        get() = blockedReason == null && (needsEnable || needsGrant)

    val canApply: Boolean get() = blockedReason == null && !isActive

    val fontLabel: String get() = theme.font.displayName

    val modeLabel: String get() = if (theme.dark) "Dark" else "Light"

    /** One sentence for the confirmation dialog, naming exactly what the tap will change. */
    val grantSentence: String
        get() = ThemeLibraryLogic.grantSentence(needsEnable, needsGrant, pluginName)
}

/** The four lenses over the library. All of them are in-memory; none is a stored preference. */
enum class ThemeLibraryFilter(val label: String) {
    ALL("All"),
    DARK("Dark"),
    LIGHT("Light"),
    ACTIVE("Active"),
}

/**
 * The library's screen state.
 *
 * [entries] is already filtered and sorted; [totalCount] is the unfiltered library size, so the header
 * can say "12 of 254" without the screen re-counting what the ViewModel already knows.
 */
data class ThemeLibraryUiState(
    val isLoading: Boolean = true,
    val entries: List<ThemeLibraryEntry> = emptyList(),
    val totalCount: Int = 0,
    val query: String = "",
    val filter: ThemeLibraryFilter = ThemeLibraryFilter.ALL,
    val activeThemeName: String? = null,
    val killSwitchOn: Boolean = false,
    val pendingApply: ThemeApplyConfirmation? = null,
    val message: String? = null,
    /** Whether [message] reports a refusal rather than a result. */
    val messageIsWarning: Boolean = false,
) {
    val isFiltered: Boolean get() = query.isNotBlank() || filter != ThemeLibraryFilter.ALL

    /** True when the library has themes but this lens hides them all. */
    val isFilteredEmpty: Boolean get() = !isLoading && entries.isEmpty() && totalCount > 0
}

/** A held apply waiting on the user's explicit yes, because it would enable or grant the plugin. */
data class ThemeApplyConfirmation(
    val pluginId: String,
    val themeName: String,
    val pluginName: String,
    val needsEnable: Boolean,
    val needsGrant: Boolean,
)

/**
 * The pure half of the library: which themes a lens shows, and in what order.
 *
 * Kept out of the ViewModel so a JVM test can pin the two decisions that are easy to get subtly wrong:
 * the active theme must survive every filter (it is the answer to "what am I wearing?"), and search has
 * to match the words a user actually types — the theme's name, the plugin's name, the developer, the
 * font, and the mode — rather than only the first field.
 */
object ThemeLibraryLogic {

    /**
     * The sentence a confirmation uses, in one place so the card and the dialog cannot describe the
     * same operation two ways.
     */
    fun grantSentence(needsEnable: Boolean, needsGrant: Boolean, pluginName: String): String {
        val parts = buildList {
            if (needsEnable) add("turn on \"$pluginName\"")
            if (needsGrant) add("grant it Change the look")
        }
        return when (parts.size) {
            0 -> "Nothing about the plugin changes."
            1 -> "This will ${parts.first()}."
            else -> "This will ${parts[0]} and ${parts[1]}."
        }
    }

    fun entriesFor(
        installed: List<InstalledPlugin>,
        themes: Map<String, PluginTheme?>,
        activePluginId: String?,
    ): List<ThemeLibraryEntry> = installed
        .asSequence()
        .filter { PluginSection.THEME in it.manifest.declares }
        .mapNotNull { plugin ->
            val theme = themes[plugin.id] ?: return@mapNotNull null
            ThemeLibraryEntry(
                pluginId = plugin.id,
                pluginName = plugin.name,
                developer = plugin.manifest.developer,
                theme = theme,
                isActive = plugin.id == activePluginId,
                needsEnable = !plugin.enabled,
                needsGrant = !plugin.grant.has(PluginPermission.CHANGE_UI),
                blockedReason = blockedReason(plugin),
            )
        }
        .sortedWith(
            compareByDescending<ThemeLibraryEntry> { it.isActive }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.theme.name },
        )
        .toList()

    fun visible(
        entries: List<ThemeLibraryEntry>,
        query: String,
        filter: ThemeLibraryFilter,
    ): List<ThemeLibraryEntry> {
        val needle = query.trim()
        return entries.filter { entry ->
            lensMatches(entry, filter) && (needle.isEmpty() || matches(entry, needle))
        }
    }

    fun matches(entry: ThemeLibraryEntry, query: String): Boolean {
        val needle = query.trim()
        if (needle.isEmpty()) return true
        return listOf(
            entry.theme.name,
            entry.pluginName,
            entry.developer,
            entry.pluginId,
            entry.fontLabel,
            entry.modeLabel,
        ).any { it.contains(needle, ignoreCase = true) }
    }

    private fun lensMatches(entry: ThemeLibraryEntry, filter: ThemeLibraryFilter): Boolean = when (filter) {
        ThemeLibraryFilter.ALL -> true
        ThemeLibraryFilter.DARK -> entry.theme.dark
        ThemeLibraryFilter.LIGHT -> !entry.theme.dark
        ThemeLibraryFilter.ACTIVE -> entry.isActive
    }

    /**
     * The three states the library cannot repair, worst first.
     *
     * The ordering is the plugin detail screen's, and for the same reason: a plugin that is both
     * incompatible and suspended should say "needs a newer GameCore" rather than send the user to clear
     * a suspension that would not make it run.
     */
    private fun blockedReason(plugin: InstalledPlugin): String? = when {
        !plugin.manifest.compatibility.isRunnable -> plugin.manifest.compatibility.reason
        plugin.quarantined ->
            "Quarantined after a problem. Release it in Plugins before using its theme."
        plugin.suspended ->
            plugin.suspensionDetail?.let { "Stopped: $it. Clear the stop in Plugins first." }
                ?: "Stopped after a problem. Clear the stop in Plugins first."
        else -> null
    }
}
