package com.gamecore.core.plugin.overlay

import com.gamecore.core.plugin.ui.ComponentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The overlay gate's view of a chart: a declarative plot is allowed over a game, but only a
 * literal one.
 *
 * A chart is a leaf like a `stat` or a `text`, so most of what keeps it safe is the same audit
 * every node passes — it must be an allowed type and its strings are sanitised. The one rule that
 * is the chart's own is the reason this file exists: an overlay chart may carry its own numbers but
 * may not name a live host feed, because a module's data appetite is walked from its `stat` nodes
 * and a live chart series would ride behind no granted scope. That rejection is pinned here at both
 * series slots, next to the proof that a literal chart still parses.
 */
class PluginOverlayChartTest {

    private fun pill(body: String): String =
        """{"format":"gamecore.plugin.pill","version":1,$body}"""

    private fun withContent(content: String): String =
        pill(""""id":"m","title":"M","size":"small","content":$content""")

    private fun parsed(json: String): PluginOverlayModule {
        val r = PluginOverlayCodec.decode(json)
        assertTrue("expected Parsed, got $r", r is PluginOverlayCodec.Result.Parsed)
        return (r as PluginOverlayCodec.Result.Parsed).module
    }

    private fun rejection(json: String): PluginOverlayCodec.Result.Rejected {
        val r = PluginOverlayCodec.decode(json)
        assertTrue("expected Rejected, got $r", r is PluginOverlayCodec.Result.Rejected)
        return r as PluginOverlayCodec.Result.Rejected
    }

    // --------------------------------------------------------------------------------- happy path

    @Test
    fun `a literal chart is an allowed overlay node`() {
        val m = parsed(
            withContent("""{"type":"chart","props":{"kind":"line","values":"1,2,3,4"}}"""),
        )
        assertEquals(ComponentType.CHART, m.content.type)
        // A chart names no stat, so a literal-only module asks for no scopes and starts no sampler.
        assertTrue(m.requiredScopes.isEmpty())
    }

    @Test
    fun `the numeric values string survives sanitisation intact`() {
        val m = parsed(
            withContent("""{"type":"chart","props":{"kind":"bar","values":"10,20,30"}}"""),
        )
        val values = (m.content.props["values"] as com.gamecore.core.plugin.ui.PropValue.Str).value
        // The sanitiser strips control and bidi characters and collapses whitespace; digits and
        // the comma delimiter are none of those, so the series the author shipped is unchanged.
        assertEquals("10,20,30", values)
    }

    @Test
    fun `a chart carried inside a card is audited too`() {
        val m = parsed(
            withContent(
                """{"type":"card","children":[
                   {"type":"chart","props":{"values":"1,2,3"}}]}""",
            ),
        )
        assertEquals(ComponentType.CARD, m.content.type)
        assertEquals(ComponentType.CHART, m.content.children.first().type)
    }

    // --------------------------------------------------------------------------------- refusals

    @Test
    fun `a live primary series is refused on an overlay chart`() {
        val reason = rejection(
            withContent("""{"type":"chart","props":{"stat":"fps","values":"1,2,3"}}"""),
        ).reason
        assertEquals(PluginOverlayCodec.Reason.BAD_STAT, reason)
    }

    @Test
    fun `a live secondary series is refused on an overlay chart`() {
        val reason = rejection(
            withContent("""{"type":"chart","props":{"stat2":"cpu","values":"1,2,3"}}"""),
        ).reason
        assertEquals(PluginOverlayCodec.Reason.BAD_STAT, reason)
    }
}
