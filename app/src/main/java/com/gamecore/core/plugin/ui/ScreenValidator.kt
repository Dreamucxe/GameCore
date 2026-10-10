package com.gamecore.core.plugin.ui

/**
 * Validates a plugin screen tree before it is ever rendered.
 *
 * The renderer trusts its input, so this is where the input is made trustworthy. Three kinds
 * of defence live here, and they are the reason a malformed or hostile screen degrades to the
 * default UI rather than hanging, crashing, or drawing something it should not:
 *
 *  - **Shape** — every node's type is a known [ComponentType]; a leaf has no children; a
 *    container's children are themselves valid. Unknown types are rejected, not skipped.
 *  - **Bounds** — the tree is capped in total node count and in depth, so a plugin cannot
 *    ship a screen that recurses the renderer into a stack overflow or allocates a million
 *    composables. Grids are capped in column count. Strings are capped in length.
 *  - **No forbidden surfaces** — there is simply no node type for a WebView, a raw view, or a
 *    full-screen overlay, so "reject the dangerous component" is handled by the component set
 *    being closed rather than by a blocklist that could miss one.
 *
 * The result is either a validated [PluginScreen] or a list of reasons it was refused. A
 * single bad node fails the whole screen (and the host falls back to the default screen for
 * that route); partial rendering of a tree whose invariants are broken is how you get a UI
 * that half-works and wholly confuses.
 */
class ScreenValidator(
    private val maxNodes: Int = 500,
    private val maxDepth: Int = 24,
    private val maxGridColumns: Int = 8,
    private val maxStringLength: Int = 2_000,
    private val maxChildren: Int = 100,
) {

    sealed interface Result {
        data class Valid(val screen: PluginScreen) : Result
        data class Invalid(val reasons: List<String>) : Result
    }

    fun validate(screen: PluginScreen): Result {
        val reasons = mutableListOf<String>()
        if (screen.id.isBlank()) reasons += "screen id is blank"
        if (screen.title.length > maxStringLength) reasons += "screen title too long"

        var nodeCount = 0
        fun walk(node: ComponentNode, depth: Int) {
            nodeCount++
            if (nodeCount > maxNodes) {
                reasons += "screen exceeds $maxNodes nodes"
                return
            }
            if (depth > maxDepth) {
                reasons += "screen exceeds depth $maxDepth"
                return
            }
            if (!node.type.isContainer && node.children.isNotEmpty()) {
                reasons += "${node.type.id} is a leaf but has children"
            }
            if (node.children.size > maxChildren) {
                reasons += "${node.type.id} has more than $maxChildren children"
            }
            if (node.type == ComponentType.GRID) {
                val cols = (node.props["columns"] as? PropValue.Num)?.value?.toInt() ?: 1
                if (cols < 1 || cols > maxGridColumns) reasons += "grid columns out of 1..$maxGridColumns"
            }
            node.props.forEach { (k, v) ->
                if (v is PropValue.Str && v.value.length > maxStringLength) {
                    reasons += "prop '$k' string too long"
                }
            }
            if (reasons.size > 50) return // stop piling on; the screen is clearly bad
            node.children.forEach { walk(it, depth + 1) }
        }
        walk(screen.root, 0)

        return if (reasons.isEmpty()) Result.Valid(screen) else Result.Invalid(reasons.distinct())
    }
}
