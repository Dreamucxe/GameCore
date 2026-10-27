package com.gamecore.plugin.sdk

import com.gamecore.plugin.sdk.templates.batteryBadge
import com.gamecore.plugin.sdk.templates.chargerBoost
import com.gamecore.plugin.sdk.templates.colorblindSafeTheme
import com.gamecore.plugin.sdk.templates.cooldownLadder
import com.gamecore.plugin.sdk.templates.exitRestore
import com.gamecore.plugin.sdk.templates.minimalVitals
import com.gamecore.plugin.sdk.templates.nightShiftTheme
import com.gamecore.plugin.sdk.templates.papercutTheme
import com.gamecore.plugin.sdk.templates.statDictionary
import com.gamecore.plugin.sdk.templates.thermalDesk
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.json.JSONObject

/**
 * Packages the ten new official plugin packs, validates their authored bytes against the host's
 * limits, and refreshes both the `dist/` deliverables and the bundled `assets/plugins/` copies.
 *
 * The test is one gate for ten packages because the promise is shared: every one is data-only,
 * every manifest agrees with its sections, every `stat` rides a scope the manifest requests, every
 * rule names only a broker action, and every package is a zip of JSON files. The per-package
 * assertions below add the shape each concept promised.
 *
 * The bundled path is asymmetric on purpose. The host's bundled loader reads exactly one
 * `widgets.json` per plugin, while a sideloaded `.gcplugin` can carry every `widgets/<id>.json`.
 * So the widget packs flatten one representative card into the APK and keep the full deck in
 * `dist/`. Every other section is a single file and is bundled whole.
 */
class NewPluginPacksPackageTest {

    private data class Case(
        val slug: String,
        val id: String,
        val name: String,
        val def: PluginDefinition,
        /** The widget id flattened to `widgets.json` for the bundled copy, if this pack has widgets. */
        val bundledWidget: String? = null,
    )

    private val cases = listOf(
        Case("nightshift", "com.dreamucxe.gamecore.nightshift", "Night Shift", nightShiftTheme()),
        Case("colorblindsafe", "com.dreamucxe.gamecore.colorblindsafe", "Colorblind Safe", colorblindSafeTheme()),
        Case("papercut", "com.dreamucxe.gamecore.papercut", "Papercut", papercutTheme()),
        Case("minimalvitals", "com.dreamucxe.gamecore.minimalvitals", "Minimal Vitals", minimalVitals(), "vitals_snapshot"),
        Case("thermaldesk", "com.dreamucxe.gamecore.thermaldesk", "Thermal Desk", thermalDesk(), "thermal_trend"),
        Case("batterybadge", "com.dreamucxe.gamecore.batterybadge", "Battery Badge", batteryBadge()),
        Case("cooldownladder", "com.dreamucxe.gamecore.cooldownladder", "Cooldown Ladder", cooldownLadder()),
        Case("exitrestore", "com.dreamucxe.gamecore.exitrestore", "Exit Restore", exitRestore()),
        Case("chargerboost", "com.dreamucxe.gamecore.chargerboost", "Charger Boost", chargerBoost()),
        Case("statdictionary", "com.dreamucxe.gamecore.statdictionary", "Stat Dictionary", statDictionary()),
    )

    private val expectedSections = mapOf(
        "nightshift" to listOf("theme", "screens", "icon"),
        "colorblindsafe" to listOf("theme", "screens", "icon"),
        "papercut" to listOf("theme", "screens", "icon"),
        "minimalvitals" to listOf("widgets", "icon"),
        "thermaldesk" to listOf("widgets", "icon"),
        "batterybadge" to listOf("pill", "icon"),
        "cooldownladder" to listOf("rules", "screens", "icon"),
        "exitrestore" to listOf("rules", "screens", "icon"),
        "chargerboost" to listOf("rules", "screens", "icon"),
        "statdictionary" to listOf("screens", "icon"),
    )

    private val expectedSectionPaths = mapOf(
        "nightshift" to listOf("theme.json", "screens/screens.json", "icon.json"),
        "colorblindsafe" to listOf("theme.json", "screens/screens.json", "icon.json"),
        "papercut" to listOf("theme.json", "screens/screens.json", "icon.json"),
        "minimalvitals" to listOf(
            "widgets/vitals_snapshot.json",
            "widgets/vitals_cpu.json",
            "widgets/vitals_ram.json",
            "widgets/vitals_battery.json",
            "widgets/vitals_heat.json",
            "icon.json",
        ),
        "thermaldesk" to listOf(
            "widgets/thermal_now.json",
            "widgets/thermal_load.json",
            "widgets/thermal_plan.json",
            "widgets/thermal_trend.json",
            "icon.json",
        ),
        "batterybadge" to listOf("pill.json", "icon.json"),
        "cooldownladder" to listOf("rules/rules.json", "screens/screens.json", "icon.json"),
        "exitrestore" to listOf("rules/rules.json", "screens/screens.json", "icon.json"),
        "chargerboost" to listOf("rules/rules.json", "screens/screens.json", "icon.json"),
        "statdictionary" to listOf("screens/screens.json", "icon.json"),
    )

