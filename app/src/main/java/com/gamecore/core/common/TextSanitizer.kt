package com.gamecore.core.common

/**
 * Sanitises text that came from outside GameCore's own code before it is stored or
 * rendered.
 *
 * Three sources qualify: names the user types (profile labels, custom HUD element
 * captions, crosshair preset names), text read back out of an imported HUD layout
 * file, and strings the platform hands over that originate in another app's
 * manifest — a game's `android:label` is attacker-controlled in exactly the same
 * sense as user input, because anyone can publish an APK whose label is a thousand
 * combining marks or a right-to-left override.
 *
 * Compose does not interpret markup, so this is not about HTML escaping. The
 * concrete problems it prevents are:
 *
 *  * **Control and format characters.** A `U+202E` in a game's label reverses the
 *    rendering of everything after it, including GameCore's own UI text on the same
 *    row. Zero-width characters make two visually identical profile names
 *    non-equal, so a user cannot tell why their profile "already exists".
 *  * **Unbounded length.** A label is drawn into a fixed overlay window and written
 *    to a notification; a megabyte of text in either is a layout pass that never
 *    finishes.
 *  * **Combining-mark stacking.** Hundreds of diacritics on one base character
 *    ("Zalgo") draw far outside the line box, over whatever is beneath — which,
 *    for an app that draws over other apps, means over the game.
 *  * **Newlines** in a single-line field, which turn a one-line list row into a
 *    scrolling column.
 *
 * Everything here is a pure function over a String, so it is unit-tested without a
 * device, and it is applied at both boundaries — before persistence and before
 * render — because data already in the database predates any given version of this
 * code.
 */
object TextSanitizer {

    /** Fits a notification line, a list row, and an overlay label. */
    const val MAX_NAME_LENGTH = 64

    /** For a free-text note attached to a session. */
    const val MAX_NOTE_LENGTH = 512

    /** Beyond this many combining marks on one base character, the rest are dropped. */
    private const val MAX_COMBINING_MARKS = 2

    /**
     * The standard treatment for any short, single-line, user-visible name.
     *
     * Order matters: strip the dangerous characters first, then collapse the
     * whitespace they leave behind, and truncate last so the limit applies to what
     * will actually be drawn rather than to the raw input.
     */
    fun sanitizeName(raw: String?, maxLength: Int = MAX_NAME_LENGTH): String {
        if (raw.isNullOrEmpty()) return ""
        val stripped = stripDangerousCharacters(raw)
        val collapsed = collapseWhitespace(stripped)
        val limited = limitCombiningMarks(collapsed)
        return truncate(limited, maxLength)
    }

    /**
     * Multi-line text. Newlines survive; everything else in the single-line
     * treatment still applies.
     */
    fun sanitizeMultiline(raw: String?, maxLength: Int = MAX_NOTE_LENGTH): String {
        if (raw.isNullOrEmpty()) return ""
        val stripped = stripDangerousCharacters(raw, allowNewlines = true)
        val limited = limitCombiningMarks(stripped)
        // Three or more consecutive blank lines serve no purpose and let a short
        // note push everything else off a card.
        val collapsed = limited.replace(Regex("\n{3,}"), "\n\n")
        return truncate(collapsed, maxLength)
    }

    /**
     * A package name, validated rather than sanitised.
     *
     * Package names have a defined grammar, and a string that does not match it is
     * not a package name that needs cleaning — it is a value that must not be used
     * to look one up. Returns null so the caller has to handle the rejection.
     */
    fun validatePackageName(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        if (trimmed.length > 255) return null
        return if (PACKAGE_NAME.matches(trimmed)) trimmed else null
    }

    /**
     * Text bound for a notification.
     *
     * Notifications are drawn by the system UI, not by GameCore, in a process with
     * different rendering rules and no way for us to constrain the result. The
     * conservative treatment is a shorter cap and no newlines at all.
     */
    fun sanitizeForNotification(raw: String?): String = sanitizeName(raw, maxLength = 48)

