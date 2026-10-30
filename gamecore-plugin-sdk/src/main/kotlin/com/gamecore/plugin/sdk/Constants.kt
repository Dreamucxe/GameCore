package com.gamecore.plugin.sdk

/**
 * The build-time constants a plugin author writes against, mirroring the host's registries.
 *
 * These are plain strings on purpose: the SDK is a separate pure-JVM module and does not depend
 * on `:app`, so it cannot import the host enums. Parity is therefore a *mirror* rather than a
 * shared type — each set here is transcribed from the host's own enum and pinned by this
 * module's tests. The one way it can drift is a change on the host side that nobody reflected
 * here, which is a real risk and the reason every set below records what the host actually does
 * with a value that falls outside it. Those behaviours are not uniform: some are a clean
 * rejection on load, and some are a clamp, a truncation or a rule that quietly never fires.
 *
 * Writing them as named constants rather than raw strings in a plugin's DSL is what turns a
 * typo into a compile error in the plugin's own build — the earliest point it can surface, and
 * the only one where the author is still looking at the line that caused it.
 */
object Permissions {
    const val READ_APPROVED_DATA = "read_approved_data"
    const val CHANGE_UI = "change_ui"
    const val ADD_PILL_MODULES = "add_pill_modules"
    const val CREATE_OVERLAY = "create_overlay"
    const val MODIFY_SETTINGS = "modify_settings"
    const val RUN_AUTOMATIONS = "run_automations"
    const val ADD_AIMLAB_CONTENT = "add_aimlab_content"
    const val SYSTEM_ACTIONS = "system_actions"

    /** From API 3: a plugin's own isolated, host-encrypted key–value store. */
    const val STORE_DATA = "store_data"

    /** From API 3: register bounded, read-only widgets onto GameCore's home dashboard. */
    const val ADD_DASHBOARD_WIDGETS = "add_dashboard_widgets"

    /**
     * From host build 37: choose which of GameCore's launcher icons the app wears.
     *
     * Separate from [CHANGE_UI] because it is a different surface with a different lifetime — the
     * launcher icon is what the phone shows when GameCore is closed. The icon has to be one of
     * [IconSlots]: a package cannot supply a launcher icon, because Android resolves that from the
     * installed APK's manifest and no API takes a bitmap for it.
     */
    const val CHANGE_ICON = "change_icon"
}

/**
 * The launcher icons GameCore ships, by id. Exactly the ids the host's `AppIconSlot` accepts.
 *
 * Kept as constants so a typo is a compile error in the plugin's own build. If you need an icon that
 * is not here, the answer is not a string the host will reject — it is that the host has to ship it.
 *
 * **What this can and cannot do.** Naming a slot here changes the icon the launcher shows. Shipping
 * your own image (`icon(slot, artwork = ...)`) does not: Android will not put a package's bitmap in
 * its launcher slot, so the host offers that image as a *pinned home-screen shortcut* instead, which
 * the user has to accept in one tap.
 */
object IconSlots {
    const val DEFAULT = "default"
    const val MONO = "mono"
    const val GLITCH = "glitch"
    const val NEON = "neon"
    const val REDLINE = "redline"

    /** Every id the host accepts, in display order. */
    val ALL: Set<String> = linkedSetOf(DEFAULT, MONO, GLITCH, NEON, REDLINE)
}

/** Scopes valid on [Permissions.READ_APPROVED_DATA]. */
object DataScopes {
    const val SESSIONS = "sessions"
    const val GAMES = "games"
    const val DEVICE_STATS = "device_stats"
    const val THERMALS = "thermals"

    /** From API 3: device motion from the gyroscope and accelerometer, read through the stat feed. */
    const val MOTION = "motion"

    /**
     * From API 4: the user's own automation history — the trail of which rules fired and when —
     * which the host shows read-only to a plugin that holds this scope. Mirrors the host's
     * `DataScope.AUTOMATION_HISTORY`. The trail is empty until a rule actually fires, and a plugin
     * can never write to it or fabricate an entry: it is a window onto the user's data, not a store
     * the plugin owns. Unlike the overlay/widget sections, a scope is not a section, so a plugin that
     * reads this must set its manifest `api` to [Sdk.API_V4] itself — nothing bumps it automatically.
     */
    const val AUTOMATION_HISTORY = "automation_history"
}

