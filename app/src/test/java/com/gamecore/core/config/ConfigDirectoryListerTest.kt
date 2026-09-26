package com.gamecore.core.config

import com.gamecore.core.common.AccessLevel
import com.gamecore.core.shizuku.ElevatedShell
import com.gamecore.core.shizuku.ShellCommand
import com.gamecore.core.shizuku.ShellResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ConfigDirectoryLister]'s behaviour, verified against a fake [ElevatedShell] that returns canned
 * `ls -1Ap` stdout for whatever command it is handed — the same fake-shell pattern the config editor's
 * own test uses, minus the file plumbing this lister does not need. The point is the parse and the
 * result contract: directories are told from files by the trailing slash, an empty directory is a
 * success, a denied or unbuildable path is a [ConfigListResult.Failed] and never a throw, and `.`/`..`
 * never reach the browser.
 */
class ConfigDirectoryListerTest {

    private val gamePkg = "com.example.game"
    private val gameUser = 0

    private val shell = FakeShell()
    private val lister = ConfigDirectoryLister(shell)

    /** Point the fake at a successful listing whose stdout is [stdout]. */
    private fun givenListing(stdout: String) {
        shell.response = ShellResult(exitCode = 0, stdout = stdout, stderr = "", accessLevel = AccessLevel.SHIZUKU)
    }

    @Test
    fun `a mix of files and subdirectories parses with directories flagged`() = runBlocking {
        // `ls -1Ap`: one name per line, a trailing slash on directories, dotfiles shown but no `.`/`..`.
        givenListing("config.ini\nsaves/\n.hidden_state\ndata/\nplayer.dat\n")

        val result = lister.list(gamePkg, gameUser)

        assertTrue("was $result", result is ConfigListResult.Listed)
        val entries = (result as ConfigListResult.Listed).entries
        assertEquals(5, entries.size)

        // The trailing slash is a directory marker, not part of the name, so it is stripped.
        val byName = entries.associateBy { it.name }
        assertFalse(byName.getValue("config.ini").isDirectory)
        assertTrue(byName.getValue("saves").isDirectory)
        assertTrue(byName.getValue("data").isDirectory)
        assertFalse(byName.getValue("player.dat").isDirectory)
        // `-A` keeps dotfiles; only `.`/`..` are meant to be dropped, so a real dotfile is a normal file.
        assertFalse(byName.getValue(".hidden_state").isDirectory)

        // The command carries no size, so every row's size is unknown — never a fabricated zero.
        entries.forEach { assertNull("size for ${it.name}", it.sizeBytes) }
        // Top-level children carry a bare relative path the browser can descend into.
        assertEquals("saves", byName.getValue("saves").relativePath)
        assertEquals("config.ini", byName.getValue("config.ini").relativePath)
    }

    @Test
    fun `listing a subdirectory composes each child's relative path onto the parent`() = runBlocking {
        givenListing("settings.xml\nprofiles/\n")

        val result = lister.list(gamePkg, gameUser, relativePath = "shared_prefs")

        assertTrue("was $result", result is ConfigListResult.Listed)
        val byName = (result as ConfigListResult.Listed).entries.associateBy { it.name }
        // The child path is the parent joined with the name by a single slash — feedable straight back
        // into list() to descend, or into statConfigFile() to open.
        assertEquals("shared_prefs/settings.xml", byName.getValue("settings.xml").relativePath)
        assertEquals("shared_prefs/profiles", byName.getValue("profiles").relativePath)
    }

    @Test
    fun `an empty directory is an empty success list`() = runBlocking {
        givenListing("") // an empty `files` directory prints nothing

        val result = lister.list(gamePkg, gameUser)

        assertTrue("was $result", result is ConfigListResult.Listed)
        assertTrue((result as ConfigListResult.Listed).entries.isEmpty())
    }

    @Test
    fun `a non-zero exit is a shell failure, not a thrown exception`() = runBlocking {
        shell.response = ShellResult(
            exitCode = 1, stdout = "", stderr = "ls: /…/files: Permission denied", accessLevel = AccessLevel.SHIZUKU,
        )

        val result = lister.list(gamePkg, gameUser)

        assertTrue("was $result", result is ConfigListResult.Failed)
        assertEquals(ConfigListResult.Failed.Reason.SHELL, (result as ConfigListResult.Failed).reason)
    }

    @Test
    fun `a path that cannot be built is rejected before the shell runs`() = runBlocking {
        // A `..` segment makes ShellCommand.listConfigDir(...) return null; that null is a failure result.
        val result = lister.list(gamePkg, gameUser, relativePath = "../secrets")

        assertTrue("was $result", result is ConfigListResult.Failed)
        assertEquals(ConfigListResult.Failed.Reason.PATH_REJECTED, (result as ConfigListResult.Failed).reason)
        // The command was never even built, so nothing reached the shell.
        assertTrue(shell.executed.isEmpty())
    }

    @Test
    fun `dot and dot-dot entries are excluded even if the listing emits them`() = runBlocking {
        // `-A` omits `.`/`..`, but a shell that ignored the flag must not put "up a level" rows in a browser.
        givenListing(".\n..\n./\n../\nreal.cfg\nsubdir/\n")

        val result = lister.list(gamePkg, gameUser)

        assertTrue("was $result", result is ConfigListResult.Listed)
        val entries = (result as ConfigListResult.Listed).entries
        assertEquals(2, entries.size)
        assertTrue(entries.none { it.name == "." || it.name == ".." })
        assertTrue(entries.any { it.name == "real.cfg" && !it.isDirectory })
        assertTrue(entries.any { it.name == "subdir" && it.isDirectory })
    }

    @Test
    fun `an unavailable shell requires shizuku and runs nothing`() = runBlocking {
        shell.available = false

        val result = lister.list(gamePkg, gameUser)

        assertTrue("was $result", result is ConfigListResult.RequiresShizuku)
        assertTrue(shell.executed.isEmpty())
    }
}

/**
 * A fake [ElevatedShell] that hands back a canned [ShellResult] for any command and records what it ran,
 * so a test can drive both the happy path and the failure paths without a device. It mirrors the shape
 * of the config editor test's own fake — the three interface members plus an `executed` log — but has no
 * file plumbing, because the lister only ever issues a read-only [ShellCommand.ListConfigDir].
 */
private class FakeShell : ElevatedShell {

    var available = true
    var response: ShellResult = ShellResult(0, "", "", AccessLevel.SHIZUKU)
    val executed = mutableListOf<ShellCommand>()

    override val accessLevel = AccessLevel.SHIZUKU

    override suspend fun isAvailable(): Boolean = available

    override suspend fun execute(command: ShellCommand, timeoutMillis: Long): ShellResult {
        executed += command
        return response
    }
}
