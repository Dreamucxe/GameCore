package com.gamecore.studio.core

import com.gamecore.studio.model.GlassDraft
import com.gamecore.studio.model.GlitchDraft
import com.gamecore.studio.model.PluginDraft
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Turns a [PluginDraft] into a `.gcplugin` package.
 *
 * The format is data, not code: a zip holding `manifest.json` and whichever supported section
 * files the draft declares. The studio writes it from the documented rules rather than from
 * GameCore's code — the maker is a separate app and must not need GameCore's source — and `:app`
 * carries a test that decodes what this writes with the host's own codecs, so the two cannot drift.
 *
 * The output is **reproducible**: the same draft always produces the same bytes, so the
 * SHA-256 a user is shown is a statement about the package and not about the clock. That is why
 * the entries carry a fixed timestamp instead of the time of writing, and why permissions are
 * sorted rather than left in insertion order.
 */
object PluginComposer {

    /** The legacy theme-only entry list. Use [entryNames] for a draft's dynamic package shape. */
    val ENTRIES: List<String> = listOf("manifest.json", "theme.json")

    /** The entries [packageBytes] will write, in deterministic archive order. */
    fun entryNames(draft: PluginDraft): List<String> = packageEntries(draft).map { it.first }

    /**
     * The zip epoch, as a DOS timestamp: 1980-01-01 00:00 *local* time.
     *
     * `ZipEntry.time` takes epoch milliseconds and converts them with the default time zone,
     * while the archive stores the conversion back to local time — so the two cancel out and
     * the bytes are the same in every time zone. The value is fixed rather than "now" so two
     * runs over the same draft agree byte for byte and the SHA-256 the user copies describes
     * the package, not the moment it happened to be written.
     */
    private val FIXED_TIME_MILLIS: Long =
        LocalDateTime.of(1980, 1, 1, 0, 0, 0)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    /** The manifest, as the JSON that goes into the package. */
    fun manifestJson(draft: PluginDraft): String {
        val scopesByPermission = draft.effectivePermissionScopes()
        val permissions = draft.effectivePermissions().sorted()
        val declares = draft.sections.declaredSections().sorted()
        return buildString {
        appendLine("{")
        appendLine("  \"format\": \"gamecore.plugin\",")
        appendLine("  \"id\": ${quote(draft.id.trim())},")
        appendLine("  \"name\": ${quote(draft.name.trim())},")
        appendLine("  \"version\": ${draft.version},")
        appendLine("  \"versionName\": ${quote(draft.versionName.trim())},")
        appendLine("  \"api\": ${draft.api},")
        appendLine("  \"minHostVersion\": ${draft.minHostVersion},")
        appendLine("  \"developer\": ${quote(draft.developer.trim().ifEmpty { "Unknown" }) },")
        appendLine("  \"description\": ${quote(draft.description.trim())},")
        appendLine("  \"changelog\": ${quote(draft.changelog.trim())},")
        append("  \"permissions\": [")
        permissions.forEachIndexed { index, permission ->
            if (index > 0) append(", ")
            val scopes = scopesByPermission[permission].orEmpty()
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .distinct()
                .sorted()
            append("{ \"id\": ${quote(permission)}")
            if (scopes.isNotEmpty()) {
                append(", \"scopes\": [")
                scopes.forEachIndexed { scopeIndex, scope ->
                    if (scopeIndex > 0) append(", ")
                    append(quote(scope))
                }
                append("]")
            }
            append(" }")
        }
        appendLine("],")
        append("  \"declares\": [")
        declares.forEachIndexed { index, section ->
            if (index > 0) append(", ")
            append(quote(section))
        }
        appendLine("]")
        appendLine("}")
        }
    }

