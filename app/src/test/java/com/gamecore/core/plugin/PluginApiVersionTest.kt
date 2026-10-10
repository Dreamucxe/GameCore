package com.gamecore.core.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the compatibility matrix at its exact edges, because an off-by-one here is invisible until it
 * is a support thread.
 *
 * [PluginApi.compatibility] is three comparisons, and each of them is inclusive on one side. Flip any
 * one of them and the failure is silent in the direction that matters: a plugin built against this
 * build's own API, or declaring this build's own `versionCode`, is refused on the very version that
 * supports it — and the message it is refused with ("needs a newer version of GameCore") sends the
 * user to an update that does not exist. [PluginHostVersionTest] pins the constants themselves; this
 * pins the arithmetic around them, in terms of the constants, so the boundaries stay checked after
 * the day `CURRENT` or `MINIMUM_SUPPORTED` finally moves.
 *
 * The last two tests are about what the UI shows rather than what the host decides. [Compatibility]
 * is the only place a refusal's wording lives (§24 honest UI: the screen reads the verdict's own
 * reason instead of composing its own), so a blank `reason` would be a plugin row that says nothing
 * at all, and a second `isRunnable` verdict would let an unsupported plugin load.
 */
class PluginApiVersionTest {

    /** Every (api, minHostVersion) pair worth checking, named by the boundary it sits on. */
    private val boundaries: List<Triple<String, Pair<Int, Int>, Compatibility>> = listOf(
        Triple("api == CURRENT", PluginApi.CURRENT to 1, Compatibility.Supported),
        Triple("api == MINIMUM_SUPPORTED", PluginApi.MINIMUM_SUPPORTED to 1, Compatibility.Supported),
        Triple("api == CURRENT + 1", (PluginApi.CURRENT + 1) to 1, Compatibility.TooNew),
        Triple("api == MINIMUM_SUPPORTED - 1", (PluginApi.MINIMUM_SUPPORTED - 1) to 1, Compatibility.TooOld),
        Triple(
            "minHostVersion == HOST_VERSION_CODE",
            PluginApi.CURRENT to PluginApi.HOST_VERSION_CODE,
            Compatibility.Supported,
        ),
        Triple(
            "minHostVersion == HOST_VERSION_CODE + 1",
            PluginApi.CURRENT to (PluginApi.HOST_VERSION_CODE + 1),
            Compatibility.HostTooOld,
        ),
    )

    @Test
    fun `the api window is inclusive at both ends and exclusive one step past either`() {
        // CURRENT and MINIMUM_SUPPORTED are the same number today, so two of these rows coincide.
        // They are written in terms of the constants anyway: the day the floor moves, the rows
        // separate on their own and the boundary stays checked without anyone editing this test.
        assertEquals(Compatibility.Supported, PluginApi.compatibility(PluginApi.CURRENT, 1))
        assertEquals(Compatibility.Supported, PluginApi.compatibility(PluginApi.MINIMUM_SUPPORTED, 1))
        assertEquals(Compatibility.TooNew, PluginApi.compatibility(PluginApi.CURRENT + 1, 1))
        assertEquals(Compatibility.TooOld, PluginApi.compatibility(PluginApi.MINIMUM_SUPPORTED - 1, 1))
    }

    @Test
    fun `the host version boundary includes this build and excludes the next`() {
        // A plugin naming the *lowest* build it supports must run on exactly that build. This is the
        // comparison that decides whether every plugin the SDK produces for 3.8 installs on 3.8.
        assertEquals(
            Compatibility.Supported,
            PluginApi.compatibility(PluginApi.CURRENT, PluginApi.HOST_VERSION_CODE),
        )
        assertEquals(
            Compatibility.HostTooOld,
            PluginApi.compatibility(PluginApi.CURRENT, PluginApi.HOST_VERSION_CODE + 1),
        )
    }

    @Test
    fun `isSupported says exactly what compatibility says at every boundary`() {
        // isSupported is the convenience every call site actually uses. If it ever drifted from the
        // verdict — by caching, by widening, by answering a different question — the install screen
        // and the loader would disagree about the same plugin.
        boundaries.forEach { (label, pair, expected) ->
            val (api, minHost) = pair
            assertEquals(label, expected, PluginApi.compatibility(api, minHost))
            assertEquals(label, expected.isRunnable, PluginApi.isSupported(api, minHost))
        }
    }

    @Test
    fun `Supported is the only runnable verdict`() {
        // Written as a sweep over the enum rather than four assertions, so a fifth verdict added
        // without deciding whether it may run fails here instead of defaulting to "it may".
        Compatibility.entries.forEach { verdict ->
            assertEquals(
                "${verdict.name}.isRunnable must be true only for Supported",
                verdict == Compatibility.Supported,
                verdict.isRunnable,
            )
        }
        assertTrue(Compatibility.Supported.isRunnable)
        assertFalse(Compatibility.entries.filter { it != Compatibility.Supported }.any { it.isRunnable })
    }

    @Test
    fun `every verdict carries a reason the UI can show`() {
        // The plugin row and the install screen render this string and compose nothing of their own,
        // so a blank reason is a refusal with no explanation on screen. TooNew and HostTooOld share
        // their wording on purpose — "update GameCore" is the action for both — so this checks that
        // each reason exists and reads as a sentence, not that the four are distinct.
        Compatibility.entries.forEach { verdict ->
            assertTrue("${verdict.name} has a blank reason", verdict.reason.isNotBlank())
            assertEquals("${verdict.name}'s reason must be trimmed", verdict.reason.trim(), verdict.reason)
            assertTrue("${verdict.name}'s reason must read as a sentence", verdict.reason.endsWith("."))
        }
    }
}
