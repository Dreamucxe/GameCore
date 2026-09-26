package com.gamecore.ui.config

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.config.ConfigDirEntry
import com.gamecore.core.config.ConfigDirectoryLister
import com.gamecore.core.config.ConfigListResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Where a row tap in the config browser wants to go, as an intent the host acts on.
 *
 * The browser is a tree the user walks: tapping a folder descends into it, tapping a file opens the
 * editor. But [ConfigBrowserItem] deliberately carries no `isDirectory` — the screen draws a row the same
 * way whichever it is — so which of the two a tap means is a fact only this ViewModel still holds, from the
 * [ConfigDirEntry] it built the row from. This type is how that fact leaves the ViewModel: it names the
 * destination in the browser's own terms (package, user, relative path) and nothing about a nav controller.
 *
 * It is emitted, not held in UI state, and it is not a navigation call, on purpose. A ViewModel that popped
 * or pushed a back stack would have to know what is under it and could fire the same navigation twice on a
 * recomposition that re-read a state field. GameCoreRoot — which owns the graph and is not this file — maps
 * OpenDirectory to another browser and OpenFile to the editor, so the intent stops here and routing lives
 * there: the same split SessionReportViewModel makes when it reports a delete through a flag and lets the
 * host navigate.
 */
sealed interface ConfigBrowserNav {

    /** Descend into a subdirectory — the host opens another browser at [relativePath]. */
    data class OpenDirectory(
        val packageName: String,
        val userId: Int,
        val relativePath: String,
    ) : ConfigBrowserNav

    /** Open a file — the host opens the config editor on [relativePath]. */
    data class OpenFile(
        val packageName: String,
        val userId: Int,
        val relativePath: String,
    ) : ConfigBrowserNav
}

/**
 * Everything ConfigBrowserScreen draws, in one immutable snapshot, and nothing it doesn't.
 *
 * The four fields are exactly the screen's four data parameters — [title], [items], [isLoading],
 * [emptyMessage] — because that screen is deliberately stateless and takes only what it draws. There is no
 * separate `error` field the way the editor's state has one: ConfigBrowserScreen has a single message slot,
 * the centred [emptyMessage] it shows whenever [items] is empty and it is not [isLoading]. So an empty
 * folder, an absent elevated shell and a failed listing all arrive here the same way — no items, not
 * loading, and the reason in [emptyMessage] — which is the honest mapping onto a screen that offers one
 * place to say "there is nothing to show, and here is why".
 */
data class ConfigBrowserUiState(
    val title: String = "",
    val items: List<ConfigBrowserItem> = emptyList(),
    val isLoading: Boolean = true,
    val emptyMessage: String = "",
)

/**
 * Drives ConfigBrowserScreen for one config directory (task #28): lists it once, maps its children to rows,
 * and turns a row tap into a navigation intent the host carries out.
 *
 * One directory per instance, read once rather than observed. A folder's listing does not change under the
 * user while they look at it — and descending is a new destination with its own instance — so a flow here
 * would be a subscription that never fires, exactly the call SessionReportViewModel makes about a finished
 * session. The read is a single suspend call to [ConfigDirectoryLister.list] in [viewModelScope].
 *
 * Missing navigation arguments degrade to a message rather than a crash. The package and user id are read as
 * nullable and, when either is absent, the screen says it was opened without a folder instead of a `!!`
 * turning a mis-wired route into process death in front of the user — the same discipline
 * ConfigEditorViewModel applies to its target. The relative path is different: absent means the game's
 * `files` root, so it defaults to "" rather than being treated as missing.
 */