/**
 * The fonts a theme may pick. A theme cannot ship a font file — it names a bundled face or a platform family.
 *
 * The host **rejects** an unknown id rather than falling back, so a theme naming a font that
 * is not here is a theme that does not load at all.
 */
object ThemeFonts {
    const val SYSTEM = "system"
    const val ROUNDED = "rounded"
    const val MONO = "mono"
    const val SERIF = "serif"

    /** The platform's cursive family; the exact face varies by device. */
    const val CURSIVE = "cursive"

    /** A bundled pixel face inspired by Minecraft, not the proprietary Minecraft typeface. */
    const val MINECRAFT = "minecraft"

    /** Bundled serif face used by the Liquid Glass font themes. */
    const val CINZEL = "cinzel"

    /** Bundled hand-lettered face used by the Liquid Glass font themes. */
    const val COMIC_NEUE = "comic-neue"

    /** Bundled neutral UI face used by the Liquid Glass font themes. */
    const val INTER = "inter"

    /** Bundled monospaced face used by the Liquid Glass font themes. */
    const val JETBRAINS_MONO = "jetbrains-mono"

    /** Bundled geometric display face used by the Liquid Glass font themes. */
    const val ORBITRON = "orbitron"

    /** Bundled arcade pixel face used by the Liquid Glass font themes. */
    const val PRESS_START_2P = "press-start-2p"

    /** Bundled rounded sans face used by the Liquid Glass font themes. */
    const val RUBIK = "rubik"

    /** Bundled terminal face used by the Liquid Glass font themes. */
    const val VT323 = "vt323"

    val ALL = setOf(
        SYSTEM,
        ROUNDED,
        MONO,
        SERIF,
        CURSIVE,
        MINECRAFT,
        CINZEL,
        COMIC_NEUE,
        INTER,
        JETBRAINS_MONO,
        ORBITRON,
        PRESS_START_2P,
        RUBIK,
        VT323,
    )
}

/**
 * The bounds the host's theme codec enforces.
 *
 * Same split as [OverlayBounds]: the host clamps the two numbers and truncates the name, and
 * this SDK refuses instead, because a theme the host quietly adjusted is one the author never
 * saw on their own screen.
 */
object ThemeBounds {
    /** The theme's display name, truncated host-side. */
    const val MAX_NAME_LENGTH = 48

    /** Corner radius in dp, clamped host-side. */
    const val MIN_CORNER_DP = 0
    const val MAX_CORNER_DP = 48

    /** The density nudge, clamped host-side. A theme may lean the UI, not rebuild it. */
    const val MIN_UI_SCALE = 0.8
    const val MAX_UI_SCALE = 1.4

    /** From schema 2: the type-ramp scale, clamped host-side. A theme leans the ramp, not rebuilds it. */
    const val MIN_TYPE_SCALE = 0.85
    const val MAX_TYPE_SCALE = 1.30
    const val DEFAULT_TYPE_SCALE = 1.0
}

/**
 * The theme schema versions, mirroring the host's `PluginTheme.SCHEMA_*` constants.
 *
 * A theme that uses only the v1 surface emits no `schema` key and stays an API-1 plugin. The
 * moment it reaches for a v2 feature — an AMOLED base, a semantic role, a type control — the DSL
 * stamps `schema: V2` into the theme and raises the manifest `api` to match, because a build that
 * predates v2 would read those keys as unknown and reject the whole theme.
 */
object ThemeSchema {
    const val V1 = 1
    const val V2 = 2

    /**
     * Schema 3 adds `effects`, whose only member today is the `glitch` motion block.
     *
     * A theme that asks for motion must declare this, because a host that predates it rejects the
     * whole theme rather than rendering the palette without the movement the author designed it
     * around — see the host's `PluginTheme.SCHEMA_V3`.
     */
    const val V3 = 3

    /**
     * Schema 4 adds the `glass` material block to `effects`: translucent surfaces, a specular rim and
     * top highlight, an optional drifting sheen, a backdrop blur and the spring the panel settles with.
     *
     * A host that predates it rejects the theme rather than rendering an opaque stand-in, for the same
     * reason schema 3 does: a glass theme whose transparency silently became a flat fill is not the
     * design the author made, and the user has no way to tell that from a rendering bug.
     */
    const val V4 = 4
    const val CURRENT = V4
}

