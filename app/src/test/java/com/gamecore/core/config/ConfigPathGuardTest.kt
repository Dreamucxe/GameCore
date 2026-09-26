package com.gamecore.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The HARD BOUNDARY tests (spec, PART A). Every one of these asserts that the config editor cannot be talked
 * into touching a byte outside one game's own `Android/data/<pkg>/files`. The rejections are the point: a
 * traversal, an absolute-elsewhere, a prefix-sibling, or a symlink escape must each come back [PathVerdict.Rejected]
 * with the *specific* reason, and an accepted path must come back as the canonical, symlink-resolved location —
 * never the raw lexical string a caller supplied.
 *
 * Symlink resolution is injected, so these run with no disk and no privileged shell: a fake canonicaliser lets a
 * symlink "escape" or "fail to resolve" deterministically.
 */
class ConfigPathGuardTest {

    private val root = "/data/media/0/Android/data/com.game/files"

    /** Build a guard; the default canonicaliser is identity (no symlinks → physical check mirrors lexical). */
    private fun guard(canonicalize: (String) -> String = { it }) = ConfigPathGuard(root, canonicalize)

    private fun allowedPath(v: PathVerdict): String {
        assertTrue("expected Allowed but was $v", v is PathVerdict.Allowed)
        return (v as PathVerdict.Allowed).canonicalPath
    }

    private fun rejectionReason(v: PathVerdict): PathRejection {
        assertTrue("expected Rejected but was $v", v is PathVerdict.Rejected)
        return (v as PathVerdict.Rejected).reason
    }

    // ---- accepted: paths that stay inside the sandbox ----

    @Test
    fun `a plain relative path inside the root is allowed`() {
        assertEquals("$root/settings.ini", allowedPath(guard().resolve("settings.ini")))
    }

    @Test
    fun `a nested relative path is allowed`() {
        assertEquals("$root/cfg/graphics.cfg", allowedPath(guard().resolve("cfg/graphics.cfg")))
    }

    @Test
    fun `the root itself resolves back to the root`() {
        assertEquals(root, allowedPath(guard().resolve(".")))
    }

    @Test
    fun `redundant separators and dot segments collapse`() {
        assertEquals("$root/a/b", allowedPath(guard().resolve("a//./b")))
    }

    @Test
    fun `an absolute path already inside the root is allowed`() {
        assertEquals("$root/save.dat", allowedPath(guard().resolve("$root/save.dat")))
    }

    @Test
    fun `a relative path that dips into a subdir then back out but stays inside is allowed`() {
        assertEquals("$root/b", allowedPath(guard().resolve("a/../b")))
    }

    // ---- rejected: traversal ----

    @Test
    fun `dot-dot traversal above the root is rejected`() {
        assertEquals(PathRejection.TRAVERSAL_ABOVE_ROOT, rejectionReason(guard().resolve("../other/secret")))
    }

    @Test
    fun `a traversal that dips in then climbs out of the root is rejected`() {
        assertEquals(PathRejection.TRAVERSAL_ABOVE_ROOT, rejectionReason(guard().resolve("sub/../../sibling")))
    }

    @Test
    fun `a relative path climbing all the way to the filesystem root is rejected`() {
        assertEquals(
            PathRejection.TRAVERSAL_ABOVE_ROOT,
            rejectionReason(guard().resolve("../../../../../../../../etc/hosts")),
        )
    }

    // ---- rejected: absolute-elsewhere ----

    @Test
    fun `an absolute path outside the root is rejected`() {
        assertEquals(PathRejection.ABSOLUTE_OUTSIDE_ROOT, rejectionReason(guard().resolve("/etc/hosts")))
    }

    @Test
    fun `an absolute path into another package's data dir is rejected`() {
        val other = "/data/media/0/Android/data/com.other/files/save.dat"
        assertEquals(PathRejection.ABSOLUTE_OUTSIDE_ROOT, rejectionReason(guard().resolve(other)))
    }

    @Test
    fun `a sibling directory sharing the root's name prefix is not treated as inside`() {
        // "files-backup" starts with "files" but is a different directory; a naive prefix test would let it in.
        val sibling = "/data/media/0/Android/data/com.game/files-backup/x"
        assertEquals(PathRejection.ABSOLUTE_OUTSIDE_ROOT, rejectionReason(guard().resolve(sibling)))
    }

    @Test
    fun `an absolute path that uses traversal to leave the root is rejected`() {
        assertEquals(
            PathRejection.ABSOLUTE_OUTSIDE_ROOT,
            rejectionReason(guard().resolve("$root/../../com.other/files/x")),
        )
    }

    // ---- rejected: unusable input ----

    @Test
    fun `an empty or whitespace-only path is rejected`() {
        assertEquals(PathRejection.EMPTY, rejectionReason(guard().resolve("")))
        assertEquals(PathRejection.EMPTY, rejectionReason(guard().resolve("   ")))
    }

    @Test
    fun `a NUL byte in the path is rejected before any filesystem access`() {
        // 0.toChar() is the NUL byte, written without a \u escape so the test source stays plainly legible.
        val withNul = "save" + 0.toChar() + ".dat"
        assertEquals(PathRejection.ILLEGAL_CHARACTER, rejectionReason(guard().resolve(withNul)))
    }

    // ---- rejected/allowed: symlink resolution (the check a textual validator misses) ----

    @Test
    fun `a path that is textually inside but symlinks outside the root is rejected`() {
        val escaping: (String) -> String = { path ->
            if (path == "$root/evil") "/data/media/0/Android/data/com.other/files/target" else path
        }
        assertEquals(PathRejection.SYMLINK_ESCAPES_ROOT, rejectionReason(guard(escaping).resolve("evil")))
    }

    @Test
    fun `a symlink that resolves to another location still inside the root is allowed`() {
        val redirect: (String) -> String = { path ->
            if (path == "$root/link") "$root/real/config.ini" else path
        }
        // The returned path is the canonical target, not the lexical "link" the caller passed.
        assertEquals("$root/real/config.ini", allowedPath(guard(redirect).resolve("link")))
    }

    @Test
    fun `containment is judged against the canonical root, so a symlinked ancestor does not break it`() {
        // The whole tree really lives behind a symlink: /data/media/0 -> /mnt/real. Root and target both get
        // rewritten, and the path is still correctly judged inside.
        val symlinkedAncestor: (String) -> String = { it.replaceFirst("/data/media/0", "/mnt/real") }
        assertEquals(
            "/mnt/real/Android/data/com.game/files/x",
            allowedPath(guard(symlinkedAncestor).resolve("x")),
        )
    }

    @Test
    fun `a path whose real location cannot be resolved is rejected, not trusted`() {
        val throwing: (String) -> String = { path ->
            if (path == "$root/broken") throw IOException("cannot stat") else path
        }
        assertEquals(PathRejection.SYMLINK_ESCAPES_ROOT, rejectionReason(guard(throwing).resolve("broken")))
    }

    // ---- construction ----

    @Test
    fun `constructing a guard with a relative root is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { ConfigPathGuard("not/absolute/root") }
    }
}

