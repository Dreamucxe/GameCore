package com.gamecore.aimlab.engine

/**
 * Decides what a finished pointer gesture was — a shot, or a look — from how far it travelled (§5).
 *
 * Pulled out of the Compose surface so the rule is one pure, unit-tested function instead of an inline
 * comparison the tests cannot reach. The evidence this fixes: a Reaction run showed 17 "misses" with 0
 * attempts while the target had not even appeared, which means something other than a deliberate tap was
 * being counted as a shot. The rule here is deliberately strict, so a look-drag can never be scored:
 *
 *  - a pointer that stayed within [slopPx] for its whole life is a **tap** — a deliberate shot;
 *  - a pointer that ever travelled past [slopPx] is a **drag** — look only, never a shot, even if the
 *    finger wandered back under the threshold before lifting (the travel is cumulative, never reset);
 *  - a pointer that went down inside an on-screen control's exclusion region is **neither** — it belongs
 *    to that control (the movement strafe pad, or a Shoot button) for its whole life.
 *
 * Touch-*down* is never itself a shot: the classification happens on release, so resting a thumb on the
 * screen while waiting for a reaction target does nothing until it lifts as a genuine tap. A gesture that
 * is still in progress is [TouchResult.PENDING].
 */
object TouchClassifier {

    /** Whether a completed gesture with [cumulativeTravelPx] of travel is a shot, given the touch [slopPx]. */
    fun classifyRelease(cumulativeTravelPx: Float, slopPx: Float): TouchResult =
        if (cumulativeTravelPx <= slopPx) TouchResult.TAP else TouchResult.DRAG

    /** Whether a pointer that has travelled [cumulativeTravelPx] is looking yet (past the slop). */
    fun isLooking(cumulativeTravelPx: Float, slopPx: Float): Boolean = cumulativeTravelPx > slopPx
}

/** The outcome of a pointer gesture on the training surface. */
enum class TouchResult {
    /** A deliberate tap: fires a shot. */
    TAP,

    /** A look drag: rotates the camera, never a shot. */
    DRAG,

    /** Down inside a control's region, or still in progress: neither a shot nor a look. */
    PENDING,
}