    /** The theme, as the JSON that goes into the package. */
    fun themeJson(draft: PluginDraft): String {
        val theme = draft.theme
        val schema = theme.schema
        val hasTypography = schema >= 2
        val effects = effectsJson(theme.glass, theme.glitch, schema)
        return buildString {
            appendLine("{")
            appendLine("  \"name\": ${quote(theme.name.trim())},")
            appendLine("  \"schema\": $schema,")
            appendLine("  \"dark\": ${theme.dark},")
            if (schema >= 2) {
                appendLine("  \"amoled\": ${theme.amoled},")
            }
            appendLine("  \"primary\": ${quote(theme.primary.argbHex())},")
            appendLine("  \"onPrimary\": ${quote(theme.onPrimary.argbHex())},")
            appendLine("  \"background\": ${quote(theme.background.argbHex())},")
            appendLine("  \"surface\": ${quote(theme.surface.argbHex())},")
            appendLine("  \"onSurface\": ${quote(theme.onSurface.argbHex())},")
            appendLine("  \"accent\": ${quote(theme.accent.argbHex())},")
            if (schema >= 2) {
                appendLine("  \"roles\": {")
                appendLine("    \"secondary\": ${quote(theme.secondary.argbHex())},")
                appendLine("    \"tertiary\": ${quote(theme.tertiary.argbHex())},")
                appendLine("    \"surfaceVariant\": ${quote(theme.surfaceVariant.argbHex())},")
                appendLine("    \"onSurfaceVariant\": ${quote(theme.onSurfaceVariant.argbHex())},")
                appendLine("    \"outline\": ${quote(theme.outline.argbHex())},")
                appendLine("    \"error\": ${quote(theme.error.argbHex())},")
                appendLine("    \"onError\": ${quote(theme.onError.argbHex())}")
                appendLine("  },")
            }
            appendLine("  \"cornerRadiusDp\": ${theme.cornerRadiusDp},")
            appendLine("  \"uiScale\": ${theme.uiScale},")
            appendLine("  \"font\": ${quote(theme.font)}" + if (hasTypography) "," else "")
            if (hasTypography) {
                appendLine("  \"typography\": {")
                appendLine("    \"scale\": ${theme.typographyScale},")
                appendLine("    \"weight\": ${quote(theme.typographyWeight)}")
                append("  }")
                if (effects != null) appendLine(",") else appendLine()
            }
            if (effects != null) {
                append(effects)
            }
            appendLine("}")
        }
    }

    /**
     * The optional `effects` object, or null when the theme declares no material or motion.
     *
     * A v2 theme writes no effects key at all. A v3 theme may carry glitch, and only v4 may carry
     * glass — the schema numbers are the compatibility contract, so the writer follows them rather
     * than emitting a block the declared schema says the theme does not use.
     */
    private fun effectsJson(glass: GlassDraft?, glitch: GlitchDraft?, schema: Int): String? {
        if (schema < 3) return null
        if (glass == null && glitch == null) return null
        return buildString {
            appendLine("  \"effects\": {")
            var wroteOne = false
            if (glitch != null) {
                wroteOne = true
                appendLine("    \"glitch\": {")
                appendLine("      \"intensity\": ${glitch.intensity},")
                appendLine("      \"slices\": ${glitch.slices},")
                appendLine("      \"sliceOffsetDp\": ${glitch.sliceOffsetDp},")
                appendLine("      \"ghostOffsetDp\": ${glitch.ghostOffsetDp},")
                appendLine("      \"fractureLines\": ${glitch.fractureLines},")
                appendLine("      \"noise\": ${glitch.noise},")
                appendLine("      \"microJitter\": ${glitch.microJitter},")
                appendLine("      \"hardwareReactive\": ${glitch.hardwareReactive},")
                appendLine("      \"burstMinMs\": ${glitch.burstMinMs},")
                appendLine("      \"burstMaxMs\": ${glitch.burstMaxMs}")
                append("    }")
            }
            if (glass != null && schema >= 4) {
                if (wroteOne) appendLine(",")
                val intensitySetting = glass.intensitySetting?.trim()?.takeIf { it.isNotEmpty() }
                appendLine("    \"glass\": {")
                appendLine("      \"intensity\": ${glass.intensity},")
                appendLine("      \"transparency\": ${glass.transparency},")
                appendLine("      \"gloss\": ${glass.gloss},")
                appendLine("      \"blurRadiusDp\": ${glass.blurRadiusDp},")
                appendLine("      \"sheen\": ${glass.sheen},")
                appendLine("      \"sheenPeriodMs\": ${glass.sheenPeriodMs},")
                appendLine("      \"spring\": ${quote(glass.spring)},")
                append("      \"hardwareReactive\": ${glass.hardwareReactive}")
                if (intensitySetting != null) {
                    appendLine(",")
                    appendLine("      \"intensitySetting\": ${quote(intensitySetting)}")
                } else {
                    appendLine()
                }
                append("    }")
            }
            appendLine()
            append("  }")
        }
    }

