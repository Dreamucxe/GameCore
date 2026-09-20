package com.gamecore.aimlab.ui.hud

import com.gamecore.aimlab.engine.ControlLayout
import com.gamecore.aimlab.engine.ControlRole
import com.gamecore.aimlab.engine.ControlShape
import com.gamecore.aimlab.engine.ControlWidget
import com.gamecore.aimlab.engine.LayoutPreset
import com.gamecore.aimlab.engine.SafeArea
import kotlin.math.roundToInt

/**
 * Everything the control/HUD editor draws: the saved layouts, and the one being arranged.
 *
 * The screen is two halves of the same state, the same split the weapon editor uses. [layouts] is the
 * stored list, observed straight from the repository and never copied into local state, so a save or a
 * delete shows up because the flow re-emitted rather than because this class remembered to update a
 * cache. [draft] is the working copy of whichever layout the user opened — the one the preview renders
 * and the one a drag moves — and it exists only while the editor is open.
 *
 * There is no draft *form* here, unlike [com.gamecore.aimlab.ui.weapon.WeaponDraft]. A control layout is
 * already the shape the user manipulates: a position is a place on the screen, not a number typed into a
 * field, and the engine's [ControlWidget] holds it in exactly the fractions the preview draws from.
 * Converting it into some intermediate integer form would only give two representations that could
 * disagree about where a button is. The only conversion this file does is the size/opacity **percent**
 * the sliders move in, and those percentages are derived from the engine's own
 * [ControlWidget.MIN_SIZE]/[ControlWidget.MAX_SIZE]/[ControlWidget.MIN_OPACITY] rather than typed out
 * again — a slider whose ends disagreed with `normalised()` would be a control the user can set and the
 * engine silently moves back.
 *
 * [safeArea] is the device's real usable region, measured from the window's insets by the screen and
 * handed down. Every position this editor produces goes through [ControlWidget.clampTo] against it, so a
 * control cannot be dragged under a cutout or off the edge. It is state rather than a constant because
 * it changes with rotation and with a foldable unfolding, and a layout arranged on one has to be pulled
 * back inside the other.
 *
 * [loading] is the frame before the layouts flow has answered, kept apart from "loaded and empty" so the
 * empty state cannot flash up in front of a list that is about to arrive. [error] is only ever set when a
 * write actually failed; nothing here reports a success that did not happen.
 */
data class ControlEditorState(
    val loading: Boolean = true,
    val layouts: List<ControlLayout> = emptyList(),
    val draft: ControlLayout? = null,
    val editingId: Long? = null,
    val selectedRole: ControlRole? = null,
    val safeArea: SafeArea = SafeArea(),
    val arranging: Boolean = false,
    val draggingRole: ControlRole? = null,
    val dirty: Boolean = false,
    val confirmingDelete: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
) {

    /**
     * The control the per-control card edits, resolved from the draft rather than held separately.
     *
     * Holding the widget itself would mean two copies of one button — the one in the layout the preview
     * draws and the one the sliders move — and they would drift the first time a clamp moved only one of
     * them. The role is the layout's own uniqueness key, so it is the only thing worth remembering.
     */
    val selected: ControlWidget? get() = draft?.controls?.firstOrNull { it.role == selectedRole }

    /**
     * Every role in the open layout, disabled ones included.
     *
     * This is what the selector row offers, and the "included" matters: [ControlOverlay] draws only
     * enabled controls and `TouchMath.controlAt` only hits enabled ones, so a control the user switched
     * off is invisible and untouchable in the preview. Without it listed here, switching a control off
     * would be a one-way door.
     */
    val roles: List<ControlRole> get() = draft?.controls?.map { it.role }.orEmpty()

    /** True while the whole screen is the preview and a finger moves controls rather than scrolls. */
    val isArranging: Boolean get() = arranging && draft != null

    /** True when the open layout has never been stored, so saving creates rather than amends. */
    val isNew: Boolean get() = editingId == null

    /** A layout needs a name before it is worth storing, and a write already in flight is not repeated. */
    val canSave: Boolean get() = draft != null && !saving && draft.name.isNotBlank()

    /** Only a stored layout can be deleted; an unsaved draft is discarded by opening something else. */
    val canDelete: Boolean get() = editingId != null && !saving

    /** There has to be something stored to copy before "duplicate" means anything. */
    val canDuplicate: Boolean get() = editingId != null && !saving

    /** The editor card's heading: the layout's own name once it has one, else what is being made. */
    val editorTitle: String
        get() = when {
            draft == null -> "No layout open"
            draft.name.isNotBlank() -> draft.name
            isNew -> "New layout"
            else -> "Layout"
        }

    /** How many of the open layout's controls will actually appear on the training surface. */
    val enabledCount: Int get() = draft?.enabledControls?.size ?: 0

    /** The one-line summary under a layout in the list: how much of it is switched on. */
    fun subtitleFor(layout: ControlLayout): String {
        val enabled = layout.enabledControls.size
        return "$enabled of ${layout.controls.size} controls enabled"
    }

    /**
     * The one-line summary of a control: its size and how visible it is.
     *
     * Width and height are reported separately because they genuinely are separate — §11's independently
     * sizable Shoot and ADS are two [ControlWidget]s with their own `widthFraction`/`heightFraction`, and
     * a single "size" figure here would be the first place that stopped being true.
     */
    fun summaryFor(control: ControlWidget): String {
        val width = percentOf(control.widthFraction)
        val height = percentOf(control.heightFraction)
        val state = if (control.enabled) control.shape.label else "off"
        return "$width% × $height% · ${control.opacityPercent}% opacity · $state"
    }

    companion object {

        /**
         * The width/height slider's range, as whole percents of the screen.
         *
         * Derived from the engine's own clamps rather than written out, so the slider's ends are exactly
         * the values [ControlWidget.normalised] will keep. A slider that went below `MIN_SIZE` would let
         * the user set a size the engine then quietly changed underneath them.
         */
        val SIZE_PERCENT: IntRange =
            percentOf(ControlWidget.MIN_SIZE)..percentOf(ControlWidget.MAX_SIZE)

        /**
         * The opacity slider's range. The floor is the engine's, and it exists for a reason: a control
         * faded to nothing is a control the user cannot find again to fade back up.
         */
        val OPACITY_PERCENT: IntRange = ControlWidget.MIN_OPACITY..100

        /**
         * The finger-count presets §12 asks for.
         *
         * `CUSTOM` is excluded: it carries no finger count of its own and builds the three-finger table,
         * so offering it beside "3 finger" would be two chips that do the same thing under different
         * names. Filtering on the count rather than naming the entry means a preset added to the enum
         * later appears here automatically.
         */
        val PRESETS: List<LayoutPreset> = LayoutPreset.entries.filter { it.fingers > 0 }

        /** Every outline a control can take. Decorative — hit-testing uses the bounding box regardless. */
        val SHAPES: List<ControlShape> = ControlShape.entries.toList()

        /** A 0..1 fraction as the whole percent a slider moves in. */
        fun percentOf(fraction: Float): Int = (fraction * 100f).roundToInt()

        /** A slider's whole percent back into the 0..1 fraction the engine stores. */
        fun fractionOf(percent: Int): Float = percent / 100f
    }
}
