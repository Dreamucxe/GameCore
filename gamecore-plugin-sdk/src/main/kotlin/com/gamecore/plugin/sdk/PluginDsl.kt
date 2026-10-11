package com.gamecore.plugin.sdk

import org.json.JSONArray
import org.json.JSONObject

/** The `type` the host reads a chart node by (`ComponentType.CHART.id`). */
private const val CHART_NODE_TYPE = "chart"

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
        val builder = ThemeBuilder().apply(block)
        sections["theme.json"] = builder.toJson().toString(2).toByteArray()
        declared += "theme"
        // Per-use api bump: a theme that touched a schema-2 feature needs a host that understands
        // it, so raise the manifest api to match. A pure v1 theme leaves api at Sdk.API (1).
        if (builder.schemaVersion() >= ThemeSchema.V2) {
            api = maxOf(api, ThemeSchema.V2)
        }
    }

    /** Attach automation rules built with [RulesBuilder]. */
    fun rules(block: RulesBuilder.() -> Unit) {
        val json = RulesBuilder().apply(block).toJson()
        sections["rules/rules.json"] = json.toString(2).toByteArray()
        declared += "rules"
    }

    /**
     * Attach a typed settings section built with [SettingsBuilder].
     *
     * Like [theme], this writes `settings.json` and declares the `settings` section in one act,
     * so the manifest's `declares` and the package contents can never disagree. Settings are an
     * API-3 feature, so declaring any raises the manifest `api` to [Sdk.API_V3]; a plugin that
     * declares none leaves `api` at [Sdk.API] and is byte-for-byte the file it was before this
     * existed.
     */
    fun settings(block: SettingsBuilder.() -> Unit) {
        val json = SettingsBuilder().apply(block).toJson()
        sections["settings.json"] = json.toString(2).toByteArray()
        declared += "settings"
        api = maxOf(api, Sdk.API_V3)
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
        // Per-use api bump, mirroring the schema-2 theme bump: a chart node and a motion stat are
        // each API-3 features, so a module that draws either needs a host that understands it. A
        // module with neither leaves api at Sdk.API, so its pill.json and manifest stay
        // byte-identical to a build that predates both.
        if (pillUsesChart(json) || pillUsesMotionReading(json)) {
            api = maxOf(api, Sdk.API_V3)
        }
    }

    /**
     * Attach one or more declarative screens built with [ScreensBuilder].
     *
     * Like [rules], declaring the section and writing the file are one act, so the host never sees a
     * manifest whose `declares` and screen files disagree. Declarative screens are a 3.x host feature,
     * so a package that ships one raises its manifest `api` to [Sdk.API_V3] — the same per-use bump
     * [theme] makes for a schema-2 theme. A plugin that reaches for none of the 3.x features stays at
     * [Sdk.API] and emits byte-identical output to a build that predates them.
     */
    fun screens(block: ScreensBuilder.() -> Unit) {
        val json = ScreensBuilder().apply(block).toJson()
        sections["screens/screens.json"] = json.toString(2).toByteArray()
        declared += "screens"
        api = maxOf(api, Sdk.API_V3)
    }

    /**
     * Attach one or more read-only dashboard widgets built with [WidgetsBuilder].
     *
     * Each widget is its own `widgets/<id>.json` file, and declaring the section and writing those
     * files are one act — like [screens], so the host never sees a manifest whose `declares` names
     * `widgets` with no file behind it, nor a widget file the manifest never declared. Dashboard
     * widgets are a 3.x host feature, so a package that ships any raises its manifest `api` to
     * [Sdk.API_V3] — the same per-use bump [screens] and [settings] make, and unconditional here for
     * the same reason: [WidgetsBuilder.toFiles] refuses an empty section, so reaching this bump means
     * at least one widget was declared. A plugin that calls this never leaves its `api` at [Sdk.API]
     * and emits byte-identical output to a build that predates the feature.
     *
     * A dashboard widget draws GameCore's own approved readings, so a plugin that ships one must also
     * request [Permissions.ADD_DASHBOARD_WIDGETS], and any `stat` node needs
     * [Permissions.READ_APPROVED_DATA] scoped to that stat's [OverlayStats.scopeFor] — a missing grant
     * is not malformed JSON, so no codec will catch it for you.
     */
    fun widgets(block: WidgetsBuilder.() -> Unit) {
        val files = WidgetsBuilder().apply(block).toFiles()
        files.forEach { (path, json) -> sections[path] = json.toString(2).toByteArray() }
        declared += "widgets"
        api = maxOf(api, Sdk.API_V3)
    }

    /** Attach a raw section file (escape hatch for sections without a dedicated builder yet). */
    fun section(path: String, declares: String, bytes: ByteArray) {
        sections[path] = bytes
        declared += declares
    }

    /** True when the finished pill tree contains a chart node at any depth. */
    private fun pillUsesChart(pill: JSONObject): Boolean {
        fun walk(node: JSONObject): Boolean {
            if (node.optString("type") == CHART_NODE_TYPE) return true
            val children = node.optJSONArray("children") ?: return false
            for (i in 0 until children.length()) {
                if (walk(children.getJSONObject(i))) return true
            }
            return false
        }
        return walk(pill.getJSONObject("content"))
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
 * Whether a built `pill.json` draws any [OverlayStats.MOTION_READINGS] stat. Walked from the finished
 * tree rather than tracked as the DSL builds, so a motion stat added any way at all is caught, and
 * iterative so a deeply nested module cannot overflow the stack while deciding its own api level.
 */
private fun pillUsesMotionReading(pill: JSONObject): Boolean {
    val root = pill.optJSONObject("content") ?: return false
    val pending = ArrayDeque<JSONObject>()
    pending.addLast(root)
    while (pending.isNotEmpty()) {
        val node = pending.removeLast()
        if (node.optString("type") == "stat") {
            val statId = node.optJSONObject("props")?.optString("stat")
            if (statId != null && statId in OverlayStats.MOTION_READINGS) return true
        }
        val children = node.optJSONArray("children") ?: continue
        for (i in 0 until children.length()) pending.addLast(children.getJSONObject(i))
    }
    return false
}

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
 * name is valid and legible. The eleven v1 values are emitted every time, including the ones left
 * at their default: the host requires the six colours and defaults the rest, and a theme file
 * that spells out what it means is one a developer can read back and diff. The schema-2 extras —
 * [amoled], [roles] and [typography] — are additive and appear only when the theme uses one, so a
 * theme that touches none of them is byte-for-byte the v1 file it was before they existed.
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

    /** From schema 2: base the dark theme on the true-black AMOLED scheme. Absent on a v1 theme. */
    var amoled: Boolean = false

    private var rolesBuilder: ThemeRolesBuilder? = null
    private var typographyBuilder: ThemeTypographyBuilder? = null

    /** From schema 2: recolour semantic roles beyond the v1 six. A role left unset is inherited. */
    fun roles(block: ThemeRolesBuilder.() -> Unit) {
        rolesBuilder = (rolesBuilder ?: ThemeRolesBuilder()).apply(block)
    }

    /** From schema 2: a type-ramp scale and/or one global weight. */
    fun typography(block: ThemeTypographyBuilder.() -> Unit) {
        typographyBuilder = (typographyBuilder ?: ThemeTypographyBuilder()).apply(block)
    }

    /**
     * The schema this theme serialises as: V2 the moment it uses any v2 feature, else V1. This
     * gates both the keys [toJson] emits and the manifest api bump in [PluginBuilder.theme].
     */
    internal fun schemaVersion(): Int =
        if (amoled || rolesBuilder?.any() == true || typographyBuilder?.any() == true) {
            ThemeSchema.V2
        } else {
            ThemeSchema.V1
        }

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
            // Additive v2 extras — emitted ONLY when a v2 feature was used, so a pure v1 theme is
            // byte-for-byte the 11-key file it has always been.
            if (schemaVersion() >= ThemeSchema.V2) {
                put("schema", ThemeSchema.V2)
                if (amoled) put("amoled", true)
                rolesBuilder?.toJsonOrNull()?.let { put("roles", it) }
                typographyBuilder?.toJsonOrNull()?.let { put("typography", it) }
            }
        }
    }
}

