package com.gamecore.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.common.TextSanitizer
import com.gamecore.core.emulator.EmulatorRegistry
import com.gamecore.core.emulator.EmulatorSystem
import com.gamecore.core.emulator.RomDraft
import com.gamecore.core.emulator.RomEntry
import com.gamecore.core.emulator.RomFile
import com.gamecore.data.database.RomDao
import com.gamecore.data.database.RomEntity
import com.gamecore.data.database.RomMappers
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** What reading a picked document produced: a draft to confirm, or a reason it cannot be used. */
sealed interface RomPrepareResult {
    data class Prepared(val draft: RomDraft) : RomPrepareResult
    data class Rejected(val reason: String) : RomPrepareResult
}

/** What adding a confirmed draft produced. */
sealed interface RomAddResult {
    data class Added(val id: Long, val system: EmulatorSystem) : RomAddResult
    data class AlreadyAdded(val existing: RomEntry?) : RomAddResult
    data class Rejected(val reason: String) : RomAddResult
}

/** A write that changes an existing row. */
sealed interface RomWriteResult {
    data object Saved : RomWriteResult
    data class Rejected(val reason: String) : RomWriteResult
    data class Failed(val detail: String) : RomWriteResult
}

/**
 * The ROM library and the persisted document grants behind it.
 *
 * The repository is the only place a picked URI becomes a row. It validates the extension, keeps the
 * grant with `takePersistableUriPermission`, and refuses to store a URI it cannot read again — a row that
 * survives a reboot but cannot be opened is worse than a rejection the user can act on.
 *
 * It never copies a file into GameCore's storage. A disc image can be many gigabytes, and a copy would
 * double the space a user needs and silently detach the library from the file they actually update. The
 * library stores the document URI and the metadata the provider exposes.
 */
