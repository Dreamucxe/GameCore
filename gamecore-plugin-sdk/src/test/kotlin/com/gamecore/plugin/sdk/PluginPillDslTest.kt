package com.gamecore.plugin.sdk

import com.gamecore.plugin.sdk.templates.Templates
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `pill` section gate: the DSL emits a module the host's codec will accept, and refuses —
 * at the author's build, not on someone's screen — everything it would reject.
 *
 * The rejection tests matter more than the happy path. A module is drawn over a running game
 * through a permission the user granted once; "the host will catch it" is not a usable answer
 * for an author whose readout silently does not appear.
 */
class PluginPillDslTest {

    private fun pillOf(definition: PluginDefinition): JSONObject =
        JSONObject(String(definition.sectionFiles.getValue("pill.json")))

    /** Every `type` in a module tree, root first. */
    private fun typesIn(node: JSONObject): List<String> {
        val children = node.optJSONArray("children") ?: JSONArray()
        return buildList {
            add(node.getString("type"))
            for (i in 0 until children.length()) addAll(typesIn(children.getJSONObject(i)))
        }
    }

    private fun module(block: PillBuilder.() -> Unit): PluginDefinition =
        plugin("com.example.pill", "Pill") { pill(block) }

    // ------------------------------------------------------------------------------- happy path

    @Test
    fun `a pill section carries the overlay format and version`() {
        val pill = pillOf(Templates.overlay())
        assertEquals("gamecore.plugin.pill", pill.getString("format"))
        assertEquals(Sdk.PILL_FORMAT, pill.getString("format"))
        assertEquals(1, pill.getInt("version"))
        assertEquals("fps-badge", pill.getString("id"))
        assertEquals("Frame rate", pill.getString("title"))
    }

    @Test
    fun `anchor is a pair of fractions, size is an id, opacity is a percent`() {
        val pill = pillOf(Templates.overlay())
        assertEquals(0.5, pill.getJSONObject("anchor").getDouble("x"), 0.0001)
        assertEquals(0.08, pill.getJSONObject("anchor").getDouble("y"), 0.0001)
        assertEquals(OverlaySizes.SMALL, pill.getString("size"))
        assertEquals(85, pill.getInt("opacity"))
    }

    @Test
    fun `the content tree keeps the author's shape and stat ids`() {
        val content = pillOf(Templates.overlay()).getJSONObject("content")
        assertEquals("row", content.getString("type"))
        assertEquals(10, content.getJSONObject("props").getInt("gap"))
        assertEquals(listOf("row", "column", "text", "stat", "column", "text", "stat"), typesIn(content))

        val firstStat = content.getJSONArray("children").getJSONObject(0)
            .getJSONArray("children").getJSONObject(1)
        assertEquals(OverlayStats.FPS, firstStat.getJSONObject("props").getString("stat"))
    }

    @Test
    fun `declaring a module emits pill dot json and declares the pill section`() {
        val definition = Templates.overlay()
        val manifest = JSONObject(String(definition.manifestJson))
        assertTrue(manifest.getJSONArray("declares").toList().contains("pill"))
        assertTrue(definition.sectionFiles.containsKey("pill.json"))
    }

    @Test
    fun `a packaged plugin that declares a pill actually contains pill dot json`() {
        val out = File.createTempFile("overlay", ".gcplugin")
        PluginPackager.writeTo(out, Templates.overlay())
        val names = mutableListOf<String>()
        ZipInputStream(out.inputStream()).use { zin ->
            var e = zin.nextEntry
            while (e != null) { names += e.name; e = zin.nextEntry }
        }
        assertTrue(names.contains("manifest.json"))
        assertTrue(names.contains("pill.json"))
        out.delete()
    }

    @Test
    fun `the overlay template asks for the permissions its module needs`() {
        val manifest = JSONObject(String(Templates.overlay().manifestJson))
        val permissions = manifest.getJSONArray("permissions")
        val byId = (0 until permissions.length())
            .map { permissions.getJSONObject(it) }
            .associateBy { it.getString("id") }

        // create_overlay is the gate for drawing the module; add_pill_modules is a different seam.
        assertTrue(byId.containsKey(Permissions.CREATE_OVERLAY))
        assertFalse(byId.containsKey(Permissions.ADD_PILL_MODULES))

        val scopes = byId.getValue(Permissions.READ_APPROVED_DATA)
            .getJSONArray("scopes").toList().map { it.toString() }
        assertTrue(scopes.contains(DataScopes.DEVICE_STATS))
        assertTrue(scopes.contains(DataScopes.THERMALS))
        assertEquals(DataScopes.DEVICE_STATS, OverlayStats.scopeFor(OverlayStats.FPS))
        assertEquals(DataScopes.THERMALS, OverlayStats.scopeFor(OverlayStats.TEMP))
    }

