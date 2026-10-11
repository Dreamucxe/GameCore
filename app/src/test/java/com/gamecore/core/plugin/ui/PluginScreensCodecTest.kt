package com.gamecore.core.plugin.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trust boundary for a plugin's declarative screens. Almost every test here is a refusal,
 * because almost all a codec is, is the set of inputs it will not pass on: the envelope and each
 * screen are a closed key set, an unknown key at any depth refuses the whole file, and a bad value
 * refuses the whole section. [PluginScreensCodec.decode] must never throw — a [org.json.JSONException]
 * anywhere becomes a typed [PluginScreensCodec.Reason.MALFORMED].
 *
 * The two deliberate departures from the overlay codec are pinned here as their own cases: the full
 * component set (BUTTON/SLIDER/SWITCH/SCROLL) is accepted because a screen is a touchable surface the
 * renderer already draws inert, and an unknown `icon` resolves to null rather than rejecting because an
 * icon is pure chrome. Everything load-bearing still rejects.
 */
class PluginScreensCodecTest {

    // ------------------------------------------------------------------------------ helpers

    private fun file(screens: String): String =
        """{"format":"gamecore.plugin.screens","version":1,"screens":[$screens]}"""

    private fun screen(body: String): String = "{$body}"

    private val textNode = """{"type":"text","props":{"text":"Hello"}}"""

    private fun parsed(json: String): List<PluginScreen> {
        val r = PluginScreensCodec.decode(json)
        assertTrue("expected Parsed, got $r", r is PluginScreensCodec.Result.Parsed)
        return (r as PluginScreensCodec.Result.Parsed).screens
    }

    private fun rejection(json: String): PluginScreensCodec.Result.Rejected {
        val r = PluginScreensCodec.decode(json)
        assertTrue("expected Rejected, got $r", r is PluginScreensCodec.Result.Rejected)
        return r as PluginScreensCodec.Result.Rejected
    }

    private fun reason(json: String): PluginScreensCodec.Reason = rejection(json).reason

    // ------------------------------------------------------------------------------ the happy path

    @Test
    fun `a minimal screen parses with its fields`() {
        val screens = parsed(
            file(screen(""""id":"overview","title":"Overview","icon":"insights","content":$textNode""")),
        )
        assertEquals(1, screens.size)
        val s = screens.single()
        assertEquals("overview", s.id)
        assertEquals("Overview", s.title)
        assertEquals("insights", s.icon)
        assertEquals(ComponentType.TEXT, s.root.type)
    }

    @Test
    fun `many screens in one file all parse`() {
        val screens = parsed(
            file(
                (1..3).joinToString(",") { screen(""""id":"s$it","title":"S$it","content":$textNode""") },
            ),
        )
        assertEquals(listOf("s1", "s2", "s3"), screens.map { it.id })
    }

    // ------------------------------------------------------------------------------ never throws

    @Test
    fun `null or blank is EMPTY, never a throw`() {
        assertEquals(PluginScreensCodec.Reason.EMPTY, reasonOf(PluginScreensCodec.decode(null)))
        assertEquals(PluginScreensCodec.Reason.EMPTY, reason(""))
        assertEquals(PluginScreensCodec.Reason.EMPTY, reason("   "))
    }

    @Test
    fun `garbage is MALFORMED, never a throw`() {
        assertEquals(PluginScreensCodec.Reason.MALFORMED, reason("not json at all"))
        assertEquals(PluginScreensCodec.Reason.MALFORMED, reason("{"))
    }

    private fun reasonOf(r: PluginScreensCodec.Result): PluginScreensCodec.Reason =
        (r as PluginScreensCodec.Result.Rejected).reason

    // ------------------------------------------------------------------------------ the envelope

    @Test
    fun `wrong format is rejected`() {
        assertEquals(
            PluginScreensCodec.Reason.WRONG_FORMAT,
            reason("""{"format":"gamecore.plugin.rules","version":1,"screens":[]}"""),
        )
    }

    @Test
    fun `version is read before the key set so a future file reports a version mismatch`() {
        // An unknown key in a file that also declares a future version is a BAD_VERSION, not UNKNOWN_KEY.
        assertEquals(
            PluginScreensCodec.Reason.BAD_VERSION,
            reason("""{"format":"gamecore.plugin.screens","version":2,"whatsThis":1,"screens":[]}"""),
        )
    }

    @Test
    fun `missing version is MISSING_FIELD`() {
        assertEquals(
            PluginScreensCodec.Reason.MISSING_FIELD,
            reason("""{"format":"gamecore.plugin.screens","screens":[]}"""),
        )
    }

    @Test
    fun `an unknown envelope key is rejected`() {
        assertEquals(
            PluginScreensCodec.Reason.UNKNOWN_KEY,
            reason("""{"format":"gamecore.plugin.screens","version":1,"extra":1,"screens":[]}"""),
        )
    }

    @Test
    fun `missing screens array is MISSING_FIELD`() {
        assertEquals(
            PluginScreensCodec.Reason.MISSING_FIELD,
            reason("""{"format":"gamecore.plugin.screens","version":1}"""),
        )
    }

    @Test
    fun `screens that is not an array is MALFORMED`() {
        assertEquals(
            PluginScreensCodec.Reason.MALFORMED,
            reason("""{"format":"gamecore.plugin.screens","version":1,"screens":{}}"""),
        )
    }

