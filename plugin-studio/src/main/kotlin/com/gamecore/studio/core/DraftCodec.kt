package com.gamecore.studio.core

import com.gamecore.studio.model.BoolSetting
import com.gamecore.studio.model.BrokerActionId
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
import com.gamecore.studio.model.StringSetting
import com.gamecore.studio.model.ThemeDraft
import com.gamecore.studio.model.Trigger
import com.gamecore.studio.model.WidgetDraft
import com.gamecore.studio.model.WidgetSize
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Studio's private, lossless storage form for a [PluginDraft].
 *
 * This is not package JSON. It deliberately records editor-only labels, the creator category and
 * tagged node primitives so every supported draft value can survive process death. Unknown keys are
 * ignored for forward compatibility; a malformed known value returns `null`. Records written before
 * sections existed migrate to a theme-enabled [CreatorSections] and [CreatorCategory.THEME].
 */
object DraftCodec {

    private const val STORAGE_SCHEMA = 2
    private const val MAX_STORED_NODE_DEPTH = 32
    private const val MAX_STORED_NODES = 1_024

    fun encode(draft: PluginDraft): String = JSONObject()
        .put("storageSchema", STORAGE_SCHEMA)
        .put("id", draft.id)
        .put("name", draft.name)
        .put("version", draft.version)
        .put("versionName", draft.versionName)
        .put("developer", draft.developer)
        .put("description", draft.description)
        .put("changelog", draft.changelog)
        .put("api", draft.api)
        .put("minHostVersion", draft.minHostVersion)
        .put("creatorCategory", draft.creatorCategory.name)
        .put("permissions", stringArray(draft.permissions.sorted()))
        .put("permissionScopes", encodePermissionScopes(draft.permissionScopes))
        .put("theme", encodeTheme(draft.theme))
        .put("sections", encodeSections(draft.sections))
        .toString()

    fun decode(json: String): PluginDraft? = try {
        val root = JSONObject(json)
        val defaults = PluginDraft()
        val categoryName = string(root, "creatorCategory", CreatorCategory.THEME.name)
        val category = CreatorCategory.entries.firstOrNull { it.name == categoryName }
            ?: throw JSONException("unknown creatorCategory \"$categoryName\"")
        PluginDraft(
            id = string(root, "id", defaults.id),
            name = string(root, "name", defaults.name),
            version = int(root, "version", defaults.version),
            versionName = string(root, "versionName", defaults.versionName),
            developer = string(root, "developer", defaults.developer),
            description = string(root, "description", defaults.description),
            changelog = string(root, "changelog", defaults.changelog),
            api = int(root, "api", defaults.api),
            minHostVersion = int(root, "minHostVersion", defaults.minHostVersion),
            permissions = permissions(root, defaults.permissions),
            theme = objectOrNull(root, "theme")?.let(::decodeTheme) ?: ThemeDraft(),
            creatorCategory = category,
            permissionScopes = decodePermissionScopes(root),
            sections = if (root.has("sections")) {
                decodeSections(requiredObject(root, "sections"))
            } else {
                // Migration from the original theme-only notebook shape.
                CreatorSections(themeEnabled = true)
            },
        )
    } catch (_: Exception) {
        null
    }

    // ---------------------------------------------------------------------------------- sections

    private fun encodeSections(sections: CreatorSections): JSONObject = JSONObject()
        .put("themeEnabled", sections.themeEnabled)
        .put("screens", array(sections.screens.map(::encodeScreen)))
        .also { root -> sections.overlay?.let { root.put("overlay", encodeOverlay(it)) } }
        .put("settings", array(sections.settings.map(::encodeSetting)))
        .put("widgets", array(sections.widgets.map(::encodeWidget)))
        .put("rules", array(sections.rules.map(::encodeRule)))

