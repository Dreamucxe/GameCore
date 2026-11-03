package com.gamecore.studio.model

/** The Studio category used to organise a creator draft; it is not a host manifest field. */
enum class CreatorCategory {
    THEME,
    INTERFACE,
    AUTOMATION,
    OVERLAY,
    SETTINGS,
    MONITORING,
    DASHBOARD,
    UTILITY,
    COMBINED,
}

/**
 * The non-theme sections authored by Plugin Studio.
 *
 * This is deliberately a pure data model. It does not import the host, Android, or Compose, and
 * none of the capability helpers below represents a grant. [requiredPermissions] and
 * [requiredScopes] only describe what a manifest would have to request for the authored sections;
 * GameCore and the user remain the authorities that decide whether any request is granted.
 */
data class CreatorSections(
    val themeEnabled: Boolean = true,
    val screens: List<ScreenDraft> = emptyList(),
    val overlay: OverlayDraft? = null,
    val settings: List<SettingDraft> = emptyList(),
    val widgets: List<WidgetDraft> = emptyList(),
    val rules: List<RuleDraft> = emptyList(),
) {

    /** Every problem that would make one of these section files unsafe or host-incompatible. */
    fun problems(): List<DraftProblem> = buildList {
        if (screens.size > MAX_SCREENS) {
            add(DraftProblem("screens", "A plugin can declare at most $MAX_SCREENS screens."))
        }
        addDuplicateProblems(
            values = screens.map { canonicalSlug(it.id) },
            field = "screens",
            noun = "screen",
        )
        screens.forEachIndexed { index, screen ->
            addAll(screen.problems("screens[$index]"))
        }

        overlay?.let { addAll(it.problems("overlay")) }

        if (settings.size > MAX_SETTINGS) {
            add(DraftProblem("settings", "A settings section can declare at most $MAX_SETTINGS settings."))
        }
        addDuplicateProblems(
            values = settings.map { it.id.trim() },
            field = "settings",
            noun = "setting",
        )
        settings.forEachIndexed { index, setting ->
            addAll(setting.problems("settings[$index]"))
        }

        if (widgets.size > MAX_WIDGETS) {
            add(DraftProblem("widgets", "A plugin can declare at most $MAX_WIDGETS dashboard widgets."))
        }
        addDuplicateProblems(
            values = widgets.map { canonicalSlug(it.id) },
            field = "widgets",
            noun = "widget",
        )
        widgets.forEachIndexed { index, widget ->
            addAll(widget.problems("widgets[$index]"))
        }

        if (rules.size > MAX_RULES) {
            add(DraftProblem("rules", "A rules section can declare at most $MAX_RULES rules."))
        }
        addDuplicateProblems(
            values = rules.map { it.id.trim() },
            field = "rules",
            noun = "rule",
        )
        rules.forEachIndexed { index, rule ->
            addAll(rule.problems("rules[$index]"))
        }
    }

    /** The host section ids this draft needs, in package order. */
    fun declaredSections(): Set<String> = linkedSetOf<String>().apply {
        if (themeEnabled) add(SECTION_THEME)
        if (overlay != null) add(SECTION_PILL)
        if (screens.isNotEmpty()) add(SECTION_SCREENS)
        if (rules.isNotEmpty()) add(SECTION_RULES)
        if (settings.isNotEmpty()) add(SECTION_SETTINGS)
        if (widgets.isNotEmpty()) add(SECTION_WIDGETS)
    }

    /**
     * Data apertures used by live `stat` nodes and live chart series.
     *
     * Rule conditions intentionally do not contribute scopes. They are evaluated by the host's
     * automation feed and are gated by automation permissions, just as in the host. A screen,
     * overlay, or widget that displays a live reading does contribute its reading's scope.
     */
    fun requiredScopes(): Set<String> = linkedSetOf<String>().apply {
        screens.forEach { addAll(it.root.liveScopes(NodeSurface.SCREEN)) }
        overlay?.let { addAll(it.content.liveScopes(NodeSurface.OVERLAY)) }
        widgets.forEach { addAll(it.content.liveScopes(NodeSurface.WIDGET)) }
    }

    /**
     * Permissions implied by the authored content. This is a request plan, never proof of a grant.
     */
    fun requiredPermissions(): Set<String> = linkedSetOf<String>().apply {
        if (themeEnabled || screens.isNotEmpty()) add(PERMISSION_CHANGE_UI)
        if (overlay != null) add(PERMISSION_CREATE_OVERLAY)
        if (widgets.isNotEmpty()) add(PERMISSION_ADD_DASHBOARD_WIDGETS)
        if (settings.isNotEmpty()) add(PERMISSION_STORE_DATA)
        if (rules.isNotEmpty()) {
            add(PERMISSION_RUN_AUTOMATIONS)
            if (rules.any { it.actionIds.isNotEmpty() }) add(PERMISSION_SYSTEM_ACTIONS)
        }
        if (requiredScopes().isNotEmpty()) add(PERMISSION_READ_APPROVED_DATA)
    }

    companion object {
        const val MAX_SCREENS = 32
        const val MAX_SETTINGS = 64
        const val MAX_WIDGETS = 32
        const val MAX_RULES = 32

        const val SECTION_THEME = "theme"
        const val SECTION_PILL = "pill"
        const val SECTION_SCREENS = "screens"
        const val SECTION_RULES = "rules"
        const val SECTION_SETTINGS = "settings"
        const val SECTION_WIDGETS = "widgets"

        const val PERMISSION_CHANGE_UI = "change_ui"
        const val PERMISSION_CREATE_OVERLAY = "create_overlay"
        const val PERMISSION_ADD_DASHBOARD_WIDGETS = "add_dashboard_widgets"
        const val PERMISSION_STORE_DATA = "store_data"
        const val PERMISSION_RUN_AUTOMATIONS = "run_automations"
        const val PERMISSION_SYSTEM_ACTIONS = "system_actions"
        const val PERMISSION_READ_APPROVED_DATA = "read_approved_data"

        const val SCOPE_DEVICE_STATS = "device_stats"
        const val SCOPE_THERMALS = "thermals"
        const val SCOPE_MOTION = "motion"
    }
}

