package com.gamecore.core.overlay

import com.gamecore.core.model.DockActionId
import com.gamecore.core.model.DockActionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dock's availability rules — [DockActionId.unavailableReason] and [DockActionId.isGatedByQuickActions]
 * (§3.7.1, feature 5).
 *
 * [DockActionRoutesTest] pins where a press goes; this pins whether the press is offered at all, and the two
 * failures it guards against are opposites. A control wrongly marked unavailable is a feature the user paid
 * for, placed in their dock and cannot use, with a sentence under it explaining a limitation that is not
 * real. A control wrongly marked available is worse: it is drawn live, tapped mid-match, and fails in
 * whatever way the service's unprepared path happens to fail — a loupe with no feed, a rate step that sets
 * nothing, a placement overlay for a trigger the user never set up.
 *
 * Both are invisible in review, because the rules read plausibly either way and the signals that would expose
 * them belong to a device nobody tests on: the phone with no `MediaProjection`, the single-rate panel, the
 * profile with no volume trigger. Asserting them here is the only place they get checked at all, and that is
 * possible only because [DockAvailability] is pure — the signals are a snapshot of already-resolved facts, so
 * every one of these cases is a constructor call rather than a device.
 *
 * Each test below moves one field off a named base fixture — [capable] for the single-absence cases, so that
 * the absence under test is the only thing wrong with the device, and [hostile] for the cases that assert a
 * signal reaches nothing. The bare [DockSignals] defaults are themselves asserted, once, by
 * `the default signals disable exactly the trigger and refresh chips`: they are deliberately not a device that
 * can do everything, and a test built on them would be asserting two absences while claiming to assert one.
 */
class DockAvailabilityTest {

    /**
     * A device that can do everything: capture works, nothing is recording, a HUD is saved, four rates are
     * known and a trigger is ready to place. The baseline the single-absence tests move one field away from.
     */
    private val capable = DockSignals(selectableRates = 4, triggerReady = true)

    // ---------------------------------------------------------------- nothing is gratuitously barred

    /**
     * On a fully capable device all fourteen controls are usable.
     *
     * The direction of the check that is easy to leave out, and the one a cautious rule breaks first: a gate
     * written slightly too wide — `selectableRates <= 2`, say, or a Hunt branch that forgot
     * [DockSignals.huntNeedsCapture] — still passes every test that asserts a reason, because it produces
     * one. It is only visible from this side, as a control greyed on a phone that could run it.
     */
    @Test
    fun `every control is usable on a fully capable device`() {
        for (id in DockActionId.entries) {
            assertNull(
                "${id.name} is greyed out on a device that can do everything",
                id.unavailableReason(capable),
            )
        }
    }

    /**
     * The five controls GameCore draws from its own state cannot be disabled by any signal at all.
     *
     * Asserted against the most hostile snapshot the type allows — no capture, mid-recording, no rates read,
     * no trigger, both feature screens switched off — because these are the controls whose branch is `null`
     * with nothing guarding it, and a well-meant future edit that moved a capture check up one line would
     * take the crosshair down with it. The crosshair, stats pill and wheels guide read nothing and hold no
     * feed; the full-panel chip opens a window the service already owns; the RAM/CPU figures come from
     * `/proc` and from this app's own process. None of them has anything to be unavailable about.
     *
     * The HUD is left out on purpose: it is the one control in that group a signal legitimately disables, and
     * the test below it covers that.
     */
    @Test
    fun `no signal can disable a control that reads nothing`() {
        val hostile = DockSignals(
            captureSupported = false,
            recording = true,
            hasHudLayout = false,
            huntNeedsCapture = true,
            selectableRates = null,
            triggerReady = false,
            screenExtractionOffered = false,
            touchSamplingOffered = false,
        )
        val selfContained = listOf(
            DockActionId.CROSSHAIR,
            DockActionId.STATS,
            DockActionId.WHEELS,
            DockActionId.FULL_PANEL,
            DockActionId.SYSTEM_STATS,
        )
        for (id in selfContained) {
            assertNull(
                "${id.name} draws from GameCore's own state, so nothing about the device can bar it",
                id.unavailableReason(hostile),
            )
        }
    }

    // ---------------------------------------------------------------------------- one gate at a time

    @Test
    fun `the HUD cell waits only for a saved layout`() {
        assertEquals(
            DockUnavailableReason.NO_HUD_LAYOUT,
            DockActionId.HUD.unavailableReason(capable.copy(hasHudLayout = false)),
        )
        assertNull(DockActionId.HUD.unavailableReason(capable))
    }

