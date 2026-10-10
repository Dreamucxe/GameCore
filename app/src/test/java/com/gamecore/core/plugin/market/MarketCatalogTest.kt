package com.gamecore.core.plugin.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Part J gate: marketplace index integrity (hash pinning) and catalog filtering. */
class MarketCatalogTest {

    private fun entry(
        id: String,
        verified: Boolean = false,
        downloads: Long = 0,
        published: Long = 0,
        category: MarketEntry.Category = MarketEntry.Category.OTHER,
        api: Int = 1,
        minHost: Int = 1,
        sha: String = "a".repeat(64),
    ) = MarketEntry(
        id = id, name = id, developer = "Dev", summary = "s", category = category,
        versionName = "1.0", versionCode = 1, api = api, minHostVersion = minHost,
        sizeBytes = 100, sha256 = sha, downloadUrl = "https://x/$id.gcplugin",
        previewImageRefs = emptyList(), verified = verified, downloadCount = downloads,
        publishedAtEpochMs = published,
    )

    // ---------------------------------------------------------------- integrity

    @Test
    fun `a matching hash verifies`() {
        val e = entry("p", sha = "b".repeat(64))
        assertTrue(MarketIntegrity.matches(e, "B".repeat(64))) // case-insensitive
    }

    @Test
    fun `a mismatched or malformed hash never verifies`() {
        val e = entry("p", sha = "b".repeat(64))
        assertFalse(MarketIntegrity.matches(e, "c".repeat(64)))
        assertFalse(MarketIntegrity.matches(e, "short"))
        assertFalse(MarketIntegrity.matches(entry("p", sha = "notahash"), "b".repeat(64)))
    }

    // ---------------------------------------------------------------- catalog

    @Test
    fun `featured is verified-only, sorted by downloads`() {
        val cat = MarketCatalog(listOf(
            entry("a", verified = true, downloads = 10),
            entry("b", verified = false, downloads = 100),
            entry("c", verified = true, downloads = 50),
        ))
        val f = cat.featured(27).map { it.id }
        assertEquals(listOf("c", "a"), f)
    }

    @Test
    fun `an unsupported plugin is excluded from installable lists`() {
        val cat = MarketCatalog(listOf(
            entry("ok", downloads = 1),
            entry("too_new_host", minHost = 999),
        ))
        assertEquals(listOf("ok"), cat.popular(27).map { it.id })
    }

    @Test
    fun `search matches name developer and summary case-insensitively`() {
        val cat = MarketCatalog(listOf(entry("Cyberpunk"), entry("Minimal")))
        assertEquals(listOf("Cyberpunk"), cat.search("cyber", 27).map { it.id })
        assertTrue(cat.search("", 27).isEmpty())
    }

    @Test
    fun `newest sorts by publish time`() {
        val cat = MarketCatalog(listOf(
            entry("old", published = 100),
            entry("new", published = 999),
        ))
        assertEquals(listOf("new", "old"), cat.newest(27).map { it.id })
    }
}
