package com.gamecore.core.plugin.ui

import org.junit.Assert.assertTrue
import org.junit.Test

/** Part F gate: the declarative UI engine's validator — shape, bounds, and closed component set. */
class ScreenValidatorTest {

    private val v = ScreenValidator(maxNodes = 50, maxDepth = 5, maxGridColumns = 4)

    private fun leaf(type: ComponentType, props: Map<String, PropValue> = emptyMap()) =
        ComponentNode(type, props)

    private fun screen(root: ComponentNode) = PluginScreen("home", "Home", root)

    @Test
    fun `a well-formed tree validates`() {
        val tree = ComponentNode(
            ComponentType.COLUMN, emptyMap(),
            listOf(
                leaf(ComponentType.TEXT, mapOf("text" to PropValue.Str("Hello"))),
                leaf(ComponentType.BUTTON, mapOf("label" to PropValue.Str("Go"), "action" to PropValue.ActionRef("set_peak_refresh_rate"))),
            ),
        )
        assertTrue(v.validate(screen(tree)) is ScreenValidator.Result.Valid)
    }

    @Test
    fun `a leaf with children is rejected`() {
        val tree = ComponentNode(ComponentType.TEXT, emptyMap(), listOf(leaf(ComponentType.TEXT)))
        assertTrue(v.validate(screen(tree)) is ScreenValidator.Result.Invalid)
    }

    @Test
    fun `a tree deeper than the cap is rejected`() {
        var node = leaf(ComponentType.TEXT)
        repeat(10) { node = ComponentNode(ComponentType.COLUMN, emptyMap(), listOf(node)) }
        assertTrue(v.validate(screen(node)) is ScreenValidator.Result.Invalid)
    }

    @Test
    fun `a tree with too many nodes is rejected`() {
        val many = (1..100).map { leaf(ComponentType.TEXT) }
        val tree = ComponentNode(ComponentType.COLUMN, emptyMap(), many)
        assertTrue(v.validate(screen(tree)) is ScreenValidator.Result.Invalid)
    }

    @Test
    fun `a grid with too many columns is rejected`() {
        val tree = ComponentNode(ComponentType.GRID, mapOf("columns" to PropValue.Num(99.0)))
        assertTrue(v.validate(screen(tree)) is ScreenValidator.Result.Invalid)
    }

    @Test
    fun `an unknown component type cannot be constructed`() {
        // There is no ComponentType for a webview; fromId returns null, so a parser can never
        // build such a node. This asserts the closed-set property at the type boundary.
        org.junit.Assert.assertNull(ComponentType.fromId("webview"))
        org.junit.Assert.assertNull(ComponentType.fromId("rawview"))
    }
}
