package com.gamecore.core.emulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ordering and compatibility rules a user can be misled by.
 *
 * A planner that returned every installed emulator for every file would make the launch screen look
 * capable while sending PS2 images to a Game Boy emulator. These tests pin the opposite: only descriptors
 * that name the file's system appear, a remembered package wins, and open-only emulators are separated
 * from ones that can receive a document.
 */
class EmulatorLaunchPlannerTest {

    private fun descriptor(
        id: String,
        systems: Set<EmulatorSystem>,
        handlesFiles: Boolean = true,
    ) = EmulatorDescriptor(
        id = id,
        displayName = id.replaceFirstChar { it.uppercase() },
        packages = listOf("com.example.$id"),
        systems = systems,
        handlesFiles = handlesFiles,
    )

    private fun installed(
        descriptor: EmulatorDescriptor,
        label: String = descriptor.displayName,
        packageName: String = descriptor.packages.first(),
    ) = InstalledEmulator(
        descriptor = descriptor,
        packageName = packageName,
        label = label,
        versionName = "1.0",
    )

    @Test
    fun `unknown system has no candidates`() {
        val choices = EmulatorLaunchPlanner.choices(
            EmulatorSystem.UNKNOWN,
            listOf(installed(descriptor("any", setOf(EmulatorSystem.PLAYSTATION)))),
        )
        assertTrue(choices.isEmpty)
        assertEquals(0, choices.total)
    }

    @Test
    fun `only emulators naming the system are offered`() {
        val ps2 = installed(descriptor("ps2", setOf(EmulatorSystem.PLAYSTATION_2)))
        val gba = installed(descriptor("gba", setOf(EmulatorSystem.GAME_BOY_ADVANCE)))
        val choices = EmulatorLaunchPlanner.choices(EmulatorSystem.PLAYSTATION_2, listOf(ps2, gba))
        assertEquals(listOf(ps2), choices.handOff)
        assertTrue(choices.openOnly.isEmpty())
    }

    @Test
    fun `preferred package is ranked first`() {
        val alpha = installed(descriptor("alpha", setOf(EmulatorSystem.PLAYSTATION)))
        val zebra = installed(descriptor("zebra", setOf(EmulatorSystem.PLAYSTATION)))
        val choices = EmulatorLaunchPlanner.choices(
            EmulatorSystem.PLAYSTATION,
            listOf(alpha, zebra),
            preferredPackage = "com.example.zebra",
        )
        assertEquals(listOf(zebra, alpha), choices.handOff)
    }

    @Test
    fun `hand-off emulators come before open-only ones even when the open-only one is preferred`() {
        val container = installed(
            descriptor("container", setOf(EmulatorSystem.WINDOWS), handlesFiles = false),
        )
        val handOff = installed(descriptor("handoff", setOf(EmulatorSystem.WINDOWS)))
        val choices = EmulatorLaunchPlanner.choices(
            EmulatorSystem.WINDOWS,
            listOf(container, handOff),
            preferredPackage = "com.example.container",
        )
        assertEquals(listOf(handOff), choices.handOff)
        assertEquals(listOf(container), choices.openOnly)
        assertEquals(2, choices.total)
    }

    @Test
    fun `open-only emulators do not disappear`() {
        val winlator = installed(
            descriptor("winlator", setOf(EmulatorSystem.WINDOWS), handlesFiles = false),
        )
        val choices = EmulatorLaunchPlanner.choices(EmulatorSystem.WINDOWS, listOf(winlator))
        assertTrue(choices.handOff.isEmpty())
        assertEquals(listOf(winlator), choices.openOnly)
        assertFalse(choices.isEmpty)
    }

    @Test
    fun `uninstalled preferred package is ignored`() {
        val alpha = installed(descriptor("alpha", setOf(EmulatorSystem.PLAYSTATION)))
        val choices = EmulatorLaunchPlanner.choices(
            EmulatorSystem.PLAYSTATION,
            listOf(alpha),
            preferredPackage = "com.example.gone",
        )
        assertEquals(listOf(alpha), choices.handOff)
    }

    @Test
    fun `label order is stable when nothing is preferred`() {
        val zulu = installed(descriptor("zulu", setOf(EmulatorSystem.PLAYSTATION)), label = "Zulu")
        val alpha = installed(descriptor("alpha", setOf(EmulatorSystem.PLAYSTATION)), label = "Alpha")
        val choices = EmulatorLaunchPlanner.choices(
            EmulatorSystem.PLAYSTATION,
            listOf(zulu, alpha),
        )
        assertEquals(listOf(alpha, zulu), choices.handOff)
    }

    @Test
    fun `supports follows the descriptor, not a label`() {
        val ds = installed(descriptor("ds", setOf(EmulatorSystem.NINTENDO_DS)))
        assertTrue(EmulatorLaunchPlanner.supports(EmulatorSystem.NINTENDO_DS, listOf(ds)))
        assertFalse(EmulatorLaunchPlanner.supports(EmulatorSystem.NINTENDO_3DS, listOf(ds)))
    }
}
