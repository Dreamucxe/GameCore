package com.gamecore.core.plugin.dashboard

import com.gamecore.core.plugin.overlay.ModuleStat
import com.gamecore.core.plugin.ui.ComponentNode
import com.gamecore.core.plugin.ui.ComponentType
import com.gamecore.core.plugin.ui.PropValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate on a panel a plugin places on the GameCore dashboard.
 *
 * This is [com.gamecore.core.plugin.overlay.PluginOverlayCodecTest] re-aimed at the home surface,
 * so it is almost all refusals for the same reason: a widget is drawn read-only, from data the user
 * has granted a specific aperture for, in a span of a fixed set — and every one of those properties
 * is a thing this codec enforces rather than something the renderer or the host can be trusted to
 * re-check. Each bound in the contract gets its own test with its own reason.
 *
 * The places this file deliberately diverges from the overlay codec's tests are the places the
 * surfaces differ: a `scroll` container is *accepted* here (the dashboard is scrolled with a finger),
 * there is no anchor to refuse, and a live chart series is *validated* against the stat vocabulary
 * rather than refused outright — the dashboard host resolves a chart's live feed where the overlay
 * host cannot, so the series' scope folds into the widget's appetite and the registry gates the whole
 * widget on it. Everything else — interactive leaves, action references, unknown stats, the envelope
 * guards — is refused exactly as it is for an overlay module.
 */
class PluginDashboardCodecTest {

    // ------------------------------------------------------------------------------ helpers

    private fun widget(body: String): String =
        """{"format":"gamecore.plugin.widget","version":1,$body}"""

    /** A worked example in the shape the SDK emits: a titled tile with a stat and a literal chart. */
    private val minimal = widget(
        """
        "id":"fps-tile","title":"Frame rate","size":"medium",
        "content":{"type":"column","props":{"gap":6},"children":[
          {"type":"text","props":{"text":"FPS","color":"#99FFFFFF","size":11}},
          {"type":"stat","props":{"stat":"fps","size":14}},
          {"type":"chart","props":{"kind":"line","values":"1,2,3,4"}}]}
        """.trimIndent(),
    )

    private fun parsed(json: String): PluginDashboardWidget {
        val r = PluginDashboardCodec.decode(json)
        assertTrue("expected Parsed, got $r", r is PluginDashboardCodec.Result.Parsed)
        return (r as PluginDashboardCodec.Result.Parsed).widget
    }

    private fun rejection(json: String): PluginDashboardCodec.Result.Rejected {
        val r = PluginDashboardCodec.decode(json)
        assertTrue("expected Rejected, got $r", r is PluginDashboardCodec.Result.Rejected)
        return r as PluginDashboardCodec.Result.Rejected
    }

    private fun reason(json: String): PluginDashboardCodec.Reason = rejection(json).reason

    /** A widget whose content is the single node given, for one-node content tests. */
    private fun withContent(content: String): String = widget(
        """"id":"w","title":"W","size":"medium","content":$content""",
    )

    // ------------------------------------------------------------------------------ happy path

    @Test
    fun `the worked example parses, charts and all`() {
        val m = parsed(minimal)
        assertEquals("fps-tile", m.id)
        assertEquals("Frame rate", m.title)
        assertEquals(WidgetSize.MEDIUM, m.size)
        assertEquals(ComponentType.COLUMN, m.content.type)
        assertEquals(3, m.content.children.size)
        // The chart is literal, so the only scope the tile asks for is its stat node's.
        assertEquals(setOf("device_stats"), m.requiredScopes)
    }

    @Test
    fun `an absent size defaults to the ordinary tile`() {
        val m = parsed(widget(""""id":"w","content":{"type":"spacer"}"""))
        assertEquals(WidgetSize.MEDIUM, m.size)
    }

    @Test
    fun `a title that sanitises away falls back to the id so the user can still find it`() {
        val m = parsed(widget(""""id":"w","title":"   ","content":{"type":"spacer"}"""))
        assertEquals("w", m.title)
    }

    @Test
    fun `a scroll container is allowed, unlike in an overlay module`() {
        // The dashboard is scrolled with a finger, so a scrolling container is legitimate content
        // here even though the overlay codec refuses it as a touch surface.
        val m = parsed(
            withContent("""{"type":"scroll","children":[{"type":"text","props":{"text":"x"}}]}"""),
        )
        assertEquals(ComponentType.SCROLL, m.content.type)
    }
    // ------------------------------------------------------------------------------ envelope guards

    @Test
    fun `a file that is not ours is rejected`() {
        val bad = minimal.replace("gamecore.plugin.widget", "gamecore.plugin.pill")
        assertEquals(PluginDashboardCodec.Reason.WRONG_FORMAT, reason(bad))
    }

