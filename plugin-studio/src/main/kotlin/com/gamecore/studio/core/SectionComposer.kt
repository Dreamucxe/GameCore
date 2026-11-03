package com.gamecore.studio.core

import com.gamecore.studio.model.BoolSetting
import com.gamecore.studio.model.EnumSetting
import com.gamecore.studio.model.FloatSetting
import com.gamecore.studio.model.IntSetting
import com.gamecore.studio.model.NodeDraft
import com.gamecore.studio.model.NodeKind
import com.gamecore.studio.model.NodeValue
import com.gamecore.studio.model.OverlayDraft
import com.gamecore.studio.model.RuleDraft
import com.gamecore.studio.model.ScreenDraft
import com.gamecore.studio.model.SettingDraft
import com.gamecore.studio.model.StringSetting
import com.gamecore.studio.model.WidgetDraft
import org.json.JSONArray
import org.json.JSONObject

/**
 * Deterministic writers for Plugin Studio's declarative section files.
 *
 * The writers do no permission work and execute no authored value. A node prop is always one of
 * four JSON primitives, text that resembles the host's action/colour sigils is escaped as literal
 * text, and ids from closed enums are emitted from those enums rather than copied from arbitrary
 * strings. Call the model's `problems()` before export; the defensive numeric fallbacks here merely
 * keep an in-progress invalid draft serialisable.
 */
object SectionComposer {

    /** A host `screens` envelope (format/version 1), preserving the authored screen order. */
    fun screensJson(screens: List<ScreenDraft>): String = render(
        stableObject(
            "format" to SCREENS_FORMAT,
            "version" to FORMAT_VERSION,
            "screens" to StableArray(screens.map(::screenObject)),
        ),
    )

    /** A host `pill.json` envelope (format/version 1). */
    fun overlayJson(overlay: OverlayDraft): String = render(
        stableObject(
            "format" to PILL_FORMAT,
            "version" to FORMAT_VERSION,
            "id" to canonicalSlug(overlay.id),
            "title" to overlay.title,
            "anchor" to stableObject(
                "x" to finite(overlay.xFraction),
                "y" to finite(overlay.yFraction),
            ),
            "size" to overlay.size.id,
            "opacity" to overlay.opacity,
            "content" to nodeObject(overlay.content),
        ),
    )

    /** A schema-1 `settings.json`; v1 deliberately has no `schema` key. */
    fun settingsJson(settings: List<SettingDraft>): String = render(
        stableObject("settings" to StableArray(settings.map(::settingObject))),
    )

    /** A host `widgets/<id>.json` envelope (format/version 1). */
    fun widgetJson(widget: WidgetDraft): String = render(
        stableObject(
            "format" to WIDGET_FORMAT,
            "version" to FORMAT_VERSION,
            "id" to canonicalSlug(widget.id),
            "title" to widget.title,
            "size" to widget.size.id,
            "content" to nodeObject(widget.content),
        ),
    )

    /**
     * A host rules document. Conditions remain a flat AND and actions remain one sequential list;
     * no branch, parallel, schedule, retry, command, value, or code field exists in this shape.
     */
    fun rulesJson(rules: List<RuleDraft>): String = render(
        stableObject("rules" to StableArray(rules.map(::ruleObject))),
    )

    /**
     * One host component node.
     *
     * Props are sorted by key so a caller's choice of `Map` implementation cannot perturb output.
     * Empty `props`/`children` are omitted exactly as the host codec's own encoder omits them.
     */
    fun nodeJson(node: NodeDraft): JSONObject = nodeObject(node).toJsonObject()

    // ----------------------------------------------------------------------------------- sections

    private fun screenObject(screen: ScreenDraft): StableObject = stableObject(
        buildList {
            add("id" to canonicalSlug(screen.id))
            add("title" to screen.title)
            screen.icon?.let { add("icon" to canonicalId(it)) }
            add("content" to nodeObject(screen.root))
        },
    )

    private fun settingObject(setting: SettingDraft): StableObject = when (setting) {
        is BoolSetting -> stableObject(
            "id" to canonicalSlug(setting.id),
            "type" to "bool",
            "default" to setting.default,
        )
        is IntSetting -> stableObject(
            buildList {
                add("id" to canonicalSlug(setting.id))
                add("type" to "int")
                add("default" to setting.default)
                setting.min?.let { add("min" to it) }
                setting.max?.let { add("max" to it) }
            },
        )
        is FloatSetting -> stableObject(
            buildList {
                add("id" to canonicalSlug(setting.id))
                add("type" to "float")
                add("default" to finite(setting.default))
                setting.min?.let { add("min" to finite(it)) }
                setting.max?.let { add("max" to finite(it)) }
            },
        )
        is EnumSetting -> stableObject(
            "id" to canonicalSlug(setting.id),
            "type" to "enum",
            "default" to setting.default,
            "options" to StableArray(setting.options),
        )
        is StringSetting -> stableObject(
            "id" to canonicalSlug(setting.id),
            "type" to "string",
            "default" to setting.default,
            "maxLength" to setting.maxLength,
        )
    }

