package com.gamecore.studio.core

import com.gamecore.studio.model.BoolSetting
import com.gamecore.studio.model.BrokerActionId
import com.gamecore.studio.model.BuiltinIcon
import com.gamecore.studio.model.CreatorCategory
import com.gamecore.studio.model.CreatorSections
import com.gamecore.studio.model.EnumSetting
import com.gamecore.studio.model.FloatSetting
import com.gamecore.studio.model.GlassDraft
import com.gamecore.studio.model.GlitchDraft
import com.gamecore.studio.model.IntSetting
import com.gamecore.studio.model.NodeDraft
import com.gamecore.studio.model.NodeKind
import com.gamecore.studio.model.NodeValue
import com.gamecore.studio.model.Operator
import com.gamecore.studio.model.OverlayDraft
import com.gamecore.studio.model.OverlaySize
import com.gamecore.studio.model.PluginDraft
import com.gamecore.studio.model.RuleCondition
import com.gamecore.studio.model.RuleDraft
import com.gamecore.studio.model.RuleVariable
import com.gamecore.studio.model.ScreenDraft
import com.gamecore.studio.model.SettingDraft
import com.gamecore.studio.model.StatId
import com.gamecore.studio.model.StringSetting
import com.gamecore.studio.model.ThemeDraft
import com.gamecore.studio.model.Trigger
import com.gamecore.studio.model.WidgetDraft
import com.gamecore.studio.model.WidgetSize
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/** What happened when an untrusted package was opened in Studio. */
sealed interface ImportOutcome {
    data class Success(val draft: PluginDraft, val report: ImportReport) : ImportOutcome
    data class Failure(val message: String) : ImportOutcome
}

/** Every package field Studio could not preserve exactly. Nothing is silently discarded. */
data class ImportReport(
    val warnings: List<String>,
    val ignoredManifestKeys: List<String> = emptyList(),
    val ignoredThemeKeys: List<String> = emptyList(),
    val ignoredSections: List<String> = emptyList(),
)

/**
 * Loss-aware importer for the exact declarative section set [PluginComposer] can write.
 *
 * No package value is executed. Unsupported files are refused because re-export would erase them;
 * supported JSON is parsed into closed Studio vocabularies; and strict-section unknown keys fail
 * with their full path. Theme/manifest unknown keys retain the older reporting contract and are
 * listed as explicit losses in [ImportReport].
 */
object PluginImporter {

    const val MAX_ENTRIES = 64
    const val MAX_ENTRY_BYTES = 2 * 1024 * 1024
    const val MAX_TOTAL_BYTES = 4 * 1024 * 1024

    private const val MANIFEST = "manifest.json"
    private const val THEME = "theme.json"
    private const val PILL = "pill.json"
    private const val SETTINGS = "settings.json"
    private const val FORMAT = "gamecore.plugin"

    private const val SECTION_THEME = "theme"
    private const val SECTION_PILL = "pill"
    private const val SECTION_SCREENS = "screens"
    private const val SECTION_RULES = "rules"
    private const val SECTION_SETTINGS = "settings"
    private const val SECTION_WIDGETS = "widgets"

    private val SUPPORTED_SECTIONS = setOf(
        SECTION_THEME,
        SECTION_PILL,
        SECTION_SCREENS,
        SECTION_RULES,
        SECTION_SETTINGS,
        SECTION_WIDGETS,
    )

    private val MANIFEST_KEYS = setOf(
        "format", "id", "name", "version", "versionName", "api",
        "minHostVersion", "developer", "description", "changelog",
        "permissions", "declares",
    )
    private val PERMISSION_KEYS = setOf("id", "scopes")

    private val THEME_KEYS = setOf(
        "name", "schema", "dark", "amoled", "primary", "onPrimary", "background",
        "surface", "onSurface", "accent", "roles", "cornerRadiusDp", "uiScale",
        "font", "typography", "effects",
    )
    private val ROLE_KEYS = setOf(
        "secondary", "tertiary", "surfaceVariant", "onSurfaceVariant", "outline", "error", "onError",
    )
    private val TYPOGRAPHY_KEYS = setOf("scale", "weight")
    private val EFFECT_KEYS = setOf("glitch", "glass")
    private val GLITCH_KEYS = setOf(
        "intensity", "slices", "sliceOffsetDp", "ghostOffsetDp", "fractureLines",
        "noise", "microJitter", "hardwareReactive", "burstMinMs", "burstMaxMs",
    )
    private val GLASS_KEYS = setOf(
        "intensity", "transparency", "gloss", "blurRadiusDp", "sheen",
        "sheenPeriodMs", "spring", "hardwareReactive", "intensitySetting",
    )

    private val SCREEN_ROOT_KEYS = setOf("format", "version", "screens")
    private val SCREEN_KEYS = setOf("id", "title", "icon", "content")
    private val OVERLAY_KEYS = setOf("format", "version", "id", "title", "anchor", "size", "opacity", "content")
    private val ANCHOR_KEYS = setOf("x", "y")
    private val WIDGET_KEYS = setOf("format", "version", "id", "title", "size", "content")
    private val SETTINGS_ROOT_KEYS = setOf("schema", "settings")
    private val RULES_ROOT_KEYS = setOf("rules")
    private val RULE_KEYS = setOf("id", "trigger", "actions", "conditions")
    private val CONDITION_KEYS = setOf("variable", "op", "value")
    private val NODE_KEYS = setOf("type", "props", "children")

