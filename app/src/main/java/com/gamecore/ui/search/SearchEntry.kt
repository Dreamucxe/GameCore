package com.gamecore.ui.search

import com.gamecore.aimlab.ui.home.AimLabRoutes
import com.gamecore.core.whatsnew.WhatsNewRegistry
import com.gamecore.ui.Destination

/**
 * The category a search entry belongs to. Used for grouping results in the UI.
 */
enum class SearchCategory(val displayName: String, val order: Int) {
    HOME("Home", -1),
    TRAINING("Training", 0),
    PERFORMANCE("Performance", 1),
    DISPLAY("Display", 2),
    PLUGINS("Plugins", 3),
    SETTINGS("Settings", 4),
    DATA("Data", 5),
    HELP("Help", 6),
}

/**
 * A single searchable entry in the global index.
 *
 * @param title The primary display name shown in results.
 * @param description A one-sentence summary of what the entry does.
 * @param keywords Additional searchable terms (synonyms, abbreviations, related words).
 * @param route The navigation route string (matches a [Destination.route] or [AimLabRoutes] constant).
 * @param category The section this entry is grouped under in results.
 */
data class SearchEntry(
    val title: String,
    val description: String,
    val keywords: List<String>,
    val route: String,
    val category: SearchCategory,
)

/**
 * A scored search result returned by [SearchIndex.search].
 *
 * @param entry The matching index entry.
 * @param score Higher is better. Exact title match = 1000, prefix = 500, word-start = 200, contains = 50.
 *              Title weight > keywords > description. Stable sort preserves declaration order for ties.
 */
data class SearchResult(
    val entry: SearchEntry,
    val score: Int,
)

/**
 * Static, compile-time search index covering every user-facing destination and feature.
 *
 * Pure Kotlin — no Android dependencies, no Hilt, no runtime reflection. The index is built once
 * as a [List] so it can be unit-tested on the JVM and used directly by the Compose screen.
 */
object SearchIndex {

