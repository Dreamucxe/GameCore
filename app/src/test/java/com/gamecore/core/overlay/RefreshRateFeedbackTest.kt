package com.gamecore.core.overlay

import com.gamecore.core.model.RefreshRateOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one part of the panel's refresh-rate control that has a right answer worth asserting.
 *
 * Worth its own file because the rule under test is not "does this compile" but "which of six outcomes may
 * fill a chip", and five of the six are cases a hand-written handler gets wrong in the same direction: it
 * treats "we wrote the setting" as "the display moved". Every test below is really the same question asked
 * about a different outcome — *did the panel claim something it does not know* — so they are written to fail
 * loudly on the honest field, [RefreshRateFeedback.pinnedRateHz], rather than on the message text.
 *
 * The messages themselves are deliberately compared against [RefreshRateOutcome.message] rather than against
 * string literals. Literals here would pass while saying something different from what the profile editor
 * says for the same outcome, which is the drift the shared property exists to prevent — a test that pinned
 * the words would make the drift *harder* to notice, not easier.
 */
class RefreshRateFeedbackTest {

    @Test
    fun `a confirmed change fills the chip the user tapped and says nothing`() {
        val outcome = RefreshRateOutcome.Applied(rateHz = 120f, verifiedBy = "the settings provider")

        val feedback = refreshRateFeedback(requestedHz = 120f, outcome = outcome)

        assertEquals(120f, feedback.pinnedRateHz)
        assertNull("A change that worked has nothing to announce", feedback.message)
    }

    /**
     * The reason [refreshRateFeedback] carries the requested figure rather than the read-back one.
     *
     * A panel advertises 119.998 through `Display.getSupportedModes()` and the controller reads 120.0 back
     * out of `dumpsys`; `Applied` already means those two agreed within its own tolerance. Recording the
     * read-back would leave the chip labelled 119.998 compared against a pin of 120.0 — no chip filled after
     * a change that worked, which is the same silence a failure produces.
     */
    @Test
    fun `the chip is filled from the rate that was asked for and not the rate that was read back`() {
        val outcome = RefreshRateOutcome.Applied(rateHz = 120.0f, verifiedBy = "the settings provider")

        val feedback = refreshRateFeedback(requestedHz = 119.998f, outcome = outcome)

        assertEquals(119.998f, feedback.pinnedRateHz)
    }

    /**
     * "Leave alone" is the release chip, and a successful release leaves the row empty rather than moving the
     * fill onto whatever rate the panel settled at.
     *
     * `release()` reports `Applied(rateHz = maxSupported)`, so a handler that recorded the outcome's own
     * figure would light the 120 Hz chip immediately after being told to stop holding 120 Hz.
     */
    @Test
    fun `releasing the pin fills no chip even though the release succeeded`() {
        val outcome = RefreshRateOutcome.Applied(rateHz = 120f, verifiedBy = "the settings provider")

        val feedback = refreshRateFeedback(requestedHz = null, outcome = outcome)

        assertNull("Nothing is pinned after a release, so nothing is filled", feedback.pinnedRateHz)
        assertNull(feedback.message)
    }

    /**
     * The MediaTek case, and the one this whole type exists for.
     *
     * Both settings keys were written, both read back intact, and the panel is still at 60. There is nothing
     * in the platform's own state that distinguishes this from a success — which is exactly why the panel
     * must not fill a chip from the platform's state.
     */
    @Test
    fun `a rate the platform accepted and ignored fills no chip and carries its own sentence`() {
        val outcome = RefreshRateOutcome.NotHonoured(requestedHz = 120f, actualHz = 60f)

        val feedback = refreshRateFeedback(requestedHz = 120f, outcome = outcome)

        assertNull(feedback.pinnedRateHz)
        assertEquals(outcome.message, feedback.message)
    }

    @Test
    fun `a change that could not be confirmed fills no chip`() {
        val outcome = RefreshRateOutcome.AppliedUnverified(
            requestedHz = 90f,
            reason = "no elevated shell to query the platform's own dump",
        )

        val feedback = refreshRateFeedback(requestedHz = 90f, outcome = outcome)

        assertNull("Requested is not confirmed", feedback.pinnedRateHz)
        assertEquals(outcome.message, feedback.message)
    }

    @Test
    fun `a rate this display does not offer fills no chip`() {
        val outcome = RefreshRateOutcome.RateUnsupported(
            requestedHz = 144f,
            available = listOf(60f, 120f),
        )

        val feedback = refreshRateFeedback(requestedHz = 144f, outcome = outcome)

        assertNull(feedback.pinnedRateHz)
        assertEquals(outcome.message, feedback.message)
    }

    @Test
    fun `a missing access fills no chip and passes the access reason through`() {
        val outcome = RefreshRateOutcome.RequiresAccess(
            detail = "Setting the refresh rate needs Shizuku on this device.",
            needsShizuku = true,
        )

        val feedback = refreshRateFeedback(requestedHz = 120f, outcome = outcome)

        assertNull(feedback.pinnedRateHz)
        assertEquals("Setting the refresh rate needs Shizuku on this device.", feedback.message)
    }

    @Test
    fun `an outright failure fills no chip`() {
        val outcome = RefreshRateOutcome.Failed(detail = "The shell command returned nothing.")

        val feedback = refreshRateFeedback(requestedHz = 60f, outcome = outcome)

        assertNull(feedback.pinnedRateHz)
        assertEquals("The shell command returned nothing.", feedback.message)
    }

    /**
     * The property the panel's honesty rests on, stated once as a rule rather than implied eight times above.
     *
     * Every outcome that is not [RefreshRateOutcome.Applied] must fill no chip *and* must say something. The
     * pairing is the point: a case that filled nothing and said nothing would leave the user looking at a row
     * where their tap did nothing at all, which is the failure the whole feature is built to avoid.
     */
    @Test
    fun `every outcome other than a confirmed one is silent about the rate and loud about the reason`() {
        val unsuccessful = listOf(
            RefreshRateOutcome.NotHonoured(requestedHz = 120f, actualHz = null),
            RefreshRateOutcome.AppliedUnverified(requestedHz = 120f, reason = "not readable"),
            RefreshRateOutcome.RateUnsupported(requestedHz = 120f, available = emptyList()),
            RefreshRateOutcome.RequiresAccess(detail = "Needs Shizuku.", needsShizuku = true),
            RefreshRateOutcome.Failed(detail = "Write refused."),
        )

        unsuccessful.forEach { outcome ->
            val feedback = refreshRateFeedback(requestedHz = 120f, outcome = outcome)
            assertNull("$outcome must not fill a chip", feedback.pinnedRateHz)
            assertEquals("$outcome must explain itself", outcome.message, feedback.message)
        }
    }
}