/**
 * The seven optional [ThemeRoles] a v2 theme may recolour. Each is validated as a hex colour the
 * same way the v1 colours are, and only the roles the author set are emitted.
 */
@PluginDsl
class ThemeRolesBuilder {
    var secondary: String? = null
    var tertiary: String? = null
    var surfaceVariant: String? = null
    var onSurfaceVariant: String? = null
    var outline: String? = null
    var error: String? = null
    var onError: String? = null

    internal fun any(): Boolean =
        listOf(secondary, tertiary, surfaceVariant, onSurfaceVariant, outline, error, onError)
            .any { it != null }

    internal fun toJsonOrNull(): JSONObject? {
        val json = JSONObject()
        secondary?.let { json.put(ThemeRoles.SECONDARY, hexColor("theme role secondary", it)) }
        tertiary?.let { json.put(ThemeRoles.TERTIARY, hexColor("theme role tertiary", it)) }
        surfaceVariant?.let { json.put(ThemeRoles.SURFACE_VARIANT, hexColor("theme role surfaceVariant", it)) }
        onSurfaceVariant?.let { json.put(ThemeRoles.ON_SURFACE_VARIANT, hexColor("theme role onSurfaceVariant", it)) }
        outline?.let { json.put(ThemeRoles.OUTLINE, hexColor("theme role outline", it)) }
        error?.let { json.put(ThemeRoles.ERROR, hexColor("theme role error", it)) }
        onError?.let { json.put(ThemeRoles.ON_ERROR, hexColor("theme role onError", it)) }
        return if (json.length() == 0) null else json
    }
}

/**
 * A v2 theme's type controls: a scale on the whole ramp and/or one global weight. The host clamps
 * the scale; this SDK refuses one out of range, the same bargain the corner radius and UI scale
 * strike — a value the host quietly adjusted is one the author never saw.
 */
@PluginDsl
class ThemeTypographyBuilder {
    /** Multiplies every role's size and line height together. In [ThemeBounds.MIN_TYPE_SCALE]..[ThemeBounds.MAX_TYPE_SCALE]. */
    var scale: Double = ThemeBounds.DEFAULT_TYPE_SCALE

    /** One of [ThemeWeights], or null to keep each role's own designed weight. */
    var weight: String? = null

    internal fun any(): Boolean = scale != ThemeBounds.DEFAULT_TYPE_SCALE || weight != null

    internal fun toJsonOrNull(): JSONObject? {
        val json = JSONObject()
        if (scale != ThemeBounds.DEFAULT_TYPE_SCALE) {
            require(scale in ThemeBounds.MIN_TYPE_SCALE..ThemeBounds.MAX_TYPE_SCALE) {
                "theme type scale is $scale; the host clamps to ${ThemeBounds.MIN_TYPE_SCALE}.." +
                    "${ThemeBounds.MAX_TYPE_SCALE}, so a value outside it ships as a ramp the author never chose"
            }
            json.put("scale", scale)
        }
        weight?.let {
            val id = it.trim().lowercase()
            require(id in ThemeWeights.ALL) {
                "theme type weight is \"$it\"; a theme names one of ${ThemeWeights.ALL.joinToString()}"
            }
            json.put("weight", id)
        }
        return if (json.length() == 0) null else json
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
