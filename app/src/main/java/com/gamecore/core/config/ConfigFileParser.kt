package com.gamecore.core.config

/**
 * Turns the text of a game's config file into editable rows, and — just as importantly — turns edited rows
 * back into file text without disturbing a single byte the user did not touch (spec §A5).
 *
 * ## The formats, and why parsing is best-effort
 *
 * Games write settings in whatever shape they please. This parser recognises the two that map cleanly onto
 * a row-per-setting editor:
 *
 *  - [ConfigFormat.KEY_VALUE] — `key=value` / `key: value`, optionally grouped under `[section]` headers,
 *    the INI-family shape most native config files use.
 *  - [ConfigFormat.FLAT_JSON] — a single JSON object whose values are all primitives (string, number,
 *    boolean, null). Nesting is *not* flat, so an object or array value drops the whole file to raw text.
 *
 * Anything the parser cannot confidently structure becomes [ConfigFormat.RAW_TEXT]: one editable region
 * holding the whole file. Guessing wrong is worse than not guessing — misreading prose as `key=value` would
 * offer edits that corrupt the file on save — so a line that is neither blank, comment, section nor entry
 * disqualifies the key-value reading entirely.
 *
 * ## Round-trip by splicing, not re-emitting
 *
 * The parser never rebuilds a file from a model of it. It keeps the [original] text and records, per entry,
 * the exact character span its value occupies. [ParsedConfig.render] copies the original and splices new
 * values into those spans (highest offset first, so earlier edits do not shift later spans). An unedited
 * entry is never rewritten, so a render with no edits returns the original byte-for-byte — comments, blank
 * lines, key spelling, separators and trailing whitespace all survive untouched. That is the property the
 * round-trip tests pin down, and the property that makes a "save" safe on a file the user barely changed.
 */
enum class ConfigFormat { KEY_VALUE, FLAT_JSON, RAW_TEXT }

/** The JSON type of a [ConfigFormat.FLAT_JSON] value, so [ParsedConfig.render] can re-encode an edit correctly. */
enum class JsonValueType { STRING, NUMBER, BOOLEAN, NULL }

/** Raised when an edit cannot be encoded back into the file's format (e.g. non-numeric text for a JSON number). */
class InvalidConfigEditException(message: String) : IllegalArgumentException(message)

/**
 * One editable row. [valueStart]/[valueEnd] are the half-open character span of the value within
 * [ParsedConfig.original]; they are internal because callers edit by [key]/index, not by offset.
 */
data class ConfigEntry(
    /** Display key: the trimmed key for INI/JSON, or empty for the single raw-text region. */
    val key: String,
    /** Value as shown in the editor: trimmed for INI, decoded for JSON strings, the whole file for raw text. */
    val displayValue: String,
    /** INI section this entry sits under, or null for flat JSON / top-level / raw text. */
    val section: String?,
    /** For [ConfigFormat.FLAT_JSON] entries, the JSON type of the value; null otherwise. */
    val jsonType: JsonValueType?,
    internal val valueStart: Int,
    internal val valueEnd: Int,
)

/**
 * A parsed config file: its detected [format], the untouched [original] text, and the [entries] the editor
 * shows. Render the file back with [render].
 */
data class ParsedConfig(
    val format: ConfigFormat,
    val original: String,
    val entries: List<ConfigEntry>,
) {
    /**
     * Rebuild the file text applying [edits] (entry index -> new display value). Entries absent from [edits]
     * keep their original bytes. Splices run from the highest [ConfigEntry.valueStart] down so an earlier
     * splice never invalidates a later span. Throws [InvalidConfigEditException] if a JSON edit is not
     * encodable as its value's type.
     */
    fun render(edits: Map<Int, String>): String {
        if (edits.isEmpty()) return original
        val ordered = edits.keys.sortedByDescending { entries[it].valueStart }
        val sb = StringBuilder(original)
        for (index in ordered) {
            val entry = entries[index]
            sb.replace(entry.valueStart, entry.valueEnd, encodeValue(entry, edits.getValue(index)))
        }
        return sb.toString()
    }

    private fun encodeValue(entry: ConfigEntry, raw: String): String = when (format) {
        ConfigFormat.KEY_VALUE, ConfigFormat.RAW_TEXT -> raw
        ConfigFormat.FLAT_JSON -> encodeJsonValue(entry.jsonType!!, raw)
    }
}

/**
 * The entry point. Tries the structured formats in order of specificity — flat JSON (which either parses
 * whole or not at all), then key-value — and falls back to a single raw-text region when neither fits.
 */
object ConfigParser {

