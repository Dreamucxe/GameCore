package com.gamecore.ui.wheels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.core.model.AppSettings
import com.gamecore.core.model.WheelAccent
import com.gamecore.core.model.WheelButtonConfig
import com.gamecore.core.model.WheelButtonIcon
import com.gamecore.core.model.WheelButtonStyle
import com.gamecore.core.model.WheelConfig
import com.gamecore.core.model.WheelOperatingMode
import com.gamecore.core.model.WheelProfile
import com.gamecore.core.model.WheelResponseCurve
import com.gamecore.core.model.WheelVisualStyle
import com.gamecore.core.overlay.OverlayPalette
import com.gamecore.core.overlay.WheelOverlay
import com.gamecore.core.wheel.InjectionBackendStatus
import com.gamecore.core.wheel.WheelTouchProcessor
import com.gamecore.ui.components.ActionRow
import com.gamecore.ui.components.ChoiceRow
import com.gamecore.ui.components.ConfirmDialog
import com.gamecore.ui.components.KeyValueRow
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.OnResume
import com.gamecore.ui.components.PlainCard
import com.gamecore.ui.components.RowDivider
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.SectionGap
import com.gamecore.ui.components.SliderRow
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.SwitchRow
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.colour
import com.gamecore.ui.components.screenAspectRatio
import com.gamecore.ui.games.GameIcon
import java.util.Locale

/**
 * The complete High Sensitivity Wheel control surface.
 *
 * [WheelsScreen] is wiring only. [WheelsContent] receives immutable state and callbacks, keeping the screen
 * renderable without Hilt while the ViewModel owns durable settings, app discovery and runtime actions.
 * Every slider previews in ViewModel state and commits once on release; mode is runtime-only and therefore
 * cannot come back enabled after a process restart.
 */
@Composable
fun WheelsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WheelsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    OnResume { viewModel.refreshPermission() }

    WheelsContent(
        state = state,
        onBack = onBack,
        actions = WheelsActions(
            toggleWheel = viewModel::toggleWheel,
            acceptRisk = viewModel::acceptRisk,
            dismissRisk = viewModel::dismissRisk,
            setMode = viewModel::setMode,
            toggleLock = viewModel::toggleLock,
            resetPosition = viewModel::resetPosition,
            refreshBackend = viewModel::refreshBackend,
            refreshDiagnostics = viewModel::refreshDiagnostics,
            selectProfile = viewModel::selectProfile,
            createProfile = viewModel::createProfile,
            duplicateProfile = viewModel::duplicateActiveProfile,
            renameProfile = viewModel::renameActiveProfile,
            deleteProfile = viewModel::deleteProfile,
            restoreProfile = viewModel::restoreActiveProfileDefaults,
            setSensitivity = viewModel::setSensitivity,
            setXSensitivity = viewModel::setXSensitivity,
            setYSensitivity = viewModel::setYSensitivity,
            setRadius = viewModel::setRadius,
            setDeadzone = viewModel::setDeadzone,
            setDiameter = viewModel::setDiameter,
            setOpacity = viewModel::setOpacity,
            setSmoothing = viewModel::setSmoothing,
            commitProfile = viewModel::commitProfileDraft,
            setCurve = viewModel::setCurve,
            setStyle = viewModel::setStyle,
            setAccent = viewModel::setAccent,
            setInvertX = viewModel::setInvertX,
            setInvertY = viewModel::setInvertY,
            setShowVector = viewModel::setShowVector,
            setProfileHaptics = viewModel::setProfileHaptics,
            setAutoSwitch = viewModel::setAutoSwitch,
            bindGame = viewModel::bindGame,
            unbindGame = viewModel::unbindGame,
            reloadApps = viewModel::loadApps,
            setButtonVisible = viewModel::setButtonVisible,
            setButtonSize = viewModel::setButtonSize,
            setButtonOpacity = viewModel::setButtonOpacity,
            commitButton = viewModel::commitButtonDraft,
            setButtonStyle = viewModel::setButtonStyle,
            setButtonIcon = viewModel::setButtonIcon,
            setButtonAccent = viewModel::setButtonAccent,
            setButtonHaptics = viewModel::setButtonHaptics,
            setButtonIdleFade = viewModel::setButtonIdleFade,
            setButtonSnap = viewModel::setButtonSnapToEdge,
            setPrecisionMultiplier = viewModel::setPrecisionMultiplier,
            commitPrecisionMultiplier = viewModel::commitPrecisionMultiplier,
            setTurboMultiplier = viewModel::setTurboMultiplier,
            commitTurboMultiplier = viewModel::commitTurboMultiplier,
            setGuideEnabled = viewModel::setGuideEnabled,
            setGuideRadius = viewModel::setGuideRadius,
            commitGuideRadius = viewModel::commitGuideRadius,
            setAimLabSensitivity = viewModel::setAimLabSensitivity,
            commitAimLabSensitivity = viewModel::commitAimLabSensitivity,
            resetConfiguration = viewModel::resetConfiguration,
        ),
        modifier = modifier,
    )
}

