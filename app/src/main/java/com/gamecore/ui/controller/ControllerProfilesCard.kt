package com.gamecore.ui.controller

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.core.common.Formatters
import com.gamecore.core.controller.ControllerProfile
import com.gamecore.core.controller.ControllerProfileComparator
import com.gamecore.core.controller.ControllerTestResult
import com.gamecore.core.controller.DriftThresholds
import com.gamecore.core.controller.ResponseCurveKind
import com.gamecore.core.controller.ResponseCurveSettings
import com.gamecore.core.input.ControllerDevice
import com.gamecore.ui.components.ActionRow
import com.gamecore.ui.components.ChoiceRow
import com.gamecore.ui.components.KeyValueRow
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.RowDivider
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.startIntentSafely
import java.util.Locale

/**
 * Saved controller references, comparison, transfer and this controller's drift history.
 *
 * Two different things share the card because they share a key: both are filed under the device's capability
 * signature. Profiles remain editable and exportable after the controller is unplugged; the history pane is
 * scoped to the controller currently selected on the screen above. Nothing here is applied to a game — the
 * note at the top says so, and the only "apply" action in the feature does not exist.
 */
@Composable
fun ControllerProfilesCard(
    device: ControllerDevice,
    curve: ResponseCurveSettings,
    thresholds: DriftThresholds,
    modifier: Modifier = Modifier,
    viewModel: ControllerProfilesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val signature = remember(device) { device.capabilitySignature() }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.import(uri)
    }

    LaunchedEffect(signature) {
        viewModel.setDevice(signature, device.name.ifBlank { "Device ${device.deviceId}" })
    }

    // A written export is staged as a one-shot share; fire it once and let the ViewModel clear it.
    LaunchedEffect(state.share) {
        if (state.share == null) return@LaunchedEffect
        val intent = viewModel.shareIntent() ?: return@LaunchedEffect
        val launched = context.startIntentSafely(
            Intent.createChooser(intent, "Share controller profiles"),
        )
        if (launched) viewModel.dismissShare() else viewModel.onShareFailed()
    }

    SectionCard(
        title = "Profiles & drift history",
        modifier = modifier,
        subtitle = "Saved references, comparison and this controller's drift history",
        icon = Icons.Filled.Memory,
        action = {
            TextButton(
                onClick = { viewModel.newProfile(curve, thresholds) },
                enabled = state.draft == null && state.deviceSignature != null,
            ) {
                Text("New")
            }
        },
    ) {
        NoteBanner(
            text = "Profiles store a reference dead zone, curve and drift tolerance. They are not applied " +
                "to any game or to the controller; GameCore has no supported mechanism to do that.",
            tone = Tone.Muted,
            icon = Icons.Filled.Info,
        )

        state.deviceSignature?.let { signature ->
            Spacer(modifier = Modifier.height(8.dp))
            KeyValueRow(label = "Capability key", value = signature, tone = Tone.Muted)
        }

        state.message?.let { message ->
            Spacer(modifier = Modifier.height(10.dp))
            NoteBanner(
                text = message,
                tone = if (state.messageIsError) Tone.Danger else Tone.Accent,
                icon = Icons.Filled.Info,
                action = { TextButton(onClick = viewModel::dismissMessage) { Text("OK") } },
            )
        }
        if (state.importIssues.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            NoteBanner(
                text = state.importIssues.joinToString("\n") { "• $it" },
                tone = Tone.Warning,
                icon = Icons.Filled.Info,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        val draft = state.draft
        if (draft != null) {
            ProfileEditor(draft = draft, busy = state.busy, viewModel = viewModel)
        } else {
            ProfileList(state = state, viewModel = viewModel)
        }

        RowDivider(modifier = Modifier.padding(vertical = 12.dp))
        HistorySection(
            state = state,
            onClear = viewModel::requestClearHistory,
        )

        RowDivider(modifier = Modifier.padding(vertical = 12.dp))
        TransferSection(
            state = state,
            onExport = viewModel::exportAll,
            onImport = { importPicker.launch(arrayOf("application/json", "text/plain")) },
            onShare = {
                val intent = viewModel.shareIntent()
                if (intent == null || !context.startIntentSafely(Intent.createChooser(intent, "Share controller profiles"))) {
                    viewModel.onShareFailed()
                }
            },
        )
    }

    state.pendingDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            icon = { androidx.compose.material3.Icon(Icons.Filled.Delete, contentDescription = null) },
            title = { Text("Delete profile?") },
            text = {
                Text(
                    "\"${profile.name}\" will be removed from this device. Saved drift results are not " +
                        "part of a profile and are kept.",
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelete) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDelete) { Text("Cancel") }
            },
        )
    }

    if (state.confirmClearHistory) {
        AlertDialog(
            onDismissRequest = viewModel::dismissClearHistory,
            icon = { androidx.compose.material3.Icon(Icons.Filled.Delete, contentDescription = null) },
            title = { Text("Clear drift history?") },
            text = {
                Text(
                    "Every saved drift result for ${state.deviceName ?: "this controller"} will be removed. " +
                        "Profiles and other controllers' results are kept.",
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmClearHistory) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissClearHistory) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ProfileList(
    state: ControllerProfilesUiState,
    viewModel: ControllerProfilesViewModel,
) {
    if (state.loading) {
        Text("Loading saved profiles…", style = MaterialTheme.typography.bodyMedium)
        return
    }
    if (state.profiles.isEmpty()) {
        Text(
            text = "No profiles saved yet. \"New\" starts one from the curve settings on this screen.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Text("Saved profiles", style = MaterialTheme.typography.labelLarge)
    state.profiles.forEach { profile ->
        ProfileRow(
            profile = profile,
            onEdit = { viewModel.edit(profile) },
            onDelete = { viewModel.requestDelete(profile) },
        )
    }

    if (state.profiles.size >= 2) {
        Spacer(modifier = Modifier.height(12.dp))
        Text("Compare", style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(4.dp))
        val left = state.profiles.firstOrNull { it.id == state.compareLeft }
        val right = state.profiles.firstOrNull { it.id == state.compareRight }
        ProfilePicker(
            label = "A",
            profiles = state.profiles,
            selectedId = state.compareLeft,
            onSelect = viewModel::selectCompareLeft,
        )
        Spacer(modifier = Modifier.height(6.dp))
        ProfilePicker(
            label = "B",
            profiles = state.profiles,
            selectedId = state.compareRight,
            onSelect = viewModel::selectCompareRight,
        )
        if (left != null && right != null) {
            Spacer(modifier = Modifier.height(8.dp))
            if (left.id == right.id) {
                Text(
                    text = "Pick two different profiles to compare.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                ControllerProfileComparator.compare(left, right).forEach { difference ->
                    KeyValueRow(
                        label = difference.label,
                        value = if (difference.differs) {
                            "${difference.left} → ${difference.right}"
                        } else {
                            difference.left
                        },
                        tone = if (difference.differs) Tone.Warning else Tone.Muted,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileRow(
    profile: ControllerProfile,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(profile.name, style = MaterialTheme.typography.titleSmall)
            Text(
                text = "${profile.deviceName} · ${profile.curve.label} · " +
                    "${formatPercent(profile.deadzone)} dead zone",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = onEdit,
            modifier = Modifier.semantics { contentDescription = "Edit ${profile.name}" },
        ) {
            Text("Edit")
        }
        TextButton(
            onClick = onDelete,
            modifier = Modifier.semantics { contentDescription = "Delete ${profile.name}" },
        ) {
            Text("Delete")
        }
    }
}

@Composable
private fun ProfilePicker(
    label: String,
    profiles: List<ControllerProfile>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = profiles.firstOrNull { it.id == selectedId }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Compare profile $label: ${selected?.name ?: "none"}" },
            ) {
                Text(
                    text = selected?.name ?: "Choose a profile",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                profiles.forEach { profile ->
                    DropdownMenuItem(
                        text = { Text(profile.name) },
                        onClick = {
                            onSelect(profile.id)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileEditor(
    draft: ProfileDraft,
    busy: Boolean,
    viewModel: ControllerProfilesViewModel,
) {
    Text(
        text = if (draft.id == 0L) "New profile" else "Edit profile",
        style = MaterialTheme.typography.titleSmall,
    )
    Spacer(modifier = Modifier.height(6.dp))
    OutlinedTextField(
        value = draft.name,
        onValueChange = viewModel::setDraftName,
        label = { Text("Profile name") },
        singleLine = true,
        enabled = !busy,
        isError = draft.errors.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
    )
    KeyValueRow(label = "Controller", value = draft.deviceName)
    LabSlider(
        label = "Dead zone",
        valueText = formatPercent(draft.deadzone),
        value = draft.deadzone,
        range = 0f..0.50f,
        onChange = viewModel::setDraftDeadzone,
        enabled = !busy,
    )
    Text("Curve", style = MaterialTheme.typography.labelLarge)
    Spacer(modifier = Modifier.height(6.dp))
    ChoiceRow(
        options = ResponseCurveKind.entries.toList(),
        selected = draft.curve,
        onSelect = viewModel::setDraftCurve,
        label = { it.label },
        perRow = 3,
        enabled = !busy,
    )
    LabSlider(
        label = "Exponent",
        valueText = String.format(Locale.US, "%.2f", draft.exponent),
        value = draft.exponent,
        range = 1f..4f,
        onChange = viewModel::setDraftExponent,
        enabled = !busy && draft.curve == ResponseCurveKind.EXPONENTIAL,
    )
    LabSlider(
        label = "Drift offset threshold",
        valueText = formatPercent(draft.driftOffset),
        value = draft.driftOffset,
        range = 0f..0.30f,
        onChange = viewModel::setDraftDriftOffset,
        enabled = !busy,
    )
    LabSlider(
        label = "Drift jitter threshold",
        valueText = formatPercent(draft.driftJitter),
        value = draft.driftJitter,
        range = 0f..0.10f,
        onChange = viewModel::setDraftDriftJitter,
        enabled = !busy,
    )
    OutlinedTextField(
        value = draft.notes,
        onValueChange = viewModel::setDraftNotes,
        label = { Text("Notes (optional)") },
        minLines = 2,
        maxLines = 4,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    )
    if (draft.errors.isNotEmpty()) {
        Spacer(modifier = Modifier.height(6.dp))
        NoteBanner(
            text = draft.errors.joinToString("\n") { "• $it" },
            tone = Tone.Danger,
            icon = Icons.Filled.Info,
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    ActionRow {
        Button(onClick = viewModel::saveDraft, enabled = !busy) {
            Text(if (busy) "Saving…" else "Save profile")
        }
        OutlinedButton(onClick = viewModel::dismissDraft, enabled = !busy) {
            Text("Cancel")
        }
    }
}

@Composable
private fun HistorySection(
    state: ControllerProfilesUiState,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Drift history",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f),
        )
        if (state.history.isNotEmpty()) {
            TextButton(onClick = onClear) { Text("Clear") }
        }
    }
    if (state.history.isEmpty()) {
        Text(
            text = "No saved drift runs for this controller. A conclusive run can be saved from the Drift panel.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    state.history.forEach { result ->
        HistoryRow(result)
    }
}

@Composable
private fun HistoryRow(result: ControllerTestResult) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = Formatters.dateTime(result.startedAtMillis),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            StatusChip(result.verdict.label, result.verdict.tone())
        }
        Text(
            text = "${result.stick.label} · offset ${formatPercent(result.offsetMagnitude)} · " +
                "jitter ${formatPercent(result.jitter)} · ${result.sampleCount} readings over " +
                "${String.format(Locale.US, "%.1f", result.durationMillis / 1000f)} s",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TransferSection(
    state: ControllerProfilesUiState,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onShare: () -> Unit,
) {
    Text("Transfer", style = MaterialTheme.typography.labelLarge)
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = "Export writes a versioned JSON file of reference profiles. Import validates every entry and " +
            "never deletes or overwrites a profile already on this device.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(6.dp))
    ActionRow {
        Button(onClick = onExport, enabled = !state.busy) {
            Text(if (state.busy) "Working…" else "Export profiles")
        }
        OutlinedButton(onClick = onImport, enabled = !state.busy) {
            Text("Import")
        }
    }
    if (state.share != null) {
        Spacer(modifier = Modifier.height(2.dp))
        TextButton(onClick = onShare) { Text("Share again") }
    }
}
