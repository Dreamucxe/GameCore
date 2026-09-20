package com.gamecore.aimlab.engine

/**
 * The interactive control layout for a training session: the on-screen buttons and sticks.
 *
 * This is deliberately NOT the HUD stat-widget model (`com.gamecore.core.model.HudLayout`). Those widgets
 * are read-only stat readouts; these are things a finger presses — shoot, ADS, joystick — with size,
 * shape and enable state. Keeping them separate is what lets the existing HUD builder and every saved HUD
 * layout keep working unchanged (see AimLab_ReuseMap.md).
 *
 * Positions and sizes are normalised, never pixels, so a layout survives rotation and moving between
 * devices — the same reason the HUD builder uses fractions. [presets] gives the 2/3/4/5-finger starting
 * layouts §12 asks for.
 */
data class ControlLayout(
    val id: Long = 0L,
    val name: String,
    /** The portrait arrangement. Kept as the primary `controls` field for backward compatibility: every
     *  layout saved before landscape support had exactly this, and existing code that reads [controls]
     *  keeps meaning "the portrait set". */
    val controls: List<ControlWidget> = emptyList(),
    /** The landscape arrangement of the same controls (§4). Empty until the layout is edited in landscape
     *  or filled from a landscape preset; the editor and overlay fall back to a preset when it is empty. */
    val landscapeControls: List<ControlWidget> = emptyList(),
    val createdAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L,
) {
    val enabledControls: List<ControlWidget> get() = controls.filter { it.enabled }

    /** The control set for a given orientation: [controls] is portrait, [landscapeControls] is landscape. */
    fun controlsFor(orientation: ControlOrientation): List<ControlWidget> = when (orientation) {
        ControlOrientation.PORTRAIT -> controls
        ControlOrientation.LANDSCAPE -> landscapeControls
    }

    /** The enabled controls for a given orientation — what the overlay actually draws (§4). */
    fun enabledControlsFor(orientation: ControlOrientation): List<ControlWidget> =
        controlsFor(orientation).filter { it.enabled }

    /** Replaces the control set of one orientation, leaving the other untouched. */
    fun withControlsFor(orientation: ControlOrientation, next: List<ControlWidget>): ControlLayout =
        when (orientation) {
            ControlOrientation.PORTRAIT -> copy(controls = next)
            ControlOrientation.LANDSCAPE -> copy(landscapeControls = next)
        }

    fun withControl(control: ControlWidget): ControlLayout =
        copy(controls = controls.filterNot { it.role == control.role } + control)

    /** Clamps every control (both orientations) into the safe area for a given aspect ratio. */
    fun clampedTo(safeArea: SafeArea): ControlLayout = copy(
        controls = controls.map { it.clampTo(safeArea) },
        landscapeControls = landscapeControls.map { it.clampTo(safeArea) },
    )

    /**
     * Repairs a layout that lost its controls, and fills in a missing landscape set (§3 self-heal, §4).
     *
     * A layout saved before controls were persisted correctly — or one that only ever held a portrait set —
     * comes back with an empty [controls] and/or empty [landscapeControls]. This rebuilds only the empty
     * side from the finger-count preset the layout's own controls imply, so a layout that *does* have
     * controls keeps every one of them and its name; only the genuinely-empty side is filled. It is not
     * destructive: a non-empty side is returned untouched.
     *
     * The preset is inferred from whichever side has controls (their count maps to 2/3/4/5-finger); if both
     * sides are empty the three-finger preset is used, which is the same base [preset] and the editor's
     * "new layout" default. This is pure and android-free, so both the repository's self-heal on load and
     * the overlay's draw-time fallback go through the one function.
     */
    fun healed(): ControlLayout {
        val basis = if (controls.isNotEmpty()) controls else landscapeControls
        val presetKind = LayoutPreset.forControlCount(basis.size)
        val portrait = if (controls.isNotEmpty()) controls else ControlLayoutPresets.portrait(presetKind)
        val landscape = if (landscapeControls.isNotEmpty()) {
            landscapeControls
        } else {
            ControlLayoutPresets.landscape(presetKind)
        }
        return copy(controls = portrait, landscapeControls = landscape)
    }

    /** True when neither orientation holds any control — a layout that would draw nothing. */
    val isEmpty: Boolean get() = controls.isEmpty() && landscapeControls.isEmpty()

    companion object {
        const val MAX_NAME_LENGTH = 40

        /** The finger-count presets. Each returns a fresh layout with sensible default placements. */
        fun preset(preset: LayoutPreset): ControlLayout = ControlLayoutPresets.build(preset)
    }
}

/**
 * The two screen orientations a layout stores a separate control arrangement for (§4).
 *
 * Name is the stable stored key (the `orientation` column on a control row). A layout carries one set of
 * controls per orientation, each a set of fractions of *that* orientation's safe area, because a
 * thumb-reachable spot in portrait is not the same spot in landscape.
 */
enum class ControlOrientation {
    PORTRAIT,
    LANDSCAPE,
    ;

    companion object {
        fun fromName(name: String?): ControlOrientation = entries.firstOrNull { it.name == name } ?: PORTRAIT
    }
}

