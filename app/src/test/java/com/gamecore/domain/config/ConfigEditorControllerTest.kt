package com.gamecore.domain.config

import com.gamecore.BuildConfig
import com.gamecore.core.common.AccessLevel
import com.gamecore.core.config.ConfigFileInspector
import com.gamecore.core.config.ConfigWorkspace
import com.gamecore.core.config.UpdateDrift
import com.gamecore.core.config.ViewOnlyReason
import com.gamecore.core.model.ConfigBackupKind
import com.gamecore.core.shizuku.ElevatedShell
import com.gamecore.core.shizuku.ShellCommand
import com.gamecore.core.shizuku.ShellResult
import com.gamecore.data.database.ConfigBackupDao
import com.gamecore.data.database.ConfigBackupEntity
import com.gamecore.data.repository.ConfigBackupRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The config editor's behaviour, verified against a fake elevated shell that moves real bytes between
 * two temp directories — one standing in for a game's sandbox, one for GameCore's own storage — and a
 * real [ConfigBackupRepository] over an in-memory DAO. The point is to prove the safety net, not the
 * plumbing: an over-limit file is refused before it is copied, the untouched original is secured before
 * anything overwrites the live file, a second save never displaces that first original, and any failure
 * before the commit rename leaves the live file exactly as it was.
 */
class ConfigEditorControllerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val gamePkg = "com.example.game"
    private val gameUser = 0
    private val ownUser = 0

    private lateinit var gameCoreRoot: File
    private lateinit var sandboxRoot: File
    private lateinit var shell: FakeShell
    private lateinit var dao: FakeConfigBackupDao
    private lateinit var workspace: ConfigWorkspace
    private lateinit var controller: ConfigEditorController

    @Before
    fun setUp() {
        gameCoreRoot = temp.newFolder("gamecore-files")
        sandboxRoot = temp.newFolder("sandbox")
        shell = FakeShell(gameCoreRoot, sandboxRoot)
        dao = FakeConfigBackupDao()
        workspace = ConfigWorkspace(gameCoreRoot, ownUser)
        val repo = ConfigBackupRepository(dao, Dispatchers.Unconfined)
        controller = ConfigEditorController(shell, workspace, repo, Dispatchers.Unconfined)
    }

    private fun target(rel: String = "shared_prefs/settings.xml") = ConfigTarget(gamePkg, gameUser, rel)

    @Test
    fun `open requires shizuku when the shell is unavailable`() = runBlocking {
        shell.available = false
        assertTrue(controller.open(target()) is ConfigOpenResult.RequiresShizuku)
    }

    @Test
    fun `open reports not found for a missing file`() = runBlocking {
        assertEquals(ConfigOpenResult.NotFound, controller.open(target()))
    }

    @Test
    fun `open returns editable text with its parse and hash`() = runBlocking {
        val body = "fov=90\nname=player\n"
        shell.writeGameFile(gamePkg, gameUser, "shared_prefs/settings.xml", body.toByteArray())
        val result = controller.open(target())
        assertTrue("was $result", result is ConfigOpenResult.Editable)
        result as ConfigOpenResult.Editable
        assertEquals(body, result.text)
        assertEquals(body.toByteArray().size.toLong(), result.sizeBytes)
        assertEquals(64, result.sha256.length)
        assertTrue(stagingIsEmpty())
    }

    @Test
    fun `open returns view-only for binary content`() = runBlocking {
        shell.writeGameFile(gamePkg, gameUser, "a.bin", byteArrayOf(0, 1, 2, 0, 3))
        val result = controller.open(target("a.bin"))
        assertTrue("was $result", result is ConfigOpenResult.ViewOnly)
        assertEquals(ViewOnlyReason.BINARY, (result as ConfigOpenResult.ViewOnly).reason)
    }

    @Test
    fun `open refuses an over-limit file before copying a byte of it`() = runBlocking {
        val big = ByteArray(ConfigFileInspector.MAX_EDITABLE_BYTES + 1)
        shell.writeGameFile(gamePkg, gameUser, "big.cfg", big)
        val result = controller.open(target("big.cfg"))
        assertTrue("was $result", result is ConfigOpenResult.ViewOnly)
        result as ConfigOpenResult.ViewOnly
        assertEquals(ViewOnlyReason.TOO_LARGE, result.reason)
        assertEquals(big.size.toLong(), result.sizeBytes)
        assertTrue(shell.executed.none { it is ShellCommand.CopyConfigFile })
    }

    @Test
    fun `open reports no drift when the file has never been backed up`() = runBlocking {
        // No original recorded, so there is no "as of when" to compare a current install time against.
        shell.writeGameFile(gamePkg, gameUser, "settings.cfg", "fov=90\n".toByteArray())
        val result = controller.open(target("settings.cfg"), currentLastUpdateTime = 5000L)
        assertTrue("was $result", result is ConfigOpenResult.Editable)
        assertEquals(UpdateDrift.NO_BASELINE, (result as ConfigOpenResult.Editable).updateDrift)
    }

    @Test
    fun `open flags drift when the game was updated since the original was recorded`() = runBlocking {
        shell.writeGameFile(gamePkg, gameUser, "settings.cfg", "fov=70\n".toByteArray())
        // The first save stamps the backed-up original with the install time at save.
        assertTrue(
            controller.save(target("settings.cfg"), "fov=80\n".toByteArray(), sourceLastUpdateTime = 1000L)
                is ConfigSaveResult.Saved,
        )
        // Reopen with a newer install time: the game was updated in between, so the config may be stale.
        val result = controller.open(target("settings.cfg"), currentLastUpdateTime = 2000L)
        assertTrue("was $result", result is ConfigOpenResult.Editable)
        assertEquals(UpdateDrift.UPDATED_SINCE, (result as ConfigOpenResult.Editable).updateDrift)
    }

    @Test
    fun `open reports unchanged when the install time matches the recorded baseline`() = runBlocking {
        shell.writeGameFile(gamePkg, gameUser, "settings.cfg", "fov=70\n".toByteArray())
        assertTrue(
            controller.save(target("settings.cfg"), "fov=80\n".toByteArray(), sourceLastUpdateTime = 1000L)
                is ConfigSaveResult.Saved,
        )
        val result = controller.open(target("settings.cfg"), currentLastUpdateTime = 1000L)
        assertTrue("was $result", result is ConfigOpenResult.Editable)
        assertEquals(UpdateDrift.UNCHANGED, (result as ConfigOpenResult.Editable).updateDrift)
    }

    @Test
    fun `open cannot judge drift when the current install time is unknown`() = runBlocking {
        shell.writeGameFile(gamePkg, gameUser, "settings.cfg", "fov=70\n".toByteArray())
        assertTrue(
            controller.save(target("settings.cfg"), "fov=80\n".toByteArray(), sourceLastUpdateTime = 1000L)
                is ConfigSaveResult.Saved,
        )
        // A null current time — the package manager did not surface one — is the safe "cannot warn" direction.
        val result = controller.open(target("settings.cfg"), currentLastUpdateTime = null)
        assertTrue("was $result", result is ConfigOpenResult.Editable)
        assertEquals(UpdateDrift.NO_BASELINE, (result as ConfigOpenResult.Editable).updateDrift)
    }

    @Test
    fun `open cannot judge drift when the recorded original carries no install stamp`() = runBlocking {
        shell.writeGameFile(gamePkg, gameUser, "settings.cfg", "fov=70\n".toByteArray())
        // Save without a source stamp: the original row is recorded with a null sourceLastUpdateTime.
        assertTrue(
            controller.save(target("settings.cfg"), "fov=80\n".toByteArray(), sourceLastUpdateTime = null)
                is ConfigSaveResult.Saved,
        )
        val result = controller.open(target("settings.cfg"), currentLastUpdateTime = 2000L)
        assertTrue("was $result", result is ConfigOpenResult.Editable)
        assertEquals(UpdateDrift.NO_BASELINE, (result as ConfigOpenResult.Editable).updateDrift)
    }

    @Test
    fun `save commits the new bytes and backs up the original first`() = runBlocking {
        val original = "fov=70\n"
        val edited = "fov=110\n"
        shell.writeGameFile(gamePkg, gameUser, "settings.cfg", original.toByteArray())

        val result = controller.save(target("settings.cfg"), edited.toByteArray(), sourceLastUpdateTime = 1234L)

        assertTrue("was $result", result is ConfigSaveResult.Saved)
        assertEquals(edited.toByteArray().size.toLong(), (result as ConfigSaveResult.Saved).sizeBytes)
        assertArrayEquals(edited.toByteArray(), shell.gameFileBytes(gamePkg, gameUser, "settings.cfg"))
        assertFalse(shell.gameFileExists(gamePkg, gameUser, "settings.cfg.gc-tmp"))
        val original0 = dao.originals().single()
        assertEquals("settings.cfg", original0.relativePath)
        assertEquals(1234L, original0.sourceLastUpdateTime)
        assertArrayEquals(original.toByteArray(), File(original0.backupPath).readBytes())
        assertTrue(stagingIsEmpty())
    }

    @Test
    fun `a second save keeps the first original untouched`() = runBlocking {
        shell.writeGameFile(gamePkg, gameUser, "s.cfg", "v1".toByteArray())
        assertTrue(controller.save(target("s.cfg"), "v2".toByteArray()) is ConfigSaveResult.Saved)
        assertTrue(controller.save(target("s.cfg"), "v3".toByteArray()) is ConfigSaveResult.Saved)

        assertArrayEquals("v3".toByteArray(), shell.gameFileBytes(gamePkg, gameUser, "s.cfg"))
        val original0 = dao.originals().single()
        assertArrayEquals("v1".toByteArray(), File(original0.backupPath).readBytes())
    }

    @Test
    fun `save refuses to create a missing file`() = runBlocking {
        assertEquals(ConfigSaveResult.NotFound, controller.save(target("nope.cfg"), "x".toByteArray()))
        assertTrue(dao.originals().isEmpty())
    }

    @Test
    fun `save requires shizuku when the shell is unavailable`() = runBlocking {
        shell.available = false
        assertTrue(controller.save(target(), "x".toByteArray()) is ConfigSaveResult.RequiresShizuku)
    }

    @Test
    fun `a failed commit leaves the live file untouched and sweeps the temp`() = runBlocking {
        val original = "keep-me".toByteArray()
        shell.writeGameFile(gamePkg, gameUser, "c.cfg", original)
        shell.failOn = { cmd ->
            if (cmd is ShellCommand.MoveConfigFile) ShellResult.failure("mv denied", AccessLevel.SHIZUKU) else null
        }

        val result = controller.save(target("c.cfg"), "new".toByteArray())

        assertTrue("was $result", result is ConfigSaveResult.Failed)
        assertEquals(ConfigOpStage.COMMIT, (result as ConfigSaveResult.Failed).stage)
        assertArrayEquals(original, shell.gameFileBytes(gamePkg, gameUser, "c.cfg"))
        assertFalse(shell.gameFileExists(gamePkg, gameUser, "c.cfg.gc-tmp"))
        // The original was still secured before the doomed commit — the safety net does not depend on success.
        assertArrayEquals(original, File(dao.originals().single().backupPath).readBytes())
    }

    @Test
    fun `a failed copy-in aborts before any commit`() = runBlocking {
        val original = "orig".toByteArray()
        shell.writeGameFile(gamePkg, gameUser, "d.cfg", original)
        shell.failOn = { cmd ->
            if (cmd is ShellCommand.CopyConfigFile && cmd.to.endsWith(".gc-tmp")) {
                ShellResult.failure("cp denied", AccessLevel.SHIZUKU)
            } else {
                null
            }
        }

        val result = controller.save(target("d.cfg"), "new".toByteArray())

        assertTrue("was $result", result is ConfigSaveResult.Failed)
        assertEquals(ConfigOpStage.COPY_IN, (result as ConfigSaveResult.Failed).stage)
        assertArrayEquals(original, shell.gameFileBytes(gamePkg, gameUser, "d.cfg"))
    }

    @Test
    fun `save refuses outright when the original cannot be secured`() = runBlocking {
        shell.writeGameFile(gamePkg, gameUser, "e.cfg", "orig".toByteArray())
        shell.failOn = { cmd ->
            if (cmd is ShellCommand.CopyConfigFile && cmd.to.contains("configstaging")) {
                ShellResult.failure("cp out denied", AccessLevel.SHIZUKU)
            } else {
                null
            }
        }

        val result = controller.save(target("e.cfg"), "new".toByteArray())

        assertTrue("was $result", result is ConfigSaveResult.Failed)
        assertEquals(ConfigOpStage.BACKUP, (result as ConfigSaveResult.Failed).stage)
        assertTrue(dao.originals().isEmpty())
        assertArrayEquals("orig".toByteArray(), shell.gameFileBytes(gamePkg, gameUser, "e.cfg"))
        assertFalse(shell.gameFileExists(gamePkg, gameUser, "e.cfg.gc-tmp"))
    }

    @Test
    fun `an orphaned on-disk original is recorded with its own bytes, not the edited live file`() = runBlocking {
        // A `.orig` survives on disk but its ledger row is gone — the shape a whole-game "forget" leaves
        // if it drops rows without deleting files — and the live file has since been edited. The next save
        // must not overwrite that durable original, and the row it writes must describe the bytes actually
        // on disk (the true original), not the edited file it copies out now.
        val trueOriginal = "fov=70\n".toByteArray()
        val editedLive = "fov=999\n".toByteArray()
        shell.writeGameFile(gamePkg, gameUser, "o.cfg", editedLive)
        val backupRel = workspace.backupPath(gamePkg, gameUser, "o.cfg")
        workspace.writeAtomic(backupRel, trueOriginal) // orphaned original: on disk, no ledger row

        val result = controller.save(target("o.cfg"), "fov=42\n".toByteArray())

        assertTrue("was $result", result is ConfigSaveResult.Saved)
        val original0 = dao.originals().single()
        assertArrayEquals(trueOriginal, File(original0.backupPath).readBytes())
        assertEquals(trueOriginal.size.toLong(), original0.sizeBytes)
    }


    @Test
    fun `restore requires shizuku when the shell is unavailable`() = runBlocking {
        shell.available = false
        assertTrue(controller.restore(target(), 1L) is ConfigRestoreResult.RequiresShizuku)
    }

    @Test
    fun `restore reports not found for an unknown backup id`() = runBlocking {
        assertEquals(ConfigRestoreResult.NotFound, controller.restore(target(), 999L))
    }

    @Test
    fun `restore puts the backed-up original back`() = runBlocking {
        shell.writeGameFile(gamePkg, gameUser, "r.cfg", "v1".toByteArray())
        assertTrue(controller.save(target("r.cfg"), "v2".toByteArray()) is ConfigSaveResult.Saved)
        assertTrue(controller.save(target("r.cfg"), "v3".toByteArray()) is ConfigSaveResult.Saved)

        val originalId = dao.originals().single().id
        val result = controller.restore(target("r.cfg"), originalId)

        assertTrue("was $result", result is ConfigRestoreResult.Restored)
        assertArrayEquals("v1".toByteArray(), shell.gameFileBytes(gamePkg, gameUser, "r.cfg"))
        assertFalse(shell.gameFileExists(gamePkg, gameUser, "r.cfg.gc-tmp"))
        assertTrue(stagingIsEmpty())
    }

    @Test
    fun `restore brings back a checkpoint snapshot`() = runBlocking {
        shell.writeGameFile(gamePkg, gameUser, "k.cfg", "A".toByteArray())
        val captured = controller.checkpoint(target("k.cfg"), "good")
        assertTrue("was $captured", captured is ConfigCheckpointResult.Captured)
        assertTrue(controller.save(target("k.cfg"), "B".toByteArray()) is ConfigSaveResult.Saved)

        val result = controller.restore(target("k.cfg"), (captured as ConfigCheckpointResult.Captured).backupId)

        assertTrue("was $result", result is ConfigRestoreResult.Restored)
        assertArrayEquals("A".toByteArray(), shell.gameFileBytes(gamePkg, gameUser, "k.cfg"))
    }

    @Test
    fun `restore rejects a backup that belongs to another file`() = runBlocking {
        shell.writeGameFile(gamePkg, gameUser, "one.cfg", "one".toByteArray())
        shell.writeGameFile(gamePkg, gameUser, "two.cfg", "two".toByteArray())
        assertTrue(controller.save(target("one.cfg"), "one-edited".toByteArray()) is ConfigSaveResult.Saved)
        val foreignId = dao.originals().single().id

        val result = controller.restore(target("two.cfg"), foreignId)

        assertTrue("was $result", result is ConfigRestoreResult.Failed)
        assertEquals(ConfigOpStage.PATH_REJECTED, (result as ConfigRestoreResult.Failed).stage)
        assertArrayEquals("two".toByteArray(), shell.gameFileBytes(gamePkg, gameUser, "two.cfg"))
    }

    @Test
    fun `a failed restore commit leaves the live file untouched and sweeps the temp`() = runBlocking {
        shell.writeGameFile(gamePkg, gameUser, "rc.cfg", "v1".toByteArray())
        assertTrue(controller.save(target("rc.cfg"), "v2".toByteArray()) is ConfigSaveResult.Saved)
        val originalId = dao.originals().single().id
        shell.failOn = { cmd ->
            if (cmd is ShellCommand.MoveConfigFile) ShellResult.failure("mv denied", AccessLevel.SHIZUKU) else null
        }

        val result = controller.restore(target("rc.cfg"), originalId)

        assertTrue("was $result", result is ConfigRestoreResult.Failed)
        assertEquals(ConfigOpStage.COMMIT, (result as ConfigRestoreResult.Failed).stage)
        assertArrayEquals("v2".toByteArray(), shell.gameFileBytes(gamePkg, gameUser, "rc.cfg"))
        assertFalse(shell.gameFileExists(gamePkg, gameUser, "rc.cfg.gc-tmp"))
    }

    @Test
    fun `restore fails cleanly when the durable backup file is gone`() = runBlocking {
        shell.writeGameFile(gamePkg, gameUser, "g.cfg", "v1".toByteArray())
        assertTrue(controller.save(target("g.cfg"), "v2".toByteArray()) is ConfigSaveResult.Saved)
        val original = dao.originals().single()
        assertTrue(File(original.backupPath).delete()) // the ledger row survives its durable file

        val result = controller.restore(target("g.cfg"), original.id)

        assertTrue("was $result", result is ConfigRestoreResult.Failed)
        assertEquals(ConfigOpStage.BACKUP, (result as ConfigRestoreResult.Failed).stage)
        assertArrayEquals("v2".toByteArray(), shell.gameFileBytes(gamePkg, gameUser, "g.cfg"))
    }

    @Test
    fun `checkpoint requires shizuku when the shell is unavailable`() = runBlocking {
        shell.available = false
        assertTrue(controller.checkpoint(target(), "x") is ConfigCheckpointResult.RequiresShizuku)
    }

    @Test
    fun `checkpoint reports not found for a missing file`() = runBlocking {
        assertEquals(ConfigCheckpointResult.NotFound, controller.checkpoint(target("nope.cfg"), "x"))
        assertTrue(dao.checkpoints().isEmpty())
    }

    @Test
    fun `checkpoint captures a labeled snapshot without touching the original`() = runBlocking {
        shell.writeGameFile(gamePkg, gameUser, "c.cfg", "A".toByteArray())

        val result = controller.checkpoint(target("c.cfg"), "my point")

        assertTrue("was $result", result is ConfigCheckpointResult.Captured)
        result as ConfigCheckpointResult.Captured
        assertEquals("my point", result.label)
        assertEquals(64, result.sha256.length)
        val row = dao.checkpoints().single()
        assertEquals("my point", row.label)
        assertArrayEquals("A".toByteArray(), File(row.backupPath).readBytes())
        assertTrue(dao.originals().isEmpty())
        assertTrue(stagingIsEmpty())
    }

    @Test
    fun `checkpoint sanitises the label on the way in`() = runBlocking {
        shell.writeGameFile(gamePkg, gameUser, "s.cfg", "A".toByteArray())

        val result = controller.checkpoint(target("s.cfg"), "clean\u200Bpoint")

        assertTrue("was $result", result is ConfigCheckpointResult.Captured)
        val stored = dao.checkpoints().single().label
        assertEquals("cleanpoint", stored)
    }
    @Test
    fun `checkpoint does not record an original, and a later save still captures the true original`() = runBlocking {
        shell.writeGameFile(gamePkg, gameUser, "d.cfg", "A".toByteArray())

        assertTrue(controller.checkpoint(target("d.cfg"), "snap") is ConfigCheckpointResult.Captured)
        assertTrue("a checkpoint is not an original", dao.originals().isEmpty())
        assertEquals(1, dao.checkpoints().size)

        // The live file is untouched by the checkpoint, so the first save still backs up the real original.
        assertTrue(controller.save(target("d.cfg"), "B".toByteArray()) is ConfigSaveResult.Saved)
        assertArrayEquals("A".toByteArray(), File(dao.originals().single().backupPath).readBytes())
        assertEquals(1, dao.checkpoints().size)
    }

    @Test
    fun `two checkpoints of one file both persist with distinct paths`() = runBlocking {
        shell.writeGameFile(gamePkg, gameUser, "t.cfg", "A".toByteArray())

        val first = controller.checkpoint(target("t.cfg"), "one")
        val second = controller.checkpoint(target("t.cfg"), "two")

        assertTrue(first is ConfigCheckpointResult.Captured)
        assertTrue(second is ConfigCheckpointResult.Captured)
        first as ConfigCheckpointResult.Captured
        second as ConfigCheckpointResult.Captured
        assertTrue("distinct backup ids", first.backupId != second.backupId)

        val rows = dao.checkpoints()
        assertEquals(2, rows.size)
        assertEquals("both rows have distinct backup paths", 2, rows.map { it.backupPath }.toSet().size)
        rows.forEach { assertArrayEquals("A".toByteArray(), File(it.backupPath).readBytes()) }
    }

    /** True when no per-operation staging directory was left behind under GameCore's files root. */
    private fun stagingIsEmpty(): Boolean =
        gameCoreRoot.resolve("configstaging").listFiles().isNullOrEmpty()




}

