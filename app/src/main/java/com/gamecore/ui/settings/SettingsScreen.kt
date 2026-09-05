package com.gamecore.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.BuildConfig
import com.gamecore.core.common.Formatters
import com.gamecore.core.model.AccentChoice
import com.gamecore.core.model.AppSettings
import com.gamecore.core.model.RecordingQuality
import com.gamecore.core.model.ThemeChoice
import com.gamecore.ui.Destination
import com.gamecore.ui.components.ActionRow
import com.gamecore.ui.components.ChoiceRow
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
                onScaleChange = viewModel::setUiScale,
                onScaleCommit = viewModel::commitScale,
                modifier = padded,
            )
        }
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
 * Theme, accent and scale.
 *
 * Dynamic colour appears only on Android 12 and later, where the platform actually has a wallpaper palette
 * to take. On Android 11 a switch for it would be a control that does nothing, which §32 does not allow, so
 * the row is replaced by a line saying the device has no such palette.
 */
@Composable
private fun AppearanceCard(
    state: SettingsUiState,
    onEdit: ((AppSettings) -> AppSettings) -> Unit,
    onScaleChange: (Int) -> Unit,
    onScaleCommit: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
            perRow = 3,
        )
        RowDivider()
        Text(
            text = "ACCENT",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ColourSwatches(
            colours = AccentChoice.entries.map { Color(it.argb) },
            selected = Color(state.settings.accent.argb),
            onSelect = { picked -> onEdit { it.copy(accent = accentFor(picked)) } },
        )
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
        SliderRow(
            title = "Text and control size",
            value = state.uiScalePercent,
            range = AppSettings.MIN_UI_SCALE..AppSettings.MAX_UI_SCALE,
            onValueChange = onScaleChange,
            valueLabel = "${state.uiScalePercent}%",
            description = "Scales GameCore's own screens. It does not change anything outside this app.",
            onValueChangeFinished = onScaleCommit,
        )
    }
}

/** Maps a swatch back to the choice it came from. Identity by colour, since the swatches are built from it. */
private fun accentFor(colour: Color): AccentChoice =
    AccentChoice.entries.firstOrNull { Color(it.argb) == colour } ?: AccentChoice.CYAN

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

/** Three screens rather than three settings: none of these is a value, they are all a state of the device. */
@Composable
private fun AccessCard(
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = "Access and tools", icon = Icons.Filled.Shield, modifier = modifier) {
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
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    SectionCard(
        title = "Data",
        icon = Icons.Filled.Storage,
        subtitle = "All of it on this device, none of it anywhere else",
        modifier = modifier,
    ) {
        KeyValueRow(label = "Recorded sessions", value = Formatters.count(state.sessionCount, "session"))
        KeyValueRow(label = "Game profiles", value = Formatters.count(state.profileCount, "profile"))
        KeyValueRow(label = "HUD layouts", value = Formatters.count(state.layoutCount, "layout"))
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

/** The build, the device it is on, and the short version of what this app will not do. */
@Composable
private fun AboutCard(
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = "About", icon = Icons.Filled.PhoneAndroid, modifier = modifier) {
        KeyValueRow(label = "GameCore", value = BuildConfig.VERSION_NAME)
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

private const val ABOUT_TEXT =
    "GameCore works entirely offline. There is no account, nothing is uploaded, and the only packets it " +
        "sends are the latency measurement you can switch off above. It does not root the device, modify a " +
        "game, or report a change it could not actually make — where Android does not allow something, the " +
        "screen for it says so instead."