    private fun ruleObject(rule: RuleDraft): StableObject = stableObject(
        buildList {
            add("id" to rule.id.trim())
            add("trigger" to rule.trigger.name)
            add("actions" to StableArray(rule.actionIds.map { it.id }))
            if (rule.conditions.isNotEmpty()) {
                add(
                    "conditions" to StableArray(
                        rule.conditions.map { condition ->
                            stableObject(
                                "variable" to condition.variable.id,
                                "op" to condition.op.name,
                                "value" to finite(condition.value),
                            )
                        },
                    ),
                )
            }
        },
    )

    // -------------------------------------------------------------------------------------- nodes

    private fun nodeObject(node: NodeDraft): StableObject = stableObject(
        buildList {
            add("type" to node.kind.id)
            if (node.props.isNotEmpty()) {
                val props = node.props.entries
                    .sortedBy { it.key }
                    .map { (key, value) -> key to primitive(node.kind, key, value) }
                add("props" to stableObject(props))
            }
            if (node.children.isNotEmpty()) {
                add("children" to StableArray(node.children.map(::nodeObject)))
            }
        },
    )

    private fun primitive(kind: NodeKind, key: String, value: NodeValue): Any = when (value) {
        is NodeValue.Text -> literalText(canonicalNodeText(kind, key, value.value))
        is NodeValue.Number -> finite(value.value)
        is NodeValue.Bool -> value.value
        is NodeValue.Color -> colorHex(value.argb)
    }

    /** Known id-bearing props are normalised; labels and literal series stay byte-for-byte text. */
    private fun canonicalNodeText(kind: NodeKind, key: String, value: String): String = when {
        kind == NodeKind.STAT && key == "stat" -> canonicalId(value)
        kind == NodeKind.CHART && key in LIVE_CHART_KEYS -> canonicalId(value)
        kind == NodeKind.IMAGE && key == "icon" -> canonicalId(value)
        else -> value
    }

    /**
     * A single leading `@` means ActionRef to the host and a valid `#` literal means Color. Prefixing
     * the host's `@@` literal sigil makes either round-trip as [NodeValue.Text], never as an action or
     * colour. Existing leading `@@` text receives the same prefix and therefore remains unchanged
     * after the host removes exactly one literal sigil.
     */
    private fun literalText(value: String): String =
        if (value.startsWith("@") || COLOR_LITERAL.matches(value)) "@@$value" else value

    private fun colorHex(raw: Long): String {
        val argb = raw and 0xFFFF_FFFFL
        val alpha = (argb ushr 24) and 0xFF
        return if (alpha == 0xFFL) {
            "#%06X".format(argb and 0xFF_FFFFL)
        } else {
            "#%08X".format(argb)
        }
    }

    // -------------------------------------------------------------------------- stable JSON writer

    /** A tiny ordered representation used because JSONObject does not promise map iteration order. */
    private data class StableObject(val fields: List<Pair<String, Any>>)
    private data class StableArray(val values: List<Any>)

    private fun stableObject(vararg fields: Pair<String, Any>): StableObject = StableObject(fields.toList())
    private fun stableObject(fields: List<Pair<String, Any>>): StableObject = StableObject(fields)

    private fun StableObject.toJsonObject(): JSONObject = JSONObject().also { json ->
        fields.forEach { (key, value) -> json.put(key, value.toOrgJson()) }
    }

    private fun Any.toOrgJson(): Any = when (this) {
        is StableObject -> toJsonObject()
        is StableArray -> JSONArray().also { array -> values.forEach { array.put(it.toOrgJson()) } }
        else -> this
    }

    private fun render(value: Any): String = when (value) {
        is StableObject -> value.fields.joinToString(separator = ",", prefix = "{", postfix = "}") { (key, item) ->
            jsonString(key) + ":" + render(item)
        }
        is StableArray -> value.values.joinToString(separator = ",", prefix = "[", postfix = "]") { render(it) }
        is String -> jsonString(value)
        is Boolean -> value.toString()
        is kotlin.Number -> jsonNumber(value)
        else -> error("unsupported JSON value ${value::class.qualifiedName}")
    }

    private fun jsonNumber(number: kotlin.Number): String {
        val value = number.toDouble()
        if (!value.isFinite()) return "0"
        return when (number) {
            is Byte, is Short, is Int, is Long -> number.toString()
            else -> number.toString().removeSuffix(".0")
        }
    }

    private fun jsonString(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character < ' ') {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }

    private fun canonicalSlug(id: String): String = id.trim().lowercase()
    private fun canonicalId(id: String): String = id.trim().lowercase()
    private fun finite(value: Double): Double = if (value.isFinite()) value else 0.0

    private const val FORMAT_VERSION = 1
    private const val SCREENS_FORMAT = "gamecore.plugin.screens"
    private const val PILL_FORMAT = "gamecore.plugin.pill"
    private const val WIDGET_FORMAT = "gamecore.plugin.widget"
    private val LIVE_CHART_KEYS = setOf("stat", "stat2")
    private val COLOR_LITERAL = Regex("^#(?:[0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$")
}
