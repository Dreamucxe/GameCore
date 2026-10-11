package com.gamecore.core.plugin.ui.chart

import com.gamecore.core.model.MetricHistory
import com.gamecore.core.plugin.ui.ComponentNode
import com.gamecore.core.plugin.ui.ComponentType
import com.gamecore.core.plugin.ui.PropValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chart reader's contract: a total, defensive decode that never throws and resolves every
 * author mistake to the least surprising reading rather than to a rejection.
 *
 * The security boundaries this reader sits behind — the node codec, the screen validator, the
 * overlay audit — are tested in their own files; what is pinned here is only the reader's own
 * decisions: how `kind` falls back, how a delimited series parses, where the point cap sits, and
 * which half-specified ranges auto-scale. Every case is a plain [ComponentNode] in and a
 * [ChartSpec] out, so the whole file runs without Compose or a device, exactly as the node codec's
 * own test does.
 */
class ChartNodeCodecTest {

    private fun chart(vararg props: Pair<String, PropValue>): ChartSpec =
        ChartNodeCodec.decode(ComponentNode(ComponentType.CHART, props.toMap()))

    private fun str(value: String) = PropValue.Str(value)
    private fun num(value: Double) = PropValue.Num(value)

    // --------------------------------------------------------------------------------- point cap

    @Test
    fun `the point cap mirrors the host's rolling history`() {
        // The reader duplicates the constant rather than importing the model layer; this is the
        // pin that keeps the duplicate honest, so a change to the history length cannot silently
        // leave a chart able to carry more points than any live feed ever holds.
        assertEquals(MetricHistory.CAPACITY, ChartNodeCodec.MAX_POINTS)
        assertEquals(120, ChartNodeCodec.MAX_POINTS)
    }

    // --------------------------------------------------------------------------------- kind

    @Test
    fun `kind reads line and bar, case- and space-insensitively`() {
        assertEquals(ChartKind.LINE, chart("kind" to str("line")).kind)
        assertEquals(ChartKind.BAR, chart("kind" to str("bar")).kind)
        assertEquals(ChartKind.BAR, chart("kind" to str("  BAR ")).kind)
    }

    @Test
    fun `an absent or unknown kind falls back to a line`() {
        assertEquals(ChartKind.LINE, chart().kind)
        assertEquals(ChartKind.LINE, chart("kind" to str("pie")).kind)
        assertEquals(ChartKind.LINE, chart("kind" to str("")).kind)
    }

    // --------------------------------------------------------------------------------- series

    @Test
    fun `a values string parses across comma, semicolon and whitespace`() {
        assertEquals(listOf(1f, 2f, 3f), chart("values" to str("1,2,3")).values)
        assertEquals(listOf(1f, 2f, 3f), chart("values" to str("1; 2 ;3")).values)
        assertEquals(listOf(1f, 2f, 3f), chart("values" to str("1  2\t3")).values)
    }

    @Test
    fun `parsing drops blanks and anything that is not a finite number`() {
        // "x" is not a number, "" is a blank between delimiters, "1e999" overflows to Infinity.
        assertEquals(listOf(1f, 4f), chart("values" to str("1, x, , 1e999, 4")).values)
    }

    @Test
    fun `negative and decimal points survive`() {
        assertEquals(listOf(-2.5f, 0f, 3.25f), chart("values" to str("-2.5, 0, 3.25")).values)
    }

    @Test
    fun `a values string is capped at MAX_POINTS, dropping the tail`() {
        val encoded = (1..200).joinToString(",") { it.toString() }
        val parsed = chart("values" to str(encoded)).values
        assertEquals(ChartNodeCodec.MAX_POINTS, parsed.size)
        assertEquals(1f, parsed.first())
        assertEquals(ChartNodeCodec.MAX_POINTS.toFloat(), parsed.last())
    }

    @Test
    fun `an absent or blank series is empty, and an empty values2 becomes null`() {
        assertEquals(emptyList<Float>(), chart().values)
        assertEquals(emptyList<Float>(), chart("values" to str("   ")).values)
        assertNull(chart("values" to str("1,2")).values2)
        assertEquals(listOf(9f), chart("values2" to str("9")).values2)
    }

    // --------------------------------------------------------------------------------- live ids

    @Test
    fun `stat and stat2 become trimmed series ids, blank becomes null`() {
        val spec = chart("stat" to str(" fps "), "stat2" to str("cpu"))
        assertEquals("fps", spec.seriesId)
        assertEquals("cpu", spec.series2Id)
        assertTrue(spec.isLive)
    }

    @Test
    fun `a literal-only chart is not live`() {
        val spec = chart("values" to str("1,2,3"))
        assertNull(spec.seriesId)
        assertNull(spec.series2Id)
        assertFalse(spec.isLive)
        assertNull(chart("stat" to str("   ")).seriesId)
    }

    // --------------------------------------------------------------------------------- range

    @Test
    fun `min and max read from a Num or a numeric Str, and an explicit range needs both`() {
        assertEquals(0f..100f, chart("min" to num(0.0), "max" to num(100.0)).explicitRange())
        assertEquals(0f..100f, chart("min" to str("0"), "max" to str("100")).explicitRange())
    }

    @Test
    fun `a half or inverted range auto-scales rather than pinning`() {
        assertNull(chart("min" to num(0.0)).explicitRange())
        assertNull(chart("max" to num(100.0)).explicitRange())
        assertNull(chart("min" to num(100.0), "max" to num(0.0)).explicitRange())
        assertNull(chart("min" to num(5.0), "max" to num(5.0)).explicitRange())
    }

    // --------------------------------------------------------------------------------- styling

    @Test
    fun `colours come straight off a Color prop, labels and note off trimmed strings`() {
        val spec = chart(
            "color" to PropValue.Color(0xFF112233.toInt()),
            "color2" to PropValue.Color(0xFF445566.toInt()),
            "label" to str(" Speed "),
            "label2" to str("Target"),
            "note" to str("live"),
        )
        assertEquals(0xFF112233.toInt(), spec.colorArgb)
        assertEquals(0xFF445566.toInt(), spec.color2Argb)
        assertEquals("Speed", spec.label)
        assertEquals("Target", spec.label2)
        assertEquals("live", spec.note)
    }

    @Test
    fun `a string in a colour slot is not a colour, and absent styling is null`() {
        // The node codec is what turns a "#RRGGBB" string into a Color; a Str reaching the reader
        // is a label, never a colour, so the reader never guesses one.
        assertNull(chart("color" to str("#112233")).colorArgb)
        val bare = chart()
        assertNull(bare.colorArgb)
        assertNull(bare.label)
        assertNull(bare.note)
    }
}
