package com.gamecore.core.plugin

import com.gamecore.core.plugin.content.AutomationEngine
import com.gamecore.core.plugin.content.RulesCodec
import com.gamecore.core.plugin.dashboard.PluginDashboardCodec
import com.gamecore.core.plugin.icon.PluginIconCodec
import com.gamecore.core.plugin.overlay.PluginOverlayCodec
import com.gamecore.core.plugin.settings.PluginSettingsCodec
import com.gamecore.core.plugin.theme.PluginThemeCodec
import com.gamecore.core.plugin.ui.PluginScreensCodec

/**
 * Validates the payload of every plugin section that the production registry actually consumes.
 *
 * Container validation alone can prove that `screens/x.json` exists; it cannot prove that the
 * screen will decode. Installation preview must not promise a capability that the registry will
 * later silently drop, so this pass runs the same pure codecs before bytes are accepted. It never
 * executes a rule, opens an Android service, decodes artwork, or grants a permission.
 *
 * `profiles`, `aimlab`, and `stats` remain container-only because this build has no registry
 * consumer for them. Treating an unrelated helper as their runtime codec would be a stronger but
 * false claim; the install UI can report those declarations, but cannot validate behavior that is
 * not wired into the host.
 */
object PluginSectionValidator {

    data class Invalid(val section: PluginSection, val path: String, val detail: String)

    fun validate(manifest: PluginManifest, entries: Map<String, ByteArray>): Invalid? {
        for (section in manifest.declares) {
            when (section) {
                PluginSection.THEME -> validateTheme(entries)?.let { return it }
                PluginSection.PILL -> validateOverlay(entries)?.let { return it }
                PluginSection.SCREENS -> validateScreens(entries)?.let { return it }
                PluginSection.RULES -> validateRules(entries)?.let { return it }
                PluginSection.SETTINGS -> validateSettings(entries)?.let { return it }
                PluginSection.WIDGETS -> validateWidgets(entries)?.let { return it }
                PluginSection.ICON -> validateIcon(entries)?.let { return it }
                PluginSection.PROFILES,
                PluginSection.AIMLAB,
                PluginSection.STATS,
                -> Unit
            }
        }
        return null
    }

    private fun validateTheme(entries: Map<String, ByteArray>): Invalid? {
        val path = PluginSection.THEME.entryPrefix
        return when (val result = PluginThemeCodec.decode(entries[path].utf8())) {
            is PluginThemeCodec.Result.Parsed -> null
            is PluginThemeCodec.Result.Rejected -> invalid(PluginSection.THEME, path, result.reason, result.detail)
        }
    }

    private fun validateOverlay(entries: Map<String, ByteArray>): Invalid? {
        val path = PluginSection.PILL.entryPrefix
        return when (val result = PluginOverlayCodec.decode(entries[path].utf8())) {
            is PluginOverlayCodec.Result.Parsed -> null
            is PluginOverlayCodec.Result.Rejected -> invalid(PluginSection.PILL, path, result.reason, result.detail)
        }
    }

    private fun validateSettings(entries: Map<String, ByteArray>): Invalid? {
        val path = PluginSection.SETTINGS.entryPrefix
        return when (val result = PluginSettingsCodec.decode(entries[path].utf8())) {
            is PluginSettingsCodec.Result.Parsed -> null
            is PluginSettingsCodec.Result.Rejected -> invalid(PluginSection.SETTINGS, path, result.reason, result.detail)
        }
    }

    private fun validateScreens(entries: Map<String, ByteArray>): Invalid? {
        val paths = entries.sectionPaths(PluginSection.SCREENS)
        val seen = linkedSetOf<String>()
        var count = 0
        for (path in paths) {
            if (!path.endsWith(".json")) {
                return Invalid(PluginSection.SCREENS, path, "a screens entry must be a .json file")
            }
            when (val result = PluginScreensCodec.decode(entries[path].utf8())) {
                is PluginScreensCodec.Result.Rejected ->
                    return invalid(PluginSection.SCREENS, path, result.reason, result.detail)
                is PluginScreensCodec.Result.Parsed -> for (screen in result.screens) {
                    if (++count > PluginScreensCodec.MAX_SCREENS) {
                        return Invalid(
                            PluginSection.SCREENS,
                            path,
                            "the plugin contains more than ${PluginScreensCodec.MAX_SCREENS} screens",
                        )
                    }
                    if (!seen.add(screen.id)) {
                        return Invalid(PluginSection.SCREENS, path, "duplicate screen id \"${screen.id}\"")
                    }
                }
            }
        }
        return if (count == 0) {
            Invalid(PluginSection.SCREENS, paths.firstOrNull().orEmpty(), "the declared screens section is empty")
        } else null
    }

