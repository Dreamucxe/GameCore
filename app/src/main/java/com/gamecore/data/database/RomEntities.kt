package com.gamecore.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One file the user added to the emulator frontend's library.
 *
 * The URI is a persisted `content://` document, not a path: under scoped storage a path is not a permission,
 * and a row that stored one would look fine until the next process start and then fail to open. [system] is
 * the family the user confirmed rather than a value guessed from the extension on every read, because four
 * families share `.iso` and re-guessing would silently change a file's meaning after an app update.
 *
 * [emulatorPackage] is a remembered preference, not a compatibility proof. The launcher re-checks the
 * installed package and its declared systems every time; an uninstalled or incompatible choice falls back
 * to the ranked list instead of producing a dead button.
 *
 * [sizeBytes] is nullable because `OpenableColumns.SIZE` is optional. NULL means the provider did not say,
 * which is rendered as "size unknown" rather than as a zero-byte file.
 */
@Entity(
    tableName = "rom_library",
    indices = [
        Index(value = ["uri"], unique = true),
        Index("system"),
        Index("added_at"),
    ],
)
data class RomEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "uri")
    val uri: String,

    @ColumnInfo(name = "display_name")
    val displayName: String,

    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long?,

    /** The lower-case suffix without a dot; stored rather than recomputed from a possibly renamed file. */
    @ColumnInfo(name = "extension")
    val extension: String,

    /** An [com.gamecore.core.emulator.EmulatorSystem] name, parsed defensively on read. */
    @ColumnInfo(name = "system")
    val system: String,

    @ColumnInfo(name = "emulator_package")
    val emulatorPackage: String?,

    @ColumnInfo(name = "added_at")
    val addedAtMillis: Long,

    @ColumnInfo(name = "last_launched_at")
    val lastLaunchedAtMillis: Long?,

    @ColumnInfo(name = "launch_count")
    val launchCount: Int,
)
