package com.gamecore.core.config

import com.gamecore.core.shizuku.ElevatedShell
import com.gamecore.core.shizuku.ShellCommand
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One immediate child of a game's config directory, as the browser (task #28) shows a row.
 *
 * [relativePath] is the child's own path relative to the game's `files` directory — the parent's
 * relative path with [name] appended — so it can be handed straight back to
 * [ShellCommand.listConfigDir] to descend into a subdirectory, or to `statConfigFile`/`copyConfigFile`
 * to open a file, without any call site rebuilding a path. It is composed the same way the shell's own
 * path builder joins segments (a single `/` between parts, nothing before a top-level name), so the
 * string this carries and the one the shell validates are the same string.
 *
 * [isDirectory] comes from the trailing-slash marker `ls -p` writes, not from a second stat call, which
 * is the whole reason the listing uses `-p`: the browser can tell a folder from a file in one command.
 *
 * [sizeBytes] is nullable and, with the command as it stands today, is always null — see
 * [ConfigDirectoryLister] for why. The field exists so the browser's row model does not have to change
 * if the listing is ever taught to expose a size; until then "unknown" is the honest value, not zero.
 */
data class ConfigDirEntry(
    val relativePath: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long?,
)

/**
 * The outcome of listing one config directory.
 *
 * Three cases, kept distinct because the browser reacts to each differently — the same shape the
 * config editor's own results ([com.gamecore.domain.config.ConfigOpenResult] and friends) settled on,
 * mirrored here rather than imported because those live a layer up in `domain` and this is `core`.
 *
 *  * [Listed] — the directory was read. An empty list is a success, not a failure: a game's `files`
 *    directory legitimately starts out empty, and "nothing here" is a real answer to show.
 *  * [RequiresShizuku] — the elevated shell is not available, so the game's sandbox cannot be reached
 *    at all. The browser prompts for Shizuku rather than showing an error, exactly as the editor does.
 *  * [Failed] — the shell was reachable but the listing did not happen: either the path could not be
 *    built ([Reason.PATH_REJECTED], the null from a factory that refused a traversal or a bad name) or
 *    the command itself came back non-zero ([Reason.SHELL], a denial or a missing directory). Neither
 *    is ever a thrown exception — a browser that crashes on a denied folder is worse than one that
 *    reports it.
 */
sealed interface ConfigListResult {

    /** The directory was read; [entries] is its immediate children, possibly empty. */
    data class Listed(val entries: List<ConfigDirEntry>) : ConfigListResult

    /** The elevated shell is not available; the game's sandbox cannot be reached without it. */
    object RequiresShizuku : ConfigListResult

    /** The listing did not happen. [reason] says which side gave up; [detail] is a short, user-safe note. */
    data class Failed(val reason: Reason, val detail: String) : ConfigListResult {

        /** Which half of the attempt failed — kept apart so the browser can word each honestly. */
        enum class Reason {
            /** The parts named no buildable path (a `..`, a bad package, an impossible user id). */
            PATH_REJECTED,

            /** The command reached the device and exited non-zero (denied, or no such directory). */
            SHELL,
        }
    }
}

/**
 * Turns "show me this folder" into a parsed list of its immediate children, for the config-file
 * browser (task #28).
 *
 * It runs exactly one read-only command — [ShellCommand.ListConfigDir], built as `ls -1Ap <path>` — and
 * parses its stdout. It reuses the app's one shell abstraction the way the editor does: an
 * [ElevatedShell] injected through the constructor, whose [ShellResult] carries the exit code and the
 * text. Nothing here builds a path from a string; the path is built by [ShellCommand.listConfigDir],
 * which validates the package, the user id and every segment, so a `..` or an absolute path cannot
 * reach the argv and a rejected input is a [ConfigListResult.Failed] rather than a crash.
 *
 * The parser is total: any stdout — empty, or malformed — yields a list (possibly empty), never a
 * thrown exception, because a browser handed a denied or truncated listing must still return a screen.
 *
 * On the output format. `ls -1Ap` prints one entry per line (`-1`), shows dotfiles a config might hide
 * behind while still omitting `.` and `..` (`-A`), and marks a directory — and only a directory — with
 * a trailing `/` (`-p`). So a line is a directory iff it ends in `/`, and the name is the line with that
 * one marker removed; every other line is a file. Crucially, these flags carry **no size** — there is no
 * `-l` and no `-s` in the command — so [ConfigDirEntry.sizeBytes] is always null here. That is faithful
 * to the source command rather than a gap: the editor reads a file's size with a separate
 * [ShellCommand.StatConfigFile] when it opens one, and the browser can do the same per row if it ever
 * needs a size, rather than this lister inventing one the listing never gave.
 */
