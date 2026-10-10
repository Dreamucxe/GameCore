package com.gamecore.plugin.sdk

import org.json.JSONArray
import org.json.JSONObject

/**
 * The Kotlin DSL for defining a plugin. A `plugin { }` block builds an in-memory description
 * that [PluginPackager] turns into a `.gcplugin` zip.
 *
 * This is a build-time authoring aid, not a runtime extension point: the DSL only ever emits
 * JSON and asset bytes, exactly the data the host loads. There is no hook, no callback, no
 * code path from this DSL into the running app — a plugin "runs" by being interpreted, never
 * by executing anything written here.
 */
@DslMarker
annotation class PluginDsl

fun plugin(id: String, name: String, block: PluginBuilder.() -> Unit): PluginDefinition =
    PluginBuilder(id, name).apply(block).build()

@PluginDsl
class PluginBuilder(private val id: String, private val name: String) {
    var version: Int = 1
    var versionName: String = "1.0"
    var api: Int = Sdk.API
    var minHostVersion: Int = Sdk.HOST_VERSION_CODE
    var developer: String = "Unknown"
    var description: String = ""
    var changelog: String = ""

    private val permissions = mutableListOf<RequestedPermission>()
    private val sections = linkedMapOf<String, ByteArray>()
    private val declared = linkedSetOf<String>()

    /** Request a permission, optionally scoped (scopes only meaningful for read_approved_data/modify_settings). */
    fun request(permission: String, scopes: Set<String> = emptySet()) {
        permissions += RequestedPermission(permission, scopes)
    }

    /** Attach a theme section built with [ThemeBuilder]. */
    fun theme(block: ThemeBuilder.() -> Unit) {
        val json = ThemeBuilder().apply(block).toJson()
        sections["theme.json"] = json.toString(2).toByteArray()
        declared += "theme"
    }

    /** Attach automation rules built with [RulesBuilder]. */
    fun rules(block: RulesBuilder.() -> Unit) {
        val json = RulesBuilder().apply(block).toJson()
        sections["rules/rules.json"] = json.toString(2).toByteArray()
        declared += "rules"
    }

    /**
     * Attach the overlay module built with [PillBuilder].
     *
     * Declaring the section and writing the file are one act here, which is the point: the
     * host rejects a package whose `declares` and contents disagree in *either* direction, so
     * the SDK never lets them be written separately.
     *
     * A module is drawn over a game, so the plugin must also request
     * [Permissions.CREATE_OVERLAY], and any `stat` node needs
     * [Permissions.READ_APPROVED_DATA] scoped to that stat's [OverlayStats.scopeFor] — a
     * missing grant is not malformed JSON, so no codec will catch it for you.
     */
    fun pill(block: PillBuilder.() -> Unit) {
        val json = PillBuilder().apply(block).toJson()
        sections["pill.json"] = json.toString(2).toByteArray()
        declared += "pill"
    }

    /** Attach a raw section file (escape hatch for sections without a dedicated builder yet). */
    fun section(path: String, declares: String, bytes: ByteArray) {
        sections[path] = bytes
        declared += declares
    }

    internal fun build(): PluginDefinition {
        val manifest = JSONObject().apply {
            put("format", Sdk.FORMAT)
            put("id", id)
            put("name", name)
            put("version", version)
            put("versionName", versionName)
            put("api", api)
            put("minHostVersion", minHostVersion)
            put("developer", developer)
            if (description.isNotEmpty()) put("description", description)
            if (changelog.isNotEmpty()) put("changelog", changelog)
            if (permissions.isNotEmpty()) {
                put("permissions", JSONArray().apply {
                    permissions.forEach { p ->
                        put(JSONObject().apply {
                            put("id", p.id)
                            if (p.scopes.isNotEmpty()) put("scopes", JSONArray(p.scopes.toList()))
                        })
                    }
                })
            }
            if (declared.isNotEmpty()) put("declares", JSONArray(declared.toList()))
        }
        return PluginDefinition(
            manifestJson = manifest.toString(2).toByteArray(),
            sectionFiles = sections.toMap(),
        )
    }

    data class RequestedPermission(val id: String, val scopes: Set<String>)
}

/** The bytes of a plugin, ready to be zipped. */
data class PluginDefinition(
    val manifestJson: ByteArray,
    val sectionFiles: Map<String, ByteArray>,
)