    /**
     * An empty HUD is the HUD cell's business and nobody else's.
     *
     * The signal is the service's own resolved layout rather than a device fact, so a branch that read it for
     * the wrong control would be describing a saved file as a hardware limit. Worth pinning because the
     * sentence behind this code — "No HUD layout saved yet" — is nonsense on any other chip.
     */
    @Test
    fun `an empty HUD bars nothing but the HUD`() {
        val noLayout = capable.copy(hasHudLayout = false)
        for (id in DockActionId.entries) {
            if (id == DockActionId.HUD) continue
            assertNull("${id.name} is barred by an empty HUD layout", id.unavailableReason(noLayout))
        }
    }

    @Test
    fun `no projection bars the loupe and Scout`() {
        val noCapture = capable.copy(captureSupported = false)
        assertEquals(
            DockUnavailableReason.CAPTURE_UNSUPPORTED,
            DockActionId.MAGNIFIER.unavailableReason(noCapture),
        )
        assertEquals(
            DockUnavailableReason.CAPTURE_UNSUPPORTED,
            DockActionId.SCOUT.unavailableReason(noCapture),
        )
    }

    /**
     * A recording does **not** bar the loupe or Scout.
     *
     * The loupe's feed and a recording are two virtual displays on one `MediaProjection` and share it quite
     * happily — `GamingOverlayService.probePanel` says so where it bars the screenshot and deliberately not
     * the loupe. The plausible wrong rule is the symmetrical one, "a recording owns the projection, so
     * nothing else may use it", which would kill the magnifier the instant a user hit record and would look
     * like the overlay crashing rather than like a rule being applied.
     */
    @Test
    fun `a recording never bars the loupe or Scout`() {
        val recording = capable.copy(recording = true)
        assertNull(DockActionId.MAGNIFIER.unavailableReason(recording))
        assertNull(DockActionId.SCOUT.unavailableReason(recording))
    }

    /**
     * Hunt is barred by a missing projection only when its saved grade actually wants the feed.
     *
     * The capture-free grade tints GameCore's own transparent glass and draws nothing from the screen, so it
     * works on a device with no `MediaProjection` at all — which makes "no capture, therefore no Hunt" the
     * obvious rule and the wrong one. It would take a working feature away on exactly the devices that have
     * nothing else: no loupe, no Scout, no screenshot, and now not the one grade that never needed any of it.
     */
    @Test
    fun `Hunt is barred only when its grade needs the feed`() {
        val noCapture = capable.copy(captureSupported = false)
        assertNull(
            "the capture-free grade tints GameCore's own glass and needs no projection",
            DockActionId.HUNT.unavailableReason(noCapture),
        )
        assertEquals(
            DockUnavailableReason.CAPTURE_UNSUPPORTED,
            DockActionId.HUNT.unavailableReason(noCapture.copy(huntNeedsCapture = true)),
        )
    }

    /** A grade that wants the feed is no obstacle at all on a device that has one. */
    @Test
    fun `a capture-hungry Hunt grade is fine where capture works`() {
        assertNull(DockActionId.HUNT.unavailableReason(capable.copy(huntNeedsCapture = true)))
    }

    @Test
    fun `the screenshot chip is barred while a recording holds the projection`() {
        assertEquals(
            DockUnavailableReason.RECORDING_IN_PROGRESS,
            DockActionId.SCREENSHOT.unavailableReason(capable.copy(recording = true)),
        )
        assertNull(DockActionId.SCREENSHOT.unavailableReason(capable))
    }

    // ------------------------------------------------------------------------------- which gate wins

    /**
     * With no projection *and* a recording running, the screenshot chip reports the missing projection.
     *
     * Both conditions are true at once, so the answer is an ordering decision rather than a lookup, and it
     * matches the `if / else if` in `probePanel` on purpose. "Stop the recording first" is advice the user
     * cannot act on here — there is no recording to stop on a device that cannot hold a projection in the
     * first place — and if they somehow could, the shot still would not happen. The shallower sentence must
     * not shadow the deeper truth, and a rule written the other way round would be invisible until someone
     * held exactly this pair of conditions.
     */
    @Test
    fun `a missing projection outranks a running recording on the screenshot chip`() {
        val both = capable.copy(captureSupported = false, recording = true)
        assertEquals(
            "the deeper truth is that this device cannot take the shot at all",
            DockUnavailableReason.CAPTURE_UNSUPPORTED,
            DockActionId.SCREENSHOT.unavailableReason(both),
        )
    }

    // -------------------------------------------------------------------------------- the rate probe

