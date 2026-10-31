package com.gamecore.core.i18n

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads language catalogues out of `assets/i18n/` and keeps the parsed ones.
 *
 * Catalogues are data, not compiled resources, so a language can be corrected or added without a
 * source change and third-party translations can be dropped into the same folder. The trade is that a
 * parse failure is possible, which is why [read] never throws: a malformed file reads as an empty map
 * and the screen falls back to English. There is no state in which text disappears.
 *
 * Parsed maps are cached per tag. A user switches language once in a while, and keeping the previous
 * language's map costs a few kilobytes — far less than re-reading and re-parsing the file every time
 * the root recomposes.
 */
object I18nCatalog {

    private val cache = ConcurrentHashMap<String, Strings>()

    fun strings(context: Context, language: AppLanguage): Strings {
        val key = language.tag.ifEmpty { AppLanguage.fallback.tag }
        return cache.getOrPut(key) {
            val app = context.applicationContext
            val fallback = read(app, AppLanguage.fallback.tag)
            val selected = if (key == AppLanguage.fallback.tag) fallback else read(app, key)
            Strings(selected, fallback)
        }
    }

    private fun read(context: Context, tag: String): Map<String, String> = runCatching {
        val text = context.assets.open("i18n/$tag.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = JSONObject(text)
        buildMap {
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = root.optString(key, "")
                if (value.isNotBlank()) put(key, value)
            }
        }
    }.getOrDefault(emptyMap())
}
