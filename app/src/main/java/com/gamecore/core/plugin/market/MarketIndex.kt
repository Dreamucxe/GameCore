package com.gamecore.core.plugin.market

import com.gamecore.core.common.TextSanitizer
import com.gamecore.core.plugin.InstalledPlugin
import com.gamecore.core.plugin.PluginApi
import com.gamecore.core.plugin.PluginOrigin
import org.json.JSONObject

/**
 * The curated index after decoding: usable entries, plus how many lines were refused.
 *
 * A single malformed entry is skipped rather than blanking the catalog, but the count is kept so the
 * screen can say "some entries could not be read" instead of quietly showing a shorter list. The index
 * is remote input even when it is curated, so every field is validated here before it reaches a
 * download or a row.
 */
data class MarketIndexDocument(
    val entries: List<MarketEntry>,
    val skippedEntries: Int = 0,
)

/**
 * Decodes the one JSON document GameCore trusts to discover plugins.
 *
 * The index is authority for *what exists* and *what bytes are expected*, never for what the bytes are
 * allowed to do: the package carries its own manifest, and the loader re-parses it. The hash is the
 * transport-independent check, so a compromised download server cannot substitute a package even if it
 * serves a different file.
 */
object MarketIndexCodec {

    /** Refuse an absurd value before asking org.json to parse it. The real file is tens of KB. */
    const val MAX_INDEX_BYTES = 1_000_000

    private const val KEY_ENTRIES = "entries"
    private const val KEY_ID = "id"
    private const val KEY_NAME = "name"
    private const val KEY_DEVELOPER = "developer"
    private const val KEY_SUMMARY = "summary"
    private const val KEY_CATEGORY = "category"
    private const val KEY_VERSION_NAME = "versionName"
    private const val KEY_VERSION_CODE = "versionCode"
    private const val KEY_API = "api"
    private const val KEY_MIN_HOST = "minHostVersion"
    private const val KEY_SIZE = "sizeBytes"
    private const val KEY_SHA256 = "sha256"
    private const val KEY_URL = "downloadUrl"
    private const val KEY_PREVIEWS = "previewImageRefs"
    private const val KEY_VERIFIED = "verified"
    private const val KEY_DOWNLOADS = "downloadCount"
    private const val KEY_PUBLISHED = "publishedAtEpochMs"

    private const val MAX_FIELD = 512
    private const val MAX_PREVIEWS = 6
    private const val MAX_PREVIEW_LENGTH = 300

    /**
     * Only the repository GameCore curates is a valid package source.
     *
     * The hash already makes a substituted package inert, but constraining the host means a typo or a
     * hand-edited index cannot turn a tap into a request to an arbitrary server, and the UI can say
     * exactly where a package comes from.
     */
    const val PACKAGE_HOST = "raw.githubusercontent.com"

    private val HEX64 = Regex("^[0-9a-f]{64}$")

    fun decode(raw: String): MarketIndexDocument? {
        if (raw.isBlank() || raw.length > MAX_INDEX_BYTES) return null
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val array = root.optJSONArray(KEY_ENTRIES) ?: return null

        val seen = HashSet<String>()
        val entries = ArrayList<MarketEntry>()
        var skipped = 0
        for (i in 0 until array.length()) {
            val value = array.optJSONObject(i)
            val entry = value?.let(::readEntry)
            if (entry == null || !seen.add(entry.id)) {
                skipped++
                continue
            }
            entries += entry
        }
        return MarketIndexDocument(entries = entries, skippedEntries = skipped)
    }

