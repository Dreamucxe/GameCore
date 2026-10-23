package com.gamecore.ui.backup

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.common.Formatters
import com.gamecore.data.repository.BackupExport
import com.gamecore.data.repository.BackupImport
import com.gamecore.data.repository.BackupManager
import com.gamecore.data.repository.BackupPolicy
import com.gamecore.data.repository.BackupPreview
import com.gamecore.data.repository.RetentionPurge
import com.gamecore.ui.components.Tone
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The §20 backup screen's state and the actions behind it.
 *
 * Thin over [BackupManager]: it owns no configuration and holds no copy of one — the manager reads and
 * writes the stores directly — so this class only guards against a second action while one runs, turns the
 * manager's typed outcome into a line the user can read, and stages the share hand-off for the screen. The
 * SAF file pick lives in the screen (it needs an activity result), so [preview] and [restore] take the
 * already-picked Uri.
 *
 * Since schema v2 there are five actions rather than two. The restore is two-phase — [preview] decodes and
 * counts a picked file, [confirmRestore] applies it — because the file can now carry a whole history and the
 * user is owed the counts before anything is written. The retention card owns the other two: [setRetentionDays]
 * persists the dropdown's window, and [purgeNow] is the explicit "Delete now" that turns the window into
 * deletions without waiting for the next launch or restore.
 */
@HiltViewModel
class BackupRestoreViewModel @Inject constructor(
    private val backups: BackupManager,
) : ViewModel() {

    private val _state = MutableStateFlow(BackupRestoreUiState())
    val state: StateFlow<BackupRestoreUiState> = _state.asStateFlow()

    private var shareCounter = 0L

    /** The file and policy a [preview] decoded, held until the user confirms or dismisses it. */
    private var pendingRestore: Pair<Uri, BackupPolicy>? = null

    init {
        // The retention card's numbers; the screen shows a quiet placeholder until this lands.
        viewModelScope.launch { refreshRetention() }
    }

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

    // ------------------------------------------------------------------------------------------ restore

    /**
     * Decodes the picked file and stages it for the user's confirmation.
     *
     * Deliberately not [restore]: the file picker returns as soon as the user chooses a document, and writing
     * configuration and history without letting the user see what the file holds would make the manager's
     * preview pointless. A file the preview refuses gets the same typed reason a restore would give; nothing
     * is written on either path.
     */
    fun preview(uri: Uri, policy: BackupPolicy) {
        if (_state.value.isBusy) return
        _state.update { it.copy(isBusy = true, message = null, preview = null) }
        viewModelScope.launch {
            when (val outcome = backups.preview(uri)) {
                is BackupPreview.Ready -> {
                    pendingRestore = uri to policy
                    _state.update { it.copy(isBusy = false, preview = outcome) }
                }
                is BackupPreview.Failed -> {
                    pendingRestore = null
                    _state.update {
                        it.copy(
                            isBusy = false,
                            message = messageFor(BackupImport.Failed(outcome.reason)),
                            messageTone = Tone.Danger,
                        )
                    }
                }
            }
        }
    }

    /** Applies the file [preview] decoded, under the policy chosen when it was picked. */
    fun confirmRestore() {
        val (uri, policy) = pendingRestore ?: return
        pendingRestore = null
        _state.update { it.copy(preview = null) }
        restore(uri, policy)
    }

    /** Drops the preview without applying it; the picked file is not read again and nothing is written. */
    fun dismissPreview() {
        pendingRestore = null
        _state.update { it.copy(preview = null) }
    }

    /** Reads and applies the picked file under [policy], reporting what was written or why it was refused. */
    fun restore(uri: Uri, policy: BackupPolicy) {
        if (_state.value.isBusy) return
        _state.update { it.copy(isBusy = true, message = null) }
        viewModelScope.launch {
            val outcome = backups.restore(uri, policy)
            // A restore ends with the manager's own retention sweep, so the card's count may have changed.
            val retention = backups.retentionStatus()
            _state.update {
                it.copy(
                    isBusy = false,
                    message = messageFor(outcome),
                    messageTone = toneFor(outcome),
                    retention = retention,
                )
            }
        }
    }

    /** Clears the staged share request once the screen has launched it, so it fires exactly once. */
    fun onShareLaunched() = _state.update { it.copy(share = null) }

    // ---------------------------------------------------------------------------------------- retention

    /** Persists the dropdown's window; the card then re-reads how many sessions it puts in scope. */
    fun setRetentionDays(days: Int) {
        if (_state.value.isBusy) return
        viewModelScope.launch {
            backups.setRetentionDays(days)
            refreshRetention()
        }
    }

    /**
     * Deletes what the current window puts out of scope, now rather than at the next launch or restore.
     *
     * Personal records are not touched — [BackupManager.purgeExpired] deletes session rows only — and the
     * message says so, because "delete old sessions" must not read as "delete your records".
     */
    fun purgeNow() {
        if (_state.value.isBusy) return
        _state.update { it.copy(isBusy = true, message = null) }
        viewModelScope.launch {
            val purge = backups.purgeExpired()
            _state.update {
                it.copy(
                    isBusy = false,
                    message = purgeMessage(purge),
                    messageTone = if (purge.total > 0) Tone.Good else Tone.Muted,
                )
            }
            refreshRetention()
        }
    }

    private suspend fun refreshRetention() {
        val status = backups.retentionStatus()
        _state.update { it.copy(retention = status) }
    }

    private fun purgeMessage(purge: RetentionPurge): String {
        if (purge.total == 0) {
            return "Nothing in your history is older than the window, so nothing was deleted."
        }
        val parts = buildList {
            if (purge.gameSessions > 0) add(Formatters.count(purge.gameSessions, "game session"))
            if (purge.aimLabSessions > 0) add(Formatters.count(purge.aimLabSessions, "Aim Lab run"))
        }
        return "Deleted ${parts.joinToString(" and ")}. Personal records were kept."
    }

    // ----------------------------------------------------------------------------------------- messages

    private fun messageFor(outcome: BackupImport): String = when (outcome) {
        is BackupImport.Restored -> {
            val presets = outcome.crosshairsAdded + outcome.coloursAdded
            buildString {
                append("Restored ${outcome.profilesImported} profile(s), ${outcome.layoutsAdded} layout(s) and ")
                append("$presets preset(s), plus your appearance, settings and macros.")
                val history = listOfNotNull(
                    outcome.gameSessionsAdded.takeIf { it > 0 }?.let { Formatters.count(it, "game session") },
                    outcome.aimLabSessionsAdded.takeIf { it > 0 }?.let { Formatters.count(it, "Aim Lab run") },
                    outcome.recordsAdded.takeIf { it > 0 }?.let { Formatters.count(it, "personal record") },
                )
                append(
                    if (history.isEmpty()) " No history needed adding."
                    else " History gained ${history.joinToString(", ")}."
                )
                if (outcome.purgedSessions > 0) {
                    append(
                        " The retention window then removed " +
                            "${Formatters.count(outcome.purgedSessions, "old session")}."
                    )
                }
            }
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
