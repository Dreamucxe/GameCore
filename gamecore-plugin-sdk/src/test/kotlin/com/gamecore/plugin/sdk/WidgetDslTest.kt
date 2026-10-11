package com.gamecore.plugin.sdk

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `widgets { }` DSL: it emits one `widgets/<id>.json` file per widget, declares the section, and
 * bumps the manifest api to the 3.x level — while a plugin that declares no widgets stays byte-for-byte
 * the package it was before the feature existed.
 *
 * The structural point this pins is that a widget carries the OVERLAY component set, not the screen's.
 * [WidgetBuilder] is built on [OverlayNodeScope], so `button`, `slider`, `switch` and `scroll` are not
 * functions an author can call here at all — their absence is a compile-time guarantee, not a runtime
 * refusal, which is why no test below reaches for one: there is nothing to reach for. What a widget may
 * use is the nine read-only components, and those are exercised here.
 */
class WidgetDslTest {

    // ------------------------------------------------------------------------------- helpers

    private fun manifestOf(def: PluginDefinition): JSONObject =
        JSONObject(String(def.manifestJson))

    private fun widgetFile(def: PluginDefinition, id: String): JSONObject =
        JSONObject(String(def.sectionFiles.getValue("widgets/$id.json")))

    private fun declaresOf(def: PluginDefinition): List<String> {
        val manifest = manifestOf(def)
        if (!manifest.has("declares")) return emptyList()
        val arr = manifest.getJSONArray("declares")
        return (0 until arr.length()).map { arr.getString(it) }
    }

    private fun build(block: WidgetsBuilder.() -> Unit): PluginDefinition =
        plugin("com.example.w", "W") { widgets(block) }

    /** Every `type` in a node tree, root first. */
    private fun typesIn(node: JSONObject): List<String> {
        val children = node.optJSONArray("children") ?: JSONArray()
        return buildList {
            add(node.getString("type"))
            for (i in 0 until children.length()) addAll(typesIn(children.getJSONObject(i)))
        }
    }

    // PLACEHOLDER_WIDGET_TESTS

    // ------------------------------------------------------------------------------- happy path

    @Test
    fun `a widget carries the widget format, version, id, title, size and single root node`() {
        val def = build { widget("fps", "Frame rate", WidgetSizes.LARGE) { text("60") } }
        val w = widgetFile(def, "fps")
        assertEquals("gamecore.plugin.widget", w.getString("format"))
        assertEquals(Sdk.WIDGET_FORMAT, w.getString("format"))
        assertEquals(Sdk.WIDGET_VERSION, w.getInt("version"))
        assertEquals("fps", w.getString("id"))
        assertEquals("Frame rate", w.getString("title"))
        assertEquals("large", w.getString("size"))
        assertEquals("text", w.getJSONObject("content").getString("type"))
    }

    @Test
    fun `size defaults to medium and a blank title is omitted`() {
        val w = widgetFile(build { widget("w") { text("x") } }, "w")
        assertEquals(WidgetSizes.MEDIUM, w.getString("size"))
        assertEquals("medium", w.getString("size"))
        assertFalse(w.has("title"))
    }

    @Test
    fun `the full size is accepted`() {
        val w = widgetFile(build { widget("w", "W", WidgetSizes.FULL) { text("x") } }, "w")
        assertEquals("full", w.getString("size"))
    }

    @Test
    fun `each widget is its own widgets slash id dot json file`() {
        val def = build {
            widget("a", "A") { text("x") }
            widget("b", "B") { text("y") }
        }
        assertTrue(def.sectionFiles.containsKey("widgets/a.json"))
        assertTrue(def.sectionFiles.containsKey("widgets/b.json"))
        assertEquals("a", widgetFile(def, "a").getString("id"))
        assertEquals("b", widgetFile(def, "b").getString("id"))
    }

    @Test
    fun `a widget may carry the full overlay component set`() {
        val def = build {
            widget("w") {
                column(gap = 8, padding = 12) {
                    text("t")
                    stat("fps")
                    spacer(4)
                    image("speed")
                    row { text("r") }
                    grid(columns = 2) { text("g") }
                    card(background = "#101010") { text("c") }
                    chart(values = listOf(1, 2, 3))
                }
            }
        }
        val types = typesIn(widgetFile(def, "w").getJSONObject("content")).toSet()
        assertEquals(
            setOf("column", "text", "stat", "spacer", "image", "row", "grid", "card", "chart"),
            types,
        )
    }

