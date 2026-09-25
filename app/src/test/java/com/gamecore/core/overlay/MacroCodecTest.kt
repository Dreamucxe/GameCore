package com.gamecore.core.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MacroCodec]'s round trip and its self-heal (§14), asserted the way the Aim Lab `ConfigCodecTest` and
 * [com.gamecore.core.export.SampleExport]'s test assert theirs: on what a real `org.json` actually parses,
 * not on a paraphrase of it.
 *
 * Where the config codec is a *foreign*-file importer that rejects a bad document with a typed reason
 * (§20/§36), this is the *internal* store codec, so its contract is the opposite one: [MacroCodec.decode]
 * is total. A blank key, a truncated write, an object where an array was expected, an action name a newer
 * build wrote or an older one has since dropped — each degrades to *fewer macros*, never an exception, so a
 * corrupt macro key lets the overlay open empty rather than not open at all. Every case below feeds it a
 * string a hand-edit or a version skew could plausibly leave behind and asserts a normalised list back.
 *
 * These tests are only meaningful because a real `org.json` is on the unit-test classpath — the stub inside
 * `android.jar` returns defaults under `isReturnDefaultValues`, which would let the round trip and the
 * dropping below pass without a single character being parsed.
 */
class MacroCodecTest {

    // --- Round trip -------------------------------------------------------------------------------

    @Test
    fun `a macro list survives encode and decode with names and action order intact`() {
        val macros = listOf(
            Macro(1L, "Warm Up", listOf(OverlayAction.PILL, OverlayAction.CROSSHAIR, OverlayAction.HUD)),
            Macro(2L, "Go Dark", listOf(OverlayAction.DO_NOT_DISTURB, OverlayAction.FLASHLIGHT)),
        )
        val decoded = MacroCodec.decode(MacroCodec.encode(macros))
        assertEquals(2, decoded.size)
        assertEquals("Warm Up", decoded[0].name)
        assertEquals(
            listOf(OverlayAction.PILL, OverlayAction.CROSSHAIR, OverlayAction.HUD),
            decoded[0].actions,
        )
        assertEquals("Go Dark", decoded[1].name)
        assertEquals(listOf(OverlayAction.DO_NOT_DISTURB, OverlayAction.FLASHLIGHT), decoded[1].actions)
    }

    @Test
    fun `an empty list round trips through an empty array`() {
        assertEquals("[]", MacroCodec.encode(emptyList()))
        assertTrue(MacroCodec.decode(MacroCodec.encode(emptyList())).isEmpty())
    }

    // --- Dropping unknown and barred actions ------------------------------------------------------

    @Test
    fun `an action name this build does not know is dropped and the rest kept in order`() {
        val decoded = MacroCodec.decode(
            """[{"id":1,"name":"Mix","actions":["PILL","TELEPORT","HUD"]}]""",
        )
        assertEquals(1, decoded.size)
        assertEquals(listOf(OverlayAction.PILL, OverlayAction.HUD), decoded[0].actions)
    }

    @Test
    fun `a valid but non-macroable action is dropped and the macroable ones kept`() {
        val decoded = MacroCodec.decode(
            """[{"id":1,"name":"Mix","actions":["COLOR","PILL","OPEN_APP","HUD"]}]""",
        )
        assertEquals(1, decoded.size)
        assertEquals(listOf(OverlayAction.PILL, OverlayAction.HUD), decoded[0].actions)
    }

    // --- decode is total --------------------------------------------------------------------------

    @Test
    fun `decode never throws and yields nothing for anything it cannot parse`() {
        assertTrue(MacroCodec.decode(null).isEmpty())
        assertTrue(MacroCodec.decode("").isEmpty())
        assertTrue(MacroCodec.decode("   ").isEmpty())
        assertTrue(MacroCodec.decode("not json at all").isEmpty())
        assertTrue(MacroCodec.decode("""{"id":1,"name":"A"}""").isEmpty()) // an object, not an array
        assertTrue(MacroCodec.decode("[1,2,3]").isEmpty())                 // an array of the wrong shape
        assertTrue(MacroCodec.decode("[{},{}]").isEmpty())                 // objects missing every key
    }