    /**
     * The four interactive types have no function on the scope, so this walk can only ever see
     * the eight the host allows — the test records the guarantee the compiler is enforcing.
     */
    @Test
    fun `only the host's non-interactive node types can be emitted`() {
        val allowed = setOf("column", "row", "grid", "card", "text", "stat", "spacer", "image")
        val rejected = setOf("button", "slider", "switch", "scroll")
        val types = typesIn(pillOf(Templates.overlay()).getJSONObject("content"))
        assertTrue(types.all { it in allowed }, "unexpected node types: ${types - allowed}")
        assertTrue(types.none { it in rejected })
    }

    // -------------------------------------------------------------------------------- rejections

    @Test
    fun `an opacity outside the host's clamp is refused`() {
        assertFailsWith<IllegalArgumentException> {
            module { id = "m"; opacity = 10; text("hi") }
        }
        assertFailsWith<IllegalArgumentException> {
            module { id = "m"; opacity = 140; text("hi") }
        }
        // The edges are legal.
        module { id = "m"; opacity = OverlayBounds.MIN_OPACITY; text("hi") }
        module { id = "m"; opacity = OverlayBounds.MAX_OPACITY; text("hi") }
    }

    @Test
    fun `an anchor outside the unit square is refused`() {
        assertFailsWith<IllegalArgumentException> {
            module { id = "m"; anchor(x = 1.4, y = 0.1); text("hi") }
        }
        assertFailsWith<IllegalArgumentException> {
            module { id = "m"; anchor(x = 0.5, y = -0.2); text("hi") }
        }
        module { id = "m"; anchor(x = 0.0, y = 1.0); text("hi") }
    }

    @Test
    fun `an unknown stat id is refused`() {
        assertFailsWith<IllegalArgumentException> {
            module { id = "m"; stat("framerate") }
        }
        assertFailsWith<IllegalArgumentException> {
            module { id = "m"; stat("FPS") } // the ids are lowercase
        }
        OverlayStats.ALL.forEach { known -> module { id = "m"; stat(known) } }
    }

    @Test
    fun `an unknown size id is refused`() {
        assertFailsWith<IllegalArgumentException> {
            module { id = "m"; size = "huge"; text("hi") }
        }
    }

    @Test
    fun `a module needs exactly one root node`() {
        assertFailsWith<IllegalArgumentException> { module { id = "m" } }
        assertFailsWith<IllegalArgumentException> {
            module { id = "m"; text("one"); text("two") }
        }
    }

    @Test
    fun `a module needs an id`() {
        assertFailsWith<IllegalArgumentException> { module { text("hi") } }
    }

    @Test
    fun `a grid wider than the cap is refused`() {
        assertFailsWith<IllegalArgumentException> {
            module { id = "m"; grid(columns = OverlayBounds.MAX_GRID_COLUMNS + 1) { text("hi") } }
        }
        assertFailsWith<IllegalArgumentException> {
            module { id = "m"; grid(columns = 0) { text("hi") } }
        }
        module { id = "m"; grid(columns = OverlayBounds.MAX_GRID_COLUMNS) { text("hi") } }
    }

    @Test
    fun `a tree deeper than the cap is refused, and the cap counts the root as zero`() {
        // Seven containers: depths 0..6, the deepest tree the host accepts.
        module {
            id = "m"
            column { column { column { column { column { column { column { } } } } } } }
        }
        // One node further down is depth 7.
        assertFailsWith<IllegalArgumentException> {
            module {
                id = "m"
                column { column { column { column { column { column { column { text("deep") } } } } } } }
            }
        }
    }

    @Test
    fun `a tree with more nodes than the cap is refused`() {
        assertFailsWith<IllegalArgumentException> {
            module {
                id = "m"
                column {
                    repeat(4) {
                        column { repeat(OverlayBounds.MAX_CHILDREN) { text("n") } }
                    }
                }
            }
        }
    }

    @Test
    fun `a container with more children than the cap is refused`() {
        assertFailsWith<IllegalArgumentException> {
            module {
                id = "m"
                column { repeat(OverlayBounds.MAX_CHILDREN + 1) { text("n") } }
            }
        }
        module {
            id = "m"
            column { repeat(OverlayBounds.MAX_CHILDREN) { text("n") } }
        }
    }

