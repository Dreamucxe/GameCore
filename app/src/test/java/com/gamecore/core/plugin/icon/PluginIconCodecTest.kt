package com.gamecore.core.plugin.icon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `icon.json` trust boundary.
 *
 * JUnit4 idiom (`:app` module): `assertEquals(message, expected, actual)` — the message is FIRST.
 *
 * Two things are being proved here. The ordinary codec contract — unreadable input produces a typed
 * rejection and never a half-trusted spec — and the one rule this section exists for: a slot the
 * host does not ship is refused outright rather than quietly replaced with the default icon, because
 * a plugin that asked for an icon and silently got another one would have no way to notice.
 */
class PluginIconCodecTest {

    private fun parsed(raw: String): PluginIconSpec =
        when (val r = PluginIconCodec.decode(raw)) {
            is PluginIconCodec.Result.Parsed -> r.spec
            is PluginIconCodec.Result.Rejected ->
                throw AssertionError("expected a parse, got ${r.reason} ${r.detail}")
        }

    private fun rejected(raw: String?): PluginIconCodec.Reason =
        when (val r = PluginIconCodec.decode(raw)) {
            is PluginIconCodec.Result.Parsed -> throw AssertionError("expected a rejection, got $r")
            is PluginIconCodec.Result.Rejected -> r.reason
        }

    @Test
    fun a_slot_alone_is_a_valid_icon() {
        val spec = parsed("""{"slot":"glitch"}""")
        assertEquals("slot", AppIconSlot.GLITCH, spec.slot)
        assertNull("no artwork", spec.artworkPath)
        assertNull("no label", spec.label)
        assertFalse("no artwork", spec.hasArtwork)
    }

    @Test
    fun every_slot_the_app_ships_round_trips() {
        for (slot in AppIconSlot.entries) {
            val spec = parsed("""{"slot":"${slot.id}"}""")
            assertEquals("slot ${slot.id}", slot, spec.slot)
        }
    }

    @Test
    fun slot_ids_are_lowercase_and_unique() {
        val ids = AppIconSlot.entries.map { it.id }
        assertEquals("ids are unique", ids.size, ids.toSet().size)
        for (id in ids) {
            assertEquals("id \"$id\" is already lowercase", id, id.lowercase())
        }
    }

    @Test
    fun artwork_under_assets_and_a_label_parse() {
        val spec = parsed("""{"slot":"neon","artwork":"assets/icon.webp","label":"  Neon   Night  "}""")
        assertEquals("artwork", "assets/icon.webp", spec.artworkPath)
        assertTrue("has artwork", spec.hasArtwork)
        assertEquals("label is sanitised, not passed through", "Neon Night", spec.label)
    }

    @Test
    fun a_slot_that_would_not_resolve_is_refused_rather_than_defaulted() {
        assertEquals("unknown slot", PluginIconCodec.Reason.BAD_SLOT, rejected("""{"slot":"rainbow"}"""))
        assertEquals("empty slot", PluginIconCodec.Reason.BAD_SLOT, rejected("""{"slot":""}"""))
        assertEquals("non-string slot", PluginIconCodec.Reason.BAD_SLOT, rejected("""{"slot":7}"""))
    }

    @Test
    fun an_absent_slot_is_a_missing_field_not_a_default() {
        assertEquals(
            "artwork with no slot",
            PluginIconCodec.Reason.MISSING_FIELD,
            rejected("""{"artwork":"assets/icon.png"}"""),
        )
    }

    @Test
    fun unknown_keys_are_rejected_so_a_future_field_never_loads_as_nothing() {
        assertEquals(
            "unknown key",
            PluginIconCodec.Reason.UNKNOWN_KEY,
            rejected("""{"slot":"mono","animation":"pulse"}"""),
        )
    }

    @Test
    fun empty_and_malformed_input_are_told_apart() {
        assertEquals("null", PluginIconCodec.Reason.EMPTY, rejected(null))
        assertEquals("blank", PluginIconCodec.Reason.EMPTY, rejected("   "))
        assertEquals("truncated json", PluginIconCodec.Reason.MALFORMED, rejected("""{"slot":"""))
        assertEquals("an array is not an icon", PluginIconCodec.Reason.MALFORMED, rejected("""["glitch"]"""))
    }

    @Test
    fun artwork_paths_outside_the_one_admitted_shape_are_refused() {
        val bad = listOf(
            "assets/../secret.png",
            "../assets/icon.png",
            "/assets/icon.png",
            "assets/sub/icon.png",
            "assets/icon.gif",
            "assets/icon",
            "res/drawable/icon.png",
            "assets/.hidden.png",
        )
        for (path in bad) {
            assertEquals(
                "path \"$path\" must be refused",
                PluginIconCodec.Reason.BAD_ARTWORK,
                rejected("""{"slot":"mono","artwork":"$path"}"""),
            )
        }
    }

    @Test
    fun an_overlong_artwork_path_is_refused_before_it_is_looked_up() {
        val long = "assets/" + "a".repeat(PluginIconSpec.MAX_ARTWORK_PATH) + ".png"
        assertEquals(
            "overlong path",
            PluginIconCodec.Reason.BAD_ARTWORK,
            rejected("""{"slot":"mono","artwork":"$long"}"""),
        )
    }

    @Test
    fun a_label_that_sanitises_to_nothing_is_refused_and_a_long_one_is_truncated() {
        assertEquals(
            "control characters only",
            PluginIconCodec.Reason.BAD_LABEL,
            rejected("""{"slot":"mono","label":"\u0000\u0007"}"""),
        )
        val long = parsed("""{"slot":"mono","label":"${"x".repeat(200)}"}""")
        assertEquals("truncated to the cap", PluginIconSpec.MAX_LABEL, long.label!!.length)
    }

    @Test
    fun artwork_is_accepted_only_when_its_bytes_are_a_png_or_a_webp() {
        assertTrue("png magic", PluginIconArtwork.isAcceptable(PNG))
        assertTrue("webp magic", PluginIconArtwork.isAcceptable(WEBP))
        assertFalse("empty", PluginIconArtwork.isAcceptable(ByteArray(0)))
        assertFalse("null", PluginIconArtwork.isAcceptable(null))
        assertFalse("jpeg magic", PluginIconArtwork.isAcceptable(JPEG))
        assertFalse("a truncated png header", PluginIconArtwork.isAcceptable(PNG.copyOf(4)))
    }

    @Test
    fun artwork_over_the_byte_cap_is_refused() {
        val huge = PNG + ByteArray(PluginIconArtwork.MAX_BYTES)
        assertFalse("over the cap", PluginIconArtwork.isAcceptable(huge))
        assertTrue("exactly at the cap is fine", PluginIconArtwork.isAcceptable(PNG.copyOf(PluginIconArtwork.MAX_BYTES)))
    }

    private companion object {
        /** `\x89PNG\r\n\x1a\n` — the eight bytes every PNG starts with. */
        val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01)

        /** `RIFF`, a size, then `WEBP`. */
        val WEBP = byteArrayOf(
            0x52, 0x49, 0x46, 0x46, 0x24, 0x00, 0x00, 0x00,
            0x57, 0x45, 0x42, 0x50, 0x56, 0x50, 0x38, 0x20,
        )

        /** `FF D8 FF` — a JPEG, which the host will not decode for this. */
        val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
    }
}