    /** All entries, in declaration order (used as the tie-breaker for stable sorting). */
    val entries: List<SearchEntry> = buildList {

        // ============================================================ HOME
        add(SearchEntry(
            title = "Home",
            description = "The app's front door: the device card, search, and shortcuts to everything else.",
            keywords = listOf("home", "start", "main", "dashboard", "device", "search"),
            route = Destination.Home.route,
            category = SearchCategory.HOME,
        ))

        // ============================================================ TRAINING (Aim Lab)
        // Aim Lab section routes from AimLabRoutes
        add(SearchEntry(
            title = "Aim Lab Home",
            description = "The Aim Lab front door: training modes, setup editors and progress screens.",
            keywords = listOf("aimlab", "aim lab", "training", "practice"),
            route = AimLabRoutes.HOME,
            category = SearchCategory.TRAINING,
        ))
        add(SearchEntry(
            title = "Flick Training",
            description = "Snap onto targets that appear and vanish. Measures speed and precision.",
            keywords = listOf("flick", "snap", "reflex", "speed"),
            route = AimLabRoutes.FLICK,
            category = SearchCategory.TRAINING,
        ))
        add(SearchEntry(
            title = "Tracking Training",
            description = "Keep your aim on a target that keeps moving. Measures smooth pursuit.",
            keywords = listOf("tracking", "smooth", "pursuit", "follow"),
            route = AimLabRoutes.TRACKING,
            category = SearchCategory.TRAINING,
        ))
        add(SearchEntry(
            title = "Reaction Test",
            description = "Tap the moment a target shows. Measures response time.",
            keywords = listOf("reaction", "reflex", "response", "time"),
            route = AimLabRoutes.REACTION,
            category = SearchCategory.TRAINING,
        ))
        add(SearchEntry(
            title = "Gyro Training",
            description = "Aim by tilting the device. Gyro training, or tilt training on phones without a gyroscope.",
            keywords = listOf("gyro", "gyroscope", "tilt", "motion", "accelerometer"),
            route = AimLabRoutes.GYRO,
            category = SearchCategory.TRAINING,
        ))
        add(SearchEntry(
            title = "Recoil Training",
            description = "Pull down against a weapon's kick to hold the pattern.",
            keywords = listOf("recoil", "kick", "pattern", "control", "spray"),
            route = AimLabRoutes.RECOIL,
            category = SearchCategory.TRAINING,
        ))
        add(SearchEntry(
            title = "Movement Training",
            description = "Hit targets while strafing. Aim under movement.",
            keywords = listOf("movement", "strafe", "move", "dodge", "ads"),
            route = AimLabRoutes.MOVEMENT,
            category = SearchCategory.TRAINING,
        ))
        add(SearchEntry(
            title = "Free Practice",
            description = "An open sandbox. Not scored, but a run with shots in it is saved to history.",
            keywords = listOf("practice", "free", "sandbox", "warmup", "warm up"),
            route = AimLabRoutes.PRACTICE,
            category = SearchCategory.TRAINING,
        ))
        add(SearchEntry(
            title = "Adaptive Training Plan",
            description = "Today's recommended run, chosen from your own recorded sessions and personal records.",
            keywords = listOf("adaptive", "plan", "today", "recommendation", "coach", "what should i train", "training plan"),
            route = AimLabRoutes.HOME,
            category = SearchCategory.TRAINING,
        ))
        add(SearchEntry(
            title = "Sensitivity Lab",
            description = "Tune camera and gyro sensitivity, curves and deadzone.",
            keywords = listOf("sensitivity", "sens", "dpi", "curve", "deadzone", "eDPI"),
            route = AimLabRoutes.SENSITIVITY,
            category = SearchCategory.TRAINING,
        ))
        add(SearchEntry(
            title = "Weapon Editor",
            description = "Build fictional weapons: fire rate, recoil, spread.",
            keywords = listOf("weapon", "gun", "fire rate", "recoil", "spread", "build"),
            route = AimLabRoutes.WEAPON,
            category = SearchCategory.TRAINING,
        ))
        add(SearchEntry(
            title = "Control / HUD Editor",
            description = "Place the on-screen shoot, aim and stick controls.",
            keywords = listOf("controls", "hud", "layout", "editor", "onscreen", "touch"),
            route = AimLabRoutes.CONTROLS,
            category = SearchCategory.TRAINING,
        ))
        add(SearchEntry(
            title = "Statistics",
            description = "Your trends across every session you have run.",
            keywords = listOf("stats", "statistics", "trends", "progress", "charts"),
            route = AimLabRoutes.STATS,
            category = SearchCategory.TRAINING,
        ))
        add(SearchEntry(
            title = "Personal Records",
            description = "Your best result in each mode and difficulty.",
            keywords = listOf("records", "personal best", "pb", "best", "high score"),
            route = AimLabRoutes.RECORDS,
            category = SearchCategory.TRAINING,
        ))
        add(SearchEntry(
            title = "Session History",
            description = "Every session you have run. Open one for its full report.",
            keywords = listOf("history", "sessions", "past", "log", "list"),
            route = AimLabRoutes.HISTORY,
            category = SearchCategory.TRAINING,
        ))

        // ============================================================ PERFORMANCE
        add(SearchEntry(
            title = "Performance",
            description = "Governor, CPU/GPU tuning and the thermal readout. Also reachable from the dock.",
            keywords = listOf("performance", "governor", "cpu", "gpu", "thermal", "temperature", "fps", "frame rate", "frame time", "percentile", "p95", "p99", "thermal guard"),
            route = Destination.Performance.route,
            category = SearchCategory.PERFORMANCE,
        ))
        add(SearchEntry(
            title = "Network Stability",
            description = "Live connection readout: transport, Wi-Fi band, signal, link speed, ping and jitter.",
            keywords = listOf("network", "wifi", "ping", "jitter", "latency", "signal", "band", "stability", "connection"),
            route = Destination.NetworkStability.route,
            category = SearchCategory.PERFORMANCE,
        ))
        add(SearchEntry(
            title = "Graphics",
            description = "Render resolution preset and frame-rate cap — the two device levers that lighten what the system draws.",
            keywords = listOf("graphics", "resolution", "fps cap", "frame rate", "render", "downscale"),
            route = Destination.Graphics.route,
            category = SearchCategory.PERFORMANCE,
        ))
        add(SearchEntry(
            title = "Theme Engine",
            description = "Live backdrop driven by CPU, memory and temperature with manual overrides and save points.",
            keywords = listOf("theme", "engine", "aurora", "calibration", "backdrop", "colour", "color"),
            route = Destination.ThemeEngine.route,
            category = SearchCategory.PERFORMANCE,
        ))
        add(SearchEntry(
            title = "Battery Saver Override",
            description = "Per game, hold full performance even under battery saver.",
            keywords = listOf("battery", "saver", "override", "power", "performance"),
            route = Destination.Settings.route, // Settings screen has this section
            category = SearchCategory.PERFORMANCE,
        ))
        add(SearchEntry(
            title = "Memory Reclaim",
            description = "Free RAM on launch — the apps a profile will never close and what each game holds in cache.",
            keywords = listOf("memory", "ram", "reclaim", "cache", "free", "never close", "game storage"),
            route = Destination.GameStorage.route,
            category = SearchCategory.PERFORMANCE,
        ))
        add(SearchEntry(
            title = "Thermal Auto-Downshift",
            description = "Per game, step the refresh rate down when the device runs hot and back up once it has stayed cool.",
            keywords = listOf("thermal", "downshift", "throttle", "heat", "temperature", "refresh rate"),
            route = Destination.Settings.route,
            category = SearchCategory.PERFORMANCE,
        ))

        // ============================================================ DISPLAY
        add(SearchEntry(
            title = "HUD Builder",
            description = "Arrange built-in panels and plugin dashboard widgets into a saved layout.",
            keywords = listOf("hud", "heads up display", "builder", "layout", "panels", "widgets", "dashboard"),
            route = Destination.Hud.route,
            category = SearchCategory.DISPLAY,
        ))
        add(SearchEntry(
            title = "Crosshair",
            description = "Design and colour the crosshair overlay. Quick-select from the overlay panel.",
            keywords = listOf("crosshair", "reticle", "aim", "dot", "overlay"),
            route = Destination.Crosshair.route,
            category = SearchCategory.DISPLAY,
        ))
        add(SearchEntry(
            title = "Scout (Magnifier)",
            description = "Centre pixel-zoom pane with adjustable zoom factor and screen lift.",
            keywords = listOf("scout", "magnifier", "zoom", "pixel", "zoom"),
            route = Destination.Scout.route,
            category = SearchCategory.DISPLAY,
        ))
        add(SearchEntry(
            title = "Hunt (Colour Grade)",
            description = "Choose a Hunt filter preset and turn the full-screen grade on or off.",
            keywords = listOf("hunt", "colour grade", "color grade", "filter", "lut", "grade"),
            route = Destination.Hunt.route,
            category = SearchCategory.DISPLAY,
        ))
        add(SearchEntry(
            title = "Wheels (Sensitivity Rings)",
            description = "Feel-only guide-ring overlay and the sensitivity value the Aim Lab surface remaps against.",
            keywords = listOf("wheels", "rings", "guide", "sensitivity", "remap", "feel"),
            route = Destination.Wheels.route,
            category = SearchCategory.DISPLAY,
        ))
        add(SearchEntry(
            title = "Colour Correction",
            description = "Fourteen values, gamma mode and preset library. Also reachable from the overlay panel.",
            keywords = listOf("colour", "color", "correction", "gamma", "preset", "editor", "hue", "saturation"),
            route = Destination.Colour.route,
            category = SearchCategory.DISPLAY,
        ))
        add(SearchEntry(
            title = "Glass Control Center",
            description = "One slider for how much glass material the app is wearing, over a live preview panel.",
            keywords = listOf("glass", "control center", "material", "blur", "transparency", "gloss"),
            route = Destination.GlassCentre.route,
            category = SearchCategory.DISPLAY,
        ))
        add(SearchEntry(
            title = "Screen Extraction",
            description = "Capture the current screen, crop a region, then save or share it. Also on the dock.",
            keywords = listOf("screen", "extraction", "capture", "crop", "screenshot", "share"),
            route = Destination.ScreenExtraction.route,
            category = SearchCategory.DISPLAY,
        ))
        add(SearchEntry(
            title = "Touch Sampling Monitor",
            description = "See your device's real touch input rate live. Shows Unavailable when hardware can't report it.",
            keywords = listOf("touch", "sampling", "monitor", "hz", "rate", "input"),
            route = Destination.SamplingMonitor.route,
            category = SearchCategory.DISPLAY,
        ))

        // ============================================================ PLUGINS
        add(SearchEntry(
            title = "Plugin Manager",
            description = "Installed plugins with preview, rollback and quarantine alongside enable/disable, reset to default and the in-app marketplace.",
            keywords = listOf("plugins", "manager", "marketplace", "install", "enable", "disable", "theme", "preview", "rollback", "revert", "quarantine", "safety"),
            route = Destination.Plugins.route,
            category = SearchCategory.PLUGINS,
        ))
        add(SearchEntry(
            title = "Plugin Automation History",
            description = "Your automation history shown by a plugin that holds the automation_history read scope.",
            keywords = listOf("automation", "history", "plugin", "scope", "grant"),
            route = Destination.PluginAutomationHistory.route, // template, but searchable
            category = SearchCategory.PLUGINS,
        ))
        add(SearchEntry(
            title = "Dashboard Composer",
            description = "Arrange built-in panels and plugin dashboard widgets into one saved layout.",
            keywords = listOf("dashboard", "composer", "layout", "widgets", "panels", "arrange"),
            route = Destination.DashboardComposer.route,
            category = SearchCategory.PLUGINS,
        ))

        // ============================================================ SETTINGS
        // Top-level Settings sections (from SettingsScreen.kt sections)
        add(SearchEntry(
            title = "Settings Home",
            description = "How GameCore looks, measures and behaves.",
            keywords = listOf("settings", "preferences", "options", "configure"),
            route = Destination.Settings.route,
            category = SearchCategory.SETTINGS,
        ))
        add(SearchEntry(
            title = "Appearance",
            description = "Theme choice (Dark, Light, AMOLED), accent colour, wallpaper and app icon.",
            keywords = listOf("appearance", "theme", "dark", "light", "amoled", "accent", "wallpaper", "icon", "colour", "color"),
            route = Destination.Settings.route,
            category = SearchCategory.SETTINGS,
        ))
        add(SearchEntry(
            title = "Home Stats",
            description = "Hide GameCore's built-in Home metric tiles when a plugin dashboard already shows them.",
            keywords = listOf(
                "home stats", "home dashboard", "dashboard", "duplicate", "duplicates", "plugin",
                "plugin dashboard", "cpu", "ram", "battery", "temperature", "refresh rate", "statistics",
            ),
            route = Destination.Settings.route,
            category = SearchCategory.SETTINGS,
        ))
        add(SearchEntry(
            title = "Display & Text",
            description = "UI scale, text size preset and animation settings.",
            keywords = listOf("display", "text", "scale", "size", "font", "animation", "ui scale"),
            route = Destination.Settings.route,
            category = SearchCategory.SETTINGS,
        ))
        add(SearchEntry(
            title = "Behavior",
            description = "Automatic profile application, launch confirmation, keep screen on, and more.",
            keywords = listOf("behavior", "behaviour", "auto", "apply", "launch", "confirm", "screen on"),
            route = Destination.Settings.route,
            category = SearchCategory.SETTINGS,
        ))
        add(SearchEntry(
            title = "Overlays",
            description = "Stats pill, floating button, quick sheet, and the full overlay panel configuration.",
            keywords = listOf("overlays", "pill", "floating", "button", "sheet", "panel", "stats"),
            route = Destination.Overlay.route,
            category = SearchCategory.SETTINGS,
        ))
        add(SearchEntry(
            title = "Games",
            description = "Per-game profiles, step detection, resolution override and CPU affinity.",
            keywords = listOf("games", "profiles", "per game", "step detection", "resolution", "affinity", "cpu"),
            route = Destination.Games.route,
            category = SearchCategory.SETTINGS,
        ))
        add(SearchEntry(
            title = "Emulators (experimental)",
            description = "Detect emulators already installed, keep a library of ROM links and hand a file to one.",
            keywords = listOf(
                "emulator", "rom", "iso", "disc", "launcher", "experimental", "retroarch", "dolphin",
                "ppsspp", "winlator", "aethersx2", "melonds", "duckstation", "flycast",
            ),
            route = Destination.Emulators.route,
            category = SearchCategory.SETTINGS,
        ))
        add(SearchEntry(
            title = "Monitoring",
            description = "Performance HUD, sample intervals, latency host and recording quality.",
            keywords = listOf("monitoring", "hud", "sample", "interval", "latency", "host", "recording"),
            route = Destination.Settings.route,
            category = SearchCategory.SETTINGS,
        ))
        add(SearchEntry(
            title = "Network",
            description = "Latency host, ping interval, and network stability mode settings.",
            keywords = listOf("network", "latency", "ping", "host", "interval", "stability"),
            route = Destination.Settings.route,
            category = SearchCategory.SETTINGS,
        ))
        add(SearchEntry(
            title = "Recording",
            description = "Instant replay buffer length, quality, storage budget and thermal behaviour.",
            keywords = listOf("recording", "replay", "instant replay", "buffer", "quality", "storage", "budget"),
            route = Destination.Settings.route,
            category = SearchCategory.SETTINGS,
        ))
        add(SearchEntry(
            title = "Access & Tools",
            description = "Shizuku, permissions, quick trigger, never-close list, game storage, config browser and dashboard composer.",
            keywords = listOf("access", "tools", "shizuku", "permissions", "quick trigger", "never close", "game storage", "config", "browser"),
            route = Destination.Tools.route,
            category = SearchCategory.SETTINGS,
        ))
        add(SearchEntry(
            title = "Backup & Restore",
            description = "Save your setup and recorded history to one file, restore it, and choose how long sessions are kept.",
            keywords = listOf("backup", "restore", "export", "import", "transfer", "config", "history", "sessions", "retention", "keep", "delete old", "purge"),
            route = Destination.BackupRestore.route,
            category = SearchCategory.SETTINGS,
        ))
        add(SearchEntry(
            title = "Setup Wizard",
            description = "Guided first-run setup that turns on only the permissions your chosen features need.",
            keywords = listOf("setup", "wizard", "first run", "onboarding", "guide"),
            route = Destination.SetupWizard.route,
            category = SearchCategory.SETTINGS,
        ))
        add(SearchEntry(
            title = "Setup Health",
            description = "What is set up, what is not, and what each gap costs with a fix shortcut.",
            keywords = listOf("setup", "health", "status", "check", "verify", "gaps"),
            route = Destination.SetupHealth.route,
            category = SearchCategory.SETTINGS,
        ))
        add(SearchEntry(
            title = "Developer",
            description = "Who wrote this and where to find them. The last row of Settings.",
            keywords = listOf("developer", "about", "credits", "contact", "github", "source"),
            route = Destination.Developer.route,
            category = SearchCategory.SETTINGS,
        ))
        add(SearchEntry(
            title = "Charge Bypass",
            description = "Standalone control for the kernel charge-control node. Probe support and toggle by hand.",
            keywords = listOf("charge", "bypass", "charging", "kernel", "battery", "limit"),
            route = Destination.Charge.route,
            category = SearchCategory.SETTINGS,
        ))
        add(SearchEntry(
            title = "Dock Customisation",
            description = "Rearrange the floating dock's buttons, choose which appear, and add quick actions.",
            keywords = listOf("dock", "customisation", "customization", "buttons", "quick actions", "shortcuts"),
            route = Destination.DockCustomization.route,
            category = SearchCategory.SETTINGS,
        ))
        add(SearchEntry(
            title = "Quick Apps",
            description = "The apps the control panel's quick-launch row offers, and the order they sit in.",
            keywords = listOf("quick apps", "launch", "row", "control panel", "shortcuts"),
            route = Destination.QuickApps.route,
            category = SearchCategory.SETTINGS,
        ))
        add(SearchEntry(
            title = "Quick Trigger",
            description = "How to open GameCore quickly, and what each mechanism needs to work.",
            keywords = listOf("quick trigger", "trigger", "shortcut", "gesture", "hardware button", "open", "launch"),
            route = Destination.QuickTrigger.route,
            category = SearchCategory.SETTINGS,
        ))
        add(SearchEntry(
            title = "Never Close",
            description = "The apps GameCore will never close, whatever a profile asks for.",
            keywords = listOf("never close", "protect", "launcher", "background", "close", "kill", "allowlist"),
            route = Destination.NeverClose.route,
            category = SearchCategory.SETTINGS,
        ))
        add(SearchEntry(
            title = "Macro Editor",
            description = "Name, order and delete the one-tap macros that appear as a row on the quick sheet.",
            keywords = listOf("macro", "editor", "one tap", "quick sheet", "automation"),
            route = Destination.MacroEditor.route,
            category = SearchCategory.SETTINGS,
        ))
        add(SearchEntry(
            title = "Motion & Gyro",
            description = "Live gyroscope and accelerometer readings with the optional aim-motion summary.",
            keywords = listOf("motion", "gyro", "gyroscope", "accelerometer", "aim", "sensor"),
            route = Destination.Motion.route,
            category = SearchCategory.SETTINGS,
        ))
        add(SearchEntry(
            title = "Sensor Calibration",
            description = "A still capture of the gyroscope and accelerometer, with the bias, noise and drift the readings actually show.",
            keywords = listOf("sensor", "calibration", "calibrate", "gyro", "gyroscope", "accelerometer", "bias", "noise", "drift", "trace", "still"),
            route = Destination.SensorCalibration.route,
            category = SearchCategory.SETTINGS,
        ))
        add(SearchEntry(
            title = "Accessibility",
            description = "Larger text, stronger contrast, reduced motion and touch feedback for the interface.",
            keywords = listOf("accessibility", "large text", "font size", "contrast", "reduce motion", "haptics", "touch feedback"),
            route = Destination.Settings.route,
            category = SearchCategory.SETTINGS,
        ))
        add(SearchEntry(
            title = "Game Detection",
            description = "Choose which apps GameCore watches and which are excluded, with a pinned profile for the ones it should use.",
            keywords = listOf("detection", "allowlist", "blocklist", "exclude", "excluded", "games", "foreground", "usage access", "auto detect", "pinned"),
            route = Destination.Games.route,
            category = SearchCategory.SETTINGS,
        ))
        add(SearchEntry(
            title = "Touch Heatmap",
            description = "The touch heatmap: only the touches this app can legitimately observe.",
            keywords = listOf("touch", "heatmap", "taps", "visualise", "visualize"),
            route = Destination.Touch.route,
            category = SearchCategory.SETTINGS,
        ))
        add(SearchEntry(
            title = "Controller Lab",
            description = "Attached controllers, their axes, and the input they are sending.",
            keywords = listOf("controller", "gamepad", "axes", "input", "sticks", "triggers"),
            route = Destination.Controller.route,
            category = SearchCategory.SETTINGS,
        ))
        add(SearchEntry(
            title = "Capability Scanner",
            description = "GPU and video-codec capabilities as Android actually exposes them.",
            keywords = listOf("capability", "scanner", "gpu", "codec", "vulkan", "renderer", "hardware"),
            route = Destination.Capability.route,
            category = SearchCategory.SETTINGS,
        ))
        add(SearchEntry(
            title = "What's New",
            description = "See new features once on update, and browse all past updates any time.",
            keywords = listOf("whats new", "what's new", "changelog", "updates", "release notes", "history"),
            route = Destination.WhatsNew.route,
            category = SearchCategory.SETTINGS,
        ))

        // ============================================================ DATA
        add(SearchEntry(
            title = "Sessions",
            description = "Encrypted session history with per-session reports, tags and export.",
            keywords = listOf("sessions", "history", "reports", "export", "encrypted", "log"),
            route = Destination.Sessions.route,
            category = SearchCategory.DATA,
        ))
        add(SearchEntry(
            title = "Games List",
            description = "All games on the device with their profiles. Add, edit, or delete profiles.",
            keywords = listOf("games", "list", "profiles", "add", "edit", "delete"),
            route = Destination.Games.route,
            category = SearchCategory.DATA,
        ))
        add(SearchEntry(
            title = "Config Browser",
            description = "Browse a game's own configuration files and edit one in place. Needs Shizuku.",
            keywords = listOf("config", "browser", "files", "edit", "shizuku", "sandbox"),
            route = Destination.ConfigBrowser.route, // template
            category = SearchCategory.DATA,
        ))
        add(SearchEntry(
            title = "Config Editor",
            description = "Edit a config file in place, shown as a diff and written atomically with automatic backups.",
            keywords = listOf("config", "editor", "diff", "atomic", "backup", "write"),
            route = Destination.ConfigEditor.route, // template
            category = SearchCategory.DATA,
        ))

        // ============================================================ HELP
        add(SearchEntry(
            title = "Shizuku Setup",
            description = "Grant Shizuku so GameCore can use elevated shell for profiles, config editor and charge bypass.",
            keywords = listOf("shizuku", "setup", "grant", "shell", "elevated", "adb"),
            route = Destination.Shizuku.route,
            category = SearchCategory.HELP,
        ))
        add(SearchEntry(
            title = "Permissions",
            description = "Overlay permission, notification listener, usage access — what each one does and how to grant it.",
            keywords = listOf("permissions", "overlay", "notification", "usage", "access", "grant", "allow"),
            route = Destination.Permissions.route,
            category = SearchCategory.HELP,
        ))
        add(SearchEntry(
            title = "Device Fitness Check",
            description = "What this device needs for GameCore's features, and what each gap costs, with a fix for every actionable gap.",
            keywords = listOf("fitness", "device check", "compatibility", "readiness", "sensors", "thermal", "power saver", "refresh rate", "permissions", "capability"),
            route = Destination.Fitness.route,
            category = SearchCategory.HELP,
        ))
        add(SearchEntry(
            title = "Media Access",
            description = "What the media strip in the overlay panel reads, and the notification listener access it needs.",
            keywords = listOf("media", "access", "notification listener", "now playing", "song", "album"),
            route = Destination.MediaAccess.route,
            category = SearchCategory.HELP,
        ))

        // ============================================================ WHATS NEW FEATURES
        // Extract feature names from WhatsNewRegistry for searchability
        WhatsNewRegistry.releases.forEach { release ->
            release.features.forEach { feature ->
                add(SearchEntry(
                    title = feature.title,
                    description = feature.description,
                    keywords = listOf("whats new", "feature", "update", "release", "changelog"),
                    route = Destination.WhatsNew.route,
                    category = SearchCategory.HELP,
                ))
            }
        }
    }