    @Test
    fun `an empty screens array parses to an empty list`() {
        assertEquals(emptyList<PluginScreen>(), parsed(file("")))
    }

    @Test
    fun `more than the cap of screens is TOO_MANY`() {
        val tooMany = (1..33).joinToString(",") { screen(""""id":"s$it","title":"S$it","content":$textNode""") }
        assertEquals(PluginScreensCodec.Reason.TOO_MANY, reason(file(tooMany)))
    }

    // PLACEHOLDER_TESTS2

    // ------------------------------------------------------------------------------ one screen

    @Test
    fun `a screen that is not an object is MALFORMED`() {
        assertEquals(
            PluginScreensCodec.Reason.MALFORMED,
            reason("""{"format":"gamecore.plugin.screens","version":1,"screens":[5]}"""),
        )
    }

    @Test
    fun `an unknown screen key is rejected`() {
        assertEquals(
            PluginScreensCodec.Reason.UNKNOWN_KEY,
            reason(file(screen(""""id":"a","title":"A","content":$textNode,"extra":1"""))),
        )
    }

    @Test
    fun `a screen missing id or content is MISSING_FIELD`() {
        assertEquals(
            PluginScreensCodec.Reason.MISSING_FIELD,
            reason(file(screen(""""title":"A","content":$textNode"""))),
        )
        assertEquals(
            PluginScreensCodec.Reason.MISSING_FIELD,
            reason(file(screen(""""id":"a","title":"A""""))),
        )
    }

    @Test
    fun `a screen id must match the slug grammar`() {
        assertEquals(
            PluginScreensCodec.Reason.BAD_ID,
            reason(file(screen(""""id":"has space","title":"A","content":$textNode"""))),
        )
    }

    @Test
    fun `a screen id is trimmed and lower-cased`() {
        val s = parsed(file(screen(""""id":"  Overview  ","title":"A","content":$textNode"""))).single()
        assertEquals("overview", s.id)
    }

    @Test
    fun `two screens with the same id are rejected`() {
        val dup = listOf(
            screen(""""id":"home","title":"One","content":$textNode"""),
            screen(""""id":"home","title":"Two","content":$textNode"""),
        ).joinToString(",")
        assertEquals(PluginScreensCodec.Reason.DUPLICATE_ID, reason(file(dup)))
    }

    @Test
    fun `a blank title falls back to the id`() {
        val s = parsed(file(screen(""""id":"home","title":"   ","content":$textNode"""))).single()
        assertEquals("home", s.title)
    }

    @Test
    fun `a missing title falls back to the id`() {
        val s = parsed(file(screen(""""id":"home","content":$textNode"""))).single()
        assertEquals("home", s.title)
    }

    @Test
    fun `content that is not an object is BAD_NODE`() {
        assertEquals(
            PluginScreensCodec.Reason.BAD_NODE,
            reason(file(screen(""""id":"home","title":"A","content":5"""))),
        )
    }

    @Test
    fun `an unknown node type is BAD_NODE`() {
        assertEquals(
            PluginScreensCodec.Reason.BAD_NODE,
            reason(file(screen(""""id":"home","title":"A","content":{"type":"webview"}"""))),
        )
    }

    @Test
    fun `a tree that fails the validator is BAD_TREE`() {
        val grid = """{"type":"grid","props":{"columns":99},"children":[$textNode]}"""
        assertEquals(
            PluginScreensCodec.Reason.BAD_TREE,
            reason(file(screen(""""id":"home","title":"A","content":$grid"""))),
        )
    }

    // ------------------------------------------------------- the two departures from the overlay codec

    @Test
    fun `an unknown icon resolves to null rather than rejecting the file`() {
        val s = parsed(file(screen(""""id":"home","title":"A","icon":"nonsense","content":$textNode"""))).single()
        assertNull(s.icon)
    }

    @Test
    fun `the full component set is accepted on a screen`() {
        // BUTTON, SLIDER, SWITCH and SCROLL — all refused by the overlay codec — are legal here.
        val tree = """
            {"type":"scroll","props":{"gap":8},"children":[
              {"type":"button","props":{"text":"Go"}},
              {"type":"slider","props":{"min":0,"max":100,"value":50}},
              {"type":"switch","props":{"value":true}}
            ]}
        """.trimIndent()
        val s = parsed(file(screen(""""id":"home","title":"A","content":$tree"""))).single()
        assertEquals(ComponentType.SCROLL, s.root.type)
        assertEquals(3, s.root.children.size)
    }

    // ------------------------------------------------------------------------------ round trip

    @Test
    fun `decode of encode returns an equal list`() {
        val original = parsed(
            file(
                listOf(
                    screen(""""id":"a","title":"A","icon":"speed","content":$textNode"""),
                    screen(""""id":"b","title":"B","content":$textNode"""),
                ).joinToString(","),
            ),
        )
        val round = parsed(PluginScreensCodec.encode(original))
        assertEquals(original, round)
    }

    @Test
    fun `a screen with no icon round-trips to no icon, not an empty string`() {
        val original = parsed(file(screen(""""id":"a","title":"A","content":$textNode"""))).single()
        val round = parsed(PluginScreensCodec.encode(listOf(original))).single()
        assertNull(round.icon)
    }
}
