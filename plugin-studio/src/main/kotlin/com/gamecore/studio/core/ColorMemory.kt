package com.gamecore.studio.core

import android.content.Context

/**
 * The studio's colour memory: recent picks, favourites and saved palettes.
 *
 * These are editor conveniences, not part of a package. They live in the host app's own
 * preferences so they survive a restart, and they are never written into an exported
 * `.gcplugin` — a palette the user saved for themselves is not a claim about the theme.
 *
 * The store is deliberately tiny and synchronous; it is read once when the picker opens and
 * written when a colour is chosen, so there is no background work to coordinate.
 */
class ColorMemory(context: Context) {

    private val preferences = context.getSharedPreferences(PREFERENCE_FILE, Context.MODE_PRIVATE)

    /** The most recent distinct picks, newest first. */
    fun recent(): List<Long> = decode(preferences.getString(RECENT_KEY, null))

    /** Remember one pick, moving it to the front and keeping the list short. */
    fun rememberRecent(color: Long) {
        val next = (listOf(color and 0xFFFFFFFFL) + recent().filter { it != (color and 0xFFFFFFFFL) })
            .take(MAX_RECENT)
        preferences.edit().putString(RECENT_KEY, encode(next)).apply()
    }

    /** Colours the user starred. */
    fun favorites(): List<Long> = decode(preferences.getString(FAVORITES_KEY, null))

    /** Star or unstar one colour. Returns the new state: true when it is now a favourite. */
    fun toggleFavorite(color: Long): Boolean {
        val value = color and 0xFFFFFFFFL
        val current = favorites()
        val next = if (value in current) current - value else current + value
        preferences.edit().putString(FAVORITES_KEY, encode(next)).apply()
        return value in next
    }

    /** A saved arrangement of colours, by name. */
    data class SavedPalette(val name: String, val colors: List<Long>)

    /** Every saved palette, newest first. */
    fun palettes(): List<SavedPalette> = preferences.all.asSequence()
        .filter { (key, value) -> key.startsWith(PALETTE_PREFIX) && value is String }
        .mapNotNull { (key, value) ->
            val colors = decode(value as String)
            if (colors.isEmpty()) null else SavedPalette(key.removePrefix(PALETTE_PREFIX), colors)
        }
        .sortedBy { it.name.lowercase() }
        .toList()

    /** Save (or replace) a palette under [name]. A blank name is ignored. */
    fun savePalette(name: String, colors: List<Long>) {
        val clean = name.trim().take(48)
        if (clean.isEmpty() || colors.isEmpty()) return
        preferences.edit().putString(PALETTE_PREFIX + clean, encode(colors.take(32))).apply()
    }

    /** Remove a saved palette by name. */
    fun deletePalette(name: String) {
        preferences.edit().remove(PALETTE_PREFIX + name).apply()
    }

    private fun encode(colors: List<Long>): String =
        colors.joinToString(",") { "%08X".format(it and 0xFFFFFFFFL) }

    private fun decode(raw: String?): List<Long> =
        raw?.split(',')?.mapNotNull { token ->
            token.trim().takeIf { it.length == 8 }?.toLongOrNull(16)
        } ?: emptyList()

    private companion object {
        const val PREFERENCE_FILE = "plugin_studio_colors"
        const val RECENT_KEY = "recent"
        const val FAVORITES_KEY = "favorites"
        const val PALETTE_PREFIX = "palette:"
        const val MAX_RECENT = 12
    }
}