/** The closed component vocabulary the creator offers. Interactive host leaves are omitted. */
enum class NodeKind(val id: String, val isContainer: Boolean) {
    COLUMN("column", true),
    ROW("row", true),
    GRID("grid", true),
    SCROLL("scroll", true),
    CARD("card", true),
    TEXT("text", false),
    STAT("stat", false),
    SPACER("spacer", false),
    IMAGE("image", false),
    CHART("chart", false),
}

/**
 * A host primitive. There is intentionally no action-reference case and no object/array case.
 * [Color] stores packed ARGB in the low 32 bits and is emitted as `#RRGGBB`/`#AARRGGBB`.
 */
sealed interface NodeValue {
    data class Text(val value: String) : NodeValue

    data class Number(val value: Double) : NodeValue {
        constructor(value: kotlin.Number) : this(value.toDouble())
    }

    data class Bool(val value: Boolean) : NodeValue

    data class Color(val argb: Long) : NodeValue {
        constructor(argb: Int) : this(argb.toLong() and 0xFFFF_FFFFL)
    }
}

/** One declarative component and, for a container, its children in render order. */
data class NodeDraft(
    val kind: NodeKind,
    val props: Map<String, NodeValue> = emptyMap(),
    val children: List<NodeDraft> = emptyList(),
) {

    /** Validate this tree with one of the host surface's real cap sets. */
    fun problems(
        surface: NodeSurface = NodeSurface.SCREEN,
        field: String = "node",
    ): List<DraftProblem> = validateNodeTree(this, surface, field)

    companion object {
        fun column(
            children: List<NodeDraft> = emptyList(),
            props: Map<String, NodeValue> = emptyMap(),
        ) = NodeDraft(NodeKind.COLUMN, props, children)

        fun row(
            children: List<NodeDraft> = emptyList(),
            props: Map<String, NodeValue> = emptyMap(),
        ) = NodeDraft(NodeKind.ROW, props, children)

        fun grid(
            columns: Int = 1,
            children: List<NodeDraft> = emptyList(),
            props: Map<String, NodeValue> = emptyMap(),
        ) = NodeDraft(
            NodeKind.GRID,
            linkedMapOf<String, NodeValue>("columns" to NodeValue.Number(columns)).apply { putAll(props) },
            children,
        )

        fun scroll(
            children: List<NodeDraft> = emptyList(),
            props: Map<String, NodeValue> = emptyMap(),
        ) = NodeDraft(NodeKind.SCROLL, props, children)

        fun card(
            children: List<NodeDraft> = emptyList(),
            props: Map<String, NodeValue> = emptyMap(),
        ) = NodeDraft(NodeKind.CARD, props, children)

        fun text(
            text: String = "Text",
            props: Map<String, NodeValue> = emptyMap(),
        ) = NodeDraft(
            NodeKind.TEXT,
            linkedMapOf<String, NodeValue>("text" to NodeValue.Text(text)).apply { putAll(props) },
        )

        fun stat(
            stat: StatId = StatId.FPS,
            props: Map<String, NodeValue> = emptyMap(),
        ) = stat(stat.id, props)

        fun stat(
            stat: String,
            props: Map<String, NodeValue> = emptyMap(),
        ) = NodeDraft(
            NodeKind.STAT,
            linkedMapOf<String, NodeValue>("stat" to NodeValue.Text(stat)).apply { putAll(props) },
        )

        fun spacer(
            size: Double? = null,
            props: Map<String, NodeValue> = emptyMap(),
        ) = NodeDraft(
            NodeKind.SPACER,
            linkedMapOf<String, NodeValue>().apply {
                size?.let { put("size", NodeValue.Number(it)) }
                putAll(props)
            },
        )

        fun image(
            icon: BuiltinIcon = BuiltinIcon.INFO,
            props: Map<String, NodeValue> = emptyMap(),
        ) = image(icon.id, props)

        fun image(
            icon: String,
            props: Map<String, NodeValue> = emptyMap(),
        ) = NodeDraft(
            NodeKind.IMAGE,
            linkedMapOf<String, NodeValue>("icon" to NodeValue.Text(icon)).apply { putAll(props) },
        )

        fun chart(
            values: String? = null,
            values2: String? = null,
            stat: StatId? = null,
            stat2: StatId? = null,
            props: Map<String, NodeValue> = emptyMap(),
        ) = NodeDraft(
            NodeKind.CHART,
            linkedMapOf<String, NodeValue>().apply {
                values?.let { put("values", NodeValue.Text(it)) }
                values2?.let { put("values2", NodeValue.Text(it)) }
                stat?.let { put("stat", NodeValue.Text(it.id)) }
                stat2?.let { put("stat2", NodeValue.Text(it.id)) }
                putAll(props)
            },
        )
    }
}