/**
 * The motion character a glass panel settles with, mirroring the host's `PluginGlass.Spring`.
 *
 * Named after the feel rather than the numbers, because the numbers are the host's to own: a plugin
 * says "this should land like a switch" and the host decides what stiffness that is on the device it
 * is running on. [NONE] is not "fast" — it is the absence of motion, which is what a user who turned
 * animations off must get.
 */
object GlassSprings {
    const val NONE = "none"
    const val GENTLE = "gentle"
    const val SNAPPY = "snappy"
    const val BOUNCY = "bouncy"

    val ALL = setOf(NONE, GENTLE, SNAPPY, BOUNCY)
}

/**
 * Bounds for a schema-4 `glass` block, mirroring the host's `PluginGlass`.
 *
 * The host clamps every one of these, so a value outside a bound is not rejected — it ships as the
 * nearest legal value, which is a theme that renders differently from the one that was authored. The
 * DSL asserts instead of clamping so that lands in the author's build, not in the user's hands.
 */
object GlassBounds {
    /** How far every other value leans toward its maximum. In [MIN_INTENSITY]..[MAX_INTENSITY]. */
    const val MIN_INTENSITY = 0.0
    const val MAX_INTENSITY = 1.0

    /**
     * How see-through the theme's own surfaces are at full intensity: 0 leaves the authored colours
     * exactly as they are, 1 lets a surface show almost all of what is behind it.
     *
     * This multiplies what the theme's colours already carry rather than replacing it, so a theme that
     * ships `#CC101216` keeps its own alpha as the ceiling and this only ever removes more.
     */
    const val MIN_TRANSPARENCY = 0.0
    const val MAX_TRANSPARENCY = 1.0

    /** The specular rim on the panel's top edge and the highlight that falls across its face. */
    const val MIN_GLOSS = 0.0
    const val MAX_GLOSS = 1.0

    /**
     * The backdrop blur radius in dp.
     *
     * Honest about the platform: Android applies a real blur from API 31, and the host renders the
     * translucency, rim and sheen without it below that. A device under 31 still gets glass — it gets
     * glass with a sharp backdrop, and the Control Center says so rather than pretending.
     */
    const val MIN_BLUR_DP = 0
    const val MAX_BLUR_DP = 32

    /** The period of one drift of the highlight across the panel. */
    const val MIN_SHEEN_MS = 1500
    const val MAX_SHEEN_MS = 20000

    /** The longest a bound setting id may be — the same cap the host puts on a setting id. */
    const val MAX_SETTING_ID_LENGTH = 64
}

/**
 * Bounds for a schema-3 `glitch` block, mirroring the host's `PluginGlitch`.
 *
 * The host clamps every one of these, so a value outside a bound is not rejected — it ships as the
 * nearest legal value, which is a theme that renders differently from the one that was authored.
 * The DSL asserts instead of clamping so that lands in the author's build, not in the user's hands.
 */
object GlitchBounds {
    const val MIN_INTENSITY = 0.0
    const val MAX_INTENSITY = 1.0

    const val MIN_SLICES = 2
    const val MAX_SLICES = 6

    const val MIN_SLICE_OFFSET_DP = 0
    const val MAX_SLICE_OFFSET_DP = 48

    const val MIN_GHOST_OFFSET_DP = 0
    const val MAX_GHOST_OFFSET_DP = 12

    const val MIN_BURST_MS = 120
    const val MAX_BURST_MS = 8000
}

/**
 * The seven Material roles a v2 theme may recolour beyond the v1 six. Each is an optional
 * `#RRGGBB` / `#AARRGGBB` colour; a role left unset keeps the house scheme's own value.
 */
object ThemeRoles {
    const val SECONDARY = "secondary"
    const val TERTIARY = "tertiary"
    const val SURFACE_VARIANT = "surfaceVariant"
    const val ON_SURFACE_VARIANT = "onSurfaceVariant"
    const val OUTLINE = "outline"
    const val ERROR = "error"
    const val ON_ERROR = "onError"

    val ALL = setOf(SECONDARY, TERTIARY, SURFACE_VARIANT, ON_SURFACE_VARIANT, OUTLINE, ERROR, ON_ERROR)
}

