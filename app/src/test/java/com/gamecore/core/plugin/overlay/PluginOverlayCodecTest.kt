package com.gamecore.core.plugin.overlay

import com.gamecore.core.plugin.DataScope
import com.gamecore.core.plugin.ui.ComponentNode
import com.gamecore.core.plugin.ui.ComponentType
import com.gamecore.core.plugin.ui.PluginScreen
import com.gamecore.core.plugin.ui.PropValue
import com.gamecore.core.plugin.ui.ScreenValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate on the only thing a plugin can draw over a running game.
 *
 * Almost all of this file is refusals, because that is almost all the codec is. A module is
 * rendered in a window that takes no touches, from data the user has granted a specific aperture
 * for, in a box of a fixed size — and every one of those three properties is a thing this codec
 * enforces rather than something the renderer or the service can be trusted to re-check. So each
 * bound in the contract gets its own test with its own reason, and the clamped fields get tests
 * at both ends, because "clamped" silently becoming "rejected" would break working plugins and
 * "rejected" silently becoming "clamped" would let one through.
 *
 * The model's own invariants are asserted here too: that every [ModuleStat] names a real
 * `DataScope`, that [PluginOverlayModule.requiredScopes] is derived from the tree rather than
 * declared beside it, and that [PluginOverlayModule.validator]'s caps are the tight ones.
 */
class PluginOverlayCodecTest {

    // ------------------------------------------------------------------------------ helpers

    private fun pill(body: String): String =
        """{"format":"gamecore.plugin.pill","version":1,$body}"""

    /** The contract's own worked example, verbatim in shape. */
    private val minimal = pill(
        """
        "id":"fps-badge","title":"Frame rate",
        "anchor":{"x":0.5,"y":0.08},"size":"small","opacity":85,
        "content":{"type":"row","props":{"gap":6,"align":"center"},"children":[
          {"type":"text","props":{"text":"FPS","color":"#99FFFFFF","size":11}},
          {"type":"stat","props":{"stat":"fps","size":14}}]}
        """.trimIndent(),
    )

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

    private fun reason(json: String): PluginOverlayCodec.Reason = rejection(json).reason

    /** A module whose content is the single node given, for one-node content tests. */
    private fun withContent(content: String): String = pill(
        """"id":"m","title":"M","size":"small","content":$content""",
    )

    private fun node(type: ComponentType, vararg props: Pair<String, PropValue>) =
        ComponentNode(type, props.toMap())

    // ------------------------------------------------------------------------------ happy path

    @Test
    fun `the contract's worked example parses`() {
        val m = parsed(minimal)
        assertEquals("fps-badge", m.id)
        assertEquals("Frame rate", m.title)
        assertEquals(ModuleSize.SMALL, m.size)
        assertEquals(85, m.opacityPercent)
        assertEquals(0.5f, m.anchor.xFraction, 0.0001f)
        assertEquals(0.08f, m.anchor.yFraction, 0.0001f)
        assertEquals(ComponentType.ROW, m.content.type)
        assertEquals(2, m.content.children.size)
        assertEquals(setOf("device_stats"), m.requiredScopes)
    }

    /**
     * The three defaults a file gets for saying nothing, each of which has to be the least
     * intrusive option rather than the most useful one.
     *
     * The fixture is spelled out here rather than taken from [withContent], which supplies
     * `"size":"small"` itself — so this test used to assert the size default by reading back a
     * value it had provided, and would have passed just as happily if an absent size had meant
     * `LARGE`. A test for a default has to omit the key.
     */
    @Test
    fun `an absent size, opacity and anchor default to the least intrusive values`() {
        val m = parsed(pill(""""id":"m","content":{"type":"spacer"}"""))
        assertEquals(ModuleSize.SMALL, m.size)
        assertEquals(PluginOverlayModule.MAX_OPACITY_PERCENT, m.opacityPercent)
        assertEquals(ModuleAnchor.TOP_CENTER, m.anchor)
    }

    @Test
    fun `a title that sanitises away falls back to the id so the user can still find it`() {
        val m = parsed(pill(""""id":"m","title":"   ","content":{"type":"spacer"}"""))
        assertEquals("m", m.title)
    }