/** Which renderer will consume a node tree; each surface has a different cap/type policy. */
enum class NodeSurface { SCREEN, OVERLAY, WIDGET }

/** The eight live readings exposed by overlay/dashboard/screen renderers. */
enum class StatId(val id: String, val scope: String) {
    FPS("fps", CreatorSections.SCOPE_DEVICE_STATS),
    CPU("cpu", CreatorSections.SCOPE_DEVICE_STATS),
    GPU("gpu", CreatorSections.SCOPE_DEVICE_STATS),
    RAM("ram", CreatorSections.SCOPE_DEVICE_STATS),
    BATTERY("battery", CreatorSections.SCOPE_THERMALS),
    TEMP("temp", CreatorSections.SCOPE_THERMALS),
    ROTATION_RATE("rotation_rate", CreatorSections.SCOPE_MOTION),
    MOVEMENT("movement", CreatorSections.SCOPE_MOTION),
    ;

    companion object {
        private val byId = entries.associateBy { it.id }
        fun fromId(id: String): StatId? = byId[id.trim().lowercase()]
    }
}

/** The twenty vectors compiled into the host and nameable by screen chrome/image nodes. */
enum class BuiltinIcon(val id: String) {
    SPEED("speed"),
    MEMORY("memory"),
    THERMOSTAT("thermostat"),
    BATTERY("battery"),
    BOLT("bolt"),
    TIMER("timer"),
    TIMELINE("timeline"),
    DATA("data"),
    INSIGHTS("insights"),
    SENSORS("sensors"),
    STORAGE("storage"),
    SHIELD("shield"),
    EXTENSION("extension"),
    INFO("info"),
    WARNING("warning"),
    BLOCK("block"),
    CHECK("check"),
    DOT("dot"),
    GAME("game"),
    SETTINGS("settings"),
    ;

    companion object {
        private val byId = entries.associateBy { it.id }
        fun fromId(id: String): BuiltinIcon? = byId[id.trim().lowercase()]
    }
}

