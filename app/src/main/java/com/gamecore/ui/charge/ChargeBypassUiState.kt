package com.gamecore.ui.charge

import com.gamecore.core.common.Observed
import com.gamecore.core.common.RestrictionReason
import com.gamecore.core.common.unavailabilityText
import com.gamecore.core.common.valueOrNull
import com.gamecore.domain.charge.ChargeControlNode

/**
 * What the charge-bypass screen knows: whether this device can be run from the charger, and whether it
 * is being right now.
 *
 * The screen behind this is an explainer with one switch, so the state is small — but the same §22
 * discipline the rest of the app follows applies to the one fact that matters here. Whether the feature
 * is available is not a guess from `Build.MANUFACTURER`; it is [support], the result of
 * [com.gamecore.domain.charge.ChargeBypassController.support] actually probing the hardware for a
 * writable power-supply node. So the switch is greyed the same way ShizukuScreen greys what this device
 * will not honour: [canToggle] is false until [support] is an [Observed.Value], and the reason it is
 * false is the device's own words in [unavailableReason], not a shrug.
 *
 * [isBypassed] is the switch's position, and it tracks the last *confirmed* action rather than an
 * optimistic flip. A write the shell could not read back leaves the switch where it was and puts the
 * failure in [message], because a charge-stop that is reported "on" but did not take is exactly the lie
 * §24's honesty rule is against — the controller itself backs such a write out, and this mirrors that by
 * refusing to move the switch on anything short of an [Observed.Value].
 */
data class ChargeBypassUiState(
    /** The probe: the node this device exposes, or the device's reason there is none. */
    val support: Observed<ChargeControlNode> = Observed.awaitingSample(NOT_PROBED),
    /** The switch's position — true only while a confirmed bypass of GameCore's is in effect. */
    val isBypassed: Boolean = false,
    /** A toggle is mid-flight; the switch is disabled and the busy label shows. */
    val isBusy: Boolean = false,
    /** The first probe has not answered yet, so the card shows "Checking…" rather than a verdict. */
    val isProbing: Boolean = false,
    /**
     * Whether GameCore's own thermal warnings are on, read straight from settings.
     *
     * Surfaced only to cross-reference honestly in the heat note: stopping the charge keeps the cell
     * cooler, it does *not* cool the processor, and a hot game is still a hot game — so the screen points
     * at the warning the user can lean on for that rather than implying this feature covers it.
     */
    val thermalWarningsOn: Boolean = true,
    /** The last result, shown once in a banner the user dismisses. Never a running log. */
    val message: String? = null,
) {

    /** The writable node this device exposes, or null when none was found. */
    val node: ChargeControlNode? get() = support.valueOrNull

    /** True once a node has been probed and found writable — the one condition the switch needs. */
    val isSupported: Boolean get() = support is Observed.Value

    /**
     * The two absences told apart, so the screen can say the useful thing rather than "not available".
     *
     * A missing shell is fixable — the user installs Shizuku and comes back — and a missing node is not,
     * so the copy under a disabled switch differs by which of these is true.
     */
    val needsShizuku: Boolean
        get() = (support as? Observed.Restricted)?.reason == RestrictionReason.REQUIRES_ELEVATED_ACCESS

    val nodeAbsent: Boolean
        get() = (support as? Observed.Restricted)?.reason == RestrictionReason.NOT_PRESENT_ON_DEVICE

    /** The gate. The switch does nothing until the hardware has been proven to accept the write. */
    val canToggle: Boolean get() = isSupported && !isBusy

    /**
     * The device's own reason the feature is unavailable, or null when it is available.
     *
     * The controller writes these sentences ([com.gamecore.domain.charge.ChargeBypassController]'s
     * `SHIZUKU_REQUIRED` and `NO_NODE`), so the screen shows the reason the same voice the rest of the
     * app explains an absence in, falling back to the shared wording only if a detail ever comes blank.
     */
    val unavailableReason: String?
        get() = when (val s = support) {
            is Observed.Value -> null
            is Observed.Restricted -> s.detail.ifBlank { s.unavailabilityText() ?: "" }
            is Observed.Failed -> s.detail.ifBlank { s.unavailabilityText() ?: "" }
        }
}

/** The placeholder [ChargeBypassUiState.support] carries before the first probe answers. */
private const val NOT_PROBED =
    "GameCore has not checked this device for a charge-control node yet."
