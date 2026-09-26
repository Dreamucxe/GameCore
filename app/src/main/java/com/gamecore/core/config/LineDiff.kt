package com.gamecore.core.config

/**
 * Whether a line survived unchanged, was added by the new text, or was removed from the old (spec §28).
 */
enum class DiffType { UNCHANGED, ADDED, REMOVED }

/**
 * One line in a rendered diff, tagged with where it came from.
 *
 * [oldLine] and [newLine] are 1-based positions in the two inputs, and which of them is present is fixed
 * by [type]: an [DiffType.UNCHANGED] line sat in both texts and carries both numbers; a [DiffType.REMOVED]
 * line existed only in the old text and carries [oldLine] with [newLine] null; a [DiffType.ADDED] line
 * exists only in the new text and carries [newLine] with [oldLine] null. The numbers are the line's place
 * in its own text, not its row in the merged view, so a caller can print a real gutter beside each side.
 */
data class DiffLine(
    val type: DiffType,
    val text: String,
    val oldLine: Int?,
    val newLine: Int?,
)

/**
 * A whole diff: the merged sequence of [lines] in reading order, plus the counts a summary line wants.
 *
 * [added] and [removed] are exactly the number of [DiffType.ADDED] and [DiffType.REMOVED] entries in
 * [lines] — precomputed so a "+3 −1" header need not re-scan the list.
 */
data class DiffResult(
    val lines: List<DiffLine>,
    val added: Int,
    val removed: Int,
)

/**
 * A pure, deterministic line diff — no Android, no I/O, no clock — for previewing a config edit before it
 * is written back (spec §28). Given the old and new text it produces the minimal set of line additions and
 * removals that turns one into the other, computed by a classic longest-common-subsequence over lines.
 *
 * **How text becomes lines.** The single rule is `text.split("\n")`, applied to both inputs and never
 * softened. That rule is worth stating because its edges are the ones callers trip on:
 *
 *  - `"a\nb"` is the two lines `["a", "b"]`.
 *  - `"a\n"` is the two lines `["a", ""]` — a trailing newline is a final empty line, not nothing. A file
 *    saved with the customary trailing newline therefore diffs cleanly against another saved the same way.
 *  - `""` is the one line `[""]` — the empty string is a single empty line, not zero lines. So diffing
 *    `""` against `""` yields one UNCHANGED empty line (added 0, removed 0), and diffing `""` against real
 *    content shows that empty line removed alongside the content added. We never trim or special-case the
 *    empty input; treating it as one empty line is what keeps the split rule the *only* rule.
 *
 * **Determinism.** The LCS table and its backtrack are fully determined by the inputs, and the one place a
 * choice arises — a line replaced by a different line, where an add and a remove are equally minimal — is
 * resolved the same way every time: the removed line is emitted before the added one, so a replacement
 * reads as `−old` then `+new`, the order a reader expects.
 *
 * **Cost.** This is the textbook O(m·n) dynamic program in both time and memory, m and n being the line
 * counts. That is ample for the hand-editable config files this previews (the editor caps input at a few
 * megabytes) and deliberately simpler than a Myers-style diff, which this does not attempt.
 */
object LineDiff {

    /**
     * Diff [oldText] into [newText], returning the merged line sequence and the add/remove counts.
     *
     * Both inputs are split on `"\n"` per the rule documented on [LineDiff]; the result's [DiffResult.lines]
     * are in reading order, and [DiffResult.added] / [DiffResult.removed] count the ADDED / REMOVED entries.
     */
    fun diff(oldText: String, newText: String): DiffResult {
        val old = oldText.split("\n")
        val new = newText.split("\n")
        val m = old.size
        val n = new.size

        // lcs[i][j] = length of the longest common subsequence of old[0until i) and new[0 until j).
        // Row 0 and column 0 stay 0 (an empty side shares nothing), so the loop fills from (1,1).
        val lcs = Array(m + 1) { IntArray(n + 1) }
        for (i in 1..m) {
            val oi = old[i - 1]
            val row = lcs[i]
            val prev = lcs[i - 1]
            for (j in 1..n) {
                row[j] = if (oi == new[j - 1]) {
                    prev[j - 1] + 1
                } else {
                    maxOf(prev[j], row[j - 1])
                }
            }
        }

        // Backtrack from (m, n) to (0, 0), emitting lines in reverse and flipping at the end. On a tie
        // between "the new line was added" and "the old line was removed", we take the addition here so
        // that, once reversed, the removal precedes the addition in reading order.
        val reversed = ArrayList<DiffLine>(m + n)
        var added = 0
        var removed = 0
        var i = m
        var j = n
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && old[i - 1] == new[j - 1] -> {
                    reversed.add(DiffLine(DiffType.UNCHANGED, old[i - 1], oldLine = i, newLine = j))
                    i--
                    j--
                }
                j > 0 && (i == 0 || lcs[i][j - 1] >= lcs[i - 1][j]) -> {
                    reversed.add(DiffLine(DiffType.ADDED, new[j - 1], oldLine = null, newLine = j))
                    added++
                    j--
                }
                else -> {
                    reversed.add(DiffLine(DiffType.REMOVED, old[i - 1], oldLine = i, newLine = null))
                    removed++
                    i--
                }
            }
        }

        reversed.reverse()
        return DiffResult(lines = reversed, added = added, removed = removed)
    }
}
