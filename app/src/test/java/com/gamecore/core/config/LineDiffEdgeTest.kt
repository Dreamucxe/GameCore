package com.gamecore.core.config

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Additional edge cases for [LineDiff.diff], kept apart from the base LineDiffTest so that file's pinned
 * cases stay exactly as they were. Nothing here repeats a case already asserted there: these cover
 * carriage-return preservation, contiguous multi-line insert/remove/replace blocks with their 1-based
 * gutter numbers, a leading insertion's number shift, literal whitespace-only lines, and how duplicate
 * lines resolve — each derived from the documented `split("\n")` rule and the "removal before addition"
 * tie-break on [LineDiff].
 *
 * The same helpers and count-agreement check as the base test are used verbatim so both files read alike.
 */
class LineDiffEdgeTest {

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
    fun `carriage returns are kept on each line since only newlines split`() {
        // A CRLF file splits on "\n" alone, so each line keeps its trailing "\r"; the diff compares those
        // "\r"-bearing strings literally. The final empty line is the trailing CRLF's own empty segment.
        val result = LineDiff.diff("a\r\nb\r\n", "a\r\nc\r\n")
        assertEquals(
            listOf(
                unchanged("a\r", old = 1, new = 1),
                removed("b\r", old = 2),
                added("c\r", new = 2),
                unchanged("", old = 3, new = 3),
            ),
            result.lines,
        )
        assertEquals(1, result.added)
        assertEquals(1, result.removed)
        assertCountsAgree(result)
    }

    @Test
    fun `a contiguous inserted block keeps every new line number in order`() {
        val result = LineDiff.diff("a\nb", "a\nx\ny\nz\nb")
        assertEquals(
            listOf(
                unchanged("a", old = 1, new = 1),
                added("x", new = 2),
                added("y", new = 3),
                added("z", new = 4),
                unchanged("b", old = 2, new = 5),
            ),
            result.lines,
        )
        assertEquals(3, result.added)
        assertEquals(0, result.removed)
        assertCountsAgree(result)
    }

    @Test
    fun `a contiguous removed block keeps every old line number in order`() {
        val result = LineDiff.diff("a\nx\ny\nz\nb", "a\nb")
        assertEquals(
            listOf(
                unchanged("a", old = 1, new = 1),
                removed("x", old = 2),
                removed("y", old = 3),
                removed("z", old = 4),
                unchanged("b", old = 5, new = 2),
            ),
            result.lines,
        )
        assertEquals(0, result.added)
        assertEquals(3, result.removed)
        assertCountsAgree(result)
    }

    @Test
    fun `a mid-file multi-line replacement removes the old block before adding the new`() {
        // Two lines swapped for two others between shared anchors: the tie-break emits both removals first,
        // then both additions, and the anchors' numbers straddle the block on each side.
        val result = LineDiff.diff("a\nb\nc\nd", "a\nX\nY\nd")
        assertEquals(
            listOf(
                unchanged("a", old = 1, new = 1),
                removed("b", old = 2),
                removed("c", old = 3),
                added("X", new = 2),
                added("Y", new = 3),
                unchanged("d", old = 4, new = 4),
            ),
            result.lines,
        )
        assertEquals(2, result.added)
        assertEquals(2, result.removed)
        assertCountsAgree(result)
    }

    @Test
    fun `a leading insertion shifts new line numbers while old numbers stay put`() {
        val result = LineDiff.diff("b\nc", "a\nb\nc")
        assertEquals(
            listOf(
                added("a", new = 1),
                unchanged("b", old = 1, new = 2),
                unchanged("c", old = 2, new = 3),
            ),
            result.lines,
        )
        assertEquals(1, result.added)
        assertEquals(0, result.removed)
        assertCountsAgree(result)
    }

    @Test
    fun `whitespace-only lines are compared literally with no trimming`() {
        // Line 1 is two spaces on both sides (unchanged); line 2 is a tab vs a single space (a real change),
        // proving the diff never trims — a tab and a space are different lines.
        val result = LineDiff.diff("  \n\t", "  \n ")
        assertEquals(
            listOf(
                unchanged("  ", old = 1, new = 1),
                removed("\t", old = 2),
                added(" ", new = 2),
            ),
            result.lines,
        )
        assertEquals(1, result.added)
        assertEquals(1, result.removed)
        assertCountsAgree(result)
    }

    @Test
    fun `with duplicate lines the last occurrence is the one kept unchanged`() {
        // "a\na" against "a": the LCS anchors on the second "a", so the first line reads as removed and the
        // survivor carries old line 2 — a documented consequence of the backtrack, not an arbitrary pick.
        val result = LineDiff.diff("a\na", "a")
        assertEquals(
            listOf(
                removed("a", old = 1),
                unchanged("a", old = 2, new = 1),
            ),
            result.lines,
        )
        assertEquals(0, result.added)
        assertEquals(1, result.removed)
        assertCountsAgree(result)
    }
}
