package com.gamecore.core.system

import com.gamecore.core.common.DataSource
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.common.Observed
import com.gamecore.core.common.valueOrNull
import com.gamecore.core.model.RefreshRateMechanism
import com.gamecore.core.model.RefreshRateOutcome
import com.gamecore.core.shizuku.ElevatedShell
import com.gamecore.core.shizuku.ReadableProperty
import com.gamecore.core.shizuku.ShellCommand
import com.gamecore.core.shizuku.WritableSetting
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Changes the display's refresh rate, and proves it.
 *
 * The proof is the entire reason this class is more than two lines. Three separate
 * platform behaviours conspire to make a naive implementation lie:
 *
 *  1. `WindowManager.LayoutParams.preferredRefreshRate` and `Surface.setFrameRate()`
 *     only affect the calling app's own window. GameCore's window is not the game's, so
 *     neither can change the rate a game renders at, and neither is used here to claim
 *     one did. That is what [RefreshRateMechanism.WINDOW_PREFERENCE] exists to say.
 *  2. The keys that *are* device-wide — `min_refresh_rate` and `peak_refresh_rate`, the
 *     pair Android's own display settings write — are accepted by the settings provider
 *     on essentially every device and honoured by fewer. On MediaTek and PowerVR builds
 *     in particular, the write succeeds and the panel does not move.
 *  3. `Display.getRefreshRate()` reports the mode active at the moment of the call, and
 *     the platform drops to 60 on a static screen by design. So a low reading is not by
 *     itself evidence that a pin failed, and a high one taken before the mode switch is
 *     not evidence that it worked.
 *
 * So: both bounds are written, not just the peak — the display manager picks a mode
 * inside the window they describe, and a peak alone leaves the platform free to drop —
 * and then the *platform's own* view of the active mode is read back and compared.
 * [RefreshRateOutcome.Applied] is returned only after that comparison succeeds.
 * [RefreshRateOutcome.NotHonoured] is returned when the write went through and the panel
 * stayed where it was, which is the MediaTek case stated plainly rather than hidden.
 */
