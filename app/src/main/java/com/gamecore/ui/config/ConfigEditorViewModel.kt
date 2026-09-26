package com.gamecore.ui.config

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.config.UpdateDrift
import com.gamecore.core.config.ViewOnlyReason
import com.gamecore.core.model.ConfigBackup
import com.gamecore.core.system.InstalledAppLister
import com.gamecore.data.preferences.SecurePreferenceStore
import com.gamecore.domain.config.ConfigCheckpointResult
import com.gamecore.domain.config.ConfigEditorController
import com.gamecore.domain.config.ConfigOpenResult
import com.gamecore.domain.config.ConfigRestoreResult
import com.gamecore.domain.config.ConfigSaveResult
import com.gamecore.domain.config.ConfigTarget
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One config file, being viewed or edited.
 *
 * The whole screen is one file's text held here and written once, on [save] — never field-by-field and
 * never per keystroke. That is not a UI nicety: [ConfigEditorController] guarantees the live file is only
 * ever replaced *whole, or left exactly as it was*, and an autosave streaming each character into the
 * game's sandbox would be handing a running game a half-typed configuration. The buffer stays local until
 * the user commits it, and the commit is atomic all the way down.
 *
 * A single immutable [ConfigEditorUiState] in one [MutableStateFlow], not a `combine` of parts: everything
 * here is the one document and the facts about it, none of it is a live feed, and a screen where a
 * background emission could replace what the user is typing is a screen that loses their work.
 *
 * The edit notice is a *preference*, not a capability. Whether the user has the elevated shell to reach a
 * config at all is [ConfigEditorController.isAvailable]'s answer and lives in the load path; whether they
 * have already read the one-time "editing is powerful, originals are backed up" notice is a remembered
 * choice in [SecurePreferenceStore]. The two are unrelated, and conflating them would either re-warn a
 * user who dismissed the notice or gate the warning behind a capability check it has nothing to do with.
 * This mirrors the resolution-override notice in `ProfileEditorViewModel`: seed a visible flag from the
 * stored setting, and flip that setting false — via [SecurePreferenceStore.updateSettings] — when the user
 * asks not to be shown it again.
 *
 * Missing navigation arguments degrade to an error state rather than crash. A `!!` here would turn a route
 * wired with the wrong key — a programming error, but a recoverable one — into a process death in front of
 * the user; instead the screen reports it was opened without a file and the rest of the app is untouched.
 */
