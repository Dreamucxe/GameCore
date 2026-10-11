package com.gamecore.core.plugin.overlay

import com.gamecore.core.plugin.ui.ComponentNode
import com.gamecore.core.plugin.ui.ComponentType
import com.gamecore.core.plugin.ui.PropValue
import com.gamecore.core.plugin.ui.ScreenValidator

/**
 * Where a module sits on the screen, as a fraction of each axis.
 *
 * Fractions, not pixels, and the choice is load-bearing. The overlay layer stores the Game Pill's
 * own position in pixels, which is correct for something the user drags with a finger on one
 * device; a plugin's module is authored once, shipped to every device, and must land in the same
 * visual place on a 1080p phone and a tablet, through a rotation, with or without a cutout. A
 * fraction survives all of that, and it is the same representation GameCore's own HUD widgets
 * use for exactly the same reason. The service multiplies these by the current window size at
 * the moment it builds the window; nothing here knows a pixel.
 */
data class ModuleAnchor(val xFraction: Float, val yFraction: Float) {

    /**
     * The same anchor, forced onto the screen.
     *
     * Clamped rather than refused: a module authored at `y = 1.2` is a developer's arithmetic
     * mistake, and the useful response is to put it at the bottom edge where they can see what
     * they did. A non-finite fraction is a different matter — it cannot be clamped, because every
     * comparison against `NaN` is false, and it would reach the window manager as a `NaN`
     * coordinate — so it falls back to the default position instead.
     */
    fun clamped(): ModuleAnchor = ModuleAnchor(
        clampFraction(xFraction, DEFAULT_X),
        clampFraction(yFraction, DEFAULT_Y),
    )

    companion object {
        /** Horizontally centred: the one position no game's own HUD reliably occupies. */
        const val DEFAULT_X = 0.5f

        /** Just below the status bar, above where most games put their own top row. */
        const val DEFAULT_Y = 0.08f

        val TOP_CENTER = ModuleAnchor(DEFAULT_X, DEFAULT_Y)

        private fun clampFraction(value: Float, fallback: Float): Float =
            if (value.isFinite()) value.coerceIn(0f, 1f) else fallback
    }
}

/**
 * The sizes a module may be, as a closed set.
 *
 * This is the enum rather than two numbers because free dimensions are how a "module" becomes a
 * full-screen surface. Any arithmetic bound — "no larger than half the screen", say — has to be
 * evaluated against a screen size the plugin can lie about or a device it was not authored for,
 * and a bug in that arithmetic is a plugin drawing over the whole game. Three fixed boxes have no
 * arithmetic to get wrong: the largest is 240×112dp, which on the smallest supported display is
 * still a badge, and the renderer lays the content out inside whichever box the author picked.
 *
 * The dp figures are the same family of sizes as the Game Pill's own collapsed and expanded
 * states, so a plugin module reads as part of the same overlay rather than a foreign box.
 */
enum class ModuleSize(val id: String, val widthDp: Int, val heightDp: Int) {
    SMALL("small", 120, 48),
    MEDIUM("medium", 180, 72),
    LARGE("large", 240, 112);

    companion object {
        private val byId = entries.associateBy { it.id }

        /** Resolves an authored id, or null. Never `valueOf` on untrusted input. */
        fun fromId(id: String): ModuleSize? = byId[id.trim().lowercase()]
    }
}

/**
 * The closed set of readings a module may display, each tied to the data scope that must have
 * been granted before it may be shown.
 *
 * A `stat` node names one of these by id; it does not name a sensor, a file, a property or an
 * expression. That is what makes a module's data appetite *statically knowable*: the service can
 * walk a decoded module, collect the scopes it needs (see [PluginOverlayModule.requiredScopes])
 * and refuse the whole module before it is ever shown if the user has not granted them. A plugin
 * cannot widen its own appetite at runtime because there is no runtime — the tree is data, and
 * this list is the whole vocabulary.
 *
 * [scope] is the id of a `DataScope`, carried as a string because this model is also what the
 * SDK and the JSON speak; the ids are asserted against the real enum in the codec's test, so a
 * rename there cannot quietly orphan a stat here. Battery and temperature both sit under
 * `thermals`, which is the scope whose description already covers "temperature and battery
 * readings" — a module showing battery percentage is asking for the thermals aperture, not for a
 * fifth one invented here.
 */
