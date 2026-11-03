package com.gamecore.studio.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * The capability boundary the studio promises, as data.
 *
 * This is the same boundary [PluginDraft.problems] enforces and
 * `docs/PLUGIN_STUDIO_CAPABILITIES.md` records, written once more for the debug MCP bridge so an
 * automated author can ask what is real instead of inferring it from the editor's controls. It is
 * deliberately explicit about the negative side: a missing feature is named, not omitted.
 *
 * The host is still the authority. This object says what the *studio* can represent and what the
 * host runtime consumes; it does not claim a package is installed, permitted or running.
 */
object StudioCapabilities {

    /** The bridge's own wire version, separate from the plugin API. */
    const val BRIDGE_PROTOCOL = 1

    fun asJson(): JSONObject = JSONObject()
        .put("bridgeProtocol", BRIDGE_PROTOCOL)
        .put("packageFormat", "gamecore.plugin")
        .put("studioPluginApi", com.gamecore.studio.model.PluginDraft.CURRENT_API)
        .put("minimumHostVersion", com.gamecore.studio.model.PluginDraft.DEFAULT_MIN_HOST_VERSION)
        .put(
            "sections",
            JSONArray(
                listOf(
                    section("theme", "theme.json", "Applies schema 1–4 colours, roles, type, glitch and glass."),
                    section("screens", "screens/screens.json", "Read-only component trees opened from the plugin detail page."),
                    section("overlay", "pill.json", "One fixed-size, non-touchable overlay while the host overlay service runs."),
                    section("settings", "settings.json", "Host-rendered bool, integer, float, enum and string controls."),
                    section("widgets", "widgets/<id>.json", "Bounded, read-only Home cards."),
                    section("rules", "rules/rules.json", "One edge trigger, a flat AND of conditions and allowlisted broker actions."),
                ),
            ),
        )
        .put(
            "components",
            JSONArray(
                listOf(
                    "column", "row", "grid", "scroll", "card", "text",
                    "stat", "spacer", "image", "chart",
                ),
            ),
        )
        .put(
            "chartSeries",
            JSONObject()
                .put("screensAndWidgets", JSONArray(listOf("fps", "cpu", "ram", "temperature")))
                .put("overlay", JSONArray(listOf("fps", "cpu", "gpu", "ram", "battery", "temperature", "motion"))),
        )
        .put(
            "automation",
            JSONObject()
                .put("triggers", 7)
                .put("variables", JSONArray(listOf("fps", "cpu", "gpu", "ram", "battery", "temp")))
                .put("operators", 6)
                .put("actions", 7)
                .put("shape", "edge-triggered; one trigger and a flat AND of at most 10 conditions")
                .put("noBranching", true)
                .put("noScheduling", true)
                .put("noCommandText", true),
        )
        .put(
            "limits",
            JSONObject()
                .put("screens", com.gamecore.studio.model.CreatorSections.MAX_SCREENS)
                .put("overlays", 1)
                .put("settings", com.gamecore.studio.model.CreatorSections.MAX_SETTINGS)
                .put("widgets", com.gamecore.studio.model.CreatorSections.MAX_WIDGETS)
                .put("rules", com.gamecore.studio.model.CreatorSections.MAX_RULES)
                .put("conditionsPerRule", com.gamecore.studio.model.RuleDraft.MAX_CONDITIONS)
                .put("actionsPerRule", com.gamecore.studio.model.RuleDraft.MAX_ACTIONS),
        )
        .put(
            "unsupported",
            JSONArray(
                listOf(
                    "touchable, resizable or multiple overlays",
                    "screen buttons or interactive controls with actions",
                    "plugin messaging or shared plugin storage",
                    "scheduled, branching, looping, retrying or parallel workflows",
                    "arbitrary intents, network access, shell commands or executable code",
                    "per-plugin assets, sounds, fonts or vectors",
                    "generic setting-to-screen/widget bindings",
                    "a marketplace",
                    "on-device signing with GameCore's release key",
                ),
            ),
        )
        .put(
            "authority",
            "Permissions in a package are requests. GameCore grants and enforces them after install; " +
                "the studio and the bridge cannot grant, install or run a plugin.",
        )

    private fun section(id: String, entry: String, behavior: String): JSONObject = JSONObject()
        .put("id", id)
        .put("entry", entry)
        .put("hostBehavior", behavior)
}
