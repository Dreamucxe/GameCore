package com.gamecore.core.schedule

import com.gamecore.core.common.TextSanitizer
import org.json.JSONArray
import org.json.JSONObject

/**
 * The schedule's on-disk format.
 *
 * The file is private to this app and written only by the schedule screen, so this codec is
 * deliberately more forgiving than a `.gcplugin` codec: a malformed row is dropped rather than
 * rejecting the whole schedule, and an unknown field is ignored. What it must not do is invent a
 * target or a time: a row without a valid minute pair is not repaired into "00:00–00:00" (which
 * would be a 24-hour window), it is dropped. The remaining rows are still the user's.
 */
object AppearanceScheduleCodec {

    private const val FORMAT = "gamecore.appearance.schedule"
    private const val VERSION = 1
    private const val THEME_DEFAULT = "default"
    private const val THEME_PLUGIN_PREFIX = "plugin:"
    private const val DASHBOARD_BUILTIN = "builtin"
    private const val DASHBOARD_PLUGIN = "plugin"

    fun encode(schedule: AppearanceSchedule): String {
        val canonical = schedule.normalised()
        return JSONObject().apply {
            put("format", FORMAT)
            put("version", VERSION)
            put("enabled", canonical.enabled)
            put("windows", JSONArray().apply {
                canonical.windows.forEach { window ->
                    put(JSONObject().apply {
                        put("id", window.id)
                        put("label", window.label)
                        put("start", window.startMinute)
                        put("end", window.endMinute)
                        window.theme?.let { put("theme", encodeTheme(it)) }
                        window.dashboard?.let { put("dashboard", encodeDashboard(it)) }
                    })
                }
            })
            put("games", JSONArray().apply {
                canonical.gameOverrides.forEach { override ->
                    put(JSONObject().apply {
                        put("package", override.packageName)
                        put("label", override.label)
                        override.theme?.let { put("theme", encodeTheme(it)) }
                        override.dashboard?.let { put("dashboard", encodeDashboard(it)) }
                    })
                }
            })
        }.toString()
    }

    fun decode(raw: String?): AppearanceSchedule {
        if (raw.isNullOrBlank()) return AppearanceSchedule()
        return runCatching {
            val root = JSONObject(raw)
            if (root.optString("format") != FORMAT || root.optInt("version", 0) != VERSION) {
                AppearanceSchedule()
            } else {
                AppearanceSchedule(
                    enabled = root.optBoolean("enabled", false),
                    windows = decodeWindows(root.optJSONArray("windows")),
                    gameOverrides = decodeGames(root.optJSONArray("games")),
                ).normalised()
            }
        }.getOrDefault(AppearanceSchedule())
    }

    private fun decodeWindows(array: JSONArray?): List<AppearanceWindow> {
        if (array == null) return emptyList()
        val out = ArrayList<AppearanceWindow>(array.length())
        for (i in 0 until array.length()) {
            val row = array.optJSONObject(i) ?: continue
            // `optInt` turns a malformed string into 0, which would quietly become midnight and
            // turn a broken row into an all-day window. Require a JSON number, then range-check it.
            val start = row.numberOrNull("start") ?: continue
            val end = row.numberOrNull("end") ?: continue
            if (start !in 0 until AppearanceWindow.MINUTES_PER_DAY) continue
            if (end !in 0 until AppearanceWindow.MINUTES_PER_DAY) continue
            out += AppearanceWindow(
                id = row.optString("id").trim().take(64).ifBlank { "window-$i" },
                label = row.optString("label"),
                startMinute = start,
                endMinute = end,
                theme = decodeTheme(row.optString("theme", "")),
                dashboard = decodeDashboard(row.optString("dashboard", "")),
            )
        }
        return out
    }

    private fun decodeGames(array: JSONArray?): List<GameAppearanceOverride> {
        if (array == null) return emptyList()
        val out = ArrayList<GameAppearanceOverride>(array.length())
        for (i in 0 until array.length()) {
            val row = array.optJSONObject(i) ?: continue
            val packageName = TextSanitizer.validatePackageName(row.optString("package"))
                ?: continue
            out += GameAppearanceOverride(
                packageName = packageName,
                label = row.optString("label"),
                theme = decodeTheme(row.optString("theme", "")),
                dashboard = decodeDashboard(row.optString("dashboard", "")),
            )
        }
        return out
    }

    private fun encodeTheme(target: ThemeTarget): String = when (target) {
        ThemeTarget.Default -> THEME_DEFAULT
        is ThemeTarget.Plugin -> THEME_PLUGIN_PREFIX + target.pluginId
    }

    private fun decodeTheme(raw: String): ThemeTarget? {
        val value = raw.trim()
        return when {
            value.isEmpty() -> null
            value == THEME_DEFAULT -> ThemeTarget.Default
            value.startsWith(THEME_PLUGIN_PREFIX) -> {
                val id = value.removePrefix(THEME_PLUGIN_PREFIX).trim()
                if (id.isEmpty() || id.length > 200) null else ThemeTarget.Plugin(id)
            }
            else -> null
        }
    }

    private fun encodeDashboard(target: DashboardTarget): String = when (target) {
        DashboardTarget.BuiltIn -> DASHBOARD_BUILTIN
        DashboardTarget.PluginDashboard -> DASHBOARD_PLUGIN
    }

    private fun decodeDashboard(raw: String): DashboardTarget? = when (raw.trim()) {
        DASHBOARD_BUILTIN -> DashboardTarget.BuiltIn
        DASHBOARD_PLUGIN -> DashboardTarget.PluginDashboard
        else -> null
    }

    private fun JSONObject.numberOrNull(key: String): Int? =
        (opt(key) as? Number)?.toInt()
}