    private fun decodeSections(root: JSONObject): CreatorSections = CreatorSections(
        themeEnabled = boolean(root, "themeEnabled", true),
        screens = objectArray(root, "screens").map(::decodeScreen),
        overlay = objectOrNull(root, "overlay")?.let(::decodeOverlay),
        settings = objectArray(root, "settings").map(::decodeSetting),
        widgets = objectArray(root, "widgets").map(::decodeWidget),
        rules = objectArray(root, "rules").map(::decodeRule),
    )

    private fun encodeScreen(screen: ScreenDraft): JSONObject = JSONObject()
        .put("id", screen.id)
        .put("title", screen.title)
        .also { root -> screen.icon?.let { root.put("icon", it) } }
        .put("root", encodeNode(screen.root))

    private fun decodeScreen(root: JSONObject): ScreenDraft = ScreenDraft(
        id = requiredString(root, "id"),
        title = requiredString(root, "title"),
        icon = nullableString(root, "icon"),
        root = decodeNode(requiredObject(root, "root"), 0, NodeBudget()),
    )

    private fun encodeOverlay(overlay: OverlayDraft): JSONObject = JSONObject()
        .put("id", overlay.id)
        .put("title", overlay.title)
        .put("xFraction", numberValue(overlay.xFraction))
        .put("yFraction", numberValue(overlay.yFraction))
        .put("size", overlay.size.id)
        .put("opacity", overlay.opacity)
        .put("content", encodeNode(overlay.content))

    private fun decodeOverlay(root: JSONObject): OverlayDraft = OverlayDraft(
        id = requiredString(root, "id"),
        title = requiredString(root, "title"),
        xFraction = double(root, "xFraction", 0.5),
        yFraction = double(root, "yFraction", 0.08),
        size = enumById(requiredString(root, "size"), OverlaySize.entries) { it.id },
        opacity = int(root, "opacity", 85),
        content = decodeNode(requiredObject(root, "content"), 0, NodeBudget()),
    )

    private fun encodeWidget(widget: WidgetDraft): JSONObject = JSONObject()
        .put("id", widget.id)
        .put("title", widget.title)
        .put("size", widget.size.id)
        .put("content", encodeNode(widget.content))

    private fun decodeWidget(root: JSONObject): WidgetDraft = WidgetDraft(
        id = requiredString(root, "id"),
        title = requiredString(root, "title"),
        size = enumById(requiredString(root, "size"), WidgetSize.entries) { it.id },
        content = decodeNode(requiredObject(root, "content"), 0, NodeBudget()),
    )

    // -------------------------------------------------------------------------------------- nodes

    private fun encodeNode(node: NodeDraft): JSONObject {
        val props = JSONObject()
        node.props.toSortedMap().forEach { (key, value) -> props.put(key, encodeNodeValue(value)) }
        return JSONObject()
            .put("kind", node.kind.id)
            .put("props", props)
            .put("children", array(node.children.map(::encodeNode)))
    }

    private fun encodeNodeValue(value: NodeValue): JSONObject = when (value) {
        is NodeValue.Text -> JSONObject().put("kind", "text").put("value", value.value)
        is NodeValue.Number -> JSONObject().put("kind", "number").put("value", numberValue(value.value))
        is NodeValue.Bool -> JSONObject().put("kind", "bool").put("value", value.value)
        is NodeValue.Color -> JSONObject().put("kind", "color").put("value", value.argb)
    }

    private class NodeBudget(var count: Int = 0)

    private fun decodeNode(root: JSONObject, depth: Int, budget: NodeBudget): NodeDraft {
        if (depth > MAX_STORED_NODE_DEPTH) throw JSONException("stored node tree is too deep")
        if (++budget.count > MAX_STORED_NODES) throw JSONException("stored node tree is too large")
        val kindId = requiredString(root, "kind")
        val kind = enumById(kindId, NodeKind.entries) { it.id }
        val props = linkedMapOf<String, NodeValue>()
        objectValue(root, "props", JSONObject()).keys().asSequence().sorted().forEach { key ->
            props[key] = decodeNodeValue(requiredObject(objectValue(root, "props", JSONObject()), key))
        }
        val children = objectArray(root, "children").map { decodeNode(it, depth + 1, budget) }
        return NodeDraft(kind, props, children)
    }

