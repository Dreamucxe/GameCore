package com.gamecore.ui.backup

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.data.repository.BackupExport
import com.gamecore.data.repository.BackupImport
import com.gamecore.data.repository.BackupManager
import com.gamecore.data.repository.BackupPolicy
import com.gamecore.ui.components.Tone
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The §20 backup screen's state and the two actions behind it.
 *
 * Thin over [BackupManager]: it owns no configuration and holds no copy of one — the manager reads and
 * writes the stores directly — so this class only guards against a second action while one runs, turns the
 * manager's typed outcome into a line the user can read, and stages the share hand-off for the screen. The
 * SAF file pick lives in the screen (it needs an activity result), so [restore] takes the already-picked Uri.
 */
@HiltViewModel
class BackupRestoreViewModel @Inject constructor(
    private val backups: BackupManager,
) : ViewModel() {

    private val _state = MutableStateFlow(BackupRestoreUiState())
    val state: StateFlow<BackupRestoreUiState> = _state.asStateFlow()

    private var shareCounter = 0L

    /** Writes a backup and, on success, stages its share intent for the screen to launch. */
    fun export() {
        if (_state.value.isBusy) return
        _state.update { it.copy(isBusy = true, message = null) }
        viewModelScope.launch {
            _state.update { current ->
                when (val outcome = backups.export()) {
                    is BackupExport.Written -> current.copy(
                        isBusy = false,
                        message = "Saved ${outcome.fileName}. Choose where to send it.",
                        messageTone = Tone.Good,
                        share = outcome.shareIntent?.let { ShareRequest(++shareCounter, it) },
                    )
                    is BackupExport.Failed -> current.copy(
                        isBusy = false,
                        message = "Could not write the backup (${outcome.detail}).",
                        messageTone = Tone.Danger,
                    )
                }
            }
        }
    }

    /** Reads and applies the picked file under [policy], reporting what was written or why it was refused. */
    fun restore(uri: Uri, policy: BackupPolicy) {
        if (_state.value.isBusy) return
        _state.update { it.copy(isBusy = true, message = null) }
        viewModelScope.launch {
            val outcome = backups.restore(uri, policy)
            _state.update {
                it.copy(isBusy = false, message = messageFor(outcome), messageTone = toneFor(outcome))
            }
        }
    }

    /** Clears the staged share request once the screen has launched it, so it fires exactly once. */
    fun onShareLaunched() = _state.update { it.copy(share = null) }

    private fun messageFor(outcome: BackupImport): String = when (outcome) {
        is BackupImport.Restored -> {
            val presets = outcome.crosshairsAdded + outcome.coloursAdded
            "Restored ${outcome.profilesImported} profile(s), ${outcome.layoutsAdded} layout(s) and " +
                "$presets preset(s), plus your appearance, settings and macros. Anything already on this " +
                "device was kept."
        }
        is BackupImport.Failed -> when (outcome.reason) {
            BackupImport.Reason.UNREADABLE -> "That file could not be read."
            BackupImport.Reason.EMPTY -> "That file is empty."
            BackupImport.Reason.MALFORMED -> "That file is not a valid GameCore backup."
            BackupImport.Reason.WRONG_FORMAT -> "That is not a GameCore backup file."
            BackupImport.Reason.UNSUPPORTED_VERSION ->
                "That backup was written by a newer version of GameCore."
        }
    }

    private fun toneFor(outcome: BackupImport): Tone =
        if (outcome is BackupImport.Restored) Tone.Good else Tone.Danger
}
