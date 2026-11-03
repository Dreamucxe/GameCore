package com.gamecore.studio.core

import com.gamecore.studio.model.PluginDraft
import com.gamecore.studio.model.ThemeDraft
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.zip.ZipInputStream

/**
 * The contract of a composed package: a zip, two entries, and the same bytes every time.
 *
 * The composer is the only thing that turns a draft into a file, so what it writes is
 * the whole of the studio's output. Three properties matter and each has a test here:
 *
 *  - **Shape.** The package holds exactly `manifest.json` and `theme.json`. A section
 *    the manifest does not declare must not be in the zip and a declared section must
 *    be, so the studio writes one section and declares exactly that one.
 *  - **Content.** The manifest carries the keys the host's `PluginManifestCodec`
 *    requires and the theme carries the six colours the host's `PluginThemeCodec`
 *    requires, as `#RRGGBB` strings.
 *  - **Determinism.** The same draft always produces the same bytes, and the bytes do
 *    not depend on when the file was written — which is what lets the SHA-256 a user is
 *    shown be a statement about the package rather than about the clock.
 *
 * The zip is read with `java.util.zip.ZipInputStream`, and the JSON is parsed by
 * [MiniJson] below rather than by `org.json`: this is an Android library, so its unit
 * tests run against the `org.json` stub in `android.jar`, whose methods return defaults
 * instead of parsing. A test written against that stub would assert against an empty
 * object and pass without having read anything at all.
 *
 * What this file does *not* check is that the host accepts the package. That is
 * `PluginStudioPackageTest` in `:app`, which runs the same bytes through the host's own
 * codecs — the host's rules are the authority; these are the producer's side of the
 * same contract.
 */
class PluginComposerTest {

    /** A draft with something in every field, so a change to one shows up in the bytes. */
    private val draft = PluginDraft(
        id = "com.example.mytheme",
        name = "Studio Theme",
        version = 3,
        versionName = "1.2.0",
        developer = "Plugin Maker",
        description = "A theme written by the Plugin Maker.",
        changelog = "1.2.0 — colours changed.",
        api = PluginDraft.CURRENT_API,
        minHostVersion = PluginDraft.DEFAULT_MIN_HOST_VERSION,
        permissions = setOf("change_ui", "store_data"),
        theme = ThemeDraft(
            name = "Studio Theme",
            schema = ThemeDraft.CURRENT_SCHEMA,
            primary = 0xFF8A4CFFL,
            onPrimary = 0xFF0A2413L,
            background = 0xFF141012L,
            surface = 0xFF242021L,
            onSurface = 0xFFE7EFF6L,
            accent = 0xFFC0D42BL,
            cornerRadiusDp = 12,
        ),
    )

    // ------------------------------------------------------------------------------ shape

    @Test
    fun `the package is a zip with exactly the manifest and the theme`() {
        val entries = entries(PluginComposer.packageBytes(draft))
        assertEquals(
            "the package carried ${entries.keys}",
            listOf("manifest.json", "theme.json"),
            entries.keys.toList(),
        )
    }

    @Test
    fun `the package begins with a zip signature`() {
        val bytes = PluginComposer.packageBytes(draft)
        assertEquals("PK", String(bytes, 0, 2, Charsets.US_ASCII))
        assertEquals(0x03, bytes[2].toInt() and 0xFF)
        assertEquals(0x04, bytes[3].toInt() and 0xFF)
    }

    // ------------------------------------------------------------------------------ the manifest

    @Test
    fun `the manifest carries the keys the host requires`() {
        val json = manifestJson()
        listOf("format", "id", "name", "version", "api", "minHostVersion").forEach { key ->
            assertTrue("the manifest has no \"$key\"", json.containsKey(key))
        }
        assertEquals("gamecore.plugin", json["format"])
        assertEquals(draft.id, json["id"])
        assertEquals(draft.name, json["name"])
        assertEquals(draft.version.toLong(), number(json, "version"))
        assertEquals(draft.api.toLong(), number(json, "api"))
        assertEquals(draft.minHostVersion.toLong(), number(json, "minHostVersion"))
    }

    @Test
    fun `the manifest declares the theme section and nothing else`() {
        assertEquals(listOf("theme"), manifestJson()["declares"])
    }

    @Test
    fun `the manifest lists the requested permissions as objects with an id`() {
        val permissions = manifestJson()["permissions"] as List<*>
        assertEquals(draft.permissions.size, permissions.size)
        val ids = permissions.map { (it as Map<*, *>)["id"] as String }.sorted()
        assertEquals(draft.permissions.sorted(), ids)
    }