data class ScreenDraft(
    val id: String,
    val title: String,
    val icon: String? = null,
    val root: NodeDraft,
) {
    fun problems(field: String = "screen"): List<DraftProblem> = buildList {
        validateSlug(id, "$field.id", "screen")?.let(::add)
        if (title.isBlank()) add(DraftProblem("$field.title", "A screen needs a title."))
        if (title.length > MAX_TITLE_LENGTH) {
            add(DraftProblem("$field.title", "A screen title is at most $MAX_TITLE_LENGTH characters."))
        }
        icon?.let {
            if (it !in BUILTIN_ICON_IDS) {
                add(DraftProblem("$field.icon", "\"$it\" is not a built-in GameCore icon."))
            }
        }
        addAll(root.problems(NodeSurface.SCREEN, "$field.root"))
    }

    companion object {
        const val MAX_TITLE_LENGTH = 64
    }
}

enum class OverlaySize(val id: String) {
    SMALL("small"), MEDIUM("medium"), LARGE("large"),
}

data class OverlayDraft(
    val id: String,
    val title: String,
    val xFraction: Double = 0.5,
    val yFraction: Double = 0.08,
    val size: OverlaySize = OverlaySize.SMALL,
    val opacity: Int = 85,
    val content: NodeDraft,
) {
    fun problems(field: String = "overlay"): List<DraftProblem> = buildList {
        validateSlug(id, "$field.id", "overlay")?.let(::add)
        validateCompactTitle(title, "$field.title", "overlay")?.let(::add)
        if (!xFraction.isFinite() || xFraction !in 0.0..1.0) {
            add(DraftProblem("$field.xFraction", "An overlay x position is a finite fraction from 0 to 1."))
        }
        if (!yFraction.isFinite() || yFraction !in 0.0..1.0) {
            add(DraftProblem("$field.yFraction", "An overlay y position is a finite fraction from 0 to 1."))
        }
        if (opacity !in MIN_OPACITY..MAX_OPACITY) {
            add(DraftProblem("$field.opacity", "Overlay opacity is $MIN_OPACITY to $MAX_OPACITY percent."))
        }
        addAll(content.problems(NodeSurface.OVERLAY, "$field.content"))
    }

    companion object {
        const val MIN_OPACITY = 20
        const val MAX_OPACITY = 100
    }
}

enum class WidgetSize(val id: String) {
    SMALL("small"), MEDIUM("medium"), LARGE("large"), FULL("full"),
}

data class WidgetDraft(
    val id: String,
    val title: String,
    val size: WidgetSize = WidgetSize.MEDIUM,
    val content: NodeDraft,
) {
    fun problems(field: String = "widget"): List<DraftProblem> = buildList {
        validateSlug(id, "$field.id", "widget")?.let(::add)
        validateCompactTitle(title, "$field.title", "widget")?.let(::add)
        addAll(content.problems(NodeSurface.WIDGET, "$field.content"))
    }
}

/**
 * One schema-1 setting declaration.
 *
 * [label] and [description] belong only to Studio's editor. The host settings format has no keys
 * for either field, so [com.gamecore.studio.core.SectionComposer] deliberately never emits them.
 */
sealed interface SettingDraft {
    val id: String
    val label: String
    val description: String

    fun problems(field: String = "setting"): List<DraftProblem> = settingProblems(this, field)
}

data class BoolSetting(
    override val id: String,
    val default: Boolean = false,
    override val label: String = "",
    override val description: String = "",
) : SettingDraft

data class IntSetting(
    override val id: String,
    val default: Long = 0,
    val min: Long? = null,
    val max: Long? = null,
    override val label: String = "",
    override val description: String = "",
) : SettingDraft

data class FloatSetting(
    override val id: String,
    val default: Double = 0.0,
    val min: Double? = null,
    val max: Double? = null,
    override val label: String = "",
    override val description: String = "",
) : SettingDraft

data class EnumSetting(
    override val id: String,
    val options: List<String>,
    val default: String,
    override val label: String = "",
    override val description: String = "",
) : SettingDraft

data class StringSetting(
    override val id: String,
    val default: String = "",
    val maxLength: Int = MAX_SETTING_STRING_LENGTH,
    override val label: String = "",
    override val description: String = "",
) : SettingDraft

/** The seven edge triggers published by the host automation engine. */
enum class Trigger {
    GAME_LAUNCHED,
    GAME_CLOSED,
    SESSION_STARTED,
    SESSION_ENDED,
    THERMAL_HIGH,
    BATTERY_LOW,
    CHARGER_CONNECTED,
}

/** The six numeric variables actually published to rule conditions. */
enum class RuleVariable(val id: String) {
    FPS("fps"), CPU("cpu"), GPU("gpu"), RAM("ram"), BATTERY("battery"), TEMP("temp"),
}

