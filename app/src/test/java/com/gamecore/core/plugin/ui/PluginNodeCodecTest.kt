package com.gamecore.core.plugin.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Part F gate: `ComponentNode` ↔ JSON, proved on the JVM.
 *
 * The codec's promise is that a tree handed to [ScreenValidator] is one that could be built
 * safely, and that promise is only as good as the set of documents it refuses — so most of this
 * file is refusals. The rest pins the prop grammar, because the two string sigils are the only
 * ambiguous part of the format and the round trip is only exact if the encoder escapes exactly
 * the strings the decoder would otherwise re-read as something else.
 *
 * A real `org.json` is on the test classpath, so parsing here means what it means on the device.
 */
class PluginNodeCodecTest {

    // ------------------------------------------------------------------------------ helpers

    private fun ok(json: String): ComponentNode {
        val r = PluginNodeCodec.decodeNode(json)
        assertTrue("expected Ok, got $r", r is PluginNodeCodec.Result.Ok)
        return (r as PluginNodeCodec.Result.Ok).node
    }

    private fun rejection(json: String): String {
        val r = PluginNodeCodec.decodeNode(json)
        assertTrue("expected Rejected, got $r", r is PluginNodeCodec.Result.Rejected)
        return (r as PluginNodeCodec.Result.Rejected).reason
    }

    /** A one-prop text node, for pinning the prop grammar one value at a time. */
    private fun textProp(value: String): PropValue? =
        ok("""{"type":"text","props":{"v":$value}}""").props["v"]

    private fun nested(depth: Int): String {
        val sb = StringBuilder()
        repeat(depth) { sb.append("""{"type":"column","children":[""") }
        sb.append("""{"type":"text"}""")
        repeat(depth) { sb.append("]}") }
        return sb.toString()
    }

    // ------------------------------------------------------------------------------ happy path

    @Test
    fun `a well-formed tree decodes with its props and children`() {
        val node = ok(
            """
            {"type":"row","props":{"gap":6,"align":"center"},
             "children":[{"type":"text","props":{"text":"FPS"}},{"type":"stat","props":{"stat":"fps"}}]}
            """.trimIndent(),
        )
        assertEquals(ComponentType.ROW, node.type)
        assertEquals(PropValue.Num(6.0), node.props["gap"])
        assertEquals(PropValue.Str("center"), node.props["align"])
        assertEquals(2, node.children.size)
        assertEquals(ComponentType.TEXT, node.children[0].type)
        assertEquals(ComponentType.STAT, node.children[1].type)
    }

    @Test
    fun `props and children are optional and default to empty`() {
        val node = ok("""{"type":"spacer"}""")
        assertTrue(node.props.isEmpty())
        assertTrue(node.children.isEmpty())
    }

    // ------------------------------------------------------------------------------ the prop grammar

    @Test
    fun `booleans and numbers decode from their JSON type`() {
        assertEquals(PropValue.Bool(true), textProp("true"))
        assertEquals(PropValue.Bool(false), textProp("false"))
        assertEquals(PropValue.Num(11.0), textProp("11"))
        assertEquals(PropValue.Num(1.5), textProp("1.5"))
        assertEquals(PropValue.Num(-3.0), textProp("-3"))
    }

    @Test
    fun `a six-digit hex string is a colour, forced opaque`() {
        assertEquals(PropValue.Color(0xFFFF0000.toInt()), textProp("\"#FF0000\""))
    }

    @Test
    fun `an eight-digit hex string is a colour that keeps its alpha`() {
        assertEquals(PropValue.Color(0x99FFFFFF.toInt()), textProp("\"#99FFFFFF\""))
    }

    @Test
    fun `a hash string that is not valid hex stays text, it is not an error`() {
        // A label may legitimately start with a hash, and three-digit shorthand is not part of
        // the colour grammar — neither may become a rejection or a silently wrong colour.
        assertEquals(PropValue.Str("#1 player"), textProp("\"#1 player\""))
        assertEquals(PropValue.Str("#FFF"), textProp("\"#FFF\""))
        assertEquals(PropValue.Str("#notahex"), textProp("\"#notahex\""))
    }

