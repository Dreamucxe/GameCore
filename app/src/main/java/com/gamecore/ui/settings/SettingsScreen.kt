package com.gamecore.ui.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.core.common.Formatters
import com.gamecore.core.model.AccentChoice
import com.gamecore.core.model.AnimationsMode
import com.gamecore.core.model.AppSettings
import com.gamecore.core.model.RecordingQuality
import com.gamecore.core.model.TextSizePreset
import com.gamecore.core.model.ThemeChoice
import com.gamecore.core.model.appVersionLabel
import com.gamecore.core.model.customAccentLabel
import com.gamecore.core.model.scaleForPreset
import com.gamecore.core.model.textSizePreset
import com.gamecore.core.model.uiScaleLabel
import com.gamecore.ui.Destination
import com.gamecore.ui.components.ActionRow
import com.gamecore.ui.components.ChoiceRow
import com.gamecore.ui.components.ColourPicker
import com.gamecore.ui.components.ColourSwatches
import com.gamecore.ui.components.ConfirmDialog
import com.gamecore.ui.components.KeyValueRow
import com.gamecore.ui.components.NavRow
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.RowDivider
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.SliderRow
import com.gamecore.ui.components.StepperRow
import com.gamecore.ui.components.SwitchRow
import com.gamecore.ui.components.TextFieldRow
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.startIntentSafely

/**
 * §22's settings, in the order a user looks for them.
 *
 * Appearance first because it is the reason most people open this screen; the destructive things last,
 * behind confirmations, where nobody reaches them by accident. Everything in between writes through as it
 * is touched — there is no save button on this screen and no draft to lose.
 *
 * The rows that lead somewhere else ([Destination.Overlay], [Destination.Crosshair],
 * [Destination.Permissions], [Destination.Tools], [Destination.Shizuku]) are here rather than duplicated as
 * controls: a floating pill has a dozen settings of its own and a screen that shows a live preview of them,
 * and reproducing three of the twelve here would give two places to change one thing.
 *
 * Every section takes lambdas rather than the ViewModel, as the profile editor does. `onEdit` is the one
 * transform every switch and choice goes through; the sections that own a gesture or an action take the
 * specific callback for it.
 */
@Composable
fun SettingsScreen(
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val padded = Modifier.padding(horizontal = ScreenPadding)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScreenHeader(title = "Settings", subtitle = "How GameCore looks, measures and behaves") }

        state.message?.let { message ->
            item {
                NoteBanner(
                    text = message,
                    tone = Tone.Accent,
                    icon = Icons.Filled.Info,
                    modifier = padded,
                    action = { TextButton(onClick = viewModel::dismissMessage) { Text("OK") } },
                )
            }
        }

        if (state.isLoaded && !state.isPersisting) {
            item {
                NoteBanner(
                    text = NOT_PERSISTING,
                    tone = Tone.Warning,
                    icon = Icons.Filled.Shield,
                    modifier = padded,
                )
            }
        }

        item {
            AppearanceCard(
                state = state,
                onEdit = viewModel::update,
                modifier = padded,
            )
        }
        item {
            DisplayTextCard(
                state = state,
                onScaleChange = viewModel::setUiScale,
                onScaleCommit = viewModel::commitScale,
                modifier = padded,
            )
        }
        item { BehaviorCard(state = state, onEdit = viewModel::update, modifier = padded) }
        item { OverlaysCard(state = state, onNavigate = onNavigate, modifier = padded) }
        item {
            GamesCard(
                state = state,
                onEdit = viewModel::update,
                onStepDetection = viewModel::stepDetectionInterval,
                onNavigate = onNavigate,
                modifier = padded,
            )
        }
        item {
            MonitoringCard(
                state = state,
                onEdit = viewModel::update,
                onStepSample = viewModel::stepSampleInterval,
                onNavigate = onNavigate,
                modifier = padded,
            )
        }
        item {
            NetworkCard(
                state = state,
                onEdit = viewModel::update,
                onHostChange = viewModel::setLatencyHost,
                onHostCommit = viewModel::commitLatencyHost,
                onHostReset = viewModel::resetLatencyHost,
                modifier = padded,
            )
        }
        item {
            RecordingCard(
                state = state,
                onEdit = viewModel::update,
                onNavigate = onNavigate,
                modifier = padded,
            )
        }
        item { AccessCard(onNavigate = onNavigate, modifier = padded) }
        item {
            DataCard(
                state = state,
                onExport = viewModel::export,
                onDismissExport = viewModel::dismissExport,
                onClearHistory = viewModel::askClearHistory,
                onResetSettings = viewModel::askResetSettings,
                onNavigate = onNavigate,
                modifier = padded,
            )
        }
        item { AboutCard(onNavigate = onNavigate, modifier = padded) }
    }

    if (state.pendingClearHistory) {
        ConfirmDialog(
            title = "Clear all history?",
            message = "Every recorded session and all of its samples are deleted. Your profiles, HUD " +
                "layouts and settings are not touched. This cannot be undone.",
            confirmLabel = "Delete everything",
            onConfirm = viewModel::confirmClearHistory,
            onDismiss = viewModel::cancelPending,
        )
    }

    if (state.pendingResetSettings) {
        ConfirmDialog(
            title = "Reset all settings?",
            message = "Appearance, intervals, the reference host and the recording quality go back to " +
                "their defaults. Recorded sessions, game profiles and HUD layouts are kept.",
            confirmLabel = "Reset",
            onConfirm = viewModel::confirmResetSettings,
            onDismiss = viewModel::cancelPending,
        )
    }
}

