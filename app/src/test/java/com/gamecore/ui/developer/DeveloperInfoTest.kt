package com.gamecore.ui.developer

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three addresses the developer screen offers.
 *
 * An address is the one string in this app that fails quietly. A mistyped handle compiles, renders and
 * opens a browser; what comes back is a stranger's page or a "not found", on someone else's device, long
 * after the release. So the assertions here are the ones a reader cannot make by looking at the literal:
 * that each is an absolute URL Android can resolve at all, that none of them has slipped to `http`, that
 * each is on the host its row claims, and that the handle printed above the links is the one the GitHub
 * address actually goes to.
 *
 * What no test can check is that the accounts are the right accounts. That is why the host and the handle
 * are asserted separately from each other: those are the two halves someone editing one link would change.
 */
class DeveloperInfoTest {

    @Test
    fun `every link is an absolute https address with something after the host`() {
        for (url in DeveloperInfo.all) {
            val parsed = URI(url)
            assertEquals("scheme of $url", "https", parsed.scheme)
            assertTrue("$url names no host", parsed.host.orEmpty().isNotBlank())
            assertTrue("$url is a bare host with no profile on it", parsed.path.orEmpty().length > 1)
        }
    }

    @Test
    fun `each link is on the platform its row is named for`() {
        assertEquals("github.com", URI(DeveloperInfo.GITHUB).host)
        assertEquals("discord.gg", URI(DeveloperInfo.DISCORD).host)
        assertEquals("linkedin.com", URI(DeveloperInfo.LINKEDIN).host)
    }

    @Test
    fun `the screen offers three distinct links`() {
        assertEquals(3, DeveloperInfo.all.size)
        assertEquals(DeveloperInfo.all.size, DeveloperInfo.all.toSet().size)
    }

    /** The card prints the handle and the row opens the URL; a rename that touched one would show here. */
    @Test
    fun `the github link goes to the handle the card prints`() {
        assertTrue(
            "${DeveloperInfo.GITHUB} is not ${DeveloperInfo.HANDLE}'s",
            DeveloperInfo.GITHUB.endsWith("/${DeveloperInfo.HANDLE}"),
        )
    }
}