    @Test
    fun `an at-prefixed string is an action reference`() {
        assertEquals(PropValue.ActionRef("set_peak_refresh_rate"), textProp("\"@set_peak_refresh_rate\""))
    }

    @Test
    fun `a doubled at escapes the sigil and the remainder is literal text`() {
        assertEquals(PropValue.Str("home"), textProp("\"@@home\""))
        assertEquals(PropValue.Str("@home"), textProp("\"@@@home\""))
        assertEquals(PropValue.Str(""), textProp("\"@@\""))
    }

    @Test
    fun `a doubled at also escapes a string shaped like a colour`() {
        // Without this, a label whose text happens to be "#FF0000" could not be expressed at
        // all, and the round trip below would be exact for most strings rather than for every one.
        assertEquals(PropValue.Str("#FF0000"), textProp("\"@@#FF0000\""))
    }

    @Test
    fun `a plain string is text`() {
        assertEquals(PropValue.Str("FPS"), textProp("\"FPS\""))
        assertEquals(PropValue.Str(""), textProp("\"\""))
    }

    // ------------------------------------------------------------------------------ round trip

    @Test
    fun `round trip is exact for every prop kind, including the escaped strings`() {
        val tree = ComponentNode(
            ComponentType.COLUMN,
            mapOf(
                "gap" to PropValue.Num(6.0),
                "fraction" to PropValue.Num(0.125),
                "dark" to PropValue.Bool(true),
                "opaque" to PropValue.Color(0xFFFF0000.toInt()),
                "translucent" to PropValue.Color(0x1234ABCD),
                "action" to PropValue.ActionRef("set_peak_refresh_rate"),
                "plain" to PropValue.Str("Frame rate"),
                "atLeading" to PropValue.Str("@home"),
                "atDoubled" to PropValue.Str("@@home"),
                "colourShaped" to PropValue.Str("#FF0000"),
                "hashNotHex" to PropValue.Str("#1 player"),
                "empty" to PropValue.Str(""),
            ),
            listOf(
                ComponentNode(ComponentType.TEXT, mapOf("text" to PropValue.Str("FPS"))),
                ComponentNode(
                    ComponentType.CARD,
                    emptyMap(),
                    listOf(ComponentNode(ComponentType.STAT, mapOf("stat" to PropValue.Str("fps")))),
                ),
            ),
        )

        val again = PluginNodeCodec.decodeNode(PluginNodeCodec.encodeNode(tree))
        assertEquals(PluginNodeCodec.Result.Ok(tree), again)
    }

    @Test
    fun `an empty prop bag and child list are omitted from the encoding`() {
        val json = PluginNodeCodec.encodeNode(ComponentNode(ComponentType.SPACER, emptyMap())).toString()
        assertEquals("""{"type":"spacer"}""", json)
    }

    // ------------------------------------------------------------------------------ refusals

    @Test
    fun `an unknown component type is rejected, never skipped`() {
        assertTrue(rejection("""{"type":"webview"}""").contains("webview"))
        assertTrue(rejection("""{"children":[]}""").isNotEmpty())
    }

    @Test
    fun `an unknown node key is rejected rather than ignored`() {
        assertTrue(rejection("""{"type":"text","style":{"css":"x"}}""").contains("style"))
    }

    @Test
    fun `props must be an object and children must be an array`() {
        assertTrue(rejection("""{"type":"text","props":[1,2]}""").contains("props"))
        assertTrue(rejection("""{"type":"column","children":{"a":1}}""").contains("children"))
    }

    @Test
    fun `a child that is not an object is rejected`() {
        assertTrue(rejection("""{"type":"column","children":["text"]}""").contains("child 0"))
    }