    fun parse(text: String): ParsedConfig {
        parseFlatJson(text)?.let { return it }
        // A file that opens with '{' is JSON-family. If it did not parse as flat JSON above it is nested or
        // malformed, and reading it as INI would misparse its first line into a junk `{"key"` row — so skip
        // straight to raw text. (A leading '[' is NOT treated this way: that is an ordinary INI section header.)
        if (!text.trimStart().startsWith("{")) {
            parseKeyValue(text)?.let { return it }
        }
        return ParsedConfig(
            format = ConfigFormat.RAW_TEXT,
            original = text,
            entries = listOf(
                ConfigEntry(
                    key = "",
                    displayValue = text,
                    section = null,
                    jsonType = null,
                    valueStart = 0,
                    valueEnd = text.length,
                ),
            ),
        )
    }
}

/**
 * Encode an edited [raw] display value back into its JSON [type]. Strings are always re-escaped; the other
 * types are validated against their grammar and inserted literally, because silently coercing "yes" into a
 * boolean or "1,5" into a number would write a value the game never agreed to.
 */
private fun encodeJsonValue(type: JsonValueType, raw: String): String = when (type) {
    JsonValueType.STRING -> encodeJsonString(raw)
    JsonValueType.NUMBER -> {
        if (!isJsonNumber(raw.trim())) throw InvalidConfigEditException("Not a valid number: \"$raw\"")
        raw.trim()
    }
    JsonValueType.BOOLEAN -> {
        val v = raw.trim()
        if (v != "true" && v != "false") throw InvalidConfigEditException("Expected true or false: \"$raw\"")
        v
    }
    JsonValueType.NULL -> {
        if (raw.trim() != "null") throw InvalidConfigEditException("This value can only be null")
        "null"
    }
}

/**
 * Parse [text] as INI / `key=value`, tracking [section] headers and recording each value's global character
 * span. Returns null — meaning "not this format" — if any non-blank, non-comment, non-section line lacks a
 * `=`/`:` separator, or if no entry is found at all. Being strict here is deliberate: a file that is only
 * partly key-value is safer edited as raw text than half-misread into rows.
 */
private fun parseKeyValue(text: String): ParsedConfig? {
    val entries = ArrayList<ConfigEntry>()
    var currentSection: String? = null
    var i = 0
    val n = text.length
    while (i < n) {
        val lineStart = i
        var j = lineStart
        while (j < n && text[j] != '\n') j++
        // Content excludes the newline and a CRLF's trailing '\r', so a value span never swallows the terminator.
        var contentEnd = j
        if (contentEnd > lineStart && text[contentEnd - 1] == '\r') contentEnd--
        val line = text.substring(lineStart, contentEnd)
        val trimmed = line.trim()

        when {
            trimmed.isEmpty() -> Unit
            trimmed[0] == '#' || trimmed[0] == ';' -> Unit
            trimmed[0] == '[' && trimmed.endsWith("]") && trimmed.length >= 3 ->
                currentSection = trimmed.substring(1, trimmed.length - 1).trim()
            else -> {
                val sep = firstSeparatorIndex(line)
                if (sep < 0) return null
                val key = line.substring(0, sep).trim()
                if (key.isEmpty()) return null
                val valueStart = lineStart + sep + 1
                entries.add(
                    ConfigEntry(
                        key = key,
                        displayValue = text.substring(valueStart, contentEnd).trim(),
                        section = currentSection,
                        jsonType = null,
                        valueStart = valueStart,
                        valueEnd = contentEnd,
                    ),
                )
            }
        }
        i = if (j < n) j + 1 else n
    }
    return if (entries.isEmpty()) null else ParsedConfig(ConfigFormat.KEY_VALUE, text, entries)
}

/** Index within [line] of the first `=` or `:` (whichever comes first), or -1 if the line has neither. */
private fun firstSeparatorIndex(line: String): Int {
    val eq = line.indexOf('=')
    val colon = line.indexOf(':')
    return when {
        eq >= 0 && colon >= 0 -> minOf(eq, colon)
        eq >= 0 -> eq
        else -> colon
    }
}

/**
 * Parse [text] as a flat JSON object. Returns null (so the caller can try key-value / raw) when the text is
 * not a JSON object, has a nested value, repeats a key, or has trailing junk — anything that a row-per-key
 * editor cannot represent unambiguously. Value spans cover the whole JSON literal (quotes included for
 * strings), so [ParsedConfig.render] replaces the entire literal when a value is edited.
 */
private fun parseFlatJson(text: String): ParsedConfig? = try {
    val scanner = FlatJsonScanner(text)
    scanner.skipWhitespace()
    if (scanner.atEnd() || scanner.peek() != '{') {
        null
    } else {
        val entries = scanner.parseObjectEntries()
        scanner.skipWhitespace()
        if (!scanner.atEnd()) null else ParsedConfig(ConfigFormat.FLAT_JSON, text, entries)
    }
} catch (e: JsonFormatException) {
    null
}

