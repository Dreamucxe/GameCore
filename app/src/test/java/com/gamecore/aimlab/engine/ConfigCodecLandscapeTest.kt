package com.gamecore.aimlab.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Export/import of the per-orientation control sets, and backward compatibility with older exports (§4).
 *
 * The rules pinned here: a layout with a landscape set round-trips both orientations; an export from a
 * build that predates landscape (no `landscapeControls` key) still imports, landing everything as the
 * portrait set with an empty landscape set; and malformed orientation data in the file is rejected like
 * any other bad control.
 */
class ConfigCodecLandscapeTest {

    private fun accepted(json: String): ConfigCodec.ConfigBundle {
        val r = ConfigCodec.decode(json)
        assertTrue("expected Ok, got $r", r is ConfigCodec.ImportResult.Ok)
        return (r as ConfigCodec.ImportResult.Ok).bundle
    }

    @Test
    fun `a layout with both orientations round-trips`() {
        val layout = ControlLayout(
            id = 5L,
            name = "Both",
            controls = listOf(ControlWidget(ControlRole.SHOOT, 0.85f, 0.80f)),
            landscapeControls = listOf(
                ControlWidget(ControlRole.SHOOT, 0.90f, 0.72f),
                ControlWidget(ControlRole.MOVE_STICK, 0.12f, 0.72f),
            ),
        )
        val bundle = ConfigCodec.ConfigBundle(layouts = listOf(layout))
        val decoded = accepted(ConfigCodec.encode(bundle))
        assertEquals(1, decoded.layouts[0].controls.size)
        assertEquals(2, decoded.layouts[0].landscapeControls.size)
        assertTrue(decoded.layouts[0].landscapeControls.any { it.role == ControlRole.MOVE_STICK })
    }

    @Test
    fun `an older export with no landscape key still imports as portrait-only`() {
        // Hand-built document in the pre-landscape shape: "controls" only, no "landscapeControls".
        val json = """
            {"schema":1,"kind":"aimlab-config","weapons":[],"sensitivities":[],
             "layouts":[{"id":0,"name":"Old","controls":[
               {"role":"SHOOT","x":0.85,"y":0.8,"w":0.15,"h":0.15,"opacity":80,"shape":"CIRCLE","enabled":true}
             ]}]}
        """.trimIndent()
        val decoded = accepted(json)
        assertEquals(1, decoded.layouts.size)
        assertEquals(1, decoded.layouts[0].controls.size)
        assertTrue("old export must have no landscape set", decoded.layouts[0].landscapeControls.isEmpty())
    }

    @Test
    fun `a malformed control in the landscape set is rejected`() {
        val json = """
            {"schema":1,"kind":"aimlab-config","weapons":[],"sensitivities":[],
             "layouts":[{"id":0,"name":"Bad","controls":[],"landscapeControls":[
               {"role":"NOT_A_ROLE","x":0.5,"y":0.5}
             ]}]}
        """.trimIndent()
        val r = ConfigCodec.decode(json)
        assertTrue("an unknown landscape role must be rejected", r is ConfigCodec.ImportResult.Rejected)
    }

    @Test
    fun `a NaN coordinate never survives into the decoded landscape set`() {
        // The bare `NaN` token is non-standard JSON. The org.json on the classpath coerces it to the
        // field default rather than to Double.NaN, so the import is accepted with a *safe* coordinate
        // rather than rejected — and if a build's org.json ever returned a real NaN instead, the codec's
        // `finite()` guard would reject the file. Either way the property that matters is the same and is
        // what this asserts: no NaN coordinate can reach the decoded model.
        val json = """
            {"schema":1,"kind":"aimlab-config","weapons":[],"sensitivities":[],
             "layouts":[{"id":0,"name":"NaN","controls":[],"landscapeControls":[
               {"role":"SHOOT","x":NaN,"y":0.5}
             ]}]}
        """.trimIndent()
        when (val r = ConfigCodec.decode(json)) {
            is ConfigCodec.ImportResult.Rejected -> Unit // a real-NaN org.json path: rejected, also fine
            is ConfigCodec.ImportResult.Ok -> {
                r.bundle.layouts.flatMap { it.controls + it.landscapeControls }.forEach { c ->
                    assertTrue("a NaN x reached the model", c.xFraction.isFinite())
                    assertTrue("a NaN y reached the model", c.yFraction.isFinite())
                }
            }
        }
    }
}
