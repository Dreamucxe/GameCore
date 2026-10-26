package com.gamecore.data.database

import com.gamecore.core.emulator.EmulatorSystem
import com.gamecore.core.emulator.RomEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Persistence mapping for the ROM library.
 *
 * The important asymmetry is deliberate: a row with an unknown system name is kept and shown as
 * "Unknown system" rather than dropped, because it is still the user's file and can be reassigned. A row
 * with no URI or no name cannot be opened or drawn, so that one is dropped.
 */
class RomMappersTest {

    private fun entity(
        id: Long = 7L,
        uri: String = "content://example/game.iso",
        displayName: String = "Game.iso",
        sizeBytes: Long? = 4_000_000_000L,
        extension: String = "iso",
        system: String = EmulatorSystem.PLAYSTATION_2.name,
        emulatorPackage: String? = null,
        launchCount: Int = 0,
    ) = RomEntity(
        id = id,
        uri = uri,
        displayName = displayName,
        sizeBytes = sizeBytes,
        extension = extension,
        system = system,
        emulatorPackage = emulatorPackage,
        addedAtMillis = 100L,
        lastLaunchedAtMillis = null,
        launchCount = launchCount,
    )

    @Test
    fun `row round-trips through the model`() {
        val row = entity(emulatorPackage = "com.example.emulator", launchCount = 3)
        val model = RomMappers.toModel(row)
        requireNotNull(model)
        assertEquals(7L, model.id)
        assertEquals("content://example/game.iso", model.uri)
        assertEquals(EmulatorSystem.PLAYSTATION_2, model.system)
        assertEquals("iso", model.extension)
        assertEquals("com.example.emulator", model.emulatorPackage)
        assertEquals(3, model.launchCount)

        val back = RomMappers.toEntity(model)
        assertEquals(row.copy(lastLaunchedAtMillis = model.lastLaunchedAtMillis), back)
    }

    @Test
    fun `unknown stored system is kept as unknown`() {
        val model = RomMappers.toModel(entity(system = "FUTURE_CONSOLE"))
        requireNotNull(model)
        assertEquals(EmulatorSystem.UNKNOWN, model.system)
    }

    @Test
    fun `rows without a URI or a name are dropped`() {
        assertNull(RomMappers.toModel(entity(uri = " ")))
        assertNull(RomMappers.toModel(entity(displayName = "")))
    }

    @Test
    fun `absent size stays absent and impossible counters are clamped`() {
        val model = RomMappers.toModel(entity(sizeBytes = null, launchCount = -4))
        requireNotNull(model)
        assertNull(model.sizeBytes)
        assertEquals(0, model.launchCount)

        val negative = RomMappers.toModel(entity(sizeBytes = -1L))
        requireNotNull(negative)
        assertNull(negative.sizeBytes)
    }

    @Test
    fun `blank remembered package is no preference`() {
        val model = RomMappers.toModel(entity(emulatorPackage = "   "))
        requireNotNull(model)
        assertNull(model.emulatorPackage)
    }

    @Test
    fun `missing extension is recovered from the display name`() {
        val model = RomMappers.toModel(entity(extension = ""))
        requireNotNull(model)
        assertEquals("iso", model.extension)
    }

    @Test
    fun `model writes the enum name, not its ordinal`() {
        val model = RomEntry(
            id = 0,
            uri = "content://example/game.gba",
            displayName = "Game.gba",
            sizeBytes = null,
            extension = "gba",
            system = EmulatorSystem.GAME_BOY_ADVANCE,
            emulatorPackage = null,
            addedAtMillis = 1,
            lastLaunchedAtMillis = null,
            launchCount = 0,
        )
        assertEquals("GAME_BOY_ADVANCE", RomMappers.toEntity(model).system)
    }
}
