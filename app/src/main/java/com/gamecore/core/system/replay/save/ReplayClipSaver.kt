package com.gamecore.core.system.replay.save

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.common.TextSanitizer
import com.gamecore.domain.gaming.replay.ReplaySegment
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns the retained rolling-buffer segments into ONE clip in the user's gallery (audit A6).
 *
 * This is the first and only MediaStore writer in the app, and it is the thin Android seam over the
 * pure [com.gamecore.domain.gaming.replay] selection logic: it receives an **already ordered** list of
 * [ReplaySegment] (from `SaveSelection.select(...)`) and does no re-selection or re-ordering of its own.
 *
 * Three stages, each of which can fail cleanly into a [ReplaySaveResult.Failed]:
 *
 *  1. **Stitch** — a `MediaMuxer` remux (never a re-encode) of the same-codec / same-resolution segment
 *     mp4s into one file in the private replay cache. Tracks are read from the first usable segment,
 *     samples are copied verbatim, and presentation timestamps are offset segment-to-segment so the clip
 *     plays as one continuous timeline. Video-only in practice, but an audio track is copied if a segment
 *     already carries one, so the code stays correct if that ever changes.
 *  2. **Publish** — on API 29+ insert a `MediaStore.Video.Media` row under `Movies/GameCore` with
 *     `IS_PENDING = 1`, stream the bytes in, then clear the pending flag (no storage permission needed).
 *     On 26–28, where scoped MediaStore writes are unavailable, fall back to the app's own
 *     `files/recordings/` directory and vend a `FileProvider` grant using the existing pattern.
 *  3. **Confirm** — re-open the published clip and assert a non-zero, sane byte count *before* reporting
 *     success. A publish that left an empty or truncated item is deleted and reported as
 *     [ReplaySaveResult.Reason.EmptyOutput]; there is no fake success.
 *
 * Nothing here is logged: failures surface as a typed [ReplaySaveResult.Failed] carrying at most an
 * exception's class name, so no path or footage content can reach a release logcat.
 */