    fun import(bytes: ByteArray): ImportOutcome {
        val read = try {
            PackageZip.read(bytes)
        } catch (e: Exception) {
            return ImportOutcome.Failure("The package could not be read: ${e.message.orEmpty()}")
        }
        if (read.issues.isNotEmpty()) return ImportOutcome.Failure(read.issues.first())

        val entries = read.entries.associateByTo(linkedMapOf()) { it.name }
        val manifestEntry = entries[MANIFEST]
            ?: return ImportOutcome.Failure("The package must contain manifest.json.")

        val unsupportedFiles = entries.keys
            .filter { it != MANIFEST && sectionOf(it) == null }
            .sorted()
        if (unsupportedFiles.isNotEmpty()) {
            return ImportOutcome.Failure(unexpectedEntries(unsupportedFiles))
        }

        val report = ReportBuilder()
        return try {
            val parsedManifest = parseManifest(
                String(manifestEntry.bytes, Charsets.UTF_8),
                report,
            )
            val actualSections = entries.keys.mapNotNull(::sectionOf).toSet()
            if (actualSections.isEmpty()) {
                fail("The package has no supported content section. Studio needs at least one of theme, pill, screens, rules, settings, or widgets.")
            }
            val missing = parsedManifest.declares - actualSections
            val undeclared = actualSections - parsedManifest.declares
            if (missing.isNotEmpty()) {
                fail("manifest.json declares ${quoted(missing.sorted())}, but the matching section file is absent.")
            }
            if (undeclared.isNotEmpty()) {
                val files = entries.keys.filter { sectionOf(it) in undeclared }.sorted()
                fail(
                    "The package carries ${quoted(files)}, but manifest.json does not declare the matching section. " +
                        "Importing it anyway would risk data loss; declarations and files must agree.",
                )
            }

            val themeEnabled = SECTION_THEME in actualSections
            val theme = if (themeEnabled) {
                parseTheme(text(entries.getValue(THEME)), report)
            } else {
                ThemeDraft()
            }

            val screens = entries.values
                .filter { sectionOf(it.name) == SECTION_SCREENS }
                .flatMap { parseScreens(text(it), it.name) }
            if (SECTION_SCREENS in actualSections && screens.isEmpty()) {
                fail("The declared screens section contains no screens, which Studio cannot preserve as a capability.")
            }
            if (screens.size > CreatorSections.MAX_SCREENS) {
                fail("The screens files contain ${screens.size} screens; Studio can preserve at most ${CreatorSections.MAX_SCREENS}.")
            }

            val overlay = entries[PILL]?.let { parseOverlay(text(it), it.name) }
            val settings = entries[SETTINGS]?.let { parseSettings(text(it), it.name) }.orEmpty()
            val widgets = entries.values
                .filter { sectionOf(it.name) == SECTION_WIDGETS }
                .map { parseWidget(text(it), it.name) }
            if (widgets.size > CreatorSections.MAX_WIDGETS) {
                fail("The widget files contain ${widgets.size} widgets; Studio can preserve at most ${CreatorSections.MAX_WIDGETS}.")
            }
            val duplicateWidget = widgets.groupBy { canonicalId(it.id) }.entries.firstOrNull { it.value.size > 1 }
            if (duplicateWidget != null) {
                fail("Two widget files declare the id \"${duplicateWidget.key}\". Studio will not overwrite either widget.")
            }

            val rules = entries.values
                .filter { sectionOf(it.name) == SECTION_RULES }
                .flatMap { parseRules(text(it), it.name) }
            if (SECTION_RULES in actualSections && rules.isEmpty()) {
                fail("The declared rules section contains no rules, which Studio cannot preserve as a capability.")
            }
            if (rules.size > CreatorSections.MAX_RULES) {
                fail("The rules files contain ${rules.size} rules; Studio can preserve at most ${CreatorSections.MAX_RULES}.")
            }

            val sections = CreatorSections(
                themeEnabled = themeEnabled,
                screens = screens,
                overlay = overlay,
                settings = settings,
                widgets = widgets,
                rules = rules,
            )
            val draft = PluginDraft(
                id = parsedManifest.id,
                name = parsedManifest.name,
                version = parsedManifest.version,
                versionName = parsedManifest.versionName,
                developer = parsedManifest.developer,
                description = parsedManifest.description,
                changelog = parsedManifest.changelog,
                api = parsedManifest.api,
                minHostVersion = parsedManifest.minHostVersion,
                permissions = parsedManifest.permissions,
                theme = theme,
                creatorCategory = inferCategory(sections),
                permissionScopes = parsedManifest.permissionScopes,
                sections = sections,
            )
            ImportOutcome.Success(draft, report.build())
        } catch (e: ImportFailure) {
            ImportOutcome.Failure(e.message ?: "The package could not be imported.")
        } catch (e: Exception) {
            ImportOutcome.Failure("The package could not be imported: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    internal fun readEntries(bytes: ByteArray): Map<String, ByteArray> {
        val result = PackageZip.read(bytes)
        if (result.issues.isNotEmpty()) throw PackageReadException(result.issues.first())
        return result.entries.associateTo(linkedMapOf()) { it.name to it.bytes }
    }

    private fun sectionOf(path: String): String? = when {
        path == THEME -> SECTION_THEME
        path == PILL -> SECTION_PILL
        path == SETTINGS -> SECTION_SETTINGS
        directJson(path, "screens/") -> SECTION_SCREENS
        directJson(path, "rules/") -> SECTION_RULES
        directJson(path, "widgets/") -> SECTION_WIDGETS
        else -> null
    }

    private fun directJson(path: String, prefix: String): Boolean {
        if (!path.startsWith(prefix) || !path.endsWith(".json")) return false
        val leaf = path.removePrefix(prefix)
        return leaf.length > ".json".length && '/' !in leaf
    }

    private fun unexpectedEntries(names: List<String>): String =
        "This package also carries ${quoted(names)}. Dropping those files on re-export would be data loss, " +
            "and Studio cannot preserve them, so Studio cannot import this package."

    private fun text(entry: PackageZip.Entry): String = String(entry.bytes, Charsets.UTF_8)

    // ------------------------------------------------------------------------------------ manifest

    private data class ParsedManifest(
        val id: String,
        val name: String,
        val version: Int,
        val versionName: String,
        val developer: String,
        val description: String,
        val changelog: String,
        val api: Int,
        val minHostVersion: Int,
        val permissions: Set<String>,
        val permissionScopes: Map<String, Set<String>>,
        val declares: Set<String>,
    )

    private fun parseManifest(raw: String, report: ReportBuilder): ParsedManifest {
        val root = parseObject(raw, MANIFEST)
        report.unknownManifestKeys(unknownKeys(root, MANIFEST_KEYS))
        val format = requireString(root, "format", MANIFEST)
        if (format != FORMAT) {
            fail("This is not a GameCore plugin package: manifest.json declares format \"$format\" instead of \"$FORMAT\".")
        }
        val version = requireInt(root, "version", MANIFEST)
        val parsedPermissions = parsePermissions(root, report)
        return ParsedManifest(
            id = requireString(root, "id", MANIFEST),
            name = requireString(root, "name", MANIFEST),
            version = version,
            versionName = optionalString(root, "versionName", version.toString(), MANIFEST),
            developer = optionalString(root, "developer", "Unknown", MANIFEST),
            description = optionalString(root, "description", "", MANIFEST),
            changelog = optionalString(root, "changelog", "", MANIFEST),
            api = requireInt(root, "api", MANIFEST),
            minHostVersion = requireInt(root, "minHostVersion", MANIFEST),
            permissions = parsedPermissions.first,
            permissionScopes = parsedPermissions.second,
            declares = parseDeclares(root),
        )
    }

    private fun parsePermissions(
        root: JSONObject,
        report: ReportBuilder,
    ): Pair<Set<String>, Map<String, Set<String>>> {
        if (!root.has("permissions") || root.isNull("permissions")) return emptySet<String>() to emptyMap()
        val array = requireArray(root, "permissions", MANIFEST)
        val permissions = linkedSetOf<String>()
        val scopes = linkedMapOf<String, Set<String>>()
        for (index in 0 until array.length()) {
            when (val item = array.opt(index)) {
                is String -> {
                    permissions += item
                    scopes.remove(item)
                }
                is JSONObject -> {
                    val label = "manifest.json permissions[$index]"
                    unknownKeys(item, PERMISSION_KEYS).forEach { report.unknownManifestKey("permissions[$index].$it") }
                    val id = requireString(item, "id", label)
                    permissions += id
                    if (item.has("scopes") && !item.isNull("scopes")) {
                        scopes[id] = stringArray(item, "scopes", label).toCollection(linkedSetOf())
                    } else {
                        scopes.remove(id)
                    }
                }
                else -> fail("manifest.json permission $index must be an object with an \"id\" or a permission name.")
            }
        }
        return permissions to scopes
    }

    private fun parseDeclares(root: JSONObject): Set<String> {
        if (!root.has("declares") || root.isNull("declares")) return emptySet()
        val array = requireArray(root, "declares", MANIFEST)
        val out = linkedSetOf<String>()
        for (index in 0 until array.length()) {
            val raw = array.opt(index) as? String
                ?: fail("manifest.json declares[$index] must be a section name.")
            val section = raw.trim().lowercase()
            if (section !in SUPPORTED_SECTIONS) {
                fail("manifest.json declares unsupported section \"$section\". Studio cannot preserve it, so importing would cause data loss.")
            }
            out += section
        }
        return out
    }

    // --------------------------------------------------------------------------------------- theme

    private fun parseTheme(raw: String, report: ReportBuilder): ThemeDraft {
        val root = parseObject(raw, THEME)
        report.unknownThemeKeys(unknownKeys(root, THEME_KEYS))
        val schema = optionalInt(root, "schema", 1, THEME)
        if (schema !in 1..ThemeDraft.CURRENT_SCHEMA) {
            fail("theme.json declares schema $schema, but this studio supports only schemas 1 to ${ThemeDraft.CURRENT_SCHEMA}.")
        }
        val defaults = ThemeDraft()

        var secondary = defaults.secondary
        var tertiary = defaults.tertiary
        var surfaceVariant = defaults.surfaceVariant
        var onSurfaceVariant = defaults.onSurfaceVariant
        var outline = defaults.outline
        var error = defaults.error
        var onError = defaults.onError
        if (root.has("roles")) {
            val roles = requireObject(root, "roles", THEME)
            report.unknownThemeKeys(unknownKeys(roles, ROLE_KEYS), "roles")
            secondary = optionalColor(roles, "secondary", secondary, "theme.json role")
            tertiary = optionalColor(roles, "tertiary", tertiary, "theme.json role")
            surfaceVariant = optionalColor(roles, "surfaceVariant", surfaceVariant, "theme.json role")
            onSurfaceVariant = optionalColor(roles, "onSurfaceVariant", onSurfaceVariant, "theme.json role")
            outline = optionalColor(roles, "outline", outline, "theme.json role")
            error = optionalColor(roles, "error", error, "theme.json role")
            onError = optionalColor(roles, "onError", onError, "theme.json role")
        }

        var typographyScale = defaults.typographyScale
        var typographyWeight = defaults.typographyWeight
        if (root.has("typography")) {
            val typography = requireObject(root, "typography", THEME)
            report.unknownThemeKeys(unknownKeys(typography, TYPOGRAPHY_KEYS), "typography")
            typographyScale = optionalFloat(typography, "scale", typographyScale, "theme.json typography")
            typographyWeight = optionalString(typography, "weight", typographyWeight, "theme.json typography")
        }

        var glass: GlassDraft? = null
        var glitch: GlitchDraft? = null
        if (root.has("effects")) {
            val effects = requireObject(root, "effects", THEME)
            report.unknownThemeKeys(unknownKeys(effects, EFFECT_KEYS), "effects")
            if (effects.has("glitch")) glitch = parseGlitch(requireObject(effects, "glitch", "theme.json effects"), report)
            if (effects.has("glass")) glass = parseGlass(requireObject(effects, "glass", "theme.json effects"), report)
        }

        return ThemeDraft(
            name = optionalString(root, "name", "Theme", THEME),
            schema = schema,
            dark = optionalBoolean(root, "dark", true, THEME),
            amoled = optionalBoolean(root, "amoled", false, THEME),
            primary = requireColor(root, "primary", THEME),
            onPrimary = requireColor(root, "onPrimary", THEME),
            background = requireColor(root, "background", THEME),
            surface = requireColor(root, "surface", THEME),
            onSurface = requireColor(root, "onSurface", THEME),
            accent = requireColor(root, "accent", THEME),
            secondary = secondary,
            tertiary = tertiary,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            outline = outline,
            error = error,
            onError = onError,
            cornerRadiusDp = optionalInt(root, "cornerRadiusDp", 16, THEME),
            uiScale = optionalFloat(root, "uiScale", 1f, THEME),
            font = optionalString(root, "font", ThemeDraft.FONT_SYSTEM, THEME),
            typographyWeight = typographyWeight,
            typographyScale = typographyScale,
            glass = glass,
            glitch = glitch,
        )
    }

    private fun parseGlitch(root: JSONObject, report: ReportBuilder): GlitchDraft {
        report.unknownThemeKeys(unknownKeys(root, GLITCH_KEYS), "effects.glitch")
        val defaults = GlitchDraft()
        val label = "theme.json effects.glitch"
        return GlitchDraft(
            intensity = optionalFloat(root, "intensity", defaults.intensity, label),
            slices = optionalInt(root, "slices", defaults.slices, label),
            sliceOffsetDp = optionalInt(root, "sliceOffsetDp", defaults.sliceOffsetDp, label),
            ghostOffsetDp = optionalInt(root, "ghostOffsetDp", defaults.ghostOffsetDp, label),
            fractureLines = optionalBoolean(root, "fractureLines", defaults.fractureLines, label),
            noise = optionalBoolean(root, "noise", defaults.noise, label),
            microJitter = optionalBoolean(root, "microJitter", defaults.microJitter, label),
            hardwareReactive = optionalBoolean(root, "hardwareReactive", defaults.hardwareReactive, label),
            burstMinMs = optionalInt(root, "burstMinMs", defaults.burstMinMs, label),
            burstMaxMs = optionalInt(root, "burstMaxMs", defaults.burstMaxMs, label),
        )
    }

    private fun parseGlass(root: JSONObject, report: ReportBuilder): GlassDraft {
        report.unknownThemeKeys(unknownKeys(root, GLASS_KEYS), "effects.glass")
        val defaults = GlassDraft()
        val label = "theme.json effects.glass"
        return GlassDraft(
            intensity = optionalFloat(root, "intensity", defaults.intensity, label),
            transparency = optionalFloat(root, "transparency", defaults.transparency, label),
            gloss = optionalFloat(root, "gloss", defaults.gloss, label),
            blurRadiusDp = optionalInt(root, "blurRadiusDp", defaults.blurRadiusDp, label),
            sheen = optionalBoolean(root, "sheen", defaults.sheen, label),
            sheenPeriodMs = optionalInt(root, "sheenPeriodMs", defaults.sheenPeriodMs, label),
            spring = optionalString(root, "spring", defaults.spring, label),
            hardwareReactive = optionalBoolean(root, "hardwareReactive", defaults.hardwareReactive, label),
            intensitySetting = optionalNullableString(root, "intensitySetting", label),
        )
    }

    // ------------------------------------------------------------------------------------- screens

    private fun parseScreens(raw: String, file: String): List<ScreenDraft> {
        val root = parseObject(raw, file)
        rejectUnknown(root, SCREEN_ROOT_KEYS, file)
        requireEnvelope(root, file, "gamecore.plugin.screens")
        val array = requireArray(root, "screens", file)
        return (0 until array.length()).map { index ->
            val label = "$file screens[$index]"
            val item = array.opt(index) as? JSONObject ?: fail("$label must be an object.")
            rejectUnknown(item, SCREEN_KEYS, label)
            val id = canonicalId(requireString(item, "id", label))
            val rawIcon = optionalNullableString(item, "icon", label)
            val icon = rawIcon?.let { BuiltinIcon.fromId(it)?.id ?: it }
            ScreenDraft(
                id = id,
                title = optionalString(item, "title", id, label),
                icon = icon,
                root = parseNode(requireObject(item, "content", label), "$label.content"),
            )
        }
    }

    // ------------------------------------------------------------------------------------- overlay

    private fun parseOverlay(raw: String, file: String): OverlayDraft {
        val root = parseObject(raw, file)
        rejectUnknown(root, OVERLAY_KEYS, file)
        requireEnvelope(root, file, "gamecore.plugin.pill")
        val id = canonicalId(requireString(root, "id", file))
        var x = 0.5
        var y = 0.08
        if (root.has("anchor")) {
            val anchor = requireObject(root, "anchor", file)
            rejectUnknown(anchor, ANCHOR_KEYS, "$file anchor")
            x = optionalDouble(anchor, "x", x, "$file anchor")
            y = optionalDouble(anchor, "y", y, "$file anchor")
        }
        val sizeId = optionalString(root, "size", OverlaySize.SMALL.id, file).trim().lowercase()
        val size = OverlaySize.entries.firstOrNull { it.id == sizeId }
            ?: fail("$file field \"size\" names unknown overlay size \"$sizeId\".")
        return OverlayDraft(
            id = id,
            title = optionalString(root, "title", id, file),
            xFraction = x,
            yFraction = y,
            size = size,
            opacity = optionalInt(root, "opacity", 100, file),
            content = parseNode(requireObject(root, "content", file), "$file.content"),
        )
    }

    // ------------------------------------------------------------------------------------ settings

    private fun parseSettings(raw: String, file: String): List<SettingDraft> {
        val root = parseObject(raw, file)
        rejectUnknown(root, SETTINGS_ROOT_KEYS, file)
        val schema = optionalInt(root, "schema", 1, file)
        if (schema != 1) fail("$file declares unsupported settings schema $schema.")
        val array = requireArray(root, "settings", file)
        if (array.length() == 0) fail("$file contains no settings.")
        if (array.length() > CreatorSections.MAX_SETTINGS) {
            fail("$file contains more than ${CreatorSections.MAX_SETTINGS} settings.")
        }
        return (0 until array.length()).map { index ->
            val label = "$file settings[$index]"
            val item = array.opt(index) as? JSONObject ?: fail("$label must be an object.")
            parseSetting(item, label)
        }
    }

    private fun parseSetting(root: JSONObject, label: String): SettingDraft {
        val type = requireString(root, "type", label).trim().lowercase()
        val allowed = when (type) {
            "int", "float" -> setOf("id", "type", "default", "min", "max")
            "enum" -> setOf("id", "type", "default", "options")
            "string" -> setOf("id", "type", "default", "maxLength")
            "bool" -> setOf("id", "type", "default")
            else -> fail("$label names unsupported setting type \"$type\".")
        }
        rejectUnknown(root, allowed, label)
        val id = requireString(root, "id", label).trim()
        return when (type) {
            "bool" -> BoolSetting(id, requireBoolean(root, "default", label))
            "int" -> IntSetting(
                id = id,
                default = requireLong(root, "default", label),
                min = optionalLong(root, "min", label),
                max = optionalLong(root, "max", label),
            )
            "float" -> FloatSetting(
                id = id,
                default = requireDouble(root, "default", label),
                min = optionalNullableDouble(root, "min", label),
                max = optionalNullableDouble(root, "max", label),
            )
            "enum" -> EnumSetting(
                id = id,
                options = stringArray(root, "options", label),
                default = requireString(root, "default", label),
            )
            "string" -> StringSetting(
                id = id,
                default = requireString(root, "default", label),
                maxLength = optionalInt(root, "maxLength", 256, label),
            )
            else -> error("checked above")
        }
    }

    // -------------------------------------------------------------------------------------- widget

    private fun parseWidget(raw: String, file: String): WidgetDraft {
        val root = parseObject(raw, file)
        rejectUnknown(root, WIDGET_KEYS, file)
        requireEnvelope(root, file, "gamecore.plugin.widget")
        val id = canonicalId(requireString(root, "id", file))
        val sizeId = optionalString(root, "size", WidgetSize.MEDIUM.id, file).trim().lowercase()
        val size = WidgetSize.entries.firstOrNull { it.id == sizeId }
            ?: fail("$file field \"size\" names unknown widget size \"$sizeId\".")
        return WidgetDraft(
            id = id,
            title = optionalString(root, "title", id, file),
            size = size,
            content = parseNode(requireObject(root, "content", file), "$file.content"),
        )
    }

    // ---------------------------------------------------------------------------------------- rules

    private fun parseRules(raw: String, file: String): List<RuleDraft> {
        val root = parseObject(raw, file)
        rejectUnknown(root, RULES_ROOT_KEYS, file)
        val array = requireArray(root, "rules", file)
        return (0 until array.length()).map { index ->
            val label = "$file rules[$index]"
            val item = array.opt(index) as? JSONObject ?: fail("$label must be an object.")
            rejectUnknown(item, RULE_KEYS, label)
            val id = optionalString(item, "id", "rule_$index", label)
            val triggerRaw = requireString(item, "trigger", label).trim().uppercase()
            val trigger = Trigger.entries.firstOrNull { it.name == triggerRaw }
                ?: fail("$label names unknown trigger \"$triggerRaw\".")
            val conditions = if (item.has("conditions")) {
                val conditionsArray = requireArray(item, "conditions", label)
                (0 until conditionsArray.length()).map { conditionIndex ->
                    val conditionLabel = "$label.conditions[$conditionIndex]"
                    val condition = conditionsArray.opt(conditionIndex) as? JSONObject
                        ?: fail("$conditionLabel must be an object.")
                    rejectUnknown(condition, CONDITION_KEYS, conditionLabel)
                    val variableId = requireString(condition, "variable", conditionLabel).trim().lowercase()
                    val variable = RuleVariable.entries.firstOrNull { it.id == variableId }
                        ?: fail("$conditionLabel names unknown automation variable \"$variableId\".")
                    val opName = requireString(condition, "op", conditionLabel).trim().uppercase()
                    val op = Operator.entries.firstOrNull { it.name == opName }
                        ?: fail("$conditionLabel names unknown operator \"$opName\".")
                    RuleCondition(
                        variable = variable,
                        op = op,
                        value = optionalDouble(condition, "value", 0.0, conditionLabel),
                    )
                }
            } else {
                emptyList()
            }
            val actions = if (item.has("actions")) {
                stringArray(item, "actions", label).map { rawAction ->
                    val actionId = rawAction.trim().lowercase()
                    BrokerActionId.entries.firstOrNull { it.id == actionId }
                        ?: fail("$label names unknown broker action \"$rawAction\"; Studio never imports arbitrary action references.")
                }
            } else {
                emptyList()
            }
            RuleDraft(id, trigger, conditions, actions)
        }
    }

    // ---------------------------------------------------------------------------------------- nodes

    private class NodeBudget(var nodes: Int = 0)

    private fun parseNode(root: JSONObject, label: String): NodeDraft =
        parseNodeAt(root, label, depth = 0, budget = NodeBudget())

    private fun parseNodeAt(root: JSONObject, label: String, depth: Int, budget: NodeBudget): NodeDraft {
        if (depth > 32) fail("$label is nested deeper than the host node parser permits.")
        if (++budget.nodes > 512) fail("$label contains more nodes than the host parser permits.")
        rejectUnknown(root, NODE_KEYS, label)
        val typeId = requireString(root, "type", label).trim().lowercase()
        val kind = NodeKind.entries.firstOrNull { it.id == typeId }
            ?: fail("$label uses unsupported component type \"$typeId\". Studio cannot preserve interactive or unknown nodes.")

        val props = linkedMapOf<String, NodeValue>()
        if (root.has("props")) {
            val objectProps = requireObject(root, "props", label)
            for (key in objectProps.keys()) {
                props[key] = parseNodeValue(objectProps.opt(key), kind, key, "$label.props.$key")
            }
        }
        val children = if (root.has("children")) {
            val array = requireArray(root, "children", label)
            (0 until array.length()).map { index ->
                val child = array.opt(index) as? JSONObject ?: fail("$label.children[$index] must be an object.")
                parseNodeAt(child, "$label.children[$index]", depth + 1, budget)
            }
        } else {
            emptyList()
        }
        return NodeDraft(kind, props, children)
    }

    private fun parseNodeValue(raw: Any?, kind: NodeKind, key: String, label: String): NodeValue = when (raw) {
        is Boolean -> NodeValue.Bool(raw)
        is Number -> NodeValue.Number(raw.toDouble())
        is String -> decodeNodeString(raw, kind, key, label)
        else -> fail("$label must be a string, number, or boolean primitive.")
    }

    private fun decodeNodeString(raw: String, kind: NodeKind, key: String, label: String): NodeValue {
        if (raw.startsWith("@@")) return NodeValue.Text(raw.substring(2))
        if (raw.startsWith("@")) {
            fail("$label is an action reference. Studio imports declarative values only and will not preserve arbitrary actions.")
        }
        if (raw.startsWith("#")) parseColor(raw)?.let { return NodeValue.Color(it) }
        val canonical = when {
            kind == NodeKind.STAT && key == "stat" -> StatId.fromId(raw)?.id
            kind == NodeKind.CHART && key in setOf("stat", "stat2") -> StatId.fromId(raw)?.id
            kind == NodeKind.IMAGE && key == "icon" -> BuiltinIcon.fromId(raw)?.id
            else -> null
        }
        return NodeValue.Text(canonical ?: raw)
    }

    // ------------------------------------------------------------------------------------ category

    private fun inferCategory(sections: CreatorSections): CreatorCategory {
        val purposes = buildList {
            if (sections.screens.isNotEmpty()) add(CreatorCategory.INTERFACE)
            if (sections.overlay != null) add(CreatorCategory.OVERLAY)
            if (sections.settings.isNotEmpty()) add(CreatorCategory.SETTINGS)
            if (sections.widgets.isNotEmpty()) add(CreatorCategory.DASHBOARD)
            if (sections.rules.isNotEmpty()) add(CreatorCategory.AUTOMATION)
        }
        if (purposes.isEmpty()) return CreatorCategory.THEME
        if (purposes.size > 1) return CreatorCategory.COMBINED
        val sole = purposes.single()
        val monitoringSurface = when (sole) {
            CreatorCategory.INTERFACE -> sections.screens.any { containsLiveReading(it.root) }
            CreatorCategory.OVERLAY -> sections.overlay?.content?.let(::containsLiveReading) == true
            CreatorCategory.DASHBOARD -> sections.widgets.any { containsLiveReading(it.content) }
            else -> false
        }
        return if (monitoringSurface) CreatorCategory.MONITORING else sole
    }

    private fun containsLiveReading(root: NodeDraft): Boolean {
        val pending = ArrayDeque<NodeDraft>()
        pending.add(root)
        var visited = 0
        while (pending.isNotEmpty() && visited++ < 1_024) {
            val node = pending.removeLast()
            if (node.kind == NodeKind.STAT) return true
            if (node.kind == NodeKind.CHART && ("stat" in node.props || "stat2" in node.props)) return true
            node.children.forEach(pending::addLast)
        }
        return false
    }

    // -------------------------------------------------------------------------------- JSON helpers

    private fun parseObject(raw: String, file: String): JSONObject = try {
        JSONObject(raw)
    } catch (e: JSONException) {
        fail("$file is not valid JSON: ${e.message.orEmpty()}")
    }

    private fun requireEnvelope(root: JSONObject, file: String, format: String) {
        val actual = requireString(root, "format", file)
        if (actual != format) fail("$file field \"format\" must be \"$format\", not \"$actual\".")
        val version = requireInt(root, "version", file)
        if (version != 1) fail("$file version must be 1, not $version.")
    }

    private fun rejectUnknown(root: JSONObject, allowed: Set<String>, label: String) {
        unknownKeys(root, allowed).firstOrNull()?.let { key ->
            fail("$label contains unknown key \"$key\". Studio cannot preserve it; importing would cause data loss.")
        }
    }

    private fun unknownKeys(root: JSONObject, allowed: Set<String>): List<String> =
        root.keys().asSequence().filter { it !in allowed }.sorted().toList()

    private fun requireObject(root: JSONObject, key: String, label: String): JSONObject =
        root.opt(key) as? JSONObject ?: fail("$label field \"$key\" must be an object.")

    private fun requireArray(root: JSONObject, key: String, label: String): JSONArray =
        root.opt(key) as? JSONArray ?: fail("$label field \"$key\" must be an array.")

    private fun requireString(root: JSONObject, key: String, label: String): String {
        if (!root.has(key) || root.isNull(key)) fail("$label is missing required field \"$key\".")
        return root.opt(key) as? String ?: fail("$label field \"$key\" must be a string.")
    }

    private fun optionalString(root: JSONObject, key: String, default: String, label: String): String {
        if (!root.has(key) || root.isNull(key)) return default
        return root.opt(key) as? String ?: fail("$label field \"$key\" must be a string.")
    }

    private fun optionalNullableString(root: JSONObject, key: String, label: String): String? {
        if (!root.has(key) || root.isNull(key)) return null
        return root.opt(key) as? String ?: fail("$label field \"$key\" must be a string.")
    }

    private fun requireBoolean(root: JSONObject, key: String, label: String): Boolean =
        root.opt(key) as? Boolean ?: fail("$label field \"$key\" must be true or false.")

    private fun optionalBoolean(root: JSONObject, key: String, default: Boolean, label: String): Boolean {
        if (!root.has(key) || root.isNull(key)) return default
        return requireBoolean(root, key, label)
    }

    private fun requireInt(root: JSONObject, key: String, label: String): Int {
        val value = requireLong(root, key, label)
        if (value !in Int.MIN_VALUE..Int.MAX_VALUE) fail("$label field \"$key\" is outside the whole-number range.")
        return value.toInt()
    }

    private fun optionalInt(root: JSONObject, key: String, default: Int, label: String): Int =
        if (!root.has(key) || root.isNull(key)) default else requireInt(root, key, label)

    private fun requireLong(root: JSONObject, key: String, label: String): Long {
        if (!root.has(key) || root.isNull(key)) {
            fail("$label is missing required field \"$key\".")
        }
        val value = root.opt(key) as? Number ?: fail("$label field \"$key\" must be a whole number.")
        val double = value.toDouble()
        if (!double.isFinite() || double % 1.0 != 0.0 || double < Long.MIN_VALUE || double > Long.MAX_VALUE) {
            fail("$label field \"$key\" must be a whole number.")
        }
        return value.toLong()
    }

    private fun optionalLong(root: JSONObject, key: String, label: String): Long? =
        if (!root.has(key) || root.isNull(key)) null else requireLong(root, key, label)

    private fun requireDouble(root: JSONObject, key: String, label: String): Double {
        val value = root.opt(key) as? Number ?: fail("$label field \"$key\" must be a number.")
        return value.toDouble()
    }

    private fun optionalDouble(root: JSONObject, key: String, default: Double, label: String): Double =
        if (!root.has(key) || root.isNull(key)) default else requireDouble(root, key, label)

    private fun optionalNullableDouble(root: JSONObject, key: String, label: String): Double? =
        if (!root.has(key) || root.isNull(key)) null else requireDouble(root, key, label)

    private fun optionalFloat(root: JSONObject, key: String, default: Float, label: String): Float =
        optionalDouble(root, key, default.toDouble(), label).toFloat()

    private fun stringArray(root: JSONObject, key: String, label: String): List<String> {
        val array = requireArray(root, key, label)
        return (0 until array.length()).map { index ->
            array.opt(index) as? String ?: fail("$label field \"$key\" item $index must be a string.")
        }
    }

    private fun requireColor(root: JSONObject, key: String, label: String): Long {
        val value = requireString(root, key, label)
        return parseColor(value) ?: fail("$label colour \"$key\" is not a #RRGGBB or #AARRGGBB colour.")
    }

    private fun optionalColor(root: JSONObject, key: String, default: Long, label: String): Long {
        if (!root.has(key) || root.isNull(key)) return default
        return requireColor(root, key, label)
    }

    private fun parseColor(raw: String): Long? {
        val value = raw.trim().removePrefix("#")
        if (value.length != 6 && value.length != 8) return null
        if (!value.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
        val parsed = value.toLongOrNull(16) ?: return null
        return if (value.length == 6) 0xFF000000L or parsed else parsed
    }

    private fun canonicalId(value: String): String = value.trim().lowercase()
    private fun quoted(values: List<String>): String = values.joinToString(", ") { "\"$it\"" }
    private fun fail(message: String): Nothing = throw ImportFailure(message)
    private class ImportFailure(message: String) : Exception(message)

    // -------------------------------------------------------------------------------------- report

    private class ReportBuilder {
        private val warnings = mutableListOf<String>()
        private val manifestKeys = mutableListOf<String>()
        private val themeKeys = mutableListOf<String>()

        fun unknownManifestKeys(keys: List<String>) {
            keys.forEach { key ->
                manifestKeys += key
                warnings += "The manifest key \"$key\" is not understood by this studio. The supported fields " +
                    "were imported, but \"$key\" will not be written back when the plugin is exported."
            }
        }

        fun unknownManifestKey(key: String) = unknownManifestKeys(listOf(key))

        fun unknownThemeKeys(keys: List<String>, prefix: String? = null) {
            keys.forEach { key ->
                val path = if (prefix == null) key else "$prefix.$key"
                themeKeys += path
                warnings += "The theme key \"$path\" is not understood by this studio, so it will not be " +
                    "written back when the plugin is exported. The supported part of the theme was imported."
            }
        }

        fun build(): ImportReport = ImportReport(
            warnings = warnings.distinct(),
            ignoredManifestKeys = manifestKeys.distinct(),
            ignoredThemeKeys = themeKeys.distinct(),
            ignoredSections = emptyList(),
        )
    }
}

// ---------------------------------------------------------------------------------------- zip reader

/** Shared bounded zip reader for import and inspection. */
internal object PackageZip {
    data class Entry(val name: String, val bytes: ByteArray)
    data class ReadResult(val entries: List<Entry>, val totalBytes: Int, val issues: List<String>)

    private const val NOT_A_ZIP = "The file is not a readable zip archive."
    private const val BUFFER_BYTES = 8 * 1024

    fun read(bytes: ByteArray): ReadResult {
        if (!hasZipSignature(bytes)) return ReadResult(emptyList(), 0, listOf(NOT_A_ZIP))
        val entries = arrayListOf<Entry>()
        val issues = arrayListOf<String>()
        val names = hashSetOf<String>()
        var total = 0
        var count = 0
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    count++
                    if (count > PluginImporter.MAX_ENTRIES) {
                        issues += "The package has more than ${PluginImporter.MAX_ENTRIES} entries."
                        break
                    }
                    val name = entry.name ?: ""
                    if (!isSafeName(name)) {
                        issues += "The package entry \"$name\" has an unsafe path."
                        break
                    }
                    if (!names.add(name)) {
                        issues += "The package contains the entry \"$name\" more than once."
                        break
                    }
                    val data = readEntry(zip, name, PluginImporter.MAX_TOTAL_BYTES - total, issues) ?: break
                    total += data.size
                    entries += Entry(name, data)
                }
            }
        } catch (_: Exception) {
            issues += NOT_A_ZIP
        }
        return ReadResult(entries, total, issues.distinct())
    }

