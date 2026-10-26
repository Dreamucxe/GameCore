package com.gamecore.core.emulator

import java.util.Locale

/**
 * Pure file-name and extension rules for the ROM library.
 *
 * Kept free of Android types so every branch is testable on the JVM. The repository supplies the display
 * name and size it read from the content provider; this object only decides what can be inferred from the
 * name, and it never guesses a system for an ambiguous extension.
 */
object RomFile {

    /**
     * The lower-case suffix after the last dot, or null when there is no usable one.
     *
     * A leading dot is a hidden file, not an extension: `.iso` on its own has no stem and is not accepted.
     * Multi-dot names use the last segment, so `Game 1.0.iso` is an ISO and `archive.tar.gz` is a `gz`.
     */
    fun extensionOf(displayName: String): String? {
        val name = displayName.trim()
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.lastIndex) return null
        return name.substring(dot + 1).lowercase(Locale.US)
    }

    /**
     * Every family that lists [extension], which may be more than one.
     *
     * `.iso` deliberately returns four families. Collapsing that to one would be the exact place this
     * feature started lying: a GameCube image and a PS2 image are both ISO files and neither can be told
     * apart without reading the disc structure, which GameCore does not do.
     */
    fun systemsFor(extension: String): List<EmulatorSystem> =
        EmulatorSystem.candidatesFor(extension)

    /** Whether at least one known family lists this extension. */
    fun isRecognised(extension: String): Boolean = systemsFor(extension).isNotEmpty()

    /** The suffix as it is drawn in the library, with the dot, or an empty string when there is none. */
    fun suffix(extension: String): String =
        extension.trim().lowercase(Locale.US).takeIf { it.isNotEmpty() }?.let { ".$it" }.orEmpty()
}

/**
 * What the content provider told GameCore about a file, before the user chooses a system.
 *
 * [sizeBytes] is null when the provider does not expose `OpenableColumns.SIZE`; that is a real absence and
 * is rendered as "size unknown" rather than as zero. [candidateSystems] is derived, not stored, so a later
 * build that learns a new extension can re-offer the choice without rewriting the row.
 */
data class RomDraft(
    val uri: String,
    val displayName: String,
    val sizeBytes: Long?,
    val extension: String,
) {
    val candidateSystems: List<EmulatorSystem> get() = RomFile.systemsFor(extension)

    /** The one system that can be chosen without asking, or null when the extension is ambiguous. */
    val unambiguousSystem: EmulatorSystem? get() = candidateSystems.singleOrNull()
}
