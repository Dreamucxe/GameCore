package com.gamecore.core.emulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The extension rules the ROM library is built on.
 *
 * The cases that matter are the ones that would otherwise let GameCore guess: `.iso` is four families,
 * not one; a leading dot is not an extension; case is irrelevant; and a draft only auto-selects a system
 * when exactly one family claims the suffix.
 */
class RomFileTest {

    @Test
    fun `extension is lower-cased and uses the last dot`() {
        assertEquals("iso", RomFile.extensionOf("Game.iso"))
        assertEquals("iso", RomFile.extensionOf("Game.ISO"))
        assertEquals("iso", RomFile.extensionOf("Game 1.0.iso"))
        assertEquals("gz", RomFile.extensionOf("archive.tar.gz"))
        assertEquals("iso", RomFile.extensionOf("  Game.iso  "))
    }

    @Test
    fun `missing or unusable extensions are null`() {
        assertNull(RomFile.extensionOf("Game"))
        assertNull(RomFile.extensionOf("Game."))
        assertNull(RomFile.extensionOf(".iso"))
        assertNull(RomFile.extensionOf(""))
        assertNull(RomFile.extensionOf("   "))
    }

    @Test
    fun `iso is deliberately ambiguous`() {
        val systems = RomFile.systemsFor("iso")
        assertEquals(
            listOf(
                EmulatorSystem.GAMECUBE_WII,
                EmulatorSystem.PLAYSTATION_2,
                EmulatorSystem.PLAYSTATION_PORTABLE,
                EmulatorSystem.PLAYSTATION,
            ),
            systems,
        )
        assertNull(
            RomDraft(
                uri = "content://example/game.iso",
                displayName = "Game.iso",
                sizeBytes = 1024L,
                extension = "iso",
            ).unambiguousSystem,
        )
    }

    @Test
    fun `exe has exactly one system and is therefore auto-selected`() {
        val draft = RomDraft(
            uri = "content://example/setup.exe",
            displayName = "setup.exe",
            sizeBytes = null,
            extension = "exe",
        )
        assertEquals(listOf(EmulatorSystem.WINDOWS), draft.candidateSystems)
        assertEquals(EmulatorSystem.WINDOWS, draft.unambiguousSystem)
    }

    @Test
    fun `recognition is case-insensitive and unknown suffixes are rejected`() {
        assertTrue(RomFile.isRecognised("ISO"))
        assertTrue(RomFile.isRecognised("GBA"))
        assertFalse(RomFile.isRecognised("pdf"))
        assertFalse(RomFile.isRecognised(""))
    }

    @Test
    fun `suffix is rendered with one dot`() {
        assertEquals(".iso", RomFile.suffix("ISO"))
        assertEquals(".gba", RomFile.suffix("gba"))
        assertEquals("", RomFile.suffix(""))
    }

    @Test
    fun `stored system names parse defensively`() {
        assertEquals(EmulatorSystem.PLAYSTATION_2, EmulatorSystem.fromStoredName("PLAYSTATION_2"))
        assertEquals(EmulatorSystem.UNKNOWN, EmulatorSystem.fromStoredName("FUTURE_CONSOLE"))
        assertEquals(EmulatorSystem.UNKNOWN, EmulatorSystem.fromStoredName(null))
    }
}
