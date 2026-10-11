package com.gamecore.plugin.sdk

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The `screens` authoring surface: the DSL emits an envelope the host's [PluginScreensCodec] accepts,
 * and refuses — at the author's build, not on a user's device — everything that codec would reject.
 *
 * The rejection cases carry the weight. A screen reaches a real navigation route; "the host will catch
 * it" is no answer for an author whose screen silently fails to install. So id grammar, duplicate ids,
 * titles, icons, grid width, the stat vocabulary, the finite-slider rule, cross-axis alignment, and the
 * tree bounds (depth, children) are each a build-time failure here.
 *
 * This also pins the one structural difference from the overlay DSL: a screen carries the FULL
 * component set — `button`, `slider`, `switch` and `scroll` included — because a screen is a touchable,
 * scrollable surface. The emitted prop names are the ones the host renderer reads, so a screen authored
 * here draws as written.
 */
class PluginScreensDslTest {

    // ------------------------------------------------------------------------------- helpers

    private fun screensOf(block: ScreensBuilder.() -> Unit): JSONObject =
        ScreensBuilder().apply(block).toJson()

    private fun firstScreen(block: ScreensBuilder.() -> Unit): JSONObject =
        screensOf(block).getJSONArray("screens").getJSONObject(0)

    private fun contentOf(block: ScreenNodeScope.() -> Unit): JSONObject =
        firstScreen { screen("s", "S", block = block) }.getJSONObject("content")

    /** Every `type` in a node tree, root first. */
    private fun typesIn(node: JSONObject): List<String> {
        val children = node.optJSONArray("children") ?: JSONArray()
        return buildList {
            add(node.getString("type"))
            for (i in 0 until children.length()) addAll(typesIn(children.getJSONObject(i)))
        }
    }

    // ------------------------------------------------------------------------------- happy path

    @Test
    fun `a screens section carries the format and version`() {
        val file = screensOf { screen("overview", "Overview") { text("Hi") } }
        assertEquals("gamecore.plugin.screens", file.getString("format"))
        assertEquals(Sdk.SCREENS_FORMAT, file.getString("format"))
        assertEquals(Sdk.SCREENS_VERSION, file.getInt("version"))
    }

    @Test
    fun `a screen carries its id, title, icon and single root node`() {
        val s = firstScreen { screen("overview", "Overview", icon = "insights") { text("Hi") } }
        assertEquals("overview", s.getString("id"))
        assertEquals("Overview", s.getString("title"))
        assertEquals("insights", s.getString("icon"))
        assertEquals("text", s.getJSONObject("content").getString("type"))
    }

    @Test
    fun `an icon is optional and omitted when not given`() {
        val s = firstScreen { screen("overview", "Overview") { text("Hi") } }
        assertTrue(!s.has("icon"))
    }

    @Test
    fun `several screens emit in author order`() {
        val file = screensOf {
            screen("a", "A") { text("x") }
            screen("b", "B") { text("y") }
        }
        val ids = (0 until file.getJSONArray("screens").length())
            .map { file.getJSONArray("screens").getJSONObject(it).getString("id") }
        assertEquals(listOf("a", "b"), ids)
    }

    // --------------------------------------------------------------------------- full component set

    @Test
    fun `a screen may carry all twelve component types`() {
        val content = contentOf {
            column(gap = 12, align = "center", padding = 16) {
                text("t")
                button("b")
                slider(50.0)
                switch(true)
                stat("fps")
                spacer(8)
                image("speed")
                row(gap = 8) { text("r") }
                grid(columns = 2) { text("g") }
                scroll { text("s") }
                card(background = "#101010") { text("c") }
            }
        }
        val types = typesIn(content).toSet()
        val all = setOf(
            "column", "row", "grid", "scroll", "card",
            "text", "button", "slider", "switch", "stat", "spacer", "image",
        )
        assertEquals(all, types)
    }

    // --------------------------------------------------------------------- the renderer's prop names

    @Test
    fun `containers emit gap, padding, align and grid columns under the names the renderer reads`() {
        val col = contentOf { column(gap = 10, align = "center", padding = 16) { text("x") } }
        val props = col.getJSONObject("props")
        assertEquals(10, props.getInt("gap"))
        assertEquals(16, props.getInt("padding"))
        assertEquals("center", props.getString("align"))

        val grid = contentOf { grid(columns = 3) { text("x") } }
        assertEquals(3, grid.getJSONObject("props").getInt("columns"))
    }

    @Test
    fun `a card's background is emitted as the color prop`() {
        val card = contentOf { card(background = "#101418") { text("x") } }
        assertTrue(card.getJSONObject("props").has("color"))
    }

    @Test
    fun `text emits text, size, bold and align`() {
        val col = contentOf { text("Hello", size = 20, bold = true, align = "center") }
        val props = col.getJSONObject("props")
        assertEquals("Hello", props.getString("text"))
        assertEquals(20, props.getInt("size"))
        assertTrue(props.getBoolean("bold"))
        assertEquals("center", props.getString("align"))
    }