/**
 * The global type weights a v2 theme may name. Unset (null) keeps each Material role's own weight —
 * the ramp draws display and label text at different weights on purpose.
 */
object ThemeWeights {
    const val LIGHT = "light"
    const val NORMAL = "normal"
    const val MEDIUM = "medium"
    const val BOLD = "bold"

    val ALL = setOf(LIGHT, NORMAL, MEDIUM, BOLD)
}

/**
 * The closed set of overlay-module sizes, as `pill.json` spells them.
 *
 * A size is an id and not a width/height pair on purpose: a module that could name its own
 * dimensions could grow itself into a full-screen surface by arithmetic, and "bounded" would
 * then be a promise the format could not keep. The host maps each id to fixed dp —
 * `small 120×48`, `medium 180×72`, `large 240×112` — and rejects an id it does not know.
 */
object OverlaySizes {
    const val SMALL = "small"
    const val MEDIUM = "medium"
    const val LARGE = "large"

    val ALL = setOf(SMALL, MEDIUM, LARGE)
}

/**
 * The closed set of dashboard-widget sizes, as a `widgets/<id>.json` spells them.
 *
 * Like [OverlaySizes], a size is an id and not a width/height pair: a widget that could name its own
 * dimensions could grow itself across the whole dashboard by arithmetic, and "bounded" would then be a
 * promise the format could not keep. The host maps each id to a fixed span in its dashboard grid and
 * rejects an id it does not know — a small tile, a medium and a large card, and [FULL] for the
 * full-width row a chart usually wants.
 */
object WidgetSizes {
    const val SMALL = "small"
    const val MEDIUM = "medium"
    const val LARGE = "large"
    const val FULL = "full"

    val ALL = setOf(SMALL, MEDIUM, LARGE, FULL)
}

/**
 * The stats a `stat` node in an overlay module may name.
 *
 * A module shows GameCore's own approved readings and nothing else — there is no game-memory
 * read, no screen read, no sensor a plugin reaches directly — so this set is the whole of what
 * a module can display, and an unknown id is a rejection rather than a blank field.
 *
 * Each stat sits behind a [Permissions.READ_APPROVED_DATA] scope, which is the question every
 * author gets wrong first: see [scopeFor]. Naming a stat whose scope the manifest does not
 * request is not a parse error — the module loads and the host declines to fill that field,
 * which is a worse outcome than a build failure, so check it here.
 */
object OverlayStats {
    const val FPS = "fps"
    const val CPU = "cpu"
    const val GPU = "gpu"
    const val RAM = "ram"
    const val BATTERY = "battery"
    const val TEMP = "temp"

    /** From API 3: the two gyroscope/accelerometer readings, each read through the `motion` scope. */
    const val ROTATION_RATE = "rotation_rate"
    const val MOVEMENT = "movement"

    /** The motion subset, for the DSL's per-use api bump to [Sdk.API_V3]. */
    val MOTION_READINGS = setOf(ROTATION_RATE, MOVEMENT)

    val ALL = setOf(FPS, CPU, GPU, RAM, BATTERY, TEMP, ROTATION_RATE, MOVEMENT)

    /**
     * The [DataScopes] id a stat is read through, or null if the stat is unknown.
     *
     * The split is the host's `DataScope`, not a convenience grouping: frame rate and load
     * figures are `device_stats`, while battery level and temperature are `thermals`, because
     * a plugin that draws a frame counter has no business seeing the device's heat.
     */
    fun scopeFor(stat: String): String? = when (stat) {
        FPS, CPU, GPU, RAM -> DataScopes.DEVICE_STATS
        BATTERY, TEMP -> DataScopes.THERMALS
        ROTATION_RATE, MOVEMENT -> DataScopes.MOTION
        else -> null
    }
}

/**
 * The icons an `image` node in an overlay module may name.
 *
 * **A module ships no images.** There is no path by which the host loads a byte of plugin
 * artwork — not from the package, not from a file, not from a URL — so an `image` node names
 * one of the twenty vectors already compiled into GameCore, and names nothing else. An unknown
 * name draws a neutral placeholder on the host, which is a blank where a glyph was meant, so
 * this SDK rejects it at the call instead.
 *
 * The names are GameCore's own vocabulary rather than the icon library's, which is what lets
 * the host re-point one at a better vector without breaking a plugin that already shipped.
 */