/**
 * A fake [ElevatedShell] that carries out the config commands against real files in two temp trees, one
 * for each side of a copy. It parses the absolute path each [ShellCommand] carries back into a real
 * [File]: GameCore's own package maps under [gameCoreFilesRoot] — so the file the controller reads
 * locally is the very one this wrote — and any other package under a per-app subtree of [sandboxRoot].
 */
private class FakeShell(
    private val gameCoreFilesRoot: File,
    private val sandboxRoot: File,
) : ElevatedShell {

    var available = true

    /** Force a chosen command to fail; return null to run it normally. Drives the abort-path tests. */
    var failOn: (ShellCommand) -> ShellResult? = { null }

    val executed = mutableListOf<ShellCommand>()

    override val accessLevel = AccessLevel.SHIZUKU

    override suspend fun isAvailable(): Boolean = available

    override suspend fun execute(command: ShellCommand, timeoutMillis: Long): ShellResult {
        executed += command
        failOn(command)?.let { return it }
        return when (command) {
            is ShellCommand.StatConfigFile -> stat(command.path)
            is ShellCommand.CopyConfigFile -> copy(command.from, command.to)
            is ShellCommand.MoveConfigFile -> move(command.from, command.to)
            is ShellCommand.DeleteConfigFile -> delete(command.path)
            else -> ShellResult.failure("unsupported in fake: $command", accessLevel)
        }
    }

    private fun ok(stdout: String = ""): ShellResult = ShellResult(0, stdout, "", accessLevel)
    private fun fail(detail: String): ShellResult = ShellResult(1, "", detail, accessLevel)

    private fun stat(path: String): ShellResult {
        val f = realFile(path)
        return when {
            f.isFile -> ok("${f.length()}|regular file")
            f.isDirectory -> ok("${f.length()}|directory")
            else -> fail("stat: no such file")
        }
    }

    private fun copy(from: String, to: String): ShellResult {
        val src = realFile(from)
        if (!src.isFile) return fail("cp: source missing")
        val dst = realFile(to)
        dst.parentFile?.mkdirs()
        src.copyTo(dst, overwrite = true)
        return ok()
    }

    private fun move(from: String, to: String): ShellResult {
        val src = realFile(from)
        if (!src.isFile) return fail("mv: source missing")
        val dst = realFile(to)
        dst.parentFile?.mkdirs()
        dst.delete()
        if (!src.renameTo(dst)) {
            src.copyTo(dst, overwrite = true)
            src.delete()
        }
        return ok()
    }

    private fun delete(path: String): ShellResult {
        realFile(path).delete() // rm -f: an already-absent file is not an error
        return ok()
    }

    /** Map the absolute shell path back to the real temp file standing in for it. */
    private fun realFile(shellPath: String): File {
        val match = PATH.matchEntire(shellPath)
            ?: throw IllegalArgumentException("unparseable shell path: $shellPath")
        val (user, pkg, rel) = match.destructured
        return if (pkg == BuildConfig.APPLICATION_ID) {
            File(gameCoreFilesRoot, rel)
        } else {
            File(File(sandboxRoot, "$pkg/$user"), rel)
        }
    }

    fun writeGameFile(pkg: String, userId: Int, rel: String, bytes: ByteArray) {
        File(File(sandboxRoot, "$pkg/$userId"), rel).apply { parentFile?.mkdirs() }.writeBytes(bytes)
    }

    fun gameFileBytes(pkg: String, userId: Int, rel: String): ByteArray? =
        File(File(sandboxRoot, "$pkg/$userId"), rel).takeIf { it.isFile }?.readBytes()

    fun gameFileExists(pkg: String, userId: Int, rel: String): Boolean =
        File(File(sandboxRoot, "$pkg/$userId"), rel).isFile

    private companion object {
        val PATH = Regex("""^/storage/emulated/(\d+)/Android/data/([^/]+)/files/(.+)$""")
    }


}

