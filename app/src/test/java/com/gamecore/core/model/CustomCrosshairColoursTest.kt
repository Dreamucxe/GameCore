package com.gamecore.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The custom colour list's rules and its storage format.
 *
 * Worth a test file of its own because everything here is enforced in one place on purpose — both the
 * getter and the setter in `SecurePreferenceStore` go through [CustomCrosshairColours.normalise], so a rule
 * that quietly stopped holding would stop holding for stored data as well as for new writes. The cases
 * below are the four things that rule set exists to prevent: a transparent crosshair colour, the same
 * swatch drawn twice, an unbounded row, and a preferences file that a bad write turns into a crash.
 */
class CustomCrosshairColoursTest {

    @Test
    fun `alpha is forced opaque, whatever came in`() {
        assertEquals(
            listOf(0xFF123456.toInt()),
            CustomCrosshairColours.normalise(listOf(0x00123456)),
        )
        assertEquals(
            listOf(0xFF123456.toInt()),
            CustomCrosshairColours.normalise(listOf(0x80123456.toInt())),
        )
    }

    /** Two colours differing only in alpha are one colour here, which is what makes the cap meaningful. */
    @Test
    fun `the same colour at two alphas collapses to one entry`() {
        assertEquals(
            listOf(0xFF123456.toInt()),
            CustomCrosshairColours.normalise(listOf(0x00123456, 0xFF123456.toInt())),
        )
    }

    @Test
    fun `a colour already in the built in row is dropped`() {
        assertEquals(
            listOf(0xFF123456.toInt()),
            CustomCrosshairColours.normalise(CROSSHAIR_COLOURS + 0xFF123456.toInt()),
        )
    }

    @Test
    fun `the newest colour goes to the front`() {
        val first = CustomCrosshairColours.remember(emptyList(), 0xFF111111.toInt())
        val second = CustomCrosshairColours.remember(first, 0xFF222222.toInt())
        assertEquals(listOf(0xFF222222.toInt(), 0xFF111111.toInt()), second)
    }

    /** Re-picking a colour already in the list moves it up rather than adding a second copy. */
    @Test
    fun `remembering a colour twice moves it rather than duplicating it`() {
        val list = CustomCrosshairColours.remember(
            listOf(0xFF111111.toInt(), 0xFF222222.toInt()),
            0xFF222222.toInt(),
        )
        assertEquals(listOf(0xFF222222.toInt(), 0xFF111111.toInt()), list)
    }

    /**
     * The cap drops the oldest rather than refusing the newest.
     *
     * The direction is the whole point: a full list that rejected new colours would leave a user unable to
     * add one with nothing on any screen explaining why, which is exactly the kind of silent no this app's
     * rules are against.
     */
    @Test
    fun `a full list makes room for a new colour by forgetting the oldest`() {
        var list = emptyList<Int>()
        for (i in 1..CustomCrosshairColours.MAX) {
            list = CustomCrosshairColours.remember(list, 0xFF000000.toInt() or i)
        }
        assertEquals(CustomCrosshairColours.MAX, list.size)
        val oldest = 0xFF000000.toInt() or 1
        assertTrue("the oldest colour should still be here before the ninth", list.contains(oldest))

        val newest = 0xFF00ABCD.toInt()
        list = CustomCrosshairColours.remember(list, newest)
        assertEquals(CustomCrosshairColours.MAX, list.size)
        assertEquals(newest, list.first())
        assertFalse("the oldest colour should have made way", list.contains(oldest))
    }

    // ------------------------------------------------------------------------------------ the format

    @Test
    fun `a list survives a round trip through storage`() {
        val colours = listOf(0xFF00ABCD.toInt(), 0xFF112233.toInt(), 0xFFFEDCBA.toInt())
        assertEquals(colours, CustomCrosshairColours.decode(CustomCrosshairColours.encode(colours)))
    }

    @Test
    fun `colours are stored as eight upper case hex digits`() {
        assertEquals("FF00ABCD", CustomCrosshairColours.encode(listOf(0xFF00ABCD.toInt())))
        assertEquals(
            "FF000001|FF000002",
            CustomCrosshairColours.encode(listOf(0xFF000001.toInt(), 0xFF000002.toInt())),
        )
    }

    @Test
    fun `nothing stored reads back as nothing rather than as a default set`() {
        assertEquals(emptyList<Int>(), CustomCrosshairColours.decode(null))
        assertEquals(emptyList<Int>(), CustomCrosshairColours.decode(""))
        assertEquals(emptyList<Int>(), CustomCrosshairColours.decode("   "))
        assertEquals("", CustomCrosshairColours.encode(emptyList()))
    }

    /**
     * A bad entry costs that entry and nothing else.
     *
     * The failure this rules out is a preferences file with one corrupt value taking the whole list with
     * it, which the user would experience as their saved colours disappearing for no stated reason.
     */
    @Test
    fun `an unreadable entry is skipped and the rest of the list survives`() {
        val raw = "FF00ABCD|nonsense|FF112233|FF11|ZZZZZZZZ|"
        assertEquals(
            listOf(0xFF00ABCD.toInt(), 0xFF112233.toInt()),
            CustomCrosshairColours.decode(raw),
        )
    }

    /** Reads normalise too, so a list written past the cap by any route still comes back within it. */
    @Test
    fun `a stored list longer than the cap is trimmed on the way back out`() {
        val raw = (1..CustomCrosshairColours.MAX + 4)
            .joinToString("|") { "%08X".format(0xFF000000.toInt() or it) }
        assertEquals(CustomCrosshairColours.MAX, CustomCrosshairColours.decode(raw).size)
    }

    /** A high colour is why decoding goes through unsigned parsing: `FFFEDCBA` overflows a signed `Int`. */
    @Test
    fun `a colour with the top bit set decodes rather than failing to parse`() {
        assertEquals(listOf(0xFFFEDCBA.toInt()), CustomCrosshairColours.decode("FFFEDCBA"))
    }
}
