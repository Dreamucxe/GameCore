package com.gamecore.aimlab.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Import validation: the one place in Aim Lab where data the app did not create is turned into objects
 * the app trusts.
 *
 * Every test here feeds [ConfigCodec.decode] a file a user could plausibly pick — truncated, from a
 * different app, from a future version, hand-edited — and asserts a *typed rejection* rather than an
 * exception or a half-populated bundle. §36 requires an import to be validated before anything is written,
 * so a malformed entry must fail the whole file: a partial import would leave the user's weapon list in a
 * state neither they nor the app chose.
 *
 * These tests are only meaningful because a real `org.json` is on the unit-test classpath. The one inside
 * `android.jar` is a stub that returns defaults under `isReturnDefaultValues`, which would make every case
 * below pass without a single character being parsed.
 */
class ConfigCodecTest {

    private fun rejection(raw: String): String {
        val result = ConfigCodec.decode(raw)
        assertTrue("Expected a rejection, got $result", result is ConfigCodec.ImportResult.Rejected)
        return (result as ConfigCodec.ImportResult.Rejected).reason
    }

    private fun accepted(raw: String): ConfigCodec.ConfigBundle {
        val result = ConfigCodec.decode(raw)
        assertTrue("Expected acceptance, got $result", result is ConfigCodec.ImportResult.Ok)
        return (result as ConfigCodec.ImportResult.Ok).bundle
    }

    private fun envelope(body: String) =
        """{"kind":"${ConfigCodec.KIND}","schema":${ConfigCodec.SCHEMA_VERSION},$body}"""

    // --- Round trip -------------------------------------------------------------------------------

    @Test
    fun `a bundle this app wrote survives its own round trip`() {
        val bundle = ConfigCodec.ConfigBundle(
            weapons = listOf(
                Weapon(
                    id = 7L,
                    name = "Test Rifle",
                    category = WeaponCategory.ASSAULT_RIFLE,
                    fireRateRpm = 700,
                    magazineSize = 30,
                    reloadMillis = 2_100L,
                    adsTimeMillis = 240L,
                ),
            ),
            sensitivities = listOf(SensitivityProfile(id = 3L, name = "Claw", cameraSensitivity = 1.4f)),
            layouts = listOf(
                ControlLayout(
                    id = 9L,
                    name = "Four finger",
                    controls = listOf(
                        ControlWidget(ControlRole.SHOOT, 0.85f, 0.75f),
                        ControlWidget(ControlRole.ADS, 0.85f, 0.45f, shape = ControlShape.SQUARE),
                    ),
                ),
            ),
        )

        val decoded = accepted(ConfigCodec.encode(bundle))

        assertEquals(1, decoded.weapons.size)
        assertEquals("Test Rifle", decoded.weapons[0].name)
        assertEquals(WeaponCategory.ASSAULT_RIFLE, decoded.weapons[0].category)
        assertEquals(700, decoded.weapons[0].fireRateRpm)
        assertEquals(2_100L, decoded.weapons[0].reloadMillis)
        assertEquals(240L, decoded.weapons[0].adsTimeMillis)
        assertEquals("Claw", decoded.sensitivities[0].name)
        assertEquals(1.4f, decoded.sensitivities[0].cameraSensitivity, 1e-4f)
        assertEquals(2, decoded.layouts[0].controls.size)
        assertEquals(ControlRole.SHOOT, decoded.layouts[0].controls[0].role)
        assertEquals(ControlShape.SQUARE, decoded.layouts[0].controls[1].shape)
    }

    @Test
    fun `an empty bundle round trips to an empty bundle rather than being rejected`() {
        val decoded = accepted(ConfigCodec.encode(ConfigCodec.ConfigBundle()))
        assertTrue(decoded.weapons.isEmpty())
        assertTrue(decoded.sensitivities.isEmpty())
        assertTrue(decoded.layouts.isEmpty())
    }

    // --- Envelope ---------------------------------------------------------------------------------

    @Test
    fun `an oversized file is rejected before it is ever parsed`() {
        // Checked first and on the byte length, so a hostile 40 MB file cannot make the parser allocate.
        val huge = "x".repeat(ConfigCodec.MAX_IMPORT_BYTES + 1)
        assertTrue(rejection(huge).isNotEmpty())
    }

