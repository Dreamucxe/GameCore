package com.gamecore.studio.core

import com.gamecore.studio.model.PluginDraft

/** One entry in a package, with the number of uncompressed bytes it carries. */
data class EntryInfo(val name: String, val bytes: Int)

/**
 * What the studio can say about a package without trusting or executing it.
 *
 * [manifestJson] is the required manifest and [themeJson] is the optional theme entry, or `""`
 * when it is absent or could not be read. [issues] contains only observable facts about the container
 * — an unreadable zip, a missing entry, an entry over the caps. It deliberately does **not**
 * claim to have run GameCore's manifest or theme codecs: those live in `:app`, and its
 * `PluginStudioPackageTest` is the test that runs composed bytes through them.
 */
data class PackageInspection(
    val entries: List<EntryInfo>,
    val totalBytes: Int,
    val sha256: String,
    val manifestJson: String,
    val themeJson: String,
    val issues: List<String>,
)

/**
 * Reads a package for the import screen: what is in it, how big it is, and what is wrong
 * with the container.
 *
 * This is the non-throwing side of [PluginImporter]. A file the importer would refuse still
 * gets an inspection, so the UI can name the entry that was too large or say that
 * `manifest.json` is missing instead of showing an empty error.
 */
object PackageInspector {

    private const val MANIFEST = "manifest.json"
    private const val THEME = "theme.json"

    fun inspect(bytes: ByteArray): PackageInspection {
        val read = try {
            PackageZip.read(bytes)
        } catch (e: Exception) {
            return PackageInspection(
                entries = emptyList(),
                totalBytes = 0,
                sha256 = PluginComposer.sha256(bytes),
                manifestJson = "",
                themeJson = "",
                issues = listOf("The package could not be read: ${e.message.orEmpty()}"),
            )
        }

        val byName = read.entries.associateBy { it.name }
        val manifest = byName[MANIFEST]
        val theme = byName[THEME]
        val issues = read.issues.toMutableList()
        if (manifest == null) issues += "The package has no manifest.json."

        return PackageInspection(
            entries = read.entries.map { EntryInfo(it.name, it.bytes.size) },
            totalBytes = read.totalBytes,
            sha256 = PluginComposer.sha256(bytes),
            manifestJson = manifest?.bytes?.toString(Charsets.UTF_8).orEmpty(),
            themeJson = theme?.bytes?.toString(Charsets.UTF_8).orEmpty(),
            issues = issues.distinct(),
        )
    }

    /**
     * The inspection of the package [draft] would produce.
     *
     * Composing first rather than describing the draft is deliberate: the screen shows the
     * bytes the user would actually export, including the deterministic zip entry order and
     * the exact JSON the composer writes.
     */
    fun inspect(draft: PluginDraft): PackageInspection =
        inspect(PluginComposer.packageBytes(draft))
}
