package com.gamecore.domain.gaming

import com.gamecore.core.model.DisplaySize
import com.gamecore.core.model.GameProfile
import com.gamecore.core.model.ResolutionScale

/**
 * Which of a profile's two display overrides is the one to write (§B2).
 *
 * [GameProfile] carries two fields that are one `wm size` write: [GameProfile.displaySize], a size the
 * user typed or picked, and [GameProfile.resolutionOverride], a percentage of the panel. They cannot
 * both be honoured — the second write would silently undo the first, the restore journal would hold one
 * row for two changes, and the report would claim both — so the choice between them is made here, once,
 * rather than left to whoever happens to build the plan.
 *
 * **The resolution override wins.** It is the more specific instruction: a size is two numbers that mean
 * what they say, while a percentage is a statement about *this* panel that only holds if it is the one
 * applied. A profile holding both is a profile edited on one device and imported onto another — the
 * editor keeps the user from setting both, and §2's import validates the pair the same way — so the
 * losing field is a leftover rather than a request, and the newer of the two features is the better guess
 * at intent.
 *
 * Pure and dependency-free on purpose: this is the one decision in the display path that is worth
 * asserting directly, and [ProfileApplier] — which owns it — cannot be constructed in a plain JVM test.
 */
internal sealed interface DisplayTarget {

    /** A fixed size, stretched across the panel exactly as `wm size` reads it. */
    data class Stretch(val size: DisplaySize) : DisplayTarget

    /** A percentage of the panel's own resolution, both axes by the same factor. */
    data class Scale(val scale: ResolutionScale) : DisplayTarget

    companion object {

        /**
         * The override to apply, or null when the profile leaves the display's size alone.
         *
         * Null and not a third case, because "neither field is set" is not a target: it is the absence
         * of one, and the plan turns it into a skip with a reason rather than a write.
         */
        fun of(profile: GameProfile): DisplayTarget? {
            val scale = profile.resolutionOverride
            if (scale != null) return Scale(scale)
            val size = profile.displaySize
            return if (size != null) Stretch(size) else null
        }
    }
}