@HiltViewModel
class ConfigEditorViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val controller: ConfigEditorController,
    private val preferences: SecurePreferenceStore,
    private val installedApps: InstalledAppLister,
) : ViewModel() {

    // The three parts of a ConfigTarget arrive as separate nav arguments and are read as nullable: a route
    // that forgot one, or named it wrongly, must surface as an honest error rather than a `!!` crash.
    private val packageName: String? = savedState.get<String>(ARG_PACKAGE)
    private val userId: Int? = savedState.get<Int>(ARG_USER)
    private val relativePath: String? = savedState.get<String>(ARG_PATH)

    /**
     * The file this editor acts on, or null when a nav argument was missing. Built once: the three parts
     * only mean something together (see [ConfigTarget]), so pairing a path with the wrong package cannot
     * happen if the target is never reassembled from loose values at a call site.
     */
    private val target: ConfigTarget? =
        if (packageName != null && userId != null && relativePath != null) {
            ConfigTarget(packageName, userId, relativePath)
        } else {
            null
        }

    /**
     * The game's current `PackageInfo.lastUpdateTime`, read once in [load] and reused by [save].
     *
     * It is the "as of now" side of the update-drift check (§A9): [load] hands it to
     * [ConfigEditorController.open] to compare against the original's recorded stamp, and [save] passes it
     * on so the first backup records the install this edit was made against. Null when the package manager
     * did not surface it — an honest "unknown", which the controller reads as "cannot warn" rather than a
     * false alarm.
     */
    private var currentLastUpdateTime: Long? = null

    private val editing = MutableStateFlow(
        ConfigEditorUiState(
            fileName = relativePath?.let { fileNameOf(it) } ?: "",
            isLoading = true,
        ),
    )

    val state: StateFlow<ConfigEditorUiState> = editing.asStateFlow()

    init {
        // Seed the one-time notice from the stored preference, then load the file — in one coroutine and in
        // this order so the notice flag is settled before the screen first draws its gate. `first()` reads
        // the current value of the settings StateFlow, matching the notice-seeding this ViewModel mirrors.
        viewModelScope.launch {
            val armed = preferences.settings.first().showConfigEditNotice
            editing.value = editing.value.copy(showEditNotice = armed)
            load()
        }
    }

    /**
     * Opens [target] and maps the outcome onto the buffer; never throws.
     *
     * The availability check comes first because it is the editor's precondition: without the elevated
     * shell there is no sandbox to read, and saying so is more use than a bare "not found". An
     * [ConfigOpenResult.Editable] result fills the buffer and its baseline together, so the freshly-loaded
     * file starts clean. A [ConfigOpenResult.ViewOnly] file has no text to show — the bytes are binary or
     * over the size cap — so the buffer is left empty and read-only with a reason. Every other outcome is a
     * blocking [ConfigEditorUiState.error].
     */
    private suspend fun load() {
        val target = target
        if (target == null) {
            editing.value = editing.value.copy(
                isLoading = false,
                error = "This editor was opened without a file to show.",
            )
            return
        }
        // Clear the drift banner on every load, not just the Editable branch: a reload that lands on
        // ViewOnly (e.g. a restore) must not leave a stale "updated since" warning hanging over an empty
        // read-only field. The Editable branch below re-sets it from this open's own drift result.
        editing.value = editing.value.copy(isLoading = true, error = null, showDriftWarning = false)
        if (!controller.isAvailable()) {
            editing.value = editing.value.copy(isLoading = false, error = SHIZUKU_MESSAGE)
            return
        }
        // The game's current install time, for the update-drift check (§A9). Read here so open() can
        // compare it to the original's recorded stamp, and cached so save() records the same value.
        currentLastUpdateTime = installedApps.describe(target.packageName)?.lastUpdateTime
        editing.value = when (val result = controller.open(target, currentLastUpdateTime)) {
            is ConfigOpenResult.Editable -> editing.value.copy(
                isLoading = false, text = result.text, baseline = result.text, readOnly = false, error = null,
                showDriftWarning = result.updateDrift == UpdateDrift.UPDATED_SINCE,
            )
            is ConfigOpenResult.ViewOnly -> editing.value.copy(
                isLoading = false, readOnly = true, text = "", baseline = "",
                message = viewOnlyMessage(result.reason),
            )
            ConfigOpenResult.NotFound ->
                editing.value.copy(isLoading = false, error = "That file no longer exists.")
            ConfigOpenResult.RequiresShizuku ->
                editing.value.copy(isLoading = false, error = SHIZUKU_MESSAGE)
            is ConfigOpenResult.Failed -> editing.value.copy(
                isLoading = false, error = "Couldn't open the file (${result.stage}). ${result.detail}",
            )
        }
    }

    /** Every keystroke lands here; a read-only buffer ignores them so a view-only file cannot go dirty. */
    fun onTextChange(new: String) {
        if (editing.value.readOnly) return
        editing.value = editing.value.copy(text = new)
    }

    /**
     * Writes the buffer back to the file, whole, and advances the baseline to what was written.
     *
     * The bytes are snapshotted *before* the suspending save so the baseline can be set to exactly what the
     * controller committed — not to whatever the user may have typed while the save was in flight. Resetting
     * the baseline to the post-save buffer would wrongly report a file with newer unsaved keystrokes as
     * clean. `toByteArray()` is UTF-8, matching the strict UTF-8 the file was decoded with on open.
     *
     * The game's [currentLastUpdateTime], read on load, rides along as `sourceLastUpdateTime` so the first
     * save stamps the backed-up original with the install this edit was made against — the baseline a later
     * open (§A9) compares to. Re-entrancy is guarded because the Save button stays enabled until the
     * baseline advances, so a double tap could otherwise launch two privileged writes at once.
     */
    fun save() {
        val target = target ?: return
        val current = editing.value
        if (current.readOnly || current.isSaving) return
        val committed = current.text
        editing.value = current.copy(isSaving = true, message = null)
        viewModelScope.launch {
            val result = controller.save(target, committed.toByteArray(), sourceLastUpdateTime = currentLastUpdateTime)
            editing.value = when (result) {
                is ConfigSaveResult.Saved ->
                    editing.value.copy(isSaving = false, baseline = committed, message = "Saved.")
                ConfigSaveResult.NotFound -> editing.value.copy(
                    isSaving = false,
                    message = "That file no longer exists, so there was nothing to save over.",
                )
                ConfigSaveResult.RequiresShizuku ->
                    editing.value.copy(isSaving = false, message = SHIZUKU_MESSAGE)
                is ConfigSaveResult.Failed -> editing.value.copy(
                    isSaving = false, message = "Save failed (${result.stage}). ${result.detail}",
                )
            }
        }
    }

    /**
     * The Restore action's first half: fetch every backup this file has and raise the picker over them.
     *
     * Restore is only meaningful against a chosen copy, so the button does not restore — it asks *which*.
     * The list is fetched fresh each time (a checkpoint taken since the screen opened should appear) and
     * ordered original-first, newest-checkpoint-next by the repository. When there is nothing to restore —
     * no save has secured an original yet — the picker would be an empty modal, so this says so on the
     * status line instead and leaves the buffer alone.
     */
    fun requestRestore() {
        val target = target ?: return
        editing.value = editing.value.copy(message = null)
        viewModelScope.launch {
            val found = controller.backups(target)
            editing.value = if (found.isEmpty()) {
                editing.value.copy(message = "There are no saved copies to restore yet.")
            } else {
                editing.value.copy(backups = found, showBackupPicker = true)
            }
        }
    }

    /** Closes the backup picker without restoring anything — the file and buffer are untouched. */
    fun dismissBackupPicker() {
        editing.value = editing.value.copy(showBackupPicker = false)
    }

    /**
     * Puts the backup named by [backupId] back as the live file, then reloads so the buffer shows the
     * restored bytes. The reload is what advances the baseline, so a successful restore leaves the editor
     * clean and showing what is now on disk rather than the edit the user was undoing.
     */
    fun restore(backupId: Long) {
        val target = target ?: return
        editing.value = editing.value.copy(message = null, showBackupPicker = false)
        viewModelScope.launch {
            when (val result = controller.restore(target, backupId)) {
                is ConfigRestoreResult.Restored -> {
                    load()
                    editing.value = editing.value.copy(message = "Restored.")
                }
                ConfigRestoreResult.NotFound ->
                    editing.value = editing.value.copy(message = "That backup is no longer available.")
                ConfigRestoreResult.RequiresShizuku ->
                    editing.value = editing.value.copy(message = SHIZUKU_MESSAGE)
                is ConfigRestoreResult.Failed -> editing.value =
                    editing.value.copy(message = "Restore failed (${result.stage}). ${result.detail}")
            }
        }
    }

    /**
     * Snapshots the current file as a labelled checkpoint kept alongside — never in place of — the
     * untouched original. It leaves the buffer and baseline alone: a checkpoint is a mark on the bytes on
     * disk, saved or not, and taking one changes nothing about what the user is editing.
     *
     * The game's [currentLastUpdateTime] rides along as `sourceLastUpdateTime`, mirroring [save], so the
     * checkpoint row carries the same install-time drift baseline the controller records for a first save.
     */
    fun checkpoint(label: String?) {
        val target = target ?: return
        editing.value = editing.value.copy(message = null)
        viewModelScope.launch {
            editing.value = when (
                val result = controller.checkpoint(target, label, sourceLastUpdateTime = currentLastUpdateTime)
            ) {
                is ConfigCheckpointResult.Captured -> editing.value.copy(message = "Checkpoint saved.")
                ConfigCheckpointResult.NotFound -> editing.value.copy(
                    message = "That file no longer exists, so there was nothing to checkpoint.",
                )
                ConfigCheckpointResult.RequiresShizuku -> editing.value.copy(message = SHIZUKU_MESSAGE)
                is ConfigCheckpointResult.Failed -> editing.value.copy(
                    message = "Checkpoint failed (${result.stage}). ${result.detail}",
                )
            }
        }
    }

    /**
     * Continues past the one-time edit notice. When [dontShowAgain] is set the preference is flipped so the
     * notice never returns; either way it is hidden for this screen. The write goes through
     * [SecurePreferenceStore.updateSettings] — the same store and the same one-time-notice discipline the
     * resolution-override notice uses — because this is a remembered choice, not this session's state.
     */
    fun proceedPastNotice(dontShowAgain: Boolean) {
        if (dontShowAgain) {
            preferences.updateSettings { it.copy(showConfigEditNotice = false) }
        }
        editing.value = editing.value.copy(showEditNotice = false)
    }

    /** Clears the transient status line once the UI has shown it. */
    fun dismissMessage() {
        editing.value = editing.value.copy(message = null)
    }

    /** Dismisses the update-drift banner once the user has acknowledged it; the file itself is untouched. */
    fun dismissDriftWarning() {
        editing.value = editing.value.copy(showDriftWarning = false)
    }

    companion object {
        /**
         * SavedStateHandle keys for the three parts of the [ConfigTarget]. Literal strings, not references
         * to a route object, so this ViewModel compiles independently of the navigation change that feeds
         * it — but the navArguments that route registers must be named with exactly these keys.
         */
        const val ARG_PACKAGE = "cfg_package"
        const val ARG_USER = "cfg_user"
        const val ARG_PATH = "cfg_path"

        /** Shared wording for every outcome that means the elevated shell is not there to reach the file. */
        private const val SHIZUKU_MESSAGE =
            "Editing config files needs the elevated shell, which isn't available right now."

        /** The title-bar name: the path's last segment, or the whole path when it has no separator. */
        private fun fileNameOf(path: String): String = path.substringAfterLast('/').ifEmpty { path }

        /** A plain-language reason a file opened read-only, for the status line. */
        private fun viewOnlyMessage(reason: ViewOnlyReason): String = when (reason) {
            ViewOnlyReason.BINARY ->
                "This file isn't text, so it's shown read-only — editing it here could corrupt it."
            ViewOnlyReason.TOO_LARGE ->
                "This file is too large to edit here, so it's shown read-only."
        }
    }
}

