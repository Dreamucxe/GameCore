package com.gamecore.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The boundary every string from outside GameCore crosses.
 *
 * A game's `android:label` is as attacker-controlled as a text field, so these cases are written as
 * the hostile inputs they defend against rather than as coverage of the helper's branches.
 *
 * The invisible characters are built from their code points rather than pasted in. A literal U+202E
 * in this file would reverse the rendering of the assertion it appears in, which would make the test
 * for that character unreviewable — the very problem it exists to prevent.
 */
class TextSanitizerTest {

    private val bidiOverride = Char(0x202E).toString()
    private val ltrOverride = Char(0x202D).toString()
    private val zeroWidthSpace = Char(0x200B).toString()
    private val zeroWidthJoiner = Char(0x200D).toString()
    private val combiningAcute = Char(0x0301).toString()
    private val combiningTildeBelow = Char(0x0330).toString()
    private val bell = Char(0x0007).toString()
    private val grinningFace = String(Character.toChars(0x1F600))

    @Test
    fun `an ordinary name is returned exactly as typed`() {
        assertEquals("Genshin Impact", TextSanitizer.sanitizeName("Genshin Impact"))
        assertEquals("PUBG: BATTLEGROUNDS", TextSanitizer.sanitizeName("PUBG: BATTLEGROUNDS"))
        assertEquals("Cafe Racer 2", TextSanitizer.sanitizeName("Cafe Racer 2"))
    }

    @Test
    fun `nothing is not an error`() {
        assertEquals("", TextSanitizer.sanitizeName(null))
        assertEquals("", TextSanitizer.sanitizeName(""))
        assertEquals("", TextSanitizer.sanitizeMultiline(null))
    }

    @Test
    fun `a bidi override cannot reverse the row it is drawn on`() {
        assertEquals("Gameevil", TextSanitizer.sanitizeName("Game${bidiOverride}evil"))
        assertEquals("", TextSanitizer.sanitizeName(bidiOverride + ltrOverride + zeroWidthSpace))
    }

    @Test
    fun `zero-width characters cannot make two identical names unequal`() {
        val plain = TextSanitizer.sanitizeName("Warzone")
        val invisible = TextSanitizer.sanitizeName("War${zeroWidthSpace}zo${zeroWidthJoiner}ne")
        assertEquals(plain, invisible)
        assertEquals("Warzone", invisible)
    }

    @Test
    fun `a newline in a single-line field becomes a space rather than a second row`() {
        assertEquals("Line Two", TextSanitizer.sanitizeName("Line\nTwo"))
        assertEquals("a b", TextSanitizer.sanitizeName("a\n\n\nb"))
        assertEquals("tab bed", TextSanitizer.sanitizeName("tab\tbed"))
        assertEquals("spaced out", TextSanitizer.sanitizeName("  spaced   out  "))
    }

    @Test
    fun `a control character is removed outright, not turned into a space`() {
        assertEquals("ab", TextSanitizer.sanitizeName("a${bell}b"))
        assertEquals("ab", TextSanitizer.sanitizeMultiline("a${bell}b"))
    }

    @Test
    fun `a stack of diacritics is cut to what real text needs`() {
        val zalgo = "e" + combiningAcute.repeat(40)
        assertEquals("e" + combiningAcute.repeat(2), TextSanitizer.sanitizeName(zalgo))
        // Two marks is legitimate text and survives untouched.
        val legitimate = "e" + combiningAcute + combiningTildeBelow
        assertEquals(legitimate, TextSanitizer.sanitizeName(legitimate))
    }

    @Test
    fun `a name longer than the cap is truncated with an ellipsis at the cap`() {
        val cut = TextSanitizer.sanitizeName("a".repeat(100))
        assertEquals("a".repeat(63) + "…", cut)
        assertEquals(TextSanitizer.MAX_NAME_LENGTH, cut.length)
    }

    @Test
    fun `a name exactly at the cap is left alone`() {
        val exact = "a".repeat(TextSanitizer.MAX_NAME_LENGTH)
        assertEquals(exact, TextSanitizer.sanitizeName(exact))
    }

    @Test
    fun `truncation never splits a surrogate pair into a replacement glyph`() {
        val cut = TextSanitizer.sanitizeName("a".repeat(62) + grinningFace + "b".repeat(40))
        assertEquals("a".repeat(62) + "…", cut)
        assertFalse(cut.any { Character.isSurrogate(it) })
    }

    @Test
    fun `truncation never orphans a combining mark from its base letter`() {
        val cut = TextSanitizer.sanitizeName("a".repeat(63) + combiningAcute + "b".repeat(20))
        assertEquals("a".repeat(62) + "…", cut)
    }

    @Test
    fun `a cap too small for an ellipsis yields nothing rather than a stray glyph`() {
        assertEquals("", TextSanitizer.sanitizeName("hello", maxLength = 1))
        assertEquals("", TextSanitizer.sanitizeName("hello", maxLength = 0))
    }

    @Test
    fun `a multiline note keeps its lines but not its blank pages`() {
        assertEquals("first\nsecond", TextSanitizer.sanitizeMultiline("first\nsecond"))
        assertEquals("a\n\nb", TextSanitizer.sanitizeMultiline("a\n\n\n\n\nb"))
        assertEquals("a\n\nb", TextSanitizer.sanitizeMultiline("a\n\nb"))
    }

    @Test
    fun `notification text is capped shorter than screen text`() {
        val cut = TextSanitizer.sanitizeForNotification("a".repeat(60))
        assertEquals("a".repeat(47) + "…", cut)
        assertEquals(48, cut.length)
        assertTrue(cut.length < TextSanitizer.MAX_NAME_LENGTH)
    }

    @Test
    fun `a well-formed package name is accepted and trimmed`() {
        assertEquals("com.example.game", TextSanitizer.validatePackageName("com.example.game"))
        assertEquals("com.example.game", TextSanitizer.validatePackageName("  com.example.game  "))
        assertEquals("com.a.b_c9", TextSanitizer.validatePackageName("com.a.b_c9"))
        assertEquals("_private.app", TextSanitizer.validatePackageName("_private.app"))
    }

    @Test
    fun `anything that is not a package name is rejected rather than cleaned`() {
        assertNull(TextSanitizer.validatePackageName(null))
        assertNull(TextSanitizer.validatePackageName(""))
        assertNull(TextSanitizer.validatePackageName("   "))
        // A single bare segment is far likelier to be a mistake than a real package.
        assertNull(TextSanitizer.validatePackageName("nodots"))
        assertNull(TextSanitizer.validatePackageName("com."))
        assertNull(TextSanitizer.validatePackageName(".com.example"))
        assertNull(TextSanitizer.validatePackageName("com..example"))
        assertNull(TextSanitizer.validatePackageName("1com.example"))
        assertNull(TextSanitizer.validatePackageName("com.example-game"))
        assertNull(TextSanitizer.validatePackageName("com.example.game/../../etc"))
        assertNull(TextSanitizer.validatePackageName("com.example; id"))
        assertNull(TextSanitizer.validatePackageName("com.example.game$zeroWidthSpace"))
        assertNull(TextSanitizer.validatePackageName("com." + "a".repeat(260)))
    }
}
