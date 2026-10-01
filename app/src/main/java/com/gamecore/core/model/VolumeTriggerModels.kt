package com.gamecore.core.model

/**
 * The volume-button point trigger: a physical volume key wired to a synthetic tap at a spot on the screen,
 * remembered per game.
 *
 * The idea is small and the honesty is the same one [QuickTriggerSettings] is built around. A player picks a
 * point on the screen — a fire button, a reload, a scope — and hands one of the two volume keys to it; from
 * then on that key presses that point for them. What the key can actually reach is not this model's promise
 * to make: like every other volume path in this app, an ordinary application only sees a volume key while
 * its own window has focus, and reaching *into a running game* with the tap needs GameCore's accessibility
 * service. This file is the stored intent — which key, which point, which gesture — and nothing here reads a
 * key or dispatches a touch. The detector and the dispatcher own that, and say where they can and cannot act.
 *
 * Two things are load-bearing in the shapes below, and are why this is its own small cluster of types rather
 * than a handful of loose columns:
 *
 * - **The point is a fraction, not a pixel.** [FractionPoint] stores 0..1 of the screen in each axis, for the
 *   same reason [CrosshairPreset.xFraction] and the floating button's fractions do: a pixel chosen on a
 *   1080-wide portrait screen lands somewhere else on the 2400-wide landscape one the game actually runs in,
 *   and a resolution override moves it again. A fraction re-resolves against whatever display the tap is sent
 *   to, so the point the user placed survives a rotation and a downscale.
 * - **It is per game, and per key.** The config rides on [GameProfile], so "Volume Down taps the fire button"
 *   is true in the shooter and means nothing in the racer. [up] and [down] are separate because the two keys
 *   are assigned separately — a player may want only one of them bound — which is why both are nullable and
 *   null means *unassigned*.
 *
 * Session-time behaviour, not a device write: applying a profile that carries only this changes no device
 * setting and records nothing to restore, so — like the §3.5 thermal and network toggles on [GameProfile],
 * and unlike a brightness or a display size — it is deliberately **not** part of [GameProfile.changesNothing].
 * The global master switch that gates the whole feature is a single preference on `AppSettings`, not here;
 * this per-profile [enabled] is the game's own on/off underneath it.
 */
data class VolumeTriggerConfig(
    /** The per-game switch, under the global master on `AppSettings`. Off for a new and a pre-feature profile. */
    val enabled: Boolean = false,
    /** What Volume Up does, or null if that key is left to its normal job in this game. */
    val up: VolumeTriggerBinding? = null,
    /** What Volume Down does, or null if that key is left alone in this game. */
    val down: VolumeTriggerBinding? = null,
) {
    fun normalised(): VolumeTriggerConfig = copy(
        up = up?.normalised(),
        down = down?.normalised(),
    )

    /**
     * The binding for one key, or null if it is unassigned in this game.
     *
     * A selector rather than a `when` at each call site, so the detector — which knows only which key fired —
     * asks this config what that key is for and gets back the whole binding or nothing, mirroring the way
     * [FloatingButtonConfig.positionFraction] hands back a pair or null for an orientation.
     */
    fun binding(button: VolumeTriggerButton): VolumeTriggerBinding? = when (button) {
        VolumeTriggerButton.VOLUME_UP -> up
        VolumeTriggerButton.VOLUME_DOWN -> down
    }

    companion object {
        val DEFAULT = VolumeTriggerConfig()
    }
}

/**
 * One physical key's assignment: the point it taps, the gesture that fires it, and how long a hold lasts.
 *
 * [point] is nullable and null means the key is bound in name only — the gesture is chosen but no spot on the
 * screen has been placed yet, so there is nothing to tap. A binding with a null point is a half-finished one
 * the editor can hold while the user is still placing the marker; the dispatcher treats it as doing nothing.
 *
 * [holdMs] matters only for [VolumeTriggerPressMode.HOLD] — it is how long the synthetic touch stays down
 * before it lifts, which is what turns a long press of the key into a press-and-hold on the point rather than
 * a tap. It is stored and clamped for the other two modes as well rather than made conditional, because a
 * value the user set on HOLD and then switched away from should still be there when they switch back, exactly
 * as the panel width survives a layout change on [FloatingButtonConfig].
 */
data class VolumeTriggerBinding(
    val point: FractionPoint? = null,
    val pressMode: VolumeTriggerPressMode = VolumeTriggerPressMode.SINGLE_TAP,
    val holdMs: Int = DEFAULT_HOLD_MS,
) {
    fun normalised(): VolumeTriggerBinding = copy(
        // A point read back out of storage is trusted no more than the button's fractions are: a value
        // outside 0..1 would place the tap off the screen on the next resolve.
        point = point?.normalised(),
        holdMs = holdMs.coerceIn(MIN_HOLD_MS, MAX_HOLD_MS),
    )

    /** True once a spot has been placed, so the dispatcher has somewhere to tap. */
    val isAssigned: Boolean get() = point != null

    companion object {
        /** A firm, deliberate hold — long enough to register as press-and-hold, short enough not to feel stuck. */
        const val DEFAULT_HOLD_MS = 250

        /** Below this a "hold" is indistinguishable from a tap, so it is the floor. */
        const val MIN_HOLD_MS = 50

        /** Five seconds. Past this the point is effectively held for the rest of the round, which is a stuck key. */
        const val MAX_HOLD_MS = 5_000
    }
}

/**
 * A point on the screen as a fraction of it, 0..1 in each axis, clamped by [normalised].
 *
 * The whole reason the trigger stores this and not a pixel: 0.5/0.5 is the centre of every display, so the
 * marker the user placed survives a rotation, a resolution downscale and a move to another device, the same
 * property [CrosshairPreset.xFraction] and the floating button's fractions rely on. The dispatcher multiplies
 * it by the live display's usable size to get the pixel it actually taps.
 *
 * A model-layer value type, deliberately not the overlay layer's `com.gamecore.core.overlay.PositionFraction`:
 * that type lives in a layer that depends on this model and not the reverse, so importing it here would be the
 * back-edge [FloatingButtonConfig] avoids by storing four flat floats. This cluster keeps its own point type
 * on the near side of that wall.
 */
data class FractionPoint(val x: Float, val y: Float) {
    fun normalised(): FractionPoint = FractionPoint(
        x = x.coerceIn(0f, 1f),
        y = y.coerceIn(0f, 1f),
    )
}

/** Which physical key a binding is for. Stored by name, so declaration order is not load-bearing. */
enum class VolumeTriggerButton {
    VOLUME_UP,
    VOLUME_DOWN,
}

/**
 * The gesture on the key, and what it does to the point.
 *
 * The three the feature offers, each naming both the key press that fires it and the touch it produces:
 * [SINGLE_TAP] a single press taps the point, [DOUBLE_TAP] a double press taps it, and [HOLD] a long press
 * holds it down for [VolumeTriggerBinding.holdMs] and then lifts. Stored by name and parsed back through [of],
 * so a value written by a later version that this build does not know falls back to the safe default rather
 * than throwing — the same contract [PillDisplayMode.of] and [HuntFilter.of] keep.
 */
enum class VolumeTriggerPressMode {
    SINGLE_TAP,
    DOUBLE_TAP,
    HOLD,
    ;

    companion object {
        /** Parse a stored name back to a mode, falling back to [SINGLE_TAP] for an unknown or absent value. */
        fun of(name: String?): VolumeTriggerPressMode = entries.firstOrNull { it.name == name } ?: SINGLE_TAP
    }
}
