package com.gamecore.core.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec §4's pinned-toggle rules, as arithmetic on lists (tests required by spec §10).
 *
 * Every rule is exercised against an injected `isAvailable` predicate so the device never enters the
 * picture: `all` is the everything-works phone, `none` the stripped-down one, and the named sets in
 * between stand in for "no Shizuku" or "no flash unit". That is the whole point of keeping [QuickSheetPins]
 * pure — these run on a plain JVM with no `Context`.
 */
class QuickSheetPinsTest {

    private val all: (QuickToggle) -> Boolean = { true }
    private val none: (QuickToggle) -> Boolean = { false }

    // ------------------------------------------------------------------ QuickToggle: parsing and defaults

    @Test
    fun `the defaults are the six the spec names, in its order`() {
        assertEquals(
            listOf(
                QuickToggle.SILENCE,
                QuickToggle.CROSSHAIR,
                QuickToggle.STATS,
                QuickToggle.HUD,
                QuickToggle.SCREENSHOT,
                QuickToggle.REFRESH,
            ),
            QuickToggle.DEFAULT_SET,
        )
        assertEquals(QuickSheetPins.MAX_PINS, QuickToggle.DEFAULT_SET.size)
    }

    @Test
    fun `a known id parses back to its toggle`() {
        assertEquals(QuickToggle.CROSSHAIR, QuickToggle.of("CROSSHAIR"))
        assertEquals(QuickToggle.SILENCE, QuickToggle.of("SILENCE"))
    }

    @Test
    fun `an unknown or absent id is dropped, not defaulted`() {
        assertNull(QuickToggle.of("GAMMA"))
        assertNull(QuickToggle.of("crosshair")) // ids are the stable enum name, case-sensitive
        assertNull(QuickToggle.of(""))
        assertNull(QuickToggle.of(null))
    }

    @Test
    fun `parsing a stored list with of drops the ids this build does not know`() {
        val stored = listOf("STATS", "MYSTERY", "HUD", "TIME_TRAVEL")
        val parsed = stored.mapNotNull(QuickToggle::of)
        assertEquals(listOf(QuickToggle.STATS, QuickToggle.HUD), parsed)
    }

    // ------------------------------------------------------------------ normalise

    @Test
    fun `normalise keeps at most six`() {
        // Every toggle there is, in enum order, is ten — four over the cap.
        val raw = QuickToggle.entries.toList()
        val out = QuickSheetPins.normalise(raw, all)
        assertEquals(QuickSheetPins.MAX_PINS, out.size)
        assertEquals(raw.take(QuickSheetPins.MAX_PINS), out)
    }

    @Test
    fun `normalise collapses duplicates to the first occurrence, order kept`() {
        val raw = listOf(
            QuickToggle.HUD,
            QuickToggle.STATS,
            QuickToggle.HUD, // dupe of the first entry
            QuickToggle.CROSSHAIR,
            QuickToggle.STATS, // dupe
        )
        assertEquals(listOf(QuickToggle.HUD, QuickToggle.STATS, QuickToggle.CROSSHAIR), QuickSheetPins.normalise(raw, all))
    }

    @Test
    fun `normalise skips toggles unavailable on this device`() {
        // Only the three overlay toggles work here; the capture and shell ones do not.
        val overlaysOnly: (QuickToggle) -> Boolean = {
            it == QuickToggle.STATS || it == QuickToggle.CROSSHAIR || it == QuickToggle.HUD
        }
        val raw = listOf(QuickToggle.SILENCE, QuickToggle.STATS, QuickToggle.TORCH, QuickToggle.HUD, QuickToggle.REFRESH)
        assertEquals(listOf(QuickToggle.STATS, QuickToggle.HUD), QuickSheetPins.normalise(raw, overlaysOnly))
    }

    @Test
    fun `an empty list falls back to the available defaults`() {
        assertEquals(QuickToggle.DEFAULT_SET, QuickSheetPins.normalise(emptyList(), all))
    }

    @Test
    fun `a list emptied by availability falls back to the available defaults`() {
        // Everything the user stored is unavailable, so the fallback fires — and the fallback is filtered
        // too, so the conditional defaults (Silence, Screenshot, Refresh) drop and only the overlays remain.
        val overlaysOnly: (QuickToggle) -> Boolean = {
            it == QuickToggle.STATS || it == QuickToggle.CROSSHAIR || it == QuickToggle.HUD
        }
        val raw = listOf(QuickToggle.TORCH, QuickToggle.ROTATION, QuickToggle.RECORD)
        assertEquals(
            listOf(QuickToggle.CROSSHAIR, QuickToggle.STATS, QuickToggle.HUD),
            QuickSheetPins.normalise(raw, overlaysOnly),
        )
    }

    @Test
    fun `when even the defaults are unavailable the grid is honestly empty`() {
        assertTrue(QuickSheetPins.normalise(listOf(QuickToggle.STATS), none).isEmpty())
        assertTrue(QuickSheetPins.normalise(emptyList(), none).isEmpty())
    }

