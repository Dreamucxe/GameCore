package com.gamecore.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate that decides whether a game's file is shown as editable text or as a read-only notice (spec §A4).
 *
 * These tests pin the two refusals — too large, and binary — and, just as importantly, the cases that must
 * NOT be refused: ordinary UTF-8, tabs and newlines, an empty file, a file sitting exactly on the size cap.
 * A wrong "binary" verdict only costs an edit; a wrong "text" verdict on a save blob is a corrupted save, so
 * the suspicious cases are asserted explicitly rather than left to chance.
 */
class ConfigFileInspectorTest {

    private fun editableText(inspection: FileInspection): String {
        assertTrue("expected Editable but was $inspection", inspection is FileInspection.Editable)
        return (inspection as FileInspection.Editable).text
    }

    @Test
    fun `plain ascii config text is editable and returned verbatim`() {
        val bytes = "graphics=high\nfps=60\n".toByteArray(Charsets.UTF_8)
        assertEquals("graphics=high\nfps=60\n", editableText(ConfigFileInspector.inspect(bytes)))
    }

    @Test
    fun `multibyte utf-8 is decoded, not rejected`() {
        val bytes = "name=café\nlevel=Ω\n".toByteArray(Charsets.UTF_8)
        assertEquals("name=café\nlevel=Ω\n", editableText(ConfigFileInspector.inspect(bytes)))
    }

    @Test
    fun `an empty file is editable as the empty string`() {
        assertEquals("", editableText(ConfigFileInspector.inspect(ByteArray(0))))
    }

    @Test
    fun `tabs newlines and carriage returns do not read as binary`() {
        assertFalse(ConfigFileInspector.looksBinary("a\tb\r\nc\n".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `a NUL byte marks the file binary`() {
        val bytes = byteArrayOf('o'.code.toByte(), 'k'.code.toByte(), 0, 'x'.code.toByte())
        assertTrue(ConfigFileInspector.looksBinary(bytes))
        val inspection = ConfigFileInspector.inspect(bytes)
        assertTrue(inspection is FileInspection.ViewOnly)
        assertEquals(ViewOnlyReason.BINARY, (inspection as FileInspection.ViewOnly).reason)
    }

    @Test
    fun `a high share of control bytes reads as binary`() {
        // 0x01 is a control byte that never occurs in ordinary text; a run of them is unmistakably binary.
        val bytes = ByteArray(200) { 0x01 }
        assertTrue(ConfigFileInspector.looksBinary(bytes))
        assertEquals(
            ViewOnlyReason.BINARY,
            (ConfigFileInspector.inspect(bytes) as FileInspection.ViewOnly).reason,
        )
    }

    @Test
    fun `bytes that are not valid utf-8 are treated as binary`() {
        // 0xC3 starts a two-byte sequence; 0x28 '(' is not a valid continuation, so the decode fails.
        val bytes = byteArrayOf(0xC3.toByte(), 0x28)
        assertFalse("this is a decode failure, not a control-byte failure", ConfigFileInspector.looksBinary(bytes))
        assertEquals(
            ViewOnlyReason.BINARY,
            (ConfigFileInspector.inspect(bytes) as FileInspection.ViewOnly).reason,
        )
    }

    @Test
    fun `a file exactly on the size cap is editable`() {
        val bytes = ByteArray(ConfigFileInspector.MAX_EDITABLE_BYTES) { 'a'.code.toByte() }
        assertTrue(ConfigFileInspector.inspect(bytes) is FileInspection.Editable)
    }

    @Test
    fun `a file one byte over the cap is view-only for size`() {
        val bytes = ByteArray(ConfigFileInspector.MAX_EDITABLE_BYTES + 1) { 'a'.code.toByte() }
        val inspection = ConfigFileInspector.inspect(bytes)
        assertTrue(inspection is FileInspection.ViewOnly)
        assertEquals(ViewOnlyReason.TOO_LARGE, (inspection as FileInspection.ViewOnly).reason)
    }

}