    private fun validateRules(entries: Map<String, ByteArray>): Invalid? {
        val paths = entries.sectionPaths(PluginSection.RULES)
        val engine = AutomationEngine()
        val seen = linkedSetOf<String>()
        var count = 0
        for (path in paths) {
            if (!path.endsWith(".json")) {
                return Invalid(PluginSection.RULES, path, "a rules entry must be a .json file")
            }
            when (val result = RulesCodec.decode(entries[path].utf8())) {
                is RulesCodec.Result.Rejected ->
                    return invalid(PluginSection.RULES, path, result.reason, result.detail)
                is RulesCodec.Result.Parsed -> for (rule in result.rules) {
                    if (++count > MAX_RULES_PER_PLUGIN) {
                        return Invalid(
                            PluginSection.RULES,
                            path,
                            "the plugin contains more than $MAX_RULES_PER_PLUGIN rules",
                        )
                    }
                    if (!seen.add(rule.id)) {
                        return Invalid(PluginSection.RULES, path, "duplicate rule id \"${rule.id}\"")
                    }
                    val validation = engine.validate(rule)
                    if (validation is AutomationEngine.Validation.Invalid) {
                        return Invalid(
                            PluginSection.RULES,
                            path,
                            validation.reasons.joinToString("; "),
                        )
                    }
                }
            }
        }
        return if (count == 0) {
            Invalid(PluginSection.RULES, paths.firstOrNull().orEmpty(), "the declared rules section is empty")
        } else null
    }

    private fun validateWidgets(entries: Map<String, ByteArray>): Invalid? {
        val paths = entries.sectionPaths(PluginSection.WIDGETS)
        val seen = linkedSetOf<String>()
        var count = 0
        for (path in paths) {
            if (!path.endsWith(".json")) {
                return Invalid(PluginSection.WIDGETS, path, "a widget entry must be a .json file")
            }
            when (val result = PluginDashboardCodec.decode(entries[path].utf8())) {
                is PluginDashboardCodec.Result.Rejected ->
                    return invalid(PluginSection.WIDGETS, path, result.reason, result.detail)
                is PluginDashboardCodec.Result.Parsed -> {
                    val widget = result.widget
                    if (++count > PluginDashboardCodec.MAX_WIDGETS) {
                        return Invalid(
                            PluginSection.WIDGETS,
                            path,
                            "the plugin contains more than ${PluginDashboardCodec.MAX_WIDGETS} widgets",
                        )
                    }
                    if (!seen.add(widget.id)) {
                        return Invalid(PluginSection.WIDGETS, path, "duplicate widget id \"${widget.id}\"")
                    }
                }
            }
        }
        return if (count == 0) {
            Invalid(PluginSection.WIDGETS, paths.firstOrNull().orEmpty(), "the declared widgets section is empty")
        } else null
    }

    private fun validateIcon(entries: Map<String, ByteArray>): Invalid? {
        val path = PluginSection.ICON.entryPrefix
        return when (val result = PluginIconCodec.decode(entries[path].utf8())) {
            is PluginIconCodec.Result.Rejected -> invalid(PluginSection.ICON, path, result.reason, result.detail)
            is PluginIconCodec.Result.Parsed -> {
                val artwork = result.spec.artworkPath
                if (artwork != null && artwork !in entries) {
                    Invalid(PluginSection.ICON, path, "artwork \"$artwork\" is missing from the package")
                } else null
            }
        }
    }

    private fun invalid(section: PluginSection, path: String, reason: Any, detail: String): Invalid =
        Invalid(section, path, listOf(reason.toString(), detail).filter { it.isNotBlank() }.joinToString(": "))

    private fun ByteArray?.utf8(): String? = this?.toString(Charsets.UTF_8)

    private fun Map<String, ByteArray>.sectionPaths(section: PluginSection): List<String> {
        val prefix = section.entryPrefix
        return keys.filter { if (prefix.endsWith('/')) it.startsWith(prefix) else it == prefix }
    }

    private const val MAX_RULES_PER_PLUGIN = 32
}
