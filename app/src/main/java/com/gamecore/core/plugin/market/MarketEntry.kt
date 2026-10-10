package com.gamecore.core.plugin.market

/**
 * One entry in the curated marketplace index.
 *
 * The marketplace has no custom backend: it is a single curated JSON file in a GitHub repo,
 * fetched over HTTPS with certificate pinning, listing the plugins GameCore has reviewed.
 * Every entry therefore has to carry enough to let the client verify what it downloads
 * *without* trusting the download server — above all [sha256], the hash of the exact
 * `.gcplugin` bytes. The client fetches the package from [downloadUrl], hashes the bytes, and
 * installs only if the hash matches this entry; a man-in-the-middle who somehow served
 * different bytes past the pinning would still fail the hash. The index is the authority, the
 * download is just transport.
 *
 * Counts ([downloadCount]) come from the index and are shown as-is. Ratings and reviews need
 * a backend GameCore does not have, so this model has no field for them — the UI shows "real
 * reviews coming later" rather than inventing numbers. [verified] is GameCore's own review
 * flag, set in the curated index, not a value a plugin can assert about itself.
 */
data class MarketEntry(
    val id: String,
    val name: String,
    val developer: String,
    val summary: String,
    val category: Category,
    val versionName: String,
    val versionCode: Int,
    val api: Int,
    val minHostVersion: Int,
    val sizeBytes: Long,
    val sha256: String,
    val downloadUrl: String,
    val previewImageRefs: List<String>,
    val verified: Boolean,
    val downloadCount: Long,
    val publishedAtEpochMs: Long,
) {
    enum class Category(val id: String, val label: String) {
        THEME("theme", "Themes"),
        PERFORMANCE("performance", "Performance"),
        PILL("pill", "Game Pill"),
        AIMLAB("aimlab", "Aim Lab"),
        AUTOMATION("automation", "Automations"),
        STATS("stats", "Stats"),
        OTHER("other", "Other");

        companion object {
            private val byId = entries.associateBy { it.id }
            fun fromId(id: String): Category = byId[id.trim().lowercase()] ?: OTHER
        }
    }
}

/** Verifies downloaded bytes against the hash the index pinned. Transport-independent. */
object MarketIntegrity {

    /**
     * Does [actualSha256] (lowercase hex of the downloaded bytes) match what [entry] pinned?
     * Case-insensitive on the hex, but length- and content-exact otherwise: a blank or
     * wrong-length pinned hash is treated as a mismatch, never as "skip the check".
     */
    fun matches(entry: MarketEntry, actualSha256: String): Boolean {
        val expected = entry.sha256.trim().lowercase()
        val actual = actualSha256.trim().lowercase()
        if (expected.length != 64 || actual.length != 64) return false
        if (!HEX64.matches(expected) || !HEX64.matches(actual)) return false
        // Constant-time-ish compare; the strings are short and public, but no early-out habit.
        var diff = 0
        for (i in 0 until 64) diff = diff or (expected[i].code xor actual[i].code)
        return diff == 0
    }

    private val HEX64 = Regex("^[0-9a-f]{64}$")
}

/** Filtering and sorting over an in-memory index. Pure; the fetch/pin/cache layer sits above. */
class MarketCatalog(private val entries: List<MarketEntry>) {

    fun featured(hostVersionCode: Int): List<MarketEntry> =
        entries.filter { it.verified && isInstallable(it, hostVersionCode) }
            .sortedByDescending { it.downloadCount }

    fun popular(hostVersionCode: Int): List<MarketEntry> =
        installable(hostVersionCode).sortedByDescending { it.downloadCount }

    fun newest(hostVersionCode: Int): List<MarketEntry> =
        installable(hostVersionCode).sortedByDescending { it.publishedAtEpochMs }

    fun byCategory(category: MarketEntry.Category, hostVersionCode: Int): List<MarketEntry> =
        installable(hostVersionCode).filter { it.category == category }

    fun byDeveloper(developer: String): List<MarketEntry> =
        entries.filter { it.developer.equals(developer, ignoreCase = true) }

    /** Case-insensitive substring search over name, developer and summary. */
    fun search(query: String, hostVersionCode: Int): List<MarketEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return installable(hostVersionCode).filter {
            it.name.lowercase().contains(q) ||
                it.developer.lowercase().contains(q) ||
                it.summary.lowercase().contains(q)
        }
    }

    private fun installable(hostVersionCode: Int) = entries.filter { isInstallable(it, hostVersionCode) }

    private fun isInstallable(entry: MarketEntry, hostVersionCode: Int): Boolean =
        com.gamecore.core.plugin.PluginApi.isSupported(entry.api, entry.minHostVersion) &&
            entry.minHostVersion <= hostVersionCode
}