/** The six simple comparisons supported by a flat rule condition. */
enum class Operator { EQ, NEQ, LT, LTE, GT, GTE }

/** The whole host broker registry. A rule cannot represent an arbitrary command or setting key. */
enum class BrokerActionId(val id: String) {
    SET_PEAK_REFRESH_RATE("set_peak_refresh_rate"),
    SET_MIN_REFRESH_RATE("set_min_refresh_rate"),
    SET_WINDOW_ANIMATION_SCALE("set_window_animation_scale"),
    SET_TRANSITION_ANIMATION_SCALE("set_transition_animation_scale"),
    SET_ANIMATOR_DURATION_SCALE("set_animator_duration_scale"),
    SET_LOW_POWER("set_low_power"),
    SET_SCREEN_BRIGHTNESS("set_screen_brightness"),
}

data class RuleCondition(
    val variable: RuleVariable,
    val op: Operator,
    val value: Double,
) {
    /** Readable alias for editor code that calls the field an operator rather than JSON's `op`. */
    val operator: Operator get() = op
}

typealias ConditionDraft = RuleCondition

/**
 * A bounded host automation: one trigger, a flat AND of [conditions], then [actionIds] in order.
 *
 * There is intentionally no branch, OR group, parallel block, schedule, delay, retry, loop, or
 * rule-to-rule call. The host grammar supports none of those; a creator control for one would only
 * pretend to produce behaviour that cannot be encoded.
 */
data class RuleDraft(
    val id: String,
    val trigger: Trigger,
    val conditions: List<RuleCondition> = emptyList(),
    val actionIds: List<BrokerActionId> = emptyList(),
) {
    fun problems(field: String = "rule"): List<DraftProblem> = buildList {
        if (id.isBlank()) add(DraftProblem("$field.id", "A rule needs an id."))
        if (conditions.size > MAX_CONDITIONS) {
            add(DraftProblem("$field.conditions", "A rule can have at most $MAX_CONDITIONS conditions."))
        }
        conditions.forEachIndexed { index, condition ->
            if (!condition.value.isFinite()) {
                add(DraftProblem("$field.conditions[$index].value", "A rule condition value must be finite."))
            }
        }
        if (actionIds.isEmpty()) {
            add(DraftProblem("$field.actionIds", "A rule needs at least one broker action."))
        }
        if (actionIds.size > MAX_ACTIONS) {
            add(DraftProblem("$field.actionIds", "A rule can run at most $MAX_ACTIONS actions."))
        }
    }

    companion object {
        const val MAX_CONDITIONS = 10
        const val MAX_ACTIONS = 8
    }
}

// --------------------------------------------------------------------------------------- validation

private const val MAX_SLUG_LENGTH = 64
private const val MAX_NODE_PROPS = 64
private const val MAX_PROP_NAME_LENGTH = 64
private const val MAX_COMPACT_TITLE_LENGTH = 48
private const val MAX_SETTING_ID_LENGTH = 64
private const val MAX_ENUM_OPTIONS = 32
private const val MAX_SETTING_STRING_LENGTH = 256

private val SLUG = Regex("^[a-z0-9][a-z0-9._-]{0,${MAX_SLUG_LENGTH - 1}}$")
private val SETTING_ID = Regex("^[a-z0-9][a-z0-9._-]{0,${MAX_SETTING_ID_LENGTH - 1}}$")
private val BUILTIN_ICON_IDS = BuiltinIcon.entries.mapTo(linkedSetOf()) { it.id }

private data class NodeCaps(
    val maxNodes: Int,
    val maxDepth: Int,
    val maxChildren: Int,
    val maxGridColumns: Int,
    val maxStringLength: Int,
    val allowScroll: Boolean,
    val allowLiveChart: Boolean,
)

private fun NodeSurface.caps(): NodeCaps = when (this) {
    NodeSurface.SCREEN -> NodeCaps(500, 24, 100, 8, 2_000, allowScroll = true, allowLiveChart = true)
    NodeSurface.OVERLAY -> NodeCaps(40, 6, 12, 4, 120, allowScroll = false, allowLiveChart = false)
    NodeSurface.WIDGET -> NodeCaps(40, 6, 12, 4, 120, allowScroll = true, allowLiveChart = true)
}

private data class PendingNode(val node: NodeDraft, val depth: Int, val field: String)

