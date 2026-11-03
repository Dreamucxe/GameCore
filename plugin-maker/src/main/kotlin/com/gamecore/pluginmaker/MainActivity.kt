package com.gamecore.pluginmaker

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gamecore.studio.core.StudioSession
import com.gamecore.studio.model.PluginDraft
import com.gamecore.studio.ui.PluginStudioScreen
import com.gamecore.studio.ui.PluginStudioTheme

/**
 * The extension the studio gives the package it writes.
 *
 * The studio reports the name of the file it wrote without this extension, so
 * the maker adds it back when it tells the user where the file went: the name
 * they will look for in the folder they picked is the one with it.
 */
private const val GAME_CORE_PLUGIN_EXTENSION = ".gcplugin"

/**
 * The Plugin Maker's only activity.
 *
 * The maker is a shell around the studio: the editor in `plugin-studio` does
 * the work, and this activity does the three things an editor cannot do for
 * itself, because they are the *phone's* business rather than the plugin's.
 *
 *  - **It holds the draft.** The studio never mutates the draft in place, so
 *    the host keeps the single copy and hands it back on every change.
 *  - **It knows what GameCore is installed.** The studio is a library and
 *    knows nothing about the phone it runs on; the maker can ask the package
 *    manager, and it tells the user whether the plugin they are making will
 *    run on the phone in their hand.
 *  - **It can start GameCore.** A jump from the editor to the app that will
 *    run the plugin, when there is one to jump to.
 *
 * Nothing here needs a permission. The package is written through the storage
 * access framework, where the user picks the folder, and the installed
 * GameCore is read through the package manager, which is a query.
 */
class MainActivity : ComponentActivity() {

    /**
     * The GameCore on this phone, or null when there is none.
     *
     * A state and not a plain field because it can change while the maker is
     * in the background: the user can install GameCore from the launcher and
     * come back, and the maker should notice rather than keep saying no.
     */
    private val installedGameCore = mutableStateOf<InstalledGameCore?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The window's title, which is what the task switcher and accessibility
        // services read. The launcher shows the app label from the manifest.
        title = MakerStrings.TITLE
        refreshGameCore()
        setContent {
            PluginStudioTheme {
                MakerRoot(
                    session = MakerStudio.session,
                    installedGameCore = installedGameCore,
                    launchGameCore = ::launchGameCore,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Cheap, and it is the only moment at which the answer can have
        // changed: the maker is in the foreground and the phone is not.
        refreshGameCore()
    }

    /** Re-reads the installed GameCore from the package manager. */
    private fun refreshGameCore() {
        installedGameCore.value = readInstalledGameCore(packageManager)
    }

    /**
     * Starts GameCore.
     *
     * Returns false when there was nothing to start — GameCore is not
     * installed, or the package manager would not give up a launcher intent
     * for it. The caller says so to the user rather than pretending the tap
     * did something, and nothing here throws: a maker with no GameCore on the
     * phone is a perfectly ordinary maker.
     */
    private fun launchGameCore(): Boolean {
        val intent = gameCoreLaunchIntent(packageManager) ?: return false
        return try {
            startActivity(intent)
            true
        } catch (notThere: android.content.ActivityNotFoundException) {
            false
        }
    }
}

/**
 * The maker's chrome around the editor.
 *
 * The editor fills the screen; the chrome is the strip at the top that says
 * what GameCore is on this phone, because that is the one fact the editor
 * cannot know and the user most needs before they spend an hour on a plugin
 * that will not run.
 */
@Composable
private fun MakerRoot(
    session: StudioSession,
    installedGameCore: MutableState<InstalledGameCore?>,
    launchGameCore: () -> Boolean,
) {
    // The one copy of the draft. The studio never mutates it; it hands a new
    // one back and the host swaps it in. The debug-only MCP bridge can replace
    // the same session from a worker thread, so an observer that is not already
    // on the main thread posts its update rather than writing Compose state off
    // the UI thread.
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var draft by remember { mutableStateOf(session.current()) }
    DisposableEffect(session) {
        val listener: (PluginDraft) -> Unit = { next ->
            if (Looper.myLooper() == Looper.getMainLooper()) {
                draft = next
            } else {
                mainHandler.post { draft = next }
            }
        }
        session.addListener(listener)
        onDispose { session.removeListener(listener) }
    }

    val snackbarHostState = remember { SnackbarHostState() }

    // A message is a one-shot outcome rather than a piece of the layout: it is
    // shown once and then forgotten, so it cannot reappear on the next
    // recomposition.
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        message = null
    }

    Scaffold(
        topBar = {
            MakerHeader(
                gameCore = installedGameCore.value,
                onOpenGameCore = {
                    if (!launchGameCore()) {
                        // No GameCore to open. Say so instead of doing nothing
                        // in silence.
                        message = MakerStrings.GAME_CORE_NOT_INSTALLED
                    }
                },
            )
        },
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.navigationBarsPadding(),
            )
        },
        // The chrome owns the system bars: the strip insets itself from the
        // status bar, the snackbar from the navigation bar, and the editor is
        // handed the whole area so that its own insets are applied once and
        // not twice.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        PluginStudioScreen(
            draft = draft,
            onDraftChange = { session.update(it) },
            modifier = Modifier.padding(innerPadding),
            onSaved = { fileName ->
                // The studio reports the name of the package it wrote without
                // the extension it gave the file, so the extension is put back
                // here: this is the name the user is going to look for in the
                // folder they picked.
                message = MakerStrings.saveConfirmation("$fileName$GAME_CORE_PLUGIN_EXTENSION")
            },
            onOpenGameCore = {
                if (!launchGameCore()) {
                    message = MakerStrings.GAME_CORE_NOT_INSTALLED
                }
            },
        )
    }
}

/**
 * The strip at the top of the maker: what the app is, and what is on the phone.
 *
 * The studio's editor has a heading of its own, so this strip does not repeat
 * one; what it does carry is the one fact the editor cannot know and the user
 * most needs before they spend an hour on a plugin that will not run — which
 * GameCore is on this phone, and which version.
 */
@Composable
private fun MakerHeader(
    gameCore: InstalledGameCore?,
    onOpenGameCore: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val installed = gameCore != null
    Surface(color = scheme.surfaceVariant) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = MakerStrings.SUBTITLE,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (installed) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = if (installed) scheme.primary else scheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = gameCoreStatusSentence(gameCore),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (installed) scheme.primary else scheme.error,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            TextButton(
                onClick = onOpenGameCore,
                // Nothing to open when GameCore is not on the phone. The line
                // next to it says why.
                enabled = installed,
            ) {
                Text(MakerStrings.OPEN_GAMECORE)
            }
        }
    }
}
