package com.gamecore.ui.emulators

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.emulator.EmulatorDescriptor
import com.gamecore.core.emulator.EmulatorLaunchPlanner
import com.gamecore.core.emulator.EmulatorSystem
import com.gamecore.core.emulator.InstalledEmulator
import com.gamecore.core.emulator.RomDraft
import com.gamecore.core.emulator.RomEntry
import com.gamecore.data.repository.EmulatorRepository
import com.gamecore.data.repository.RomAddResult
import com.gamecore.data.repository.RomLibraryRepository
import com.gamecore.data.repository.RomPrepareResult
import com.gamecore.data.repository.RomWriteResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The emulator frontend's actions.
 *
 * The ViewModel owns the questions the screen asks: which system an ambiguous file belongs to, which of
 * several installed emulators should receive it, and whether a remembered choice is still installed. It
 * never starts an Activity itself; it emits a [LaunchRequest] and the screen reports whether Android
 * accepted it.
 *
 * A launch count is incremented only after an intent carrying the ROM resolved to a target application and
 * Android accepted the start. Opening an emulator's own home screen is not counted, because GameCore did not
 * see the file go anywhere and saying otherwise would turn "opened the app" into "played the game".
 */
@HiltViewModel
class EmulatorViewModel @Inject constructor(
    private val romLibrary: RomLibraryRepository,
    private val emulators: EmulatorRepository,
) : ViewModel() {

    private val editing = MutableStateFlow(EmulatorUiState())

    val state: StateFlow<EmulatorUiState> = editing.asStateFlow()

    init {
        viewModelScope.launch {
            romLibrary.roms.collect { rows ->
                editing.update { current -> current.copy(roms = rows, loading = false) }
            }
        }
        refreshEmulators()
    }

    // ------------------------------------------------------------------ library

    /**
     * Reads a picked document and either adds it, asks which system it belongs to, or explains why it
     * cannot be used. The picker returns a URI with a temporary grant; [RomLibraryRepository] is what
     * turns it into a persisted one, and only after the user has confirmed a system.
     */
    fun addRom(uri: Uri) {
        viewModelScope.launch {
            editing.update { it.copy(busy = true, message = null) }
            when (val prepared = romLibrary.prepare(uri)) {
                is RomPrepareResult.Rejected -> showMessage(prepared.reason, isError = true)
                is RomPrepareResult.Prepared -> {
                    val draft = prepared.draft
                    val singleSystem = draft.unambiguousSystem
                    when {
                        singleSystem != null -> addConfirmed(draft, singleSystem)

                        draft.candidateSystems.isEmpty() -> showMessage(
                            "GameCore could not match .${draft.extension} to a system.",
                            isError = true,
                        )

                        else -> editing.update {
                            it.copy(
                                busy = false,
                                pendingRom = PendingRom(draft, draft.candidateSystems),
                            )
                        }
                    }
                }
            }
        }
    }

    fun confirmPendingSystem(system: EmulatorSystem) {
        val pending = editing.value.pendingRom ?: return
        addConfirmed(pending.draft, system)
    }

    fun cancelPendingRom() {
        editing.update { it.copy(pendingRom = null, busy = false) }
    }

    private fun addConfirmed(draft: RomDraft, system: EmulatorSystem) {
        viewModelScope.launch {
            when (val result = romLibrary.add(draft, system)) {
                is RomAddResult.Added -> editing.update {
                    it.copy(
                        pendingRom = null,
                        busy = false,
                        message = "${draft.displayName} added as ${result.system.label}.",
                        messageIsError = false,
                    )
                }

                is RomAddResult.AlreadyAdded -> editing.update {
                    it.copy(
                        pendingRom = null,
                        busy = false,
                        message = "That file is already in the library.",
                        messageIsError = false,
                    )
                }

                is RomAddResult.Rejected -> editing.update {
                    it.copy(
                        pendingRom = null,
                        busy = false,
                        message = result.reason,
                        messageIsError = true,
                    )
                }
            }
        }
    }

    fun openSystemEditor(rom: RomEntry) {
        editing.update { it.copy(systemEditor = rom) }
    }

    fun dismissSystemEditor() {
        editing.update { it.copy(systemEditor = null) }
    }

    fun changeSystem(rom: RomEntry, system: EmulatorSystem) {
        viewModelScope.launch {
            when (val result = romLibrary.assignSystem(rom.id, system)) {
                RomWriteResult.Saved -> editing.update {
                    it.copy(
                        systemEditor = null,
                        message = "${rom.displayName} is now filed as ${system.label}.",
                        messageIsError = false,
                    )
                }

                is RomWriteResult.Rejected -> showMessage(result.reason, isError = true)
                is RomWriteResult.Failed -> showMessage(
                    "The library could not change that system (${result.detail}).",
                    isError = true,
                )
            }
        }
    }

    fun requestDelete(rom: RomEntry) {
        editing.update { it.copy(pendingDelete = rom) }
    }

    fun dismissDelete() {
        editing.update { it.copy(pendingDelete = null) }
    }

    fun confirmDelete() {
        val rom = editing.value.pendingDelete ?: return
        viewModelScope.launch {
            when (val result = romLibrary.remove(rom.id)) {
                RomWriteResult.Saved -> editing.update {
                    it.copy(
                        pendingDelete = null,
                        message = "${rom.displayName} removed from the library.",
                        messageIsError = false,
                    )
                }

                is RomWriteResult.Rejected -> showMessage(result.reason, isError = true)
                is RomWriteResult.Failed -> showMessage(
                    "The library could not remove that file (${result.detail}).",
                    isError = true,
                )
            }
        }
    }

    // ------------------------------------------------------------------ launching

    /**
     * Chooses an emulator for [rom], asking only when there is a real choice.
     *
     * One compatible emulator launches immediately. Several open a chooser. None produces a sentence naming
     * the missing capability and the action that fixes it, never a tap that does nothing.
     */
    fun launch(rom: RomEntry) {
        if (!rom.hasKnownSystem) {
            showMessage("Choose a system for this file before launching it.", isError = true)
            return
        }
        val choices = EmulatorLaunchPlanner.choices(
            system = rom.system,
            installed = editing.value.emulators,
            preferredPackage = rom.emulatorPackage,
        )
        when {
            choices.handOff.size == 1 -> handOff(rom, choices.handOff.first())
            choices.handOff.size > 1 -> editing.update {
                it.copy(launchChooser = LaunchChooser(rom, choices))
            }

            choices.openOnly.isNotEmpty() -> openOnly(rom, choices.openOnly.first())
            else -> showMessage(
                "No installed emulator handles ${rom.system.label}. Open \"Get emulators\" to install " +
                    "one, then refresh.",
                isError = true,
            )
        }
    }

    fun launchWith(emulator: InstalledEmulator) {
        val chooser = editing.value.launchChooser ?: return
        handOff(chooser.rom, emulator)
    }

    fun dismissLaunchChooser() {
        editing.update { it.copy(launchChooser = null) }
    }

    private fun handOff(rom: RomEntry, emulator: InstalledEmulator) {
        viewModelScope.launch {
            editing.update { it.copy(busy = true) }
            val intent = runCatching { emulators.handOffIntent(emulator, rom) }.getOrNull()
            if (intent == null) {
                openOnlyNow(
                    rom = rom,
                    emulator = emulator,
                    note = "GameCore could not hand ${rom.displayName} to ${emulator.label}. Open " +
                        "${emulator.label} and pick the file in its own browser.",
                )
                return@launch
            }
            romLibrary.setPreferredEmulator(rom.id, emulator.packageName)
            editing.update {
                it.copy(
                    busy = false,
                    launchChooser = null,
                    launchRequest = LaunchRequest(
                        intent = intent,
                        label = emulator.label,
                        handOff = true,
                        romId = rom.id,
                    ),
                    message = null,
                    messageIsError = false,
                )
            }
        }
    }

    private fun openOnly(rom: RomEntry, emulator: InstalledEmulator) {
        viewModelScope.launch { openOnlyNow(rom, emulator, note = null) }
    }

    private suspend fun openOnlyNow(rom: RomEntry, emulator: InstalledEmulator, note: String?) {
        val intent = runCatching { emulators.openIntent(emulator) }.getOrNull()
        if (intent == null) {
            editing.update {
                it.copy(
                    busy = false,
                    launchChooser = null,
                    message = "${emulator.label} has no launch screen GameCore can open.",
                    messageIsError = true,
                )
            }
            return
        }
        romLibrary.setPreferredEmulator(rom.id, emulator.packageName)
        editing.update {
            it.copy(
                busy = false,
                launchChooser = null,
                launchRequest = LaunchRequest(intent, emulator.label, handOff = false),
                message = note ?: "Opened ${emulator.label}. Pick the file in its own browser; GameCore " +
                    "cannot confirm that it ran.",
                messageIsError = false,
            )
        }
    }

    /** Opens an emulator's own screen from the installed list. */
    fun openEmulator(emulator: InstalledEmulator) {
        viewModelScope.launch {
            val intent = runCatching { emulators.openIntent(emulator) }.getOrNull()
            if (intent == null) {
                showMessage("${emulator.label} has no launch screen GameCore can open.", isError = true)
            } else {
                editing.update {
                    it.copy(launchRequest = LaunchRequest(intent, emulator.label, handOff = false))
                }
            }
        }
    }

    /** The screen reports whether Android accepted the intent. A failure is a message, not a silent tap. */
    fun launchHandled(success: Boolean) {
        val request = editing.value.launchRequest
        editing.update { it.copy(launchRequest = null, busy = false) }
        if (!success) {
            showMessage("Android could not open ${request?.label ?: "that app"}.", isError = true)
            return
        }
        val handOff = request ?: return
        val romId = handOff.romId ?: return
        val label = handOff.label
        viewModelScope.launch {
            romLibrary.recordLaunch(romId)
            editing.update {
                it.copy(
                    message = "Handed the file to $label. GameCore cannot confirm that the game ran.",
                    messageIsError = false,
                )
            }
        }
    }

    // ------------------------------------------------------------------ discovery and downloads

    fun refresh() {
        refreshEmulators()
    }

    private fun refreshEmulators() {
        viewModelScope.launch {
            val installed = runCatching { emulators.installed() }.getOrElse { error ->
                editing.update {
                    it.copy(
                        loading = false,
                        message = "GameCore could not read the installed apps (${error.javaClass.simpleName}).",
                        messageIsError = true,
                    )
                }
                return@launch
            }
            editing.update { it.copy(emulators = installed, loading = false) }
        }
    }

    fun openGetEmulators() {
        editing.update { it.copy(showGetEmulators = true) }
    }

    fun closeGetEmulators() {
        editing.update { it.copy(showGetEmulators = false) }
    }

    /**
     * Opens an emulator's own download page in the browser.
     *
     * No APK is downloaded or installed by GameCore. The user sees the project's release page and decides
     * what to trust; this app never becomes an installer for a third-party binary.
     */
    fun openDownload(descriptor: EmulatorDescriptor) {
        val intent = emulators.downloadIntent(descriptor)
        if (intent == null) {
            showMessage("${descriptor.displayName} has no download page GameCore can open.", isError = true)
            return
        }
        editing.update {
            it.copy(
                showGetEmulators = false,
                launchRequest = LaunchRequest(intent, descriptor.displayName, handOff = false),
            )
        }
    }

    // ------------------------------------------------------------------ messages

    fun dismissMessage() {
        editing.update { it.copy(message = null, messageIsError = false) }
    }

    private fun showMessage(text: String, isError: Boolean) {
        editing.update {
            it.copy(
                busy = false,
                message = text,
                messageIsError = isError,
            )
        }
    }
}
