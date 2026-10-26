package com.gamecore.ui.emulators

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.core.common.Formatters
import com.gamecore.core.emulator.EmulatorDescriptor
import com.gamecore.core.emulator.EmulatorRegistry
import com.gamecore.core.emulator.EmulatorSystem
import com.gamecore.core.emulator.InstalledEmulator
import com.gamecore.core.emulator.RomEntry
import com.gamecore.ui.components.EmptyState
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.OnResume
import com.gamecore.ui.components.PlainCard
import com.gamecore.ui.components.RowDivider
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.colour
import com.gamecore.ui.components.startIntentSafely
import com.gamecore.ui.games.GameIcon

/**
 * The emulator frontend, labelled experimental on purpose.
 *
 * It is a launcher, not an emulator. The screen claims three concrete things: which emulators are
 * installed (a package-name lookup), which files the user added (a document URI plus a system they
 * confirmed), and which intent Android accepted (a resolved `ACTION_VIEW` or a launcher). It never claims
 * that a game ran. The bottom note repeats that distinction because it is the one a screenshot can make
 * look like a lie.
 */
@Composable
fun EmulatorScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EmulatorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.addRom(uri)
    }

    // Installing an emulator happens outside GameCore, so detection has to be re-read when the user
    // comes back rather than only once when this screen was composed.
    OnResume { viewModel.refresh() }

    LaunchedEffect(state.launchRequest) {
        val request = state.launchRequest ?: return@LaunchedEffect
        viewModel.launchHandled(context.startIntentSafely(request.intent))
    }

    val horizontal = Modifier.padding(horizontal = ScreenPadding)
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Emulators",
                subtitle = "Experimental launcher for emulators already on this device",
                onBack = onBack,
                action = {
                    TextButton(onClick = viewModel::openGetEmulators) { Text("Get") }
                },
            )
        }

        item {
            NoteBanner(
                text = "Experimental: GameCore detects emulators and tries to hand a file to one. Some " +
                    "emulators do not accept Android document links, and opening an emulator is not proof " +
                    "that a game ran.",
                tone = Tone.Warning,
                icon = Icons.Filled.Info,
                modifier = horizontal,
            )
        }

        state.message?.let { message ->
            item {
                NoteBanner(
                    text = message,
                    tone = if (state.messageIsError) Tone.Danger else Tone.Good,
                    icon = Icons.Filled.Info,
                    modifier = horizontal,
                    action = {
                        TextButton(onClick = viewModel::dismissMessage) { Text("Dismiss") }
                    },
                )
            }
        }

        item {
            InstalledEmulatorsCard(
                state = state,
                onRefresh = viewModel::refresh,
                onOpen = viewModel::openEmulator,
                onGet = viewModel::openGetEmulators,
                modifier = horizontal,
            )
        }

        item {
            LibraryHeader(
                count = state.roms.size,
                busy = state.busy,
                onAdd = { picker.launch(arrayOf("*/*")) },
                modifier = horizontal,
            )
        }

        if (state.loading && state.roms.isEmpty()) {
            item {
                Row(
                    modifier = horizontal.fillMaxWidth().padding(vertical = 20.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Reading installed apps and the library…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else if (state.roms.isEmpty()) {
            item {
                PlainCard(modifier = horizontal) {
                    EmptyState(
                        icon = Icons.Filled.Storage,
                        title = "No ROMs yet",
                        message = "Add an .iso, .chd, .gcm, .rvz, .cso, .nsp, .nds, .gba or .exe file. " +
                            "GameCore keeps a link to the file where it already is; it does not copy it.",
                        actionLabel = "Add ROM",
                        onAction = { picker.launch(arrayOf("*/*")) },
                    )
                }
            }
        } else {
            state.roms
                .sortedWith(compareBy<RomEntry> { it.system.ordinal }.thenByDescending { it.addedAtMillis })
                .groupBy { it.system }
                .forEach { (system, roms) ->
                    item(key = "system-${system.name}") {
                        SystemHeader(system = system, count = roms.size, modifier = horizontal)
                    }
                    items(items = roms, key = { it.id }) { rom ->
                        RomRow(
                            rom = rom,
                            rememberedEmulator = state.rememberedEmulator(rom),
                            onLaunch = { viewModel.launch(rom) },
                            onChangeSystem = { viewModel.openSystemEditor(rom) },
                            onDelete = { viewModel.requestDelete(rom) },
                            modifier = horizontal,
                        )
                    }
                }
        }

        item {
            NoteBanner(
                text = "GameCore does not include an emulator and does not execute ROMs itself. It hands a " +
                    "file to an app you installed. If that app opens its own browser instead, the file has " +
                    "not been launched. Windows .exe files need a container such as Winlator and cannot be " +
                    "handed over directly.",
                tone = Tone.Muted,
                icon = Icons.Filled.Info,
                modifier = horizontal,
            )
        }
    }

    state.pendingRom?.let { pending ->
        PendingRomDialog(
            pending = pending,
            emulators = state.emulators,
            onChoose = viewModel::confirmPendingSystem,
            onDismiss = viewModel::cancelPendingRom,
        )
    }

    state.systemEditor?.let { rom ->
        SystemEditorDialog(
            rom = rom,
            emulators = state.emulators,
            onChange = { system -> viewModel.changeSystem(rom, system) },
            onDismiss = viewModel::dismissSystemEditor,
        )
    }

    state.launchChooser?.let { chooser ->
        LaunchChooserDialog(
            chooser = chooser,
            onChoose = viewModel::launchWith,
            onDismiss = viewModel::dismissLaunchChooser,
        )
    }

    state.pendingDelete?.let { rom ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            icon = { Icon(Icons.Filled.Delete, contentDescription = null) },
            title = { Text("Forget ROM?") },
            text = {
                Text(
                    "\"${rom.displayName}\" will be removed from this library. The file itself is not " +
                        "deleted, and no emulator or game save is touched.",
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelete) { Text("Forget") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDelete) { Text("Cancel") }
            },
        )
    }

    if (state.showGetEmulators) {
        GetEmulatorsDialog(
            installed = state.emulators,
            onOpenDownload = viewModel::openDownload,
            onDismiss = viewModel::closeGetEmulators,
        )
    }
}

@Composable
private fun InstalledEmulatorsCard(
    state: EmulatorUiState,
    onRefresh: () -> Unit,
    onOpen: (InstalledEmulator) -> Unit,
    onGet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Installed emulators",
        subtitle = if (state.emulators.isEmpty()) {
            "None detected · experimental"
        } else {
            "${state.emulators.size} detected · experimental"
        },
        icon = Icons.Filled.SportsEsports,
        modifier = modifier,
        action = {
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh installed emulators")
            }
        },
    ) {
        if (state.emulators.isEmpty()) {
            Text(
                text = "No supported emulator is installed. GameCore does not bundle one. Install the " +
                    "emulator yourself, then refresh this screen.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onGet) { Text("Get emulators") }
        } else {
            state.emulators.forEachIndexed { index, emulator ->
                if (index > 0) RowDivider()
                EmulatorRow(emulator = emulator, onOpen = onOpen)
            }
        }
    }
}

