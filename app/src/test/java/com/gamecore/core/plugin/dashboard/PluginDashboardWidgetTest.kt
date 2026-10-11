package com.gamecore.core.plugin.dashboard

import com.gamecore.core.plugin.overlay.ModuleStat
import com.gamecore.core.plugin.ui.ComponentNode
import com.gamecore.core.plugin.ui.ComponentType
import com.gamecore.core.plugin.ui.PluginScreen
import com.gamecore.core.plugin.ui.PropValue
import com.gamecore.core.plugin.ui.ScreenValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trusted model behind a dashboard widget, tested where the codec is not: the derivations and
 * clamps a [PluginDashboardWidget] carries on its own, independent of the JSON it was parsed from.
 *
 * This is [com.gamecore.core.plugin.overlay.PluginOverlayModule]'s behaviour re-checked on the home
 * surface. A widget is built directly in code here, not decoded, because the point of these tests is
 * exactly the case the codec does not cover: a model assembled by hand, or straight from a decoder
 * that has not finished, where nothing has yet bounded the tree or clamped the title. The two places
 * this diverges from the overlay model are the two places the surfaces differ — [WidgetSize] has no
 * dp dimensions and its own four-span vocabulary, and [PluginDashboardWidget.ALLOWED_TYPES] admits
 * `scroll`.
 */
class PluginDashboardWidgetTest {

    // ------------------------------------------------------------------------------ helpers

    private fun stat(id: String): ComponentNode =
        ComponentNode(ComponentType.STAT, mapOf(ModuleStat.PROP to PropValue.Str(id)))

    private fun text(s: String = "x"): ComponentNode =
        ComponentNode(ComponentType.TEXT, mapOf("text" to PropValue.Str(s)))

    private fun spacer(): ComponentNode = ComponentNode(ComponentType.SPACER, emptyMap())

    private fun column(vararg children: ComponentNode): ComponentNode =
        ComponentNode(ComponentType.COLUMN, emptyMap(), children.toList())

    private fun row(vararg children: ComponentNode): ComponentNode =
        ComponentNode(ComponentType.ROW, emptyMap(), children.toList())

    private fun widget(
        content: ComponentNode,
        title: String = "W",
        size: WidgetSize = WidgetSize.MEDIUM,
        id: String = "w",
    ): PluginDashboardWidget =
        PluginDashboardWidget(id = id, title = title, size = size, content = content)

    // ------------------------------------------------------------------------------ stats and scopes

    @Test
    fun `requiredStats collects every stat across the tree, at any depth`() {
        val m = widget(
            column(
                stat("fps"),
                row(stat("battery"), stat("cpu")),
                text("label"),
            ),
        )
        assertEquals(setOf(ModuleStat.FPS, ModuleStat.BATTERY, ModuleStat.CPU), m.requiredStats)
        assertEquals(setOf("device_stats", "thermals"), m.requiredScopes)
    }

    @Test
    fun `a widget with no stat node asks for nothing and so starts no sampler`() {
        val m = widget(column(text("just a label"), spacer()))
        assertTrue(m.requiredStats.isEmpty())
        assertTrue(m.requiredScopes.isEmpty())
    }

    @Test
    fun `duplicate stats collapse and a scope is listed once however many stats ride it`() {
        val m = widget(column(stat("fps"), stat("fps"), stat("gpu"), stat("ram")))
        assertEquals(setOf(ModuleStat.FPS, ModuleStat.GPU, ModuleStat.RAM), m.requiredStats)
        // fps, gpu and ram all ride device_stats, so the scope set has one member.
        assertEquals(setOf("device_stats"), m.requiredScopes)
    }

    @Test
    fun `motion stats resolve to the motion scope`() {
        val m = widget(column(stat("rotation_rate"), stat("movement")))
        assertEquals(setOf("motion"), m.requiredScopes)
    }

    @Test
    fun `the scope walk fails closed past its cap, naming every stat rather than a partial set`() {
        // A tree larger than the walk's ceiling returns the whole vocabulary, not what it managed
        // to see: under-reporting would show a reading behind an aperture the user never granted,
        // so the truncated case must be the widest possible, never the narrowest.
        val many = (1..PluginDashboardWidget.MAX_SCOPE_WALK_NODES + 10).map { spacer() }
        val m = widget(ComponentNode(ComponentType.COLUMN, emptyMap(), many))
        assertEquals(ModuleStat.entries.toSet(), m.requiredStats)
    }

    // ------------------------------------------------------------------------------ normalised

