package com.gamecore.ui.controller

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.common.Formatters
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.controller.ControllerProfile
import com.gamecore.core.controller.ControllerProfileValidator
import com.gamecore.core.controller.ControllerTestResult
import com.gamecore.core.controller.DriftThresholds
import com.gamecore.core.controller.ResponseCurveKind
import com.gamecore.core.controller.ResponseCurveSettings
import com.gamecore.data.repository.ControllerProfileRepository
import com.gamecore.data.repository.ControllerTestResultRepository
import com.gamecore.data.repository.ControllerWriteResult
import com.gamecore.data.repository.FileShareStore
import com.gamecore.data.repository.FileWriteResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * Profiles, comparison, validated transfer and the selected controller's drift history.
 *
 * This is a second ViewModel beside [ControllerViewModel] rather than more fields in it: profiles are a
 * database concern with their own editor and import lifecycle, and the screen's input capture belongs to the
 * other one. They never write each other's state.
 *
 * Every durable write goes through a repository that validates first. An editor that loses focus, a file
 * that lies about its version, a database that refuses a row — each is a typed state the screen can show,
 * not an exception in a composable.
 */
@HiltViewModel
class ControllerProfilesViewModel @Inject constructor(
    private val profilesRepository: ControllerProfileRepository,
    private val results: ControllerTestResultRepository,
    private val files: FileShareStore,
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    private val local = MutableStateFlow(ControllerProfilesUiState())
    val state: StateFlow<ControllerProfilesUiState> = local.asStateFlow()

    private var historyJob: Job? = null

    init {
        viewModelScope.launch {
            profilesRepository.profiles
                .catch {
                    local.value = local.value.copy(
                        loading = false,
                        message = "Saved profiles could not be read.",
                        messageIsError = true,
                    )
                }
                .collect { rows -> applyProfiles(rows) }
        }
    }

    // -------------------------------------------------------------------------------- selection

    /**
     * Points the history pane at the controller the main screen selected. Profiles themselves are not
     * filtered: a profile outlives the controller being plugged in, and hiding it when the pad is unplugged
     * would leave no way to edit or export it.
     */
    fun setDevice(signature: String?, name: String?) {
        val current = local.value
        if (signature == current.deviceSignature) {
            if (name != current.deviceName) {
                local.value = current.copy(deviceName = name)
            }
            return
        }
        historyJob?.cancel()
        historyJob = null
        local.value = current.copy(deviceSignature = signature, deviceName = name, history = emptyList(), confirmClearHistory = false)
        if (signature.isNullOrBlank()) return
        historyJob = viewModelScope.launch {
            results.observeForDevice(signature, HISTORY_LIMIT)
                .catch {
                    local.value = local.value.copy(
                        message = "Drift history could not be read.",
                        messageIsError = true,
                    )
                }
                .collect { rows -> local.value = local.value.copy(history = rows) }
        }
    }

    // ----------------------------------------------------------------------------------- editor

    fun newProfile(curve: ResponseCurveSettings, thresholds: DriftThresholds) {
        val current = local.value
        local.value = current.copy(
            draft = ProfileDraft(
                deviceSignature = current.deviceSignature.orEmpty(),
                deviceName = current.deviceName ?: "Unknown controller",
                deadzone = curve.deadzone,
                curve = curve.kind,
                exponent = curve.exponent,
                driftOffset = thresholds.offset,
                driftJitter = thresholds.jitter,
            ),
            message = null,
            messageIsError = false,
            importIssues = emptyList(),
        )
    }

    fun edit(profile: ControllerProfile) {
        local.value = local.value.copy(
            draft = ProfileDraft(
                id = profile.id,
                name = profile.name,
                deviceSignature = profile.deviceSignature,
                deviceName = profile.deviceName,
                deadzone = profile.deadzone,
                curve = profile.curve,
                exponent = profile.exponent,
                driftOffset = profile.driftOffsetThreshold,
                driftJitter = profile.driftJitterThreshold,
                notes = profile.notes,
                createdAtMillis = profile.createdAtMillis,
            ),
            message = null,
            messageIsError = false,
        )
    }

    fun dismissDraft() {
        local.value = local.value.copy(draft = null)
    }

    fun setDraftName(value: String) = updateDraft { it.copy(name = value) }

    fun setDraftNotes(value: String) = updateDraft { it.copy(notes = value) }

    fun setDraftDeadzone(value: Float) = updateDraft {
        it.copy(deadzone = value.coerceIn(ControllerProfileValidator.MIN_DEADZONE, ControllerProfileValidator.MAX_DEADZONE))
    }

    fun setDraftCurve(value: ResponseCurveKind) = updateDraft { it.copy(curve = value) }

    fun setDraftExponent(value: Float) = updateDraft {
        it.copy(
            exponent = value.coerceIn(
                ResponseCurveSettings.MIN_EXPONENT,
                ResponseCurveSettings.MAX_EXPONENT,
            ),
        )
    }

    fun setDraftDriftOffset(value: Float) = updateDraft {
        it.copy(driftOffset = value.coerceIn(0f, ControllerProfileValidator.MAX_DRIFT_OFFSET))
    }

    fun setDraftDriftJitter(value: Float) = updateDraft {
        it.copy(driftJitter = value.coerceIn(0f, ControllerProfileValidator.MAX_DRIFT_JITTER))
    }

    fun saveDraft() {
        val draft = local.value.draft ?: return
        if (local.value.busy) return
        val profile = ControllerProfile(
            id = draft.id,
            name = draft.name,
            deviceSignature = draft.deviceSignature,
            deviceName = draft.deviceName,
            deadzone = draft.deadzone,
            curve = draft.curve,
            exponent = draft.exponent,
            driftOffsetThreshold = draft.driftOffset,
            driftJitterThreshold = draft.driftJitter,
            notes = draft.notes,
            createdAtMillis = draft.createdAtMillis,
            updatedAtMillis = 0L,
        )
        local.value = local.value.copy(busy = true, message = null, messageIsError = false)
        viewModelScope.launch {
            when (val outcome = profilesRepository.save(profile)) {
                is ControllerWriteResult.Saved ->
                    local.value = local.value.copy(
                        busy = false,
                        draft = null,
                        message = "Profile saved as a reference. It is not applied to any game.",
                        messageIsError = false,
                    )
                is ControllerWriteResult.Invalid ->
                    local.value = local.value.copy(
                        busy = false,
                        draft = local.value.draft?.copy(errors = outcome.errors),
                    )
                is ControllerWriteResult.Failed ->
                    local.value = local.value.copy(
                        busy = false,
                        message = "That profile could not be saved (${outcome.detail}).",
                        messageIsError = true,
                    )
            }
        }
    }

    // ----------------------------------------------------------------------------------- delete

    fun requestDelete(profile: ControllerProfile) {
        local.value = local.value.copy(pendingDelete = profile)
    }

    fun dismissDelete() {
        local.value = local.value.copy(pendingDelete = null)
    }

    fun confirmDelete() {
        val target = local.value.pendingDelete ?: return
        local.value = local.value.copy(pendingDelete = null, busy = true, message = null)
        viewModelScope.launch {
            when (val outcome = profilesRepository.delete(target.id)) {
                is ControllerWriteResult.Saved ->
                    local.value = local.value.copy(
                        busy = false,
                        message = "Deleted \"${target.name}\".",
                        messageIsError = false,
                    )
                is ControllerWriteResult.Invalid ->
                    local.value = local.value.copy(busy = false, message = outcome.errors.first(), messageIsError = true)
                is ControllerWriteResult.Failed ->
                    local.value = local.value.copy(
                        busy = false,
                        message = "That profile could not be deleted (${outcome.detail}).",
                        messageIsError = true,
                    )
            }
        }
    }

    // -------------------------------------------------------------------------------- comparison

    fun selectCompareLeft(id: Long) {
        val current = local.value
        local.value = current.copy(
            compareLeft = id,
            compareRight = current.compareRight?.takeIf { it != id },
        )
    }

    fun selectCompareRight(id: Long) {
        val current = local.value
        local.value = current.copy(
            compareRight = id,
            compareLeft = current.compareLeft?.takeIf { it != id },
        )
    }

    // ---------------------------------------------------------------------------------- transfer

    fun exportAll() {
        if (local.value.busy) return
        viewModelScope.launch {
            local.value = local.value.copy(
                busy = true,
                message = null,
                messageIsError = false,
                importIssues = emptyList(),
                share = null,
            )
            val text = runCatching { profilesRepository.exportAll() }.getOrElse {
                local.value = local.value.copy(
                    busy = false,
                    message = "Saved profiles could not be read for export.",
                    messageIsError = true,
                )
                return@launch
            }
            val fileName = "gamecore-controller-profiles-${Formatters.fileTimestamp(System.currentTimeMillis())}.json"
            when (
                val write = files.writeText(
                    subdir = "controller-profiles",
                    fileName = fileName,
                    text = text,
                    keep = 3,
                    accept = FileShareStore.byExtension(".json"),
                )
            ) {
                is FileWriteResult.Stored -> local.value = local.value.copy(
                    busy = false,
                    share = write.uri?.let { ProfilesShare(it) },
                    message = if (write.uri == null) {
                        "Profile export saved inside GameCore. No other app offered a share URI."
                    } else {
                        "Profile export ready: ${local.value.profiles.size} " +
                            Formatters.count(local.value.profiles.size, "profile") + "."
                    },
                    messageIsError = false,
                )
                is FileWriteResult.Failed -> local.value = local.value.copy(
                    busy = false,
                    message = "That export could not be written (${write.detail}).",
                    messageIsError = true,
                )
            }
        }
    }

    fun shareIntent(): android.content.Intent? {
        val share = local.value.share ?: return null
        return files.buildShareIntent(share.uri, share.mimeType)
    }

    fun onShareFailed() {
        local.value = local.value.copy(
            message = "No app accepted that export. It is still saved inside GameCore.",
            messageIsError = true,
        )
    }

    fun import(uri: Uri) {
        if (local.value.busy) return
        viewModelScope.launch {
            local.value = local.value.copy(
                busy = true,
                message = null,
                messageIsError = false,
                importIssues = emptyList(),
                share = null,
            )
            val text = withContext(io) { readLimited(uri) }
            if (text == null) {
                local.value = local.value.copy(
                    busy = false,
                    message = "That file could not be opened, or it is larger than $MAX_IMPORT_KIB KB.",
                    messageIsError = true,
                )
                return@launch
            }
            val outcome = profilesRepository.import(text)
            val summary = buildString {
                append("Imported ${outcome.imported} ")
                append(Formatters.count(outcome.imported, "profile"))
                if (outcome.rejected > 0) {
                    append("; ${outcome.rejected} ")
                    append(Formatters.count(outcome.rejected, "entry", "entries"))
                    append(" rejected")
                }
                append(".")
            }
            local.value = local.value.copy(
                busy = false,
                message = outcome.error ?: summary,
                messageIsError = outcome.error != null,
                importIssues = outcome.issues,
            )
        }
    }

    fun dismissMessage() {
        local.value = local.value.copy(message = null, importIssues = emptyList())
    }

    fun dismissShare() {
        local.value = local.value.copy(share = null)
    }

    // ----------------------------------------------------------------------------------- history

    fun requestClearHistory() {
        if (local.value.deviceSignature.isNullOrBlank() || local.value.history.isEmpty()) return
        local.value = local.value.copy(confirmClearHistory = true)
    }

    fun dismissClearHistory() {
        local.value = local.value.copy(confirmClearHistory = false)
    }

    fun confirmClearHistory() {
        val signature = local.value.deviceSignature ?: return
        local.value = local.value.copy(confirmClearHistory = false, busy = true, message = null)
        viewModelScope.launch {
            when (val outcome = results.clearForDevice(signature)) {
                is ControllerWriteResult.Saved ->
                    local.value = local.value.copy(busy = false, message = "Drift history cleared.", messageIsError = false)
                is ControllerWriteResult.Invalid ->
                    local.value = local.value.copy(busy = false, message = outcome.errors.first(), messageIsError = true)
                is ControllerWriteResult.Failed ->
                    local.value = local.value.copy(
                        busy = false,
                        message = "That history could not be cleared (${outcome.detail}).",
                        messageIsError = true,
                    )
            }
        }
    }

    private fun applyProfiles(rows: List<ControllerProfile>) {
        val current = local.value
        val left = current.compareLeft?.takeIf { id -> rows.any { it.id == id } } ?: rows.firstOrNull()?.id
        val right = current.compareRight
            ?.takeIf { id -> id != left && rows.any { it.id == id } }
            ?: rows.firstOrNull { it.id != left }?.id
        local.value = current.copy(
            loading = false,
            profiles = rows,
            compareLeft = left,
            compareRight = right,
        )
    }

    private fun updateDraft(transform: (ProfileDraft) -> ProfileDraft) {
        val draft = local.value.draft ?: return
        local.value = local.value.copy(draft = transform(draft).copy(errors = emptyList()))
    }

    /** Never reads an unbounded file into memory; a profile export is measured in kilobytes. */
    private fun readLimited(uri: Uri): String? {
        return runCatching {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_IMPORT_BYTES) return null
                    output.write(buffer, 0, read)
                }
                output.toByteArray().toString(Charsets.UTF_8)
            }
        }.getOrNull()
    }

    private companion object {
        const val HISTORY_LIMIT = 20
        const val MAX_IMPORT_KIB = 256
        const val MAX_IMPORT_BYTES = MAX_IMPORT_KIB * 1024
    }
}
