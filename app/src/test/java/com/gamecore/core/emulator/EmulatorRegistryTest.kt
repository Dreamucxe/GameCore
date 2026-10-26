package com.gamecore.core.emulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The curated registry's invariants.
 *
 * Package names have to be unique across descriptors, every descriptor has to name at least one system
 * other than [EmulatorSystem.UNKNOWN], and every advertised link has to be an HTTPS page rather than an
 * APK. A duplicate package would make detection order-dependent; an empty system set would produce a row
 * that can never be offered.
 */
class EmulatorRegistryTest {

    @Test
    fun `ids and package names are unique`() {
        assertEquals(
            EmulatorRegistry.all.size,
            EmulatorRegistry.all.map { it.id }.distinct().size,
        )
        val packages = EmulatorRegistry.all.flatMap { it.packages }
        assertEquals(packages.size, packages.distinct().size)
    }

    @Test
    fun `every descriptor names a real system`() {
        EmulatorRegistry.all.forEach { descriptor ->
            assertTrue("${descriptor.id} has no systems", descriptor.systems.isNotEmpty())
            assertFalse(
                "${descriptor.id} lists UNKNOWN",
                EmulatorSystem.UNKNOWN in descriptor.systems,
            )
        }
    }

    @Test
    fun `advertised links are https pages`() {
        EmulatorRegistry.all.forEach { descriptor ->
            val url = descriptor.downloadUrl ?: return@forEach
            assertTrue("${descriptor.id} has a non-HTTPS link: $url", url.startsWith("https://"))
        }
    }

    @Test
    fun `package lookup is exact`() {
        assertEquals("ppsspp", EmulatorRegistry.descriptorFor("org.ppsspp.ppsspp")?.id)
        assertEquals("ppsspp", EmulatorRegistry.descriptorFor("org.ppsspp.ppssppgold")?.id)
        assertNull(EmulatorRegistry.descriptorFor("org.ppsspp.ppsspp.clone"))
        assertNull(EmulatorRegistry.descriptorFor(""))
    }

    @Test
    fun `winlator is the one open-only descriptor`() {
        val winlator = EmulatorRegistry.descriptorFor("com.winlator")
        assertNotNull(winlator)
        assertFalse(winlator!!.handlesFiles)
        assertEquals(setOf(EmulatorSystem.WINDOWS), winlator.systems)
        assertTrue(EmulatorRegistry.withDownloadPage.any { it.id == "winlator" })
    }

    @Test
    fun `download and detection-only lists partition the registry`() {
        assertEquals(
            EmulatorRegistry.all.size,
            EmulatorRegistry.withDownloadPage.size + EmulatorRegistry.detectionOnly.size,
        )
        assertTrue(EmulatorRegistry.withDownloadPage.all { it.downloadUrl != null })
        assertTrue(EmulatorRegistry.detectionOnly.all { it.downloadUrl == null })
    }
}