    @Test
    fun `normalised clamps an over-long title to the cap and keeps its prefix`() {
        val long = "x".repeat(PluginDashboardWidget.MAX_TITLE_LENGTH + 20)
        val m = widget(column(text()), title = long).normalised()
        assertEquals(PluginDashboardWidget.MAX_TITLE_LENGTH, m.title.length)
        assertEquals(long.take(PluginDashboardWidget.MAX_TITLE_LENGTH), m.title)
    }

    @Test
    fun `normalised touches only the title, leaving id, size and content alone`() {
        val content = column(stat("fps"))
        val m = widget(content, title = "x".repeat(80), size = WidgetSize.FULL, id = "fps.tile")
        val n = m.normalised()
        assertEquals("fps.tile", n.id)
        assertEquals(WidgetSize.FULL, n.size)
        assertEquals(content, n.content)
    }

    @Test
    fun `normalised is idempotent and a no-op for a widget already in range`() {
        val m = widget(column(text("Frame rate")), title = "Frame rate")
        assertEquals(m, m.normalised())
        assertEquals(m.normalised(), m.normalised().normalised())
    }

    // ------------------------------------------------------------------------------ the size enum

    @Test
    fun `WidgetSize is a closed set of four spans in footprint order`() {
        assertEquals(WidgetSize.entries, WidgetSize.ALL)
        assertEquals(4, WidgetSize.ALL.size)
        assertEquals(
            listOf("small", "medium", "large", "full"),
            WidgetSize.ALL.map { it.id },
        )
    }

    @Test
    fun `WidgetSize fromId resolves an id, trims and lower-cases, and refuses an unknown one`() {
        assertEquals(WidgetSize.SMALL, WidgetSize.fromId("small"))
        assertEquals(WidgetSize.FULL, WidgetSize.fromId("  FULL "))
        assertNull(WidgetSize.fromId("jumbo"))
        assertNull(WidgetSize.fromId(""))
        // Every id round-trips, so the allowlist and the picker cannot drift apart.
        WidgetSize.ALL.forEach { assertEquals(it, WidgetSize.fromId(it.id)) }
    }

    // ------------------------------------------------------------------------------ allowed types

    @Test
    fun `ALLOWED_TYPES is the ten read-only types, scroll among them`() {
        assertEquals(
            setOf(
                ComponentType.COLUMN,
                ComponentType.ROW,
                ComponentType.GRID,
                ComponentType.SCROLL,
                ComponentType.CARD,
                ComponentType.TEXT,
                ComponentType.STAT,
                ComponentType.SPACER,
                ComponentType.IMAGE,
                ComponentType.CHART,
            ),
            PluginDashboardWidget.ALLOWED_TYPES,
        )
        assertEquals(10, PluginDashboardWidget.ALLOWED_TYPES.size)
        // The dashboard is scrolled with a finger, so scroll is legitimate here, unlike an overlay.
        assertTrue(ComponentType.SCROLL in PluginDashboardWidget.ALLOWED_TYPES)
    }

    @Test
    fun `the three interactive leaves are excluded, because a widget dispatches nothing`() {
        listOf(ComponentType.BUTTON, ComponentType.SLIDER, ComponentType.SWITCH).forEach {
            assertFalse("$it must not be allowed", it in PluginDashboardWidget.ALLOWED_TYPES)
        }
    }

    // ------------------------------------------------------------------------------ caps

    @Test
    fun `the tile caps are the overlay module's, verbatim`() {
        assertEquals(40, PluginDashboardWidget.MAX_NODES)
        assertEquals(6, PluginDashboardWidget.MAX_DEPTH)
        assertEquals(4, PluginDashboardWidget.MAX_GRID_COLUMNS)
        assertEquals(120, PluginDashboardWidget.MAX_STRING_LENGTH)
        assertEquals(12, PluginDashboardWidget.MAX_CHILDREN)
        assertEquals(48, PluginDashboardWidget.MAX_TITLE_LENGTH)
        assertEquals(512, PluginDashboardWidget.MAX_SCOPE_WALK_NODES)
    }

    @Test
    fun `validator is the ScreenValidator parameterised down to the tile caps`() {
        val v = PluginDashboardWidget.validator()
        // A tree inside the caps passes.
        val ok = v.validate(
            PluginScreen(id = "w", title = "W", root = column(text(), text())),
        )
        assertTrue(ok is ScreenValidator.Result.Valid)
        // One more child than MAX_CHILDREN allows is refused, proving the cap is wired through.
        val tooWide = (1..PluginDashboardWidget.MAX_CHILDREN + 1).map { text() }
        val bad = v.validate(
            PluginScreen(id = "w", title = "W", root = ComponentNode(ComponentType.COLUMN, emptyMap(), tooWide)),
        )
        assertTrue(bad is ScreenValidator.Result.Invalid)
    }
}