enum class ModuleStat(val id: String, val label: String, val scope: String) {
    FPS("fps", "Frame rate", "device_stats"),
    CPU("cpu", "CPU load", "device_stats"),
    GPU("gpu", "GPU load", "device_stats"),
    RAM("ram", "Memory", "device_stats"),
    BATTERY("battery", "Battery", "thermals"),
    TEMP("temp", "Temperature", "thermals"),
    ROTATION_RATE("rotation_rate", "Rotation rate", "motion"),
    MOVEMENT("movement", "Movement", "motion");

    companion object {
        /**
         * The prop name a `stat` node carries its reading in. One constant, read by the codec,
         * by [PluginOverlayModule.requiredScopes] and by the renderer, so the three cannot
         * disagree about where to look.
         */
        const val PROP = "stat"

        private val byId = entries.associateBy { it.id }

        fun fromId(id: String): ModuleStat? = byId[id.trim().lowercase()]

        /**
         * The stat a node displays, or null if it is not a [ComponentType.STAT] node or its
         * `stat` prop is missing or unknown.
         *
         * Null is the right answer for a malformed node here because the codec has already
         * refused any module containing one; this is for consumers walking an already-trusted
         * tree.
         */
        fun fromNode(node: ComponentNode): ModuleStat? {
            if (node.type != ComponentType.STAT) return null
            val raw = (node.props[PROP] as? PropValue.Str)?.value ?: return null
            return fromId(raw)
        }
    }
}

/**
 * One bounded, read-only readout a plugin may place over a game.
 *
 * This is the whole surface a data-only plugin gets on the game screen: an id, a label, a
 * position, one of three sizes, an opacity, and a tree of validated nodes. There is no touch
 * target, no full-screen mode, no window flag, no z-order and no gesture — a module is drawn by
 * GameCore's own Compose code in a window GameCore configures, and the plugin's contribution is
 * the *content*, not the window.
 *
 * Nothing in this file imports `android.*` or the overlay layer, and that is a deliberate
 * boundary rather than an accident of tidiness: the overlay layer owns window specs, slots and
 * pixels, and if this model knew about them then every plugin-format test would need a device.
 * The service translates a module into its own window spec at the edge, which also means this
 * model can be parsed, stored and shown in the install dialog by code that never touches a
 * window.
 *
 * An instance is *not* automatically in bounds — a hand-built one, or one straight from a
 * decoder that has not finished, may carry an anchor off-screen or an absurd opacity. Call
 * [normalised] to get one that is, and route anything from a plugin through
 * [com.gamecore.core.plugin.overlay.PluginOverlayCodec] rather than constructing it directly.
 */