    /**
     * Not having looked and having looked and found one rate are two different answers.
     *
     * The whole reason [DockSignals.selectableRates] is nullable. Collapsing the two — defaulting the count
     * to 0 before the one-shot probe lands — would have the dock tell a 120Hz owner "this panel runs at one
     * rate only" for as long as the probe took, and then silently correct itself. A stated device fact that
     * turns out to be false is worse than an honest "checking", which is what the nullable buys.
     */
    @Test
    fun `the refresh chip distinguishes not having looked from a single rate`() {
        assertEquals(
            "a probe that has not landed is not a single-rate panel",
            DockUnavailableReason.RATES_NOT_READ_YET,
            DockActionId.REFRESH_RATE.unavailableReason(capable.copy(selectableRates = null)),
        )
        assertEquals(
            DockUnavailableReason.SINGLE_REFRESH_RATE,
            DockActionId.REFRESH_RATE.unavailableReason(capable.copy(selectableRates = 1)),
        )
    }

    /**
     * Two is the threshold, matching `MIN_SELECTABLE_RATES` in the service and the test
     * `DisplayReader.hasVariableRefreshRate` applies. A row with one chip in it looks like a control that
     * failed to load; with two there is something to decide, so the chip is offered. Zero is asserted as well
     * because a mode list that reads as empty is still a panel the user cannot step.
     */
    @Test
    fun `two rates are enough and fewer are not`() {
        assertNull(DockActionId.REFRESH_RATE.unavailableReason(capable.copy(selectableRates = 2)))
        assertEquals(
            DockUnavailableReason.SINGLE_REFRESH_RATE,
            DockActionId.REFRESH_RATE.unavailableReason(capable.copy(selectableRates = 0)),
        )
    }

    // ------------------------------------------------------------------------------ the trigger chip

    @Test
    fun `the trigger chip is unavailable until a trigger is set up`() {
        assertEquals(
            DockUnavailableReason.TRIGGER_NOT_READY,
            DockActionId.TRIGGER_POINT.unavailableReason(capable.copy(triggerReady = false)),
        )
        assertNull(DockActionId.TRIGGER_POINT.unavailableReason(capable))
    }

    // -------------------------------------------------------------------- a preference, not a device

    /**
     * A screen the user switched off reports [DockUnavailableReason.FEATURE_SWITCHED_OFF], and the two
     * switches are independent.
     *
     * The distinction the code exists to make: the fix is two taps away in Settings and belongs to the user,
     * so telling them anything about the device would send them looking for a new phone. Both fields are
     * preferences and say so in their own KDoc in `SettingsModels.kt`. The independence half is asserted
     * because one shared branch for "a feature screen" would switch both off together, and a user who
     * disabled extraction would find the touch-rate chip greyed for a reason that was never theirs.
     */
    @Test
    fun `a switched-off screen reports a preference and not a device limit`() {
        val noExtraction = capable.copy(screenExtractionOffered = false)
        assertEquals(
            DockUnavailableReason.FEATURE_SWITCHED_OFF,
            DockActionId.SCREEN_EXTRACTION.unavailableReason(noExtraction),
        )
        assertNull(
            "the touch-rate screen has its own switch",
            DockActionId.TOUCH_SAMPLING.unavailableReason(noExtraction),
        )

        val noTouchSampling = capable.copy(touchSamplingOffered = false)
        assertEquals(
            DockUnavailableReason.FEATURE_SWITCHED_OFF,
            DockActionId.TOUCH_SAMPLING.unavailableReason(noTouchSampling),
        )
        assertNull(
            "the extraction screen has its own switch",
            DockActionId.SCREEN_EXTRACTION.unavailableReason(noTouchSampling),
        )
    }

    // ----------------------------------------------------------------------- the defaults themselves

    /**
     * [DockSignals] with nothing supplied disables exactly the trigger and the refresh chips.
     *
     * Pins which defaults are the restrictive ones, as a set rather than per field, so adding a field with a
     * cautious default fails here and has to be argued for. Both of these are deliberate and for different
     * reasons. [DockSignals.triggerReady] defaults false because the cost of a wrong "yes" is the user
     * dropped into a placement overlay for a trigger they never set up; [DockSignals.selectableRates]
     * defaults null because no probe has run yet, which is a fact about the service's start-up rather than a
     * policy. Everything else assumes it works until the service says otherwise, which is right when the cost
     * of being wrong is a chip that reports a problem on tap.
     */
    @Test
    fun `the default signals disable exactly the trigger and refresh chips`() {
        val barred = DockActionId.entries
            .filter { it.unavailableReason(DockSignals()) != null }
            .toSet()
        assertEquals(setOf(DockActionId.REFRESH_RATE, DockActionId.TRIGGER_POINT), barred)
    }