    private fun readEntry(
        zip: ZipInputStream,
        name: String,
        remainingTotal: Int,
        issues: MutableList<String>,
    ): ByteArray? {
        val output = ByteArrayOutputStream(BUFFER_BYTES)
        val buffer = ByteArray(BUFFER_BYTES)
        var size = 0
        while (true) {
            val read = zip.read(buffer)
            if (read < 0) break
            size += read
            if (size > PluginImporter.MAX_ENTRY_BYTES) {
                issues += "The entry \"$name\" is larger than ${PluginImporter.MAX_ENTRY_BYTES / (1024 * 1024)} MiB uncompressed."
                return null
            }
            if (size > remainingTotal) {
                issues += "The package expands to more than ${PluginImporter.MAX_TOTAL_BYTES / (1024 * 1024)} MiB."
                return null
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun isSafeName(name: String): Boolean {
        if (name.isEmpty() || name.length > 180) return false
        if (name.startsWith("/") || name.contains('\\')) return false
        if (name.any { it.isISOControl() }) return false
        val segments = name.split('/')
        return segments.none { it.isBlank() || it == "." || it == ".." }
    }

    private fun hasZipSignature(bytes: ByteArray): Boolean {
        if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) return false
        val third = bytes[2].toInt() and 0xFF
        val fourth = bytes[3].toInt() and 0xFF
        return (third == 3 && fourth == 4) || (third == 5 && fourth == 6) || (third == 7 && fourth == 8)
    }
}

internal class PackageReadException(message: String) : Exception(message)
