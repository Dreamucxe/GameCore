package com.gamecore.core.overlay

import com.gamecore.core.model.RefreshRateOutcome

/**
 * What the panel does with the result of a refresh-rate change: which chip is filled, and what is said.
 *
 * A type rather than two lines at the call site, because the rule it encodes is the one this feature is most
 * likely to get wrong, and the wrong version compiles. [RefreshRateOutcome] has six cases and only one of
 * them is evidence that the display moved; the other five are various kinds of "we asked". A handler written
 * as `if (!outcome.isSuccess) toast(...)` — which is what every other control in this panel does, and is
 * correct for all of them — would leave the chip filled on all six, because filling it happened earlier on
 * the optimistic path. So the decision is made once, here, and returned as data the caller cannot ignore.
 *
 * Pure, and deliberately in [com.gamecore.core.overlay] rather than in the service: the service is an
 * Android component that cannot be constructed in a unit test, and this is the part of the feature that has
 * a right answer worth asserting.
 */
internal data class RefreshRateFeedback(
    /**
     * The rate to fill a chip against, or null to fill none.
     *
     * Carried as the *requested* figure rather than the one read back — see [refreshRateFeedback] for why.
     */
    val pinnedRateHz: Float?,
    /** The sentence to show the user, or null when there is nothing to say. */
    val message: String?,
)

/**
 * Turns one [RefreshRateOutcome] into the two things the panel needs from it.
 *
 * [requestedHz] is null for the release chip — "leave alone" — and that case is not a rate of its own: a
 * successful release means *nothing is pinned*, so it fills no chip rather than filling one labelled with
 * whatever rate the panel happened to settle on afterwards. [RefreshRateOutcome.Applied.rateHz] on the
 * release path is the panel's maximum, which would light the 120 Hz chip on a device that had just been told
 * to stop holding 120 Hz.
 *
 * On the apply path the confirmed rate is recorded as [requestedHz] and not as
 * [RefreshRateOutcome.Applied.rateHz], for a duller reason: the chips are labelled from
 * `DisplayReader.supportedRates()`, which advertises 119.998, while the read-back comes from `dumpsys` and
 * may say 120.0. `Applied` already means the two matched within the controller's own tolerance, so carrying
 * the chip's own figure makes the panel's comparison exact equality instead of a second tolerance that could
 * disagree with the first.
 *
 * Silence is granted to exactly one case. Every other outcome carries [RefreshRateOutcome.message] through
 * untouched — the same sentence the profile editor shows for the same outcome, including the one about a
 * chipset that accepts the request and stays where it was. Two sentences for one platform behaviour would
 * eventually disagree about it.
 */
internal fun refreshRateFeedback(
    requestedHz: Float?,
    outcome: RefreshRateOutcome,
): RefreshRateFeedback = when (outcome) {
    is RefreshRateOutcome.Applied -> RefreshRateFeedback(
        pinnedRateHz = requestedHz,
        message = null,
    )
    // Written, accepted, and unconfirmed. The chip stays empty and the reason is shown, which is the whole
    // of §24 applied to this one control: "requested, not confirmed" is not a success and is not a failure.
    is RefreshRateOutcome.AppliedUnverified -> RefreshRateFeedback(null, outcome.message)
    is RefreshRateOutcome.NotHonoured -> RefreshRateFeedback(null, outcome.message)
    is RefreshRateOutcome.RateUnsupported -> RefreshRateFeedback(null, outcome.message)
    is RefreshRateOutcome.RequiresAccess -> RefreshRateFeedback(null, outcome.message)
    is RefreshRateOutcome.Failed -> RefreshRateFeedback(null, outcome.message)
}
