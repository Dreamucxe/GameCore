package com.gamecore.core.plugin

/**
 * Every capability a plugin can ask for, and the risk of granting it.
 *
 * This enum is the whole of what a plugin can *request*. It is closed on purpose:
 * there is no "custom" permission, no string a package can invent, and two names
 * that look like permissions — network access and launching other apps — are absent
 * because a plugin is data and may not do either. A manifest that lists a name not
 * in this enum is rejected rather than ignored, so a typo or an attempt to smuggle a
 * reserved capability is a load failure the developer sees, not a silently dropped
 * line.
 *
 * The risk tier drives three things downstream: how prominently the install screen
 * warns, whether a grant needs more than one tap, and — for [SYSTEM_ACTIONS] — a
 * separate opt-in gate entirely. It is recorded here, next to the permission, so the
 * tier and the capability can never drift apart.
 *
 * Pure Kotlin; the UI reads [title]/[rationale]/[whatItTouches] to build the consent
 * screen and never hard-codes a permission's description.
 */
enum class PluginPermission(
    val id: String,
    val tier: RiskTier,
    val title: String,
    val rationale: String,
    val whatItTouches: String,
) {
    /**
     * Read a scoped slice of GameCore's own data — sessions, games, device stats,
     * thermals — never another app's and never raw. The *scope* (which slices) is
     * carried in the manifest alongside the grant; this permission is the gate, the
     * scope is the aperture.
     */
    READ_APPROVED_DATA(
        id = "read_approved_data",
        tier = RiskTier.LOW,
        title = "Read approved data",
        rationale = "Lets the plugin show your sessions, games, and device stats.",
        whatItTouches = "Only the data you approve — never other apps' data.",
    ),

    /** Re-theme the app: colours, fonts, component styling, declarative screens. */
    CHANGE_UI(
        id = "change_ui",
        tier = RiskTier.LOW,
        title = "Change the look",
        rationale = "Lets the plugin restyle GameCore's screens and components.",
        whatItTouches = "Appearance only — safety screens stay as GameCore drew them.",
    ),

    /** Register bounded modules into the Game Pill. */
    ADD_PILL_MODULES(
        id = "add_pill_modules",
        tier = RiskTier.LOW,
        title = "Add Game Pill modules",
        rationale = "Lets the plugin add readouts and controls to the Game Pill.",
        whatItTouches = "The Pill only — bounded modules, no full-screen takeover.",
    ),

    /** Draw a bounded overlay module while a game is in the foreground. */
    CREATE_OVERLAY(
        id = "create_overlay",
        tier = RiskTier.MEDIUM,
        title = "Show an overlay",
        rationale = "Lets the plugin draw a small panel over your game.",
        whatItTouches = "A bounded window — it cannot cover the screen or take touches from the game.",
    ),

    /**
     * Change GameCore's own settings, within declared scopes. Not device settings —
     * those are [SYSTEM_ACTIONS] through the broker — only GameCore's own
     * preferences.
     */
    MODIFY_SETTINGS(
        id = "modify_settings",
        tier = RiskTier.MEDIUM,
        title = "Change GameCore settings",
        rationale = "Lets the plugin adjust GameCore's own settings in the areas you approve.",
        whatItTouches = "GameCore's settings only, limited to the scopes it declares.",
    ),

    /** Run allowlisted automation rules: a trigger, conditions, and broker actions. */
    RUN_AUTOMATIONS(
        id = "run_automations",
        tier = RiskTier.MEDIUM,
        title = "Run automations",
        rationale = "Lets the plugin react to events with rules you can review.",
        whatItTouches = "Only the triggers and actions GameCore allows — no arbitrary code.",
    ),

    /** Ship Aim Lab content packs — primitive target shapes and scenarios only. */
    ADD_AIMLAB_CONTENT(
        id = "add_aimlab_content",
        tier = RiskTier.LOW,
        title = "Add Aim Lab content",
        rationale = "Lets the plugin add aim-training scenarios.",
        whatItTouches = "Target shapes and scenarios only — no humanoid or weapon content.",
    ),

    /**
     * Keep the plugin's own small store of settings and saved state in an isolated,
     * host-encrypted namespace. The store is the plugin's alone — it cannot read another
     * plugin's keys or GameCore's own, and the host caps how many keys and how much it may
     * keep. It touches no device setting and reads no GameCore data; it only remembers what
     * the plugin itself wrote.
     */
    STORE_DATA(
        id = "store_data",
        tier = RiskTier.LOW,
        title = "Save its own settings",
        rationale = "Lets the plugin remember your choices and its own saved state.",
        whatItTouches = "Only the plugin's own isolated store — never another plugin's data or GameCore's.",
    ),

    /** Add bounded, read-only widgets to GameCore's home dashboard. */
    ADD_DASHBOARD_WIDGETS(
        id = "add_dashboard_widgets",
        tier = RiskTier.LOW,
        title = "Add dashboard widgets",
        rationale = "Lets the plugin add small readouts to your home dashboard.",
        whatItTouches = "The dashboard only — bounded, read-only cards that take no touches.",
    ),

    /**
     * Request named, device-affecting actions through the Shizuku Action Broker —
     * refresh rate, performance mode, and the rest of the enumerated set. The
     * highest tier: this is the only permission that can change something outside
     * GameCore, and it is gated behind a separate "Advanced plugin access" opt-in on
     * top of the grant.
     */
    SYSTEM_ACTIONS(
        id = "system_actions",
        tier = RiskTier.HIGH,
        title = "Change device settings (advanced)",
        rationale = "Lets the plugin request system changes like refresh rate or performance mode.",
        whatItTouches = "Only GameCore's fixed, reviewed actions — never a raw command or shell.",
    );

    companion object {
        private val byId = entries.associateBy { it.id }

        /**
         * The permission with this id, or null — the loader turns null into a typed
         * rejection naming the unknown id. Reserved names (`network`,
         * `external_app`) resolve to null here by simply not existing, which is the
         * same path a typo takes; [isReserved] lets the loader give the more precise
         * "reserved" message instead of "unknown".
         */
        fun fromId(id: String): PluginPermission? = byId[id.trim().lowercase()]

        /**
         * Names that are not permissions and never will be in this run. A manifest
         * that lists one of these is refused with a message that says *why* rather
         * than "unknown permission", because a developer who wrote `network` is
         * owed the real reason it cannot be granted.
         */
        private val RESERVED = setOf("network", "internet", "external_app", "launch_app", "intent")

        fun isReserved(id: String): Boolean = id.trim().lowercase() in RESERVED
    }
}

/**
 * How much a permission can affect, used to drive consent friction. Ordered least to
 * most so comparisons (`tier >= MEDIUM`) read naturally.
 */
enum class RiskTier(val label: String) {
    LOW("Low risk"),
    MEDIUM("Medium risk"),
    HIGH("Advanced"),
}