object OverlayIcons {
    const val SPEED = "speed"
    const val MEMORY = "memory"
    const val THERMOSTAT = "thermostat"
    const val BATTERY = "battery"
    const val BOLT = "bolt"
    const val TIMER = "timer"
    const val TIMELINE = "timeline"
    const val DATA = "data"
    const val INSIGHTS = "insights"
    const val SENSORS = "sensors"
    const val STORAGE = "storage"
    const val SHIELD = "shield"
    const val EXTENSION = "extension"
    const val INFO = "info"
    const val WARNING = "warning"
    const val BLOCK = "block"
    const val CHECK = "check"
    const val DOT = "dot"
    const val GAME = "game"
    const val SETTINGS = "settings"

    val ALL = setOf(
        SPEED, MEMORY, THERMOSTAT, BATTERY, BOLT, TIMER, TIMELINE, DATA, INSIGHTS, SENSORS,
        STORAGE, SHIELD, EXTENSION, INFO, WARNING, BLOCK, CHECK, DOT, GAME, SETTINGS,
    )
}

/**
 * The bounds the host's overlay-module codec enforces, mirrored so an author hits them at
 * build time instead of on a device.
 *
 * They are far tighter than a full plugin screen's caps, and that is the point: a module is a
 * readout a few nodes deep in a window about the size of a notification, drawn over someone's
 * game in a window that cannot take touches. The host runs its own `ScreenValidator` with
 * exactly these numbers.
 *
 * Where the host *clamps* — anchor and opacity — this SDK **refuses** instead. A clamp is the
 * right call at load time, when the alternative is a user with no module; it is the wrong call
 * at authoring time, because a layout the host quietly moved is one the author never saw.
 */
object OverlayBounds {
    /** Total nodes in a module's tree, root included. */
    const val MAX_NODES = 40

    /** Depth below the root, so a chain of `MAX_DEPTH + 1` nodes is the deepest legal tree. */
    const val MAX_DEPTH = 6

    /** Columns in a `grid` node. */
    const val MAX_GRID_COLUMNS = 4

    /** Any single string prop a node draws. */
    const val MAX_STRING_LENGTH = 120

    /**
     * The module title — much shorter than a string prop, and for a different reason.
     *
     * A prop is drawn inside the module's own window, where the cap is about layout. The title
     * is drawn in GameCore's UI, next to the switch that turns the module off, where the cap is
     * about the user still being able to read the row they are about to tap. The host truncates
     * to this length rather than refusing, so a title over it ships and silently shortens; this
     * SDK refuses, for the reason in this object's own note.
     */
    const val MAX_TITLE_LENGTH = 48

    /** Children of one container node. */
    const val MAX_CHILDREN = 12

    /** The module id, which is also its key in the host's module registry. */
    const val MAX_ID_LENGTH = 64

    /**
     * The grammar an id must match, mirroring the host codec's own pattern.
     *
     * Lowercase because an id is a key and not a label, and a set of ids that differ only in
     * case is a set a user cannot tell apart in a log.
     *
     * The host trims and lowercases before it matches, and **rejects** whatever still does not
     * fit — so an id outside this pattern either arrives under a different name than the author
     * wrote (`"My Badge"` → `my badge`, which then fails on the space) or does not arrive at
     * all. This SDK refuses both, because an id is the one string in a module that has to be
     * the same in the source and in the registry.
     */
    val ID_PATTERN = Regex("^[a-z0-9][a-z0-9._-]{0,${MAX_ID_LENGTH - 1}}$")

    /** Anchor fractions, not pixels, so a position survives rotation and a change of device. */
    const val MIN_ANCHOR = 0.0
    const val MAX_ANCHOR = 1.0

    /** Never fully transparent: an invisible window the user cannot find is worse than a faint one. */
    const val MIN_OPACITY = 20
    const val MAX_OPACITY = 100

    /** Legible over bright game art without hiding it. */
    const val DEFAULT_OPACITY = 85
}

