package com.gamecore.core.emulator

import java.util.Locale

/**
 * One ROM or disc image the user has added, as this app understands it.
 *
 * [uri] is the persisted `content://` document URI, not a file path. Scoped storage makes a path a
 * second-class answer: the user granted access to one document, and that grant is what survives a reboot.
 * [system] is user-confirmed metadata. For an ambiguous extension it starts as the system the user chose;
 * it is never inferred later from an emulator being installed.
 *
 * [emulatorPackage] is the user's remembered choice for this file, or null when GameCore has not been told
 * and should offer the ranked list. A remembered choice that has since been uninstalled is ignored by the
 * launcher and does not make the row look broken.
 */
data class RomEntry(
    val id: Long,
    val uri: String,
    val displayName: String,
    val sizeBytes: Long?,
    val extension: String,
    val system: EmulatorSystem,
    val emulatorPackage: String?,
    val addedAtMillis: Long,
    val lastLaunchedAtMillis: Long?,
    val launchCount: Int,
) {
    val suffix: String get() = RomFile.suffix(extension)
    val hasKnownSystem: Boolean get() = system != EmulatorSystem.UNKNOWN

    /** A lower-case key used only for stable grouping and search; the UI prints [displayName]. */
    val sortKey: String get() = displayName.lowercase(Locale.US)
}