/**
 * Theme, accent, custom colour and density (§7).
 *
 * Dynamic colour appears only on Android 12 and later, where the platform actually has a wallpaper palette
 * to take. On Android 11 a switch for it would be a control that does nothing, which §32 does not allow, so
 * the row is replaced by a line saying the device has no such palette.
 *
 * The ninth swatch is the custom colour, in the same row as the eight rather than in a row of its own —
 * it is the same kind of thing, a colour one tap away, and the row is where a user looks to see which
 * colour is currently on. That also makes the swatch row the *only* place `useCustomAccent` is switched:
 * tapping one of the eight turns it off, tapping the ninth turns it on. A separate "use custom colour"
 * toggle would give one visible property two owners, and the two would be out of step the first time
 * somebody tapped a swatch while the toggle was off.
 */
@Composable
private fun AppearanceCard(
    state: SettingsUiState,
    onEdit: ((AppSettings) -> AppSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pickerOpen by remember { mutableStateOf(false) }
    val custom = state.settings.customAccentArgb

    SectionCard(title = "Appearance", icon = Icons.Filled.Tune, modifier = modifier) {
        Text(
            text = "THEME",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ChoiceRow(
            options = ThemeChoice.entries.toList(),
            selected = state.settings.theme,
            onSelect = { choice -> onEdit { it.copy(theme = choice) } },
            label = { it.label },
            perRow = 2,
        )
        RowDivider()
        Text(
            text = "ACCENT",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // The empty ninth swatch is drawn in the surface's own grey: it is a slot, not a colour anybody
        // chose, and filling it with a plausible hue would be a colour the user never picked being offered
        // back to them as if they had.
        val emptyCustom = MaterialTheme.colorScheme.surfaceVariant
        ColourSwatches(
            colours = AccentChoice.entries.map { Color(it.argb) } + (custom?.let { Color(it) } ?: emptyCustom),
            selected = if (state.settings.useCustomAccent && custom != null) {
                Color(custom)
            } else {
                Color(state.settings.accent.argb)
            },
            onSelect = { picked ->
                val choice = AccentChoice.entries.firstOrNull { Color(it.argb) == picked }
                when {
                    choice != null -> onEdit { it.copy(accent = choice, useCustomAccent = false) }
                    custom != null -> onEdit { it.copy(useCustomAccent = true) }
                    // Nothing mixed yet, so the ninth swatch has nothing to select. Opening the picker is
                    // what the tap was asking for anyway.
                    else -> pickerOpen = true
                }
            },
            perRow = 5,
        )
        NavRow(
            title = "Custom colour",
            onClick = { pickerOpen = !pickerOpen },
            icon = Icons.Filled.Palette,
            description = "Mix your own accent. It joins the swatches above as the ninth colour.",
            trailing = customAccentLabel(custom, state.settings.useCustomAccent),
        )
        if (pickerOpen) {
            ColourPicker(
                initialArgb = custom ?: state.settings.accent.argb,
                // Picking is also choosing: a colour mixed and then not applied would leave the user
                // looking at an unchanged screen wondering which control they still had to find.
                onPick = { argb ->
                    onEdit { it.copy(customAccentArgb = argb, useCustomAccent = true) }
                    pickerOpen = false
                },
            )
        }
        if (state.settings.useDynamicColour) {
            Text(
                text = "The accent is coming from your wallpaper while dynamic colour is on.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        RowDivider()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SwitchRow(
                title = "Use wallpaper colours",
                checked = state.settings.useDynamicColour,
                onCheckedChange = { enabled -> onEdit { it.copy(useDynamicColour = enabled) } },
                description = "Takes the accent from Android's own palette instead of the swatches above.",
            )
        } else {
            KeyValueRow(label = "Wallpaper colours", value = "Android 12+", tone = Tone.Muted)
        }
        SwitchRow(
            title = "Compact density",
            checked = state.settings.compactDensity,
            onCheckedChange = { enabled -> onEdit { it.copy(compactDensity = enabled) } },
            description = "Tightens the space between rows. Tap targets keep their full size.",
        )
    }
}

/**
 * Font scale and the text-size presets — one stored multiplier, two ways to move it (§7).
 *
 * The presets are *derived* from [SettingsUiState.uiScalePercent] by [textSizePreset] rather than stored
 * alongside it, which is what keeps them in sync: there is one number, so there is nothing to drift. Drag
 * the slider to 90% and "Small" lights up; tap "Large" and the slider moves to 115%. A value between the
 * stops is named [TextSizePreset.CUSTOM] rather than rounded to the nearest preset, because a user who
 * deliberately chose 122% has not asked for 115%.
 *
 * "Custom" is shown but inert — tapping it stores nothing ([scaleForPreset] returns the current value). It
 * is there because the row has to be able to *report* that state, and a preset row that silently lit
 * nothing at 122% would read as the screen having lost track of the setting.
 */
@Composable
private fun DisplayTextCard(
    state: SettingsUiState,
    onScaleChange: (Int) -> Unit,
    onScaleCommit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val preset = textSizePreset(state.uiScalePercent)

    SectionCard(title = "Display and text", icon = Icons.Filled.FormatSize, modifier = modifier) {
        SliderRow(
            title = "Font scale",
            value = state.uiScalePercent,
            range = AppSettings.MIN_UI_SCALE..AppSettings.MAX_UI_SCALE,
            onValueChange = onScaleChange,
            valueLabel = uiScaleLabel(state.uiScalePercent),
            description = "Multiplies Android's own font size setting inside GameCore. It does not " +
                "change anything outside this app.",
            onValueChangeFinished = onScaleCommit,
        )
        Text(
            text = "TEXT SIZE",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ChoiceRow(
            options = TextSizePreset.entries.toList(),
            selected = preset,
            onSelect = { chosen ->
                val next = scaleForPreset(chosen, state.uiScalePercent)
                if (next != state.uiScalePercent) {
                    onScaleChange(next)
                    onScaleCommit()
                }
            },
            label = { it.label },
            perRow = 4,
        )
        if (preset == TextSizePreset.CUSTOM) {
            Text(
                text = "The slider is between the presets, at ${uiScaleLabel(state.uiScalePercent)}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Motion and haptics (§7).
 *
 * Animations defaults to [AnimationsMode.SYSTEM] and the description says what that means, because
 * "Follow system" on its own does not tell a user which system setting is being followed — and the one
 * being followed here is an accessibility setting some people have deliberately turned all the way down.
 */
@Composable
private fun BehaviorCard(
    state: SettingsUiState,
    onEdit: ((AppSettings) -> AppSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = "Behavior", icon = Icons.Filled.Animation, modifier = modifier) {
        Text(
            text = "ANIMATIONS",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ChoiceRow(
            options = AnimationsMode.entries.toList(),
            selected = state.settings.animations,
            onSelect = { mode -> onEdit { it.copy(animations = mode) } },
            label = { it.label },
            perRow = 3,
        )
        Text(
            text = "Following the system uses Android's animation scale, so motion turned down for " +
                "accessibility stays turned down here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RowDivider()
        SwitchRow(
            title = "Haptics",
            checked = state.settings.hapticsEnabled,
            onCheckedChange = { enabled -> onEdit { it.copy(hapticsEnabled = enabled) } },
            description = "A short vibration on the floating button and on destructive actions.",
        )
    }
}

/**
 * The three overlays, each behind a link to the screen that owns it.
 *
 * None of them is reproduced as a control here. The pill alone has a dozen settings and a screen that shows
 * a live preview of them, and putting three of the twelve on this card would give the user two places to
 * change one thing and no way to tell which one won.
 */
@Composable
private fun OverlaysCard(
    state: SettingsUiState,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Overlays",
        icon = Icons.Filled.Layers,
        subtitle = "Each has its own screen, with a live preview",
        modifier = modifier,
    ) {
        NavRow(
            title = "Floating button and performance pill",
            onClick = { onNavigate(Destination.Overlay) },
            description = "Position, size, opacity, corner radius, update interval and which readings the " +
                "pill shows.",
            icon = Icons.Filled.TouchApp,
        )
        NavRow(
            title = "Crosshair",
            onClick = { onNavigate(Destination.Crosshair) },
            description = "Design, size, colour, thickness, rotation and where it sits.",
            icon = Icons.Filled.CenterFocusStrong,
        )
        NavRow(
            title = "HUD layouts",
            onClick = { onNavigate(Destination.Hud) },
            description = "Drag the readouts where you want them, then pick a layout per game.",
            icon = Icons.Filled.GridView,
            trailing = Formatters.count(state.layoutCount, "layout"),
        )
    }
}

/**
 * Detection, and what happens when a game is detected.
 *
 * The switch is worded as a preference and not as a capability: turning it on does not grant usage access,
 * and a settings screen that implies otherwise is how an app ends up promising behaviour the device will
 * refuse. The description says where the grant lives.
 *
 * The never-close list is last and is a row rather than a switch, because it is the one thing on this card
 * with no effect of its own — it only narrows what the per-game "free RAM on launch" switch is allowed to
 * do. It lives here, next to the profiles it constrains, rather than under Access: nothing about it is a
 * permission, and its count reads as "none" rather than "0 apps" so an empty list does not look broken.
 *
 * The Aim Lab switch sits last, on its own, because it is the only control here that turns a whole section
 * of GameCore off rather than changing how one works. Its description says what off actually does — the
 * section's screens stop being registered as destinations rather than being hidden behind a greyed-out
 * entry — and says that nothing recorded in Aim Lab is deleted, because a master switch that quietly threw
 * a user's scores away would be the worst possible reading of it.
 */
@Composable
private fun GamesCard(
    state: SettingsUiState,
    onEdit: ((AppSettings) -> AppSettings) -> Unit,
    onStepDetection: (Int) -> Unit,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = "Games", icon = Icons.Filled.SportsEsports, modifier = modifier) {
        NavRow(
            title = "Game profiles",
            onClick = { onNavigate(Destination.Games) },
            description = "What GameCore changes when each game starts, and puts back when it stops.",
            icon = Icons.Filled.SportsEsports,
            trailing = Formatters.count(state.profileCount, "profile"),
        )
        RowDivider()
        SwitchRow(
            title = "Apply profiles automatically",
            checked = state.settings.autoApplyProfiles,
            onCheckedChange = { enabled -> onEdit { it.copy(autoApplyProfiles = enabled) } },
            description = "Needs usage access, which the permissions screen grants. With this off, a " +
                "profile applies only when you start it yourself.",
        )
        SwitchRow(
            title = "Keep the screen on in a game",
            checked = state.settings.keepScreenOnInGame,
            onCheckedChange = { enabled -> onEdit { it.copy(keepScreenOnInGame = enabled) } },
            description = "Holds the display awake while a game with a profile is in front.",
        )
        SwitchRow(
            title = "Explain resolution override before first use",
            checked = state.settings.showResolutionOverrideNotice,
            onCheckedChange = { enabled -> onEdit { it.copy(showResolutionOverrideNotice = enabled) } },
            description = "Shows a one-time note the first time you lower a game's resolution — what the " +
                "change does at the system level, and that it is not a guaranteed frame-rate win. GameCore " +
                "stops showing it once you continue past it; turn this back on to see it again.",
        )
        SwitchRow(
            title = "Explain config editing before first use",
            checked = state.settings.showConfigEditNotice,
            onCheckedChange = { enabled -> onEdit { it.copy(showConfigEditNotice = enabled) } },
            description = "Shows a one-time note the first time you edit a game's config files — that a bad " +
                "edit can corrupt a save, and that GameCore keeps an untouched original you can restore. " +
                "It keeps appearing until you tick \"Don't show this again\" in the note itself; turn this " +
                "back on to see it again.",
        )
        StepperRow(
            title = "Check what is in front every",
            valueLabel = "${state.detectionSeconds}s",
            onStep = onStepDetection,
            description = "Longer costs less battery and notices a launch later. Two seconds is enough — " +
                "the game is still loading.",
            canDecrease = state.detectionSeconds > DETECTION_SECONDS_RANGE.first,
            canIncrease = state.detectionSeconds < DETECTION_SECONDS_RANGE.last,
        )
        RowDivider()
        NavRow(
            title = "Never close these apps",
            onClick = { onNavigate(Destination.NeverClose) },
            description = "Kept running when a profile frees memory on launch, on top of everything " +
                "GameCore already leaves alone.",
            icon = Icons.Filled.Memory,
            trailing = if (state.settings.neverKillPackages.isEmpty()) {
                "None"
            } else {
                Formatters.count(state.settings.neverKillPackages.size, "app")
            },
        )
        NavRow(
            title = "Game storage",
            onClick = { onNavigate(Destination.GameStorage) },
            description = "What each game is holding in cache, and clearing the part of it that is safe " +
                "to delete. Saves are never touched.",
            icon = Icons.Filled.CleaningServices,
        )
        RowDivider()
        NavRow(
            title = "Quick trigger",
            onClick = { onNavigate(Destination.QuickTrigger) },
            description = "A shortcut that opens the GameCore panel — a volume double-tap, a shake, or a " +
                "Quick Settings tile, each with what it actually needs.",
            icon = Icons.Filled.Bolt,
        )
        RowDivider()
        SwitchRow(
            title = "Aim Lab",
            checked = state.settings.aimLabEnabled,
            onCheckedChange = { enabled -> onEdit { it.copy(aimLabEnabled = enabled) } },
            description = "Off turns the whole feature off: its screens leave the app's navigation rather " +
                "than being hidden, so nothing in the section is reachable. Aim Lab data you have already " +
                "saved is kept either way.",
        )
    }
}

/**
 * What gets measured, how often, and at whose expense.
 *
 * "Keep measuring in the background" is the one switch on this screen that costs battery on its own, and it
 * says so rather than being buried: it holds a foreground service open for no reason other than keeping the
 * sample loop alive. The thermal row says what GameCore does with a temperature and what it will never do
 * with one, because §24 rules out the thing users expect a "gaming app" to offer here.
 */
@Composable
private fun MonitoringCard(
    state: SettingsUiState,
    onEdit: ((AppSettings) -> AppSettings) -> Unit,
    onStepSample: (Int) -> Unit,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = "Monitoring", icon = Icons.Filled.Insights, modifier = modifier) {
        SwitchRow(
            title = "Record sessions",
            checked = state.settings.trackSessions,
            onCheckedChange = { enabled -> onEdit { it.copy(trackSessions = enabled) } },
            description = "One row per game session, with the readings that were actually taken while it ran.",
        )
        SwitchRow(
            title = "Ask before discarding a short session",
            checked = state.settings.confirmBeforeDiscard,
            onCheckedChange = { enabled -> onEdit { it.copy(confirmBeforeDiscard = enabled) } },
            description = "A session under a minute is more often a mis-tap on an icon than a game.",
            enabled = state.settings.trackSessions,
        )
        StepperRow(
            title = "Take a reading every",
            valueLabel = "${state.sampleSeconds}s",
            onStep = onStepSample,
            description = "CPU, memory, temperature, refresh rate and latency, for as long as something " +
                "is reading them.",
            canDecrease = state.sampleSeconds > SAMPLE_SECONDS_RANGE.first,
            canIncrease = state.sampleSeconds < SAMPLE_SECONDS_RANGE.last,
        )
        RowDivider()
        SwitchRow(
            title = "Keep measuring in the background",
            checked = state.settings.backgroundMonitoring,
            onCheckedChange = { enabled -> onEdit { it.copy(backgroundMonitoring = enabled) } },
            description = "Holds a foreground service open with no session running and no overlay on " +
                "screen. This is the one setting here that costs battery by itself.",
        )
        SwitchRow(
            title = "Warn when the device gets hot",
            checked = state.settings.showThermalWarnings,
            onCheckedChange = { enabled -> onEdit { it.copy(showThermalWarnings = enabled) } },
            description = "GameCore reports the temperature Android gives it. It never changes a thermal " +
                "limit — no app can do that safely.",
        )
        SwitchRow(
            title = "Use the elevated shell for readings",
            checked = state.settings.allowElevatedReads,
            onCheckedChange = { enabled -> onEdit { it.copy(allowElevatedReads = enabled) } },
            description = "When Shizuku is connected, lets GameCore read the figures Android will not give " +
                "an ordinary app. Turning this off does not disconnect Shizuku.",
        )
        NavRow(
            title = "Live performance",
            onClick = { onNavigate(Destination.Performance) },
            description = "The graphs, and where each reading is actually measured from.",
            icon = Icons.Filled.Insights,
        )
    }
}

/**
 * The one measurement that sends packets, and the host it sends them to.
 *
 * The host is applied on a button rather than per keystroke: "1.1.1." is a state every valid address passes
 * through, and a field that rejects it mid-word cannot be typed into. The description says outright that
 * this is not the game's own server, because a number labelled "ping" next to a game is read as the ping in
 * that match, and GameCore has no way to know which server the game is talking to.
 */
@Composable
private fun NetworkCard(
    state: SettingsUiState,
    onEdit: ((AppSettings) -> AppSettings) -> Unit,
    onHostChange: (String) -> Unit,
    onHostCommit: () -> Unit,
    onHostReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = "Network", icon = Icons.Filled.Speed, modifier = modifier) {
        SwitchRow(
            title = "Measure latency",
            checked = state.settings.measureLatency,
            onCheckedChange = { enabled -> onEdit { it.copy(measureLatency = enabled) } },
            description = "Opens a connection to the host below and times it. Off means the HUD and the " +
                "graphs show no latency at all, rather than an estimate.",
        )
        TextFieldRow(
            label = "Reference host",
            value = state.latencyHostDraft,
            onValueChange = onHostChange,
            placeholder = AppSettings.DEFAULT_LATENCY_HOST,
            maxLength = HOST_FIELD_LENGTH,
            description = "A hostname or IP address — no scheme, port or path. This is a reference host, " +
                "not the server your game is on, and GameCore never calls the figure your ping.",
        )
        ActionRow {
            TextButton(
                onClick = onHostCommit,
                enabled = state.settings.measureLatency && state.isHostEdited,
            ) { Text("Apply") }
            TextButton(
                onClick = onHostReset,
                enabled = state.settings.latencyHost != AppSettings.DEFAULT_LATENCY_HOST ||
                    state.isHostEdited,
            ) { Text("Use default") }
        }
        KeyValueRow(label = "Measuring against", value = state.settings.latencyHost)
    }
}

/**
 * Recording quality, with the file size each choice actually produces.
 *
 * The sizes are on screen because "High" is not a decision anyone can make without them: a minute of full
 * panel at 60 fps is about 90 MB, and the user finding that out from a full storage partition is the wrong
 * time. §24B's consent flow is not a setting and is not offered here — Android asks for its own permission
 * at the moment each recording starts, and nothing in GameCore can pre-approve that.
 */
@Composable
private fun RecordingCard(
    state: SettingsUiState,
    onEdit: ((AppSettings) -> AppSettings) -> Unit,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = "Screen recording", icon = Icons.Filled.CenterFocusStrong, modifier = modifier) {
        ChoiceRow(
            options = RecordingQuality.entries.toList(),
            selected = state.settings.recordingQuality,
            onSelect = { quality -> onEdit { it.copy(recordingQuality = quality) } },
            label = { it.label },
            perRow = 3,
        )
        Text(
            text = state.settings.recordingQuality.explanation,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RowDivider()
        NavRow(
            title = "Start a recording",
            onClick = { onNavigate(Destination.Tools) },
            description = "Android asks its own permission each time. GameCore cannot skip that, and does " +
                "not pretend to.",
            icon = Icons.Filled.Build,
        )
    }
}

/**
 * Four screens rather than four settings: none of these is a value, they are all a state of the device.
 */
@Composable
private fun AccessCard(
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = "Access and tools", icon = Icons.Filled.Shield, modifier = modifier) {
        // §A3, and first in this card because it is the summary over the two rows beneath it: it reads
        // every grant Permissions lists and the Shizuku state that screen explains, and says which of
        // them something the user actually turned on is waiting for. Someone who knows which grant is
        // missing still goes straight to the row they want.
        NavRow(
            title = "Setup health",
            onClick = { onNavigate(Destination.SetupHealth) },
            description = "What is set up, what is not, and what each gap costs you.",
            icon = Icons.Filled.Checklist,
        )
        NavRow(
            title = "Permissions",
            onClick = { onNavigate(Destination.Permissions) },
            description = "What each permission is for, whether this device has granted it, and the " +
                "settings page that does.",
            icon = Icons.Filled.Shield,
        )
        NavRow(
            title = "Shizuku",
            onClick = { onNavigate(Destination.Shizuku) },
            description = "Optional. What it adds, what it still cannot add, and how to set it up.",
            icon = Icons.Filled.Terminal,
        )
        NavRow(
            title = "Gaming tools",
            onClick = { onNavigate(Destination.Tools) },
            description = "Screenshot, recording, brightness, volume, Do Not Disturb, rotation and torch.",
            icon = Icons.Filled.Build,
        )
    }
}

/**
 * What is stored, how to get a copy of it out, and the two ways to destroy it.
 *
 * The counts come from the database rather than from a figure carried across a navigation, so the number
 * beside "Recorded sessions" is what "Clear history" will actually delete. The two destructive buttons are
 * separate and confirm separately: nobody should lose six weeks of history to get their accent colour back.
 */
@Composable
private fun DataCard(
    state: SettingsUiState,
    onExport: () -> Unit,
    onDismissExport: () -> Unit,
    onClearHistory: () -> Unit,
    onResetSettings: () -> Unit,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    SectionCard(
        title = "Data",
        icon = Icons.Filled.Storage,
        subtitle = "Recorded here, kept here, sent nowhere",
        modifier = modifier,
    ) {
        KeyValueRow(label = "Recorded sessions", value = Formatters.count(state.sessionCount, "session"))
        KeyValueRow(label = "Game profiles", value = Formatters.count(state.profileCount, "profile"))
        KeyValueRow(label = "HUD layouts", value = Formatters.count(state.layoutCount, "layout"))
        RowDivider()
        NavRow(
            title = "Backup & restore",
            onClick = { onNavigate(Destination.BackupRestore) },
            description = "Save your appearance, settings, profiles, presets, HUD layouts and macros to a " +
                "file, or bring them back. Session history is never included.",
            icon = Icons.Filled.SettingsBackupRestore,
        )
        RowDivider()
        Text(
            text = "Export writes a CSV with one row per session. An empty cell means that reading was " +
                "never taken — nothing is filled in or averaged to make the file look complete.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.export?.let { note ->
            NoteBanner(
                text = note.text,
                tone = if (note.isProblem) Tone.Warning else Tone.Good,
                icon = if (note.isProblem) Icons.Filled.Info else Icons.Filled.Check,
                action = {
                    if (note.uri == null) {
                        TextButton(onClick = onDismissExport) { Text("OK") }
                    } else {
                        TextButton(
                            onClick = { context.startIntentSafely(shareIntent(note.uri)) },
                        ) { Text("Share") }
                    }
                },
            )
        }
        ActionRow {
            Button(onClick = onExport, enabled = state.hasHistory && !state.isExporting) {
                Text(if (state.isExporting) "Writing the file…" else "Export history")
            }
        }
        RowDivider()
        ActionRow {
            TextButton(onClick = onClearHistory, enabled = state.hasHistory) { Text("Clear history") }
            TextButton(onClick = onResetSettings) { Text("Reset settings") }
        }
    }
}

/**
 * A share of the exported file, with a read grant that lasts for that share.
 *
 * The URI is a `content://` one from GameCore's own `FileProvider`, which is why the export needs no storage
 * permission on any API level and why the receiving app gets the one file rather than a directory.
 */
private fun shareIntent(uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
    type = "text/csv"
    putExtra(Intent.EXTRA_STREAM, uri)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

/** The build, the device it is on, the licence, and the short version of what this app will not do. */
@Composable
private fun AboutCard(
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var licenceOpen by remember { mutableStateOf(false) }

    // Read from the *installed* package rather than from `BuildConfig` (§7). A compiled-in constant
    // describes the APK this code was built into, which is the same thing right up until it is not —
    // a sideloaded build over the top, a user comparing two APKs, a bug report about "3.3" that turns out
    // to be a different 3.3. The build number is the half that settles those, and it is the half a
    // constant is least able to prove.
    val version = remember(context) {
        try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            // Null means the package manager answered without a version. "Unavailable" then, not a
            // guess — §0 applies to GameCore's own version as much as to a sensor's.
            appVersionLabel(info.versionName, code)
                ?: "Unavailable — the package reports no version"
        } catch (e: PackageManager.NameNotFoundException) {
            "Unavailable — Android could not find the package ${context.packageName}"
        }
    }

    SectionCard(title = "About", icon = Icons.Filled.PhoneAndroid, modifier = modifier) {
        KeyValueRow(
            label = "GameCore",
            value = version,
            tone = if (version.startsWith("Unavailable")) Tone.Muted else Tone.Neutral,
        )
        KeyValueRow(
            label = "Android",
            value = "${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}",
        )
        KeyValueRow(label = "Device", value = "${Build.MANUFACTURER} ${Build.MODEL}")
        RowDivider()
        Text(
            text = ABOUT_TEXT,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RowDivider()
        NavRow(
            title = "License",
            onClick = { licenceOpen = !licenceOpen },
            description = "GameCore is open source.",
            icon = Icons.Filled.Gavel,
            trailing = LICENCE_NAME,
        )
        if (licenceOpen) {
            Text(
                text = LICENCE_TEXT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        NavRow(
            title = "Developer",
            onClick = { onNavigate(Destination.Developer) },
            description = "Who made GameCore, and the three places to reach them.",
            icon = Icons.Filled.Person,
        )
    }
}

/** The host field's own limit. A DNS name cannot exceed 253 characters, so neither can this. */
private const val HOST_FIELD_LENGTH = 253

private const val NOT_PERSISTING =
    "Settings cannot be saved on this device — the encrypted preferences file would not open. Everything " +
        "here still works, but every change is lost when GameCore stops running."

/** The licence GameCore is actually shipped under — the same one as the `LICENSE` file in the repository. */
private const val LICENCE_NAME = "MIT"

/**
 * The licence, abbreviated to the part that answers the question someone taps the row to ask.
 *
 * Not the full text. The warranty disclaimer is two paragraphs of capitals that nobody reads on a phone,
 * and pasting them here would bury the grant. The copyright line and the permission sentence are the two
 * things that are actually *about this app*, and the row says plainly that the rest is in the repository
 * rather than implying this is the whole document.
 */
private const val LICENCE_TEXT =
    "MIT License · Copyright (c) 2026 Dreamucxe\n\n" +
        "Permission is hereby granted, free of charge, to any person obtaining a copy of this software " +
        "and associated documentation files to deal in the Software without restriction, including " +
        "without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense " +
        "and/or sell copies of the Software, subject to the copyright notice and this permission notice " +
        "being included in all copies.\n\n" +
        "The Software is provided \"as is\", without warranty of any kind. The full text ships with the " +
        "source."

private const val ABOUT_TEXT =
    "Everything GameCore records stays on this device. There is no account and no server of its own, no " +
        "ads, and nothing you measure, record or save is uploaded anywhere. One thing uses the network: " +
        "the latency measurement you can switch off above. It does not root the device, modify a game, " +
        "or report a change it could not actually make — where Android does not allow something, the " +
        "screen for it says so instead."