    // ------------------------------------------------------------------------------ envelope guards

    @Test
    fun `a file that is not ours is rejected`() {
        val bad = minimal.replace("gamecore.plugin.pill", "gamecore.plugin.theme")
        assertEquals(PluginOverlayCodec.Reason.WRONG_FORMAT, reason(bad))
    }

    @Test
    fun `a missing or future version is rejected rather than partially honoured`() {
        val missing = """{"format":"gamecore.plugin.pill","id":"m","content":{"type":"spacer"}}"""
        assertEquals(PluginOverlayCodec.Reason.MISSING_FIELD, reason(missing))
        val future = minimal.replace("\"version\":1", "\"version\":2")
        assertEquals(PluginOverlayCodec.Reason.BAD_VERSION, reason(future))
    }

    @Test
    fun `an unknown key is rejected, not ignored`() {
        val bad = minimal.replaceFirst("\"id\"", "\"touchable\":true,\"id\"")
        assertEquals(PluginOverlayCodec.Reason.UNKNOWN_KEY, reason(bad))
    }

    @Test
    fun `an unknown anchor key is rejected, because the author thinks it positioned something`() {
        val bad = minimal.replace("\"anchor\":{\"x\":0.5", "\"anchor\":{\"z\":1,\"x\":0.5")
        assertEquals(PluginOverlayCodec.Reason.UNKNOWN_KEY, reason(bad))
    }

