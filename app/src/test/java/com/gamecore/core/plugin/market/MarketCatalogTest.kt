package com.gamecore.core.plugin.market

import com.gamecore.core.plugin.InstalledPlugin
import com.gamecore.core.plugin.PluginGrant
import com.gamecore.core.plugin.PluginManifest
import com.gamecore.core.plugin.PluginOrigin
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
        versionCode: Int = 1,
    ) = MarketEntry(
        id = id, name = id, developer = "Dev", summary = "s", category = category,
        versionName = "1.0", versionCode = versionCode, api = api, minHostVersion = minHost,
        sizeBytes = 100, sha256 = sha, downloadUrl = "https://x/$id.gcplugin",
        previewImageRefs = emptyList(), verified = verified, downloadCount = downloads,
        publishedAtEpochMs = published,
    )

    private fun installed(
        id: String,
        version: Int,
        origin: PluginOrigin,
        sha: String = "b".repeat(64),
    ) = InstalledPlugin(
        manifest = PluginManifest(
            id = id,
            name = id,
            version = version,
            versionName = "1.0",
            api = 1,
            minHostVersion = 1,
            developer = "Dev",
            description = "",
            permissions = emptyList(),
            declares = emptySet(),
            changelog = "",
        ),
        sha256 = sha,
        origin = origin,
        enabled = true,
        grant = PluginGrant.none(id),
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

    // ---------------------------------------------------------------- update states

    @Test
    fun `a newer index entry updates a bundled plugin too`() {
        val status = MarketUpdates.statusOf(
            entry("p", versionCode = 2),
            installed("p", version = 1, origin = PluginOrigin.BUNDLED),
            hostVersionCode = 27,
        )

        assertEquals(MarketEntryState.UPDATE_AVAILABLE, status.state)
        assertTrue(status.state.canInstall)
        assertEquals("Update", status.state.actionLabel)
    }

    @Test
    fun `a bundled plugin whose shipped build is current stays bundled`() {
        val same = MarketUpdates.statusOf(
            entry("p", versionCode = 2),
            installed("p", version = 2, origin = PluginOrigin.BUNDLED),
            hostVersionCode = 27,
        )
        val ahead = MarketUpdates.statusOf(
            entry("p", versionCode = 2),
            installed("p", version = 3, origin = PluginOrigin.BUNDLED),
            hostVersionCode = 27,
        )

        assertEquals(MarketEntryState.BUNDLED, same.state)
        assertEquals(MarketEntryState.BUNDLED, ahead.state)
        assertFalse(same.state.canInstall)
    }

    @Test
    fun `an installed package that matches the index is up to date`() {
        val byHash = MarketUpdates.statusOf(
            entry("p", versionCode = 2, sha = "c".repeat(64)),
            installed("p", version = 1, origin = PluginOrigin.SIDELOADED, sha = "C".repeat(64)),
            hostVersionCode = 27,
        )
        val byVersion = MarketUpdates.statusOf(
            entry("p", versionCode = 2),
            installed("p", version = 2, origin = PluginOrigin.SIDELOADED),
            hostVersionCode = 27,
        )

        assertEquals(MarketEntryState.UP_TO_DATE, byHash.state)
        assertEquals(MarketEntryState.UP_TO_DATE, byVersion.state)
    }

    @Test
    fun `an entry the host cannot run is unsupported even when installed`() {
        val status = MarketUpdates.statusOf(
            entry("p", versionCode = 2, minHost = 999),
            installed("p", version = 1, origin = PluginOrigin.BUNDLED),
            hostVersionCode = 27,
        )

        assertEquals(MarketEntryState.INCOMPATIBLE, status.state)
        assertFalse(status.state.canInstall)
    }
}
