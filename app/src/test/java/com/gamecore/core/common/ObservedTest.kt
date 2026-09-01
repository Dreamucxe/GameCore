package com.gamecore.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The type the whole app's honesty rests on.
 *
 * Worth testing exhaustively rather than lightly, because every one of these assertions is a
 * sentence the user reads. A `map` that quietly promoted a [Observed.Restricted] to a value, or an
 * [unavailabilityText] that returned "Not available on this device." for a permission the user could
 * grant in one tap, would be a lie the compiler cannot catch.
 */
class ObservedTest {

    @Test
    fun `a real reading carries its value, its source and its precision`() {
        val reading = Observed.of(42, DataSource.PROC_FS, Precision.SAMPLED)
        assertEquals(42, reading.valueOrNull)
        assertTrue(reading.isAvailable)
        assertNull(reading.unavailabilityText())
        assertNull(reading.shortUnavailabilityText())
        assertEquals(DataSource.PROC_FS, (reading as Observed.Value).source)
        assertEquals(Precision.SAMPLED, reading.precision)
    }

    @Test
    fun `an absent reading holds nothing a screen could render`() {
        val restricted: Observed<Int> = Observed.needsElevation("dumpsys only")
        val failed: Observed<Int> = Observed.Failed("io error")
        assertNull(restricted.valueOrNull)
        assertNull(failed.valueOrNull)
        assertFalse(restricted.isAvailable)
        assertFalse(failed.isAvailable)
    }

    @Test
    fun `each factory records the reason and whether an access level would lift it`() {
        assertEquals(
            RestrictionReason.PLATFORM_RESTRICTED,
            (Observed.platform("selinux") as Observed.Restricted).reason,
        )
        assertEquals(
            AccessLevel.SHIZUKU,
            (Observed.needsElevation("shell") as Observed.Restricted).unlockedBy,
        )
        // Nothing the user can grant reveals hardware that is not there, so no level is named.
        assertNull((Observed.notPresent("no sensor") as Observed.Restricted).unlockedBy)
        assertEquals(
            RestrictionReason.PERMISSION_REQUIRED,
            (Observed.needsPermission("usage access") as Observed.Restricted).reason,
        )
        assertEquals(
            RestrictionReason.NOT_SUPPORTED_ON_API_LEVEL,
            (Observed.needsNewerApi("api 31") as Observed.Restricted).reason,
        )
        assertEquals(
            RestrictionReason.SAMPLING_DISABLED,
            (Observed.samplingDisabled("user turned it off") as Observed.Restricted).reason,
        )
    }

    @Test
    fun `awaiting a second sample is the one absence that is not a limitation`() {
        val awaiting: Observed<Float> = Observed.awaitingSample()
        assertTrue(awaiting.isAwaitingSample)
        assertEquals("Measuring…", awaiting.unavailabilityText())
        assertEquals("…", awaiting.shortUnavailabilityText())
        assertFalse(Observed.notPresent("no sensor").isAwaitingSample)
        assertFalse(Observed.of(1, DataSource.PROC_FS).isAwaitingSample)
    }

    @Test
    fun `map transforms a value and keeps where it came from`() {
        val mapped = Observed.of(1500, DataSource.SYS_FS, Precision.EXACT).map { it / 1000f }
        assertEquals(1.5f, mapped.valueOrNull!!, 0.0001f)
        assertEquals(DataSource.SYS_FS, (mapped as Observed.Value).source)
    }

    @Test
    fun `map and flatMap pass an absence through untouched`() {
        val restricted: Observed<Int> = Observed.needsElevation("shell")
        assertSame(restricted, restricted.map { it * 2 })
        assertSame(restricted, restricted.flatMap { Observed.of(it, DataSource.PROC_FS) })

        val failed: Observed<Int> = Observed.Failed("io error", cause = "java.io.IOException")
        assertSame(failed, failed.map { it * 2 })
        assertSame(failed, failed.flatMap { Observed.of(it, DataSource.PROC_FS) })
    }