    private fun readEntry(value: JSONObject): MarketEntry? {
        val id = TextSanitizer.validatePackageName(value.optString(KEY_ID)) ?: return null
        val name = TextSanitizer.sanitizeName(value.optString(KEY_NAME), 64).trim()
        if (name.isEmpty()) return null
        val sha = value.optString(KEY_SHA256).trim().lowercase()
        if (!HEX64.matches(sha)) return null
        val url = value.optString(KEY_URL).trim()
        if (!isAllowedPackageUrl(url)) return null

        val versionCode = value.optInt(KEY_VERSION_CODE, 0)
        val api = value.optInt(KEY_API, 0)
        val minHost = value.optInt(KEY_MIN_HOST, 0)
        val size = value.optLong(KEY_SIZE, 0L)
        if (versionCode <= 0 || api <= 0 || minHost < 0 || size <= 0L) return null

        val previews = ArrayList<String>()
        val previewArray = value.optJSONArray(KEY_PREVIEWS)
        if (previewArray != null) {
            for (j in 0 until previewArray.length()) {
                if (previews.size >= MAX_PREVIEWS) break
                val ref = TextSanitizer.sanitizeName(previewArray.optString(j), MAX_PREVIEW_LENGTH).trim()
                if (ref.isNotEmpty()) previews += ref
            }
        }

        return MarketEntry(
            id = id,
            name = name,
            developer = TextSanitizer.sanitizeName(value.optString(KEY_DEVELOPER), 64).trim()
                .ifEmpty { "Unattributed" },
            summary = TextSanitizer.sanitizeName(value.optString(KEY_SUMMARY), 320).trim(),
            category = MarketEntry.Category.fromId(value.optString(KEY_CATEGORY)),
            versionName = TextSanitizer.sanitizeName(value.optString(KEY_VERSION_NAME), 32).trim(),
            versionCode = versionCode,
            api = api,
            minHostVersion = minHost,
            sizeBytes = size,
            sha256 = sha,
            downloadUrl = url,
            previewImageRefs = previews,
            verified = value.optBoolean(KEY_VERIFIED, false),
            downloadCount = value.optLong(KEY_DOWNLOADS, 0L).coerceAtLeast(0L),
            publishedAtEpochMs = value.optLong(KEY_PUBLISHED, 0L).coerceAtLeast(0L),
        )
    }

    /** HTTPS, the curated host, no credentials, no fragments, no whitespace. */
    fun isAllowedPackageUrl(raw: String): Boolean {
        if (raw.isEmpty() || raw.length > MAX_FIELD || raw.any { it.isWhitespace() }) return false
        return runCatching {
            val uri = java.net.URI(raw)
            uri.scheme == "https" &&
                uri.host == PACKAGE_HOST &&
                uri.userInfo == null &&
                uri.fragment == null &&
                uri.path.isNullOrBlank().not()
        }.getOrDefault(false)
    }
}

/**
 * What the discover screen should offer for one index entry, given what is installed.
 *
 * The comparison is by version code and digest, in that order: an installed package whose bytes match
 * the index is up to date even if it claims a different version, because the bytes are the fact the
 * hash pins. A bundled plugin can be updated too: the curated package is written to the plugin
 * directory and takes precedence over the asset in the APK, so "Bundled" means the shipped copy is
 * current rather than that no update will ever be offered.
 */
enum class MarketEntryState {
    NOT_INSTALLED,
    UPDATE_AVAILABLE,
    UP_TO_DATE,
    BUNDLED,
    INCOMPATIBLE,
    ;

    val actionLabel: String?
        get() = when (this) {
            NOT_INSTALLED -> "Install"
            UPDATE_AVAILABLE -> "Update"
            else -> null
        }

    val canInstall: Boolean get() = this == NOT_INSTALLED || this == UPDATE_AVAILABLE
}

data class MarketEntryStatus(
    val entry: MarketEntry,
    val installed: InstalledPlugin?,
    val state: MarketEntryState,
) {
    val installedVersion: String? get() = installed?.manifest?.versionName
}

object MarketUpdates {

    fun statusOf(
        entry: MarketEntry,
        installed: InstalledPlugin?,
        hostVersionCode: Int,
    ): MarketEntryStatus {
        val compatible = entry.minHostVersion <= hostVersionCode &&
            PluginApi.isSupported(entry.api, entry.minHostVersion)
        // A match is either the same bytes or a version at least as new. The hash wins because it is
        // the fact the index pins; the version is the user-visible fact when the packaging differs.
        val upToDate = installed != null && (
            installed.sha256.equals(entry.sha256, ignoreCase = true) ||
                installed.manifest.version >= entry.versionCode
            )
        val state = when {
            !compatible -> MarketEntryState.INCOMPATIBLE
            installed == null -> MarketEntryState.NOT_INSTALLED
            // The shipped copy is current. Saying "Bundled" is more useful than "Up to date" because
            // it explains why there is no package on disk and no update to fetch.
            installed?.origin == PluginOrigin.BUNDLED && upToDate -> MarketEntryState.BUNDLED
            upToDate -> MarketEntryState.UP_TO_DATE
            else -> MarketEntryState.UPDATE_AVAILABLE
        }
        return MarketEntryStatus(entry = entry, installed = installed, state = state)
    }
}