@Singleton
class ReplayClipSaver @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * Stitches [segments] (already ordered ascending by start) into one clip named after [displayName].
     *
     * Safe to call from any coroutine: the whole pipeline runs on the injected IO dispatcher. The
     * stitched temporary file is always deleted, whether the save succeeds or fails.
     */
    suspend fun save(
        segments: List<ReplaySegment>,
        displayName: String,
    ): ReplaySaveResult = withContext(io) {
        if (segments.isEmpty()) {
            return@withContext ReplaySaveResult.Failed(ReplaySaveResult.Reason.NoSegments)
        }
        val fileName = buildFileName(displayName)
        val stitched = File(replayCacheDir(), "stitch-${System.nanoTime()}.mp4")
        try {
            when (val outcome = remux(segments, stitched)) {
                is StitchOutcome.Failed ->
                    return@withContext ReplaySaveResult.Failed(
                        ReplaySaveResult.Reason.StitchFailed,
                        outcome.detail,
                    )
                StitchOutcome.Ok -> Unit
            }
            if (!stitched.isFile || stitched.length() < MIN_OUTPUT_BYTES) {
                return@withContext ReplaySaveResult.Failed(ReplaySaveResult.Reason.EmptyOutput)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                publishToMediaStore(stitched, fileName)
            } else {
                publishToPrivateFile(stitched, fileName)
            }
        } catch (error: Throwable) {
            ReplaySaveResult.Failed(ReplaySaveResult.Reason.Unexpected, error.javaClass.simpleName)
        } finally {
            runCatching { stitched.delete() }
        }
    }

    // ------------------------------------------------------------------- stitch

    private sealed interface StitchOutcome {
        data object Ok : StitchOutcome
        data class Failed(val detail: String?) : StitchOutcome
    }

    /**
     * Remuxes every segment head-to-tail into [output] with no re-encode.
     *
     * The muxer's tracks are established from the first segment that opens; later segments map their
     * tracks onto those by MIME (exact first, then same family — video→video, audio→audio) so a segment
     * whose format string differs trivially still lands on the right track. A segment that will not open
     * or exposes no usable track is skipped rather than aborting the whole clip. Presentation timestamps
     * are shifted by a running offset so each segment continues the previous one's timeline; a small
     * fixed gap between segments keeps per-track timestamps strictly increasing, which the muxer requires.
     */
    private fun remux(segments: List<ReplaySegment>, output: File): StitchOutcome {
        output.parentFile?.mkdirs()
        val muxer = try {
            MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (error: Throwable) {
            return StitchOutcome.Failed(error.javaClass.simpleName)
        }

        var muxerStarted = false
        var wroteAnySample = false
        val muxerTrackByMime = HashMap<String, Int>()
        var buffer = ByteBuffer.allocate(DEFAULT_BUFFER_BYTES)
        val info = MediaCodec.BufferInfo()
        var globalOffsetUs = 0L

        try {
            for (segment in segments) {
                val extractor = MediaExtractor()
                val opened = runCatching { extractor.setDataSource(segment.path) }.isSuccess
                if (!opened) {
                    runCatching { extractor.release() }
                    continue
                }
                try {
                    val trackMap = HashMap<Int, Int>()
                    var requiredBytes = 0
                    for (t in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(t)
                        val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                        if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                        val muxTrack = if (!muxerStarted) {
                            muxerTrackByMime.getOrPut(mime) { muxer.addTrack(format) }
                        } else {
                            muxerTrackByMime[mime]
                                ?: muxerTrackByMime.entries.firstOrNull { sameFamily(it.key, mime) }?.value
                        }
                        if (muxTrack != null) {
                            trackMap[t] = muxTrack
                            requiredBytes = maxOf(requiredBytes, inputSizeOf(format))
                        }
                    }
                    if (trackMap.isEmpty()) continue

                    if (!muxerStarted) {
                        muxer.start()
                        muxerStarted = true
                    }
                    if (buffer.capacity() < requiredBytes) {
                        buffer = ByteBuffer.allocate(requiredBytes.coerceAtMost(MAX_BUFFER_BYTES))
                    }
                    trackMap.keys.forEach { extractor.selectTrack(it) }

                    var segmentMaxOutUs = globalOffsetUs
                    while (true) {
                        val sourceTrack = extractor.sampleTrackIndex
                        if (sourceTrack < 0) break
                        val muxTrack = trackMap[sourceTrack]
                        if (muxTrack == null) {
                            if (!extractor.advance()) break else continue
                        }
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break
                        info.offset = 0
                        info.size = size
                        info.presentationTimeUs = globalOffsetUs + extractor.sampleTime
                        info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                            MediaCodec.BUFFER_FLAG_KEY_FRAME
                        } else {
                            0
                        }
                        muxer.writeSampleData(muxTrack, buffer, info)
                        wroteAnySample = true
                        if (info.presentationTimeUs > segmentMaxOutUs) segmentMaxOutUs = info.presentationTimeUs
                        if (!extractor.advance()) break
                    }
                    globalOffsetUs = segmentMaxOutUs + INTER_SEGMENT_GAP_US
                } finally {
                    runCatching { extractor.release() }
                }
            }
        } catch (error: Throwable) {
            return StitchOutcome.Failed(error.javaClass.simpleName)
        } finally {
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
        return if (wroteAnySample) StitchOutcome.Ok else StitchOutcome.Failed("NoSamples")
    }

    private fun sameFamily(a: String, b: String): Boolean =
        a.substringBefore('/') == b.substringBefore('/')

    private fun inputSizeOf(format: MediaFormat): Int {
        val declared = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
        } else {
            0
        }
        if (declared > 0) return declared.coerceAtMost(MAX_BUFFER_BYTES)
        val w = if (format.containsKey(MediaFormat.KEY_WIDTH)) format.getInteger(MediaFormat.KEY_WIDTH) else 0
        val h = if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else 0
        if (w > 0 && h > 0) return (w * h * 3 / 2).coerceIn(DEFAULT_BUFFER_BYTES, MAX_BUFFER_BYTES)
        return DEFAULT_BUFFER_BYTES
    }

    // ---------------------------------------------------------------- publish Q+

    /**
     * Inserts the stitched clip into the public gallery under `Movies/GameCore` with no permission.
     *
     * `IS_PENDING = 1` keeps the row invisible to other apps until the bytes are fully written; the flag
     * is cleared only after the copy completes. On any failure the pending row is deleted so a half-written
     * ghost is never left in the gallery.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun publishToMediaStore(source: File, fileName: String): ReplaySaveResult {
        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val pending = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, MIME_MP4)
            put(
                MediaStore.Video.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_MOVIES}/$GALLERY_SUBDIR",
            )
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, pending)
            ?: return ReplaySaveResult.Failed(ReplaySaveResult.Reason.PublishFailed)
        return try {
            val streamed = resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
                true
            } ?: false
            if (!streamed) {
                runCatching { resolver.delete(uri, null, null) }
                return ReplaySaveResult.Failed(ReplaySaveResult.Reason.PublishFailed)
            }
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                null,
                null,
            )
            val bytes = confirmMediaStoreSize(uri)
            if (bytes < MIN_OUTPUT_BYTES) {
                runCatching { resolver.delete(uri, null, null) }
                ReplaySaveResult.Failed(ReplaySaveResult.Reason.EmptyOutput)
            } else {
                ReplaySaveResult.Saved(uri = uri, absolutePath = null, bytes = bytes)
            }
        } catch (error: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            ReplaySaveResult.Failed(ReplaySaveResult.Reason.PublishFailed, error.javaClass.simpleName)
        }
    }

    private fun confirmMediaStoreSize(uri: Uri): Long {
        val fromFd = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
        }.getOrNull() ?: -1L
        if (fromFd > 0L) return fromFd
        return runCatching {
            context.contentResolver
                .query(uri, arrayOf(MediaStore.Video.Media.SIZE), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
                ?: 0L
        }.getOrDefault(0L)
    }

    // ------------------------------------------------------------- publish 26–28

    /**
     * Pre-Q fallback: copy into the app's own `files/recordings/` and hand back a `FileProvider` grant.
     *
     * Reuses the existing `recordings` files-path mapping and the existing `.fileprovider` authority, so
     * pre-29 devices get a shareable clip without any new manifest / res-xml entry and without a storage
     * permission (see the integration guide for the alternative of a dedicated `replay/` sub-path).
     */
    private fun publishToPrivateFile(source: File, fileName: String): ReplaySaveResult {
        val dir = File(context.filesDir, RECORDINGS_SUBDIR).apply { mkdirs() }
        val target = File(dir, fileName)
        return try {
            source.copyTo(target, overwrite = true)
            if (!target.isFile || target.length() < MIN_OUTPUT_BYTES) {
                runCatching { target.delete() }
                ReplaySaveResult.Failed(ReplaySaveResult.Reason.EmptyOutput)
            } else {
                ReplaySaveResult.Saved(
                    uri = shareUri(target),
                    absolutePath = target.absolutePath,
                    bytes = target.length(),
                )
            }
        } catch (error: Throwable) {
            runCatching { target.delete() }
            ReplaySaveResult.Failed(ReplaySaveResult.Reason.PublishFailed, error.javaClass.simpleName)
        }
    }

    private fun shareUri(file: File): Uri? = try {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    } catch (error: Throwable) {
        null
    }

    // ----------------------------------------------------------------- internals

    private fun replayCacheDir(): File = File(context.cacheDir, "replay").apply { mkdirs() }

    /**
     * A filesystem- and MediaStore-safe clip name.
     *
     * Runs the caller's name through [TextSanitizer] first (control chars, bidi overrides, Zalgo, length),
     * then keeps only characters that are unambiguously safe as a file name across the private dir and a
     * MediaStore `DISPLAY_NAME`, and always appends a timestamp so two saves in the same second — and two
     * clips of the same play — cannot collide.
     */
    private fun buildFileName(displayName: String): String {
        val base = TextSanitizer.sanitizeName(displayName, maxLength = 40)
            .replace(Regex("[^A-Za-z0-9 _-]"), "_")
            .replace(Regex("\\s+"), "_")
            .trim('_', '.', '-')
            .take(40)
            .ifBlank { "clip" }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "GameCore-Replay-$base-$stamp.mp4"
    }

    private companion object {
        const val MIME_MP4 = "video/mp4"
        const val GALLERY_SUBDIR = "GameCore"
        const val RECORDINGS_SUBDIR = "recordings"

        /** A real clip is well over a megabyte; anything under 1 KiB is a failed/empty mux. */
        const val MIN_OUTPUT_BYTES = 1_024L

        const val DEFAULT_BUFFER_BYTES = 1 shl 21 // 2 MiB
        const val MAX_BUFFER_BYTES = 1 shl 24 // 16 MiB

        /** Nominal gap inserted between stitched segments to keep per-track timestamps increasing. */
        const val INTER_SEGMENT_GAP_US = 10_000L // 10 ms
    }
}