/**
 * The bounds the host's screen codec enforces at its DEFAULT (full-screen) caps, mirrored so an author
 * hits them at build time instead of on a device. These are far wider than [OverlayBounds]: a plugin
 * screen is a full, scrollable, touchable surface the host draws under its own theme, not a readout in
 * a notification-sized window. The host runs its own ScreenValidator with exactly these numbers — 500
 * nodes, depth 24, 8 grid columns, 2000-char strings, 100 children per container.
 *
 * Where this SDK could truncate — the screen title — it **refuses** instead, as [OverlayBounds] does,
 * because a title the host quietly shortened is one the author never saw on their own screen.
 */
object ScreenBounds {
    /** Total nodes in a screen's tree, root included. */
    const val MAX_NODES = 500

    /** Depth below the root, so a chain of `MAX_DEPTH + 1` nodes is the deepest legal tree. */
    const val MAX_DEPTH = 24

    /** Columns in a `grid` node. */
    const val MAX_GRID_COLUMNS = 8

    /** Any single string prop a node draws — far wider than an overlay's. */
    const val MAX_STRING_LENGTH = 2_000

    /** Children of one container node. */
    const val MAX_CHILDREN = 100

    /** The screen title, refused rather than truncated (see this object's note). */
    const val MAX_TITLE_LENGTH = 64

    /** Screens in one `screens/<id>.json` file; the host caps the per-plugin total the same way. */
    const val MAX_SCREENS = 32
}

/**
 * The bounds the host's dashboard-widget codec enforces, mirrored so an author meets them at build
 * time instead of on a device.
 *
 * A widget's own node tree is held to the overlay caps ([OverlayBounds]) rather than a screen's, and
 * not by coincidence: a dashboard widget is a bounded, read-only readout the size of a card, built from
 * the very same [OverlayNodeScope] an overlay module is — so a value an overlay node already checks
 * (grid width, string length) is checked against the overlay cap by construction, and the tree walk in
 * [WidgetBuilder] follows the same cap for coherence. The one bound a widget needs of its own is how
 * many of them a plugin may register, below.
 */
object WidgetBounds {
    /**
     * Widgets one plugin may register on the dashboard. A dashboard has room for a bounded number of
     * cards, so the SDK caps the count here rather than letting an author ship a manifest the host
     * would thin out on install.
     */
    const val MAX_WIDGETS = 16
}

/**
 * The broker actions an automation rule may name. These are the only device actions a plugin
 * can reach, and a rule names one — it never carries a command, a key or a value of its own.
 *
 * The host resolves each id against the real broker registry and **rejects** the rules file on
 * one it does not know, so this set is exhaustive by construction rather than by convention.
 */
object Actions {
    const val SET_PEAK_REFRESH_RATE = "set_peak_refresh_rate"
    const val SET_MIN_REFRESH_RATE = "set_min_refresh_rate"
    const val SET_WINDOW_ANIMATION_SCALE = "set_window_animation_scale"
    const val SET_TRANSITION_ANIMATION_SCALE = "set_transition_animation_scale"
    const val SET_ANIMATOR_DURATION_SCALE = "set_animator_duration_scale"
    const val SET_LOW_POWER = "set_low_power"
    const val SET_SCREEN_BRIGHTNESS = "set_screen_brightness"

    val ALL = setOf(
        SET_PEAK_REFRESH_RATE, SET_MIN_REFRESH_RATE, SET_WINDOW_ANIMATION_SCALE,
        SET_TRANSITION_ANIMATION_SCALE, SET_ANIMATOR_DURATION_SCALE, SET_LOW_POWER,
        SET_SCREEN_BRIGHTNESS,
    )
}

/**
 * Automation triggers, matching the host `AutomationRule.Trigger` set.
 *
 * The host resolves a trigger by name and **rejects** the whole rules file if one does not
 * resolve, so an unknown trigger costs the plugin every rule in it, not just the one.
 */
object Triggers {
    const val GAME_LAUNCHED = "GAME_LAUNCHED"
    const val GAME_CLOSED = "GAME_CLOSED"
    const val SESSION_STARTED = "SESSION_STARTED"
    const val SESSION_ENDED = "SESSION_ENDED"
    const val THERMAL_HIGH = "THERMAL_HIGH"
    const val BATTERY_LOW = "BATTERY_LOW"
    const val CHARGER_CONNECTED = "CHARGER_CONNECTED"

    val ALL = setOf(
        GAME_LAUNCHED, GAME_CLOSED, SESSION_STARTED, SESSION_ENDED,
        THERMAL_HIGH, BATTERY_LOW, CHARGER_CONNECTED,
    )
}

