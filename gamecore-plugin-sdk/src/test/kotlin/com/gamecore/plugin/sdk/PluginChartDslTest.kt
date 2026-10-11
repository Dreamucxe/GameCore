package com.gamecore.plugin.sdk

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `chart()` leaf on the overlay scope: it emits a chart node the host's chart reader will
 * accept, refuses at build time what the host would silently clamp or fall back on, and never
 * offers an author a way to name a live series — an overlay chart is literal-only.
 *
 * The manifest side is pinned here too: a module that draws a chart raises the package `api` to
 * [Sdk.API_V3], exactly as a schema-2 theme raises it, while a module with no chart stays byte-for-
 * byte the api-1 package it was before charts existed.
 */
class PluginChartDslTest {

    private fun pillOf(definition: PluginDefinition): JSONObject =
        JSONObject(String(definition.sectionFiles.getValue("pill.json")))

    private fun manifestOf(definition: PluginDefinition): JSONObject =
        JSONObject(String(definition.manifestJson))

    /** A module whose single root node is the chart built by [block]. */
    private fun chartModule(block: PillBuilder.() -> Unit): PluginDefinition =
        plugin("com.example.chart", "Chart") {
            pill {
                id = "plot"
                block()
            }
        }

    private fun chartNode(definition: PluginDefinition): JSONObject =
        pillOf(definition).getJSONObject("content")

    private fun chartProps(definition: PluginDefinition): JSONObject =
        chartNode(definition).getJSONObject("props")

    // ------------------------------------------------------------------------------- happy path

    @Test
    fun `a chart emits a chart node that defaults to a line`() {
        val node = chartNode(chartModule { chart(values = listOf(1, 2, 3)) })
        assertEquals("chart", node.getString("type"))
        assertEquals(ChartKinds.LINE, node.getJSONObject("props").getString("kind"))
        assertEquals("1,2,3", node.getJSONObject("props").getString("values"))
    }

    @Test
    fun `whole numbers encode without a trailing decimal and decimals survive`() {
        assertEquals("1,2,3", chartProps(chartModule { chart(values = listOf(1.0, 2.0, 3.0)) }).getString("values"))
        assertEquals(
            "-2.5,0,3.25",
            chartProps(chartModule { chart(values = listOf(-2.5, 0, 3.25)) }).getString("values"),
        )
    }

    @Test
    fun `a bar chart, a second series, a range, labels and a note all round-trip`() {
        val props = chartProps(
            chartModule {
                chart(
                    values = listOf(1, 2, 3),
                    kind = ChartKinds.BAR,
                    values2 = listOf(4, 5, 6),
                    min = 0,
                    max = 10,
                    label = "Speed",
                    label2 = "Target",
                    note = "last 3 ticks",
                    color = "#FF8800",
                    color2 = "#2244FF",
                )
            },
        )
        assertEquals(ChartKinds.BAR, props.getString("kind"))
        assertEquals("4,5,6", props.getString("values2"))
        assertEquals(0.0, props.getDouble("min"), 0.0001)
        assertEquals(10.0, props.getDouble("max"), 0.0001)
        assertEquals("Speed", props.getString("label"))
        assertEquals("Target", props.getString("label2"))
        assertEquals("last 3 ticks", props.getString("note"))
        assertEquals("#FF8800", props.getString("color"))
        assertEquals("#2244FF", props.getString("color2"))
    }

    @Test
    fun `an overlay chart offers no live series and emits neither stat prop`() {
        // Literal-only by construction: there is no stat parameter to pass, so a module chart can
        // never carry one — the mirror of how the host's overlay audit refuses one.
        val props = chartProps(chartModule { chart(values = listOf(1, 2, 3)) })
        assertFalse(props.has("stat"))
        assertFalse(props.has("stat2"))
    }

    // ------------------------------------------------------------------------------- refusals

    @Test
    fun `an unknown kind is refused at build time`() {
        assertFailsWith<IllegalArgumentException> {
            chartModule { chart(values = listOf(1, 2, 3), kind = "pie") }
        }
    }

    @Test
    fun `an empty series is refused`() {
        assertFailsWith<IllegalArgumentException> {
            chartModule { chart(values = emptyList()) }
        }
    }

    @Test
    fun `more points than the host's history can hold is refused`() {
        assertFailsWith<IllegalArgumentException> {
            chartModule { chart(values = List(ChartBounds.MAX_POINTS + 1) { 0 }) }
        }
    }

    @Test
    fun `a values string longer than the tightest host cap is refused`() {
        // Few enough points to pass the count cap, but a long encoding that overflows the string
        // cap — the limit a literal overlay chart actually meets first.
        assertFailsWith<IllegalArgumentException> {
            chartModule { chart(values = List(25) { 1_000_000 }) }
        }
    }

    @Test
    fun `a range that is not strictly increasing, or half-given, is refused`() {
        assertFailsWith<IllegalArgumentException> {
            chartModule { chart(values = listOf(1, 2, 3), min = 10, max = 0) }
        }
        assertFailsWith<IllegalArgumentException> {
            chartModule { chart(values = listOf(1, 2, 3), min = 5, max = 5) }
        }
        assertFailsWith<IllegalArgumentException> {
            chartModule { chart(values = listOf(1, 2, 3), min = 0) }
        }
    }

    @Test
    fun `a non-finite value is refused`() {
        assertFailsWith<IllegalArgumentException> {
            chartModule { chart(values = listOf(1.0, Double.NaN, 3.0)) }
        }
    }

    @Test
    fun `a malformed colour is refused`() {
        assertFailsWith<IllegalArgumentException> {
            chartModule { chart(values = listOf(1, 2, 3), color = "orange") }
        }
    }

    // ------------------------------------------------------------------------------- api bump

    @Test
    fun `a module that draws a chart raises the manifest api to v3`() {
        val manifest = manifestOf(chartModule { chart(values = listOf(1, 2, 3)) })
        assertEquals(Sdk.API_V3, manifest.getInt("api"))
    }

    @Test
    fun `a chart nested in a container still raises the api`() {
        val manifest = manifestOf(
            plugin("com.example.chart", "Chart") {
                pill {
                    id = "plot"
                    column {
                        text("Speed")
                        chart(values = listOf(1, 2, 3))
                    }
                }
            },
        )
        assertEquals(Sdk.API_V3, manifest.getInt("api"))
    }

    @Test
    fun `a module with no chart stays at the base api`() {
        val manifest = manifestOf(
            plugin("com.example.pill", "Pill") {
                pill {
                    id = "badge"
                    text("Speed")
                }
            },
        )
        assertEquals(Sdk.API, manifest.getInt("api"))
        assertTrue(manifest.getInt("api") < Sdk.API_V3)
    }
}
