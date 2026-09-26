package com.gamecore.core.config

/**
 * The HARD BOUNDARY around every config-editor file operation (spec, PART A). Nothing the editor lists, reads,
 * backs up or writes is allowed to touch a byte outside a single game's own external files directory —
 * `Android/data/<pkg>/files` — no matter what path the UI, a directory listing, or a crafted filename asks for.
 *
 * ## Why a dedicated guard, and why it is this strict
 *
 * The privileged shell runs as a user that can read and write far more than one game's sandbox. A path bug
 * here is not a wrong edit, it is the editor reaching into *another* app's data or the user's whole SD card.
 * So containment is decided here, once, for every op, and the guard refuses anything it cannot *prove* stays
 * inside the [root]:
 *
 *  - **Traversal** — a relative path that climbs out with `..` (`saves/../../secret`) is rejected, not clamped.
 *  - **Absolute-elsewhere** — an absolute path that does not sit under [root] is rejected outright.
 *  - **Symlink escape** — a path that stays inside [root] *lexically* but whose real (symlink-resolved)
 *    location leaves [root] is rejected. This is the check a purely textual validator misses, and the one an
 *    attacker who can drop a symlink into the sandbox would rely on.
 *
 * The guard proves containment twice: once lexically (normalising `.`/`..` without touching the disk) and once
 * physically (canonicalising through [canonicalize], which resolves symlinks). A path must clear *both* — a
 * lexical pass with a symlink escape is still a reject.
 *
 * ## Injecting canonicalisation
 *
 * Symlink resolution is real filesystem I/O, so it is injected as [canonicalize] rather than hard-wired to
 * [java.io.File]. Production passes [realFilesystemCanonicalizer]; tests pass a map so a symlink escape can be
 * simulated deterministically, with no disk and no privileged shell. The guard's own logic stays pure.
 */
class ConfigPathGuard(
    root: String,
    private val canonicalize: (String) -> String = realFilesystemCanonicalizer,
) {
    /** The lexically-normalised, absolute root every resolved path must remain within. Never has a trailing '/'. */
    private val root: String = normalizeAbsolute(root)
        ?: throw IllegalArgumentException("root must be an absolute path that does not climb above '/': $root")

    /** The symlink-resolved root, computed once, that every candidate's canonical form is compared against. */
    private val canonicalRoot: String = canonicalize(this.root)

    /**
     * Decide whether [candidate] is safe to operate on. [candidate] may be relative (resolved against [root])
     * or absolute (which must already sit under [root]). Returns [PathVerdict.Allowed] carrying the canonical,
     * symlink-resolved absolute path — the only path a caller should ever hand to a privileged op — or
     * [PathVerdict.Rejected] with the specific reason it was refused.
     */
    fun resolve(candidate: String): PathVerdict {
        if (candidate.isBlank()) return PathVerdict.Rejected(PathRejection.EMPTY)
        // A NUL byte terminates a C string; smuggling one past a textual check is a classic way to make the
        // shell open a different path than the one validated. Refuse it before it can reach the filesystem.
        if (candidate.contains('\u0000')) return PathVerdict.Rejected(PathRejection.ILLEGAL_CHARACTER)

        val absolute = candidate.startsWith("/")
        val joined = if (absolute) candidate else "$root/$candidate"

        // 1. Lexical containment: normalise '.'/'..' with no disk access. A '..' that climbs above the
        //    filesystem root fails normalisation; one that merely climbs above [root] fails the prefix test.
        val normalized = normalizeAbsolute(joined)
            ?: return PathVerdict.Rejected(
                if (absolute) PathRejection.ABSOLUTE_OUTSIDE_ROOT else PathRejection.TRAVERSAL_ABOVE_ROOT,
            )
        if (!isWithin(root, normalized)) {
            return PathVerdict.Rejected(
                if (absolute) PathRejection.ABSOLUTE_OUTSIDE_ROOT else PathRejection.TRAVERSAL_ABOVE_ROOT,
            )
        }

        // 2. Physical containment: resolve symlinks and re-check. A path that is textually inside [root] but
        //    whose real location is not (a symlinked file or directory) is refused here. If the real location
        //    cannot be resolved at all, we cannot prove containment, so we refuse rather than risk it.
        val canonical = try {
            canonicalize(normalized)
        } catch (e: Exception) {
            return PathVerdict.Rejected(PathRejection.SYMLINK_ESCAPES_ROOT)
        }
        if (!isWithin(canonicalRoot, canonical)) {
            return PathVerdict.Rejected(PathRejection.SYMLINK_ESCAPES_ROOT)
        }
        return PathVerdict.Allowed(canonical)
    }
}

/** The outcome of [ConfigPathGuard.resolve]: an allowed canonical path, or a rejection with its reason. */
sealed interface PathVerdict {
    /** [canonicalPath] is symlink-resolved, absolute, and proven to sit within the guard's root. */
    data class Allowed(val canonicalPath: String) : PathVerdict

    /** The path was refused; [reason] says which boundary it crossed. No filesystem op should follow. */
    data class Rejected(val reason: PathRejection) : PathVerdict
}

/** Why [ConfigPathGuard] refused a path. Each value is a distinct way out of the game's sandbox (or an unusable input). */
enum class PathRejection {
    /** The candidate was empty or whitespace-only. */
    EMPTY,

    /** The candidate held a character no config path may contain (currently a NUL byte). */
    ILLEGAL_CHARACTER,

    /** A relative path climbed above the root with `..` (or above the filesystem root entirely). */
    TRAVERSAL_ABOVE_ROOT,

    /** An absolute path pointed outside the root. */
    ABSOLUTE_OUTSIDE_ROOT,

    /** The path was textually inside the root but its symlink-resolved location was not, or could not be resolved. */
    SYMLINK_ESCAPES_ROOT,
}

/**
 * Lexically normalise an absolute [path], collapsing `.` and empty segments and applying `..`, with no disk
 * access. Returns null if [path] is not absolute, or if a `..` climbs above the filesystem root — either way
 * a path the guard must not trust. The result is absolute and never carries a trailing '/' (except "/" itself).
 */
private fun normalizeAbsolute(path: String): String? {
    if (!path.startsWith("/")) return null
    val out = ArrayDeque<String>()
    for (seg in path.split('/')) {
        when (seg) {
            "", "." -> continue
            ".." -> if (out.isEmpty()) return null else out.removeLast()
            else -> out.addLast(seg)
        }
    }
    return "/" + out.joinToString("/")
}

/**
 * True if the absolute [path] is [root] itself or a descendant of it. Compared segment-boundary-aware so that
 * `/a/files` never counts `/a/filesX` as inside it.
 */
private fun isWithin(root: String, path: String): Boolean =
    path == root || path.startsWith(if (root == "/") "/" else "$root/")

/**
 * Production canonicaliser: resolve symlinks and normalise through [java.io.File]. May throw [java.io.IOException],
 * which [ConfigPathGuard.resolve] treats as "cannot prove containment" and refuses.
 */
val realFilesystemCanonicalizer: (String) -> String = { path -> java.io.File(path).canonicalPath }

