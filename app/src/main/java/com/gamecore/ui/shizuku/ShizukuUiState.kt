package com.gamecore.ui.shizuku

import com.gamecore.core.common.Observed
import com.gamecore.core.model.DeviceCapabilities
import com.gamecore.core.model.RootState
import com.gamecore.core.model.ShizukuState
import com.gamecore.core.shizuku.SelfGrantablePermission
import com.gamecore.ui.components.Readout

/**
 * What GameCore knows about the elevated shell, and what having it would change.
 *
 * §16's help screen and §29's capability report on one page, because they answer the same question from
 * two directions: "is Shizuku working" and "what am I missing without it". Both answers are facts read
 * from the device — [verification] is the result of running `id` through the shell rather than an
 * inference from a granted permission, since the two come apart after every reboot.
 *
 * §24A.2 again: the shell's own output does not reach the UI. [verification] carries one short string,
 * the uid the shell reported, and nothing else from the command.
 */
data class ShizukuUiState(
    val shizuku: ShizukuState = ShizukuState.RUNNING_PERMISSION_UNKNOWN,
    /** The result of actually running a command, or why one could not be run. */
    val verification: Observed<String> =
        Observed.awaitingSample("GameCore has not run a command through the shell yet."),
    val isVerifying: Boolean = false,
    val isRequesting: Boolean = false,
    val capabilities: DeviceCapabilities = DeviceCapabilities.UNKNOWN,
    val root: RootState = RootState.NOT_DETECTED,
    /**
     * The eight controls [controlSummary] counts, one row each.
     *
     * Exactly the eight, so the fraction can be checked by counting the green rows. A summary the user
     * cannot verify against what is on screen is a number they have to take on trust.
     */
    val controlRows: List<Readout> = emptyList(),
    /** What the shell adds to what GameCore can *read*, which is a different question from control. */
    val readingRows: List<Readout> = emptyList(),
    /** The two accesses Shizuku can grant GameCore directly, and whether it already has them. */
    val grants: List<GrantOffer> = emptyList(),
    val isGranting: Boolean = false,
    /** True when a Shizuku manager app is present, which decides whether "Open" or "Install" is shown. */
    val isInstalled: Boolean = false,
    val message: String? = null,
) {

    val isConnected: Boolean get() = shizuku.isUsable

    /**
     * "3 of 8 available" — the honest headline, and the reason this is a fraction.
     *
     * "Shizuku: connected" says nothing about whether this device honours what gets written through it,
     * and on the MediaTek builds §24B is about, that difference is the entire story.
     */
    val controlSummary: String =
        "${capabilities.usableControlCount} of ${capabilities.controlCount} controls available"

    /** True once the shell has answered a command, which is stronger than a granted permission. */
    val isProven: Boolean get() = verification is Observed.Value
}

/**
 * One access Shizuku can hand GameCore without a trip through Settings.
 *
 * Only two of them exist: usage access and modify-system-settings are app-ops, and an ADB-level shell can
 * set an app-op. Everything else in the permission catalogue is either a runtime dialog or a toggle only
 * the user can reach, and this list deliberately does not pretend otherwise.
 */
data class GrantOffer(
    val access: SelfGrantablePermission,
    val isGranted: Boolean,
    val detail: String,
)
