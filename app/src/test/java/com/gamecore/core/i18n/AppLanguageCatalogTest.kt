package com.gamecore.core.i18n

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The catalogue set, checked as data rather than as screens.
 *
 * A missing key does not crash the app — [Strings.get] falls back to English — but it does mean a user
 * sees an English line in the middle of their language, which is exactly the kind of thing that goes
 * unnoticed without a test. This asserts every supported language has a file, every file has exactly the
 * English key set, no value is blank, and every `{0}`-style marker survived translation. It reads the
 * source assets directly, so it runs as a plain JUnit test with no device.
 */
class AppLanguageCatalogTest {

    private val directory = File("src/main/assets/i18n")

    @Test
    fun `every selectable language has a catalogue`() {
        assertTrue("catalogue directory is missing: ${directory.absolutePath}", directory.isDirectory)
        for (language in AppLanguage.selectable) {
            val file = File(directory, "${language.tag}.json")
            assertTrue("missing catalogue for ${language.tag}", file.isFile)
        }
    }

    @Test
    fun `every catalogue has exactly the english keys, all non-blank`() {
        val english = read("en")
        for (language in AppLanguage.selectable) {
            val catalogue = read(language.tag)
            assertEquals(
                "${language.tag} key set differs from English",
                english.keys,
                catalogue.keys,
            )
            for ((key, value) in catalogue) {
                assertTrue("${language.tag} has a blank value for $key", value.isNotBlank())
            }
        }
    }

    @Test
    fun `every positional marker survives translation`() {
        val english = read("en")
        val markers = Regex("""\{\d+}""")
        for (language in AppLanguage.selectable) {
            val catalogue = read(language.tag)
            for ((key, value) in english) {
                // Order is not part of the contract: several languages put the total first
                // ("{1} adımının {0}. adımı"), and [Strings.format] replaces by number, so a
                // reordered sentence still renders the right two values. What must survive is the
                // set of markers — one {0} in must produce one {0} out, never a dropped or invented
                // argument.
                assertEquals(
                    "${language.tag} changed the markers in $key: ${catalogue[key]}",
                    markers.findAll(value).map { it.value }.sorted().toList(),
                    markers.findAll(catalogue.getValue(key)).map { it.value }.sorted().toList(),
                )
            }
        }
    }

    @Test
    fun `explicit choice wins and empty follows the device`() {
        assertEquals(AppLanguage.GERMAN, AppLanguage.resolve("de", "en-US"))
        assertEquals(AppLanguage.GERMAN, AppLanguage.resolve("", "de-DE"))
        assertEquals(AppLanguage.FRENCH, AppLanguage.resolve("xx", "fr-FR"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.resolve("", "en-GB"))
        // An explicit choice that this build does not have must fall back to the device, not to a key.
        assertEquals(AppLanguage.SPANISH, AppLanguage.resolve("xx", "es-ES"))
    }

    @Test
    fun `regional aliases resolve to the catalogue that exists`() {
        assertEquals(AppLanguage.CHINESE_TRADITIONAL, AppLanguage.resolve("", "zh-HK"))
        assertEquals(AppLanguage.CHINESE_TRADITIONAL, AppLanguage.resolve("", "zh-TW"))
        assertEquals(AppLanguage.CHINESE_SIMPLIFIED, AppLanguage.resolve("", "zh-CN"))
        assertEquals(AppLanguage.CHINESE_SIMPLIFIED, AppLanguage.resolve("", "zh-SG"))
        assertEquals(AppLanguage.PORTUGUESE_BRAZIL, AppLanguage.resolve("", "pt-PT"))
        assertEquals(AppLanguage.NORWEGIAN, AppLanguage.resolve("", "nb-NO"))
        assertEquals(AppLanguage.HEBREW, AppLanguage.resolve("", "he-IL"))
    }

    @Test
    fun `right to left languages are flagged`() {
        val rtl = AppLanguage.selectable.filter { it.rtl }.map { it.tag }.toSet()
        assertEquals(setOf("ar", "he", "fa", "ur"), rtl)
    }

    private fun read(tag: String): Map<String, String> {
        val file = File(directory, "$tag.json")
        val root = JSONObject(file.readText(Charsets.UTF_8))
        return buildMap {
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, root.getString(key))
            }
        }
    }
}
