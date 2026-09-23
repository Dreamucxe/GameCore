package com.gamecore.core.overlay

import com.gamecore.core.model.FloatingButtonConfig
import com.gamecore.core.model.ThermalClass

/**
 * The floating button's visual state (spec §2), as two things that vary independently.
 *
 * The spec lists four states — Normal, Dimmed, Hot, Critical — but they are not one dial. A button can be
 * dimmed *and* hot at the same time: the dim is about whether the user has touched it recently, the dot is
 * about how warm the phone is. Folding them into a single enum would force a choice the hardware does not
 * make ("is a hot-but-idle button Hot or Dimmed?"), so this models the two axes separately and lets the
 * view combine them: opacity from [ButtonDim], the dot from [thermalDot].
 *
 * Everything here is pure. The idle timer is arithmetic on an injected clock, not a `postDelayed`, so §10's
 * "idle-dim state machine with a fake clock" is a unit test rather than a wait.
 */

/** Whether the button is at full opacity or has faded after sitting untouched (spec §2). */
enum class ButtonDim {
    /** Full opacity: the user touched it within the idle window, or a game is not in front. */
    ACTIVE,

    /** Faded to the idle opacity because nothing has touched it for [IdleDimmer.idleAfterMillis]. */
    IDLE,
}

/**
 * The dot drawn on the button, and echoed as a word in the pill and panel (spec §2).
 *
 * Only the two most severe thermal classes earn a dot: a warm phone is normal under a game and a dot for it
 * would cry wolf. [NONE] covers OK, WARM, and — importantly — UNAVAILABLE: the spec is explicit that a
 * temperature the platform will not report shows *no* dot rather than a grey one, because an absent reading
 * is not a safe reading, and drawing anything would claim knowledge the app does not have.
 */
enum class ThermalDot(val word: String) {
    NONE(""),
    HOT("Hot"),
    CRITICAL("Critical"),
}

/** The dot a thermal class maps to. HOT and CRITICAL only; everything else — including UNAVAILABLE — is none. */
fun thermalDot(level: ThermalClass): ThermalDot = when (level) {
    ThermalClass.CRITICAL -> ThermalDot.CRITICAL
    ThermalClass.HOT -> ThermalDot.HOT
    ThermalClass.OK, ThermalClass.WARM, ThermalClass.UNAVAILABLE -> ThermalDot.NONE
}

/**
 * The idle-dim state machine: a button fades after a period without a touch and returns to full opacity on
 * any touch (spec §2), computed from timestamps rather than run on a timer.
 *
 * The view records the time of the last interaction and asks [dimAt] what the state is *now*; a single
 * posted callback at [nextChangeAfterMillis] is enough to repaint at the moment of the fade, and there is
 * no repeating timer. Dimming only applies while a game is in front and the panel is closed — when either is
 * false the button is a normal control and stays [ButtonDim.ACTIVE]; that gate is the caller's, kept out of
 * here so the state machine has one job.
 *
 * @param idleAfterMillis how long without a touch before the button dims. Matches the old
 *   `FloatingGameButton.IDLE_AFTER_MILLIS` (3 s) by default so behaviour is unchanged.
 */
data class IdleDimmer(val idleAfterMillis: Long = DEFAULT_IDLE_AFTER_MILLIS) {

    /** The state at [nowMillis], given the last touch was at [lastInteractionMillis]. */
    fun dimAt(lastInteractionMillis: Long, nowMillis: Long): ButtonDim {
        val elapsed = nowMillis - lastInteractionMillis
        return if (elapsed >= idleAfterMillis) ButtonDim.IDLE else ButtonDim.ACTIVE
    }

    /**
     * Milliseconds until the state next changes, so the view can post exactly one repaint, or null if it is
     * already idle and nothing further will happen without a touch. Never negative.
     */
    fun nextChangeAfterMillis(lastInteractionMillis: Long, nowMillis: Long): Long? {
        val fadeAt = lastInteractionMillis + idleAfterMillis
        return if (nowMillis >= fadeAt) null else fadeAt - nowMillis
    }

    companion object {
        const val DEFAULT_IDLE_AFTER_MILLIS = 3_000L
    }
}

/**
 * The corner this config is pinned to, or null when it is free-dragged, unset, or the two orientations
 * disagree (spec §2's named Snap positions).
 *
 * Pinning a corner writes the same fraction to both orientations, so a pin is "both orientations hold this
 * corner's fraction". If the user then drags the button in one orientation, that orientation's fraction
 * moves off the corner and the two stop agreeing — so no corner is pinned any more, which is the truth: the
 * button is a free-dragged spot in one orientation and a corner in the other. This bridge lives in the
 * overlay layer because [Corner] does; the model keeps its position as plain fractions and knows nothing of
 * corners. Settings uses it to light the right chip.
 */
fun FloatingButtonConfig.pinnedCorner(): Corner? {
    val portrait = positionFraction(portrait = true)?.let { PositionFraction(it.first, it.second) } ?: return null
    val landscape = positionFraction(portrait = false)?.let { PositionFraction(it.first, it.second) } ?: return null
    val corner = Corner.fromFraction(portrait) ?: return null
    return if (Corner.fromFraction(landscape) == corner) corner else null
}

/**
 * This config pinned to [corner]: the corner's fraction written to *both* orientations, since a named
 * corner means the same place whichever way the screen is turned. The pixels are left as a harmless
 * fallback — the fraction wins on the next resolve.
 */
fun FloatingButtonConfig.withCorner(corner: Corner): FloatingButtonConfig {
    val f = corner.fraction
    return withPositionFraction(portrait = true, xFraction = f.xFraction, yFraction = f.yFraction)
        .withPositionFraction(portrait = false, xFraction = f.xFraction, yFraction = f.yFraction)
}
