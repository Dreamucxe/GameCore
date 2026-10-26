package com.gamecore.ui.emulators

import android.content.Intent
import com.gamecore.core.emulator.EmulatorSystem
import com.gamecore.core.emulator.InstalledEmulator
import com.gamecore.core.emulator.LaunchChoices
import com.gamecore.core.emulator.RomDraft
import com.gamecore.core.emulator.RomEntry

/** A picked file waiting for the user to choose one of several possible systems. */
data class PendingRom(
    val draft: RomDraft,
    val candidates: List<EmulatorSystem>,
)

/** A launch that needs the user to choose between several emulators that accept the file. */
data class LaunchChooser(
    val rom: RomEntry,
    val choices: LaunchChoices,
)

/**
 * A one-shot intent the screen is allowed to start.
 *
 * The ViewModel builds it because it owns the package-manager lookup; the Compose screen starts it because
 * only a screen has an Activity context. The separation keeps `startActivity` out of the ViewModel and lets
 * a failed launch be reported as a message rather than as a silent no-op.
 *
 * [handOff] distinguishes an intent carrying the ROM from an intent that only opens an emulator or a
 * browser. [romId] is the library row whose counter is incremented, and it is set only for a hand-off: the
 * screen reports back whether Android accepted the intent before that write happens. Opening an emulator
 * must never look like a launch.
 */
data class LaunchRequest(
    val intent: Intent,
    val label: String,
    val handOff: Boolean,
    val romId: Long? = null,
)

/**
 * The emulator frontend's state.
 *
 * Every list is a plain value. [emulators] comes from a package-manager read that is refreshed on resume,
 * [roms] from the encrypted database, and [pendingRom] / [launchChooser] are transient questions that
 * disappear once answered. [messageIsError] decides the banner tone instead of the message text being
 * sniffed for words.
 */
data class EmulatorUiState(
    val loading: Boolean = true,
    val emulators: List<InstalledEmulator> = emptyList(),
    val roms: List<RomEntry> = emptyList(),
    val pendingRom: PendingRom? = null,
    val systemEditor: RomEntry? = null,
    val launchChooser: LaunchChooser? = null,
    val pendingDelete: RomEntry? = null,
    val showGetEmulators: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false,
    val launchRequest: LaunchRequest? = null,
) {
    val hasEmulators: Boolean get() = emulators.isNotEmpty()
    val isEmpty: Boolean get() = roms.isEmpty()

    /** The installed emulator remembered for [rom], if it is still installed. */
    fun rememberedEmulator(rom: RomEntry): InstalledEmulator? =
        rom.emulatorPackage?.let { packageName ->
            emulators.firstOrNull { it.packageName == packageName }
        }
}