@HiltViewModel
class ConfigBrowserViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val lister: ConfigDirectoryLister,
) : ViewModel() {

    // The package and user id are the two arguments a listing cannot be built without, so they are read as
    // nullable and guarded: a route that forgot one, or named it wrongly, is a recoverable programming error
    // that must surface as a message, not a crash. The path is optional — its absence is the `files` root —
    // so it defaults to the empty string the lister already documents as "that files directory itself".
    private val packageName: String? = savedState.get<String>(ARG_PACKAGE)
    private val userId: Int? = savedState.get<Int>(ARG_USER)
    private val relativePath: String = savedState.get<String>(ARG_PATH) ?: ""

    /**
     * The relative paths, among the rows just listed, that are directories.
     *
     * The row model [ConfigBrowserItem] carries no `isDirectory` on purpose, so this set is where the
     * folder-or-file fact survives after a row is built. [onOpen] consults it to decide whether a tap
     * descends or opens the editor — a lookup by the row's own [ConfigBrowserItem.relativePath], which is
     * the same string the entry carried, so the two cannot drift.
     */
    private var directoryPaths: Set<String> = emptySet()

    private val ui = MutableStateFlow(
        ConfigBrowserUiState(title = deriveTitle(), isLoading = true),
    )

    val state: StateFlow<ConfigBrowserUiState> = ui.asStateFlow()

    /**
     * One-shot navigation intents, collected by the host.
     *
     * A [SharedFlow], never a [StateFlow]: navigating is an event that happens once per tap, not a value the
     * screen has a current one of. Backed by a buffer of one with no replay so [onOpen] can offer the intent
     * without suspending and a late collector is not re-sent a tap the user already made. The host collects
     * this and performs the actual navigation; see [ConfigBrowserNav] for why the VM only names the intent.
     */
    private val _nav = MutableSharedFlow<ConfigBrowserNav>(extraBufferCapacity = 1)
    val nav: SharedFlow<ConfigBrowserNav> = _nav.asSharedFlow()

    init {
        load()
    }

    /**
     * Lists the directory once and maps the outcome onto [ConfigBrowserUiState]; never throws.
     *
     * A missing package or user id short-circuits before the shell is touched — there is no folder named, so
     * there is nothing to list. Otherwise the three [ConfigListResult] cases each land as a screen: `Listed`
     * becomes rows (or the "empty folder" note), `RequiresShizuku` becomes the elevated-shell message the
     * editor also shows, and `Failed` surfaces its own already-user-safe [ConfigListResult.Failed.detail].
     * Every one of them clears [ConfigBrowserUiState.isLoading]; none is an exception, because a browser that
     * dies on a denied folder is worse than one that reports it.
     */
    private fun load() {
        val pkg = packageName
        val user = userId
        if (pkg == null || user == null) {
            ui.value = ui.value.copy(
                isLoading = false,
                items = emptyList(),
                emptyMessage = MISSING_ARGS_MESSAGE,
            )
            return
        }
        viewModelScope.launch {
            ui.value = when (val result = lister.list(pkg, user, relativePath)) {
                is ConfigListResult.Listed -> onListed(result.entries)
                ConfigListResult.RequiresShizuku -> ui.value.copy(
                    isLoading = false,
                    items = emptyList(),
                    emptyMessage = SHIZUKU_MESSAGE,
                )
                is ConfigListResult.Failed -> ui.value.copy(
                    isLoading = false,
                    items = emptyList(),
                    emptyMessage = result.detail,
                )
            }
        }
    }

    /**
     * Turns one directory's children into rows, folders first and then by name for a steady browse order.
     *
     * The rows carry no editability verdict, folders included: whether a *file* can actually be written is
     * the editor's call when it opens the bytes (a binary or over-cap file opens view-only there), and a
     * folder is not a thing that is edited at all — it is descended into. Claiming a read/write verdict here
     * would be this screen asserting something it does not know and the very next screen may contradict, so
     * [ConfigBrowserItem] carries no such flag (see its doc).
     *
     * [ConfigBrowserItem.sizeLabel] is "Folder" for a directory and empty for a file — never a byte count.
     * The listing behind this is `ls -1Ap`, which has no `-l` and so no size; [ConfigDirEntry.sizeBytes] is
     * always null here. An invented "0 B" would be a fabricated fact, so a file shows no size and the editor
     * reads the real one with its own stat when the file is opened.
     */
    private fun onListed(entries: List<ConfigDirEntry>): ConfigBrowserUiState {
        directoryPaths = entries.filter { it.isDirectory }.map { it.relativePath }.toSet()
        val rows = entries
            .sortedWith(compareByDescending<ConfigDirEntry> { it.isDirectory }.thenBy { it.name })
            .map { entry ->
                ConfigBrowserItem(
                    relativePath = entry.relativePath,
                    displayName = if (entry.isDirectory) entry.name + "/" else entry.name,
                    sizeLabel = if (entry.isDirectory) "Folder" else "",
                )
            }
        return ui.value.copy(
            isLoading = false,
            items = rows,
            emptyMessage = if (rows.isEmpty()) EMPTY_FOLDER_MESSAGE else "",
        )
    }

    /**
     * Reports where the tapped row wants to go; the host does the moving.
     *
     * The row hands back its [ConfigBrowserItem.relativePath]; whether that path is a directory is looked up
     * in [directoryPaths] — the fact the flat row model dropped — and an [ConfigBrowserNav.OpenDirectory] or
     * [ConfigBrowserNav.OpenFile] is offered on [nav] accordingly. If the arguments were missing there are no
     * rows to tap, so the guard here is belt-and-braces rather than a real path. `tryEmit` cannot fail on the
     * single-slot buffer for a lone tap, which is the whole reason that buffer exists.
     */
    fun onOpen(relativePath: String) {
        val pkg = packageName ?: return
        val user = userId ?: return
        val intent = if (relativePath in directoryPaths) {
            ConfigBrowserNav.OpenDirectory(pkg, user, relativePath)
        } else {
            ConfigBrowserNav.OpenFile(pkg, user, relativePath)
        }
        _nav.tryEmit(intent)
    }

    /**
     * The title bar name for this directory: its last path segment, the package name at the root, or a
     * generic fallback when even the package is missing.
     *
     * At the root the relative path is "" and has no last segment, so the package name stands in — it is the
     * one honest thing on hand that says *whose* config files these are, and the lister carries no game
     * label to prettify it with. Deeper in, the last segment is the folder the user just opened, which is
     * what a title should say. When the package itself is absent (a mis-wired route, already headed for the
     * missing-arguments message) there is nothing to name, so a plain [ROOT_TITLE] keeps the bar honest.
     */
    private fun deriveTitle(): String =
        if (relativePath.isEmpty()) {
            packageName ?: ROOT_TITLE
        } else {
            relativePath.substringAfterLast('/').ifEmpty { relativePath }
        }

    companion object {
        /**
         * SavedStateHandle keys for the folder this browser opens on — the same three literals
         * ConfigEditorViewModel reads, so one route shape carries a user from a folder to a file to a
         * subfolder without renaming an argument between screens. Literal strings, not references to a route
         * object, so this ViewModel compiles independently of the navigation change that feeds it — but the
         * navArguments that route registers must be named with exactly these keys.
         */
        const val ARG_PACKAGE = "cfg_package"
        const val ARG_USER = "cfg_user"
        const val ARG_PATH = "cfg_path"

        /** Shown when the browser was opened without the package/user it needs to name a folder. */
        private const val MISSING_ARGS_MESSAGE = "This browser was opened without a folder to show."

        /** Wording for the elevated shell being absent, matching how the config editor says the same thing. */
        private const val SHIZUKU_MESSAGE =
            "Browsing config files needs the elevated shell, which isn't available right now."

        /** A real, successful answer: the folder was read and there is simply nothing in it. */
        private const val EMPTY_FOLDER_MESSAGE = "This folder is empty."

        /** The fallback title when there is no path segment and no package to name the root with. */
        private const val ROOT_TITLE = "Config files"
    }
}