    @Test
    fun `normalise applies all rules together`() {
        // Unavailable Torch dropped, HUD de-duped, then capped at six from what remains available.
        val availableExceptTorch: (QuickToggle) -> Boolean = { it != QuickToggle.TORCH }
        val raw = listOf(
            QuickToggle.STATS,
            QuickToggle.TORCH, // unavailable -> dropped
            QuickToggle.CROSSHAIR,
            QuickToggle.HUD,
            QuickToggle.HUD, // dupe -> dropped
            QuickToggle.SILENCE,
            QuickToggle.SCREENSHOT,
            QuickToggle.REFRESH,
            QuickToggle.ROTATION, // seventh distinct available -> over the cap
        )
        assertEquals(
            listOf(
                QuickToggle.STATS,
                QuickToggle.CROSSHAIR,
                QuickToggle.HUD,
                QuickToggle.SILENCE,
                QuickToggle.SCREENSHOT,
                QuickToggle.REFRESH,
            ),
            QuickSheetPins.normalise(raw, availableExceptTorch),
        )
    }

    // ------------------------------------------------------------------ add

    @Test
    fun `add puts an available toggle on the end`() {
        assertEquals(
            listOf(QuickToggle.STATS, QuickToggle.HUD),
            QuickSheetPins.add(listOf(QuickToggle.STATS), QuickToggle.HUD, all),
        )
    }

    @Test
    fun `add refuses a duplicate`() {
        val current = listOf(QuickToggle.STATS, QuickToggle.HUD)
        assertSame(current, QuickSheetPins.add(current, QuickToggle.STATS, all))
    }

    @Test
    fun `add refuses an unavailable toggle`() {
        val current = listOf(QuickToggle.STATS)
        assertSame(current, QuickSheetPins.add(current, QuickToggle.TORCH, none))
    }

    @Test
    fun `add respects the cap`() {
        val full = QuickToggle.entries.take(QuickSheetPins.MAX_PINS)
        assertEquals(QuickSheetPins.MAX_PINS, full.size)
        assertSame(full, QuickSheetPins.add(full, QuickToggle.ROTATION, all))
    }

    // ------------------------------------------------------------------ remove

    @Test
    fun `remove takes a pin out`() {
        assertEquals(
            listOf(QuickToggle.STATS, QuickToggle.CROSSHAIR),
            QuickSheetPins.remove(listOf(QuickToggle.STATS, QuickToggle.HUD, QuickToggle.CROSSHAIR), QuickToggle.HUD),
        )
    }

    @Test
    fun `removing a pin that is not there changes nothing`() {
        val current = listOf(QuickToggle.STATS, QuickToggle.HUD)
        assertEquals(current, QuickSheetPins.remove(current, QuickToggle.TORCH))
    }

    // ------------------------------------------------------------------ move / reorder

    @Test
    fun `move shifts a pin towards the front`() {
        val current = listOf(QuickToggle.STATS, QuickToggle.HUD, QuickToggle.CROSSHAIR)
        assertEquals(
            listOf(QuickToggle.HUD, QuickToggle.STATS, QuickToggle.CROSSHAIR),
            QuickSheetPins.move(current, QuickToggle.HUD, -1),
        )
    }

    @Test
    fun `move shifts a pin towards the back`() {
        val current = listOf(QuickToggle.STATS, QuickToggle.HUD, QuickToggle.CROSSHAIR)
        assertEquals(
            listOf(QuickToggle.STATS, QuickToggle.CROSSHAIR, QuickToggle.HUD),
            QuickSheetPins.move(current, QuickToggle.HUD, 1),
        )
    }

    @Test
    fun `moving the first pin up is a wall, not a wrap`() {
        val current = listOf(QuickToggle.STATS, QuickToggle.HUD, QuickToggle.CROSSHAIR)
        assertSame(current, QuickSheetPins.move(current, QuickToggle.STATS, -1))
    }

    @Test
    fun `moving the last pin down is a wall, not a wrap`() {
        val current = listOf(QuickToggle.STATS, QuickToggle.HUD, QuickToggle.CROSSHAIR)
        assertSame(current, QuickSheetPins.move(current, QuickToggle.CROSSHAIR, 1))
    }

    @Test
    fun `moving a pin that is not there changes nothing`() {
        val current = listOf(QuickToggle.STATS, QuickToggle.HUD)
        assertSame(current, QuickSheetPins.move(current, QuickToggle.TORCH, 1))
    }

    // --- resolve: the one path the service and the settings screen share ---------------------------

    @Test
    fun `resolve turns stored names into toggles, in the order they were pinned`() {
        assertEquals(
            listOf(QuickToggle.HUD, QuickToggle.STATS),
            QuickSheetPins.resolve(listOf("HUD", "STATS")) { true },
        )
    }

    @Test
    fun `resolve drops a name this build does not know without taking its neighbours with it`() {
        // An id from a newer build, or a renamed toggle. Dropping it silently is the contract — the
        // alternative is a grid that refuses to load because one entry is a stranger.
        assertEquals(
            listOf(QuickToggle.STATS, QuickToggle.HUD),
            QuickSheetPins.resolve(listOf("STATS", "NOT_A_REAL_TOGGLE", "HUD")) { true },
        )
    }

    @Test
    fun `resolve of nothing stored is the default set`() {
        // The "never set" case, and the reason the settings screen resolves rather than reading the raw
        // list: the editor has to draw the six the sheet will actually show, or the first remove would be
        // applied to an empty list and look like a dead button.
        assertEquals(QuickToggle.DEFAULT_SET, QuickSheetPins.resolve(emptyList()) { true })
    }

    @Test
    fun `resolve of names that are all strangers falls back rather than emptying the sheet`() {
        // The two rules compose: every name drops out at parse time, and an empty list is then the
        // fallback case. A sheet wiped by one bad stored value would be the worse of the two answers.
        assertEquals(QuickToggle.DEFAULT_SET, QuickSheetPins.resolve(listOf("NOPE", "ALSO_NOPE")) { true })
    }
}