/** All screen events, grouped so the state-driven content has no app dependency. */
private data class WheelsActions(
    val toggleWheel: () -> Unit,
    val acceptRisk: () -> Unit,
    val dismissRisk: () -> Unit,
    val setMode: (WheelOperatingMode) -> Unit,
    val toggleLock: () -> Unit,
    val resetPosition: () -> Unit,
    val refreshBackend: () -> Unit,
    val refreshDiagnostics: () -> Unit,
    val selectProfile: (String) -> Unit,
    val createProfile: (String) -> Unit,
    val duplicateProfile: (String) -> Unit,
    val renameProfile: (String) -> Unit,
    val deleteProfile: (String) -> Unit,
    val restoreProfile: () -> Unit,
    val setSensitivity: (Float) -> Unit,
    val setXSensitivity: (Float) -> Unit,
    val setYSensitivity: (Float) -> Unit,
    val setRadius: (Int) -> Unit,
    val setDeadzone: (Int) -> Unit,
    val setDiameter: (Int) -> Unit,
    val setOpacity: (Float) -> Unit,
    val setSmoothing: (Float) -> Unit,
    val commitProfile: () -> Unit,
    val setCurve: (WheelResponseCurve) -> Unit,
    val setStyle: (WheelVisualStyle) -> Unit,
    val setAccent: (WheelAccent) -> Unit,
    val setInvertX: (Boolean) -> Unit,
    val setInvertY: (Boolean) -> Unit,
    val setShowVector: (Boolean) -> Unit,
    val setProfileHaptics: (Boolean) -> Unit,
    val setAutoSwitch: (Boolean) -> Unit,
    val bindGame: (String, String) -> Unit,
    val unbindGame: (String) -> Unit,
    val reloadApps: () -> Unit,
    val setButtonVisible: (Boolean) -> Unit,
    val setButtonSize: (Int) -> Unit,
    val setButtonOpacity: (Float) -> Unit,
    val commitButton: () -> Unit,
    val setButtonStyle: (WheelButtonStyle) -> Unit,
    val setButtonIcon: (WheelButtonIcon) -> Unit,
    val setButtonAccent: (WheelAccent) -> Unit,
    val setButtonHaptics: (Boolean) -> Unit,
    val setButtonIdleFade: (Boolean) -> Unit,
    val setButtonSnap: (Boolean) -> Unit,
    val setPrecisionMultiplier: (Float) -> Unit,
    val commitPrecisionMultiplier: () -> Unit,
    val setTurboMultiplier: (Float) -> Unit,
    val commitTurboMultiplier: () -> Unit,
    val setGuideEnabled: (Boolean) -> Unit,
    val setGuideRadius: (Int) -> Unit,
    val commitGuideRadius: () -> Unit,
    val setAimLabSensitivity: (Int) -> Unit,
    val commitAimLabSensitivity: () -> Unit,
    val resetConfiguration: () -> Unit,
)

private enum class ProfileNameAction { CREATE, DUPLICATE, RENAME }