@Singleton
class RomLibraryRepository @Inject constructor(
    private val dao: RomDao,
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * The library, newest first.
     *
     * A database read failure is an empty list with no invented rows. The screen carries a message of its
     * own for that case; a flow that threw would leave the user looking at a spinner forever.
     */
    val roms: Flow<List<RomEntry>> = dao.observeAll()
        .map { rows -> rows.mapNotNull(RomMappers::toModel) }
        .catch { emit(emptyList()) }

    /**
     * Reads the display name, size and extension of a picked document.
     *
     * `OpenableColumns` is queried rather than guessed from the URI's last segment, but the last segment is
     * a fallback for providers that expose no columns at all. The display name is sanitised before it can
     * be stored: it is another app's string and is drawn in a list row.
     */
    suspend fun prepare(uri: Uri): RomPrepareResult = withContext(io) {
        val resolver = context.contentResolver
        var providerName: String? = null
        var providerSize: Long? = null
        runCatching {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                        providerName = cursor.getString(nameIndex)
                    }
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        providerSize = cursor.getLong(sizeIndex)
                    }
                }
            }
        }.onFailure { error ->
            return@withContext RomPrepareResult.Rejected(
                "GameCore could not read that file (${error.javaClass.simpleName}). Pick it again and " +
                    "allow access when Android asks.",
            )
        }

        val fallbackName = uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.substringAfterLast(':')
            ?.takeIf { it.isNotBlank() }
        val displayName = TextSanitizer.sanitizeName(
            providerName ?: fallbackName,
            maxLength = MAX_DISPLAY_NAME,
        ).trim()
        if (displayName.isEmpty()) {
            return@withContext RomPrepareResult.Rejected(
                "That file has no readable name, so GameCore cannot tell what system it belongs to.",
            )
        }

        val extension = RomFile.extensionOf(displayName)
            ?: return@withContext RomPrepareResult.Rejected(
                "\"$displayName\" has no file extension. GameCore needs one to recognise the system.",
            )
        if (!RomFile.isRecognised(extension)) {
            return@withContext RomPrepareResult.Rejected(
                ".$extension is not a ROM format GameCore recognises. Supported examples: .iso, .chd, " +
                    ".gcm, .rvz, .cso, .nsp, .nds, .gba, .exe.",
            )
        }

        RomPrepareResult.Prepared(
            RomDraft(
                uri = uri.toString(),
                displayName = displayName,
                sizeBytes = providerSize?.takeIf { it >= 0L },
                extension = extension,
            ),
        )
    }

    /**
     * Stores a confirmed draft under [system].
     *
     * [system] is allowed to differ from the extension's candidates: a `.zip` may be an arcade set or a
     * zipped cartridge ROM, and the emulator's own picker remains the authority. What is not allowed is
     * [EmulatorSystem.UNKNOWN] — a row whose system cannot be named cannot be matched to an emulator.
     */
    suspend fun add(draft: RomDraft, system: EmulatorSystem): RomAddResult = withContext(io) {
        if (system == EmulatorSystem.UNKNOWN) {
            return@withContext RomAddResult.Rejected("Choose a system before adding this file.")
        }
        val uri = runCatching { Uri.parse(draft.uri) }.getOrNull()
            ?: return@withContext RomAddResult.Rejected("That file's address could not be read.")

        val existing = dao.byUri(draft.uri)
        if (existing != null) {
            return@withContext RomAddResult.AlreadyAdded(RomMappers.toModel(existing))
        }

        if (!persistReadAccess(uri)) {
            return@withContext RomAddResult.Rejected(
                "GameCore could not keep access to this file after a restart. Pick it from a provider " +
                    "that supports persistent document access, such as the system Files app.",
            )
        }

        val entity = RomEntity(
            uri = draft.uri,
            displayName = draft.displayName,
            sizeBytes = draft.sizeBytes,
            extension = draft.extension,
            system = system.name,
            emulatorPackage = null,
            addedAtMillis = System.currentTimeMillis(),
            lastLaunchedAtMillis = null,
            launchCount = 0,
        )
        val id = runCatching { dao.insert(entity) }.getOrElse { error ->
            releaseReadAccess(uri)
            return@withContext RomAddResult.Rejected(
                "The library could not save that file (${error.javaClass.simpleName}).",
            )
        }
        if (id <= 0L) {
            // The unique index won a race with another picker. The existing row already owns the grant,
            // so it is deliberately not released here.
            return@withContext RomAddResult.AlreadyAdded(
                dao.byUri(draft.uri)?.let(RomMappers::toModel),
            )
        }
        RomAddResult.Added(id = id, system = system)
    }

    /** Changes the family without touching the file's grant or launch history. */
    suspend fun assignSystem(id: Long, system: EmulatorSystem): RomWriteResult = withContext(io) {
        if (id <= 0L) return@withContext RomWriteResult.Rejected("That row has no id.")
        if (system == EmulatorSystem.UNKNOWN) {
            return@withContext RomWriteResult.Rejected("Choose a system before saving.")
        }
        runCatching { dao.updateSystem(id, system.name) }.fold(
            onSuccess = { RomWriteResult.Saved },
            onFailure = { RomWriteResult.Failed(it.javaClass.simpleName) },
        )
    }

    /**
     * Remembers the emulator the user picked for one file.
     *
     * A package that is unknown to [EmulatorRegistry] or does not name this file's system is rejected
     * rather than stored. The stored value is a shortcut, and a shortcut to an app that cannot open the
     * file would make the row look broken on the next launch.
     */
    suspend fun setPreferredEmulator(id: Long, packageName: String?): RomWriteResult = withContext(io) {
        if (packageName != null) {
            val descriptor = EmulatorRegistry.descriptorFor(packageName)
                ?: return@withContext RomWriteResult.Rejected("GameCore does not know that emulator.")
            val row = dao.byId(id) ?: return@withContext RomWriteResult.Rejected("That ROM is no longer saved.")
            val system = EmulatorSystem.fromStoredName(row.system)
            if (system !in descriptor.systems) {
                return@withContext RomWriteResult.Rejected(
                    "${descriptor.displayName} does not handle ${system.label}.",
                )
            }
        }
        runCatching { dao.updateEmulator(id, packageName) }.fold(
            onSuccess = { RomWriteResult.Saved },
            onFailure = { RomWriteResult.Failed(it.javaClass.simpleName) },
        )
    }

    /** Counts a launch only after the intent was accepted by Android. */
    suspend fun recordLaunch(id: Long): RomWriteResult = withContext(io) {
        if (id <= 0L) return@withContext RomWriteResult.Rejected("That row has no id.")
        runCatching { dao.recordLaunch(id, System.currentTimeMillis()) }.fold(
            onSuccess = { RomWriteResult.Saved },
            onFailure = { RomWriteResult.Failed(it.javaClass.simpleName) },
        )
    }

    /** Forgets a row. The persisted grant is released so the file is no longer readable by GameCore. */
    suspend fun remove(id: Long): RomWriteResult = withContext(io) {
        if (id <= 0L) return@withContext RomWriteResult.Rejected("That row has no id.")
        val row = dao.byId(id)
        runCatching { dao.delete(id) }.fold(
            onSuccess = {
                row?.uri?.let { stored ->
                    runCatching { Uri.parse(stored) }.getOrNull()?.let(::releaseReadAccess)
                }
                RomWriteResult.Saved
            },
            onFailure = { RomWriteResult.Failed(it.javaClass.simpleName) },
        )
    }

    // ---------------------------------------------------------------------- document grants

    private fun persistReadAccess(uri: Uri): Boolean = try {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        true
    } catch (error: SecurityException) {
        false
    } catch (error: Throwable) {
        false
    }

    private fun releaseReadAccess(uri: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private companion object {
        /**
         * Longer than a normal label because a ROM's file name is the user's own text and often carries a
         * game title, a region and a revision. Still bounded, because it is written into a list row.
         */
        const val MAX_DISPLAY_NAME = 120
    }
}
