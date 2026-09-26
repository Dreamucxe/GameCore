package com.gamecore.core.config

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * The gate a game's config file passes through before the editor will show it as editable rows (spec §A4).
 *
 * Two things disqualify a file from editing, and both are decided here from the raw bytes alone — before a
 * single character reaches the UI:
 *
 *  - **Size.** A config file is read and offered for editing only up to [MAX_EDITABLE_BYTES]. Past that it
 *    is almost certainly not a hand-editable settings file (a cache, a save blob, a media asset), and
 *    holding megabytes of it in a text field to no purpose is how the editor would jank or OOM.
 *  - **Binariness.** A `.dat`/`.bin`/`.sav` opened as text is a garbage screen that a careless save could
 *    corrupt. We refuse to present bytes as editable text unless they really are text, so the worst a user
 *    can do to a binary file through this editor is look at a "view only" notice.
 *
 * Detection is deliberately conservative: a NUL byte or invalid UTF-8 means binary, full stop, and a file
 * we cannot confidently call text is treated as binary rather than risked. False "binary" verdicts only
 * cost the user an edit we were unsure was safe; a false "text" verdict on a binary file is a corrupted
 * save, so the asymmetry is intentional.
 */
object ConfigFileInspector {

    /** Spec §A4: read and offer a config file for editing only up to 2 MiB (2,097,152 bytes). */
    const val MAX_EDITABLE_BYTES: Int = 2 * 1024 * 1024

    /**
     * How many leading bytes we sniff for the binary heuristic. A real text config is clean from its first
     * bytes; a binary blob betrays itself well inside this window, so there is no need to scan a whole file.
     */
    private const val SNIFF_LIMIT: Int = 8192

    /**
     * Classify [bytes] for the editor. Order matters: size is checked before content so an enormous file is
     * rejected as [ViewOnlyReason.TOO_LARGE] rather than spending the sniff on it.
     */
    fun inspect(bytes: ByteArray): FileInspection {
        if (bytes.size > MAX_EDITABLE_BYTES) return FileInspection.ViewOnly(ViewOnlyReason.TOO_LARGE)
        if (looksBinary(bytes)) return FileInspection.ViewOnly(ViewOnlyReason.BINARY)
        val text = decodeStrictUtf8(bytes) ?: return FileInspection.ViewOnly(ViewOnlyReason.BINARY)
        return FileInspection.Editable(text)
    }

    /**
     * The byte-level tell for binary content. A single NUL byte is decisive — no text encoding this editor
     * accepts produces one — and short of that, an unusually high share of odd control bytes in the sniff
     * window (backspaces, escapes, shift-out, DEL, and the like, but *not* tab/newline/CR/FF, which are
     * ordinary in text) means binary. Empty is text: there is nothing binary about zero bytes.
     */
    fun looksBinary(bytes: ByteArray): Boolean {
        val n = minOf(bytes.size, SNIFF_LIMIT)
        if (n == 0) return false
        var suspicious = 0
        for (i in 0 until n) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0x00) return true
            // Control bytes that do not occur in ordinary text. Tab (0x09), LF (0x0A), VT (0x0B),
            // FF (0x0C) and CR (0x0D) are all allowed; everything below 0x09, the 0x0E..0x1F band, and
            // DEL (0x7F) counts against the file.
            if (b < 0x09 || b in 0x0E..0x1F || b == 0x7F) suspicious++
        }
        // Better than ~10% odd control bytes in the window: call it binary.
        return suspicious * 100 / n > 10
    }

    /**
     * Decode [bytes] as UTF-8, reporting failure as null rather than substituting replacement characters.
     * Silent replacement is exactly what we must avoid: a save of the "repaired" text would rewrite the
     * file with U+FFFD where real bytes used to be, so a decode that is not clean means view-only.
     */
    private fun decodeStrictUtf8(bytes: ByteArray): String? = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }
}

/** The result of [ConfigFileInspector.inspect]: either editable text, or a reason the editor is read-only. */
sealed interface FileInspection {
    /** The bytes are text within the size cap; [text] is the strictly-decoded UTF-8 content. */
    data class Editable(val text: String) : FileInspection

    /** The bytes cannot be safely edited; [reason] says why the editor falls back to view-only. */
    data class ViewOnly(val reason: ViewOnlyReason) : FileInspection
}

/** Why a file is view-only rather than editable. */
enum class ViewOnlyReason { BINARY, TOO_LARGE }