    private val expectedPermissions = mapOf(
        "nightshift" to linkedMapOf(
            "change_ui" to emptySet<String>(),
            "change_icon" to emptySet<String>(),
        ),
        "colorblindsafe" to linkedMapOf(
            "change_ui" to emptySet<String>(),
            "change_icon" to emptySet<String>(),
        ),
        "papercut" to linkedMapOf(
            "change_ui" to emptySet<String>(),
            "change_icon" to emptySet<String>(),
        ),
        "minimalvitals" to linkedMapOf(
            "add_dashboard_widgets" to emptySet<String>(),
            "read_approved_data" to linkedSetOf(DataScopes.DEVICE_STATS, DataScopes.THERMALS),
            "change_icon" to emptySet<String>(),
        ),
        "thermaldesk" to linkedMapOf(
            "add_dashboard_widgets" to emptySet<String>(),
            "read_approved_data" to linkedSetOf(DataScopes.DEVICE_STATS, DataScopes.THERMALS),
            "change_icon" to emptySet<String>(),
        ),
        "batterybadge" to linkedMapOf(
            "create_overlay" to emptySet<String>(),
            "read_approved_data" to linkedSetOf(DataScopes.THERMALS),
            "change_icon" to emptySet<String>(),
        ),
        "cooldownladder" to linkedMapOf(
            "run_automations" to emptySet<String>(),
            "system_actions" to emptySet<String>(),
            "change_ui" to emptySet<String>(),
            "change_icon" to emptySet<String>(),
        ),
        "exitrestore" to linkedMapOf(
            "run_automations" to emptySet<String>(),
            "system_actions" to emptySet<String>(),
            "change_ui" to emptySet<String>(),
            "change_icon" to emptySet<String>(),
        ),
        "chargerboost" to linkedMapOf(
            "run_automations" to emptySet<String>(),
            "system_actions" to emptySet<String>(),
            "change_ui" to emptySet<String>(),
            "change_icon" to emptySet<String>(),
        ),
        "statdictionary" to linkedMapOf(
            "change_ui" to emptySet<String>(),
            "change_icon" to emptySet<String>(),
        ),
    )