private fun validateNodeTree(root: NodeDraft, surface: NodeSurface, rootField: String): List<DraftProblem> {
    val caps = surface.caps()
    val problems = mutableListOf<DraftProblem>()
    val pending = ArrayDeque<PendingNode>()
    pending.addLast(PendingNode(root, 0, rootField))
    var nodeCount = 0
    var reportedCount = false

    while (pending.isNotEmpty()) {
        val (node, depth, field) = pending.removeLast()
        nodeCount++
        if (nodeCount > caps.maxNodes && !reportedCount) {
            reportedCount = true
            problems += DraftProblem(rootField, "This ${surface.displayName()} tree has more than ${caps.maxNodes} nodes.")
        }

        if (depth > caps.maxDepth) {
            problems += DraftProblem(field, "This ${surface.displayName()} tree is deeper than ${caps.maxDepth}.")
            // Besides bounding validation work, this makes a draft assembled around a mutable cycle
            // report a depth error rather than looping forever.
            continue
        }

        if (!node.kind.isContainer && node.children.isNotEmpty()) {
            problems += DraftProblem("$field.children", "${node.kind.id} is a leaf and cannot have children.")
        }
        if (node.children.size > caps.maxChildren) {
            problems += DraftProblem(
                "$field.children",
                "${node.kind.id} can have at most ${caps.maxChildren} children on this surface.",
            )
        }
        if (node.kind == NodeKind.SCROLL && !caps.allowScroll) {
            problems += DraftProblem(field, "An overlay cannot contain a scroll node because its window takes no touches.")
        }

        if (node.props.size > MAX_NODE_PROPS) {
            problems += DraftProblem("$field.props", "A node can have at most $MAX_NODE_PROPS primitive props.")
        }
        node.props.toSortedMap().forEach { (key, value) ->
            val propField = "$field.props.$key"
            if (key.isBlank()) problems += DraftProblem("$field.props", "A prop name cannot be blank.")
            if (key.length > MAX_PROP_NAME_LENGTH) {
                problems += DraftProblem(propField, "A prop name is at most $MAX_PROP_NAME_LENGTH characters.")
            }
            when (value) {
                is NodeValue.Text -> if (value.value.length > caps.maxStringLength) {
                    problems += DraftProblem(
                        propField,
                        "A string on this surface is at most ${caps.maxStringLength} characters.",
                    )
                }
                is NodeValue.Number -> if (!value.value.isFinite()) {
                    problems += DraftProblem(propField, "A numeric prop must be finite.")
                }
                is NodeValue.Color -> if (value.argb !in 0L..0xFFFF_FFFFL) {
                    problems += DraftProblem(propField, "A color must be a packed 32-bit ARGB value.")
                }
                is NodeValue.Bool -> Unit
            }
        }

        when (node.kind) {
            NodeKind.GRID -> validateGrid(node, caps, field, problems)
            NodeKind.STAT -> validateStatProp(node, "stat", surface, field, problems)
            NodeKind.IMAGE -> validateIconProp(node, field, problems)
            NodeKind.CHART -> validateChart(node, caps, surface, field, problems)
            else -> Unit
        }

        // Reverse insertion preserves author order when the LIFO stack is read.
        for (index in node.children.indices.reversed()) {
            pending.addLast(PendingNode(node.children[index], depth + 1, "$field.children[$index]"))
        }
    }
    return problems
}

private fun validateGrid(
    node: NodeDraft,
    caps: NodeCaps,
    field: String,
    problems: MutableList<DraftProblem>,
) {
    val columns = node.props["columns"] ?: return // The host's documented default is one.
    val value = (columns as? NodeValue.Number)?.value
    if (value == null || !value.isFinite() || value % 1.0 != 0.0 || value !in 1.0..caps.maxGridColumns.toDouble()) {
        problems += DraftProblem(
            "$field.props.columns",
            "Grid columns must be a whole number from 1 to ${caps.maxGridColumns} on this surface.",
        )
    }
}

private fun validateStatProp(
    node: NodeDraft,
    key: String,
    surface: NodeSurface,
    field: String,
    problems: MutableList<DraftProblem>,
) {
    val id = (node.props[key] as? NodeValue.Text)?.value
    val allowed = if (surface == NodeSurface.OVERLAY) STAT_IDS else INSTANT_SURFACE_STAT_IDS
    if (id == null || id !in allowed) {
        val message = if (id != null && id in STAT_IDS && surface != NodeSurface.OVERLAY) {
            val destination = if (surface == NodeSurface.SCREEN) "full screen" else "dashboard widget"
            "The reading \"$id\" is unavailable on a $destination; motion readings are available only in overlays."
        } else {
            "A $key prop on this ${surface.displayName()} must name one of ${allowed.joinToString()}."
        }
        problems += DraftProblem("$field.props.$key", message)
    }
}