    @Test
    fun `a missing id or content is rejected`() {
        assertEquals(
            PluginOverlayCodec.Reason.MISSING_FIELD,
            reason(pill(""""id":"m","title":"M"""")),
        )
        assertEquals(
            PluginOverlayCodec.Reason.MISSING_FIELD,
            reason(pill(""""title":"M","content":{"type":"spacer"}""")),
        )
    }

    @Test
    fun `a module id that is not a token is rejected, not cleaned`() {
        assertEquals(
            PluginOverlayCodec.Reason.BAD_ID,
            reason(pill(""""id":"fps badge!","content":{"type":"spacer"}""")),
        )
        assertEquals(
            PluginOverlayCodec.Reason.BAD_ID,
            reason(pill(""""id":"","content":{"type":"spacer"}""")),
        )
        assertEquals(
            PluginOverlayCodec.Reason.BAD_ID,
            reason(pill(""""id":"-leading","content":{"type":"spacer"}""")),
        )
    }

    @Test
    fun `empty and malformed input are rejected, never thrown`() {
        assertEquals(PluginOverlayCodec.Reason.EMPTY, reason(""))
        assertTrue(PluginOverlayCodec.decode(null) is PluginOverlayCodec.Result.Rejected)
        assertEquals(PluginOverlayCodec.Reason.MALFORMED, reason("{not json"))
    }

    // ------------------------------------------------------------------------------ the size enum

    @Test
    fun `size is a closed enum, so a module cannot grow into a full-screen surface`() {
        assertEquals(
            PluginOverlayCodec.Reason.BAD_SIZE,
            reason(pill(""""id":"m","size":"fullscreen","content":{"type":"spacer"}""")),
        )
        assertEquals(
            PluginOverlayCodec.Reason.BAD_SIZE,
            reason(pill(""""id":"m","size":"480x900","content":{"type":"spacer"}""")),
        )
        assertNull(ModuleSize.fromId("fullscreen"))
        assertEquals(ModuleSize.MEDIUM, ModuleSize.fromId(" Medium "))
        assertEquals(240, ModuleSize.LARGE.widthDp)
        assertEquals(112, ModuleSize.LARGE.heightDp)
    }

    // ------------------------------------------------------------------------------ no touch surface

    @Test
    fun `every interactive node type is rejected outright`() {
        listOf("button", "slider", "switch", "scroll").forEach { type ->
            val r = rejection(withContent("""{"type":"$type"}"""))
            assertEquals(type, PluginOverlayCodec.Reason.INTERACTIVE_NODE, r.reason)
            assertTrue(r.detail, r.detail.contains(type))
        }
    }

    @Test
    fun `an interactive node is rejected however deeply it is buried`() {
        val content = """
            {"type":"column","children":[{"type":"card","children":[
              {"type":"row","children":[{"type":"button","props":{"label":"Go"}}]}]}]}
        """.trimIndent()
        assertEquals(PluginOverlayCodec.Reason.INTERACTIVE_NODE, reason(withContent(content)))
    }

    @Test
    fun `the allowed set is the eight non-interactive types`() {
        assertEquals(8, PluginOverlayModule.ALLOWED_TYPES.size)
        listOf(
            ComponentType.BUTTON, ComponentType.SLIDER, ComponentType.SWITCH, ComponentType.SCROLL,
        ).forEach { assertFalse(it.id, it in PluginOverlayModule.ALLOWED_TYPES) }
        listOf(
            ComponentType.COLUMN, ComponentType.ROW, ComponentType.GRID, ComponentType.CARD,
            ComponentType.TEXT, ComponentType.STAT, ComponentType.SPACER, ComponentType.IMAGE,
        ).forEach { assertTrue(it.id, it in PluginOverlayModule.ALLOWED_TYPES) }
    }

    @Test
    fun `an action reference is rejected, because a module could never dispatch one`() {
        val r = rejection(withContent("""{"type":"text","props":{"on_tap":"@set_peak_refresh_rate"}}"""))
        assertEquals(PluginOverlayCodec.Reason.ACTION_REF, r.reason)
        assertTrue(r.detail, r.detail.contains("set_peak_refresh_rate"))
    }

    @Test
    fun `an escaped literal that merely looks like an action is fine`() {
        // "@@..." is text, so a label about an @handle is not mistaken for a dispatch attempt.
        val m = parsed(withContent("""{"type":"text","props":{"text":"@@everyone"}}"""))
        assertEquals(PropValue.Str("everyone"), m.content.props["text"])
    }

    // ------------------------------------------------------------------------------ stats and scopes

    @Test
    fun `an unknown stat id is rejected`() {
        val r = rejection(withContent("""{"type":"stat","props":{"stat":"clipboard"}}"""))
        assertEquals(PluginOverlayCodec.Reason.BAD_STAT, r.reason)
        assertTrue(r.detail, r.detail.contains("clipboard"))
    }

    @Test
    fun `a stat node with no stat prop is rejected`() {
        assertEquals(
            PluginOverlayCodec.Reason.BAD_STAT,
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
    fun `a module with no stats needs no scopes`() {
        val m = parsed(withContent("""{"type":"text","props":{"text":"Hello"}}"""))
        assertTrue(m.requiredScopes.isEmpty())
    }

    @Test
    fun `every stat names a real data scope`() {
        // The scope strings are what the service checks a grant against; if DataScope were
        // renamed and these were not, a module would ask for an aperture that does not exist and
        // the check would pass on a string nobody can grant.
        ModuleStat.entries.forEach {
            assertNotNull("${it.id} -> ${it.scope}", DataScope.fromId(it.scope))
        }
        assertEquals(DataScope.DEVICE_STATS, DataScope.fromId(ModuleStat.FPS.scope))
        assertEquals(DataScope.THERMALS, DataScope.fromId(ModuleStat.TEMP.scope))
        assertEquals(DataScope.THERMALS, DataScope.fromId(ModuleStat.BATTERY.scope))
    }

    @Test
    fun `the stat vocabulary is closed`() {
        assertNull(ModuleStat.fromId("screen_text"))
        assertNull(ModuleStat.fromId(""))
        assertEquals(ModuleStat.FPS, ModuleStat.fromId(" FPS "))
        assertEquals(6, ModuleStat.entries.size)
    }

    // ------------------------------------------------------------------------------ clamping

    @Test
    fun `an off-screen anchor is clamped at both ends, not rejected`() {
        val low = parsed(pill(""""id":"m","anchor":{"x":-5,"y":-0.2},"content":{"type":"spacer"}"""))
        assertEquals(0f, low.anchor.xFraction, 0.0001f)
        assertEquals(0f, low.anchor.yFraction, 0.0001f)

        val high = parsed(pill(""""id":"m","anchor":{"x":9,"y":1.2},"content":{"type":"spacer"}"""))
        assertEquals(1f, high.anchor.xFraction, 0.0001f)
        assertEquals(1f, high.anchor.yFraction, 0.0001f)
    }

    /**
     * The other half of the anchor contract, and the reason it has a reason of its own.
     *
     * An anchor's *values* are clamped, because the author's intent survives the repair — they
     * wanted the module at that edge. Its *shape* cannot be repaired: `[0.5, 0.08]` carries no
     * `x` and no `y` to read, so the module would silently land at the default position, which
     * is nowhere near where the file says it goes.
     *
     * The reason matters as much as the rejection. This used to report `MALFORMED` — the same
     * reason as `"{not json"` — so a file that is valid JSON with one wrong-shaped field was
     * told it was not JSON at all, and its author sent to look at the one thing that was fine.
     */
    @Test
    fun `an anchor that is not an object is rejected as an anchor, not as broken json`() {
        listOf("[0.5,0.08]", "\"top-center\"", "0.5", "null").forEach { value ->
            assertEquals(
                "an anchor of $value should be refused as an anchor",
                PluginOverlayCodec.Reason.BAD_ANCHOR,
                reason(pill(""""anchor":$value,"id":"m","content":{"type":"spacer"}""")),
            )
        }
    }

    @Test
    fun `opacity is clamped at both ends and never to invisible`() {
        val low = parsed(pill(""""id":"m","opacity":0,"content":{"type":"spacer"}"""))
        assertEquals(PluginOverlayModule.MIN_OPACITY_PERCENT, low.opacityPercent)

        val high = parsed(pill(""""id":"m","opacity":500,"content":{"type":"spacer"}"""))
        assertEquals(PluginOverlayModule.MAX_OPACITY_PERCENT, high.opacityPercent)

        assertEquals(20, PluginOverlayModule.MIN_OPACITY_PERCENT)
        assertEquals(100, PluginOverlayModule.MAX_OPACITY_PERCENT)
    }

    @Test
    fun `a non-finite anchor falls back to the default position`() {
        // NaN cannot be clamped — every comparison against it is false — and it would reach the
        // window manager as a NaN coordinate.
        val anchor = ModuleAnchor(Float.NaN, Float.NEGATIVE_INFINITY).clamped()
        assertEquals(ModuleAnchor.DEFAULT_X, anchor.xFraction, 0.0001f)
        assertEquals(ModuleAnchor.DEFAULT_Y, anchor.yFraction, 0.0001f)
    }

    // ------------------------------------------------------------------------------ text

    @Test
    fun `the title and every string prop are sanitised`() {
        // ‮ is a right-to-left override: in an overlay it reverses the game's own text too.
        val m = parsed(
            pill(
                """
                "id":"m","title":"Frame ‮rate",
                "content":{"type":"text","props":{"text":"F‮PS","color":"#FFFFFF"}}
                """.trimIndent(),
            ),
        )
        assertEquals("Frame rate", m.title)
        assertEquals(PropValue.Str("FPS"), m.content.props["text"])
        // Non-string props are untouched by sanitising.
        assertEquals(PropValue.Color(0xFFFFFFFF.toInt()), m.content.props["color"])
    }

    @Test
    fun `an over-long string prop is truncated rather than rejected`() {
        val long = "x".repeat(400)
        val m = parsed(withContent("""{"type":"text","props":{"text":"$long"}}"""))
        val text = (m.content.props["text"] as PropValue.Str).value
        assertTrue("len=${text.length}", text.length <= PluginOverlayModule.MAX_STRING_LENGTH)
    }

    // ------------------------------------------------------------------------------ tree size

    @Test
    fun `a tree with more nodes than a module may have is rejected`() {
        val leaves = (1..12).joinToString(",") { """{"type":"text"}""" }
        val rows = (1..12).joinToString(",") { """{"type":"row","children":[$leaves]}""" }
        assertEquals(
            PluginOverlayCodec.Reason.BAD_TREE,
            reason(withContent("""{"type":"column","children":[$rows]}""")),
        )
    }

    @Test
    fun `a tree deeper than a module may be is rejected`() {
        var content = """{"type":"text"}"""
        repeat(PluginOverlayModule.MAX_DEPTH + 2) { content = """{"type":"column","children":[$content]}""" }
        assertEquals(PluginOverlayCodec.Reason.BAD_TREE, reason(withContent(content)))
    }

    @Test
    fun `a node with more children than a module may have is rejected`() {
        val many = (1..PluginOverlayModule.MAX_CHILDREN + 1).joinToString(",") { """{"type":"text"}""" }
        assertEquals(
            PluginOverlayCodec.Reason.BAD_TREE,
            reason(withContent("""{"type":"row","children":[$many]}""")),
        )
    }

    @Test
    fun `a grid wider than a module may be is rejected`() {
        assertEquals(
            PluginOverlayCodec.Reason.BAD_TREE,
            reason(withContent("""{"type":"grid","props":{"columns":9}}""")),
        )
    }

    @Test
    fun `a leaf with children is rejected by the one validator that owns tree shape`() {
        assertEquals(
            PluginOverlayCodec.Reason.BAD_TREE,
            reason(withContent("""{"type":"text","children":[{"type":"text"}]}""")),
        )
    }

    @Test
    fun `content that is not an object is rejected as a bad node`() {
        assertEquals(
            PluginOverlayCodec.Reason.BAD_NODE,
            reason(pill(""""id":"m","content":"a row of text"""")),
        )
        assertEquals(
            PluginOverlayCodec.Reason.BAD_NODE,
            reason(withContent("""{"type":"webview"}""")),
        )
    }

    // ------------------------------------------------------------------------------ the model

    @Test
    fun `the validator is the existing one, parameterised down to a badge`() {
        val v = PluginOverlayModule.validator()
        fun verdict(root: ComponentNode) = v.validate(PluginScreen("m", "M", root))

        val fine = ComponentNode(
            ComponentType.ROW, emptyMap(),
            listOf(node(ComponentType.STAT, ModuleStat.PROP to PropValue.Str("fps"))),
        )
        assertTrue(verdict(fine) is ScreenValidator.Result.Valid)

        val tooManyChildren = ComponentNode(
            ComponentType.ROW, emptyMap(),
            (1..PluginOverlayModule.MAX_CHILDREN + 1).map { node(ComponentType.TEXT) },
        )
        assertTrue(verdict(tooManyChildren) is ScreenValidator.Result.Invalid)

        val tooWide = node(ComponentType.GRID, "columns" to PropValue.Num(5.0))
        assertTrue(verdict(tooWide) is ScreenValidator.Result.Invalid)

        val tooLong = node(
            ComponentType.TEXT,
            "text" to PropValue.Str("x".repeat(PluginOverlayModule.MAX_STRING_LENGTH + 1)),
        )
        assertTrue(verdict(tooLong) is ScreenValidator.Result.Invalid)
    }

    @Test
    fun `at most three modules may be on screen at once`() {
        assertEquals(3, PluginOverlayModule.MAX_MODULES_ON_SCREEN)
    }

    // ------------------------------------------------------------------------------ round trip

    @Test
    fun `a decoded module round-trips through encode`() {
        val original = parsed(minimal)
        assertEquals(original, parsed(PluginOverlayCodec.encode(original)))
    }

    @Test
    fun `encode normalises, so the SDK cannot emit a file the loader would correct`() {
        val wild = PluginOverlayModule(
            id = "battery.badge",
            title = "Battery",
            anchor = ModuleAnchor(5f, -5f),
            size = ModuleSize.LARGE,
            opacityPercent = 500,
            content = node(ComponentType.STAT, ModuleStat.PROP to PropValue.Str("battery")),
        )
        val back = parsed(PluginOverlayCodec.encode(wild))
        assertEquals(wild.normalised(), back)
        assertEquals(1f, back.anchor.xFraction, 0.0001f)
        assertEquals(0f, back.anchor.yFraction, 0.0001f)
        assertEquals(PluginOverlayModule.MAX_OPACITY_PERCENT, back.opacityPercent)
        assertEquals(setOf("thermals"), back.requiredScopes)
    }
}