    /** The whole package, in memory. */
    fun packageBytes(draft: PluginDraft): ByteArray {
        val buffer = ByteArrayOutputStream()
        writePackage(buffer, draft)
        return buffer.toByteArray()
    }

    /** The whole package, written to [output]. */
    fun writeTo(output: File, draft: PluginDraft) {
        output.parentFile?.mkdirs()
        output.outputStream().buffered().use { writePackage(it, draft) }
    }

    /** The SHA-256 of [bytes], lowercase hex. The same value the release sidecars carry. */
    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun writePackage(sink: OutputStream, draft: PluginDraft) {
        ZipOutputStream(sink).use { zip ->
            // Fixed order and fixed time: the archive is a value, not a log of the save.
            packageEntries(draft).forEach { (path, contents) ->
                putEntry(zip, path, contents.toByteArray(Charsets.UTF_8))
            }
        }
    }

    /** Build once for [entryNames] and the writer so invalid in-progress drafts still agree on shape. */
    private fun packageEntries(draft: PluginDraft): List<Pair<String, String>> = buildList {
        add("manifest.json" to manifestJson(draft))
        val sections = draft.sections
        if (sections.themeEnabled) add("theme.json" to themeJson(draft))
        sections.overlay?.let { add("pill.json" to SectionComposer.overlayJson(it)) }
        if (sections.screens.isNotEmpty()) {
            add("screens/screens.json" to SectionComposer.screensJson(sections.screens))
        }
        if (sections.rules.isNotEmpty()) {
            add("rules/rules.json" to SectionComposer.rulesJson(sections.rules))
        }
        if (sections.settings.isNotEmpty()) {
            add("settings.json" to SectionComposer.settingsJson(sections.settings))
        }

        // Duplicate canonical ids are a draft problem, not an exception from ZipOutputStream. Keep
        // the first deterministically so packageBytes remains total for an in-progress invalid draft.
        val seenWidgetIds = linkedSetOf<String>()
        sections.widgets.sortedBy { canonicalId(it.id) }.forEach { widget ->
            val id = canonicalId(widget.id)
            if (seenWidgetIds.add(id)) {
                add("widgets/$id.json" to SectionComposer.widgetJson(widget))
            }
        }
    }

    private fun putEntry(zip: ZipOutputStream, path: String, bytes: ByteArray) {
        val entry = ZipEntry(path)
        entry.time = FIXED_TIME_MILLIS
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    /**
     * A JSON string literal, quoted and escaped.
     *
     * Hand-written rather than delegated to a JSON library: the studio is an Android library
     * and the platform's `org.json` is a stub under unit tests, which would make a composer
     * test pass without writing anything. Escaping here is the whole of what is needed —
     * quotes, backslashes, the control characters, and the line separators that are legal in
     * JSON but not in JavaScript.
     */
    internal fun quote(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (character < ' ') {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }

    private fun canonicalId(value: String): String = value.trim().lowercase()
}

/**
 * The colour as `#RRGGBB`, or `#AARRGGBB` when it carries an alpha below opaque.
 *
 * The host's codec accepts both forms. Writing six digits whenever the alpha is opaque keeps a
 * normal theme looking exactly like the v1 packages and makes a translucent colour an explicit
 * choice rather than an invisible default.
 */
internal fun Long.argbHex(): String {
    val value = this and 0xFFFFFFFFL
    val alpha = (value ushr 24) and 0xFF
    val rgb = value and 0xFFFFFFL
    return if (alpha == 0xFFL) "#%06X".format(rgb) else "#%08X".format(value)
}