private fun validateIconProp(
    node: NodeDraft,
    field: String,
    problems: MutableList<DraftProblem>,
) {
    val id = (node.props["icon"] as? NodeValue.Text)?.value
    if (id == null || id !in BUILTIN_ICON_IDS) {
        problems += DraftProblem(
            "$field.props.icon",
            "An image must name one of GameCore's built-in icons.",
        )
    }
}

private fun validateChart(
    node: NodeDraft,
    caps: NodeCaps,
    surface: NodeSurface,
    field: String,
    problems: MutableList<DraftProblem>,
) {
    for (key in listOf("stat", "stat2")) {
        if (key !in node.props) continue
        if (!caps.allowLiveChart) {
            problems += DraftProblem(
                "$field.props.$key",
                "An overlay chart is literal-only and cannot name a live $key series.",
            )
        } else {
            val id = (node.props[key] as? NodeValue.Text)?.value
            if (id == null || id !in LIVE_CHART_STAT_IDS) {
                problems += DraftProblem(
                    "$field.props.$key",
                    "A live chart $key on this ${surface.displayName()} must name one of " +
                        "${LIVE_CHART_STAT_IDS.joinToString()}; the host retains no series for the other stats.",
                )
            }
        }
    }
}

private fun NodeSurface.displayName(): String = when (this) {
    NodeSurface.SCREEN -> "screen"
    NodeSurface.OVERLAY -> "overlay"
    NodeSurface.WIDGET -> "widget"
}

private val STAT_IDS = StatId.entries.mapTo(linkedSetOf()) { it.id }
private val INSTANT_SURFACE_STAT_IDS = linkedSetOf(
    StatId.FPS.id,
    StatId.CPU.id,
    StatId.GPU.id,
    StatId.RAM.id,
    StatId.BATTERY.id,
    StatId.TEMP.id,
)
private val LIVE_CHART_STAT_IDS = linkedSetOf(
    StatId.FPS.id,
    StatId.CPU.id,
    StatId.RAM.id,
    StatId.TEMP.id,
)

private fun NodeDraft.liveScopes(surface: NodeSurface): Set<String> {
    val scopes = linkedSetOf<String>()
    val pending = ArrayDeque<Pair<NodeDraft, Int>>()
    pending.addLast(this to 0)
    var visited = 0
    while (pending.isNotEmpty()) {
        val (node, depth) = pending.removeLast()
        // All valid trees finish below this bound. An invalid/cyclic hand-built draft fails closed
        // instead of returning a partial appetite.
        if (visited++ >= 1_024 || depth > 32) {
            return if (surface == NodeSurface.OVERLAY) {
                linkedSetOf(
                    CreatorSections.SCOPE_DEVICE_STATS,
                    CreatorSections.SCOPE_THERMALS,
                    CreatorSections.SCOPE_MOTION,
                )
            } else {
                linkedSetOf(CreatorSections.SCOPE_DEVICE_STATS, CreatorSections.SCOPE_THERMALS)
            }
        }
        if (node.kind == NodeKind.STAT) {
            node.textProp("stat")?.let(StatId::fromId)?.let { stat ->
                val allowed = surface == NodeSurface.OVERLAY || stat.id in INSTANT_SURFACE_STAT_IDS
                if (allowed) scopes += stat.scope
            }
        }
        if (node.kind == NodeKind.CHART && surface != NodeSurface.OVERLAY) {
            listOf("stat", "stat2").forEach { key ->
                node.textProp(key)?.let(StatId::fromId)?.let { stat ->
                    if (stat.id in LIVE_CHART_STAT_IDS) scopes += stat.scope
                }
            }
        }
        for (index in node.children.indices.reversed()) {
            pending.addLast(node.children[index] to depth + 1)
        }
    }
    return scopes
}

private fun NodeDraft.textProp(key: String): String? =
    (props[key] as? NodeValue.Text)?.value?.trim()?.takeIf { it.isNotEmpty() }

