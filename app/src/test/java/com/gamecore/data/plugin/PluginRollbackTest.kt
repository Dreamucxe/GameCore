package com.gamecore.data.plugin

import com.gamecore.core.plugin.PluginPackageLoader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The rollback slot's one load-bearing promise, proved on the JVM: exactly one previous package per
 * plugin id, and never a chain.
 *
 * [PluginPreviousVersionStore] exists as a separate class precisely so this file can be written: its
 * KDoc says the invariant is "a JVM unit test over a temporary directory rather than something only
 * observable by installing two builds on a device". Every test here states one part of that
 * invariant — a recorded package reads back, a second record replaces rather than appends, an
 * interrupted `.tmp` write is invisible to `has`/`read`, and an id that is not a safe package name
 * never becomes a file name.
 *
 * What is *not* covered is [PluginRegistryImpl.rollback] itself: it needs a `Context`, a Keystore
 * and a plugin package, so it lives outside unit-test reach. That is the reason the bytes-and-name
 * half was split out; the registry half forwards to this store and to the ordinary install path,
 * which the loader's own suite covers.
 */
class PluginRollbackTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val pluginId = "com.example.plugin"

    /** A store rooted in a fresh subdirectory, so lazy directory creation is part of every test. */
    private fun newStore(): PluginPreviousVersionStore =
        PluginPreviousVersionStore(File(folder.root, "previous"))

    private val bytesV1 = byteArrayOf(1, 2, 3)
    private val bytesV2 = byteArrayOf(9, 8, 7, 6)

    // ------------------------------------------------------------------------------------- record

    @Test
    fun `record then read round trips the package bytes`() {
        val store = newStore()

        assertTrue(store.record(pluginId, bytesV1))
        assertTrue(store.has(pluginId))
        assertArrayEquals(bytesV1, store.read(pluginId))
    }

    @Test
    fun `record creates the directory lazily`() {
        val store = newStore()
        val directory = File(folder.root, "previous")

        assertFalse("constructing the store must not create the directory", directory.exists())
        assertTrue(store.record(pluginId, bytesV1))
        assertTrue(directory.isDirectory)
    }

    @Test
    fun `a second record replaces the first so there is never a chain`() {
        val store = newStore()
        store.record(pluginId, bytesV1)

        assertTrue(store.record(pluginId, bytesV2))

        // The slot holds the newest previous version, not the oldest: a rollback must undo the most
        // recent update, and a chain of older packages would be a history, not a rollback.
        assertArrayEquals(bytesV2, store.read(pluginId))
        val packages = File(folder.root, "previous").listFiles { file -> file.name.endsWith(".gcplugin") }
        assertEquals("exactly one backup file survives across records", 1, packages?.size)
    }

    @Test
    fun `record refuses empty bytes`() {
        val store = newStore()

        assertFalse(store.record(pluginId, ByteArray(0)))
        assertFalse(store.has(pluginId))
    }

    @Test
    fun `record refuses an oversized package`() {
        val store = newStore()
        val tooLarge = ByteArray(PluginPackageLoader.MAX_PACKAGE_BYTES + 1)

        assertFalse(store.record(pluginId, tooLarge))
        assertFalse(store.has(pluginId))
    }

    // --------------------------------------------------------------------------------------- read

    @Test
    fun `read is null and has is false before anything is recorded`() {
        val store = newStore()

        assertFalse(store.has(pluginId))
        assertNull(store.read(pluginId))
    }

    @Test
    fun `read honours its size cap`() {
        val store = newStore()
        store.record(pluginId, bytesV2)

        // A cap smaller than the stored file is a refusal, not a truncated read: half a package is
        // not a rollback.
        assertNull(store.read(pluginId, maxBytes = bytesV2.size - 1))
        assertArrayEquals(bytesV2, store.read(pluginId, maxBytes = bytesV2.size))
    }

    // ------------------------------------------------------------------------------ unsafe ids

    @Test
    fun `an id that is not a package name is refused everywhere`() {
        val store = newStore()

        // Path traversal is the reason the id is validated as a package name before it becomes a
        // file name; every entry point must refuse it the same way, never sanitise it into something
        // that could still write outside the directory.
        assertNull(store.fileFor("../evil"))
        assertFalse(store.record("../evil", bytesV1))
        assertFalse(store.has("../evil"))
        assertNull(store.read("../evil"))
    }

    @Test
    fun `a blank id is refused`() {
        val store = newStore()

        assertNull(store.fileFor(""))
        assertFalse(store.record("   ", bytesV1))
    }

    @Test
    fun `ids that share a prefix do not collide`() {
        val store = newStore()
        val neighbour = "$pluginId.extra"
        val neighbourBytes = byteArrayOf(4, 4, 4)

        assertTrue(store.record(pluginId, bytesV1))
        assertTrue(store.record(neighbour, neighbourBytes))

        assertArrayEquals(bytesV1, store.read(pluginId))
        assertArrayEquals(neighbourBytes, store.read(neighbour))
    }

    // -------------------------------------------------------------------------------------- clear

    @Test
    fun `clear removes the backup so rollback stops being offered`() {
        val store = newStore()
        store.record(pluginId, bytesV1)

        assertTrue(store.clear(pluginId))

        assertFalse(store.has(pluginId))
        assertNull(store.read(pluginId))
    }

    @Test
    fun `clear removes an interrupted temporary file`() {
        val store = newStore()
        store.record(pluginId, bytesV1)
        val temp = File(File(folder.root, "previous"), "$pluginId.gcplugin.tmp")
        temp.writeBytes(bytesV2)

        assertTrue(store.clear(pluginId))
        assertFalse(temp.exists())
    }

    @Test
    fun `clear on an empty slot reports success`() {
        assertTrue(newStore().clear(pluginId))
    }

    @Test
    fun `an interrupted temporary write is invisible to has and read`() {
        val store = newStore()
        val directory = File(folder.root, "previous")
        assertTrue(directory.mkdirs())
        File(directory, "$pluginId.gcplugin.tmp").writeBytes(bytesV2)

        // A crash between write and rename leaves only the temp file. It must not make `has` true —
        // offering a rollback that reads nothing is worse than not offering one — and read ignores it.
        assertFalse(store.has(pluginId))
        assertNull(store.read(pluginId))
    }
}