/** The comparison operators a rule condition may use, matching the host `Condition.Op` set. */
object RuleOperators {
    const val EQ = "EQ"
    const val NEQ = "NEQ"
    const val LT = "LT"
    const val LTE = "LTE"
    const val GT = "GT"
    const val GTE = "GTE"

    val ALL = setOf(EQ, NEQ, LT, LTE, GT, GTE)
}

/**
 * The variables a rule condition may compare against — the same approved figures a module can
 * draw, which is not a coincidence: both read the one stat feed a plugin is allowed to see.
 *
 * **This is the set with the quietest failure in the whole SDK, and the only one the host does
 * not police at all.** The rules codec copies `variable` through verbatim; nothing resolves it.
 * The engine then treats an unknown name as an unmet condition rather than an error — its own
 * words: *"a rule referencing a variable the feed does not provide simply never fires"*. So a
 * thermal rule that says `temp_c` where the feed says `temp` installs, validates, shows up in
 * the plugin's rule list, and protects nothing. There is no log line, and no device state that
 * looks different from the rule's conditions simply not being met yet.
 *
 * Because the host has no registry to mirror, this set is the vocabulary the stat feed actually
 * publishes. Checking against it can in principle refuse a name a later feed would provide, and
 * that is the deliberate trade: a refused name costs an author one line, while an accepted one
 * ships a rule that never runs.
 */
object RuleVariables {
    const val FPS = OverlayStats.FPS
    const val CPU = OverlayStats.CPU
    const val GPU = OverlayStats.GPU
    const val RAM = OverlayStats.RAM
    const val BATTERY = OverlayStats.BATTERY
    const val TEMP = OverlayStats.TEMP

    /**
     * The six figures the automation feed actually publishes. Decoupled from [OverlayStats.ALL] on
     * purpose: motion joined the overlay vocabulary but NOT the automation feed, and because the host
     * reads an unknown rule variable as a condition that is simply never met, aliasing the two would
     * let an author ship a motion rule that installs and silently never fires. These are exactly the
     * overlay stats whose scope is `device_stats` or `thermals`.
     */
    val ALL = setOf(FPS, CPU, GPU, RAM, BATTERY, TEMP)
}

/**
 * The bounds the host puts on a rules file.
 *
 * [MAX_ACTIONS_PER_RULE] is the one worth knowing: the engine's installer-side validator
 * refuses a rule over it, and its evaluator independently `take`s the first few — so a rule
 * that somehow reached a device with more would run a prefix of what its author wrote. A rule
 * with no actions at all is likewise a validation failure, not a no-op.
 */
object RuleBounds {
    const val MAX_RULES = 32
    const val MAX_ACTIONS_PER_RULE = 8
    const val MAX_CONDITIONS_PER_RULE = 10
}

/**
 * The settings-schema versions. v1 is the only version today and, like a v1 theme, a
 * `settings.json` emits no `schema` key at all — the host defaults an absent key to [V1]. A
 * future additive version would stamp `schema: V2` and bump the manifest api, exactly as the
 * theme schema does.
 */
object SettingsSchema {
    const val V1 = 1
    const val CURRENT = 1
}

/**
 * The five setting types a plugin may declare. The host rejects the whole `settings.json` on a
 * type outside this set, so naming one here turns a typo into a compile error in the plugin's
 * own build.
 */
object SettingTypes {
    const val BOOL = "bool"
    const val INT = "int"
    const val FLOAT = "float"
    const val ENUM = "enum"
    const val STRING = "string"

    val ALL = setOf(BOOL, INT, FLOAT, ENUM, STRING)
}

/**
 * The limits a settings section is held to, mirrored from the host's `PluginSettingsCodec`.
 * Every one is **refused** by the DSL rather than clamped by the host: a settings file the host
 * would reject, or a default it would have had to move into range, is one the author should see
 * fail at build time.
 *
 * [ID_PATTERN] is the setting-id grammar, and it is also the plugin's storage-key grammar — the
 * id a setting declares is the key its value is stored under. It is identical to the host's
 * `PluginSettingsCodec` id grammar, so an id that compiles here is one the host's namespace will
 * always accept.
 */