    @Test
    fun `a missing or future version is rejected rather than partially honoured`() {
        val missing = """{"format":"gamecore.plugin.widget","id":"w","content":{"type":"spacer"}}"""
        assertEquals(PluginDashboardCodec.Reason.MISSING_FIELD, reason(missing))
        val future = minimal.replace("\"version\":1", "\"version\":2")
        assertEquals(PluginDashboardCodec.Reason.BAD_VERSION, reason(future))
    }

    @Test
    fun `an unknown top-level key is rejected, not ignored`() {
        // opacity is an overlay field; a widget has no such key, so it is unknown here.
        val bad = minimal.replaceFirst("\"id\"", "\"opacity\":80,\"id\"")
        assertEquals(PluginDashboardCodec.Reason.UNKNOWN_KEY, reason(bad))
    }

    @Test
    fun `an unknown key nested in a node is rejected as a bad node`() {
        val r = rejection(withContent("""{"type":"text","props":{"text":"x"},"tappable":true}"""))
        assertEquals(PluginDashboardCodec.Reason.BAD_NODE, r.reason)
        assertTrue(r.detail, r.detail.contains("tappable"))
    }

    @Test
    fun `a widget id that is not a token is rejected, not cleaned`() {
        assertEquals(
            PluginDashboardCodec.Reason.BAD_ID,
            reason(widget(""""id":"fps tile!","content":{"type":"spacer"}""")),
        )
        assertEquals(
            PluginDashboardCodec.Reason.BAD_ID,
            reason(widget(""""id":"-leading","content":{"type":"spacer"}""")),
        )
    }

    @Test
    fun `empty and malformed input are rejected, never thrown`() {
        assertEquals(PluginDashboardCodec.Reason.EMPTY, reason(""))
        assertTrue(PluginDashboardCodec.decode(null) is PluginDashboardCodec.Result.Rejected)
        assertEquals(PluginDashboardCodec.Reason.MALFORMED, reason("{not json"))
    }
    // ------------------------------------------------------------------------------ the size enum

    @Test
    fun `size is a closed enum, so a widget cannot grow into an arbitrary surface`() {
        assertEquals(
            PluginDashboardCodec.Reason.BAD_SIZE,
            reason(widget(""""id":"w","size":"jumbo","content":{"type":"spacer"}""")),
        )
        assertEquals(
            PluginDashboardCodec.Reason.BAD_SIZE,
            reason(widget(""""id":"w","size":"480x900","content":{"type":"spacer"}""")),
        )
    }

    // ------------------------------------------------------------------------------ no dispatch surface

    @Test
    fun `every interactive node type is rejected outright`() {
        // scroll is deliberately absent: it is allowed on the dashboard and tested above.
        listOf("button", "slider", "switch").forEach { type ->
            val r = rejection(withContent("""{"type":"$type"}"""))
            assertEquals(type, PluginDashboardCodec.Reason.INTERACTIVE_NODE, r.reason)
            assertTrue(r.detail, r.detail.contains(type))
        }
    }

    @Test
    fun `an interactive node is rejected however deeply it is buried`() {
        val content = """
            {"type":"column","children":[{"type":"card","children":[
              {"type":"row","children":[{"type":"button","props":{"label":"Go"}}]}]}]}
        """.trimIndent()
        assertEquals(PluginDashboardCodec.Reason.INTERACTIVE_NODE, reason(withContent(content)))
    }

    @Test
    fun `an action reference is rejected, because a widget could never dispatch one`() {
        val r = rejection(
            withContent("""{"type":"text","props":{"on_tap":"@set_peak_refresh_rate"}}"""),
        )
        assertEquals(PluginDashboardCodec.Reason.ACTION_REF, r.reason)
        assertTrue(r.detail, r.detail.contains("set_peak_refresh_rate"))
    }
    // ------------------------------------------------------------------------------ stats and scopes

    @Test
    fun `an unknown stat id is rejected`() {
        val r = rejection(withContent("""{"type":"stat","props":{"stat":"clipboard"}}"""))
        assertEquals(PluginDashboardCodec.Reason.BAD_STAT, r.reason)
        assertTrue(r.detail, r.detail.contains("clipboard"))
    }

    @Test
    fun `a stat node with no stat prop is rejected`() {
        assertEquals(
            PluginDashboardCodec.Reason.BAD_STAT,
            reason(withContent("""{"type":"stat","props":{"size":14}}""")),
        )
    }

    @Test
    fun `required scopes are derived from the tree, across every stat in it`() {
        val content = """
            {"type":"column","children":[
              {"type":"stat","props":{"stat":"fps"}},
              {"type":"row","children":[
                {"type":"stat","props":{"stat":"battery"}},
                {"type":"stat","props":{"stat":"cpu"}}]}]}
        """.trimIndent()
        val m = parsed(withContent(content))
        assertEquals(setOf("device_stats", "thermals"), m.requiredScopes)
    }

