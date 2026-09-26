package com.gamecore.data.database

import com.gamecore.core.common.TextSanitizer
import com.gamecore.core.model.ConfigBackup
import com.gamecore.core.model.ConfigBackupKind

/**
 * [ConfigBackupEntity] ↔ [ConfigBackup] conversion, and the two rules [Mappers] enforces applied to
 * this table: enum names are parsed by name with a sane fallback rather than [valueOf], and the one
 * piece of user text — a checkpoint's label — is sanitised on the way in.
 *
 * The [ConfigBackupKind] fallback is [ConfigBackupKind.ORIGINAL], and the direction is deliberate: a
 * row whose kind a newer build wrote, or a hand-edited one, is read as the copy that must be *kept*,
 * never as a checkpoint the app may prune. Failing safe here means never mistaking the one untouched
 * original for something disposable.
 */
internal object ConfigBackupMappers {

    fun toEntity(backup: ConfigBackup): ConfigBackupEntity = ConfigBackupEntity(
        id = backup.id,
        packageName = backup.packageName,
        userId = backup.userId,
        relativePath = backup.relativePath,
        backupPath = backup.backupPath,
        kind = backup.kind.name,
        // The label is the only free text here and it is drawn into the backup list, so it is
        // sanitised before storage on the one path in — the same discipline profile and preset names
        // follow. A blank label after sanitising is stored as null: "no label" and "" are one state.
        label = backup.label
            ?.let { TextSanitizer.sanitizeName(it, TextSanitizer.MAX_NAME_LENGTH) }
            ?.takeIf { it.isNotBlank() },
        sizeBytes = backup.sizeBytes,
        sha256 = backup.sha256,
        sourceLastUpdateTime = backup.sourceLastUpdateTime,
        createdAtMillis = backup.createdAtMillis,
    )

    fun toModel(entity: ConfigBackupEntity): ConfigBackup = ConfigBackup(
        id = entity.id,
        packageName = entity.packageName,
        userId = entity.userId,
        relativePath = entity.relativePath,
        backupPath = entity.backupPath,
        // Matched by name, never `valueOf`; an unrecognised kind reads as the protected original
        // rather than crashing the list or, worse, being taken for a disposable checkpoint.
        kind = ConfigBackupKind.entries.firstOrNull { it.name == entity.kind }
            ?: ConfigBackupKind.ORIGINAL,
        label = entity.label,
        sizeBytes = entity.sizeBytes,
        sha256 = entity.sha256,
        sourceLastUpdateTime = entity.sourceLastUpdateTime,
        createdAtMillis = entity.createdAtMillis,
    )
}
