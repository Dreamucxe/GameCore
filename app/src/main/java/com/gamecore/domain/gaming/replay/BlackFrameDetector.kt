package com.gamecore.domain.gaming.replay

/**
 * Blocked-capture detection (audit A5), as a pure counter over luminance-variance samples.
 *
 * Some apps mark their surfaces uncapturable, so a MediaProjection of them yields black (or a single flat
 * colour) frames. The encoder's input Surface cannot be read back, so a small low-throttle ImageReader on
 * the same projection samples the mean luminance variance of a frame; that one number is fed here. A frame
 * with variance below the threshold is "uniform" (a flat, likely-blocked frame); a run of them means the
 * capture is producing nothing worth keeping and the buffer is blocked for the rest of the session.
 *
 * "For the session" is why [BlackFrameState.blocked] is sticky: once a game has proven uncapturable, a
 * later frame that happens to have texture (a loading spinner, a codec artefact) must not un-block it and
 * resume a buffer that will be black again a frame later — that is exactly the "control that lies" the app
 * refuses to ship.
 */
data class BlackFrameState(
    /** How many uniform frames have arrived back-to-back; reset to 0 by any textured frame. */
    val consecutiveUniform: Int = 0,
    /** Latched true once [consecutiveUniform] reaches the required run. Never clears within a session. */
    val blocked: Boolean = false,
)

/** What the caller should do with the sampled frame. */
sealed interface BlackFrameDecision {
    /** The capture looks fine (or has not yet proven otherwise); keep buffering. */
    data object KeepGoing : BlackFrameDecision

    /** A uniform run has tripped the detector; the capture is blocked for this session. */
    data object BlockedThisSession : BlackFrameDecision
}

object BlackFrameDetector {

    /**
     * Luminance variance below this counts a frame as uniform. Luminance is 0–255, so a truly flat frame
     * has variance 0; 1.0 admits only near-perfectly-flat frames (imperceptible dithering), well below any
     * real game frame's variance.
     */
    const val DEFAULT_UNIFORM_THRESHOLD = 1.0

    /** How many uniform frames in a row trip the block. Five low-throttle samples ≈ a sustained black feed. */
    const val DEFAULT_REQUIRED_CONSECUTIVE = 5

    /**
     * Fold one sample into the state.
     *
     * The threshold is half-open: `variance < uniformThreshold` is uniform (increments the run); a variance
     * exactly at, or above, the threshold is textured and resets the run to 0. Reaching `requiredConsecutive`
     * uniform frames latches [BlackFrameState.blocked] and returns [BlackFrameDecision.BlockedThisSession];
     * once blocked, every later sample returns that too, whatever its variance.
     *
     * @param luminanceVariance the mean luminance variance of the sampled frame.
     * @param uniformThreshold the variance below which a frame is considered flat.
     * @param requiredConsecutive uniform frames needed in a row to block.
     */
    fun decide(
        state: BlackFrameState,
        luminanceVariance: Double,
        uniformThreshold: Double = DEFAULT_UNIFORM_THRESHOLD,
        requiredConsecutive: Int = DEFAULT_REQUIRED_CONSECUTIVE,
    ): Pair<BlackFrameState, BlackFrameDecision> {
        // Sticky: a session that has been declared blocked stays blocked, regardless of this frame.
        if (state.blocked) {
            return state to BlackFrameDecision.BlockedThisSession
        }

        val uniform = luminanceVariance < uniformThreshold
        if (!uniform) {
            // Any textured frame breaks the run.
            return state.copy(consecutiveUniform = 0) to BlackFrameDecision.KeepGoing
        }

        val run = state.consecutiveUniform + 1
        return if (run >= requiredConsecutive) {
            BlackFrameState(consecutiveUniform = run, blocked = true) to
                BlackFrameDecision.BlockedThisSession
        } else {
            state.copy(consecutiveUniform = run) to BlackFrameDecision.KeepGoing
        }
    }
}