/**
 * Everything the config editor screen draws, in one immutable snapshot.
 *
 * [isDirty] is derived rather than stored: the buffer differs from [baseline] — the bytes last known to be
 * on disk — exactly when there is something to save, so the flag cannot fall out of step with the text. A
 * [readOnly] buffer never carries text (a [ConfigOpenResult.ViewOnly] file has none to show) and its Save
 * is dark regardless.
 *
 * [showDriftWarning] is the update-drift banner (§A9): true when the game has been updated since this
 * file's original was backed up, so a saved config may no longer fit the new build. It is a warning, not
 * a gate — editing and saving stay available — and [dismissDriftWarning] clears it once acknowledged.
 *
 * [showBackupPicker] raises the Restore chooser over [backups] — every copy this file has, original-first
 * — because a restore is only meaningful against one the user names; the list is filled by [requestRestore]
 * and emptied back to a closed picker by [dismissBackupPicker] or a chosen [restore].
 *
 * [message] is the transient status line (saved, restored, a read-only reason, a failure detail); [error]
 * is the blocking state that means there is no document to edit at all (a missing target, no elevated
 * shell, a file that could not be opened). They are kept apart because one is dismissed and editing goes
 * on, and the other is the whole of what the screen can show.
 */
data class ConfigEditorUiState(
    val fileName: String = "",
    val text: String = "",
    val baseline: String = "",
    val readOnly: Boolean = false,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val showEditNotice: Boolean = false,
    val showDriftWarning: Boolean = false,
    val backups: List<ConfigBackup> = emptyList(),
    val showBackupPicker: Boolean = false,
    val message: String? = null,
    val error: String? = null,
) {
    /** True when the buffer holds edits not yet on disk — the only condition under which Save is offered. */
    val isDirty: Boolean get() = text != baseline
}
