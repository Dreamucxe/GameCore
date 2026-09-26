package com.gamecore.core.config

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The line diff behind the config-edit preview (spec §28), pinned case by case.
 *
 * Two things are asserted for every input: the exact merged [DiffLine] sequence — type, text, and both
 * 1-based line numbers — and the [DiffResult.added] / [DiffResult.removed] counts. The line numbers matter
 * as much as the types (a preview draws a gutter from them), so they are checked rather than ignored.
 *
 * The trailing-newline and empty-string cases are here on purpose: the utility's one rule is
 * `text.split("\n")` with no trimming, so `"a\n"` is two lines and `""` is one empty line, and these tests
 * are what stop a well-meaning "tidy up the empty case" change from quietly breaking that contract.
 */
class LineDiffTest {

    private fun unchanged(text: String, old: Int, new: Int) =
        DiffLine(DiffType.UNCHANGED, text, oldLine = old, newLine = new)

    private fun added(text: String, new: Int) =
        DiffLine(DiffType.ADDED, text, oldLine = null, newLine = new)

    private fun removed(text: String, old: Int) =
        DiffLine(DiffType.REMOVED, text, oldLine = old, newLine = null)

    /** Every result must count exactly the ADDED/REMOVED entries its own line list contains. */
    private fun assertCountsAgree(result: DiffResult) {
        assertEquals(result.lines.count { it.type == DiffType.ADDED }, result.added)
        assertEquals(result.lines.count { it.type == DiffType.REMOVED }, result.removed)
    }

    @Test
    fun `both empty is a single unchanged empty line`() {
        val result = LineDiff.diff("", "")
        assertEquals(listOf(unchanged("", old = 1, new = 1)), result.lines)
        assertEquals(0, result.added)
        assertEquals(0, result.removed)
        assertCountsAgree(result)
    }

    @Test
    fun `identical multi-line text is entirely unchanged`() {
        val text = "graphics=high\nfps=60\nvsync=off"
        val result = LineDiff.diff(text, text)
        assertEquals(
            listOf(
                unchanged("graphics=high", old = 1, new = 1),
                unchanged("fps=60", old = 2, new = 2),
                unchanged("vsync=off", old = 3, new = 3),
            ),
            result.lines,
        )
        assertEquals(0, result.added)
        assertEquals(0, result.removed)
        assertCountsAgree(result)
    }

    @Test
    fun `inserting a line keeps its neighbours unchanged`() {
        val result = LineDiff.diff("a\nb", "a\nx\nb")
        assertEquals(
            listOf(
                unchanged("a", old = 1, new = 1),
                added("x", new = 2),
                unchanged("b", old = 2, new = 3),
            ),
            result.lines,
        )
        assertEquals(1, result.added)
        assertEquals(0, result.removed)
        assertCountsAgree(result)
    }

    @Test
    fun `deleting a line keeps its neighbours unchanged`() {
        val result = LineDiff.diff("a\nx\nb", "a\nb")
        assertEquals(
            listOf(
                unchanged("a", old = 1, new = 1),
                removed("x", old = 2),
                unchanged("b", old = 3, new = 2),
            ),
            result.lines,
        )
        assertEquals(0, result.added)
        assertEquals(1, result.removed)
        assertCountsAgree(result)
    }

    @Test
    fun `a replaced line reads as the removal before the addition`() {
        val result = LineDiff.diff("a\nX\nb", "a\nY\nb")
        assertEquals(
            listOf(
                unchanged("a", old = 1, new = 1),
                removed("X", old = 2),
                added("Y", new = 2),
                unchanged("b", old = 3, new = 3),
            ),
            result.lines,
        )
        assertEquals(1, result.added)
        assertEquals(1, result.removed)
        assertCountsAgree(result)
    }

    @Test
    fun `no common lines removes every old line then adds every new line`() {
        val result = LineDiff.diff("x\ny", "a\nb")
        assertEquals(
            listOf(
                removed("x", old = 1),
                removed("y", old = 2),
                added("a", new = 1),
                added("b", new = 2),
            ),
            result.lines,
        )
        assertEquals(2, result.added)
        assertEquals(2, result.removed)
        assertCountsAgree(result)
    }

    @Test
    fun `inserting into empty old removes the empty line and adds the content`() {
        // "" is the single empty line [""], so it is removed as the two real lines arrive. This documents
        // the split rule rather than pretending the empty string is zero lines.
        val result = LineDiff.diff("", "a\nb")
        assertEquals(
            listOf(
                removed("", old = 1),
                added("a", new = 1),
                added("b", new = 2),
            ),
            result.lines,
        )
        assertEquals(2, result.added)
        assertEquals(1, result.removed)
        assertCountsAgree(result)
    }

    @Test
    fun `clearing all content removes every line and adds the empty line`() {
        val result = LineDiff.diff("a\nb", "")
        assertEquals(
            listOf(
                removed("a", old = 1),
                removed("b", old = 2),
                added("", new = 1),
            ),
            result.lines,
        )
        assertEquals(1, result.added)
        assertEquals(2, result.removed)
        assertCountsAgree(result)
    }

    @Test
    fun `adding a trailing newline adds a final empty line`() {
        val result = LineDiff.diff("a", "a\n")
        assertEquals(
            listOf(
                unchanged("a", old = 1, new = 1),
                added("", new = 2),
            ),
            result.lines,
        )
        assertEquals(1, result.added)
        assertEquals(0, result.removed)
        assertCountsAgree(result)
    }

    @Test
    fun `removing a trailing newline removes the final empty line`() {
        val result = LineDiff.diff("a\n", "a")
        assertEquals(
            listOf(
                unchanged("a", old = 1, new = 1),
                removed("", old = 2),
            ),
            result.lines,
        )
        assertEquals(0, result.added)
        assertEquals(1, result.removed)
        assertCountsAgree(result)
    }

    @Test
    fun `a change between trailing-newline files leaves the trailing empty line unchanged`() {
        val result = LineDiff.diff("a\nb\n", "a\nc\n")
        assertEquals(
            listOf(
                unchanged("a", old = 1, new = 1),
                removed("b", old = 2),
                added("c", new = 2),
                unchanged("", old = 3, new = 3),
            ),
            result.lines,
        )
        assertEquals(1, result.added)
        assertEquals(1, result.removed)
        assertCountsAgree(result)
    }
}
