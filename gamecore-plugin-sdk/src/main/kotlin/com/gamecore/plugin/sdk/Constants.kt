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
}

/** Scopes valid on [Permissions.READ_APPROVED_DATA]. */
object DataScopes {
    const val SESSIONS = "sessions"
    const val GAMES = "games"
    const val DEVICE_STATS = "device_stats"
    const val THERMALS = "thermals"
}

/**
 * The fonts a theme may pick. A theme cannot ship a font file — it names one the app bundles.
 *
 * The host **rejects** an unknown id rather than falling back, so a theme naming a font that
 * is not here is a theme that does not load at all.
 */
object ThemeFonts {
    const val SYSTEM = "system"
    const val ROUNDED = "rounded"
    const val MONO = "mono"
    const val SERIF = "serif"

    val ALL = setOf(SYSTEM, ROUNDED, MONO, SERIF)
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

    val ALL = setOf(FPS, CPU, GPU, RAM, BATTERY, TEMP)

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

    val ALL = OverlayStats.ALL
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

/** The API version and host version this SDK targets. Keep in step with the host PluginApi. */
object Sdk {
    const val API = 1
    const val HOST_VERSION_CODE = 28
    const val FORMAT = "gamecore.plugin"

    /**
     * `pill.json`'s own format tag and version. A section file carries its format separately
     * from the package's, so a module can gain a version without reissuing the whole grammar
     * — and so a file dropped into the wrong section is caught by its tag, not by its shape.
     */
    const val PILL_FORMAT = "gamecore.plugin.pill"
    const val PILL_VERSION = 1
}