private fun settingProblems(setting: SettingDraft, field: String): List<DraftProblem> = buildList {
    val id = setting.id
    if (!SETTING_ID.matches(id) || id.split('.').any { it.isEmpty() }) {
        add(
            DraftProblem(
                "$field.id",
                "A setting id is a lowercase storage-key slug of at most $MAX_SETTING_ID_LENGTH characters.",
            ),
        )
    }
    when (setting) {
        is BoolSetting -> Unit
        is IntSetting -> {
            if (setting.min != null && setting.max != null && setting.min > setting.max) {
                add(DraftProblem("$field.min", "A setting minimum cannot exceed its maximum."))
            }
            if (setting.min != null && setting.default < setting.min) {
                add(DraftProblem("$field.default", "The default is below this setting's minimum."))
            }
            if (setting.max != null && setting.default > setting.max) {
                add(DraftProblem("$field.default", "The default is above this setting's maximum."))
            }
        }
        is FloatSetting -> {
            if (!setting.default.isFinite()) add(DraftProblem("$field.default", "A float default must be finite."))
            if (setting.min != null && !setting.min.isFinite()) {
                add(DraftProblem("$field.min", "A float minimum must be finite."))
            }
            if (setting.max != null && !setting.max.isFinite()) {
                add(DraftProblem("$field.max", "A float maximum must be finite."))
            }
            if (setting.min != null && setting.max != null && setting.min > setting.max) {
                add(DraftProblem("$field.min", "A setting minimum cannot exceed its maximum."))
            }
            if (setting.min != null && setting.default < setting.min) {
                add(DraftProblem("$field.default", "The default is below this setting's minimum."))
            }
            if (setting.max != null && setting.default > setting.max) {
                add(DraftProblem("$field.default", "The default is above this setting's maximum."))
            }
        }
        is EnumSetting -> {
            if (setting.options.isEmpty()) {
                add(DraftProblem("$field.options", "An enum setting needs at least one option."))
            }
            if (setting.options.size > MAX_ENUM_OPTIONS) {
                add(DraftProblem("$field.options", "An enum setting can have at most $MAX_ENUM_OPTIONS options."))
            }
            val seen = linkedSetOf<String>()
            setting.options.forEachIndexed { index, option ->
                if (option.isBlank()) add(DraftProblem("$field.options[$index]", "An enum option cannot be blank."))
                if (option.length > MAX_SETTING_STRING_LENGTH) {
                    add(
                        DraftProblem(
                            "$field.options[$index]",
                            "An enum option is at most $MAX_SETTING_STRING_LENGTH characters.",
                        ),
                    )
                }
                if (!seen.add(option)) {
                    add(DraftProblem("$field.options", "An enum setting cannot repeat option \"$option\"."))
                }
            }
            if (setting.default !in setting.options) {
                add(DraftProblem("$field.default", "An enum default must be one of its options."))
            }
        }
        is StringSetting -> {
            if (setting.maxLength !in 1..MAX_SETTING_STRING_LENGTH) {
                add(
                    DraftProblem(
                        "$field.maxLength",
                        "A string setting's maximum length is 1 to $MAX_SETTING_STRING_LENGTH.",
                    ),
                )
            }
            if (setting.default.length > setting.maxLength) {
                add(DraftProblem("$field.default", "The default is longer than this setting permits."))
            }
            if (setting.default.length > MAX_SETTING_STRING_LENGTH) {
                add(
                    DraftProblem(
                        "$field.default",
                        "A string default is at most $MAX_SETTING_STRING_LENGTH characters.",
                    ),
                )
            }
        }
    }
}

private fun validateSlug(id: String, field: String, noun: String): DraftProblem? =
    if (SLUG.matches(id)) null else DraftProblem(
        field,
        "A $noun id is a lowercase slug of at most $MAX_SLUG_LENGTH characters (a-z, 0-9, dot, dash, underscore).",
    )

private fun validateCompactTitle(title: String, field: String, noun: String): DraftProblem? = when {
    title.isBlank() -> DraftProblem(field, "A $noun needs a title.")
    title.length > MAX_COMPACT_TITLE_LENGTH ->
        DraftProblem(field, "A $noun title is at most $MAX_COMPACT_TITLE_LENGTH characters.")
    else -> null
}

private fun canonicalSlug(id: String): String = id.trim().lowercase()

private fun MutableList<DraftProblem>.addDuplicateProblems(
    values: List<String>,
    field: String,
    noun: String,
) {
    val seen = linkedSetOf<String>()
    val duplicates = linkedSetOf<String>()
    values.filter { it.isNotEmpty() }.forEach { if (!seen.add(it)) duplicates += it }
    duplicates.forEach { id ->
        add(DraftProblem(field, "Two ${noun}s share the id \"$id\"."))
    }
}
