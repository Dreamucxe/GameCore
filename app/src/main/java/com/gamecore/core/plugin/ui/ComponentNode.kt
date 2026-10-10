package com.gamecore.core.plugin.ui

/**
 * The closed set of UI components a plugin screen may be built from.
 *
 * A plugin screen is data — a tree of these nodes — not code, so the only things that can
 * appear on screen are the components GameCore knows how to render natively in Compose. There
 * is no "raw view", no HTML, no WebView, no custom drawing: a node whose type is not in this
 * enum is rejected by the validator, which means a plugin can arrange and style the official
 * component set but can never introduce a surface GameCore has not vetted.
 *
 * The split between [containers] and leaves matters for validation: a container may hold
 * children, a leaf may not, and the validator enforces the arity so a plugin cannot, say,
 * hang a thousand children off a Switch to blow up the renderer.
 */
enum class ComponentType(val id: String, val isContainer: Boolean) {
    // Layout containers
    COLUMN("column", true),
    ROW("row", true),
    GRID("grid", true),
    SCROLL("scroll", true),
    CARD("card", true),

    // Leaves
    TEXT("text", false),
    BUTTON("button", false),
    SLIDER("slider", false),
    SWITCH("switch", false),
    STAT("stat", false),
    SPACER("spacer", false),
    IMAGE("image", false);

    companion object {
        private val byId = entries.associateBy { it.id }
        fun fromId(id: String): ComponentType? = byId[id.trim().lowercase()]
    }
}

/**
 * One node in a plugin's screen tree.
 *
 * [props] is a bag of already-validated primitives (strings, numbers, booleans, colour ints)
 * — never arbitrary objects, never a callback, never a lambda. A [BUTTON]'s "action" prop,
 * for instance, is the *id* of an automation or broker action the plugin declared; pressing
 * it dispatches through the normal gated path, it does not run plugin code, because there is
 * no plugin code. [children] is non-empty only for a container type.
 */
data class ComponentNode(
    val type: ComponentType,
    val props: Map<String, PropValue>,
    val children: List<ComponentNode> = emptyList(),
)

/** A validated, primitive prop value. The type system makes "arbitrary object" unrepresentable. */
sealed interface PropValue {
    data class Str(val value: String) : PropValue
    data class Num(val value: Double) : PropValue
    data class Bool(val value: Boolean) : PropValue
    data class Color(val argb: Int) : PropValue
    /** A reference to a declared action/automation id — dispatched through the gate, not executed. */
    data class ActionRef(val id: String) : PropValue
}

/** A whole plugin screen: an id the host routes to, a title, and the root node. */
data class PluginScreen(
    val id: String,
    val title: String,
    val root: ComponentNode,
)
