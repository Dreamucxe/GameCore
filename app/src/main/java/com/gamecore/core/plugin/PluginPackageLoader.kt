package com.gamecore.core.plugin

import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * Opens a `.gcplugin` package: unpacks the zip, enforces every structural limit, pins a
 * SHA-256 over the exact bytes, parses `manifest.json` through [PluginManifestCodec], and
 * checks that what the manifest *declares* is exactly what the archive *carries*.
 *
 * This is the second half of the format's trust boundary. [PluginManifestCodec] decides
 * whether the manifest's *content* is safe; this decides whether the *container* is — and
 * a container is where the dangerous things live: a zip-slip entry that writes outside the
 * plugin's directory, a decompression bomb, a file that claims a section the manifest never
 * declared, a package too large to hold in memory on a mid-range phone. Each is a typed
 * [Result.Rejected], never an exception that reaches a caller: **a malformed or hostile
 * package can refuse to load, but it can never brick the app.** Any `Throwable` from the
 * zip machinery is caught and mapped to [Reason.MALFORMED].
 *
 * The loader never touches the filesystem. It consumes bytes and returns an in-memory
 * [LoadedPlugin] whose [LoadedPlugin.entries] map holds each validated file's bytes keyed
 * by its normalised path. Persisting those bytes to per-plugin storage is a later concern
 * (Part H); doing it here would mean a half-written plugin directory on a failure midway.
 *
 * Pure `java.util.zip`, `org.json` and the plugin models — no Android — so every limit and
 * every rejection below is a JVM unit test.
 */
object PluginPackageLoader {

    // -------------------------------------------------------------------------------- limits

    /** Whole package, compressed, as received. */
    const val MAX_PACKAGE_BYTES = 5 * 1024 * 1024

    /** Any single entry, uncompressed. Guards against a decompression bomb in one file. */
    const val MAX_ENTRY_BYTES = 2 * 1024 * 1024

    /** Sum of all uncompressed entries. A second bomb guard, across the whole archive. */
    const val MAX_TOTAL_UNCOMPRESSED_BYTES = 10 * 1024 * 1024

    /** Entry count ceiling, so a package of thousands of empty files cannot be walked. */
    const val MAX_ENTRIES = 200

    /** Path length ceiling per entry name. */
    const val MAX_PATH_LENGTH = 180

    private const val READ_CHUNK_BYTES = 64 * 1024

    /** The one entry every package must contain. */
    const val MANIFEST_ENTRY = "manifest.json"

    // -------------------------------------------------------------------------------- result

    sealed interface Result {
        data class Loaded(val plugin: LoadedPlugin) : Result
        data class Rejected(val reason: Reason, val detail: String = "") : Result
    }

    /**
     * Why a package was refused. Manifest-content reasons stay in
     * [PluginManifestCodec.Reason]; these are container reasons. [MANIFEST_REJECTED]
     * wraps the former so a caller sees a single rejection channel.
     */
    enum class Reason {
        EMPTY,
        MALFORMED,
        PACKAGE_TOO_LARGE,
        ENTRY_TOO_LARGE,
        TOO_MANY_ENTRIES,
        UNSAFE_PATH,
        DUPLICATE_ENTRY,
        MISSING_MANIFEST,
        MANIFEST_REJECTED,
        UNDECLARED_SECTION,
        MISSING_DECLARED_SECTION,
        DISALLOWED_FILE,
    }

    // -------------------------------------------------------------------------------- decode

    /** Convenience: load from a byte array already held in memory. */
    fun load(bytes: ByteArray?): Result {
        if (bytes == null || bytes.isEmpty()) return Result.Rejected(Reason.EMPTY)
        if (bytes.size > MAX_PACKAGE_BYTES) {
            return Result.Rejected(Reason.PACKAGE_TOO_LARGE, "${bytes.size} > $MAX_PACKAGE_BYTES bytes")
        }
        return loadInner(bytes)
    }

    /**
     * Load from a stream, capping the read at [MAX_PACKAGE_BYTES] so an outsized or
     * endless stream is dropped the instant it crosses the ceiling rather than held.
     */
    fun load(input: InputStream?): Result {
        if (input == null) return Result.Rejected(Reason.EMPTY)
        val bytes = readCapped(input, MAX_PACKAGE_BYTES)
            ?: return Result.Rejected(Reason.PACKAGE_TOO_LARGE, "> $MAX_PACKAGE_BYTES bytes")
        if (bytes.isEmpty()) return Result.Rejected(Reason.EMPTY)
        return loadInner(bytes)
    }