@Singleton
class RefreshRateController @Inject constructor(
    private val display: DisplayReader,
    private val settings: SettingsWriter,
    private val shell: ElevatedShell,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    /**
     * Which path a change would take on this device, so the UI can say so before the
     * user taps. Both keys are checked, because a device that lets an app write one and
     * not the other cannot pin a rate at all.
     */
    suspend fun mechanism(): RefreshRateMechanism = withContext(io) {
        val rates = display.supportedRates().valueOrNull.orEmpty()
        if (rates.size < 2) return@withContext RefreshRateMechanism.NONE

        val peak = settings.mechanismFor(WritableSetting.PEAK_REFRESH_RATE)
        val min = settings.mechanismFor(WritableSetting.MIN_REFRESH_RATE)
        when {
            !peak.isUsable || !min.isUsable -> RefreshRateMechanism.NONE
            peak == WriteMechanism.SHIZUKU || min == WriteMechanism.SHIZUKU ->
                RefreshRateMechanism.SHIZUKU_SETTINGS
            else -> RefreshRateMechanism.SYSTEM_SETTINGS
        }
    }

    /**
     * Pins the display to [rateHz], within the panel's own advertised rates.
     *
     * [pinMinimum] is the difference between "allow up to this rate" and "hold this
     * rate". A game profile wants the latter — the point of selecting 120 Hz for a game
     * is that the platform not drop to 60 during a quiet frame — so both bounds go to the
     * same value by default. The performance profiles that only want to raise the ceiling
     * pass false and leave the floor alone.
     */
    suspend fun apply(rateHz: Float, pinMinimum: Boolean = true): RefreshRateOutcome =
        withContext(io) {
            val available = display.supportedRates()
            val rates = available.valueOrNull
                ?: return@withContext RefreshRateOutcome.RequiresAccess(
                    detail = "This device does not report which refresh rates it supports, " +
                        "so GameCore will not write a rate it cannot check.",
                    needsShizuku = false,
                )

            // Matched with a tolerance because the panel advertises 119.998 and the user
            // taps a chip labelled 120.
            val matched = rates.minByOrNull { kotlin.math.abs(it - rateHz) }
            if (matched == null || kotlin.math.abs(matched - rateHz) > RATE_TOLERANCE) {
                return@withContext RefreshRateOutcome.RateUnsupported(rateHz, rates)
            }

            write(peak = matched, min = if (pinMinimum) matched else null, requested = matched)
        }

    /**
     * Releases the pin: `0` in both keys is the platform's own "no bound", which is what
     * Android's display settings write when the user picks automatic. Restoring a
     * captured previous value is [SettingsWriter.restore]'s job; this is the profile-exit
     * path for a device where nothing was captured.
     */
    suspend fun release(): RefreshRateOutcome = withContext(io) {
        val min = settings.write(WritableSetting.MIN_REFRESH_RATE, NO_BOUND)
        val peak = settings.write(WritableSetting.PEAK_REFRESH_RATE, NO_BOUND)
        when {
            peak.isApplied && min.isApplied -> RefreshRateOutcome.Applied(
                rateHz = display.supportedRates().valueOrNull?.maxOrNull() ?: 0f,
                verifiedBy = "the settings provider",
            )
            peak is SettingsWriteOutcome.RequiresAccess -> RefreshRateOutcome.RequiresAccess(
                peak.detail,
                peak.needsShizuku,
            )
            else -> RefreshRateOutcome.Failed(
                "The refresh-rate bounds could not be cleared on this device.",
            )
        }
    }

    /**
     * Whether this is a chipset on which the standard refresh-rate path is known to be
     * accepted and then ignored.
     *
     * Used to decide how hard to look for a confirmation, never to decide what to report:
     * an unverified change is [RefreshRateOutcome.AppliedUnverified] on a Snapdragon just
     * as much as on a MediaTek. Absent Shizuku there is no way to read a property, and
     * that is not a failure — it only means the device gets the same verification effort
     * as any other.
     */
    suspend fun isKnownUnreliableChipset(): Boolean = withContext(io) {
        if (!shell.isAvailable()) return@withContext false
        for (property in ReadableProperty.entries) {
            val value = shell.execute(ShellCommand.getProp(property)).singleValue()
                ?.lowercase()
                ?: continue
            if (UNRELIABLE_PLATFORM_HINTS.any { value.contains(it) }) return@withContext true
        }
        false
    }

    // --------------------------------------------------------------------- internals

    private suspend fun write(peak: Float, min: Float?, requested: Float): RefreshRateOutcome {
        // The floor goes first. Raising the floor above the current ceiling is rejected
        // by some builds, so on the way up the ceiling has to move first and on the way
        // down the floor does — writing peak first and min second is wrong exactly half
        // the time, so the floor is dropped to zero before either is set.
        if (min != null) {
            val cleared = settings.write(WritableSetting.MIN_REFRESH_RATE, NO_BOUND)
            if (cleared is SettingsWriteOutcome.RequiresAccess) {
                return RefreshRateOutcome.RequiresAccess(cleared.detail, cleared.needsShizuku)
            }
        }

        val peakOutcome = settings.write(WritableSetting.PEAK_REFRESH_RATE, format(peak))
        when (peakOutcome) {
            is SettingsWriteOutcome.RequiresAccess -> return RefreshRateOutcome.RequiresAccess(
                peakOutcome.detail,
                peakOutcome.needsShizuku,
            )
            is SettingsWriteOutcome.Rejected -> return RefreshRateOutcome.Failed(peakOutcome.detail)
            is SettingsWriteOutcome.Failed -> return RefreshRateOutcome.Failed(peakOutcome.detail)
            is SettingsWriteOutcome.NotHonoured -> return RefreshRateOutcome.NotHonoured(
                requestedHz = requested,
                actualHz = peakOutcome.actual.toFloatOrNull(),
            )
            else -> Unit
        }

        if (min != null) {
            val minOutcome = settings.write(WritableSetting.MIN_REFRESH_RATE, format(min))
            // A floor the device would not take is not fatal: the ceiling is set, so the
            // rate is available, and the platform may still drop on a static screen. The
            // verification below is what decides what the user is told.
            if (minOutcome is SettingsWriteOutcome.RequiresAccess) {
                return RefreshRateOutcome.RequiresAccess(
                    minOutcome.detail,
                    minOutcome.needsShizuku,
                )
            }
        }

        return verify(requested)
    }

    /**
     * Reads the change back, preferring the platform's own account of it.
     *
     * The mode switch is asynchronous — the display manager applies it on its own
     * thread — so a read taken immediately after the write catches the old mode on a
     * device where everything worked. Hence the settle delay, and hence two attempts.
     *
     * `dumpsys display` is the better witness where Shizuku is available: it names the
     * mode the display manager has *adopted*, which is the question being asked.
     * `Display.getRefreshRate()` answers a subtly different one — what is composited
     * right now — and the platform is entitled to drop that to 60 on a static screen, so
     * on its own it cannot distinguish "the pin failed" from "nothing is moving on
     * screen". It is therefore only trusted when it *confirms*; a low reading from it
     * falls through to [RefreshRateOutcome.AppliedUnverified] rather than being reported
     * as a failure that may not have happened.
     */
    private suspend fun verify(requested: Float): RefreshRateOutcome {
        repeat(VERIFY_ATTEMPTS) { attempt ->
            delay(SETTLE_MILLIS)

            if (shell.isAvailable()) {
                val dump = shell.execute(ShellCommand.DisplayDump)
                if (dump.isSuccess) {
                    val facts = DumpsysParsers.parseDisplay(dump.stdout).valueOrNull
                    val active = facts?.activeRefreshRate
                    if (active != null) {
                        return if (matches(active, requested)) {
                            RefreshRateOutcome.Applied(
                                rateHz = active,
                                verifiedBy = DataSource.DUMPSYS_SHIZUKU.label,
                            )
                        } else if (attempt == VERIFY_ATTEMPTS - 1) {
                            RefreshRateOutcome.NotHonoured(requested, active)
                        } else {
                            return@repeat
                        }
                    }
                }
            }

            val current = display.read().currentRefreshRate.valueOrNull
            if (current != null && matches(current, requested)) {
                return RefreshRateOutcome.Applied(
                    rateHz = current,
                    verifiedBy = DataSource.DISPLAY_MANAGER.label,
                )
            }
        }

        // Everything was written and accepted, and nothing could confirm the panel
        // adopted it. Reported as exactly that.
        return RefreshRateOutcome.AppliedUnverified(
            requestedHz = requested,
            reason = if (shell.isAvailable()) {
                "this device's display service did not report the active mode"
            } else {
                "confirming a device-wide rate change needs Shizuku"
            },
        )
    }

    private fun matches(actual: Float, requested: Float): Boolean =
        kotlin.math.abs(actual - requested) <= RATE_TOLERANCE

    /** `Locale.US`, so a device with a decimal-comma locale does not write `120,0`. */
    private fun format(rate: Float): String =
        String.format(java.util.Locale.US, "%.1f", rate)

    private companion object {
        /** A panel advertising 119.998 Hz is offering 120. */
        const val RATE_TOLERANCE = 1.5f

        /** What the platform's own display settings write to mean "no bound". */
        const val NO_BOUND = "0"

        const val SETTLE_MILLIS = 400L
        const val VERIFY_ATTEMPTS = 3

        /**
         * Chipset families where the standard per-window refresh-rate API is known to be
         * accepted and ignored. Substrings of `ro.board.platform` / `ro.hardware`.
         */
        val UNRELIABLE_PLATFORM_HINTS = listOf("mt6", "mt8", "metek", "mediatek", "powervr")
    }
}