    @Test
    fun `flatMap adopts the failure of the second step`() {
        val chained = Observed.of(60f, DataSource.DISPLAY_MANAGER)
            .flatMap { Observed.notPresent("no mode list") }
        assertFalse(chained.isAvailable)
        assertEquals("Not available on this device.", chained.unavailabilityText())
    }

    @Test
    fun `every absence has one exact wording, and the eight reasons do not share one`() {
        assertEquals(
            "Android does not expose this to ordinary apps on this device.",
            Observed.platform("x").unavailabilityText(),
        )
        assertEquals(
            "A permission is needed before this can be read.",
            Observed.needsPermission("x").unavailabilityText(),
        )
        assertEquals("Not available on this device.", Observed.notPresent("x").unavailabilityText())
        assertEquals("Requires Shizuku.", Observed.needsElevation("x").unavailabilityText())
        assertEquals(
            "This Android version does not provide this.",
            Observed.needsNewerApi("x").unavailabilityText(),
        )
        assertEquals(
            "Switched off in GameCore's settings.",
            Observed.samplingDisabled("x").unavailabilityText(),
        )
        assertEquals("Could not be read on this device.", Observed.Failed("x").unavailabilityText())
    }

    @Test
    fun `the short form fits a pill and still distinguishes the actionable cases`() {
        assertEquals("shizuku", Observed.needsElevation("x").shortUnavailabilityText())
        assertEquals("perm", Observed.needsPermission("x").shortUnavailabilityText())
        assertEquals("off", Observed.samplingDisabled("x").shortUnavailabilityText())
        assertEquals("error", Observed.Failed("x").shortUnavailabilityText())
        // Everything the user cannot act on collapses to one word.
        assertEquals("n/a", Observed.notPresent("x").shortUnavailabilityText())
        assertEquals("n/a", Observed.platform("x").shortUnavailabilityText())
        assertEquals("n/a", Observed.needsNewerApi("x").shortUnavailabilityText())
    }

    @Test
    fun `catching separates the sandbox refusing from a genuine fault`() {
        val refused = Observed.catching<Int>(DataSource.PROC_FS) { throw SecurityException("denied") }
        assertEquals(
            Observed.Restricted(RestrictionReason.PLATFORM_RESTRICTED, AccessLevel.SHIZUKU, "denied"),
            refused,
        )

        val broken = Observed.catching<Int>(DataSource.PROC_FS) { error("boom") }
        assertTrue(broken is Observed.Failed)
        assertEquals("boom", (broken as Observed.Failed).detail)
    }

    @Test
    fun `catching treats a null read as a failure rather than as a value`() {
        val empty = Observed.catching<String?>(DataSource.PROC_FS) { null }
        assertEquals(Observed.Failed("Read returned no data"), empty)
    }

    @Test
    fun `access levels are ordered so a higher one satisfies a lower requirement`() {
        assertTrue(AccessLevel.SHIZUKU satisfies AccessLevel.NORMAL)
        assertTrue(AccessLevel.SHIZUKU satisfies AccessLevel.SHIZUKU)
        assertTrue(AccessLevel.ROOT satisfies AccessLevel.SHIZUKU)
        assertFalse(AccessLevel.NORMAL satisfies AccessLevel.SHIZUKU)
        assertFalse(AccessLevel.SHIZUKU satisfies AccessLevel.ROOT)
    }

    @Test
    fun `a shell-sourced reading is not first party`() {
        assertTrue(DataSource.PROC_FS.isFirstParty)
        assertTrue(DataSource.DISPLAY_MANAGER.isFirstParty)
        assertFalse(DataSource.SHELL_SHIZUKU.isFirstParty)
        assertFalse(DataSource.DUMPSYS_SHIZUKU.isFirstParty)
    }
}