    @Test
    fun `an over-long string is refused`() {
        assertFailsWith<IllegalArgumentException> {
            module { id = "m"; text("x".repeat(OverlayBounds.MAX_STRING_LENGTH + 1)) }
        }
        assertFailsWith<IllegalArgumentException> {
            module {
                id = "m"
                title = "t".repeat(OverlayBounds.MAX_STRING_LENGTH + 1)
                text("hi")
            }
        }
        assertFailsWith<IllegalArgumentException> {
            module { id = "i".repeat(OverlayBounds.MAX_ID_LENGTH + 1); text("hi") }
        }
    }

    @Test
    fun `a control or bidi character is refused rather than silently stripped`() {
        assertFailsWith<IllegalArgumentException> { module { id = "m"; text("FPS\u0007") } }
        assertFailsWith<IllegalArgumentException> { module { id = "m"; text("FPS‮") } }
    }

    @Test
    fun `a leading at sign is escaped, so a label can never become an action reference`() {
        val definition = module { id = "m"; text("@boost") }
        val content = pillOf(definition).getJSONObject("content")
        // "@@boost" decodes host-side to the literal string "@boost", never to an ActionRef.
        assertEquals("@@boost", content.getJSONObject("props").getString("text"))
    }

    @Test
    fun `a colour that is not hex is refused rather than becoming a label`() {
        assertFailsWith<IllegalArgumentException> {
            module { id = "m"; text("FPS", color = "#NOTHEX") }
        }
        assertFailsWith<IllegalArgumentException> {
            module { id = "m"; text("FPS", color = "white") }
        }
        module { id = "m"; text("FPS", color = "#99FFFFFF") }
        module { id = "m"; text("FPS", color = "#FFFFFF") }
    }

    // --------------------------------------------------------------------------- prop-name parity

    /**
     * The failure this file exists to prevent, in its quietest form.
     *
     * A prop the host does not read is not an error anywhere: the codec accepts any prop key
     * within its caps, the renderer asks for the key it knows and gets nothing, and the node
     * draws with its default. So a DSL that emitted `background` for a card's fill would
     * produce a module that loads, validates, renders — and ignores the colour the author
     * chose, with nothing in any log to say why.
     *
     * The right-hand set is `ui/plugins/render/PluginNodeRenderer.kt`'s vocabulary, written out
     * because this module cannot import it. If that file's reads change, this assertion is the
     * thing that should fail.
     */
    @Test
    fun `every emitted prop key is one the host renderer reads`() {
        val hostReads = mapOf(
            "column" to setOf("gap", "align", "padding"),
            "row" to setOf("gap", "align", "padding"),
            "grid" to setOf("columns", "gap", "align", "padding"),
            "card" to setOf("color", "gap", "align", "padding"),
            "text" to setOf("text", "color", "size", "weight", "bold", "align", "maxLines", "padding"),
            "stat" to setOf("stat", "label", "color", "size", "weight", "bold", "align", "padding"),
            "spacer" to setOf("size"),
            "image" to setOf("icon", "color", "size", "padding"),
        )

        val definition = module {
            id = "parity"
            card(padding = 8, background = "#CC000000") {
                row(gap = 12, align = "center", padding = 1) {
                    column(gap = 2, align = "start", padding = 1) {
                        text("FPS", color = "#FFFFFF", size = 10)
                        stat(OverlayStats.FPS, size = 16, color = "#FFFFFF")
                    }
                    spacer(size = 6)
                    image(OverlayIcons.THERMOSTAT, size = 14)
                    grid(columns = 2, gap = 1, padding = 1) { text("x") }
                }
            }
        }

        fun check(node: JSONObject) {
            val type = node.getString("type")
            val allowed = hostReads.getValue(type)
            val props = node.optJSONObject("props") ?: JSONObject()
            props.keys().forEach { key ->
                assertTrue(key in allowed, "\"$type\" emits prop \"$key\", which the host never reads")
            }
            val children = node.optJSONArray("children") ?: JSONArray()
            for (i in 0 until children.length()) check(children.getJSONObject(i))
        }

        val content = pillOf(definition).getJSONObject("content")
        check(content)
        // The tree has to actually contain all eight types, or the walk above proves nothing.
        assertEquals(hostReads.keys, typesIn(content).toSet())
    }

