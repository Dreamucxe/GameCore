package com.gamecore.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The config parser, whose contract is two-sided: it must read the common config shapes into editable rows,
 * and it must write edits back without disturbing a byte the user did not touch (spec §A5).
 *
 * The round-trip property is the one that makes "save" safe on a file a user barely changed, so it gets the
 * most attention here: comments, blank lines, section headers, key spelling, separators, CRLF terminators
 * and trailing whitespace must all survive an edit to some *other* line. The format-detection tests guard
 * the other direction — that the parser only claims KEY_VALUE / FLAT_JSON when it can round-trip them, and
 * falls back to raw text (one editable region) the moment it cannot.
 */
class ConfigFileParserTest {

    private fun ParsedConfig.indexOf(key: String): Int =
        entries.indexOfFirst { it.key == key }.also { assertTrue("no entry '$key' in $entries", it >= 0) }

    // ---- key=value / INI ----

    @Test
    fun `equals-separated pairs parse into entries`() {
        val parsed = ConfigParser.parse("graphics=high\nfps=60")
        assertEquals(ConfigFormat.KEY_VALUE, parsed.format)
        assertEquals("high", parsed.entries[parsed.indexOf("graphics")].displayValue)
        assertEquals("60", parsed.entries[parsed.indexOf("fps")].displayValue)
    }

    @Test
    fun `colon-separated pairs parse and values are trimmed for display`() {
        val parsed = ConfigParser.parse("name :  Ada \nrole: dev")
        assertEquals(ConfigFormat.KEY_VALUE, parsed.format)
        assertEquals("Ada", parsed.entries[parsed.indexOf("name")].displayValue)
        assertEquals("dev", parsed.entries[parsed.indexOf("role")].displayValue)
    }

    @Test
    fun `ini section headers attach to the entries beneath them`() {
        val parsed = ConfigParser.parse("[gfx]\nquality=high\n[audio]\nvolume=5")
        assertEquals("gfx", parsed.entries[parsed.indexOf("quality")].section)
        assertEquals("audio", parsed.entries[parsed.indexOf("volume")].section)
    }

    // ---- round-trip fidelity ----

    @Test
    fun `rendering with no edits returns the original untouched`() {
        val original = "# header comment\n\n[gfx]\nquality = high  \n; trailing note\nfps=60\n"
        val parsed = ConfigParser.parse(original)
        assertEquals(original, parsed.render(emptyMap()))
    }

    @Test
    fun `editing one value leaves every other byte untouched`() {
        val parsed = ConfigParser.parse("a=1\nb=2\nc=3")
        val out = parsed.render(mapOf(parsed.indexOf("b") to "20"))
        assertEquals("a=1\nb=20\nc=3", out)
    }

    @Test
    fun `a comment sitting between entries survives an edit to a neighbour`() {
        val original = "# keep me\nname=old\n; and me\nother=x\n"
        val parsed = ConfigParser.parse(original)
        val out = parsed.render(mapOf(parsed.indexOf("name") to "new"))
        assertEquals("# keep me\nname=new\n; and me\nother=x\n", out)
    }

    @Test
    fun `crlf line endings are preserved when a value changes`() {
        val parsed = ConfigParser.parse("a=1\r\nb=2\r\n")
        val out = parsed.render(mapOf(parsed.indexOf("a") to "9"))
        assertEquals("a=9\r\nb=2\r\n", out)
    }

    @Test
    fun `an empty value is a valid editable entry`() {
        val parsed = ConfigParser.parse("token=\n")
        assertEquals("", parsed.entries[parsed.indexOf("token")].displayValue)
        assertEquals("token=secret\n", parsed.render(mapOf(parsed.indexOf("token") to "secret")))
    }

    @Test
    fun `editing several values at once keeps every span aligned`() {
        // Splices run highest-offset-first; a longer replacement early in the file must not shift the
        // later spans. Grow the first value and shrink the last in the same render to prove it.
        val parsed = ConfigParser.parse("a=1\nb=2\nc=3\n")
        val out = parsed.render(
            mapOf(
                parsed.indexOf("a") to "1000",
                parsed.indexOf("c") to "",
            ),
        )
        assertEquals("a=1000\nb=2\nc=\n", out)
    }

    @Test
    fun `the colon separator and its spacing are preserved on edit`() {
        // The value span starts right after the ':' , so the "name : " prefix including the space is
        // untouched; only the value text is replaced (leading space before "Ada" is part of the span).
        val parsed = ConfigParser.parse("name : Ada\n")
        val out = parsed.render(mapOf(parsed.indexOf("name") to "Bob"))
        assertEquals("name :Bob\n", out)
    }

    @Test
    fun `a value with an inline separator character keeps only its first split point`() {
        // The first '=' splits key from value; a ':' later in the value is ordinary value text.
        val parsed = ConfigParser.parse("url=http://x:8080\n")
        assertEquals("http://x:8080", parsed.entries[parsed.indexOf("url")].displayValue)
        assertEquals("url=https://y\n", parsed.render(mapOf(parsed.indexOf("url") to "https://y")))
    }

    // ---- raw-text fallback ----

    @Test
    fun `prose with no separators is one raw-text region`() {
        val text = "This is just a readme.\nNothing to edit here.\n"
        val parsed = ConfigParser.parse(text)
        assertEquals(ConfigFormat.RAW_TEXT, parsed.format)
        assertEquals(1, parsed.entries.size)
        assertEquals(text, parsed.entries[0].displayValue)
    }