/**
 * One interactive control.
 *
 * [role] is the control's identity and is unique within a layout — there is one Shoot button, one ADS
 * button — so it doubles as the stable key (no client-generated id needed, unlike HUD widgets which can
 * repeat a stat). Shoot and ADS are independently sized, which §11 calls out explicitly. Sizes and
 * position are fractions of the screen; [clampTo] guarantees the control stays reachable.
 *
 * @param xFraction 0..1 centre X.
 * @param yFraction 0..1 centre Y.
 * @param widthFraction control width as a fraction of screen width.
 * @param heightFraction control height as a fraction of screen height.
 * @param opacityPercent 15..100; floored so a control cannot be faded to invisibility and lost.
 */
data class ControlWidget(
    val role: ControlRole,
    val xFraction: Float,
    val yFraction: Float,
    val widthFraction: Float = 0.14f,
    val heightFraction: Float = 0.14f,
    val opacityPercent: Int = 70,
    val shape: ControlShape = ControlShape.CIRCLE,
    val enabled: Boolean = true,
) {
    fun normalised(): ControlWidget = copy(
        xFraction = xFraction.clampFinite(0f, 1f, fallback = 0.5f),
        yFraction = yFraction.clampFinite(0f, 1f, fallback = 0.5f),
        widthFraction = widthFraction.clampFinite(MIN_SIZE, MAX_SIZE),
        heightFraction = heightFraction.clampFinite(MIN_SIZE, MAX_SIZE),
        opacityPercent = opacityPercent.coerceIn(MIN_OPACITY, 100),
    )

    /**
     * Clamps the control's centre so its whole body stays inside [safeArea].
     *
     * The half-extents are subtracted from the available range, so a control near an edge is pushed in by
     * exactly enough that it never sits partly (or fully) off the usable screen — §12's "prevent controls
     * from being positioned completely outside the usable screen", made stronger: not even partly outside
     * the safe area. When a control is wider than the safe area allows, it is centred on that axis rather
     * than pinned to one edge.
     */
    fun clampTo(safeArea: SafeArea): ControlWidget {
        val n = normalised()
        val halfW = n.widthFraction / 2f
        val halfH = n.heightFraction / 2f
        val minX = safeArea.left + halfW
        val maxX = safeArea.right - halfW
        val minY = safeArea.top + halfH
        val maxY = safeArea.bottom - halfH
        val x = if (minX <= maxX) n.xFraction.coerceIn(minX, maxX) else (safeArea.left + safeArea.right) / 2f
        val y = if (minY <= maxY) n.yFraction.coerceIn(minY, maxY) else (safeArea.top + safeArea.bottom) / 2f
        return n.copy(xFraction = x, yFraction = y)
    }

    companion object {
        const val MIN_SIZE = 0.06f
        const val MAX_SIZE = 0.40f
        const val MIN_OPACITY = 15
    }
}

/** The controls a training layout can hold. Name is the stable stored key and the per-layout uniqueness key. */
enum class ControlRole(val label: String) {
    SHOOT("Shoot"),
    ADS("Aim"),
    RELOAD("Reload"),
    CROUCH("Crouch"),
    JUMP("Jump"),
    SPRINT("Sprint"),
    MOVE_STICK("Movement"),
    WEAPON_SWITCH("Weapon switch"),
    MELEE("Melee"),
    GYRO_TOGGLE("Gyro toggle"),
    ;

    companion object {
        fun fromName(name: String?): ControlRole? = entries.firstOrNull { it.name == name }
    }
}

/** A control's outline shape. Decorative; hit-testing uses the bounding box regardless. */
enum class ControlShape(val label: String) {
    CIRCLE("Circle"),
    SQUARE("Square"),
    ROUNDED("Rounded"),
    ;

    companion object {
        fun fromName(name: String?): ControlShape = entries.firstOrNull { it.name == name } ?: CIRCLE
    }
}

/** The finger-count presets §12 lists. */
enum class LayoutPreset(val label: String, val fingers: Int) {
    TWO_FINGER("2 finger", 2),
    THREE_FINGER("3 finger", 3),
    FOUR_FINGER("4 finger", 4),
    FIVE_FINGER("5 finger", 5),
    CUSTOM("Custom", 0),
    ;

    companion object {
        fun fromName(name: String?): LayoutPreset = entries.firstOrNull { it.name == name } ?: THREE_FINGER

        /**
         * The preset whose control count best matches [count], for self-heal.
         *
         * The presets add controls as the finger count rises (2→3 controls, 3→5, 4→7, 5→9), so a layout
         * that still has some controls is rebuilt from the preset closest to what it had rather than always
         * defaulting to three-finger. A count that matches nothing (0, or a hand-edited oddity) falls to
         * three-finger, the editor's own base.
         */
        fun forControlCount(count: Int): LayoutPreset = when {
            count >= 9 -> FIVE_FINGER
            count >= 7 -> FOUR_FINGER
            count >= 5 -> THREE_FINGER
            count in 1..4 -> TWO_FINGER
            else -> THREE_FINGER
        }
    }
}

/**
 * The usable region of the screen, in fractions, that controls are kept inside.
 *
 * Built by the Android layer from the window's safe-content insets (status/nav bars, cutout, gesture
 * areas) so the clamp adapts to the real device rather than a hard-coded resolution (§26). The engine
 * only ever sees these four fractions, which keeps the clamp math testable across arbitrary shapes.
 */
data class SafeArea(
    val left: Float = 0.03f,
    val top: Float = 0.03f,
    val right: Float = 0.97f,
    val bottom: Float = 0.97f,
) {
    init {
        require(left < right && top < bottom) { "degenerate safe area" }
    }

    companion object {
        val FULL = SafeArea(0f, 0f, 1f, 1f)
    }
}
