package com.gamecore.core.config

/**
 * The update-drift check (spec §A9): before trusting a config backup or an edit, the editor compares the game
 * package's current install timestamp against the one recorded when the editor last touched that game. If the
 * game was updated in between, its config files may have changed shape, so an old backup or a stale edit could
 * be wrong — and the UI warns. This is a pure decision over two timestamps; the timestamps themselves come from
 * `PackageManager.getPackageInfo().lastUpdateTime` (no new permission), read by the caller and passed in here.
 */
object UpdateDriftDetector {
    /**
     * Compare a recorded [baselineLastUpdateMillis] (null when the editor has never recorded one for this game)
     * against the package's [currentLastUpdateMillis]. Any difference — including a *smaller* current value from
     * a downgrade or a clock change — counts as drift, because it means the install is no longer the one the
     * baseline described. Only an exact match is treated as unchanged.
     */
    fun detect(baselineLastUpdateMillis: Long?, currentLastUpdateMillis: Long): UpdateDrift = when {
        baselineLastUpdateMillis == null -> UpdateDrift.NO_BASELINE
        baselineLastUpdateMillis != currentLastUpdateMillis -> UpdateDrift.UPDATED_SINCE
        else -> UpdateDrift.UNCHANGED
    }
}

/** The result of [UpdateDriftDetector.detect]. */
enum class UpdateDrift {
    /** No baseline timestamp has been recorded yet, so drift cannot be judged (first encounter with the game). */
    NO_BASELINE,

    /** The package's install timestamp differs from the recorded baseline: the game was updated (or changed). */
    UPDATED_SINCE,

    /** The install timestamp matches the baseline exactly: nothing changed since the editor last looked. */
    UNCHANGED,
}