    /**
     * Removes characters that change how surrounding text is drawn, or that are
     * invisible.
     *
     * Deliberately a blocklist of Unicode *categories* rather than a list of
     * specific code points: the set of format characters grows with each Unicode
     * revision, and `Character.getType` tracks the JDK's tables rather than a
     * literal list written here that would silently miss the next one.
     */
    private fun stripDangerousCharacters(raw: String, allowNewlines: Boolean = false): String {
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            val charCount = Character.charCount(cp)
            i += charCount

            if (allowNewlines && cp == '\n'.code) {
                out.append('\n')
                continue
            }

            val keep = when (Character.getType(cp).toByte()) {
                // Bidirectional overrides, zero-width joiners, invisible operators.
                Character.FORMAT -> false
                // Unassigned, private-use and surrogate code points render as
                // whatever the font decides, which on some devices is a glyph the
                // width of a screen.
                Character.UNASSIGNED, Character.PRIVATE_USE, Character.SURROGATE -> false
                // C0/C1 controls. Tab is remapped to a space below rather than kept,
                // because a tab in a fixed-width overlay label is unpredictable.
                Character.CONTROL -> false
                // Line and paragraph separators, handled as whitespace above.
                Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> false
                else -> true
            }

            when {
                keep -> out.appendCodePoint(cp)
                // A stripped control or separator becomes a space, so words that were
                // separated by one do not run together into a different word.
                cp == '\t'.code || cp == '\n'.code || cp == '\r'.code -> out.append(' ')
                else -> Unit
            }
        }
        return out.toString()
    }

    /**
     * Caps consecutive combining marks.
     *
     * Legitimate text needs two at most (a base letter with a diacritic and a tone
     * mark); a stack of two hundred is either an attack on the layout or a broken
     * paste, and in an overlay it draws over the game.
     */
    private fun limitCombiningMarks(raw: String): String {
        val out = StringBuilder(raw.length)
        var consecutive = 0
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            i += Character.charCount(cp)
            val isMark = when (Character.getType(cp).toByte()) {
                Character.NON_SPACING_MARK,
                Character.COMBINING_SPACING_MARK,
                Character.ENCLOSING_MARK,
                -> true
                else -> false
            }
            if (isMark) {
                if (consecutive < MAX_COMBINING_MARKS) {
                    out.appendCodePoint(cp)
                    consecutive++
                }
            } else {
                out.appendCodePoint(cp)
                consecutive = 0
            }
        }
        return out.toString()
    }

    /** Runs of whitespace become one space, and the ends are trimmed. */
    private fun collapseWhitespace(raw: String): String =
        raw.replace(WHITESPACE_RUN, " ").trim()

    /**
     * Truncates without splitting a surrogate pair or orphaning a combining mark
     * from its base character — either of which produces a replacement glyph in the
     * middle of the user's own profile name.
     */
    private fun truncate(raw: String, maxLength: Int): String {
        if (raw.length <= maxLength) return raw
        if (maxLength <= 1) return ""
        var end = maxLength - 1
        // Do not cut between the halves of a surrogate pair.
        if (Character.isHighSurrogate(raw[end - 1]) && Character.isLowSurrogate(raw[end])) end--
        // Do not leave a combining mark at the start of the cut remainder.
        while (end > 0) {
            val cp = raw.codePointAt(end)
            val type = Character.getType(cp).toByte()
            if (type == Character.NON_SPACING_MARK ||
                type == Character.COMBINING_SPACING_MARK ||
                type == Character.ENCLOSING_MARK
            ) {
                end--
            } else {
                break
            }
        }
        return raw.substring(0, end).trimEnd() + "…"
    }

    private val WHITESPACE_RUN = Regex("\\s+")

    /**
     * The Android package-name grammar: dot-separated segments, each starting with a
     * letter or underscore. Stricter than the platform's own parser in one respect —
     * it requires at least one dot — because every real application package has one
     * and a single bare segment is far more likely to be a mistake or an injection
     * attempt than a genuine package.
     */
    private val PACKAGE_NAME = Regex("^[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)+$")
}