    // --- id reassignment --------------------------------------------------------------------------

    @Test
    fun `decode assigns sequential ids so shared or missing ids do not collapse two macros`() {
        // decode never trusts a stored id; it mints a fresh one in file order, so normalise's distinctBy(id)
        // cannot fold two real macros into one just because a hand-edit gave them the same handle.
        val missing = MacroCodec.decode(
            """[{"name":"A","actions":["PILL"]},{"name":"B","actions":["HUD"]}]""",
        )
        assertEquals(2, missing.size)
        assertEquals(2, missing.map { it.id }.distinct().size)

        val shared = MacroCodec.decode(
            """[{"id":5,"name":"A","actions":["PILL"]},{"id":5,"name":"B","actions":["HUD"]}]""",
        )
        assertEquals(2, shared.size)
        assertEquals(2, shared.map { it.id }.distinct().size)
    }

    // --- caps -------------------------------------------------------------------------------------

    @Test
    fun `a list longer than the macro cap is trimmed to the cap`() {
        val over = (1..MacroLibrary.MAX_MACROS + 1)
            .joinToString(",") { """{"name":"M$it","actions":["PILL"]}""" }
        assertEquals(MacroLibrary.MAX_MACROS, MacroCodec.decode("[$over]").size)
    }

    @Test
    fun `duplicate action names in one macro dedupe to a single action`() {
        val decoded = MacroCodec.decode(
            """[{"name":"Dupes","actions":["PILL","PILL","PILL","HUD","HUD"]}]""",
        )
        assertEquals(1, decoded.size)
        assertEquals(listOf(OverlayAction.PILL, OverlayAction.HUD), decoded[0].actions)
    }

    @Test
    fun `all eight macroable actions fit under the action cap and survive a decode`() {
        val everyMacroable =
            """["PILL","CROSSHAIR","HUD","SCREENSHOT","RECORD","FLASHLIGHT","DO_NOT_DISTURB","ROTATION_LOCK"]"""
        val decoded = MacroCodec.decode("""[{"name":"All","actions":$everyMacroable}]""")
        assertEquals(1, decoded.size)
        assertEquals(MacroLibrary.MAX_ACTIONS, decoded[0].actions.size)
    }

    // --- dropping unrunnable macros ---------------------------------------------------------------

    @Test
    fun `a macro with a blank name or no runnable action is dropped`() {
        assertTrue(MacroCodec.decode("""[{"name":"   ","actions":["PILL"]}]""").isEmpty())
        assertTrue(MacroCodec.decode("""[{"name":"Barred","actions":["COLOR","OPEN_APP"]}]""").isEmpty())
        assertTrue(MacroCodec.decode("""[{"name":"None","actions":[]}]""").isEmpty())
    }

    @Test
    fun `a corrupt entry is dropped while the valid entries around it survive`() {
        val decoded = MacroCodec.decode(
            """[
                {"name":"Good","actions":["PILL"]},
                {"name":"","actions":["HUD"]},
                {"name":"Barred","actions":["COLOR"]}
            ]""",
        )
        assertEquals(1, decoded.size)
        assertEquals("Good", decoded[0].name)
    }

    @Test
    fun `a name is sanitised as it is decoded`() {
        // A bell survives no editor and draws as nothing; decode runs every name through the same
        // TextSanitizer boundary MacroLibrary.normalise applies, so it is gone before a caller sees it.
        val decoded = MacroCodec.decode("""[{"name":"Wa\u0007rm","actions":["PILL"]}]""")
        assertEquals(1, decoded.size)
        assertEquals("Warm", decoded[0].name)
    }
}