    @Test
    fun `a literal chart is allowed and, naming no live feed, asks for no scope`() {
        // No stat/stat2: the chart is literal. It decodes exactly as before and contributes nothing to
        // the appetite — this is the literal-chart path the relax must leave byte-identical.
        val m = parsed(withContent("""{"type":"chart","props":{"values":"1,2,3"}}"""))
        assertEquals(ComponentType.CHART, m.content.type)
        assertTrue("a literal chart contributes no scope", m.requiredScopes.isEmpty())
    }

    @Test
    fun `a live chart series is validated against the stat vocabulary, not refused outright`() {
        // A known feed is now accepted at either slot, where the overlay codec would refuse it...
        assertEquals(
            ComponentType.CHART,
            parsed(withContent("""{"type":"chart","props":{"stat":"fps","values":"1,2,3"}}""")).content.type,
        )
        assertEquals(
            ComponentType.CHART,
            parsed(withContent("""{"type":"chart","props":{"stat2":"cpu","values":"1,2,3"}}""")).content.type,
        )
        // ...and an id outside the vocabulary is still refused, the same guard a bad stat node gets.
        val r = rejection(withContent("""{"type":"chart","props":{"stat":"clipboard"}}"""))
        assertEquals(PluginDashboardCodec.Reason.BAD_STAT, r.reason)
        assertTrue(r.detail, r.detail.contains("clipboard"))
    }

    @Test
    fun `a live chart series contributes its scope to the widget appetite, so the registry can gate it`() {
        // The atomic other half of relaxing the codec: the series id MUST reach requiredStats /
        // requiredScopes, or PluginRegistryImpl.scopesGranted cannot drop a widget whose chart names a
        // feed the user never granted. stat -> fps (device_stats), stat2 -> temp (thermals).
        val m = parsed(withContent("""{"type":"chart","props":{"stat":"fps","stat2":"temp"}}"""))
        assertEquals(setOf(ModuleStat.FPS, ModuleStat.TEMP), m.requiredStats)
        assertEquals(setOf("device_stats", "thermals"), m.requiredScopes)
    }

    @Test
    fun `a chart live series and a stat node fold into one appetite, not one instead of the other`() {
        // A widget mixing a stat node and a live chart series asks for the union of both apertures,
        // proving the walk folds charts alongside stat nodes rather than replacing that fold.
        val content = """
            {"type":"column","children":[
              {"type":"stat","props":{"stat":"battery"}},
              {"type":"chart","props":{"stat":"cpu","values":"1,2,3"}}]}
        """.trimIndent()
        val m = parsed(withContent(content))
        assertEquals(setOf(ModuleStat.BATTERY, ModuleStat.CPU), m.requiredStats)
        assertEquals(setOf("thermals", "device_stats"), m.requiredScopes)
    }
    // ------------------------------------------------------------------------------ tree size

    @Test
    fun `a tree with more nodes than a widget may have is rejected`() {
        val leaves = (1..12).joinToString(",") { """{"type":"text"}""" }
        val rows = (1..12).joinToString(",") { """{"type":"row","children":[$leaves]}""" }
        assertEquals(
            PluginDashboardCodec.Reason.BAD_TREE,
            reason(withContent("""{"type":"column","children":[$rows]}""")),
        )
    }

    @Test
    fun `content that is not an object is rejected as a bad node`() {
        assertEquals(
            PluginDashboardCodec.Reason.BAD_NODE,
            reason(widget(""""id":"w","content":"a tile of text"""")),
        )
        assertEquals(
            PluginDashboardCodec.Reason.BAD_NODE,
            reason(withContent("""{"type":"webview"}""")),
        )
    }

    // ------------------------------------------------------------------------------ round trip

    @Test
    fun `a decoded widget round-trips through encode`() {
        val original = parsed(minimal)
        assertEquals(original, parsed(PluginDashboardCodec.encode(original)))
    }

    @Test
    fun `encode normalises, so the SDK cannot emit a file the loader would correct`() {
        val wild = PluginDashboardWidget(
            id = "battery.tile",
            title = "x".repeat(PluginDashboardWidget.MAX_TITLE_LENGTH + 20),
            size = WidgetSize.FULL,
            content = ComponentNode(ComponentType.STAT, mapOf(ModuleStat.PROP to PropValue.Str("battery"))),
        )
        val back = parsed(PluginDashboardCodec.encode(wild))
        assertEquals(wild.normalised(), back)
        assertTrue("len=${back.title.length}", back.title.length <= PluginDashboardWidget.MAX_TITLE_LENGTH)
        assertEquals(setOf("thermals"), back.requiredScopes)
    }
}