    private fun loadInner(bytes: ByteArray): Result {
        // A zip begins with a local-file-header ("PK\u0003\u0004") or, for an empty
        // archive, an end-of-central-directory ("PK\u0005\u0006") signature. Bytes that
        // are not a zip at all make ZipInputStream yield no entries silently, which would
        // otherwise be reported as a missing manifest; checking the magic up front gives
        // the truthful "this isn't a package" rejection instead.
        if (!looksLikeZip(bytes)) return Result.Rejected(Reason.MALFORMED, "not a zip archive")

        val sha256 = sha256Hex(bytes)

        val entries = LinkedHashMap<String, ByteArray>()
        var totalUncompressed = 0L

        try {
            ZipInputStream(bytes.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    try {
                        // Directories carry no content; the path grammar below makes the
                        // tree implicit, so a directory entry is simply skipped.
                        if (entry.isDirectory) continue

                        if (entries.size >= MAX_ENTRIES) {
                            return Result.Rejected(Reason.TOO_MANY_ENTRIES, "> $MAX_ENTRIES files")
                        }

                        val rawName = entry.name
                        val safe = safePathOrNull(rawName)
                            ?: return Result.Rejected(Reason.UNSAFE_PATH, rawName)

                        if (entries.containsKey(safe)) {
                            return Result.Rejected(Reason.DUPLICATE_ENTRY, safe)
                        }

                        // Read the single entry under its own cap. A ZipEntry's declared
                        // size is attacker-controlled, so we never trust it — we read and
                        // stop at the cap, which also catches a lying size field.
                        val data = readEntryCapped(zip)
                            ?: return Result.Rejected(Reason.ENTRY_TOO_LARGE, "$safe > $MAX_ENTRY_BYTES bytes")

                        totalUncompressed += data.size
                        if (totalUncompressed > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                            return Result.Rejected(
                                Reason.ENTRY_TOO_LARGE,
                                "archive uncompressed > $MAX_TOTAL_UNCOMPRESSED_BYTES bytes",
                            )
                        }

                        entries[safe] = data
                    } finally {
                        zip.closeEntry()
                    }
                }
            }
        } catch (e: ZipException) {
            return Result.Rejected(Reason.MALFORMED, e.message.orEmpty())
        } catch (e: Throwable) {
            // The zip machinery can throw a range of things on a corrupt stream; none of
            // them may escape, because a bad download must not take the process down.
            return Result.Rejected(Reason.MALFORMED, e.message.orEmpty())
        }

        val manifestBytes = entries[MANIFEST_ENTRY]
            ?: return Result.Rejected(Reason.MISSING_MANIFEST)

        val manifest = when (val decoded = PluginManifestCodec.decode(manifestBytes.toString(Charsets.UTF_8))) {
            is PluginManifestCodec.Result.Parsed -> decoded.manifest
            is PluginManifestCodec.Result.Rejected ->
                return Result.Rejected(Reason.MANIFEST_REJECTED, "${decoded.reason}: ${decoded.detail}")
        }

        structuralCheck(manifest, entries)?.let { return it }

