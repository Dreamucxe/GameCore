package com.gamecore.core.emulator

/**
 * One emulator GameCore knows how to recognise.
 *
 * [packages] is plural because several projects publish a free and a paid build under different package
 * names, and a user who bought the paid one should not be told the emulator is missing. Detection picks the
 * first installed package in declaration order and records which one it found.
 *
 * [handlesFiles] is the honest part. Most emulators accept an `ACTION_VIEW` content URI; Winlator runs
 * Windows programs inside its own container and exposes no supported way to hand it a specific `.exe`.
 * When this is false, GameCore can still open the emulator, but the button says so and the file is not
 * claimed to have been launched.
 *
 * [downloadUrl] is a link to the project's own release page, never an APK bundled into GameCore. A null
 * link means GameCore detects an existing install but does not point the user at a download it cannot
 * vouch for.
 */
data class EmulatorDescriptor(
    val id: String,
    val displayName: String,
    val packages: List<String>,
    val systems: Set<EmulatorSystem>,
    val handlesFiles: Boolean = true,
    val downloadUrl: String? = null,
    val note: String? = null,
)

/**
 * A descriptor plus the package actually found on this device.
 *
 * [label] and [versionName] come from the installed APK, sanitised before they reach the UI. They are not
 * stored: another developer's label changes when they update, and a copy in GameCore's database would be a
 * stale name plus a third-party string with no reason to be there.
 */
data class InstalledEmulator(
    val descriptor: EmulatorDescriptor,
    val packageName: String,
    val label: String,
    val versionName: String?,
) {
    val id: String get() = descriptor.id
    val systems: Set<EmulatorSystem> get() = descriptor.systems
    val handlesFiles: Boolean get() = descriptor.handlesFiles
}