    /**
     * Search the index for [query], returning up to [limit] results ranked by relevance.
     *
     * Ranking (higher score = better match):
     * - Exact title match (case-insensitive): 1000
     * - Title starts with query (prefix): 500
     * - Any word in title starts with query: 200
     * - Title contains query (substring): 100
     * - Exact keyword match: 400
     * - Keyword starts with query: 250
     * - Keyword contains query: 100
     * - Description contains query: 50
     *
     * Results are sorted by score descending, then by declaration order (stable sort).
     *
     * @param query The search string. Empty or whitespace returns empty list.
     * @param limit Maximum results to return (default 30).
     * @return List of [SearchResult] sorted by relevance.
     */
    fun search(query: String, limit: Int = 30): List<SearchResult> {
        val trimmed = query.trim().lowercase()
        if (trimmed.isBlank()) return emptyList()

        val results = mutableListOf<SearchResult>()

        entries.forEachIndexed { index, entry ->
            var score = 0
            val titleLower = entry.title.lowercase()
            val descLower = entry.description.lowercase()
            val keywordsLower = entry.keywords.map { it.lowercase() }

            // Title matches (highest weight)
            when {
                titleLower == trimmed -> score += 1000
                titleLower.startsWith(trimmed) -> score += 500
                titleLower.split(" ").any { it.startsWith(trimmed) } -> score += 200
                titleLower.contains(trimmed) -> score += 100
            }

            // Keyword matches (medium weight)
            keywordsLower.forEach { kw ->
                when {
                    kw == trimmed -> score += 400
                    kw.startsWith(trimmed) -> score += 250
                    kw.contains(trimmed) -> score += 100
                }
            }

            // Description matches (lowest weight)
            if (descLower.contains(trimmed)) {
                score += 50
            }

            if (score > 0) {
                results.add(SearchResult(entry, score))
            }
        }

        // Stable sort: by score descending, then by original index (declaration order)
        results.sortWith(compareByDescending<SearchResult> { it.score }
            .thenBy { entries.indexOf(it.entry) })

        return results.take(limit)
    }

    /**
     * Recent or popular entries to show when the query is empty.
     * Returns the top-level destinations and a few key features.
     */
    fun recentOrPopular(): List<SearchEntry> = entries.filter { entry ->
        entry.route in listOf(
            Destination.Home.route,
            Destination.Games.route,
            Destination.AimLabHome.route,
            Destination.Sessions.route,
            Destination.Settings.route,
            Destination.Performance.route,
            Destination.Hud.route,
            Destination.Crosshair.route,
            Destination.Overlay.route,
            Destination.Plugins.route,
            Destination.BackupRestore.route,
            Destination.SetupWizard.route,
            Destination.WhatsNew.route,
        )
    }
}