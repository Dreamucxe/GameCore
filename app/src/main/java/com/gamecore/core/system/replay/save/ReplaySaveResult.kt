package com.gamecore.core.system.replay.save

import android.net.Uri

/**
 * The outcome of stitching and publishing an Instant Replay clip (audit A6, Save path).
 *
 * Deliberately only two shapes. A [Saved] is a clip the pipeline re-opened and confirmed to have a
 * non-zero, sane byte count on disk — never a "we called insert() and assume it worked" success. A
 * [Failed] carries a coarse [Reason] the UI can turn into a message, plus an optional [detail] that is
 * **only ever an exception's simple class name** — never a path, a file name, or an exception message,
 * either of which could leak where the user's footage lives (§0 "no fake success"; release must not log
 * paths/contents).
 */
sealed interface ReplaySaveResult {

    /**
     * The clip is on disk and confirmed non-empty.
     *
     * @param uri a `content://` handle the UI can hand to a share sheet or "open" action: a MediaStore
     *   item URI on API 29+, or a `FileProvider` grant on 26–28. Null only when the 26–28 provider
     *   declined to vend a URI (the file still exists at [absolutePath]).
     * @param absolutePath the private-storage path for the 26–28 fallback clip, or null on API 29+ where
     *   the clip lives in MediaStore and has no app-visible path.
     * @param bytes the confirmed size read back from the published clip.
     */
    data class Saved(
        val uri: Uri?,
        val absolutePath: String?,
        val bytes: Long,
    ) : ReplaySaveResult {
        /** True when the clip was published to the public gallery via MediaStore (API 29+). */
        val isMediaStore: Boolean get() = absolutePath == null
    }

    /** Nothing was published; the buffer on disk is untouched. */
    data class Failed(
        val reason: Reason,
        val detail: String? = null,
    ) : ReplaySaveResult

    /** Why a save did not produce a confirmed clip. */
    enum class Reason {
        /** No segments overlapped the requested window — there was nothing to save. */
        NoSegments,

        /** The MediaMuxer remux of the retained segments failed or produced no samples. */
        StitchFailed,

        /** Handing the stitched file to MediaStore / the private dir failed. */
        PublishFailed,

        /** Publish reported success but the re-opened clip was zero / implausibly small. */
        EmptyOutput,

        /** Any other throwable, surfaced with its class name in [Failed.detail]. */
        Unexpected,
    }
}