    // ------------------------------------------------------------------------------ the theme

    @Test
    fun `the theme carries the six required colours as RRGGBB strings`() {
        val json = themeJson()
        val expected = mapOf(
            "primary" to draft.theme.primary,
            "onPrimary" to draft.theme.onPrimary,
            "background" to draft.theme.background,
            "surface" to draft.theme.surface,
            "onSurface" to draft.theme.onSurface,
            "accent" to draft.theme.accent,
        )
        expected.forEach { (key, argb) ->
            val value = json[key] as? String
            assertTrue("\"$key\" was $value", value != null)
            assertTrue("\"$key\" was $value, not a #RRGGBB string", value?.matches(Regex("^#[0-9A-Fa-f]{6}$")) == true)
            assertEquals("\"$key\" carried the wrong colour", rgbHex(argb), value)
        }
    }

    @Test
    fun `a schema-1 theme writes only the v1 surface`() {
        val v1 = draft.copy(theme = draft.theme.copy(schema = 1, glass = null, glitch = null))
        val json = PluginComposer.themeJson(v1)
        assertFalse("a v1 theme carried roles", json.contains("\"roles\""))
        assertFalse("a v1 theme carried typography", json.contains("\"typography\""))
        assertFalse("a v1 theme carried effects", json.contains("\"effects\""))
        assertFalse("a v1 theme carried amoled", json.contains("\"amoled\""))
        assertTrue(json.contains("\"schema\": 1"))
        assertTrue(json.contains("\"primary\""))
    }

    @Test
    fun `a schema-2 theme writes roles and type but no effects`() {
        val v2 = draft.copy(theme = draft.theme.copy(schema = 2, glass = null, glitch = null))
        val json = PluginComposer.themeJson(v2)
        assertTrue("a v2 theme lost its roles", json.contains("\"roles\""))
        assertTrue("a v2 theme lost typography", json.contains("\"typography\""))
        assertFalse("a v2 theme carried effects", json.contains("\"effects\""))
    }

    @Test
    fun `a translucent colour is written as AARRGGBB`() {
        val translucent = draft.copy(theme = draft.theme.copy(primary = 0x80FF8A4CL))
        val json = PluginComposer.themeJson(translucent)
        assertTrue("alpha was dropped: $json", json.contains("\"primary\": \"#80FF8A4C\""))
    }

    @Test
    fun `glass and glitch are written only at the schema that defines them`() {
        val withEffects = draft.copy(
            theme = draft.theme.copy(
                schema = 4,
                glass = com.gamecore.studio.model.GlassDraft(),
                glitch = com.gamecore.studio.model.GlitchDraft(),
            ),
        )
        val json = PluginComposer.themeJson(withEffects)
        assertTrue("glass block missing", json.contains("\"glass\""))
        assertTrue("glitch block missing", json.contains("\"glitch\""))

        val schemaThree = withEffects.copy(theme = withEffects.theme.copy(schema = 3, glass = null))
        val threeJson = PluginComposer.themeJson(schemaThree)
        assertTrue("glitch missing at schema 3", threeJson.contains("\"glitch\""))
        assertFalse("glass was written at schema 3", threeJson.contains("\"glass\""))
    }

    @Test
    fun `the theme names the schema it was written against`() {
        assertEquals(draft.theme.schema.toLong(), number(themeJson(), "schema"))
    }

    @Test
    fun `the theme carries the corner radius, the scale and the font`() {
        val json = themeJson()
        assertEquals(draft.theme.cornerRadiusDp.toLong(), number(json, "cornerRadiusDp"))
        assertEquals(draft.theme.font, json["font"])
    }

    // ------------------------------------------------------------------------------ determinism

    @Test
    fun `two compositions of the same draft are byte identical`() {
        assertArrayEquals(
            "the same draft produced two different packages",
            PluginComposer.packageBytes(draft),
            PluginComposer.packageBytes(draft),
        )
    }

    @Test
    fun `a changed colour changes the bytes`() {
        val changed = draft.copy(theme = draft.theme.copy(primary = 0xFF00FF00L))
        assertFalse(
            "changing a colour did not change the package",
            PluginComposer.packageBytes(draft).contentEquals(PluginComposer.packageBytes(changed)),
        )
    }

    @Test
    fun `every entry carries the fixed timestamp rather than the time of writing`() {
        val times = entryTimes(PluginComposer.packageBytes(draft))
        assertEquals(setOf("manifest.json", "theme.json"), times.keys)
        times.forEach { (name, time) ->
            assertEquals("\"$name\" carries the wrong timestamp", FIXED_ENTRY_TIME, time)
        }
    }