    @Test
    fun `a widget may plot a literal chart`() {
        val def = build { widget("plot", "Plot", WidgetSizes.FULL) { chart(values = listOf(1, 2, 3), kind = ChartKinds.BAR) } }
        val content = widgetFile(def, "plot").getJSONObject("content")
        assertEquals("chart", content.getString("type"))
        assertEquals(ChartKinds.BAR, content.getJSONObject("props").getString("kind"))
    }

    // ------------------------------------------------------------------------------- api bump

    @Test
    fun `declaring a widget raises the manifest api to v3 and declares the section`() {
        val def = build { widget("w", "W") { text("x") } }
        assertEquals(Sdk.API_V3, manifestOf(def).getInt("api"))
        assertTrue(declaresOf(def).contains("widgets"))
    }

    @Test
    fun `a plugin with no widgets stays at the base api, declares none and ships no widget file`() {
        val def = plugin("com.example.pill", "Pill") {
            pill {
                id = "badge"
                text("Speed")
            }
        }
        val manifest = manifestOf(def)
        assertEquals(Sdk.API, manifest.getInt("api"))
        assertTrue(manifest.getInt("api") < Sdk.API_V3)
        assertFalse(declaresOf(def).contains("widgets"))
        assertTrue(def.sectionFiles.keys.none { it.startsWith("widgets/") })
    }

    @Test
    fun `the feature is additive — a plugin that declares no widgets is byte-identical build to build`() {
        // "Byte-identical when unused": the mere existence of the widgets surface changes nothing about
        // a plugin that never calls it. The manifest a no-widgets plugin emits is deterministic and
        // carries neither the api bump nor the declares entry.
        val a = plugin("com.example.base", "Base") { theme { name = "T" } }
        val b = plugin("com.example.base", "Base") { theme { name = "T" } }
        assertContentEquals(a.manifestJson, b.manifestJson)
        assertEquals(Sdk.API, manifestOf(a).getInt("api"))
        assertFalse(declaresOf(a).contains("widgets"))

        // Adding a widget to the same body is what moves the bytes — api up, declares extended.
        val withWidget = plugin("com.example.base", "Base") {
            theme { name = "T" }
            widgets { widget("w", "W") { text("x") } }
        }
        assertFalse(a.manifestJson.contentEquals(withWidget.manifestJson))
        assertEquals(Sdk.API_V3, manifestOf(withWidget).getInt("api"))
    }

    // ------------------------------------------------------------------------------- refusals

    @Test
    fun `a blank id is refused`() {
        assertFailsWith<IllegalArgumentException> { build { widget("") { text("x") } } }
    }

    @Test
    fun `an id outside the slug grammar is refused`() {
        assertFailsWith<IllegalArgumentException> { build { widget("Bad Id") { text("x") } } }
    }

    @Test
    fun `a duplicate id is refused`() {
        assertFailsWith<IllegalArgumentException> {
            build {
                widget("w") { text("x") }
                widget("w") { text("y") }
            }
        }
    }

    @Test
    fun `an unknown size is refused`() {
        assertFailsWith<IllegalArgumentException> { build { widget("w", size = "huge") { text("x") } } }
    }

    @Test
    fun `a widget with no root node is refused`() {
        assertFailsWith<IllegalArgumentException> { build { widget("w") { } } }
    }

    @Test
    fun `a widget with more than one root node is refused`() {
        assertFailsWith<IllegalArgumentException> { build { widget("w") { text("x"); text("y") } } }
    }

    @Test
    fun `an empty widgets section is refused`() {
        assertFailsWith<IllegalArgumentException> { build { } }
    }

    @Test
    fun `more widgets than the cap is refused`() {
        assertFailsWith<IllegalArgumentException> {
            build { repeat(WidgetBounds.MAX_WIDGETS + 1) { widget("w$it") { text("x") } } }
        }
    }

    @Test
    fun `a container with too many children is refused`() {
        assertFailsWith<IllegalArgumentException> {
            build { widget("w") { column { repeat(OverlayBounds.MAX_CHILDREN + 1) { text("x") } } } }
        }
    }

    @Test
    fun `a tree deeper than the cap is refused`() {
        assertFailsWith<IllegalArgumentException> {
            build { widget("deep") { deepColumn(this, OverlayBounds.MAX_DEPTH + 5) } }
        }
    }

    /** Nests [depth] columns, then a text leaf, to drive the depth bound. */
    private fun deepColumn(scope: OverlayNodeScope, depth: Int) {
        if (depth <= 0) {
            scope.text("leaf")
            return
        }
        scope.column { deepColumn(this, depth - 1) }
    }
}
