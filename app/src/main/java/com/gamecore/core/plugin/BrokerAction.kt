package com.gamecore.core.plugin

import com.gamecore.core.shizuku.ShellCommand
import com.gamecore.core.shizuku.WritableSetting

/**
 * The catalogue of device-affecting actions a plugin may *request* — and the whole of what
 * it may request. This is the Shizuku Action Broker's registry, and it is the single most
 * security-sensitive surface in the plugin platform, so every property of it is deliberate:
 *
 *  - **Named, not expressed.** A plugin asks for an action by [id] and hands over one typed
 *    value. It never supplies a command, a shell, a settings key, a namespace, or a binder.
 *    The mapping from id to an actual [WritableSetting] lives here, in GameCore, and a
 *    plugin cannot reach a setting that has no action pointing at it.
 *  - **Closed.** There is no "custom action". An id not in this registry is rejected. The
 *    set of reachable device changes is exactly this list and changes only when GameCore's
 *    own source changes.
 *  - **Fixed template.** The command is built by [ShellCommand.putSetting] from the
 *    registry's own [WritableSetting] plus the validated value. The plugin's value is the
 *    *only* variable, and it is passed as an argv element, never concatenated into a string,
 *    so there is nothing to inject into.
 *  - **Validated and clamped.** The value must pass the setting's [ValueForm]; a value the
 *    form rejects is refused outright, and a numeric value outside an action's own tighter
 *    bounds is clamped to the bound (and the clamp is audited), never passed through.
 *  - **Tiered.** [tier] drives consent friction; HIGH actions need the SYSTEM_ACTIONS
 *    advanced opt-in (enforced by the gate) *and* per-use confirmation (enforced by the
 *    host before it calls the broker).
 *  - **Reversible.** [autoRevert] actions must capture the prior reading and schedule a
 *    restore; an action that changes something the user could get stranded by (display
 *    size/density) is never fire-and-forget.
 *
 * Actions that are never representable here, by omission and by the absence of any
 * [WritableSetting] or [ShellCommand] case for them: a raw/arbitrary command, installing or
 * removing packages, granting or revoking permissions for anything but GameCore's own
 * self-grant flow (which is not a plugin action at all), reading another app's data or
 * `/data`, an arbitrary `settings put` key, arbitrary broadcasts or intents, and screen
 * capture or reading. None of these has an entry, and there is no entry shape that could
 * carry one.
 */
enum class BrokerAction(
    val id: String,
    val tier: RiskTier,
    val setting: WritableSetting,
    /** Optional tighter numeric bound than the setting's own form, clamped (not rejected). */
    val clampMin: Float? = null,
    val clampMax: Float? = null,
    /** Must the prior value be captured and auto-restored? */
    val autoRevert: Boolean = false,
    val userDescription: String,
) {
    // ---- LOW: cosmetic / fully-reversible, no way to strand the user ----

    SET_PEAK_REFRESH_RATE(
        id = "set_peak_refresh_rate", tier = RiskTier.LOW,
        setting = WritableSetting.PEAK_REFRESH_RATE,
        userDescription = "Set the display's peak refresh rate.",
    ),
    SET_MIN_REFRESH_RATE(
        id = "set_min_refresh_rate", tier = RiskTier.LOW,
        setting = WritableSetting.MIN_REFRESH_RATE,
        userDescription = "Set the display's minimum refresh rate.",
    ),
    SET_WINDOW_ANIMATION_SCALE(
        id = "set_window_animation_scale", tier = RiskTier.LOW,
        setting = WritableSetting.WINDOW_ANIMATION_SCALE,
        clampMin = 0f, clampMax = 10f,
        userDescription = "Set the window animation scale.",
    ),
    SET_TRANSITION_ANIMATION_SCALE(
        id = "set_transition_animation_scale", tier = RiskTier.LOW,
        setting = WritableSetting.TRANSITION_ANIMATION_SCALE,
        clampMin = 0f, clampMax = 10f,
        userDescription = "Set the transition animation scale.",
    ),
    SET_ANIMATOR_DURATION_SCALE(
        id = "set_animator_duration_scale", tier = RiskTier.LOW,
        setting = WritableSetting.ANIMATOR_DURATION_SCALE,
        clampMin = 0f, clampMax = 10f,
        userDescription = "Set the animator duration scale.",
    ),

    // ---- MEDIUM: changes something the user will notice; still reversible ----

    SET_LOW_POWER(
        id = "set_low_power", tier = RiskTier.MEDIUM,
        setting = WritableSetting.LOW_POWER,
        userDescription = "Turn battery saver on or off.",
    ),
    SET_SCREEN_BRIGHTNESS(
        id = "set_screen_brightness", tier = RiskTier.MEDIUM,
        setting = WritableSetting.SCREEN_BRIGHTNESS,
        autoRevert = true,
        userDescription = "Set screen brightness (reverts automatically).",
    );

    companion object {
        private val byId = entries.associateBy { it.id }
        fun fromId(id: String): BrokerAction? = byId[id.trim().lowercase()]
    }
}

/**
 * A plugin's request to run one [BrokerAction]. The value is a bare string because that is
 * what a settings write ultimately is; it is treated as wholly untrusted here and never
 * reaches a command until it has passed the action's form and clamp.
 */
data class ActionRequest(val actionId: String, val value: String)

/** The typed outcome of asking the broker to run an action. Never an exception, never a passthrough. */
sealed interface BrokerResult {
    data class Applied(val action: BrokerAction, val appliedValue: String, val priorValue: String?) : BrokerResult

    /**
     * The value was valid but outside the action's own tighter bound and was honoured at the
     * bound instead of refused. [priorValue] is carried exactly as in [Applied] and for the same
     * reason: the value that was set is not the value that was asked for, but an auto-revert
     * action still has to know what to restore, and a clamp must not be the one path that loses
     * the restore point. No action in the registry clamps *and* auto-reverts today — brightness,
     * the only auto-revert action, has no tighter bound — so this is latent rather than live, and
     * it is carried now because the two fields are read by the same caller and a caller that has
     * to remember which result shape kept the prior value will eventually get it wrong.
     */
    data class Clamped(
        val action: BrokerAction,
        val requested: String,
        val appliedValue: String,
        val priorValue: String? = null,
    ) : BrokerResult

    data class Rejected(val reason: BrokerReject, val detail: String = "") : BrokerResult
}

enum class BrokerReject {
    UNKNOWN_ACTION,      // no such action id — a plugin cannot invent one
    PERMISSION_DENIED,   // the gate said no (not granted / no advanced access / suspended / kill switch)
    INVALID_VALUE,       // the value is not even the right shape for the setting's form
    SHIZUKU_UNAVAILABLE, // the elevated shell is not connected; nothing was attempted
    COMMAND_BUILD_FAILED,// putSetting refused to build — defence in depth, should be unreachable after validation
    EXECUTION_FAILED,    // the shell ran it and it failed
    RATE_LIMITED,        // too many actions in the window
    NO_REVERT_POINT,     // an auto-revert action whose prior reading could not be captured — refused rather than run unrevertible
}