    private fun decodeNodeValue(root: JSONObject): NodeValue = when (requiredString(root, "kind")) {
        "text" -> NodeValue.Text(requiredString(root, "value"))
        "number" -> NodeValue.Number(requiredDouble(root, "value"))
        "bool" -> NodeValue.Bool(requiredBoolean(root, "value"))
        "color" -> NodeValue.Color(requiredLong(root, "value"))
        else -> throw JSONException("unknown stored node value kind")
    }

    // ----------------------------------------------------------------------------------- settings

    private fun encodeSetting(setting: SettingDraft): JSONObject = when (setting) {
        is BoolSetting -> settingBase(setting, "bool")
            .put("default", setting.default)
        is IntSetting -> settingBase(setting, "int")
            .put("default", setting.default)
            .also { root -> setting.min?.let { root.put("min", it) } }
            .also { root -> setting.max?.let { root.put("max", it) } }
        is FloatSetting -> settingBase(setting, "float")
            .put("default", numberValue(setting.default))
            .also { root -> setting.min?.let { root.put("min", numberValue(it)) } }
            .also { root -> setting.max?.let { root.put("max", numberValue(it)) } }
        is EnumSetting -> settingBase(setting, "enum")
            .put("options", stringArray(setting.options))
            .put("default", setting.default)
        is StringSetting -> settingBase(setting, "string")
            .put("default", setting.default)
            .put("maxLength", setting.maxLength)
    }

    private fun settingBase(setting: SettingDraft, type: String): JSONObject = JSONObject()
        .put("type", type)
        .put("id", setting.id)
        .put("label", setting.label)
        .put("description", setting.description)

    private fun decodeSetting(root: JSONObject): SettingDraft {
        val id = requiredString(root, "id")
        val label = string(root, "label", "")
        val description = string(root, "description", "")
        return when (requiredString(root, "type")) {
            "bool" -> BoolSetting(id, requiredBoolean(root, "default"), label, description)
            "int" -> IntSetting(
                id = id,
                default = requiredLong(root, "default"),
                min = optionalLong(root, "min"),
                max = optionalLong(root, "max"),
                label = label,
                description = description,
            )
            "float" -> FloatSetting(
                id = id,
                default = requiredDouble(root, "default"),
                min = optionalDouble(root, "min"),
                max = optionalDouble(root, "max"),
                label = label,
                description = description,
            )
            "enum" -> EnumSetting(
                id = id,
                options = stringList(root, "options"),
                default = requiredString(root, "default"),
                label = label,
                description = description,
            )
            "string" -> StringSetting(
                id = id,
                default = requiredString(root, "default"),
                maxLength = int(root, "maxLength", 256),
                label = label,
                description = description,
            )
            else -> throw JSONException("unknown stored setting type")
        }
    }

    // --------------------------------------------------------------------------------------- rules

    private fun encodeRule(rule: RuleDraft): JSONObject = JSONObject()
        .put("id", rule.id)
        .put("trigger", rule.trigger.name)
        .put("conditions", array(rule.conditions.map { condition ->
            JSONObject()
                .put("variable", condition.variable.name)
                .put("op", condition.op.name)
                .put("value", numberValue(condition.value))
        }))
        .put("actionIds", stringArray(rule.actionIds.map { it.id }))

    private fun decodeRule(root: JSONObject): RuleDraft = RuleDraft(
        id = requiredString(root, "id"),
        trigger = enumByName(requiredString(root, "trigger"), Trigger.entries),
        conditions = objectArray(root, "conditions").map { condition ->
            RuleCondition(
                variable = enumByName(requiredString(condition, "variable"), RuleVariable.entries),
                op = enumByName(requiredString(condition, "op"), Operator.entries),
                value = requiredDouble(condition, "value"),
            )
        },
        actionIds = stringList(root, "actionIds").map { id ->
            enumById(id, BrokerActionId.entries) { it.id }
        },
    )

