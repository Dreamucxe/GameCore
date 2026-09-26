package com.gamecore.data.database

import com.gamecore.core.model.ConfigBackup
import com.gamecore.core.model.ConfigBackupKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The config-backup mapper keeps the two promises [ConfigBackupMappers] makes: a backup survives the
 * round trip unchanged, and a row it cannot fully understand fails safe rather than throwing.
 *
 * The migration itself is instrumented and lives in the androidTest set (as [ProfileSmartFeatureMapperTest]
 * notes for its own table); what is unit-testable, and what protects the user, is the mapper. The one that
 * matters most here is the [ConfigBackupKind] fallback: read the wrong way, an unrecognised kind would let
 * the app treat the one untouched original as a disposable checkpoint.
 */
class ConfigBackupMapperTest {

    private fun original() = ConfigBackup(
        id = 7L,
        packageName = "com.example.game",
        userId = 0,
        relativePath = "shared_prefs/settings.xml",
        backupPath = "/data/user/0/com.gamecore/files/configbackups/com.example.game/settings.xml.orig",
        kind = ConfigBackupKind.ORIGINAL,
        label = null,
        sizeBytes = 2_048L,
        sha256 = "abc123",
        sourceLastUpdateTime = 1_700_000_000_000L,
        createdAtMillis = 1_700_000_100_000L,
    )

    @Test
    fun `a backup round-trips through the entity unchanged`() {
        val restored = ConfigBackupMappers.toModel(ConfigBackupMappers.toEntity(original()))
        // id is preserved by the mapper; the repository is what resets it to UNSAVED before an insert.
        assertEquals(original(), restored)
    }

    @Test
    fun `a checkpoint keeps its kind and label`() {
        val checkpoint = original().copy(
            id = 0L,
            kind = ConfigBackupKind.CHECKPOINT,
            label = "before the fov change",
        )
        val restored = ConfigBackupMappers.toModel(ConfigBackupMappers.toEntity(checkpoint))
        assertEquals(ConfigBackupKind.CHECKPOINT, restored.kind)
        assertEquals("before the fov change", restored.label)
    }

    @Test
    fun `an unrecognised kind reads back as the protected original`() {
        val entity = ConfigBackupMappers.toEntity(original()).copy(kind = "SOME_FUTURE_KIND")
        assertEquals(ConfigBackupKind.ORIGINAL, ConfigBackupMappers.toModel(entity).kind)
        assertTrue(ConfigBackupMappers.toModel(entity).isOriginal)
    }

    @Test
    fun `a blank label is stored as absent`() {
        val entity = ConfigBackupMappers.toEntity(original().copy(label = "   "))
        assertNull(entity.label)
    }

    @Test
    fun `every kind name survives the round trip`() {
        // Guards the enum-by-name storage: if a constant were renamed, its stored name would stop
        // matching and this would fall through to the ORIGINAL fallback, failing here.
        ConfigBackupKind.entries.forEach { kind ->
            val entity = ConfigBackupMappers.toEntity(original().copy(kind = kind))
            assertSame(kind, ConfigBackupMappers.toModel(entity).kind)
        }
    }
}