/** Thrown internally when [text] is not the flat JSON the editor accepts; [parseFlatJson] turns it into null. */
private class JsonFormatException : Exception()

/** A tiny hand-rolled scanner for flat JSON objects. It carries no dependency, so the config parser stays self-contained. */
private class FlatJsonScanner(private val s: String) {
    private var pos = 0

    fun atEnd(): Boolean = pos >= s.length
    fun peek(): Char = if (pos < s.length) s[pos] else throw JsonFormatException()
    private fun next(): Char = if (pos < s.length) s[pos++] else throw JsonFormatException()
    private fun expect(c: Char) { if (next() != c) throw JsonFormatException() }
    fun skipWhitespace() { while (pos < s.length && s[pos].isJsonWhitespace()) pos++ }

    /** Parse `{ "k": value, ... }` into entries; assumes the cursor is at the opening brace. */
    fun parseObjectEntries(): List<ConfigEntry> {
        val entries = ArrayList<ConfigEntry>()
        val seenKeys = HashSet<String>()
        expect('{')
        skipWhitespace()
        if (peek() == '}') { next(); return entries }
        while (true) {
            skipWhitespace()
            if (peek() != '"') throw JsonFormatException()
            val key = parseString()
            if (!seenKeys.add(key)) throw JsonFormatException()
            skipWhitespace(); expect(':'); skipWhitespace()
            entries.add(parseValueAsEntry(key))
            skipWhitespace()
            when (next()) {
                ',' -> continue
                '}' -> break
                else -> throw JsonFormatException()
            }
        }
        return entries
    }

    /** Parse a single JSON value, recording its literal span. Objects/arrays are rejected — this is *flat* JSON. */
    fun parseValueAsEntry(key: String): ConfigEntry {
        val valueStart = pos
        val display: String
        val type: JsonValueType
        when (peek()) {
            '"' -> { display = parseString(); type = JsonValueType.STRING }
            '{', '[' -> throw JsonFormatException()
            't' -> { expectLiteral("true"); display = "true"; type = JsonValueType.BOOLEAN }
            'f' -> { expectLiteral("false"); display = "false"; type = JsonValueType.BOOLEAN }
            'n' -> { expectLiteral("null"); display = "null"; type = JsonValueType.NULL }
            else -> { display = parseNumber(); type = JsonValueType.NUMBER }
        }
        return ConfigEntry(key, display, section = null, jsonType = type, valueStart = valueStart, valueEnd = pos)
    }

    private fun expectLiteral(lit: String) { for (ch in lit) expect(ch) }

    /** Consume the maximal run of number characters and confirm it is a valid JSON number. */
    private fun parseNumber(): String {
        val start = pos
        while (pos < s.length && s[pos].isNumberChar()) pos++
        val literal = s.substring(start, pos)
        if (!isJsonNumber(literal)) throw JsonFormatException()
        return literal
    }

    /** Decode a JSON string literal (cursor at the opening quote), honouring the standard escapes. */
    private fun parseString(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            when (val c = next()) {
                '"' -> return sb.toString()
                '\\' -> when (val e = next()) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> sb.append(readUnicodeEscape())
                    else -> throw JsonFormatException()
                }
                // A literal control character inside a string is invalid JSON; only escapes may carry them.
                else -> if (c.code < 0x20) throw JsonFormatException() else sb.append(c)
            }
        }
    }

    private fun readUnicodeEscape(): Char {
        var value = 0
        repeat(4) {
            value = value * 16 + when (val h = next()) {
                in '0'..'9' -> h - '0'
                in 'a'..'f' -> h - 'a' + 10
                in 'A'..'F' -> h - 'A' + 10
                else -> throw JsonFormatException()
            }
        }
        return value.toChar()
    }


}

/** JSON insignificant whitespace: space, tab, line feed, carriage return. */
private fun Char.isJsonWhitespace(): Boolean = this == ' ' || this == '\t' || this == '\n' || this == '\r'

/** Characters that may appear in a JSON number literal, used to bound the greedy scan before validation. */
private fun Char.isNumberChar(): Boolean =
    this in '0'..'9' || this == '-' || this == '+' || this == '.' || this == 'e' || this == 'E'

/** JSON number grammar: optional sign, no leading zeros, optional fraction, optional exponent. */
private val JSON_NUMBER = Regex("""-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?""")

/** True if [s] is a valid JSON number (rejects a leading `+`, leading zeros, or a bare trailing dot). */
private fun isJsonNumber(s: String): Boolean = JSON_NUMBER.matches(s)

/** Encode [value] as a JSON string literal — quotes included — escaping exactly what JSON requires. */
private fun encodeJsonString(value: String): String {
    val sb = StringBuilder(value.length + 2)
    sb.append('"')
    for (c in value) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    sb.append('"')
    return sb.toString()
}