    // --------------------------------------------------------------------------------------- theme

    private fun encodeTheme(theme: ThemeDraft): JSONObject = JSONObject()
        .put("name", theme.name)
        .put("schema", theme.schema)
        .put("dark", theme.dark)
        .put("amoled", theme.amoled)
        .put("primary", theme.primary)
        .put("onPrimary", theme.onPrimary)
        .put("background", theme.background)
        .put("surface", theme.surface)
        .put("onSurface", theme.onSurface)
        .put("accent", theme.accent)
        .put("secondary", theme.secondary)
        .put("tertiary", theme.tertiary)
        .put("surfaceVariant", theme.surfaceVariant)
        .put("onSurfaceVariant", theme.onSurfaceVariant)
        .put("outline", theme.outline)
        .put("error", theme.error)
        .put("onError", theme.onError)
        .put("cornerRadiusDp", theme.cornerRadiusDp)
        .put("uiScale", numberValue(theme.uiScale.toDouble()))
        .put("font", theme.font)
        .put("typographyWeight", theme.typographyWeight)
        .put("typographyScale", numberValue(theme.typographyScale.toDouble()))
        .also { root -> theme.glass?.let { root.put("glass", encodeGlass(it)) } }
        .also { root -> theme.glitch?.let { root.put("glitch", encodeGlitch(it)) } }

    private fun decodeTheme(root: JSONObject): ThemeDraft {
        val defaults = ThemeDraft()
        return ThemeDraft(
            name = string(root, "name", defaults.name),
            schema = int(root, "schema", defaults.schema),
            dark = boolean(root, "dark", defaults.dark),
            amoled = boolean(root, "amoled", defaults.amoled),
            primary = long(root, "primary", defaults.primary),
            onPrimary = long(root, "onPrimary", defaults.onPrimary),
            background = long(root, "background", defaults.background),
            surface = long(root, "surface", defaults.surface),
            onSurface = long(root, "onSurface", defaults.onSurface),
            accent = long(root, "accent", defaults.accent),
            secondary = long(root, "secondary", defaults.secondary),
            tertiary = long(root, "tertiary", defaults.tertiary),
            surfaceVariant = long(root, "surfaceVariant", defaults.surfaceVariant),
            onSurfaceVariant = long(root, "onSurfaceVariant", defaults.onSurfaceVariant),
            outline = long(root, "outline", defaults.outline),
            error = long(root, "error", defaults.error),
            onError = long(root, "onError", defaults.onError),
            cornerRadiusDp = int(root, "cornerRadiusDp", defaults.cornerRadiusDp),
            uiScale = float(root, "uiScale", defaults.uiScale),
            font = string(root, "font", defaults.font),
            typographyWeight = string(root, "typographyWeight", defaults.typographyWeight),
            typographyScale = float(root, "typographyScale", defaults.typographyScale),
            glass = objectOrNull(root, "glass")?.let(::decodeGlass),
            glitch = objectOrNull(root, "glitch")?.let(::decodeGlitch),
        )
    }

    private fun encodeGlass(glass: GlassDraft): JSONObject = JSONObject()
        .put("intensity", numberValue(glass.intensity.toDouble()))
        .put("transparency", numberValue(glass.transparency.toDouble()))
        .put("gloss", numberValue(glass.gloss.toDouble()))
        .put("blurRadiusDp", glass.blurRadiusDp)
        .put("sheen", glass.sheen)
        .put("sheenPeriodMs", glass.sheenPeriodMs)
        .put("spring", glass.spring)
        .put("hardwareReactive", glass.hardwareReactive)
        .also { root -> glass.intensitySetting?.let { root.put("intensitySetting", it) } }