/**
 * An in-memory [ConfigBackupDao] so the real [ConfigBackupRepository] runs unchanged. [insertIfAbsent]
 * honours the unique-`backup_path` conflict the same way Room does — a second insert on a taken path is
 * ignored and reports -1 — which is exactly what makes the repository's first-original-wins rule real.
 */
private class FakeConfigBackupDao : ConfigBackupDao {

    private val rows = mutableListOf<ConfigBackupEntity>()
    private var nextId = 1L

    override fun observeForApp(packageName: String, userId: Int): Flow<List<ConfigBackupEntity>> =
        flowOf(rows.filter { it.packageName == packageName && it.userId == userId })

    override suspend fun forFile(packageName: String, userId: Int, relativePath: String): List<ConfigBackupEntity> =
        rows.filter { it.packageName == packageName && it.userId == userId && it.relativePath == relativePath }

    override suspend fun original(
        packageName: String,
        userId: Int,
        relativePath: String,
        kind: String,
    ): ConfigBackupEntity? = rows.firstOrNull {
        it.packageName == packageName && it.userId == userId && it.relativePath == relativePath && it.kind == kind
    }

    override suspend fun byId(id: Long): ConfigBackupEntity? = rows.firstOrNull { it.id == id }

    override suspend fun countForApp(packageName: String, userId: Int): Int =
        rows.count { it.packageName == packageName && it.userId == userId }

    override suspend fun insertIfAbsent(backup: ConfigBackupEntity): Long {
        if (rows.any { it.backupPath == backup.backupPath }) return -1L
        val assigned = nextId++
        rows += backup.copy(id = assigned)
        return assigned
    }

    override suspend fun delete(id: Long) {
        rows.removeAll { it.id == id }
    }

    override suspend fun deleteForApp(packageName: String, userId: Int) {
        rows.removeAll { it.packageName == packageName && it.userId == userId }
    }

    /** Every stored original, for asserting there is exactly one and it holds the untouched bytes. */
    fun originals(): List<ConfigBackupEntity> = rows.filter { it.kind == ConfigBackupKind.ORIGINAL.name }

    /** Every stored user checkpoint, for asserting captures persist without displacing the original. */
    fun checkpoints(): List<ConfigBackupEntity> = rows.filter { it.kind == ConfigBackupKind.CHECKPOINT.name }
}