    @Test
    fun `a card's fill is emitted under the key the host reads`() {
        val definition = module { id = "m"; card(background = "#CC101010") { text("hi") } }
        val props = pillOf(definition).getJSONObject("content").getJSONObject("props")
        assertEquals("#CC101010", props.getString("color"))
        assertFalse(props.has("background"))
    }

    /**
     * A spacer is square host-side, so the DSL offers one dimension. Two would be a promise the
     * renderer does not keep — `width` and `height` would both be dropped and the gap would
     * silently come out at the renderer's default.
     */
    @Test
    fun `a spacer takes one dimension and emits it as size`() {
        val definition = module { id = "m"; column { spacer(size = 6) } }
        val spacer = pillOf(definition).getJSONObject("content")
            .getJSONArray("children").getJSONObject(0)
        assertEquals(6, spacer.getJSONObject("props").getInt("size"))
    }

    /**
     * The host loads no plugin artwork at all, so an image names one of GameCore's own vectors.
     * An unknown name is a placeholder glyph on a device — visible, wrong, and silent — which is
     * why it fails here instead.
     */
    @Test
    fun `an image names a built-in icon and an unknown name is refused`() {
        val definition = module { id = "m"; column { image(OverlayIcons.SPEED) } }
        val image = pillOf(definition).getJSONObject("content")
            .getJSONArray("children").getJSONObject(0)
        assertEquals("speed", image.getJSONObject("props").getString("icon"))

        assertFailsWith<IllegalArgumentException> {
            module { id = "m"; column { image("logo.png") } }
        }
        assertFailsWith<IllegalArgumentException> {
            module { id = "m"; column { image("SPEED") } } // the ids are lowercase
        }
        OverlayIcons.ALL.forEach { known -> module { id = "m"; column { image(known) } } }
    }

    // ------------------------------------------------------------------- identity and cross axis

    /**
     * The title cap is the host's, not the string-prop cap.
     *
     * `MAX_STRING_LENGTH` is 120 and the host truncates a title at 48, so a 60-character title
     * used to build cleanly and arrive shortened — the one case in this file where the host
     * neither rejects nor honours what the author wrote.
     */
    @Test
    fun `a title longer than the host's own cap is refused, not shortened`() {
        assertTrue(OverlayBounds.MAX_TITLE_LENGTH < OverlayBounds.MAX_STRING_LENGTH)
        module { id = "m"; title = "t".repeat(OverlayBounds.MAX_TITLE_LENGTH); text("hi") }
        assertFailsWith<IllegalArgumentException> {
            module { id = "m"; title = "t".repeat(OverlayBounds.MAX_TITLE_LENGTH + 1); text("hi") }
        }
    }

    /**
     * An id outside the host's grammar is either renamed or dropped, never honoured: the host
     * trims and lowercases first, then rejects whatever still does not match. `"Module"` would
     * register as `module` and `"my module"` would not register at all. Length alone was never
     * the whole rule.
     */
    @Test
    fun `an id outside the host's grammar is refused`() {
        listOf(
            "Module",      // uppercase
            "my module",   // space
            ".leading",    // starts with punctuation
            "-leading",
            "has/slash",
            "émoji",
            "i".repeat(OverlayBounds.MAX_ID_LENGTH + 1),
        ).forEach { bad ->
            assertFailsWith<IllegalArgumentException>("id \"$bad\" should be refused") {
                module { id = bad; text("hi") }
            }
        }

        listOf("m", "fps-badge", "perf.readout", "a_b", "x9", "i".repeat(OverlayBounds.MAX_ID_LENGTH))
            .forEach { good -> module { id = good; text("hi") } }
    }

    /**
     * The host resolves `align` against the node's cross axis and falls back to that axis's
     * default for a word it does not know, so `top` on a column is a layout silently pinned to
     * the start rather than an error. `end` is legal on both and means a different edge on each.
     */
    @Test
    fun `an alignment from the wrong axis is refused`() {
        assertFailsWith<IllegalArgumentException> {
            module { id = "m"; column(align = "top") { text("hi") } }
        }
        assertFailsWith<IllegalArgumentException> {
            module { id = "m"; row(align = "right") { text("hi") } }
        }
        assertFailsWith<IllegalArgumentException> {
            module { id = "m"; row(align = "centered") { text("hi") } }
        }
        module { id = "m"; column(align = "end") { text("hi") } }
        module { id = "m"; row(align = "end") { text("hi") } }
        module { id = "m"; row(align = "CENTER") { text("hi") } } // normalised, not refused
    }
}