    private fun decodeGlass(root: JSONObject): GlassDraft {
        val defaults = GlassDraft()
        return GlassDraft(
            intensity = float(root, "intensity", defaults.intensity),
            transparency = float(root, "transparency", defaults.transparency),
            gloss = float(root, "gloss", defaults.gloss),
            blurRadiusDp = int(root, "blurRadiusDp", defaults.blurRadiusDp),
            sheen = boolean(root, "sheen", defaults.sheen),
            sheenPeriodMs = int(root, "sheenPeriodMs", defaults.sheenPeriodMs),
            spring = string(root, "spring", defaults.spring),
            hardwareReactive = boolean(root, "hardwareReactive", defaults.hardwareReactive),
            intensitySetting = nullableString(root, "intensitySetting"),
        )
    }

    private fun encodeGlitch(glitch: GlitchDraft): JSONObject = JSONObject()
        .put("intensity", numberValue(glitch.intensity.toDouble()))
        .put("slices", glitch.slices)
        .put("sliceOffsetDp", glitch.sliceOffsetDp)
        .put("ghostOffsetDp", glitch.ghostOffsetDp)
        .put("fractureLines", glitch.fractureLines)
        .put("noise", glitch.noise)
        .put("microJitter", glitch.microJitter)
        .put("hardwareReactive", glitch.hardwareReactive)
        .put("burstMinMs", glitch.burstMinMs)
        .put("burstMaxMs", glitch.burstMaxMs)

    private fun decodeGlitch(root: JSONObject): GlitchDraft {
        val defaults = GlitchDraft()
        return GlitchDraft(
            intensity = float(root, "intensity", defaults.intensity),
            slices = int(root, "slices", defaults.slices),
            sliceOffsetDp = int(root, "sliceOffsetDp", defaults.sliceOffsetDp),
            ghostOffsetDp = int(root, "ghostOffsetDp", defaults.ghostOffsetDp),
            fractureLines = boolean(root, "fractureLines", defaults.fractureLines),
            noise = boolean(root, "noise", defaults.noise),
            microJitter = boolean(root, "microJitter", defaults.microJitter),
            hardwareReactive = boolean(root, "hardwareReactive", defaults.hardwareReactive),
            burstMinMs = int(root, "burstMinMs", defaults.burstMinMs),
            burstMaxMs = int(root, "burstMaxMs", defaults.burstMaxMs),
        )
    }

    // -------------------------------------------------------------------------------- permissions

    private fun encodePermissionScopes(scopes: Map<String, Set<String>>): JSONObject = JSONObject().also { root ->
        scopes.toSortedMap().forEach { (permission, values) ->
            root.put(permission, stringArray(values.sorted()))
        }
    }

    private fun decodePermissionScopes(root: JSONObject): Map<String, Set<String>> {
        if (!root.has("permissionScopes")) return emptyMap()
        val objectValue = requiredObject(root, "permissionScopes")
        val out = linkedMapOf<String, Set<String>>()
        objectValue.keys().asSequence().sorted().forEach { permission ->
            out[permission] = stringList(objectValue, permission).toCollection(linkedSetOf())
        }
        return out
    }

    private fun permissions(root: JSONObject, default: Set<String>): Set<String> {
        if (!root.has("permissions")) return default
        return stringList(root, "permissions").toCollection(linkedSetOf())
    }

    // -------------------------------------------------------------------------------- JSON helpers

    private fun array(values: List<Any>): JSONArray = JSONArray().also { array -> values.forEach(array::put) }
    private fun stringArray(values: List<String>): JSONArray = array(values)

    private fun objectArray(root: JSONObject, key: String): List<JSONObject> {
        if (!root.has(key)) return emptyList()
        val value = root.opt(key)
        if (value !is JSONArray) throw JSONException("\"$key\" is not an array")
        return (0 until value.length()).map { index ->
            value.opt(index) as? JSONObject ?: throw JSONException("$key[$index] is not an object")
        }
    }