    @Test
    fun `a structured prop value is rejected, not coerced into a string`() {
        // PropValue exists to make "arbitrary object" unrepresentable; a codec that stringified
        // one would be reintroducing it by the back door.
        assertTrue(rejection("""{"type":"text","props":{"v":{"nested":1}}}""").contains("\"v\""))
        assertTrue(rejection("""{"type":"text","props":{"v":[1,2]}}""").contains("\"v\""))
    }

    @Test
    fun `a null prop is rejected`() {
        assertTrue(rejection("""{"type":"text","props":{"v":null}}""").contains("\"v\""))
    }

    @Test
    fun `a non-finite number is rejected`() {
        // 1e999 parses to an infinity, which would propagate into layout arithmetic as a size or
        // a gap and never come back.
        val reason = rejection("""{"type":"text","props":{"size":1e999}}""")
        assertTrue(reason, reason.contains("finite"))
    }

    @Test
    fun `a blank prop name is rejected`() {
        assertTrue(rejection("""{"type":"text","props":{"":1}}""").contains("blank"))
    }

    @Test
    fun `a prop name longer than the cap is rejected`() {
        val key = "k".repeat(PluginNodeCodec.MAX_PROP_KEY_LENGTH + 1)
        assertTrue(rejection("""{"type":"text","props":{"$key":1}}""").isNotEmpty())
    }

    // ------------------------------------------------------------------------------ parse ceilings

    @Test
    fun `a tree within the parse ceilings is accepted`() {
        // The ceilings sit above ScreenValidator's defaults on purpose, so a legal screen is
        // never refused here instead of there.
        assertEquals(ComponentType.COLUMN, ok(nested(20)).type)
    }

    @Test
    fun `a document deeper than the parse ceiling is rejected`() {
        val reason = rejection(nested(PluginNodeCodec.MAX_PARSE_DEPTH + 4))
        assertTrue(reason, reason.contains("deeper than ${PluginNodeCodec.MAX_PARSE_DEPTH}"))
    }

    @Test
    fun `a document with more nodes than the parse budget is rejected`() {
        // 601 nodes, but only 24 children at any one node and only two levels deep: the only
        // ceiling this can hit is the total budget, which is the one that bounds depth × breadth.
        val leaves = (1..24).joinToString(",") { """{"type":"text"}""" }
        val mid = (1..24).joinToString(",") { """{"type":"column","children":[$leaves]}""" }
        val reason = rejection("""{"type":"column","children":[$mid]}""")
        assertTrue(reason, reason.contains("${PluginNodeCodec.MAX_PARSE_NODES} nodes"))
    }

    @Test
    fun `a child list longer than the per-node ceiling is refused before it is read`() {
        val many = (1..PluginNodeCodec.MAX_CHILDREN_PER_NODE + 1).joinToString(",") { """{"type":"text"}""" }
        val reason = rejection("""{"type":"column","children":[$many]}""")
        assertTrue(reason, reason.contains("children"))
    }

    @Test
    fun `a prop bag larger than the per-node ceiling is rejected`() {
        val many = (1..PluginNodeCodec.MAX_PROPS_PER_NODE + 1).joinToString(",") { """"k$it":1""" }
        val reason = rejection("""{"type":"text","props":{$many}}""")
        assertTrue(reason, reason.contains("props"))
    }

    // ------------------------------------------------------------------------------ never throws

    @Test
    fun `malformed and empty text are rejected, never thrown`() {
        assertTrue(PluginNodeCodec.decodeNode("{not json") is PluginNodeCodec.Result.Rejected)
        assertTrue(PluginNodeCodec.decodeNode("[]") is PluginNodeCodec.Result.Rejected)
        assertTrue(PluginNodeCodec.decodeNode("") is PluginNodeCodec.Result.Rejected)
        assertTrue(PluginNodeCodec.decodeNode(null as String?) is PluginNodeCodec.Result.Rejected)
    }

    @Test
    fun `there is no component type for a forbidden surface`() {
        assertNull(ComponentType.fromId("webview"))
        assertNull(ComponentType.fromId("rawview"))
        assertNull(ComponentType.fromId("iframe"))
    }
}