@Singleton
class ConfigDirectoryLister @Inject constructor(
    private val shell: ElevatedShell,
) {

    /**
     * List the immediate children of [relativePath] inside [packageName]'s own `files` directory for
     * [userId]. A blank [relativePath] (the default) names that `files` directory itself.
     *
     * The shell is checked for availability first, so an absent Shizuku is reported as
     * [ConfigListResult.RequiresShizuku] before a command is ever built. The path is then built through
     * the validating factory: a null there means the parts could not name a safe path, and is turned
     * into a [ConfigListResult.Failed] with [ConfigListResult.Failed.Reason.PATH_REJECTED] — the command
     * is never run. A non-zero exit becomes [ConfigListResult.Failed.Reason.SHELL], carrying the shell's
     * own user-safe [ShellResult.failureReason] and never its stdout.
     */
    suspend fun list(
        packageName: String,
        userId: Int,
        relativePath: String = "",
    ): ConfigListResult {
        if (!shell.isAvailable()) return ConfigListResult.RequiresShizuku

        val command = ShellCommand.listConfigDir(packageName, userId, relativePath)
            ?: return ConfigListResult.Failed(
                ConfigListResult.Failed.Reason.PATH_REJECTED,
                "That location could not be read.",
            )

        val result = shell.execute(command)
        if (!result.isSuccess) {
            return ConfigListResult.Failed(ConfigListResult.Failed.Reason.SHELL, result.failureReason())
        }
        return ConfigListResult.Listed(parse(relativePath, result.lines()))
    }

    /**
     * Parse `ls -1Ap` output into entries, given the [parentRelativePath] the listing was of.
     *
     * [ShellResult.lines] has already dropped blank lines, so an empty directory (empty stdout) arrives
     * here as an empty list and leaves as one. Lines are not trimmed: a space is a legal filename
     * character and `-1` prints the name verbatim, so trimming would corrupt a real name; the only
     * marker to strip is the single trailing `/` that `-p` adds to a directory. `.` and `..` are skipped
     * defensively — `-A` already omits them, but a shell that ignored the flag must not put "go up a
     * level" rows in a browser — as is any line that reduces to an empty name.
     */
    private fun parse(parentRelativePath: String, lines: List<String>): List<ConfigDirEntry> {
        val entries = ArrayList<ConfigDirEntry>(lines.size)
        for (line in lines) {
            val isDirectory = line.endsWith("/")
            val name = if (isDirectory) line.dropLast(1) else line
            if (name.isEmpty() || name == "." || name == "..") continue
            entries += ConfigDirEntry(
                relativePath = childRelativePath(parentRelativePath, name),
                name = name,
                isDirectory = isDirectory,
                // No `-l`/`-s` in `ls -1Ap`, so the listing exposes no size; null is the honest value.
                sizeBytes = null,
            )
        }
        return entries
    }

    /**
     * Join a child [name] onto its [parent] relative path the way the shell's path builder joins
     * segments: a single `/` between parts, and nothing before a name directly under `files`. The result
     * is a valid `relativePath` for the same command family, which is what lets a browser descend by
     * feeding one entry's [ConfigDirEntry.relativePath] straight back into [list].
     */
    private fun childRelativePath(parent: String, name: String): String =
        if (parent.isEmpty()) name else "$parent/$name"
}
