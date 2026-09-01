package com.gamecore.core.system

/**
 * What a device control did.
 *
 * The same discipline [SettingsWriteOutcome] applies to settings writes, applied to the
 * controls that do not go through the settings provider — torch, volume, Do Not Disturb,
 * media keys. Each of those can fail for a reason that is *not* a bug and *not* a
 * permission problem: a device with no flash unit, a build whose vendor removed the
 * interruption-filter API, a stream the audio policy refuses to move while a call is up.
 *
 * [Unsupported] exists so those are reported as facts about the device rather than as
 * errors, which is what §24's "show 'Not supported on this device' rather than pretending"
 * requires at the level of a single button.
 */
sealed interface ControlOutcome {

    /** Done, and where a value is involved, this is the value now in effect. */
    data class Applied(val detail: String = "") : ControlOutcome

    /** The hardware or this Android build does not have this at all. */
    data class Unsupported(val detail: String) : ControlOutcome

    /** Needs an access the user has not granted. [needsShizuku] picks which button to show. */
    data class RequiresAccess(val detail: String, val needsShizuku: Boolean = false) :
        ControlOutcome

    /** A genuine failure: the platform threw, or refused for a reason it did not explain. */
    data class Failed(val detail: String) : ControlOutcome

    val isApplied: Boolean get() = this is Applied

    val message: String
        get() = when (this) {
            is Applied -> detail
            is Unsupported -> detail
            is RequiresAccess -> detail
            is Failed -> detail
        }

    companion object {
        /** Maps a settings write onto the common vocabulary, for the controls that use one. */
        fun from(outcome: SettingsWriteOutcome): ControlOutcome = when (outcome) {
            is SettingsWriteOutcome.Applied -> Applied()
            is SettingsWriteOutcome.AppliedUnverified -> Applied(
                "Applied, but the change could not be confirmed: ${outcome.reason}.",
            )
            is SettingsWriteOutcome.NotHonoured -> Failed(
                "This device accepted the change and did not apply it.",
            )
            is SettingsWriteOutcome.RequiresAccess -> RequiresAccess(
                outcome.detail,
                outcome.needsShizuku,
            )
            is SettingsWriteOutcome.Rejected -> Failed(outcome.detail)
            is SettingsWriteOutcome.Failed -> Failed(outcome.detail)
        }
    }
}
