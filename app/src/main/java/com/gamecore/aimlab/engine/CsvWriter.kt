package com.gamecore.aimlab.engine

/**
 * CSV assembly with spreadsheet-formula-injection defence, mirroring the diagnostics exporter's rules.
 *
 * Pure and android-free so the escaping is unit-tested directly (§B2.11). A CSV cell that a spreadsheet
 * would treat as a formula — one starting with `=`, `+`, `-` or `@` — is prefixed with a single quote so
 * it is read as text, and any cell containing a comma, quote, newline or semicolon is wrapped in quotes
 * with inner quotes doubled. This is the same defence the existing `DiagnosticsExporter` applies; it lives
 * here too so the Aim Lab engine can build exportable strings without an android dependency.
 */
object CsvWriter {

    /**
     * The characters that make a spreadsheet read a cell as a formula rather than as text.
     *
     * The tab is in the list for the same reason the other four are. Excel strips leading whitespace
     * before deciding what a cell is, so `\t=cmd|'/c calc'!A1` is a formula wearing a hat — the four
     * obvious leads are checked against `value[0]`, and a tab in front of one walks straight past that
     * check. `\r` and `\n` are already flattened to spaces above, which is why they are not here; the tab
     * is not flattened, because a tab inside a cell is legitimate content and the quoting rule below
     * handles it.
     */
    private val FORMULA_LEADS = charArrayOf('=', '+', '-', '@', '\t')

    /** Escapes one cell. */
    fun escape(raw: String): String {
        var value = raw.replace("\r", " ").replace("\n", " ")
        if (value.isNotEmpty() && value[0] in FORMULA_LEADS) {
            value = "'$value"
        }
        val needsQuoting = value.any { it == ',' || it == '"' || it == ';' || it == '\t' }
        if (needsQuoting) {
            value = "\"" + value.replace("\"", "\"\"") + "\""
        }
        return value
    }

    /** Joins one row of already-raw cells into an escaped CSV line. */
    fun row(cells: List<String>): String = cells.joinToString(",") { escape(it) }

    /** A whole document: header row plus data rows, newline-separated, trailing newline. */
    fun document(header: List<String>, rows: List<List<String>>): String {
        val sb = StringBuilder()
        sb.append(row(header)).append('\n')
        for (r in rows) sb.append(row(r)).append('\n')
        return sb.toString()
    }
}

/**
 * Locale-independent number formatting for export.
 *
 * `String.format("%.2f")` uses the default locale and produces `1,50` under a comma-decimal locale, which
 * corrupts both CSV and JSON. These format with integer arithmetic and a literal `.`, so output is stable
 * everywhere — the same reason the diagnostics exporter rolls its own.
 */
object NumberText {
    /** Fixed-point with [decimals] places, always using `.` as the separator. */
    fun fixed(value: Float, decimals: Int = 2): String {
        if (value.isNaN() || value.isInfinite()) return "0"
        val negative = value < 0
        val scale = pow10(decimals)
        val scaled = Math.round(Math.abs(value.toDouble()) * scale)
        val whole = scaled / scale
        val frac = scaled % scale
        val sb = StringBuilder()
        if (negative && scaled != 0L) sb.append('-')
        sb.append(whole)
        if (decimals > 0) {
            sb.append('.')
            val fracStr = frac.toString().padStart(decimals, '0')
            sb.append(fracStr)
        }
        return sb.toString()
    }

    private fun pow10(n: Int): Long {
        var r = 1L
        repeat(n) { r *= 10 }
        return r
    }
}