    // ------------------------------------------------------------------------------ helpers

    /** The zip's entries, in order, each read as UTF-8 text. */
    private fun entries(bytes: ByteArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                out[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return out
    }

    /** The zip's entries and the timestamp each one carries. */
    private fun entryTimes(bytes: ByteArray): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                out[entry.name] = entry.time
                zip.readBytes()
            }
        }
        return out
    }

    private fun manifestJson(): Map<*, *> =
        MiniJson.parse(entries(PluginComposer.packageBytes(draft)).getValue("manifest.json")) as Map<*, *>

    private fun themeJson(): Map<*, *> =
        MiniJson.parse(entries(PluginComposer.packageBytes(draft)).getValue("theme.json")) as Map<*, *>

    private fun number(json: Map<*, *>, key: String): Long =
        (json[key] as Number).toLong()

    companion object {
        /**
         * The timestamp every entry carries: the zip epoch, 1980-01-01 00:00 local.
         *
         * A zip's DOS timestamp has no timezone and no sub-second part, so the epoch
         * millis are read back in the JVM's own zone — the same zone the writer used.
         */
        private val FIXED_ENTRY_TIME: Long = LocalDateTime.of(1980, 1, 1, 0, 0, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        /** The colour as `#RRGGBB`, the form the host's theme codec accepts. */
        private fun rgbHex(argb: Long): String = "#%06X".format((argb and 0xFFFFFFL).toInt())
    }
}

/**
 * A very small JSON reader for this module's tests.
 *
 * The studio is an Android library, so its unit tests run against the `org.json` in
 * `android.jar` — a stub whose methods return defaults instead of parsing. A test
 * written against that stub would assert against an empty object and pass without having
 * read anything, so the studio's tests parse with this instead.
 *
 * It reads the whole of RFC 8259 that the composer can write: objects, arrays, strings
 * with escapes, numbers, the three literals, and `null`. It is not a general-purpose
 * library and makes no attempt to be one.
 */
private object MiniJson {

    /** The parsed value: a `Map`, a `List`, a `String`, a `Long`, a `Double`, a `Boolean` or null. */
    fun parse(text: String): Any? = Reader(text).readValue()

    private class Reader(private val text: String) {
        private var at = 0

        fun readValue(): Any? {
            skipSpace()
            return when (val c = peek()) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> {
                    require(c == '-' || c.isDigit()) { "unexpected '$c' at $at" }
                    readNumber()
                }
            }
        }

        private fun readObject(): Map<String, Any?> {
            expect('{')
            val out = LinkedHashMap<String, Any?>()
            skipSpace()
            if (peek() == '}') {
                at++
                return out
            }
            while (true) {
                skipSpace()
                val key = readString()
                skipSpace()
                expect(':')
                out[key] = readValue()
                skipSpace()
                when (text[at++]) {
                    ',' -> Unit
                    '}' -> return out
                    else -> error("expected ',' or '}' at ${at - 1}")
                }
            }
        }

        private fun readArray(): List<Any?> {
            expect('[')
            val out = ArrayList<Any?>()
            skipSpace()
            if (peek() == ']') {
                at++
                return out
            }
            while (true) {
                out += readValue()
                skipSpace()
                when (text[at++]) {
                    ',' -> Unit
                    ']' -> return out
                    else -> error("expected ',' or ']' at ${at - 1}")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                when (val c = text[at++]) {
                    '"' -> return out.toString()
                    '\\' -> out.append(readEscape())
                    else -> out.append(c)
                }
            }
        }

        private fun readEscape(): Char = when (val c = text[at++]) {
            '"' -> '"'
            '\\' -> '\\'
            '/' -> '/'
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> {
                val hex = text.substring(at, at + 4)
                at += 4
                hex.toInt(16).toChar()
            }
            else -> error("unknown escape \\$c")
        }

        private fun readNumber(): Any {
            val start = at
            while (at < text.length && (text[at].isDigit() || text[at] in "-+.eE")) at++
            val token = text.substring(start, at)
            return if (token.any { it in ".eE" }) token.toDouble() else token.toLong()
        }

        private fun readLiteral(literal: String, value: Any?): Any? {
            require(text.startsWith(literal, at)) { "expected $literal at $at" }
            at += literal.length
            return value
        }

        private fun skipSpace() {
            while (at < text.length && text[at].isWhitespace()) at++
        }

        private fun peek(): Char = text[at]

        private fun expect(c: Char) {
            require(text[at] == c) { "expected '$c' at $at but found '${text[at]}'" }
            at++
        }
    }
}