    private fun stringList(root: JSONObject, key: String): List<String> {
        val value = root.opt(key)
        if (value !is JSONArray) throw JSONException("\"$key\" is not an array")
        return (0 until value.length()).map { index ->
            value.opt(index) as? String ?: throw JSONException("$key[$index] is not a string")
        }
    }

    private fun requiredObject(root: JSONObject, key: String): JSONObject =
        root.opt(key) as? JSONObject ?: throw JSONException("\"$key\" is not an object")

    private fun objectValue(root: JSONObject, key: String, default: JSONObject): JSONObject {
        if (!root.has(key)) return default
        return requiredObject(root, key)
    }

    private fun objectOrNull(root: JSONObject, key: String): JSONObject? {
        if (!root.has(key) || root.isNull(key)) return null
        return requiredObject(root, key)
    }

    private fun requiredString(root: JSONObject, key: String): String =
        root.opt(key) as? String ?: throw JSONException("\"$key\" is not a string")

    private fun nullableString(root: JSONObject, key: String): String? {
        if (!root.has(key) || root.isNull(key)) return null
        return requiredString(root, key)
    }

    private fun string(root: JSONObject, key: String, default: String): String {
        if (!root.has(key)) return default
        return requiredString(root, key)
    }

    private fun requiredBoolean(root: JSONObject, key: String): Boolean =
        root.opt(key) as? Boolean ?: throw JSONException("\"$key\" is not a boolean")

    private fun boolean(root: JSONObject, key: String, default: Boolean): Boolean {
        if (!root.has(key)) return default
        return requiredBoolean(root, key)
    }

    private fun requiredLong(root: JSONObject, key: String): Long {
        val value = root.opt(key) as? Number ?: throw JSONException("\"$key\" is not a number")
        val asDouble = value.toDouble()
        if (!asDouble.isFinite() || asDouble % 1.0 != 0.0 || asDouble < Long.MIN_VALUE || asDouble > Long.MAX_VALUE) {
            throw JSONException("\"$key\" is not a whole number")
        }
        return value.toLong()
    }

    private fun optionalLong(root: JSONObject, key: String): Long? =
        if (!root.has(key) || root.isNull(key)) null else requiredLong(root, key)

    private fun long(root: JSONObject, key: String, default: Long): Long =
        if (!root.has(key)) default else requiredLong(root, key)

    private fun int(root: JSONObject, key: String, default: Int): Int {
        if (!root.has(key)) return default
        val value = requiredLong(root, key)
        if (value !in Int.MIN_VALUE..Int.MAX_VALUE) throw JSONException("\"$key\" is outside Int range")
        return value.toInt()
    }

    private fun numberValue(value: Double): Any = when {
        value.isNaN() -> "NaN"
        value == Double.POSITIVE_INFINITY -> "Infinity"
        value == Double.NEGATIVE_INFINITY -> "-Infinity"
        else -> value
    }

    private fun requiredDouble(root: JSONObject, key: String): Double = when (val value = root.opt(key)) {
        is Number -> value.toDouble()
        "NaN" -> Double.NaN
        "Infinity" -> Double.POSITIVE_INFINITY
        "-Infinity" -> Double.NEGATIVE_INFINITY
        else -> throw JSONException("\"$key\" is not a number")
    }

    private fun optionalDouble(root: JSONObject, key: String): Double? =
        if (!root.has(key) || root.isNull(key)) null else requiredDouble(root, key)

    private fun double(root: JSONObject, key: String, default: Double): Double =
        if (!root.has(key)) default else requiredDouble(root, key)

    private fun float(root: JSONObject, key: String, default: Float): Float =
        if (!root.has(key)) default else requiredDouble(root, key).toFloat()

    private fun <T : Enum<T>> enumByName(name: String, values: Iterable<T>): T =
        values.firstOrNull { it.name == name } ?: throw JSONException("unknown enum value \"$name\"")

    private fun <T> enumById(id: String, values: Iterable<T>, idOf: (T) -> String): T =
        values.firstOrNull { idOf(it) == id } ?: throw JSONException("unknown id \"$id\"")
}