object SettingBounds {
    const val MAX_SETTINGS = 64
    const val MAX_ID_LENGTH = 64
    const val MAX_ENUM_OPTIONS = 32
    const val MAX_STRING_DEFAULT = 256

    val ID_PATTERN = Regex("^[a-z0-9][a-z0-9._-]{0,${MAX_ID_LENGTH - 1}}$")
}

/**
 * The limits an icon section is held to, mirrored from the host's `PluginIconCodec` and
 * `PluginIconArtwork`. Unlike the theme's corner radius, none of these is clamped by the host — an
 * oversized or unreadable image refuses the whole `icon.json`, taking the slot request down with
 * it — so the DSL refuses them here, in the author's build.
 */
object IconBounds {
    /** The largest image a package may offer as shortcut artwork. */
    const val MAX_ARTWORK_BYTES = 512 * 1024

    /** The longest edge, in pixels, the host will decode artwork at. Larger is scaled down, not refused. */
    const val MAX_ARTWORK_EDGE_PX = 1024

    /** The longest shortcut label. */
    const val MAX_LABEL_LENGTH = 40
}

/** The API version and host version this SDK targets. Keep in step with the host PluginApi. */
object Sdk {
    const val API = 1
    const val HOST_VERSION_CODE = 28
    const val FORMAT = "gamecore.plugin"

    /**
     * The first host build that understands an `icon` section.
     *
     * A per-use bump rather than a change to [HOST_VERSION_CODE]: bumping the default would make every
     * template in this SDK — including the ones that predate icons — require a newer host than they
     * need. `icon {}` raises `minHostVersion` to this instead, so only a plugin that asks for an icon
     * declines to load on an older GameCore, and everything else stays byte-identical.
     */
    const val HOST_VERSION_CODE_ICON = 37

    /**
     * The first host build that understands a schema-4 `glass` effect.
     *
     * A per-use bump for the same reason [HOST_VERSION_CODE_ICON] is one: only a theme that asks for
     * glass declines to load on an older GameCore, and every plugin that does not stays byte-identical.
     * A theme that used the block needs it, because the older host rejects schema 4 outright rather
     * than rendering the palette flat.
     */
    const val HOST_VERSION_CODE_GLASS = 38

    /**
     * The manifest `api` level at which the 3.x platform features landed — a settings section,
     * declarative screens, chart components, dashboard widgets, and the motion data scope — matching
     * the host's `PluginApi` level 3. A plugin that uses any of them raises its manifest `api` to
     * this via the same per-use bump the theme uses with [ThemeSchema.V2]; a plugin that uses none of
     * them stays at [API] and emits byte-identical output to a build that predates these features.
     */
    const val API_V3 = 3

    /**
     * The manifest `api` level at which the automation-history read scope landed
     * ([DataScopes.AUTOMATION_HISTORY]), matching the host's current `PluginApi.CURRENT` of 4. Unlike
     * the 3.x features, nothing raises `api` to this automatically — a read scope is not a section — so
     * a plugin that reads automation history sets `api = API_V4` itself. A plugin that does not touch
     * the scope stays at whatever level its sections (if any) require.
     */
    const val API_V4 = 4

    /**
     * `pill.json`'s own format tag and version. A section file carries its format separately
     * from the package's, so a module can gain a version without reissuing the whole grammar
     * — and so a file dropped into the wrong section is caught by its tag, not by its shape.
     */
    const val PILL_FORMAT = "gamecore.plugin.pill"
    const val PILL_VERSION = 1

    /**
     * `screens/<id>.json`'s own format tag and version, mirroring the host screens codec. Carried inside
     * the section file, separate from the package format, so a screens file dropped into the wrong
     * section is caught by its tag rather than by its shape.
     */
    const val SCREENS_FORMAT = "gamecore.plugin.screens"
    const val SCREENS_VERSION = 1

    /**
     * `widgets/<id>.json`'s own format tag and version, mirroring the host widgets codec. Each
     * dashboard widget is its own file carrying this tag, separate from the package format, so a
     * widget file dropped into the wrong section is caught by its tag rather than by its shape —
     * exactly as [PILL_FORMAT] and [SCREENS_FORMAT] guard their own sections.
     */
    const val WIDGET_FORMAT = "gamecore.plugin.widget"
    const val WIDGET_VERSION = 1
}