data class PluginOverlayModule(
    val id: String,
    val title: String,
    val anchor: ModuleAnchor,
    val size: ModuleSize,
    val opacityPercent: Int,
    val content: ComponentNode,
) {

    /**
     * Every host stat this module's content displays, derived from the tree itself.
     *
     * Two callers need this and they need it for opposite reasons, which is why it is one walk
     * rather than two. The registry turns it into [requiredScopes] to decide whether the module
     * may be shown at all; the overlay service turns it into the set of readings to sample, so a
     * module displaying a frame rate is what starts the sampler that gives it one. A module with
     * no stat node returns the empty set and therefore costs nothing on either path — a plugin
     * that only wants to label a corner of the screen asks for no data and starts no loop.
     *
     * The walk is iterative because a model may also be built in code, where nothing has bounded
     * its depth yet, and a recursive walk over an unbounded tree is a stack overflow in the
     * service process. If the tree is somehow larger than [MAX_SCOPE_WALK_NODES] the result is
     * every stat in the vocabulary, not a partial set: under-reporting here would show a stat
     * whose aperture was never granted, so the truncated case fails closed.
     */
    val requiredStats: Set<ModuleStat>
        get() {
            val out = LinkedHashSet<ModuleStat>()
            val pending = ArrayDeque<ComponentNode>()
            pending.addLast(content)
            var visited = 0
            while (pending.isNotEmpty()) {
                if (visited++ >= MAX_SCOPE_WALK_NODES) return ModuleStat.entries.toSet()
                val node = pending.removeLast()
                ModuleStat.fromNode(node)?.let { out += it }
                node.children.forEach { pending.addLast(it) }
            }
            return out
        }

    /**
     * Every data scope this module's content needs granted, derived from [requiredStats].
     *
     * The registry consults this to refuse a module whose readings the user never approved, and it
     * is computed from the content rather than declared alongside it because a declared list can
     * disagree with the tree — and the disagreement would be resolved in the plugin's favour. A
     * module declaring `device_stats` while displaying a temperature would show a reading from an
     * aperture the user closed. Derived, the two cannot differ.
     *
     * Derived from the stats rather than walked separately so the fail-closed truncation above
     * covers this too: there is one walk, so there is one place that can be wrong.
     */
    val requiredScopes: Set<String>
        get() = requiredStats.mapTo(LinkedHashSet()) { it.scope }

    /**
     * The same module with its position and opacity forced into range.
     *
     * Only the clamped fields are touched. Everything else — the id, the title, the node tree —
     * is either already validated or not something a clamp could fix, and a "normalise" that
     * quietly rewrote content would be a second, invisible validator.
     */
    fun normalised(): PluginOverlayModule = copy(
        anchor = anchor.clamped(),
        opacityPercent = clampOpacity(opacityPercent),
    )

    companion object {
        /**
         * Never fully transparent. An invisible window the user cannot find is worse than a faint
         * one: they cannot tell which plugin to disable, and they cannot tell whether the module
         * is drawing at all.
         */
        const val MIN_OPACITY_PERCENT = 20

        const val MAX_OPACITY_PERCENT = 100

        /**
         * How many plugin modules may be on screen at once, across every installed plugin.
         *
         * Three, because the overlay keeps one live view per slot and these are a small fixed
         * allocation of slots rather than an unbounded map — and because the thing being covered
         * is a game. Beyond three, the registry drops the extras in registry order and says so;
         * a silent drop would leave the author of the fourth module debugging a renderer that
         * was never asked to draw it.
         */
        const val MAX_MODULES_ON_SCREEN = 3

        /** The ceilings [validator] applies. Far tighter than a full plugin screen's. */
        const val MAX_NODES = 40
        const val MAX_DEPTH = 6
        const val MAX_GRID_COLUMNS = 4
        const val MAX_STRING_LENGTH = 120
        const val MAX_CHILDREN = 12

        /** A module label has to fit a Settings row and the module's own box. */
        const val MAX_TITLE_LENGTH = 48

        /** A bound on [requiredScopes]' walk over a tree that may not have been validated yet. */
        const val MAX_SCOPE_WALK_NODES = 512

        /**
         * The node types a module's content may use.
         *
         * The complement — `BUTTON`, `SLIDER`, `SWITCH`, `SCROLL` — is rejected by the codec, and
         * this single set is why that rejection is reviewable: a module is rendered in a window
         * that cannot receive touches, so content that could want a touch must not be able to
         * reach it. Expressing it as "these nine are allowed" rather than "those four are
         * banned" means a future component type is excluded until someone adds it here, which is
         * the safe direction for a list that gates what can appear over a game.
         */
        val ALLOWED_TYPES: Set<ComponentType> = setOf(
            ComponentType.COLUMN,
            ComponentType.ROW,
            ComponentType.GRID,
            ComponentType.CARD,
            ComponentType.TEXT,
            ComponentType.STAT,
            ComponentType.SPACER,
            ComponentType.IMAGE,
            ComponentType.CHART,
        )

        /**
         * The validator a module's tree must pass.
         *
         * The existing [ScreenValidator], parameterised down — not a second validator. A module
         * is a badge, not a screen: forty nodes and six levels is more than any readout needs,
         * and the caps being this tight is what keeps a module cheap enough to recompose on
         * every stat tick without costing the game frames.
         */
        fun validator(): ScreenValidator = ScreenValidator(
            maxNodes = MAX_NODES,
            maxDepth = MAX_DEPTH,
            maxGridColumns = MAX_GRID_COLUMNS,
            maxStringLength = MAX_STRING_LENGTH,
            maxChildren = MAX_CHILDREN,
        )

        fun clampOpacity(percent: Int): Int =
            percent.coerceIn(MIN_OPACITY_PERCENT, MAX_OPACITY_PERCENT)
    }
}