    @Test
    fun `a file that is only partly key-value falls back to raw text rather than half-parsing`() {
        // The second line has no separator, so trusting the first as an entry would be unsafe.
        val parsed = ConfigParser.parse("valid=1\nthis line has no separator\n")
        assertEquals(ConfigFormat.RAW_TEXT, parsed.format)
    }

    @Test
    fun `raw text renders back byte-for-byte when its single region is unedited`() {
        val text = "free\nform\ntext"
        val parsed = ConfigParser.parse(text)
        assertEquals(text, parsed.render(emptyMap()))
    }

    @Test
    fun `raw text is fully editable as one region`() {
        val parsed = ConfigParser.parse("old whole file")
        assertEquals("new whole file", parsed.render(mapOf(0 to "new whole file")))
    }

    // ---- flat JSON ----

    @Test
    fun `a flat json object parses into typed entries`() {
        val parsed = ConfigParser.parse("""{"name":"Ada","fps":60,"vsync":true,"mods":null}""")
        assertEquals(ConfigFormat.FLAT_JSON, parsed.format)
        assertEquals("Ada", parsed.entries[parsed.indexOf("name")].displayValue)
        assertEquals(JsonValueType.STRING, parsed.entries[parsed.indexOf("name")].jsonType)
        assertEquals(JsonValueType.NUMBER, parsed.entries[parsed.indexOf("fps")].jsonType)
        assertEquals(JsonValueType.BOOLEAN, parsed.entries[parsed.indexOf("vsync")].jsonType)
        assertEquals(JsonValueType.NULL, parsed.entries[parsed.indexOf("mods")].jsonType)
    }

    @Test
    fun `json string values are decoded for display and re-escaped on save`() {
        val parsed = ConfigParser.parse("""{"path":"c:\\games"}""")
        assertEquals("""c:\games""", parsed.entries[parsed.indexOf("path")].displayValue)
        // Editing to a value containing a quote and a newline re-escapes both, quotes included.
        val out = parsed.render(mapOf(parsed.indexOf("path") to "a\"b\nc"))
        assertEquals("""{"path":"a\"b\nc"}""", out)
    }

    @Test
    fun `an empty json object is flat json with no entries`() {
        val parsed = ConfigParser.parse("{}")
        assertEquals(ConfigFormat.FLAT_JSON, parsed.format)
        assertTrue(parsed.entries.isEmpty())
        assertEquals("{}", parsed.render(emptyMap()))
    }

    @Test
    fun `json whitespace and key order are preserved across an edit`() {
        val original = "{\n  \"a\": 1,\n  \"b\": 2\n}"
        val parsed = ConfigParser.parse(original)
        assertEquals(ConfigFormat.FLAT_JSON, parsed.format)
        assertEquals("{\n  \"a\": 1,\n  \"b\": 20\n}", parsed.render(mapOf(parsed.indexOf("b") to "20")))
    }

    // ---- json edit validation ----

    @Test
    fun `editing a json number with non-numeric text is rejected`() {
        val parsed = ConfigParser.parse("""{"fps":60}""")
        assertThrows(InvalidConfigEditException::class.java) {
            parsed.render(mapOf(parsed.indexOf("fps") to "sixty"))
        }
    }

    @Test
    fun `a valid numeric edit to a json number is written literally`() {
        val parsed = ConfigParser.parse("""{"fps":60}""")
        assertEquals("""{"fps":30}""", parsed.render(mapOf(parsed.indexOf("fps") to "30")))
    }

    @Test
    fun `editing a json boolean to something other than true or false is rejected`() {
        val parsed = ConfigParser.parse("""{"vsync":true}""")
        assertThrows(InvalidConfigEditException::class.java) {
            parsed.render(mapOf(parsed.indexOf("vsync") to "yes"))
        }
        assertEquals("""{"vsync":false}""", parsed.render(mapOf(parsed.indexOf("vsync") to "false")))
    }

    @Test
    fun `editing a json null to anything but null is rejected`() {
        val parsed = ConfigParser.parse("""{"mods":null}""")
        assertThrows(InvalidConfigEditException::class.java) {
            parsed.render(mapOf(parsed.indexOf("mods") to "[]"))
        }
    }

    // ---- json-family files that are not flat: raw text, never misread as INI ----

    @Test
    fun `a nested json object drops to raw text, not a junk ini row`() {
        val parsed = ConfigParser.parse("""{"a":{"b":1}}""")
        assertEquals(ConfigFormat.RAW_TEXT, parsed.format)
        assertEquals(1, parsed.entries.size)
    }

    @Test
    fun `a json array value drops the whole file to raw text`() {
        val parsed = ConfigParser.parse("""{"tags":["a","b"]}""")
        assertEquals(ConfigFormat.RAW_TEXT, parsed.format)
    }

    @Test
    fun `malformed json opening with a brace is raw text, not misparsed as key-value`() {
        val parsed = ConfigParser.parse("""{"oops": }""")
        assertEquals(ConfigFormat.RAW_TEXT, parsed.format)
    }

    @Test
    fun `a duplicate json key disqualifies flat json and falls back to raw text`() {
        val parsed = ConfigParser.parse("""{"a":1,"a":2}""")
        assertEquals(ConfigFormat.RAW_TEXT, parsed.format)
    }



}