@Composable
private fun WheelsContent(
    state: WheelsUiState,
    onBack: () -> Unit,
    actions: WheelsActions,
    modifier: Modifier = Modifier,
) {
    var nameAction by remember { mutableStateOf<ProfileNameAction?>(null) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var appPickerOpen by remember { mutableStateOf(false) }
    var resetOpen by remember { mutableStateOf(false) }
    val padded = Modifier.padding(horizontal = ScreenPadding)

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "High Sensitivity Wheel",
                subtitle = "Amplified virtual joystick · Shizuku input backend",
                onBack = onBack,
            )
        }
        item { OverviewCard(state, actions, padded) }
        item { WheelControlsCard(state, actions, padded) }
        item { SensitivityCard(state, actions, padded) }
        item { AppearanceCard(state, actions, padded) }
        item {
            ProfilesCard(
                state = state,
                actions = actions,
                onNameAction = { nameAction = it },
                onDelete = { pendingDelete = it },
                modifier = padded,
            )
        }
        item {
            BindingsCard(
                state = state,
                actions = actions,
                onAdd = { appPickerOpen = true },
                modifier = padded,
            )
        }
        item { FloatingButtonCard(state, actions, padded) }
        item { AdvancedCard(state, actions, onReset = { resetOpen = true }, modifier = padded) }
        item { LegacyGuideCard(state, actions, padded) }
    }

    nameAction?.let { action ->
        ProfileNameDialog(
            action = action,
            currentName = state.activeProfile.name,
            onDismiss = { nameAction = null },
            onConfirm = { name ->
                when (action) {
                    ProfileNameAction.CREATE -> actions.createProfile(name)
                    ProfileNameAction.DUPLICATE -> actions.duplicateProfile(name)
                    ProfileNameAction.RENAME -> actions.renameProfile(name)
                }
                nameAction = null
            },
        )
    }
    pendingDelete?.let { id ->
        val profile = state.config.profiles.firstOrNull { it.id == id }
        ConfirmDialog(
            title = "Delete wheel profile?",
            message = "\"${profile?.name ?: "This profile"}\" and its game assignments will be removed. " +
                "This cannot be undone.",
            confirmLabel = "Delete",
            onConfirm = {
                actions.deleteProfile(id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
    if (appPickerOpen) {
        AppBindingDialog(
            state = state,
            onBind = { packageName ->
                actions.bindGame(packageName, state.config.activeProfileId)
                appPickerOpen = false
            },
            onReload = actions.reloadApps,
            onDismiss = { appPickerOpen = false },
        )
    }
    if (resetOpen) {
        ConfirmDialog(
            title = "Reset the High Sensitivity Wheel?",
            message = "All wheel profiles, game assignments and floating-button customisation will return " +
                "to defaults. Any active injected pointer is cancelled first.",
            confirmLabel = "Reset wheel",
            onConfirm = {
                actions.resetConfiguration()
                resetOpen = false
            },
            onDismiss = { resetOpen = false },
        )
    }
    if (state.runtime.consentRequired) {
        ConfirmDialog(
            title = "Enable touch injection at your own risk?",
            message = "GameCore will use Shizuku to inject synthetic touch events into other apps. " +
                "Injected input can misfire, can conflict with a game's rules or anti-cheat, and may " +
                "behave differently depending on the device and game. Multitouch coexistence and " +
                "end-to-end latency are not guaranteed. Use it only where you are allowed to, and keep " +
                "the floating button reachable so you can switch it off immediately.",
            confirmLabel = "Enable at my own risk",
            dismissLabel = "Not now",
            isDestructive = false,
            onConfirm = actions.acceptRisk,
            onDismiss = actions.dismissRisk,
        )
    }
}

@Composable
private fun OverviewCard(state: WheelsUiState, actions: WheelsActions, modifier: Modifier) {
    val runtime = state.runtime
    val wheelTone = when {
        runtime.lastError != null -> Tone.Danger
        runtime.injecting -> Tone.Good
        runtime.arming -> Tone.Warning
        runtime.mode != WheelOperatingMode.DISABLED -> Tone.Accent
        else -> Tone.Muted
    }
    SectionCard(
        title = "Overview",
        subtitle = "What is active now, not what was saved last time",
        modifier = modifier,
        action = {
            StatusChip(
                text = when {
                    runtime.arming -> "Starting"
                    runtime.injecting -> "Injecting"
                    else -> runtime.mode.label
                },
                tone = wheelTone,
            )
        },
    ) {
        Column(modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            KeyValueRow("Wheel", runtime.mode.label, wheelTone)
            KeyValueRow(
                "Shizuku backend",
                runtime.injector.status.label,
                if (runtime.injector.status == InjectionBackendStatus.READY) Tone.Good else Tone.Warning,
            )
            KeyValueRow(
                "Overlay permission",
                if (state.hasOverlayPermission) "Granted" else "Required",
                if (state.hasOverlayPermission) Tone.Good else Tone.Warning,
            )
            KeyValueRow(
                "Input injection",
                when {
                    runtime.injecting -> "Dispatcher accepting events"
                    runtime.injector.canInject -> "Ready, wheel not in gaming mode"
                    else -> "Not ready"
                },
                if (runtime.injecting) Tone.Good else Tone.Muted,
            )
            KeyValueRow(
                "Foreground target",
                runtime.gameLabel.ifBlank { runtime.gamePackage.orEmpty() }.ifBlank { "No game detected" },
            )
            KeyValueRow(
                "Current profile",
                runtime.effectiveProfile.name + if (runtime.perGameProfile) " · game binding" else "",
            )
            KeyValueRow("Sensitivity", multiplier(runtime.effectiveProfile.sensitivity))
            KeyValueRow("Clamp radius", "${runtime.effectiveProfile.radiusPx} px")
        }
        if (!state.hasOverlayPermission) {
            SectionGap(8)
            NoteBanner(
                text = "Display-over-other-apps permission is required for the wheel and its emergency " +
                    "button. Grant it from GameCore Settings before entering positioning or gaming mode.",
                tone = Tone.Warning,
                icon = Icons.Filled.Info,
            )
        }
        SectionGap(8)
        NoteBanner(
            text = "Use at your own risk. The wheel injects synthetic touches through Shizuku. It can " +
                "misfire, conflict with a game's rules or anti-cheat, and may behave differently on your " +
                "device. Keep the floating button reachable to turn it off.",
            tone = Tone.Warning,
            icon = Icons.Filled.Info,
        )
        runtime.lastError?.let {
            SectionGap(8)
            NoteBanner(text = it, tone = Tone.Danger, icon = Icons.Filled.Info)
        }
        SectionGap(10)
        ActionRow {
            OutlinedButton(onClick = actions.refreshBackend, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Check backend")
            }
            Button(
                onClick = actions.toggleWheel,
                enabled = state.hasOverlayPermission,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (state.wheelEnabled) "Disable" else "Enable")
            }
        }
    }
}

@Composable
private fun WheelControlsCard(state: WheelsUiState, actions: WheelsActions, modifier: Modifier) {
    val runtime = state.runtime
    SectionCard(
        title = "Wheel controls",
        subtitle = "Position without injection, calibrate safely, then enter gaming mode",
        modifier = modifier,
    ) {
        SwitchRow(
            title = "Enable wheel",
            checked = state.wheelEnabled,
            onCheckedChange = { actions.toggleWheel() },
            enabled = state.hasOverlayPermission,
            description = "Enabling enters gaming mode after the Shizuku injector reports ready. The " +
                "first enable requires accepting the injection risk warning. The wheel never re-enables " +
                "itself after a restart.",
        )
        Text("Operating mode", style = MaterialTheme.typography.labelLarge)
        SectionGap(6)
        ChoiceRow(
            options = listOf(
                WheelOperatingMode.POSITIONING,
                WheelOperatingMode.GAMING,
                WheelOperatingMode.CALIBRATION,
            ),
            selected = runtime.mode.takeUnless { it == WheelOperatingMode.DISABLED },
            onSelect = actions.setMode,
            label = { it.label },
            enabled = state.hasOverlayPermission,
            perRow = 3,
        )
        SectionGap(6)
        SwitchRow(
            title = "Lock wheel position",
            checked = runtime.effectiveProfile.locked,
            onCheckedChange = { actions.toggleLock() },
            description = "Locks the wheel while playing. An intentional drag in positioning mode " +
                "unlocks and places it, and the floating emergency button remains independently " +
                "draggable and can always turn the wheel off.",
        )
        TextButton(onClick = actions.resetPosition) { Text("Reset wheel position") }

        if (runtime.mode == WheelOperatingMode.POSITIONING) {
            NoteBanner(
                text = "Drag the wheel itself to place it. A position lock unlocks on the first drag. " +
                    "This mode consumes touches inside the ring but does not inject them into the app " +
                    "underneath.",
                tone = Tone.Accent,
                icon = Icons.Filled.Info,
            )
        }
        if (runtime.mode == WheelOperatingMode.CALIBRATION) {
            val frame = runtime.calibration
            NoteBanner(
                text = "Calibration runs the exact profile maths but never calls the injector.",
                tone = Tone.Accent,
                icon = Icons.Filled.Info,
            )
            if (frame != null) {
                KeyValueRow("Raw movement", "${oneDecimal(frame.rawDx)}, ${oneDecimal(frame.rawDy)} px")
                KeyValueRow("Output movement", "${oneDecimal(frame.outputDx)}, ${oneDecimal(frame.outputDy)} px")
                KeyValueRow(
                    "Clamp use",
                    "${(WheelTouchProcessor.throwFraction(frame, runtime.effectiveProfile) * 100).toInt()}%",
                )
            }
        }
    }
}

@Composable
private fun SensitivityCard(state: WheelsUiState, actions: WheelsActions, modifier: Modifier) {
    val profile = state.editingProfile
    SectionCard(
        title = "Sensitivity",
        subtitle = "Saved in ${profile.name}",
        modifier = modifier,
    ) {
        Text("Quick values", style = MaterialTheme.typography.labelLarge)
        SectionGap(6)
        ChoiceRow(
            options = WheelConfig.SENSITIVITIES,
            selected = WheelConfig.SENSITIVITIES.firstOrNull { nearlyEqual(it, profile.sensitivity) },
            onSelect = {
                actions.setSensitivity(it)
                actions.commitProfile()
            },
            label = ::multiplier,
            perRow = 3,
        )
        SliderRow(
            title = "Sensitivity",
            value = (profile.sensitivity * 10).toInt(),
            range = (WheelProfile.MIN_SENSITIVITY * 10).toInt()..(WheelProfile.MAX_SENSITIVITY * 10).toInt(),
            onValueChange = { actions.setSensitivity(it / 10f) },
            valueLabel = multiplier(profile.sensitivity),
            description = "Uniform movement gain. 1.00× is neutral; 4.00× is the maximum.",
            onValueChangeFinished = actions.commitProfile,
        )
        SliderRow(
            title = "Horizontal gain",
            value = (profile.xSensitivity * 100).toInt(),
            range = (WheelProfile.MIN_AXIS_GAIN * 100).toInt()..(WheelProfile.MAX_AXIS_GAIN * 100).toInt(),
            onValueChange = { actions.setXSensitivity(it / 100f) },
            valueLabel = multiplier(profile.xSensitivity),
            onValueChangeFinished = actions.commitProfile,
        )
        SliderRow(
            title = "Vertical gain",
            value = (profile.ySensitivity * 100).toInt(),
            range = (WheelProfile.MIN_AXIS_GAIN * 100).toInt()..(WheelProfile.MAX_AXIS_GAIN * 100).toInt(),
            onValueChange = { actions.setYSensitivity(it / 100f) },
            valueLabel = multiplier(profile.ySensitivity),
            onValueChangeFinished = actions.commitProfile,
        )
        SliderRow(
            title = "Clamp radius",
            value = profile.radiusPx,
            range = WheelProfile.RADIUS_RANGE,
            onValueChange = actions.setRadius,
            valueLabel = "${profile.radiusPx} px",
            description = "Maximum injected displacement from the point where the gesture began.",
            onValueChangeFinished = actions.commitProfile,
        )
        SliderRow(
            title = "Deadzone",
            value = profile.deadzonePx,
            range = WheelProfile.DEADZONE_RANGE,
            onValueChange = actions.setDeadzone,
            valueLabel = "${profile.deadzonePx} px",
            description = "Radial movement ignored around the initial touch, before gain is applied.",
            onValueChangeFinished = actions.commitProfile,
        )
        Text("Response curve", style = MaterialTheme.typography.labelLarge)
        SectionGap(6)
        ChoiceRow(
            options = WheelResponseCurve.entries,
            selected = profile.curve,
            onSelect = actions.setCurve,
            label = { it.label },
            perRow = 2,
        )
        SectionGap(4)
        Text(
            text = profile.curve.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AppearanceCard(state: WheelsUiState, actions: WheelsActions, modifier: Modifier) {
    val profile = state.editingProfile
    SectionCard(title = "Appearance", subtitle = "The touch area and its visual feedback", modifier = modifier) {
        SliderRow(
            title = "Wheel diameter",
            value = profile.diameterDp,
            range = WheelProfile.DIAMETER_RANGE,
            onValueChange = actions.setDiameter,
            valueLabel = "${profile.diameterDp} dp",
            onValueChangeFinished = actions.commitProfile,
        )
        SliderRow(
            title = "Opacity",
            value = (profile.opacity * 100).toInt(),
            range = (WheelProfile.MIN_OPACITY * 100).toInt()..(WheelProfile.MAX_OPACITY * 100).toInt(),
            onValueChange = { actions.setOpacity(it / 100f) },
            valueLabel = "${(profile.opacity * 100).toInt()}%",
            onValueChangeFinished = actions.commitProfile,
        )
        Text("Visual style", style = MaterialTheme.typography.labelLarge)
        SectionGap(6)
        ChoiceRow(
            options = WheelVisualStyle.entries,
            selected = profile.style,
            onSelect = actions.setStyle,
            label = { it.label },
            perRow = 2,
        )
        SectionGap(10)
        Text("Accent", style = MaterialTheme.typography.labelLarge)
        SectionGap(6)
        ChoiceRow(
            options = WheelAccent.entries,
            selected = profile.accent,
            onSelect = actions.setAccent,
            label = { it.label },
            perRow = 3,
        )
        SwitchRow(
            title = "Show output vector",
            checked = profile.showVector,
            onCheckedChange = actions.setShowVector,
            description = "Draw the processed direction and clamp use during calibration and play.",
        )
        SwitchRow(
            title = "Deadzone haptics",
            checked = profile.haptics,
            onCheckedChange = actions.setProfileHaptics,
            description = "Vibrate briefly when movement crosses into or out of the deadzone.",
        )
    }
}

@Composable
private fun ProfilesCard(
    state: WheelsUiState,
    actions: WheelsActions,
    onNameAction: (ProfileNameAction) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier,
) {
    val full = state.config.profiles.size >= WheelConfig.MAX_PROFILES
    SectionCard(
        title = "Profiles",
        subtitle = "${state.config.profiles.size} of ${WheelConfig.MAX_PROFILES} saved",
        modifier = modifier,
        action = {
            TextButton(onClick = { onNameAction(ProfileNameAction.CREATE) }, enabled = !full) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("New")
            }
        },
    ) {
        ChoiceRow(
            options = state.config.profiles,
            selected = state.activeProfile,
            onSelect = { actions.selectProfile(it.id) },
            label = { it.name },
            perRow = 2,
        )
        SectionGap(10)
        KeyValueRow("Editing", state.activeProfile.name, Tone.Accent)
        KeyValueRow("Position", "${percent(state.activeProfile.xFraction)}, ${percent(state.activeProfile.yFraction)}")
        ActionRow {
            OutlinedButton(
                onClick = { onNameAction(ProfileNameAction.RENAME) },
                modifier = Modifier.weight(1f),
            ) { Text("Rename") }
            OutlinedButton(
                onClick = { onNameAction(ProfileNameAction.DUPLICATE) },
                enabled = !full,
                modifier = Modifier.weight(1f),
            ) { Text("Duplicate") }
        }
        ActionRow {
            TextButton(onClick = actions.restoreProfile, modifier = Modifier.weight(1f)) {
                Text("Restore profile defaults")
            }
            TextButton(
                onClick = { onDelete(state.activeProfile.id) },
                enabled = state.config.profiles.size > 1,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Delete")
            }
        }
    }
}

@Composable
private fun BindingsCard(
    state: WheelsUiState,
    actions: WheelsActions,
    onAdd: () -> Unit,
    modifier: Modifier,
) {
    SectionCard(
        title = "Game bindings",
        subtitle = "Choose profiles automatically for launcher-visible games",
        modifier = modifier,
        action = { TextButton(onClick = onAdd) { Text("Add game") } },
    ) {
        SwitchRow(
            title = "Automatic profile selection",
            checked = state.config.autoSwitch,
            onCheckedChange = actions.setAutoSwitch,
            description = "Uses GameCore's existing foreground-game detection. A binding changes the " +
                "profile only; it never enables injection.",
        )
        if (state.boundApps.isEmpty()) {
            Text(
                text = "No games are assigned. Add a launchable app and choose its wheel profile.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.boundApps.forEach { app ->
                BindingRow(app = app, profiles = state.config.profiles, actions = actions)
            }
        }
        NoteBanner(
            text = "Android does not provide unrestricted foreground-app access. GameCore only applies " +
                "these bindings when its existing game-session detector has a supported signal; manual " +
                "profile switching remains available here and in the floating quick panel.",
            tone = Tone.Muted,
            icon = Icons.Filled.Info,
        )
    }
}

@Composable
private fun BindingRow(
    app: WheelBindingApp,
    profiles: List<WheelProfile>,
    actions: WheelsActions,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GameIcon(packageName = app.packageName, label = app.label, size = 40.dp)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                app.packageName + if (!app.isInstalled) " · not installed" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ProfileDropdown(
            profiles = profiles,
            selectedId = app.boundProfileId,
            onSelect = { actions.bindGame(app.packageName, it) },
            description = "Profile for ${app.label}",
        )
        TextButton(
            onClick = { actions.unbindGame(app.packageName) },
            modifier = Modifier.semantics { contentDescription = "Remove ${app.label} wheel binding" },
        ) { Text("Remove") }
    }
}

@Composable
private fun FloatingButtonCard(state: WheelsUiState, actions: WheelsActions, modifier: Modifier) {
    val button = state.editingButton
    SectionCard(
        title = "Floating quick button",
        subtitle = "Persistent emergency off-switch and in-game quick controls",
        modifier = modifier,
        action = {
            StatusChip(
                text = if (state.wheelButtonVisible) "On screen" else if (button.visible) "Requested" else "Hidden",
                tone = if (state.wheelButtonVisible) Tone.Good else Tone.Muted,
            )
        },
    ) {
        SwitchRow(
            title = "Show floating button",
            checked = button.visible,
            onCheckedChange = actions.setButtonVisible,
            enabled = state.hasOverlayPermission,
            description = "Tap toggles the wheel; long-press opens quick controls. Hiding it does not " +
                "enable or disable injection.",
        )
        SliderRow(
            title = "Button size",
            value = button.sizeDp,
            range = WheelButtonConfig.SIZE_RANGE,
            onValueChange = actions.setButtonSize,
            valueLabel = "${button.sizeDp} dp",
            onValueChangeFinished = actions.commitButton,
        )
        SliderRow(
            title = "Button opacity",
            value = (button.opacity * 100).toInt(),
            range = (WheelButtonConfig.MIN_OPACITY * 100).toInt()..(WheelButtonConfig.MAX_OPACITY * 100).toInt(),
            onValueChange = { actions.setButtonOpacity(it / 100f) },
            valueLabel = "${(button.opacity * 100).toInt()}%",
            onValueChangeFinished = actions.commitButton,
        )
        Text("Button style", style = MaterialTheme.typography.labelLarge)
        SectionGap(6)
        ChoiceRow(WheelButtonStyle.entries, button.style, actions.setButtonStyle, { it.label }, perRow = 3)
        SectionGap(10)
        Text("Icon", style = MaterialTheme.typography.labelLarge)
        SectionGap(6)
        ChoiceRow(WheelButtonIcon.entries, button.icon, actions.setButtonIcon, { it.label }, perRow = 2)
        SectionGap(10)
        Text("Button accent", style = MaterialTheme.typography.labelLarge)
        SectionGap(6)
        ChoiceRow(WheelAccent.entries, button.accent, actions.setButtonAccent, { it.label }, perRow = 3)
        SwitchRow("Snap to screen edge", button.snapToEdge, actions.setButtonSnap)
        SwitchRow("Fade while disabled", button.idleFade, actions.setButtonIdleFade)
        SwitchRow("Button haptics", button.haptics, actions.setButtonHaptics)
    }
}

@Composable
private fun AdvancedCard(
    state: WheelsUiState,
    actions: WheelsActions,
    onReset: () -> Unit,
    modifier: Modifier,
) {
    val profile = state.editingProfile
    val diagnostics = state.runtime.diagnostics
    SectionCard(title = "Advanced", subtitle = "Input shaping, measured diagnostics and compatibility", modifier = modifier) {
        SliderRow(
            title = "Input smoothing",
            value = (profile.smoothing * 100).toInt(),
            range = 0..90,
            onValueChange = { actions.setSmoothing(it / 100f) },
            valueLabel = "${(profile.smoothing * 100).toInt()}%",
            description = "An exponential value filter, not a queue. Zero is immediate and is the default.",
            onValueChangeFinished = actions.commitProfile,
        )
        SwitchRow("Invert horizontal direction", profile.invertX, actions.setInvertX)
        SwitchRow("Invert vertical direction", profile.invertY, actions.setInvertY)
        SliderRow(
            title = "Precision multiplier",
            value = (state.precisionMultiplier * 100).toInt(),
            range = (WheelConfig.MIN_PRECISION_MULTIPLIER * 100).toInt()..
                (WheelConfig.MAX_PRECISION_MULTIPLIER * 100).toInt(),
            onValueChange = { actions.setPrecisionMultiplier(it / 100f) },
            valueLabel = multiplier(state.precisionMultiplier),
            description = "Temporary scale used by Precision in the floating quick panel.",
            onValueChangeFinished = actions.commitPrecisionMultiplier,
        )
        SliderRow(
            title = "Turbo multiplier",
            value = (state.turboMultiplier * 100).toInt(),
            range = (WheelConfig.MIN_TURBO_MULTIPLIER * 100).toInt()..
                (WheelConfig.MAX_TURBO_MULTIPLIER * 100).toInt(),
            onValueChange = { actions.setTurboMultiplier(it / 100f) },
            valueLabel = multiplier(state.turboMultiplier),
            description = "Temporary scale used by Turbo in the floating quick panel.",
            onValueChangeFinished = actions.commitTurboMultiplier,
        )
        RowDivider()
        Text("Measured injection diagnostics", style = MaterialTheme.typography.titleSmall)
        KeyValueRow("Measured injection calls", diagnostics.processedEvents.toString())
        KeyValueRow("Accepted injections", diagnostics.injectedEvents.toString(), Tone.Good)
        KeyValueRow("Refused injections", diagnostics.refusedEvents.toString(), if (diagnostics.refusedEvents > 0) Tone.Danger else Tone.Muted)
        KeyValueRow("Taps completed after hand-off", diagnostics.delayedTaps.toString())
        KeyValueRow("First-event hand-off", micros(diagnostics.handoffMicros))
        KeyValueRow("Last processing", micros(diagnostics.lastProcessingMicros))
        KeyValueRow("Average processing", micros(diagnostics.averageProcessingMicros))
        KeyValueRow("Peak processing", micros(diagnostics.peakProcessingMicros))
        KeyValueRow("AIDL round trip", micros(diagnostics.binderMicros))
        KeyValueRow("Injected pointer", if (diagnostics.pointerActive) "Active" else "Released")
        ActionRow {
            OutlinedButton(onClick = actions.refreshDiagnostics, modifier = Modifier.weight(1f)) {
                Text("Read helper diagnostics")
            }
            OutlinedButton(onClick = actions.refreshBackend, modifier = Modifier.weight(1f)) {
                Text("Reconnect")
            }
        }
        if (state.remoteDiagnostics.isNotBlank()) {
            Text(
                state.remoteDiagnostics,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.runtime.injector.detail.takeIf { it.isNotBlank() }?.let {
            NoteBanner(text = it, tone = if (state.runtime.injector.canInject) Tone.Muted else Tone.Warning)
        }
        NoteBanner(
            text = "These times cover GameCore processing and the synchronous AIDL round trip only. They " +
                "do not measure the game's input handling, frame production, display scan-out, or physical " +
                "multitouch coexistence. The first DOWN of a gaming gesture waits only until the wheel " +
                "window leaves touch mode; that measured hand-off is shown above and is not part of the " +
                "per-event samples. A short tap is completed after the hand-off rather than dropped. The " +
                "injected stream starts at the wheel's centre, so the game control under the ring is the " +
                "one it presses. On Android 13+ events are addressed to the foreground game's uid when it " +
                "can be resolved; older or unsupported platforms fall back to untargeted injection. " +
                "Injected pointer id 9 is best-effort, not a multitouch guarantee.",
            tone = Tone.Muted,
            icon = Icons.Filled.Info,
        )
        TextButton(onClick = onReset) { Text("Reset all wheel configuration", color = Tone.Danger.colour()) }
    }
}

@Composable
private fun LegacyGuideCard(state: WheelsUiState, actions: WheelsActions, modifier: Modifier) {
    SectionCard(
        title = "Guide ring & Aim Lab",
        subtitle = "The older untouchable guide and GameCore's in-app training multiplier",
        modifier = modifier,
        action = {
            StatusChip(if (state.isGuideOn) "Guide on" else "Guide off", if (state.isGuideOn) Tone.Good else Tone.Muted)
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(screenAspectRatio())
                .clip(RoundedCornerShape(12.dp))
                .background(OverlayPalette.PanelPlate)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
        ) {
            WheelOverlay(radiusPercent = state.guideRadiusPercent)
        }
        SwitchRow(
            title = "Show legacy guide ring",
            checked = state.isGuideOn,
            onCheckedChange = actions.setGuideEnabled,
            enabled = state.canToggleGuide,
            description = "A FLAG_NOT_TOUCHABLE marker only. It is separate from the real injected wheel.",
        )
        SliderRow(
            title = "Guide size",
            value = state.guideRadiusPercent,
            range = AppSettings.WHEEL_RADIUS_MIN..AppSettings.WHEEL_RADIUS_MAX,
            onValueChange = actions.setGuideRadius,
            valueLabel = "${state.guideRadiusPercent}%",
            onValueChangeFinished = actions.commitGuideRadius,
        )
        SliderRow(
            title = "Aim Lab wheel multiplier",
            value = state.aimLabSensitivityPercent,
            range = AppSettings.WHEEL_SENSITIVITY_MIN..AppSettings.WHEEL_SENSITIVITY_MAX,
            onValueChange = actions.setAimLabSensitivity,
            valueLabel = percentMultiplier(state.aimLabSensitivityPercent),
            description = "Used only by GameCore's own Aim Lab training surface. It does not change the " +
                "Shizuku-injected wheel profile above.",
            onValueChangeFinished = actions.commitAimLabSensitivity,
        )
    }
}

@Composable
private fun ProfileDropdown(
    profiles: List<WheelProfile>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    description: String,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = profiles.firstOrNull { it.id == selectedId }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.semantics { contentDescription = "$description: ${selected?.name ?: "none"}" },
        ) {
            Text(selected?.name ?: "Choose", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
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

@Composable
private fun ProfileNameDialog(
    action: ProfileNameAction,
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val initial = when (action) {
        ProfileNameAction.CREATE -> "New profile"
        ProfileNameAction.DUPLICATE -> "$currentName copy"
        ProfileNameAction.RENAME -> currentName
    }
    var name by remember(action, currentName) { mutableStateOf(initial) }
    val title = when (action) {
        ProfileNameAction.CREATE -> "Create wheel profile"
        ProfileNameAction.DUPLICATE -> "Duplicate wheel profile"
        ProfileNameAction.RENAME -> "Rename wheel profile"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.replace('\n', ' ').take(WheelProfile.MAX_NAME_LENGTH) },
                label = { Text("Profile name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text(if (action == ProfileNameAction.RENAME) "Rename" else "Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AppBindingDialog(
    state: WheelsUiState,
    onBind: (String) -> Unit,
    onReload: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Assign ${state.activeProfile.name} to a game") },
        text = {
            when {
                state.appsLoading -> Box(
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                state.unboundApps.isEmpty() -> Column {
                    Text("Every discovered launchable app already has a wheel profile.")
                    TextButton(onClick = onReload) { Text("Scan again") }
                }

                else -> LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(state.unboundApps, key = { it.packageName }) { app ->
                        Surface(
                            onClick = { onBind(app.packageName) },
                            color = androidx.compose.ui.graphics.Color.Transparent,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                GameIcon(app.packageName, app.label, size = 40.dp)
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        app.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

private fun multiplier(value: Float): String = String.format(Locale.US, "×%.2f", value)

private fun percentMultiplier(percent: Int): String =
    "×${percent / 100}.${(percent % 100).toString().padStart(2, '0')}"

private fun oneDecimal(value: Float): String = String.format(Locale.US, "%.1f", value)

private fun percent(value: Float): String = "${(value * 100).toInt()}%"

private fun micros(value: Int): String = if (value <= 0) "No sample" else "$value µs"

private fun nearlyEqual(a: Float, b: Float): Boolean = kotlin.math.abs(a - b) < 0.005f