/**
 * Builds a plugin's `theme.json`: the six colours GameCore repaints itself with, plus a font,
 * a corner radius and a density nudge.
 *
 * Every value is checked here, and the two kinds of host failure are worth telling apart,
 * because they are why checking at all is worth the lines:
 *
 * - A malformed colour or an unknown font is a **hard rejection of the whole theme**. The
 *   plugin installs, its switch turns on, and the app stays exactly as it was. Nothing in the
 *   UI distinguishes that from a theme the user has not picked yet.
 * - A corner radius or UI scale outside the host's range is **clamped**. The theme does load,
 *   and it loads looking like something the author never chose.
 *
 * The defaults are GameCore's own dark palette, so a `theme { }` block that sets nothing but a
 * name is valid and legible. All eleven values are emitted every time, including the ones left
 * at their default: the host requires the six colours and defaults the rest, and a theme file
 * that spells out what it means is one a developer can read back and diff.
 */
@PluginDsl
class ThemeBuilder {
    var name: String = "Theme"
    var dark: Boolean = true
    var primary: String = "#4F8CFF"
    var onPrimary: String = "#FFFFFF"
    var background: String = "#000000"
    var surface: String = "#101216"
    var onSurface: String = "#E6E8EC"
    var accent: String = "#4F8CFF"
    var cornerRadiusDp: Int = 16

    /** One of [ThemeFonts]. A theme names a font the app already bundles; it cannot ship one. */
    var font: String = ThemeFonts.SYSTEM

    /** A density nudge in [ThemeBounds.MIN_UI_SCALE]..[ThemeBounds.MAX_UI_SCALE]. */
    var uiScale: Double = 1.0

    internal fun toJson(): JSONObject {
        require(name.isNotBlank()) { "a theme needs a name" }
        val fontId = font.trim().lowercase()
        require(fontId in ThemeFonts.ALL) {
            "theme font is \"$font\"; a theme names one of GameCore's own bundled faces " +
                "(${ThemeFonts.ALL.joinToString()}) and the host rejects the whole theme otherwise"
        }
        require(cornerRadiusDp in ThemeBounds.MIN_CORNER_DP..ThemeBounds.MAX_CORNER_DP) {
            "cornerRadiusDp is $cornerRadiusDp; the host clamps to " +
                "${ThemeBounds.MIN_CORNER_DP}..${ThemeBounds.MAX_CORNER_DP}, so a value outside " +
                "it ships as a corner the author never picked"
        }
        require(uiScale in ThemeBounds.MIN_UI_SCALE..ThemeBounds.MAX_UI_SCALE) {
            "uiScale is $uiScale; the host clamps to ${ThemeBounds.MIN_UI_SCALE}.." +
                "${ThemeBounds.MAX_UI_SCALE} — a theme may lean the UI, not rebuild it"
        }

        return JSONObject().apply {
            put("name", plainText("theme name", name, ThemeBounds.MAX_NAME_LENGTH))
            put("dark", dark)
            put("primary", hexColor("theme primary", primary))
            put("onPrimary", hexColor("theme onPrimary", onPrimary))
            put("background", hexColor("theme background", background))
            put("surface", hexColor("theme surface", surface))
            put("onSurface", hexColor("theme onSurface", onSurface))
            put("accent", hexColor("theme accent", accent))
            put("cornerRadiusDp", cornerRadiusDp)
            put("font", fontId)
            put("uiScale", uiScale)
        }
    }
}

/**
 * Builds a plugin's `rules/rules.json`: a flat list of "when TRIGGER, if CONDITIONS, do
 * ACTIONS" rules.
 *
 * **A rule names an action; it never carries one.** There is no command string, no settings
 * key and no value anywhere in this grammar — an action is an id from [Actions], and what it
 * does, what it clamps to and whether it needs a second opt-in is the broker's business, not
 * the plugin's. That is the whole reason automations can exist at all without a plugin ever
 * holding a shell.
 *
 * Everything a rule names is resolved here, because each of the four names fails in a
 * different way and only one of them is loud:
 *
 * - An unknown **trigger**, **operator** or **action id** is a rejection of the entire rules
 *   file — one typo costs the plugin every rule in it, not just the one that carried it.
 * - An unknown **variable** is the quiet one, and the reason [RuleVariables] exists: the host
 *   copies the name through unresolved and the engine reads a name it cannot find as a
 *   condition that is simply not met, so the rule installs and never once fires.
 *
 * ```kotlin
 * rules {
 *     rule("boost", Triggers.GAME_LAUNCHED, listOf(Actions.SET_PEAK_REFRESH_RATE))
 *     rule(
 *         "cool_down", Triggers.THERMAL_HIGH,
 *         actions = listOf(Actions.SET_MIN_REFRESH_RATE),
 *         conditions = listOf(Condition(RuleVariables.TEMP, RuleOperators.GTE, 45.0)),
 *     )
 * }
 * ```
 */