    /**
     * Every control declaring [DockActionId.isAlwaysAvailable] is usable under the bare defaults.
     *
     * The flag is "this control needs *nothing* to work", and the enum's own KDoc is careful that it is
     * documentation and a sane default rather than the gate — real capability is decided here. This test is
     * what keeps the two from contradicting each other, since a control that advertises needing nothing and
     * is then greyed out on a fresh install is the arrangement screen and the live panel disagreeing.
     *
     * One honest caveat. Five of the six — the crosshair, stats pill, wheels guide, full-panel chip and the
     * RAM/CPU figures — have no gate at all, so they pass by construction. **HUD passes because
     * [DockSignals.hasHudLayout] defaults to true**, and it is the one entry here that a signal can and does
     * disable: a user with no saved arrangement meets [DockUnavailableReason.NO_HUD_LAYOUT] on a control
     * flagged always-available. That is not a contradiction — an empty cupboard is not a missing capability,
     * and the flag is about what the control *needs*, not about whether the user has done the setting-up —
     * but it does mean this test leans on that default rather than on HUD having no gate, and the version of
     * it that passed `hasHudLayout = false` would fail on HUD by design.
     */
    @Test
    fun `every always-available control is usable under the default signals`() {
        for (id in DockActionId.entries) {
            if (!id.isAlwaysAvailable) continue
            assertNull(
                "${id.name} declares it needs nothing, yet is greyed out under the bare defaults",
                id.unavailableReason(DockSignals()),
            )
        }
    }

    // ----------------------------------------------------------------------- no orphaned reason code

    /**
     * Every [DockUnavailableReason] is reachable from some control under some snapshot.
     *
     * The gate that can go stale silently, which is why it is asserted rather than left to the compiler. The
     * `when` over the fourteen ids has no `else`, so a control with no branch cannot build — but a *code*
     * nothing produces compiles perfectly on both sides, and it means the service is carrying a string
     * resource and a mapping branch for an absence the dock will never report. That is how a half-finished
     * gate ships: the copy written, the resource added, and the rule it was written for never wired up.
     */
    @Test
    fun `every reason code is reachable from some control`() {
        val snapshots = listOf(
            capable,
            capable.copy(captureSupported = false, huntNeedsCapture = true),
            capable.copy(recording = true),
            capable.copy(hasHudLayout = false),
            capable.copy(selectableRates = null),
            capable.copy(selectableRates = 1),
            capable.copy(triggerReady = false),
            capable.copy(screenExtractionOffered = false, touchSamplingOffered = false),
        )
        val reached = snapshots
            .flatMap { signals -> DockActionId.entries.mapNotNull { it.unavailableReason(signals) } }
            .toSet()
        for (reason in DockUnavailableReason.entries) {
            assertTrue("$reason is a code no control ever reports", reason in reached)
        }
    }

    // ------------------------------------------------------------------------ the quick-actions gate

    /**
     * [DockActionId.isGatedByQuickActions] is exactly the chips, and never a toggle.
     *
     * Asserted twice over, because the two halves catch different mistakes. Against the ids by name, which
     * catches a [DockActionKind] changed on an enum entry — a control that quietly moved from the toggle grid
     * to the chip row, or out of it. And against the kind itself, which catches this being rewritten as a
     * hand-written list that then falls behind the enum.
     *
     * The gate is a group-level *hide*, not a per-control disable, which is why it is a boolean here and not
     * a [DockUnavailableReason]: with `AppSettings.quickActionsEnabled` off the panel draws no chips at all
     * rather than seven greyed ones each carrying the same sentence. Note that this is orthogonal to
     * [DockActionId.isAlwaysAvailable] — the RAM/CPU chip needs nothing to work *and* disappears with the
     * group, because it is a chip.
     */
    @Test
    fun `quick actions gate exactly the action chips`() {
        val gated = DockActionId.entries.filter { it.isGatedByQuickActions }.toSet()
        assertEquals(
            setOf(
                DockActionId.FULL_PANEL,
                DockActionId.SCREENSHOT,
                DockActionId.REFRESH_RATE,
                DockActionId.TRIGGER_POINT,
                DockActionId.SYSTEM_STATS,
                DockActionId.SCREEN_EXTRACTION,
                DockActionId.TOUCH_SAMPLING,
            ),
            gated,
        )
        for (id in DockActionId.entries) {
            assertEquals(
                "${id.name} is on the wrong side of the quick-actions gate for its kind",
                id.kind == DockActionKind.ACTION,
                id.isGatedByQuickActions,
            )
        }
    }

    /** A toggle is never hidden by the chip gate: the two grids are switched on and off separately. */
    @Test
    fun `no toggle is gated by quick actions`() {
        for (id in DockActionId.entries) {
            if (id.kind != DockActionKind.TOGGLE) continue
            assertFalse(
                "${id.name} is a toggle and would vanish with the chip row",
                id.isGatedByQuickActions,
            )
        }
    }
}
