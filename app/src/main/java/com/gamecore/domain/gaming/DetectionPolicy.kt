package com.gamecore.domain.gaming

/**
 * Why one foreground package is, or is not, watched for.
 *
 * [reason] is a sentence fragment the Games screen prints as-is, so it is written for the user rather
 * than for a log: "excluded by you" explains a deliberate choice, and "no enabled profile" explains a
 * game the user has not configured instead of implying detection is broken.
 */
data class DetectionDecision(
    val packageName: String,
    val shouldTrack: Boolean,
    val reason: String,
)

/**
 * The whole of "should GameCore act on this package?", as a pure function.
 *
 * The detector, the Games screen and the tests all have to agree on this answer. Keeping it out of
 * [GameDetector] means the precedence is decided in one place and can be exercised without a poll loop,
 * a `UsageStatsManager` or a database: an exclusion beats a pin, a pin beats an ordinary enabled profile,
 * and an empty reading never names a game.
 *
 * A pin does **not** create an enabled profile. It changes which reason is reported for a package that
 * was already going to be tracked — the UI also uses it for a badge and ordering — so it can never turn
 * a disabled profile into a running session.
 */
object DetectionPolicy {

    /** The answer for a reading with no package in it. */
    const val REASON_NO_PACKAGE = "no package"

    /** The answer for a package the user deliberately silenced. */
    const val REASON_EXCLUDED = "excluded by you"

    /** The answer for the package the user pinned. */
    const val REASON_PINNED = "pinned profile"

    /** The answer for any other package with an enabled profile. */
    const val REASON_ENABLED = "enabled profile"

    /** The answer for a package with no profile the detector may act on. */
    const val REASON_NO_ENABLED_PROFILE = "no enabled profile"

    /**
     * Decides whether [packageName] should be watched.
     *
     * The order is the policy: a blank reading is not a package at all; an exclusion is the user saying
     * no to this package whatever else is configured; a pin names the profile the user chose to keep in
     * view; an enabled profile is the ordinary case; and everything else is simply unknown.
     */
    fun decide(
        packageName: String,
        enabledProfilePackages: Set<String>,
        excluded: Set<String>,
        pinned: String?,
    ): DetectionDecision = when {
        packageName.isBlank() -> DetectionDecision(packageName, false, REASON_NO_PACKAGE)
        packageName in excluded -> DetectionDecision(packageName, false, REASON_EXCLUDED)
        packageName == pinned -> DetectionDecision(packageName, true, REASON_PINNED)
        packageName in enabledProfilePackages -> DetectionDecision(packageName, true, REASON_ENABLED)
        else -> DetectionDecision(packageName, false, REASON_NO_ENABLED_PROFILE)
    }

    /**
     * The exclusion set after the user adds or removes one package.
     *
     * Set semantics make the toggle idempotent: adding an entry that is already there, or removing one
     * that is not, leaves the same set and therefore does not cause a redundant settings write. A blank
     * package is ignored rather than stored, because there is no app it could ever match.
     */
    fun excludedAfterToggle(
        current: Set<String>,
        packageName: String,
        exclude: Boolean,
    ): Set<String> {
        if (packageName.isBlank()) return current
        return when {
            exclude && packageName in current -> current
            !exclude && packageName !in current -> current
            exclude -> current + packageName
            else -> current - packageName
        }
    }
}