    @Test
    fun `slider emits value, min and max`() {
        val node = contentOf { slider(value = 42.0, min = 0.0, max = 50.0) }
        val props = node.getJSONObject("props")
        assertEquals(42.0, props.getDouble("value"), 0.0001)
        assertEquals(0.0, props.getDouble("min"), 0.0001)
        assertEquals(50.0, props.getDouble("max"), 0.0001)
    }

    @Test
    fun `switch emits its boolean value`() {
        val node = contentOf { switch(true) }
        assertTrue(node.getJSONObject("props").getBoolean("value"))
    }

    @Test
    fun `stat emits its stat id`() {
        val node = contentOf { stat("fps") }
        assertEquals("fps", node.getJSONObject("props").getString("stat"))
    }

    @Test
    fun `image emits its icon name`() {
        val node = contentOf { image("speed") }
        assertEquals("speed", node.getJSONObject("props").getString("icon"))
    }

    @Test
    fun `a leading at-sign in text is doubled so it is kept literal`() {
        val node = contentOf { text("@home") }
        assertTrue(node.getJSONObject("props").getString("text").startsWith("@@"))
    }

    // PLACEHOLDER_TESTS2

    // ------------------------------------------------------------------------------- refusals

    @Test
    fun `a blank id is refused`() {
        assertFailsWith<IllegalArgumentException> { screensOf { screen("", "T") { text("x") } } }
    }

    @Test
    fun `an id outside the slug grammar is refused`() {
        assertFailsWith<IllegalArgumentException> { screensOf { screen("Bad Id", "T") { text("x") } } }
    }

    @Test
    fun `a duplicate id is refused`() {
        assertFailsWith<IllegalArgumentException> {
            screensOf {
                screen("home", "One") { text("x") }
                screen("home", "Two") { text("y") }
            }
        }
    }

    @Test
    fun `a blank title is refused`() {
        assertFailsWith<IllegalArgumentException> { screensOf { screen("a", "   ") { text("x") } } }
    }

    @Test
    fun `an icon outside GameCore's own set is refused`() {
        assertFailsWith<IllegalArgumentException> {
            screensOf { screen("a", "T", icon = "not-an-icon") { text("x") } }
        }
    }

    @Test
    fun `a grid with no columns or too many columns is refused`() {
        assertFailsWith<IllegalArgumentException> { contentOf { grid(columns = 0) { text("x") } } }
        assertFailsWith<IllegalArgumentException> {
            contentOf { grid(columns = ScreenBounds.MAX_GRID_COLUMNS + 1) { text("x") } }
        }
    }

    @Test
    fun `an unknown stat is refused`() {
        assertFailsWith<IllegalArgumentException> { contentOf { stat("not-a-stat") } }
    }

    @Test
    fun `an image naming an unknown icon is refused`() {
        assertFailsWith<IllegalArgumentException> { contentOf { image("not-an-icon") } }
    }

    @Test
    fun `a non-finite slider is refused`() {
        assertFailsWith<IllegalArgumentException> { contentOf { slider(Double.NaN) } }
        assertFailsWith<IllegalArgumentException> { contentOf { slider(Double.POSITIVE_INFINITY) } }
    }

    @Test
    fun `an alignment word from the wrong axis is refused`() {
        // A column's cross axis is horizontal, so a vertical word is refused rather than silently pinned.
        assertFailsWith<IllegalArgumentException> { contentOf { column(align = "top") { text("x") } } }
        // A row's cross axis is vertical, so a horizontal word is refused.
        assertFailsWith<IllegalArgumentException> { contentOf { row(align = "left") { text("x") } } }
    }

    @Test
    fun `a screen with no root node is refused`() {
        assertFailsWith<IllegalArgumentException> { screensOf { screen("a", "T") { } } }
    }

    @Test
    fun `a screen with more than one root node is refused`() {
        assertFailsWith<IllegalArgumentException> {
            screensOf { screen("a", "T") { text("x"); text("y") } }
        }
    }

    @Test
    fun `an empty screens section is refused`() {
        assertFailsWith<IllegalArgumentException> { screensOf { } }
    }

    @Test
    fun `more than the cap of screens is refused`() {
        assertFailsWith<IllegalArgumentException> {
            screensOf { repeat(ScreenBounds.MAX_SCREENS + 1) { screen("s$it", "S$it") { text("x") } } }
        }
    }

    @Test
    fun `a container with too many children is refused`() {
        assertFailsWith<IllegalArgumentException> {
            contentOf { column { repeat(ScreenBounds.MAX_CHILDREN + 1) { text("x") } } }
        }
    }

    @Test
    fun `a tree deeper than the cap is refused`() {
        assertFailsWith<IllegalArgumentException> {
            screensOf { screen("deep", "Deep") { deepColumn(this, ScreenBounds.MAX_DEPTH + 5) } }
        }
    }

    /** Nests [depth] columns, then a text leaf, to drive the depth bound. */
    private fun deepColumn(scope: ScreenNodeScope, depth: Int) {
        if (depth <= 0) {
            scope.text("leaf")
            return
        }
        scope.column { deepColumn(this, depth - 1) }
    }
}
