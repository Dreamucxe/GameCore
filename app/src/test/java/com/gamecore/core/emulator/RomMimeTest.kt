package com.gamecore.core.emulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** MIME fallback order: specific first, octet-stream second, wildcard last, with no duplicates. */
class RomMimeTest {

    @Test
    fun `iso has a specific type and a stable fallback chain`() {
        assertEquals("application/x-iso9660-image", RomMime.specific("ISO"))
        assertEquals(
            listOf("application/x-iso9660-image", "application/octet-stream", "*/*"),
            RomMime.candidates("iso"),
        )
    }

    @Test
    fun `exe is named as a Windows executable`() {
        assertEquals("application/x-msdownload", RomMime.specific("exe"))
    }

    @Test
    fun `unknown suffixes fall back without duplicating octet-stream`() {
        assertEquals("application/octet-stream", RomMime.specific("wat"))
        assertEquals(
            listOf("application/octet-stream", "*/*"),
            RomMime.candidates("wat"),
        )
    }

    @Test
    fun `zip is not treated as an opaque blob`() {
        assertTrue(RomMime.candidates("zip").first() == "application/zip")
    }
}