@Composable
private fun EmulatorRow(
    emulator: InstalledEmulator,
    onOpen: (InstalledEmulator) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GameIcon(
            packageName = emulator.packageName,
            label = emulator.label,
            size = 38.dp,
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = emulator.label,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    emulator.versionName?.let { append("v").append(it).append(" · ") }
                    append(emulator.systems.joinToString(" · ") { it.shortLabel })
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = { onOpen(emulator) }) { Text("Open") }
    }
}

@Composable
private fun LibraryHeader(
    count: Int,
    busy: Boolean,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "ROM library",
        subtitle = when (count) {
            0 -> "No files added"
            1 -> "1 file"
            else -> "$count files"
        },
        icon = Icons.Filled.Storage,
        modifier = modifier,
        action = {
            TextButton(onClick = onAdd, enabled = !busy) { Text("Add ROM") }
        },
    ) {
        Text(
            text = "Files stay where they are. GameCore stores a document link and hands it to an " +
                "emulator you installed; it does not copy or run the file.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SystemHeader(
    system: EmulatorSystem,
    count: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = system.label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (count == 1) "1 file" else "$count files",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RomRow(
    rom: RomEntry,
    rememberedEmulator: InstalledEmulator?,
    onLaunch: () -> Unit,
    onChangeSystem: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember(rom.id) { mutableStateOf(false) }
    PlainCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = Icons.Filled.SportsEsports,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp).size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rom.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = listOfNotNull(
                        rom.system.label,
                        rom.suffix.takeIf { it.isNotEmpty() },
                        rom.sizeBytes?.let(Formatters::bytes),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when {
                    !rom.hasKnownSystem -> Text(
                        text = "System not set. Choose one before launching.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Tone.Warning.colour(),
                    )

                    rememberedEmulator != null -> Text(
                        text = "Remembered: ${rememberedEmulator.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "More options for ${rom.displayName}",
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Change system") },
                        onClick = {
                            menuOpen = false
                            onChangeSystem()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Forget ROM") },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(onClick = onLaunch, enabled = rom.hasKnownSystem) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(if (rom.system == EmulatorSystem.WINDOWS) "Open container" else "Launch")
            }
            if (rom.launchCount > 0) {
                Text(
                    text = if (rom.launchCount == 1) {
                        "Handed off once"
                    } else {
                        "Handed off ${rom.launchCount} times"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PendingRomDialog(
    pending: PendingRom,
    emulators: List<InstalledEmulator>,
    onChoose: (EmulatorSystem) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Which system?") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "${pending.draft.displayName} is a .${pending.draft.extension} file, and more " +
                        "than one system uses that suffix. Choose the system this file belongs to.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                pending.candidates.forEach { system ->
                    val installed = emulators.count { system in it.systems }
                    SystemChoiceRow(
                        system = system,
                        detail = if (installed == 0) {
                            "No installed emulator handles this system yet."
                        } else if (installed == 1) {
                            "1 installed emulator handles this system."
                        } else {
                            "$installed installed emulators handle this system."
                        },
                        onClick = { onChoose(system) },
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun SystemEditorDialog(
    rom: RomEntry,
    emulators: List<InstalledEmulator>,
    onChange: (EmulatorSystem) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change system") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "Current system: ${rom.system.label}. Changing it does not move or copy the " +
                        "file; it changes which installed emulators are offered.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                EmulatorSystem.entries
                    .filter { it != EmulatorSystem.UNKNOWN }
                    .forEach { system ->
                        val installed = emulators.count { system in it.systems }
                        SystemChoiceRow(
                            system = system,
                            detail = when {
                                system == rom.system -> "Current system"
                                installed == 0 -> "No installed emulator handles this system."
                                installed == 1 -> "1 installed emulator handles this system."
                                else -> "$installed installed emulators handle this system."
                            },
                            onClick = { onChange(system) },
                        )
                        Spacer(Modifier.height(6.dp))
                    }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun SystemChoiceRow(
    system: EmulatorSystem,
    detail: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(text = system.label, style = MaterialTheme.typography.titleSmall)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LaunchChooserDialog(
    chooser: LaunchChooser,
    onChoose: (InstalledEmulator) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open with") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "${chooser.rom.displayName} can be handed to more than one installed " +
                        "emulator. Choose one; GameCore will remember it for this file.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                chooser.choices.handOff.forEach { emulator ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GameIcon(
                            packageName = emulator.packageName,
                            label = emulator.label,
                            size = 36.dp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = emulator.label, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = emulator.systems.joinToString(" · ") { it.shortLabel },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onChoose(emulator) }) { Text("Launch") }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun GetEmulatorsDialog(
    installed: List<InstalledEmulator>,
    onOpenDownload: (EmulatorDescriptor) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Experimental emulator support") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "GameCore does not include, download or install an emulator. Each link opens " +
                        "the project's own release page in your browser; installing it is your choice. " +
                        "Detection is by package name.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                EmulatorRegistry.all.forEachIndexed { index, descriptor ->
                    if (index > 0) RowDivider()
                    val emulator = installed.firstOrNull { it.descriptor.id == descriptor.id }
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = descriptor.displayName,
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    text = descriptor.systems.joinToString(" · ") { it.shortLabel },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            when {
                                emulator != null -> StatusChip("Installed", Tone.Good)
                                descriptor.downloadUrl != null -> TextButton(
                                    onClick = { onOpenDownload(descriptor) },
                                ) { Text("Get") }

                                else -> Text(
                                    text = "No link",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        descriptor.note?.let { note ->
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = note,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}