        return Result.Loaded(
            LoadedPlugin(
                manifest = manifest,
                sha256 = sha256,
                entries = entries,
            ),
        )
    }

    // ------------------------------------------------------------------ section <-> file match

    /**
     * The manifest's [PluginManifest.declares] and the archive's files must agree: every
     * declared section is present, every section-shaped file is declared, and nothing
     * outside the known section prefixes (bar the manifest and previews) is carried.
     */
    private fun structuralCheck(manifest: PluginManifest, entries: Map<String, ByteArray>): Result.Rejected? {
        val paths = entries.keys

        // Which sections does the archive actually carry?
        val present = mutableSetOf<PluginSection>()
        for (path in paths) {
            if (path == MANIFEST_ENTRY) continue
            if (isPreview(path)) continue
            if (isLooseAsset(path)) continue
            val section = sectionOf(path)
                ?: return Result.Rejected(Reason.DISALLOWED_FILE, path)
            present += section
        }

        // Declared but absent.
        (manifest.declares - present).firstOrNull()?.let {
            return Result.Rejected(Reason.MISSING_DECLARED_SECTION, it.id)
        }
        // Present but undeclared.
        (present - manifest.declares).firstOrNull()?.let {
            return Result.Rejected(Reason.UNDECLARED_SECTION, it.id)
        }
        return null
    }

    /** The section a path belongs to, by matching the section's entry prefix. */
    private fun sectionOf(path: String): PluginSection? =
        PluginSection.entries.firstOrNull { section ->
            val prefix = section.entryPrefix
            if (prefix.endsWith("/")) path.startsWith(prefix) else path == prefix
        }

    /** `previews/` holds screenshots for the install screen; it is not a section. */
    private fun isPreview(path: String): Boolean = path.startsWith("previews/")

    /**
     * `assets/` holds images referenced by theme/screens. Admission is by *prefix*, not by
     * extension, so the loader never has to guess what an entry means from its name — it
     * caps the bytes of whatever lands here ([MAX_ENTRY_BYTES], and the running total
     * against [MAX_TOTAL_UNCOMPRESSED_BYTES]) and does not decode it. Decoding is
     * Android's `BitmapFactory`, applied later with its own dimension caps, and capping
     * bytes here is what stops a bomb before it ever reaches a decoder.
     */
    private fun isLooseAsset(path: String): Boolean = path.startsWith("assets/")

    // -------------------------------------------------------------------------------- path safety

    /**
     * Normalise and vet an entry name, returning the safe relative path or null to reject.
     *
     * This is the zip-slip gate. An entry name is attacker-controlled text; a name like
     * `../../../../data/data/com.other/x` or `/etc/passwd` or `foo/../../bar` must never
     * resolve outside the plugin's own tree. We reject rather than sanitise: an absolute
     * path, a backslash (Windows separator, and a `..\` dodge), any `.` or `..` segment, a
     * leading slash, an empty or over-long name — each is refused outright, so there is no
     * cleverness to get wrong. Forward slash is the only separator admitted.
     */
    internal fun safePathOrNull(rawName: String?): String? {
        if (rawName.isNullOrEmpty()) return null
        if (rawName.length > MAX_PATH_LENGTH) return null
        // No Windows separators or drive-letter / UNC shapes; one separator only.
        if (rawName.contains('\\')) return null
        // No absolute paths.
        if (rawName.startsWith("/")) return null
        // No NUL or control smuggling.
        if (rawName.any { it.isISOControl() }) return null

        val segments = rawName.split('/')
        for (seg in segments) {
            if (seg.isEmpty()) return null          // leading/trailing/double slash
            if (seg == "." || seg == "..") return null
            // A segment of only dots/spaces is a classic traversal dodge on some filesystems.
            if (seg.isBlank()) return null
        }
        return rawName
    }

    // -------------------------------------------------------------------------------- byte helpers

    /** The ZIP magic: "PK\u0003\u0004" (local file header) or "PK\u0005\u0006" (empty EOCD). */
    private fun looksLikeZip(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        if (bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) return false
        val c = bytes[2].toInt(); val d = bytes[3].toInt()
        return (c == 3 && d == 4) || (c == 5 && d == 6) || (c == 7 && d == 8)
    }

    private fun readEntryCapped(zip: ZipInputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(READ_CHUNK_BYTES)
        var total = 0
        while (true) {
            val read = zip.read(chunk)
            if (read < 0) break
            total += read
            if (total > MAX_ENTRY_BYTES) return null
            out.write(chunk, 0, read)
        }
        return out.toByteArray()
    }

    private fun readCapped(input: InputStream, maxBytes: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(READ_CHUNK_BYTES)
        var total = 0
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            total += read
            if (total > maxBytes) return null
            out.write(chunk, 0, read)
        }
        return out.toByteArray()
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()

    /** Guarded JSON object parse used by callers that peek at an entry's shape. */
    internal fun parseJsonOrNull(raw: String?): JSONObject? = try {
        if (raw.isNullOrBlank()) null else JSONObject(raw)
    } catch (_: JSONException) {
        null
    }
}

/**
 * A `.gcplugin` that has fully passed the loader: a trusted [manifest], the [sha256] pinned
 * over the exact package bytes (what the marketplace index and the install screen verify
 * against), and the validated file [entries] keyed by normalised path. Holding the bytes in
 * memory rather than on disk means a plugin that fails a later validation pass leaves nothing
 * behind.
 */
data class LoadedPlugin(
    val manifest: PluginManifest,
    val sha256: String,
    val entries: Map<String, ByteArray>,
) {
    /** The bytes of a section file, or null if the package does not carry it. */
    fun entry(path: String): ByteArray? = entries[path]

    /** All entry paths under a section's prefix, in archive order. */
    fun pathsUnder(section: PluginSection): List<String> {
        val prefix = section.entryPrefix
        return entries.keys.filter {
            if (prefix.endsWith("/")) it.startsWith(prefix) else it == prefix
        }
    }
}