    @Test
    fun `a file at the size limit is not rejected for its size`() {
        val padded = envelope(""""note":"${"y".repeat(4_000)}"""")
        assertTrue(padded.toByteArray(Charsets.UTF_8).size <= ConfigCodec.MAX_IMPORT_BYTES)
        accepted(padded) // unknown keys are ignored, not fatal
    }

    @Test
    fun `malformed json is rejected without throwing`() {
        rejection("")
        rejection("not json at all")
        rejection("""{"kind":"aimlab-config",""") // truncated mid-write
        rejection("[]") // a top-level array, not an object
    }

    @Test
    fun `a file from another app is rejected on its kind`() {
        rejection("""{"kind":"something-else","schema":1}""")
        rejection("""{"schema":1}""") // no kind at all
    }

    @Test
    fun `a file from a different schema version is rejected in both directions`() {
        rejection("""{"kind":"${ConfigCodec.KIND}","schema":${ConfigCodec.SCHEMA_VERSION + 1}}""")
        rejection("""{"kind":"${ConfigCodec.KIND}","schema":${ConfigCodec.SCHEMA_VERSION - 1}}""")
        rejection("""{"kind":"${ConfigCodec.KIND}"}""") // missing
    }

    // --- Per-item validation ----------------------------------------------------------------------

    @Test
    fun `a weapon with no usable name is rejected`() {
        rejection(envelope(""""weapons":[{"id":1,"name":""}]"""))
        rejection(envelope(""""weapons":[{"id":1,"name":"   "}]""")) // whitespace is not a name
        rejection(envelope(""""weapons":[{"id":1}]"""))
    }

    @Test
    fun `two items sharing an id are rejected rather than silently overwriting`() {
        rejection(envelope(""""weapons":[{"id":4,"name":"A"},{"id":4,"name":"B"}]"""))
        rejection(envelope(""""sensitivities":[{"id":4,"name":"A"},{"id":4,"name":"B"}]"""))
        rejection(envelope(""""layouts":[{"id":4,"name":"A"},{"id":4,"name":"B"}]"""))
    }

    @Test
    fun `unsaved items sharing the placeholder id zero are allowed`() {
        // Zero means "not yet persisted"; the store assigns real ids on insert, so these do not collide.
        val bundle = accepted(envelope(""""weapons":[{"id":0,"name":"A"},{"id":0,"name":"B"}]"""))
        assertEquals(2, bundle.weapons.size)
    }

    @Test
    fun `a non-object inside an array is rejected`() {
        rejection(envelope(""""weapons":["just a string"]"""))
        rejection(envelope(""""layouts":[{"id":1,"name":"L","controls":[7]}]"""))
    }

    @Test
    fun `a control with an unknown role is rejected rather than defaulted`() {
        // Silently mapping an unknown role onto SHOOT would hand the user a layout they did not design.
        rejection(envelope(""""layouts":[{"id":1,"name":"L","controls":[{"role":"TELEPORT"}]}]"""))
        rejection(envelope(""""layouts":[{"id":1,"name":"L","controls":[{"x":0.5}]}]"""))
    }

    @Test
    fun `a layout that repeats a role is rejected`() {
        rejection(
            envelope(
                """"layouts":[{"id":1,"name":"L","controls":[{"role":"SHOOT"},{"role":"SHOOT"}]}]""",
            ),
        )
    }

    @Test
    fun `a layout claiming more controls than exist is rejected`() {
        val tooMany = (0..ControlRole.entries.size).joinToString(",") { """{"role":"SHOOT"}""" }
        rejection(envelope(""""layouts":[{"id":1,"name":"L","controls":[$tooMany]}]"""))
    }

    @Test
    fun `more items than the cap is rejected`() {
        val many = (1..ConfigCodec.MAX_ITEMS + 1).joinToString(",") { """{"id":$it,"name":"W$it"}""" }
        rejection(envelope(""""weapons":[$many]"""))
    }

    @Test
    fun `out-of-range numbers are clamped into the valid range instead of being trusted`() {
        // A hand-edited file asking for a 99999 RPM weapon is not rejected -- it is normalised, the same
        // way the editor would have clamped it. What must never happen is the raw value reaching the loop.
        val bundle = accepted(
            envelope(""""weapons":[{"id":1,"name":"Absurd","fireRateRpm":99999,"magazineSize":-5}]"""),
        )
        val weapon = bundle.weapons[0]
        assertTrue("fireRateRpm was $weapon", weapon.fireRateRpm in Weapon.MIN_RPM..Weapon.MAX_RPM)
        assertTrue("magazineSize was $weapon", weapon.magazineSize in 1..Weapon.MAX_MAG)
        assertEquals(weapon, weapon.normalised())
    }

    @Test
    fun `an out-of-range control is clamped to a usable size and opacity`() {
        val bundle = accepted(
            envelope(
                """"layouts":[{"id":1,"name":"L","controls":[{"role":"SHOOT","w":5.0,"h":0.001,"opacity":0}]}]""",
            ),
        )
        val control = bundle.layouts[0].controls[0]
        assertTrue(control.widthFraction <= ControlWidget.MAX_SIZE)
        assertTrue(control.heightFraction >= ControlWidget.MIN_SIZE)
        assertTrue(control.opacityPercent >= ControlWidget.MIN_OPACITY)
    }

    // --- Name sanitisation ------------------------------------------------------------------------

    @Test
    fun `control characters in an imported name are stripped before it becomes a domain object`() {
        // A bell and a backspace are invisible to whoever edited the file and are a name the editor could
        // never have produced. A newline in a single-line field turns a list row into a scrolling column,
        // so it becomes the space that separated the two words rather than being dropped outright.
        val bundle = accepted(
            envelope(""""weapons":[{"id":1,"name":"Ri\u0007f\u0008le"},{"id":2,"name":"Two\nLines"}]"""),
        )
        assertEquals("Rifle", bundle.weapons[0].name)
        assertEquals("Two Lines", bundle.weapons[1].name)
    }

    @Test
    fun `a bidi override in an imported name cannot reverse the UI drawn around it`() {
        // U+202E reverses the rendering of everything after it, including GameCore's own text on the same
        // row, so a hostile profile name would rewrite the label sitting next to it.
        val bundle = accepted(envelope(""""sensitivities":[{"id":1,"name":"Claw\u202Egnittes\u200F"}]"""))
        val name = bundle.sensitivities[0].name
        assertEquals("Clawgnittes", name)
        assertTrue("An override survived in $name", name.none { it == '\u202E' || it == '\u200F' })
    }

    @Test
    fun `a stack of combining marks on an imported name is cut to what a line box can hold`() {
        // Two hundred diacritics on one base character draw far outside the row, and in the training
        // overlay that means over the game.
        val zalgo = "A" + "\u0301".repeat(200)
        val bundle = accepted(envelope(""""weapons":[{"id":1,"name":"$zalgo"}]"""))
        assertEquals("A\u0301\u0301", bundle.weapons[0].name)
    }

    @Test
    fun `an over-long name is capped for every kind rather than carried into the app`() {
        // MAX_IMPORT_BYTES on its own would let a single name be half a megabyte, which is a layout pass
        // that never finishes once it reaches a fixed overlay label or a notification line.
        val long = "L".repeat(4_000)
        val bundle = accepted(
            envelope(
                """"weapons":[{"id":1,"name":"$long"}],"sensitivities":[{"id":1,"name":"$long"}],""" +
                    """"layouts":[{"id":1,"name":"$long"}]""",
            ),
        )
        assertTrue(bundle.weapons[0].name.length <= ConfigCodec.MAX_NAME_LENGTH)
        assertTrue(bundle.sensitivities[0].name.length <= ConfigCodec.MAX_NAME_LENGTH)
        // The layout's own stated limit, which is where the codec's cap comes from.
        assertTrue(bundle.layouts[0].name.length <= ControlLayout.MAX_NAME_LENGTH)
        assertTrue(bundle.weapons[0].name.startsWith("LLL"))
    }

    @Test
    fun `a name that sanitises away to nothing is rejected rather than given a placeholder`() {
        // A name of nothing but format and control characters parses fine and draws as empty. Inventing a
        // fallback would make this the one place decode produces data the file never contained, and would
        // present a corrupt file as a plausible one -- so it fails the same way a blank name always has.
        rejection(envelope(""""weapons":[{"id":1,"name":"\u202E\u200F"}]"""))
        rejection(envelope(""""sensitivities":[{"id":1,"name":"\u0000\u0007"}]"""))
        rejection(envelope(""""layouts":[{"id":1,"name":"\u0001\u001F"}]"""))
    }

    @Test
    fun `one bad entry rejects the whole file rather than importing the good ones`() {
        // A partial import would leave the library in a state the user never chose and cannot easily undo.
        val reason = rejection(
            envelope(""""weapons":[{"id":1,"name":"Good"},{"id":2,"name":""}]"""),
        )
        assertTrue(reason.isNotEmpty())
    }

    @Test
    fun `a rejection reason is human-readable and leaks no parser internals`() {
        val reason = rejection("""{"kind":"other","schema":1}""")
        assertTrue(reason.isNotEmpty())
        assertTrue(
            "Reason looked like a stack trace: $reason",
            !reason.contains("Exception") && !reason.contains("org.json"),
        )
    }
}
