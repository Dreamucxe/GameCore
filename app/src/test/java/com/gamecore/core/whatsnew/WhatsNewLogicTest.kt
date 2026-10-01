package com.gamecore.core.whatsnew

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "What's New" decisions as pure logic: which releases count as unseen, and whether the one-time
 * card should appear for a given stored state. No Activity, no preferences — every case is an assertion
 * on plain values, with [shouldShowCard] exercised against the real [WhatsNewRegistry] since it reads
 * that registry as its source of truth.
 */
class WhatsNewLogicTest {

    private fun release(code: Int): ReleaseNote =
        ReleaseNote(versionCode = code, versionName = "$code", date = "", features = emptyList())

    /** The newest version the app actually ships, derived so these tests survive future registry appends. */
    private val current: Int = WhatsNewRegistry.releases.maxOf { it.versionCode }

    // ---------------------------------------------------------------- shouldShowCard

    @Test
    fun `a fresh install never shows the card`() {
        // Last-seen is still 0 on a brand-new install, but there is nothing to catch up on.
        assertFalse(shouldShowCard(lastSeenVersionCode = 0, currentVersionCode = current, isFirstInstall = true))
    }

    @Test
    fun `an existing user upgrading from a pre-WhatsNew build sees the card`() {
        // last-seen 0 but NOT a fresh install: they upgraded from a build that never wrote the key, so
        // the whole changelog is genuinely unseen.
        assertTrue(shouldShowCard(lastSeenVersionCode = 0, currentVersionCode = current, isFirstInstall = false))
    }

    @Test
    fun `a user already on the current version sees nothing`() {
        assertFalse(shouldShowCard(lastSeenVersionCode = current, currentVersionCode = current, isFirstInstall = false))
    }

    @Test
    fun `last-seen at the boundary equal to current shows nothing`() {
        // lastSeen == current is the exact boundary: nothing newer than what has been seen.
        assertFalse(shouldShowCard(lastSeenVersionCode = current, currentVersionCode = current, isFirstInstall = false))
    }

    @Test
    fun `last-seen ahead of current still shows nothing`() {
        assertFalse(shouldShowCard(lastSeenVersionCode = current + 1, currentVersionCode = current, isFirstInstall = false))
    }

    @Test
    fun `an upgrade with an unseen release shows the card`() {
        // Someone who last saw the second-newest release should see the newest one.
        val secondNewest = WhatsNewRegistry.releases.map { it.versionCode }.sortedDescending()[1]
        assertTrue(shouldShowCard(lastSeenVersionCode = secondNewest, currentVersionCode = current, isFirstInstall = false))
    }

    // ---------------------------------------------------------------- entriesSince

    @Test
    fun `entriesSince keeps only releases strictly newer than last-seen`() {
        val all = listOf(release(30), release(20), release(10))
        val unseen = entriesSince(all, lastSeenVersionCode = 15)
        assertEquals(listOf(30, 20), unseen.map { it.versionCode })
    }

    @Test
    fun `entriesSince excludes the boundary release itself`() {
        val all = listOf(release(30), release(20), release(10))
        // Having seen code 20, only 30 remains — 20 is not re-shown.
        assertEquals(listOf(30), entriesSince(all, lastSeenVersionCode = 20).map { it.versionCode })
    }

    @Test
    fun `entriesSince returns newest first even when the input is unordered`() {
        val all = listOf(release(10), release(30), release(20))
        assertEquals(listOf(30, 20, 10), entriesSince(all, lastSeenVersionCode = 0).map { it.versionCode })
    }

    @Test
    fun `entriesSince is empty when nothing is newer`() {
        val all = listOf(release(30), release(20), release(10))
        assertTrue(entriesSince(all, lastSeenVersionCode = 30).isEmpty())
    }
}
