package com.gamecore.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.gamecore.core.common.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place GameCore turns an in-memory export into a file the user can open elsewhere.
 *
 * Every file-based feature — the session CSV, the per-sample CSV (§19), the profile envelope (§5),
 * the config backup (§20) — needs the same four things and got them wrong in four different ways
 * before this existed: a private subdirectory of the app's own `files/`, a bound on how many copies
 * pile up there, a `content://` URI handed out with a one-shot read grant (which is why the app asks
 * for no storage permission on any API level), and an exception funnelled into a typed result instead
 * of a crash. That mechanism lives here; each feature keeps only its own format.
 *
 * Nothing here decides *what* to write or *whether* there is anything to write — an "empty export"
 * is the caller's judgement, made before it calls, because only the caller knows what empty means.
 */
@Singleton
class FileShareStore @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * Writes [text] to `files/<subdir>/<fileName>`, keeping at most [keep] files that match [accept].
     *
     * The prune runs before the write and keeps `keep - 1`, so the file just written is the [keep]th —
     * the newest is never a candidate for its own eviction. A failure at any step (no space, a
     * directory that would not create, a provider that refuses the URI on [shareUri]) becomes
     * [FileWriteResult.Failed] carrying only the exception's class name: never a path or a message,
     * either of which can carry the very user data the export is about.
     */
    suspend fun writeText(
        subdir: String,
        fileName: String,
        text: String,
        keep: Int = DEFAULT_KEEP,
        accept: (File) -> Boolean,
    ): FileWriteResult = write(subdir, fileName, keep, accept) { it.write(text) }

    /**
     * Streaming sibling of [writeText] for payloads too large to hold twice in memory (per-sample
     * exports run to tens of thousands of rows). [body] receives a buffered writer and appends rows;
     * the buffer is flushed and closed by the caller of [body], not by [body] itself.
     */
    suspend fun writeStreaming(
        subdir: String,
        fileName: String,
        keep: Int = DEFAULT_KEEP,
        accept: (File) -> Boolean,
        body: (BufferedWriter) -> Unit,
    ): FileWriteResult = write(subdir, fileName, keep, accept, body)

    private suspend fun write(
        subdir: String,
        fileName: String,
        keep: Int,
        accept: (File) -> Boolean,
        body: (BufferedWriter) -> Unit,
    ): FileWriteResult = withContext(io) {
        try {
            val directory = File(context.filesDir, subdir).apply { if (!exists()) mkdirs() }
            prune(directory, keep, accept)
            val file = File(directory, fileName)
            file.bufferedWriter().use(body)
            FileWriteResult.Stored(file = file, uri = shareUri(file))
        } catch (error: Throwable) {
            FileWriteResult.Failed(error.javaClass.simpleName)
        }
    }

    /**
     * Keeps the newest [keep] files matching [accept] and deletes the rest.
     *
     * An export is a convenience copy of data already held elsewhere, so an unbounded pile of them in
     * the app's own storage is a slow leak the user never sees and cannot clear from this screen.
     */
    fun prune(directory: File, keep: Int, accept: (File) -> Boolean) {
        val existing = directory.listFiles()
            ?.filter { it.isFile && accept(it) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        existing.drop((keep - 1).coerceAtLeast(0)).forEach { runCatching { it.delete() } }
    }

    /**
     * A one-shot read grant for [file] through GameCore's own `FileProvider`, or null.
     *
     * Null is not a failure the caller must escalate: the file exists and is listed either way, and a
     * provider that declines a URI should not turn a good write into a bad one. Callers offer the share
     * sheet only when this is non-null.
     */
    fun shareUri(file: File): Uri? = try {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    } catch (error: Throwable) {
        null
    }

    /** A share-sheet intent for a [uri] previously obtained from [shareUri]. */
    fun buildShareIntent(uri: Uri, mimeType: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    companion object {
        const val DEFAULT_KEEP = 5

        /** Accepts files ending in [extension] (e.g. `.csv`, `.json`) — the common [prune] predicate. */
        fun byExtension(extension: String): (File) -> Boolean = { it.name.endsWith(extension) }
    }
}

/**
 * What a single write attempt did.
 *
 * [Stored.uri] is nullable on purpose (see [FileShareStore.shareUri]); [Failed.detail] is an exception
 * class name, never a path or message. Each feature maps this to its own richer result — the session
 * exporter adds a row count, the backup writer an entry count — because the count is format-specific
 * and the file mechanics are not.
 */
sealed interface FileWriteResult {
    data class Stored(val file: File, val uri: Uri?) : FileWriteResult
    data class Failed(val detail: String) : FileWriteResult
}