@PluginDsl
class RulesBuilder {
    private val rules = JSONArray()
    private val ids = linkedSetOf<String>()

    /**
     * One rule. [trigger] is from [Triggers], each action from [Actions], each condition built
     * with [Condition].
     *
     * [actions] must be non-empty: the host's validator refuses an action-less rule rather than
     * treating it as a no-op, so an empty list is a rules file that does not load.
     */
    fun rule(
        id: String,
        trigger: String,
        actions: List<String>,
        conditions: List<Condition> = emptyList(),
    ) {
        require(id.isNotBlank()) { "a rule needs an id" }
        val ruleId = plainText("rule id", id, OverlayBounds.MAX_ID_LENGTH)
        require(ids.add(ruleId)) {
            "two rules share the id \"$ruleId\"; the host names a rule by its id when it reports " +
                "a problem with one, so a duplicate makes that message ambiguous"
        }

        val triggerName = trigger.trim().uppercase()
        require(triggerName in Triggers.ALL) {
            "rule \"$ruleId\" has trigger \"$trigger\"; expected one of " +
                "${Triggers.ALL.joinToString()} — the host rejects the whole rules file on an " +
                "unknown trigger, not just this rule"
        }

        require(actions.isNotEmpty()) {
            "rule \"$ruleId\" has no actions; the host's validator refuses an action-less rule"
        }
        require(actions.size <= RuleBounds.MAX_ACTIONS_PER_RULE) {
            "rule \"$ruleId\" has ${actions.size} actions; the cap is " +
                "${RuleBounds.MAX_ACTIONS_PER_RULE}, and a rule over it is refused on install or " +
                "truncated on evaluation"
        }
        val actionIds = actions.map { raw ->
            val actionId = raw.trim().lowercase()
            require(actionId in Actions.ALL) {
                "rule \"$ruleId\" names action \"$raw\"; a rule may only name a broker action " +
                    "(${Actions.ALL.joinToString()})"
            }
            actionId
        }

        require(conditions.size <= RuleBounds.MAX_CONDITIONS_PER_RULE) {
            "rule \"$ruleId\" has ${conditions.size} conditions; the cap is " +
                "${RuleBounds.MAX_CONDITIONS_PER_RULE}"
        }
        val encoded = conditions.map { c -> c.toJson(ruleId) }

        require(rules.length() < RuleBounds.MAX_RULES) {
            "a rules file holds at most ${RuleBounds.MAX_RULES} rules"
        }

        rules.put(
            JSONObject().apply {
                put("id", ruleId)
                put("trigger", triggerName)
                put("actions", JSONArray(actionIds))
                if (encoded.isNotEmpty()) put("conditions", JSONArray(encoded))
            },
        )
    }

    /**
     * A single comparison: `variable op value`. No boolean operators and no nesting — a rule's
     * conditions are a flat conjunction, which is what keeps a rules file a decision table
     * rather than a program.
     *
     * [variable] is from [RuleVariables] and [op] from [RuleOperators]; see this class's note
     * for why the variable is the one worth getting right.
     */
    data class Condition(val variable: String, val op: String, val value: Double) {

        internal fun toJson(ruleId: String): JSONObject {
            val variableId = variable.trim().lowercase()
            require(variableId in RuleVariables.ALL) {
                "rule \"$ruleId\" compares against \"$variable\"; the stat feed publishes " +
                    "${RuleVariables.ALL.joinToString()}, and a name outside that set is not an " +
                    "error on the host — it is a condition that is never met, so the rule " +
                    "installs and silently never fires"
            }
            val opName = op.trim().uppercase()
            require(opName in RuleOperators.ALL) {
                "rule \"$ruleId\" uses operator \"$op\"; expected one of " +
                    RuleOperators.ALL.joinToString()
            }
            require(value.isFinite()) { "rule \"$ruleId\" compares against $value" }
            return JSONObject().apply {
                put("variable", variableId)
                put("op", opName)
                put("value", value)
            }
        }
    }

    internal fun toJson(): JSONObject {
        require(rules.length() > 0) { "a rules section needs at least one rule" }
        return JSONObject().put("rules", rules)
    }
}
