package com.gamecore.core.plugin.icon

/**
 * What a plugin asks for when it carries an `icon` section.
 *
 * The two halves of this type are the two things a plugin can honestly do about a launcher icon:
 *
 *  * [slot] picks one of the icons GameCore already ships. This is the only path that changes the
 *    icon the launcher shows, because that icon is a manifest resource and a package cannot supply
 *    one at install time.
 *  * [artworkPath] names an image in the package's `assets/`. That image cannot become the launcher
 *    icon, but it can become a pinned home-screen shortcut, which the system draws from a bitmap the
 *    host passes it. It is optional because most plugins will just pick a slot.
 *
 * [label] is the shortcut's caption, not a rename: the app's own launcher name stays GameCore.
 *
 * The type is produced only by [PluginIconCodec], so an instance is already validated: [slot]
 * resolved from the enum, [artworkPath] a safe relative path under `assets/`, [label] sanitised and
 * length-capped.
 */
data class PluginIconSpec(
    /** The icon to switch to. */
    val slot: AppIconSlot,

    /**
     * The package-relative path of the artwork, e.g. `assets/icon.png`, or null when the plugin
     * brought none. Never an absolute path, never a `..` segment — the codec refuses those rather
     * than cleaning them up, because a path that needed cleaning was not a path the author meant.
     */
    val artworkPath: String? = null,

    /** The caption for a pinned shortcut, or null to use the plugin's own name. */
    val label: String? = null,
) {
    /** Whether this section offers the shortcut path as well as the slot switch. */
    val hasArtwork: Boolean get() = artworkPath != null

    companion object {
        /** The longest artwork path accepted. A path long enough to matter is a path to reject. */
        const val MAX_ARTWORK_PATH = 128

        /** The longest shortcut caption. Kept short because launchers elide ruthlessly. */
        const val MAX_LABEL = 40
    }
}

/**
 * The bytes-level vetting of plugin-supplied artwork.
 *
 * The loader already caps how many bytes may sit under `assets/`, so this is not about archive
 * bombs. It is about the *decoder*: `BitmapFactory` is handed an image chosen by a plugin, and the
 * one thing that must never happen is that the host hands it something it cannot identify. So the
 * header is checked here, on the JVM, before any Android decoder sees it — a PNG or a WebP signature
 * and nothing else. A file that claims `.png` and starts with `GIF8` is refused by signature, not by
 * extension.
 *
 * Dimensions are checked separately, where a real decoder is available, because a 40000×40000 PNG is
 * valid PNG and must still not be loaded. [MAX_BYTES] here bounds the small end of that problem.
 */
object PluginIconArtwork {

    /** Enough for a 512dp icon at 4x with room to spare; a shortcut icon needs far less. */
    const val MAX_BYTES = 512 * 1024

    /** The largest edge the host will decode, in pixels. */
    const val MAX_EDGE_PX = 1024

    private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /**
     * Whether these bytes are a PNG or a WebP of an acceptable size.
     *
     * Deliberately does not decode. The answer may be true for an image whose *dimensions* are
     * absurd; that is [MAX_EDGE_PX]'s job, at the place where dims are known.
     */
    fun isAcceptable(bytes: ByteArray?): Boolean {
        if (bytes == null || bytes.isEmpty() || bytes.size > MAX_BYTES) return false
        return isPng(bytes) || isWebp(bytes)
    }

    private fun isPng(bytes: ByteArray): Boolean =
        bytes.size >= PNG.size && PNG.indices.all { bytes[it] == PNG[it] }

    /** `RIFF....WEBP`: a four-byte size, then the form type. */
    private fun isWebp(bytes: ByteArray): Boolean =
        bytes.size >= 12 &&
            bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray(Charsets.US_ASCII)) &&
            bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray(Charsets.US_ASCII))
}