    private val expectedIconSlots = mapOf(
        "nightshift" to IconSlots.MONO,
        "colorblindsafe" to IconSlots.DEFAULT,
        "papercut" to IconSlots.DEFAULT,
        "minimalvitals" to IconSlots.DEFAULT,
        "thermaldesk" to IconSlots.REDLINE,
        "batterybadge" to IconSlots.NEON,
        "cooldownladder" to IconSlots.REDLINE,
        "exitrestore" to IconSlots.MONO,
        "chargerboost" to IconSlots.NEON,
        "statdictionary" to IconSlots.DEFAULT,
    )

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "app").isDirectory && File(dir, "gamecore-plugin-sdk").isDirectory) return dir
            dir = dir.parentFile
        }
        error("repo root not found from ${File("").absolutePath}")
    }

    private fun section(def: PluginDefinition, path: String): JSONObject =
        JSONObject(String(def.sectionFiles.getValue(path)))

    private fun manifest(def: PluginDefinition): JSONObject = JSONObject(String(def.manifestJson))

    private fun requestedScopes(def: PluginDefinition): Set<String> {
        val permissions = manifest(def).getJSONArray("permissions")
        for (i in 0 until permissions.length()) {
            val permission = permissions.getJSONObject(i)
            if (permission.getString("id") == Permissions.READ_APPROVED_DATA) {
                val scopes = permission.optJSONArray("scopes") ?: return emptySet()
                return (0 until scopes.length()).map { scopes.getString(it) }.toSet()
            }
        }
        return emptySet()
    }

    /** Every `stat` id named anywhere in a node tree, walked iteratively. */
    private fun statsIn(root: JSONObject): Set<String> {
        val found = linkedSetOf<String>()
        val pending = ArrayDeque<JSONObject>()
        pending.addLast(root)
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            node.optJSONObject("props")
                ?.optString("stat")
                ?.takeIf { it.isNotEmpty() }
                ?.let { found += it }
            val children = node.optJSONArray("children") ?: continue
            for (i in 0 until children.length()) pending.addLast(children.getJSONObject(i))
        }
        return found
    }

    private fun assertOverlayTree(root: JSONObject, label: String) {
        var count = 0
        fun walk(node: JSONObject, depth: Int) {
            count++
            assertTrue(count <= OverlayBounds.MAX_NODES, "$label node count")
            assertTrue(depth <= OverlayBounds.MAX_DEPTH, "$label node depth")
            node.optJSONObject("props")?.let { props ->
                for (key in props.keys()) {
                    val value = props.opt(key)
                    if (value is String) {
                        assertTrue(
                            value.length <= OverlayBounds.MAX_STRING_LENGTH,
                            "$label string prop \"$key\" length",
                        )
                    }
                }
            }
            val children = node.optJSONArray("children") ?: return
            assertTrue(children.length() <= OverlayBounds.MAX_CHILDREN, "$label children count")
            for (i in 0 until children.length()) walk(children.getJSONObject(i), depth + 1)
        }
        walk(root, 0)
    }

    private fun assertScreenTree(root: JSONObject, label: String) {
        var count = 0
        fun walk(node: JSONObject, depth: Int) {
            count++
            assertTrue(count <= ScreenBounds.MAX_NODES, "$label node count")
            assertTrue(depth <= ScreenBounds.MAX_DEPTH, "$label node depth")
            node.optJSONObject("props")?.let { props ->
                for (key in props.keys()) {
                    val value = props.opt(key)
                    if (value is String) {
                        assertTrue(
                            value.length <= ScreenBounds.MAX_STRING_LENGTH,
                            "$label string prop \"$key\" length",
                        )
                    }
                }
            }
            val children = node.optJSONArray("children") ?: return
            assertTrue(children.length() <= ScreenBounds.MAX_CHILDREN, "$label children count")
            for (i in 0 until children.length()) walk(children.getJSONObject(i), depth + 1)
        }
        walk(root, 0)
    }

    private fun assertStatsAreGranted(def: PluginDefinition, root: JSONObject, label: String) {
        val needed = statsIn(root).mapNotNull { OverlayStats.scopeFor(it) }.toSet()
        assertEquals(
            emptySet<String>(),
            needed - requestedScopes(def),
            "$label names a stat whose scope the manifest never requests",
        )
    }

    // --------------------------------------------------------------------------------------- manifest

    @Test
    fun every_manifest_matches_its_package() {
        cases.forEach { case ->
            val m = manifest(case.def)
            assertEquals(
                setOf(
                    "format", "id", "name", "version", "versionName", "api", "minHostVersion",
                    "developer", "description", "changelog", "permissions", "declares",
                ),
                m.keys().asSequence().toSet(),
                "${case.slug} manifest emits exactly the host's known keys",
            )
            assertEquals("gamecore.plugin", m.getString("format"), "${case.slug} format")
            assertEquals(case.id, m.getString("id"), "${case.slug} id")
            assertEquals(case.name, m.getString("name"), "${case.slug} name")
            assertEquals(1, m.getInt("version"), "${case.slug} version")
            assertEquals("1.0.0", m.getString("versionName"), "${case.slug} versionName")
            assertEquals("Dream", m.getString("developer"), "${case.slug} developer")
            assertEquals("Initial release.", m.getString("changelog"), "${case.slug} changelog")
            assertTrue(m.getString("description").isNotBlank(), "${case.slug} description")
            assertTrue(m.getString("description").length <= 512, "${case.slug} description cap")

            // Only Battery Badge has no screens/widgets/settings; its pill alone is API 1.
            val expectedApi = if (case.slug == "batterybadge") Sdk.API else Sdk.API_V3
            assertEquals(expectedApi, m.getInt("api"), "${case.slug} api")
            assertEquals(
                Sdk.HOST_VERSION_CODE_ICON,
                m.getInt("minHostVersion"),
                "${case.slug} icon section needs host 37",
            )

            val permissions = m.getJSONArray("permissions")
            val actualPermissions = linkedMapOf<String, Set<String>>()
            for (i in 0 until permissions.length()) {
                val permission = permissions.getJSONObject(i)
                val id = permission.getString("id")
                val scopes = permission.optJSONArray("scopes")
                actualPermissions[id] = if (scopes == null) {
                    emptySet()
                } else {
                    (0 until scopes.length()).map { scopes.getString(it) }.toSet()
                }
                if (id == Permissions.READ_APPROVED_DATA) {
                    assertTrue(scopes != null, "${case.slug} read_approved_data carries scopes")
                } else {
                    assertTrue(scopes == null, "${case.slug} $id carries no scopes")
                }
            }
            assertEquals(expectedPermissions.getValue(case.slug), actualPermissions, "${case.slug} permissions")

            val declares = m.getJSONArray("declares")
            assertEquals(
                expectedSections.getValue(case.slug),
                (0 until declares.length()).map { declares.getString(it) },
                "${case.slug} declares its sections in call order",
            )
        }
    }

    @Test
    fun every_package_is_json_only_and_within_the_host_limits() {
        cases.forEach { case ->
            assertEquals(
                expectedSectionPaths.getValue(case.slug).toSet(),
                case.def.sectionFiles.keys.toSet(),
                "${case.slug} emits exactly the section files its declares promise",
            )
            assertTrue(
                case.def.sectionFiles.keys.all { it.endsWith(".json") },
                "${case.slug} ships only JSON section files",
            )
            assertTrue(
                case.def.sectionFiles.keys.none {
                    it.endsWith(".js") || it.endsWith(".class") || it.endsWith(".so") ||
                        it.endsWith(".html") || it.endsWith(".png") || it.endsWith(".webp")
                },
                "${case.slug} ships no script, native library or bitmap",
            )
            val bytes = case.def.manifestJson.size + case.def.sectionFiles.values.sumOf { it.size }
            assertTrue(bytes <= 5 * 1024 * 1024, "${case.slug} package <= 5MiB")
            case.def.sectionFiles.forEach { (path, sectionBytes) ->
                assertTrue(sectionBytes.size <= 2 * 1024 * 1024, "${case.slug} $path <= 2MiB")
            }
        }
    }

    // ----------------------------------------------------------------------------------------- themes

    private data class ThemeExpectation(
        val dark: Boolean,
        val font: String,
        val primary: String,
        val background: String,
        val surface: String,
        val onSurface: String,
        val accent: String,
        val onPrimary: String,
    )

    private val themeExpectations = mapOf(
        "nightshift" to ThemeExpectation(
            dark = true,
            font = ThemeFonts.SERIF,
            primary = "#FFB000",
            background = "#050403",
            surface = "#141008",
            onSurface = "#F5E6C8",
            accent = "#FF8A00",
            onPrimary = "#1A1000",
        ),
        "colorblindsafe" to ThemeExpectation(
            dark = true,
            font = ThemeFonts.SYSTEM,
            primary = "#2563EB",
            background = "#071018",
            surface = "#101D28",
            onSurface = "#F2F7FA",
            accent = "#F59E0B",
            onPrimary = "#FFFFFF",
        ),
        "papercut" to ThemeExpectation(
            dark = false,
            font = ThemeFonts.SERIF,
            primary = "#A63A2B",
            background = "#F3EDE2",
            surface = "#FFFDF7",
            onSurface = "#1C1917",
            accent = "#2F5D62",
            onPrimary = "#FFFFFF",
        ),
    )

    @Test
    fun the_three_themes_are_schema_two_and_readable() {
        themeExpectations.forEach { (slug, expected) ->
            val t = section(cases.first { it.slug == slug }.def, "theme.json")
            assertEquals(
                setOf(
                    "name", "dark", "primary", "onPrimary", "background", "surface", "onSurface",
                    "accent", "cornerRadiusDp", "font", "uiScale", "schema", "roles", "typography",
                ),
                t.keys().asSequence().toSet(),
                "$slug emits exactly the schema-2 theme keys",
            )
            assertEquals(2, t.getInt("schema"), "$slug schema 2")
            assertEquals(expected.dark, t.getBoolean("dark"), "$slug dark flag")
            assertEquals(expected.font, t.getString("font"), "$slug font")
            assertEquals(expected.primary, t.getString("primary"), "$slug primary")
            assertEquals(expected.background, t.getString("background"), "$slug background")
            assertEquals(expected.surface, t.getString("surface"), "$slug surface")
            assertEquals(expected.onSurface, t.getString("onSurface"), "$slug onSurface")
            assertEquals(expected.accent, t.getString("accent"), "$slug accent")
            assertEquals(expected.onPrimary, t.getString("onPrimary"), "$slug onPrimary")
            assertTrue(
                t.getInt("cornerRadiusDp") in ThemeBounds.MIN_CORNER_DP..ThemeBounds.MAX_CORNER_DP,
                "$slug corner radius",
            )
            assertTrue(
                t.getDouble("uiScale") in ThemeBounds.MIN_UI_SCALE..ThemeBounds.MAX_UI_SCALE,
                "$slug ui scale",
            )

            val roles = t.getJSONObject("roles")
            assertEquals(ThemeRoles.ALL, roles.keys().asSequence().toSet(), "$slug sets every semantic role")
            val typography = t.getJSONObject("typography")
            assertTrue(
                typography.getString("weight") in ThemeWeights.ALL,
                "$slug typography weight",
            )

            // Text/fill pairs a user actually reads. The accent is large emphasis, so 3:1.
            assertTrue(
                contrast(expected.onSurface, expected.background) >= 4.5,
                "$slug onSurface on background clears AA: ${contrast(expected.onSurface, expected.background)}",
            )
            assertTrue(
                contrast(expected.onSurface, expected.surface) >= 4.5,
                "$slug onSurface on surface clears AA: ${contrast(expected.onSurface, expected.surface)}",
            )
            assertTrue(
                contrast(expected.onPrimary, expected.primary) >= 4.5,
                "$slug onPrimary on primary clears AA: ${contrast(expected.onPrimary, expected.primary)}",
            )
            assertTrue(
                contrast(expected.accent, expected.background) >= 3.0,
                "$slug accent on background clears large-text AA: ${contrast(expected.accent, expected.background)}",
            )
        }
    }

    // ---------------------------------------------------------------------------------------- screens

    @Test
    fun every_screen_pack_has_the_screens_its_concept_promised() {
        val expectedScreenIds = mapOf(
            "nightshift" to listOf("overview", "bedtime"),
            "colorblindsafe" to listOf("overview", "signals", "limits"),
            "papercut" to listOf("overview", "printshop", "limits"),
            "cooldownladder" to listOf("ladder", "limits"),
            "exitrestore" to listOf("overview", "setup", "limits"),
            "chargerboost" to listOf("overview", "limits"),
            "statdictionary" to listOf(
                "overview", "frames", "load", "power", "display", "motion", "not-measured",
            ),
        )

        expectedScreenIds.forEach { (slug, ids) ->
            val def = cases.first { it.slug == slug }.def
            val screens = section(def, "screens/screens.json")
            assertEquals(Sdk.SCREENS_FORMAT, screens.getString("format"), "$slug screens format")
            assertEquals(Sdk.SCREENS_VERSION, screens.getInt("version"), "$slug screens version")
            val arr = screens.getJSONArray("screens")
            assertEquals(ids.size, arr.length(), "$slug screen count")
            assertEquals(
                ids,
                (0 until arr.length()).map { arr.getJSONObject(it).getString("id") },
                "$slug screen ids",
            )
            for (i in 0 until arr.length()) {
                val screen = arr.getJSONObject(i)
                val id = screen.getString("id")
                assertTrue(screen.getString("title").isNotBlank(), "$slug/$id title")
                assertTrue(
                    !screen.has("icon") || screen.getString("icon") in OverlayIcons.ALL,
                    "$slug/$id icon is a host icon",
                )
                val content = screen.getJSONObject("content")
                assertScreenTree(content, "$slug/$id")
                assertEquals(
                    emptySet<String>(),
                    statsIn(content),
                    "$slug/$id is static text and draws no live stat",
                )
            }
        }
    }

    // ---------------------------------------------------------------------------------------- widgets

    @Test
    fun minimal_vitals_is_five_compact_read_only_cards() {
        val def = cases.first { it.slug == "minimalvitals" }.def
        val widgetPaths = def.sectionFiles.keys.filter { it.startsWith("widgets/") }
        assertEquals(
            listOf(
                "widgets/vitals_snapshot.json",
                "widgets/vitals_cpu.json",
                "widgets/vitals_ram.json",
                "widgets/vitals_battery.json",
                "widgets/vitals_heat.json",
            ),
            widgetPaths,
            "five widgets in author order",
        )
        val allStats = linkedSetOf<String>()
        widgetPaths.forEach { path ->
            val w = section(def, path)
            val id = path.removePrefix("widgets/").removeSuffix(".json")
            assertEquals(Sdk.WIDGET_FORMAT, w.getString("format"), "$id widget format")
            assertEquals(Sdk.WIDGET_VERSION, w.getInt("version"), "$id widget version")
            assertEquals(id, w.getString("id"), "$id widget id")
            assertTrue(w.getString("size") in WidgetSizes.ALL, "$id widget size")
            assertTrue(w.getString("title").isNotBlank(), "$id widget title")
            val content = w.getJSONObject("content")
            assertOverlayTree(content, "minimalvitals/$id")
            assertStatsAreGranted(def, content, "minimalvitals/$id")
            allStats += statsIn(content)
        }
        assertEquals(
            setOf(OverlayStats.CPU, OverlayStats.RAM, OverlayStats.BATTERY, OverlayStats.TEMP),
            allStats,
            "the five cards cover CPU, memory, battery and temperature",
        )
    }

    @Test
    fun thermal_desk_is_a_thermal_dashboard_with_a_live_trend() {
        val def = cases.first { it.slug == "thermaldesk" }.def
        val widgetPaths = def.sectionFiles.keys.filter { it.startsWith("widgets/") }
        assertEquals(
            listOf(
                "widgets/thermal_now.json",
                "widgets/thermal_load.json",
                "widgets/thermal_plan.json",
                "widgets/thermal_trend.json",
            ),
            widgetPaths,
            "four widgets in author order",
        )
        val allStats = linkedSetOf<String>()
        widgetPaths.forEach { path ->
            val w = section(def, path)
            val id = path.removePrefix("widgets/").removeSuffix(".json")
            assertEquals(Sdk.WIDGET_FORMAT, w.getString("format"), "$id widget format")
            assertEquals(Sdk.WIDGET_VERSION, w.getInt("version"), "$id widget version")
            assertEquals(id, w.getString("id"), "$id widget id")
            assertTrue(w.getString("size") in WidgetSizes.ALL, "$id widget size")
            val content = w.getJSONObject("content")
            assertOverlayTree(content, "thermaldesk/$id")
            assertStatsAreGranted(def, content, "thermaldesk/$id")
            allStats += statsIn(content)
        }
        assertEquals(
            setOf(OverlayStats.CPU, OverlayStats.GPU, OverlayStats.TEMP),
            allStats,
            "the desk covers load and the battery-temperature feed",
        )
        val trend = section(def, "widgets/thermal_trend.json")
        assertEquals(WidgetSizes.FULL, trend.getString("size"), "the trend spans the full row")
        val content = trend.getJSONObject("content")
        assertEquals("chart", content.getString("type"), "the trend root is a chart")
        val props = content.getJSONObject("props")
        assertEquals(OverlayStats.TEMP, props.getString("stat"), "the chart names the temp feed")
        assertEquals(ChartKinds.LINE, props.getString("kind"), "the chart is a line")
        assertTrue(props.getString("values").isNotEmpty(), "the chart carries a fallback series")
        assertTrue(
            props.getString("values").length <= ChartBounds.MAX_VALUE_STRING_LENGTH,
            "the hand-emitted series fits the chart string cap",
        )
    }

    // -------------------------------------------------------------------------------------------- pill

    @Test
    fun battery_badge_is_a_two_figure_thermals_module() {
        val def = cases.first { it.slug == "batterybadge" }.def
        val pill = section(def, "pill.json")
        assertEquals(Sdk.PILL_FORMAT, pill.getString("format"), "pill format")
        assertEquals(Sdk.PILL_VERSION, pill.getInt("version"), "pill version")
        assertEquals("battery-badge", pill.getString("id"), "pill id")
        assertEquals(OverlaySizes.MEDIUM, pill.getString("size"), "pill size")
        assertEquals("Battery badge", pill.getString("title"), "pill title")
        val content = pill.getJSONObject("content")
        assertOverlayTree(content, "batterybadge/pill")
        assertStatsAreGranted(def, content, "batterybadge/pill")
        assertEquals(
            setOf(OverlayStats.BATTERY, OverlayStats.TEMP),
            statsIn(content),
            "the badge draws battery level and temperature only",
        )
    }

    // ------------------------------------------------------------------------------------------- rules

    @Test
    fun cooldown_ladder_is_staged_and_names_only_broker_actions() {
        val def = cases.first { it.slug == "cooldownladder" }.def
        val rules = section(def, "rules/rules.json").getJSONArray("rules")
        assertEquals(4, rules.length(), "four rules")
        assertEquals(
            listOf("warm_42_animations", "warm_45_saver", "warm_48_brightness", "battery_15_saver"),
            (0 until rules.length()).map { rules.getJSONObject(it).getString("id") },
            "rule ids in author order",
        )
        assertEquals(
            listOf(42.0, 45.0, 48.0),
            (0 until 3).map { rules.getJSONObject(it).getJSONArray("conditions").getJSONObject(0).getDouble("value") },
            "the thermal stages climb 42, 45, 48",
        )
        for (i in 0 until rules.length()) assertRuleIsWellFormed(rules.getJSONObject(i), "cooldownladder")
        assertEquals(
            Triggers.BATTERY_LOW,
            rules.getJSONObject(3).getString("trigger"),
            "the last rule is the low-battery backstop",
        )
        assertEquals(
            listOf(Actions.SET_LOW_POWER),
            rules.getJSONObject(3).getJSONArray("actions").let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            },
            "the backstop uses battery saver",
        )
    }

    @Test
    fun exit_restore_is_a_post_game_baseline_not_a_snapshot() {
        val def = cases.first { it.slug == "exitrestore" }.def
        val rules = section(def, "rules/rules.json").getJSONArray("rules")
        assertEquals(2, rules.length(), "two exit rules")
        for (i in 0 until rules.length()) {
            val rule = rules.getJSONObject(i)
            assertEquals(Triggers.GAME_CLOSED, rule.getString("trigger"), "both rules run at game close")
            assertRuleIsWellFormed(rule, "exitrestore")
        }
        val actions = rules.getJSONObject(0).getJSONArray("actions")
        assertEquals(
            listOf(Actions.SET_PEAK_REFRESH_RATE),
            (0 until actions.length()).map { actions.getString(it) },
            "the refresh rule applies the high-rate baseline",
        )
        val saver = rules.getJSONObject(1)
        assertEquals(
            listOf(Actions.SET_LOW_POWER),
            saver.getJSONArray("actions").let { arr -> (0 until arr.length()).map { arr.getString(it) } },
            "the low-battery rule turns saver on",
        )
        assertEquals(
            RuleVariables.BATTERY,
            saver.getJSONArray("conditions").getJSONObject(0).getString("variable"),
            "the saver rule watches battery level",
        )
        assertEquals(30.0, saver.getJSONArray("conditions").getJSONObject(0).getDouble("value"), 1e-9, "30% threshold")
    }

    @Test
    fun charger_boost_is_a_charging_profile_not_a_fast_charge_tool() {
        val def = cases.first { it.slug == "chargerboost" }.def
        val rules = section(def, "rules/rules.json").getJSONArray("rules")
        assertEquals(1, rules.length(), "one charger rule")
        val rule = rules.getJSONObject(0)
        assertEquals(Triggers.CHARGER_CONNECTED, rule.getString("trigger"), "charger-connected trigger")
        assertRuleIsWellFormed(rule, "chargerboost")
        val actions = rule.getJSONArray("actions")
        assertEquals(
            listOf(
                Actions.SET_PEAK_REFRESH_RATE,
                Actions.SET_MIN_REFRESH_RATE,
                Actions.SET_WINDOW_ANIMATION_SCALE,
                Actions.SET_TRANSITION_ANIMATION_SCALE,
                Actions.SET_ANIMATOR_DURATION_SCALE,
            ),
            (0 until actions.length()).map { actions.getString(it) },
            "the boost pins refresh and cuts animation overhead",
        )
        val conditions = rule.getJSONArray("conditions")
        assertEquals(2, conditions.length(), "two guards")
        assertEquals(RuleVariables.BATTERY, conditions.getJSONObject(0).getString("variable"), "battery guard")
        assertEquals(80.0, conditions.getJSONObject(0).getDouble("value"), 1e-9, "80% cap")
        assertEquals(RuleVariables.TEMP, conditions.getJSONObject(1).getString("variable"), "temperature guard")
        assertEquals(44.0, conditions.getJSONObject(1).getDouble("value"), 1e-9, "44 C cap")
    }

    private fun assertRuleIsWellFormed(rule: JSONObject, slug: String) {
        val id = rule.getString("id")
        assertTrue(rule.getString("trigger") in Triggers.ALL, "$slug/$id trigger")
        val actions = rule.getJSONArray("actions")
        assertTrue(actions.length() > 0, "$slug/$id has actions")
        assertTrue(actions.length() <= RuleBounds.MAX_ACTIONS_PER_RULE, "$slug/$id action cap")
        for (i in 0 until actions.length()) {
            assertTrue(actions.getString(i) in Actions.ALL, "$slug/$id action ${actions.getString(i)}")
        }
        val conditions = rule.optJSONArray("conditions") ?: return
        assertTrue(conditions.length() <= RuleBounds.MAX_CONDITIONS_PER_RULE, "$slug/$id condition cap")
        for (i in 0 until conditions.length()) {
            val condition = conditions.getJSONObject(i)
            assertTrue(condition.getString("variable") in RuleVariables.ALL, "$slug/$id variable")
            assertTrue(condition.getString("op") in RuleOperators.ALL, "$slug/$id operator")
        }
    }

    // -------------------------------------------------------------------------------------- icon + files

    @Test
    fun every_icon_section_is_one_of_the_hosts_own_slots() {
        cases.forEach { case ->
            val icon = section(case.def, "icon.json")
            assertEquals(
                expectedIconSlots.getValue(case.slug),
                icon.getString("slot"),
                "${case.slug} icon slot",
            )
            assertTrue(icon.getString("slot") in IconSlots.ALL, "${case.slug} icon slot is known")
        }
    }

    @Test
    fun packages_every_deliverable_and_refreshes_the_bundled_copies() {
        val root = repoRoot()
        cases.forEach { case ->
            val outDir = File(root, "dist/${case.slug}").apply {
                deleteRecursively()
                mkdirs()
            }
            val out = File(outDir, "${case.slug}.gcplugin")
            PluginPackager.writeTo(out, case.def)
            assertTrue(out.isFile, "${case.slug} artifact written")

            val ordered = ArrayList<String>()
            ZipInputStream(out.inputStream()).use { zin ->
                while (true) {
                    val entry = zin.nextEntry ?: break
                    ordered += entry.name
                    zin.closeEntry()
                }
            }
            assertEquals(
                listOf("manifest.json") + case.def.sectionFiles.keys.toList(),
                ordered,
                "${case.slug} manifest first, then sections in author order",
            )
            assertTrue(ordered.all { it.endsWith(".json") }, "${case.slug} package contains only JSON")

            ZipFile(out).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    assertTrue(entry.size <= 2 * 1024 * 1024, "${case.slug} ${entry.name} <= 2MiB")
                    val destination = File(outDir, entry.name)
                    destination.parentFile?.mkdirs()
                    destination.writeBytes(zip.getInputStream(entry).readBytes())
                }
            }

            val sha = MessageDigest.getInstance("SHA-256").digest(out.readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            File(outDir, "${case.slug}.gcplugin.sha256").writeText("$sha  ${case.slug}.gcplugin\n")
            println("${case.slug.uppercase()}_GCPLUGIN_SHA256=$sha")
            println("${case.slug.uppercase()}_GCPLUGIN_PATH=${out.absolutePath}")

            val assetDir = File(root, "app/src/main/assets/plugins/${case.slug}").apply {
                deleteRecursively()
                mkdirs()
            }
            File(assetDir, "manifest.json").writeBytes(case.def.manifestJson)
            case.def.sectionFiles.forEach { (path, bytes) ->
                val flat = when {
                    path == "screens/screens.json" -> "screens.json"
                    path == "rules/rules.json" -> "rules.json"
                    path.startsWith("widgets/") -> {
                        val id = path.removePrefix("widgets/").removeSuffix(".json")
                        if (id == case.bundledWidget) "widgets.json" else null
                    }
                    else -> path
                } ?: return@forEach
                File(assetDir, flat).writeBytes(bytes)
            }

            val expectedBundled = buildSet {
                add("manifest.json")
                add("icon.json")
                if ("theme" in expectedSections.getValue(case.slug)) add("theme.json")
                if ("pill" in expectedSections.getValue(case.slug)) add("pill.json")
                if ("screens" in expectedSections.getValue(case.slug)) add("screens.json")
                if ("rules" in expectedSections.getValue(case.slug)) add("rules.json")
                if ("widgets" in expectedSections.getValue(case.slug)) add("widgets.json")
            }
            assertEquals(
                expectedBundled,
                assetDir.listFiles().orEmpty().filter { it.isFile }.map { it.name }.toSet(),
                "${case.slug} bundled asset file set",
            )
        }
    }

    // -------------------------------------------------------------------------------------- contrast

    private fun contrast(foreground: String, background: String): Double {
        val a = luminance(foreground)
        val b = luminance(background)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    private fun luminance(hex: String): Double {
        val clean = hex.removePrefix("#")
        val rgb = if (clean.length == 8) clean.substring(2) else clean
        require(rgb.length == 6) { "not a six-digit colour: $hex" }
        fun channel(index: Int): Double {
            val value = rgb.substring(index, index + 2).toInt(16) / 255.0
            return if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(0) + 0.7152 * channel(2) + 0.0722 * channel(4)
    }
}
