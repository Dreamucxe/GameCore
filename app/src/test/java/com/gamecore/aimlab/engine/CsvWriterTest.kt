package com.gamecore.aimlab.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CSV export escaping.
 *
 * Two separate hazards live here. The first is ordinary correctness: a weapon called `AK-47, "Red"` must
 * survive a trip through a spreadsheet without splitting into three columns. The second is CSV injection —
 * a cell beginning `=`, `+`, `-` or `@` is treated as a *formula* by Excel, Sheets and LibreOffice, so a
 * name the user pasted from anywhere could execute when they open their own exported stats. Prefixing a
 * single quote neutralises that while still displaying the original text.
 *
 * [NumberText.fixed] is tested alongside because a locale that formats decimals with a comma would break
 * every numeric column in the same file; the formatter is hand-rolled precisely so it cannot.
 */
class CsvWriterTest {

    @Test
    fun `plain text passes through untouched`() {
        assertEquals("Rifle", CsvWriter.escape("Rifle"))
        assertEquals("", CsvWriter.escape(""))
        assertEquals("70", CsvWriter.escape("70"))
    }

    @Test
    fun `a comma forces quoting`() {
        assertEquals("\"AK-47, red\"", CsvWriter.escape("AK-47, red"))
    }

    @Test
    fun `a quote is doubled and the cell is quoted`() {
        assertEquals("\"He said \"\"go\"\"\"", CsvWriter.escape("""He said "go""""))
    }

    @Test
    fun `a semicolon or tab forces quoting, because some locales split on them`() {
        assertEquals("\"a;b\"", CsvWriter.escape("a;b"))
        assertEquals("\"a\tb\"", CsvWriter.escape("a\tb"))
    }

    @Test
    fun `a newline inside a cell becomes a space rather than a new record`() {
        // Quoted newlines are legal CSV but survive very few importers; a stats file must not lose rows.
        val escaped = CsvWriter.escape("line one\nline two")
        assertTrue("Still contains a newline: $escaped", !escaped.contains('\n'))
        assertTrue(!CsvWriter.escape("a\r\nb").contains('\r'))
    }

    @Test
    fun `every formula lead character is neutralised`() {
        for (lead in listOf('=', '+', '-', '@')) {
            val escaped = CsvWriter.escape("${lead}cmd|'/c calc'!A0")
            assertTrue(
                "A cell starting '$lead' was not neutralised: $escaped",
                escaped.startsWith("'") || escaped.startsWith("\"'"),
            )
        }
    }

    @Test
    fun `a negative number is still a formula lead and is neutralised`() {
        // This is the awkward case: -5 is both a legitimate value and an Excel formula start. Everything
        // numeric the app exports goes through NumberText, so quoting here costs nothing real.
        assertTrue(CsvWriter.escape("-5").startsWith("'"))
    }

    @Test
    fun `a formula lead that also needs quoting gets both treatments`() {
        val escaped = CsvWriter.escape("=SUM(A1,A2)")
        assertTrue(escaped.startsWith("\"'") || escaped.startsWith("'"))
        assertTrue(escaped.contains("SUM"))
    }

    @Test
    fun `a lead character in the middle is left alone`() {
        assertEquals("a=b", CsvWriter.escape("a=b"))
        assertEquals("Rifle+", CsvWriter.escape("Rifle+"))
    }

    @Test
    fun `a row joins escaped cells with commas`() {
        assertEquals("a,b,c", CsvWriter.row(listOf("a", "b", "c")))
        assertEquals("\"a,1\",b", CsvWriter.row(listOf("a,1", "b")))
    }

    @Test
    fun `a document is a header followed by one line per row`() {
        val doc = CsvWriter.document(
            header = listOf("mode", "score"),
            rows = listOf(listOf("FLICK", "1200"), listOf("TRACKING", "900")),
        )
        val lines = doc.trim().lines()
        assertEquals(3, lines.size)
        assertEquals("mode,score", lines[0])
        assertEquals("FLICK,1200", lines[1])
        assertEquals("TRACKING,900", lines[2])
    }

    @Test
    fun `a document with no rows still carries its header`() {
        val doc = CsvWriter.document(header = listOf("mode", "score"), rows = emptyList())
        assertEquals("mode,score", doc.trim())
    }

    @Test
    fun `fixed formats with a dot regardless of the platform locale`() {
        assertEquals("1.50", NumberText.fixed(1.5f))
        assertEquals("0.00", NumberText.fixed(0f))
        assertEquals("12", NumberText.fixed(12f, decimals = 0))
        assertEquals("0.333", NumberText.fixed(1f / 3f, decimals = 3))
    }

    @Test
    fun `fixed keeps the sign and pads the fraction`() {
        assertEquals("-2.25", NumberText.fixed(-2.25f))
        assertEquals("3.10", NumberText.fixed(3.1f))
        assertEquals("0.05", NumberText.fixed(0.05f))
    }

    @Test
    fun `fixed rounds rather than truncates`() {
        assertEquals("1.00", NumberText.fixed(0.999f))
        assertEquals("2.35", NumberText.fixed(2.345f))
    }
}
