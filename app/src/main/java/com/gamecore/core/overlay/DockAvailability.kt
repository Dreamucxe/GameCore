package com.gamecore.core.overlay

import com.gamecore.core.model.DockActionId
import com.gamecore.core.model.DockActionKind

/**
 * Why one dock control is drawn unavailable — a reason **code**, never the sentence (§3.7.1, feature 5).
 *
 * [DockToggle.reason] and [DockAction.reason] are strings, and the service is what fills them in. This enum
 * is the step before that: the pure answer to "is this control usable, and if not, which absence is it",
 * decided one package below the service and mapped to a string resource there. The split is deliberate and
 * it buys two separate things.
 *
 * The first is that `core.overlay` stays unit-testable. Everything in this file is plain Kotlin with no
 * `Context`, no `R` and no Android class in sight, which is what lets [DockActionId.unavailableReason] be
 * pinned by JUnit on a JVM — exactly what naming a destination rather than handing over a lambda bought for
 * [DockActionId.route]. A `getString` anywhere in here would need a `Context`, and a `Context` would move
 * the whole of this reasoning into the one class no unit test can construct.
 *
 * The second is that the dock and the full control panel say the *same words* about the same absence. Four
 * of the sentences below already exist, as string resources `GamingOverlayService.probePanel` hands to
 * [OverlayPanelState.reasonFor]; writing them out again as literals here would put two copies of one
 * user-facing sentence in the build, free to drift apart at the next edit and invisible to translation. A
 * user who meets the greyed Screenshot chip on the dock and then the greyed Screenshot tile on the panel is
 * meeting one fact, and should be told it once.
 *
 * This is the same move [com.gamecore.core.common.unavailabilityText] makes for a missing *reading* — one
 * place decides the explanation for an absence, so the dashboard, the HUD editor and a session report cannot
 * disagree — stopping one step short of it on purpose. That function hard-codes its sentences because it
 * predates any of them existing as a resource; the dock's four already do, so the dock names the absence and
 * lets the service name the sentence.
 */
enum class DockUnavailableReason {

    /**
     * No `MediaProjection` on this device at all, so there is no frame feed and no still to grab.
     *
     * → `R.string.overlay_capture_unsupported` ("Screen capture is not available on this device"), reused
     * verbatim from `probePanel`, which already puts that exact sentence on Screenshot, the loupe and Scout
     * from a single `capture.isSupported()` read.
     */
    CAPTURE_UNSUPPORTED,

    /**
     * A recording holds the projection, so a still cannot be taken beside it.
     *
     * → `R.string.overlay_recording_in_progress` ("Stop the recording first"). One MediaProjection at a time
     * is all the platform gives, and the recording owns it — which is why this bars Screenshot and nothing
     * else: the loupe, Scout and the replay buffer are further virtual displays on that same projection and
     * share it quite happily.
     */
    RECORDING_IN_PROGRESS,

    /**
     * The user has saved no HUD arrangement, so there is nothing to put on screen.
     *
     * → `R.string.overlay_no_hud_layout` ("No HUD layout saved yet"). A different sentence from "it is
     * switched off", which is the distinction `probePanel` is careful about too: one is an empty cupboard and
     * the other is a decision.
     */
    NO_HUD_LAYOUT,

    /**
     * The panel offers fewer than two selectable rates, so there is nothing to step between.
     *
     * → `R.string.overlay_refresh_single_rate` ("This panel runs at one rate only"). Fewer than two means the
     * user has nothing to choose, and a Refresh chip that silently did nothing would read as a control that
     * failed rather than as a device that has one rate.
     */
    SINGLE_REFRESH_RATE,

    /**
     * The display probe has not come back yet, so the rate count is not known.
     *
     * → a new string the service adds, wording "Checking refresh rates…". The honest state before the
     * one-shot probe lands, and the reason [DockSignals.selectableRates] is nullable: saying "one rate only"
     * here would be a claim the service has not yet earned, and on a 120Hz panel it would be a false one that
     * corrected itself a moment later in front of the user.
     *
     * Not the same as a probe that landed and could not read the mode list. That third case is the service's
     * to narrow — `probePanel` answers it with `rates.shortUnavailabilityText()` rather than the single-rate
     * sentence — so this code means "has not looked", not "looked and failed".
     */
    RATES_NOT_READ_YET,

    /**
     * The volume trigger for the game in front is not placeable yet.
     *
     * → a new string the service adds, wording "Set up a volume trigger for this game first". It covers the
     * whole composite gate: no game in front, no profile for it, no
     * [com.gamecore.core.model.VolumeTriggerConfig] on that profile, or the accessibility / Shizuku grants the
     * synthetic tap needs still missing.
     *
     * This code is the **floor**, not the final sentence. The service can often see which of those four it
     * is, and where it can it should narrow the wording to that specific missing piece — a user who has a
     * trigger set up and only lacks the accessibility grant is being told the wrong thing by the general
     * version. What this code promises is that the chip is never drawn live when a tap on it would drop the
     * user into a picker for a trigger they never set up.
     */
    TRIGGER_NOT_READY,

    /**
     * The user has switched this feature off in GameCore's own settings.
     *
     * → a new string the service adds, wording "Switched off in GameCore's settings" — the words
     * [com.gamecore.core.common.unavailabilityText] already uses for
     * [com.gamecore.core.common.RestrictionReason.SAMPLING_DISABLED], so the dock is matching copy the app
     * already shows rather than inventing a second way of saying it. (That one ends in a full stop and the
     * `overlay_*` resources do not, so the new resource should follow its neighbours and drop it.)
     *
     * The code for a screen the user has turned off — `screenExtractionEnabled`, `touchSamplingEnabled` — and
     * never for a device that cannot do something. The distinction is the whole value of having a separate
     * code for it: the fix for this one is a switch the user owns, two taps away in Settings, and telling them
     * "not available on this device" about their own preference would send them looking for a new phone.
     */
    FEATURE_SWITCHED_OFF,
}

/**
 * Everything [DockActionId.unavailableReason] needs to know about the device and the moment (§3.7.1,
 * feature 5).
 *
 * A flat snapshot of already-resolved facts, with nothing suspending and nothing Android behind any of them.
 * That shape is forced by where the answer is needed: the dock panel is a composable, it is recomposed while
 * sitting over a running game, and half of what decides availability is behind a `suspend` call —
 * `hudLayouts.layouts.first()`, `displayReader.supportedRates()`, every rate reader on `RefreshRateController`.
 * A composable cannot await any of them, and a panel that re-probed the display on each frame is the
 * background cost §26 rules out. So the service gathers these once, hands the snapshot down, and the rules
 * above it are arithmetic.
 *
 * Every field defaults to the most permissive *honest* value, so a test can construct one absence at a time
 * and a reader can see which signals are the restrictive ones. Two are exceptions, both documented as such
 * where they are declared: [triggerReady], because a trigger nobody has configured is genuinely absent rather
 * than merely unread, and [selectableRates], whose honest default is "nothing has looked yet" — which is a
 * refusal to claim a capability, not a claim of one.
 */
data class DockSignals(
    /**
     * Whether this device has a `MediaProjection` at all.
     *
     * ← `ScreenCaptureController.isSupported()`, which is non-suspend
     * (`core/system/ScreenCaptureController.kt:283` — it is a null check on the system service) and so can be
     * read wherever the snapshot is built.
     */
    val captureSupported: Boolean = true,

    /**
     * Whether a screen recording is running right now.
     *
     * ← `ScreenCaptureController.recording.value.isRecording`. A real `StateFlow`
     * (`core/system/ScreenCaptureController.kt:110`), so the snapshot can be rebuilt the moment it changes
     * rather than waiting for the next probe — this is the one signal here that flips mid-match.
     */
    val recording: Boolean = false,

    /**
     * Whether the user has a HUD arrangement saved.
     *
     * ← the service's own already-resolved layout. The panel's own probe reads `hudLayouts.layouts.first()`,
     * which suspends; the dock deliberately does not repeat that call and reads the resolved value instead,
     * for the reason this whole class exists.
     */
    val hasHudLayout: Boolean = true,

    /**
     * Whether the saved Hunt grade needs the capture feed.
     *
     * ← `preferences.settings.value.huntFilter.needsCapture`. The honest split
     * [com.gamecore.core.model.HuntFilter.needsCapture] draws: the capture-free grade tints GameCore's own
     * transparent glass and works on a build with no projection at all, so Hunt must not be barred by the
     * absence of something its current grade never asks for.
     */
    val huntNeedsCapture: Boolean = false,

    /**
     * How many distinct refresh rates this panel offers, or **null when the probe has not landed**.
     *
     * ← a one-shot `displayReader.supportedRates()` the service runs at start-up. One shot is enough because
     * display modes do not change at runtime, and it has to be a probe at all because every rate reader on
     * `DisplayReader` and `RefreshRateController` suspends and a composable cannot await one.
     *
     * Nullable rather than defaulting to 0, and that is the point of [DockUnavailableReason.RATES_NOT_READ_YET]:
     * "nobody has looked" and "there is one rate" are two different sentences, and collapsing them would have
     * the dock state a device fact it had not checked.
     */
    val selectableRates: Int? = null,

    /**
     * Whether a volume trigger can be placed over the game in front, right now.
     *
     * ← a composite the service assembles: a game in front, a profile for it, a
     * [com.gamecore.core.model.VolumeTriggerConfig] on that profile, and the grants the synthetic tap needs.
     *
     * The one field whose default is the **restrictive** value, on purpose. Every other default here answers
     * "assume it works until told otherwise", which is right when the cost of being wrong is a chip that
     * reports an error on tap. This one's cost is different: claiming a trigger is placeable when no profile
     * defines one drops the user into a placement overlay for a trigger they never set up, mid-match, and the
     * way out of it is not obvious. So the safe default is off, and the service has to say otherwise.
     */
    val triggerReady: Boolean = false,

    /**
     * Whether Screen Extraction is offered at all.
     *
     * ← `preferences.settings.value.screenExtractionEnabled`. A **preference, not a capability** — its own
     * KDoc in `core/model/SettingsModels.kt:319` says exactly that, and that it says nothing about whether
     * this device can hold a projection. Named `offered` rather than `supported` here so the two kinds of
     * absence cannot be confused at a call site.
     */
    val screenExtractionOffered: Boolean = true,

    /**
     * Whether the Touch Sampling Monitor is offered at all.
     *
     * ← `preferences.settings.value.touchSamplingEnabled`, and a preference for the same reason: its KDoc
     * (`core/model/SettingsModels.kt:328`) says it "never claims the panel's true sampling rate, only whether
     * the feature is offered".
     */
    val touchSamplingOffered: Boolean = true,
)

/**
 * Two or more rates before the Refresh chip is worth offering.
 *
 * The figure `GamingOverlayService.MIN_SELECTABLE_RATES` holds, and the test
 * `DisplayReader.hasVariableRefreshRate` applies: a row with one chip in it looks like a control that failed
 * to load, and the user has nothing to decide. Restated here rather than imported because `core.overlay` sits
 * *below* the service and must not reach up into it — the duplication runs in the direction that keeps the
 * dependency honest, and the service's copy is the one that should eventually point here.
 */
private const val MIN_SELECTABLE_RATES = 2

/**
 * Why [this] control cannot be used, or null when it can (§3.7.1, feature 5) — the one place the dock's
 * availability rules are written down.
 *
 * The sibling of [DockActionId.route], answering the other half of what the panel needs per cell: that
 * function says where a press goes, this one says whether the press is offered. Both are pure, both live
 * here rather than in the service, and both are here for the same reason — a rule about fourteen controls
 * that is spread across a 3,500-line service is a rule discovered by tapping chips on a phone.
 *
 * The `when` is exhaustive over all fourteen ids with **no `else`**, and that compile-time gate is the point
 * of the file. A fifteenth control added to [DockActionId] without a branch here does not build. The
 * alternative — an `else -> null` that quietly waved new controls through — would ship a chip whose
 * availability nobody had decided: drawn live, tapped mid-match, and failing in whatever way the service's
 * unprepared handler happened to fail. "Nobody has decided yet" is a build failure, not a default.
 *
 * Null for a usable control, so `unavailableReason(signals)` reads as the condition at a call site and the
 * reason-free case costs nothing. Where it returns a reason, the branch matches what `probePanel` does for
 * the equivalent full-panel tile, deliberately and down to the ordering: the dock is a second surface onto
 * the same controls, and two surfaces disagreeing about whether one control works is worse than either of
 * them being wrong.
 */
fun DockActionId.unavailableReason(signals: DockSignals): DockUnavailableReason? = when (this) {
    // ------------------------------------------------------- overlays that read nothing: never gated
    // Each is a window GameCore draws from its own state, so the overlay permission the dock already holds
    // is the whole of what they need. `probePanel` marks none of them unavailable either.
    DockActionId.CROSSHAIR -> null
    DockActionId.STATS -> null
    // The wheels guide "reads nothing and holds no feed, exactly like the crosshair" — the service's own
    // comment, and the reason it is left out of the capture-unsupported block that bars its two neighbours.
    DockActionId.WHEELS -> null

    // -------------------------------------------------------------------- the user's own arrangement
    // The HUD draws nothing from the device either, but it has nothing to draw until the user has saved a
    // layout — an empty cupboard rather than a missing capability, and a different sentence from both.
    DockActionId.HUD -> DockUnavailableReason.NO_HUD_LAYOUT.takeIf { !signals.hasHudLayout }

    // ---------------------------------------------------------- capture-fed overlays: the feed, only
    // Barred by the absence of a projection and by **nothing else** — in particular not by a recording in
    // progress, because the loupe's feed and a recording are two virtual displays on one projection and can
    // share it. `probePanel` is explicit about that, and a loupe that died when the user hit record would be
    // the bug this branch rules out.
    DockActionId.MAGNIFIER -> DockUnavailableReason.CAPTURE_UNSUPPORTED.takeIf { !signals.captureSupported }
    // Scout draws from the loupe's feed, so it is barred for the loupe's reason and on the loupe's terms.
    DockActionId.SCOUT -> DockUnavailableReason.CAPTURE_UNSUPPORTED.takeIf { !signals.captureSupported }
    // Hunt only when its saved grade actually wants the feed; the capture-free grade tints GameCore's own
    // glass and works on a device with no projection at all.
    DockActionId.HUNT -> DockUnavailableReason.CAPTURE_UNSUPPORTED
        .takeIf { !signals.captureSupported && signals.huntNeedsCapture }

    // ------------------------------------------------------------------- the full-panel escape hatch
    // Opens a window the service already owns, from a service that is already running. Nothing to check.
    DockActionId.FULL_PANEL -> null

    // -------------------------------------------------------------------------------- the still grab
    // Two gates, and the **order is load-bearing**: it matches the `if / else if` in `probePanel`, where the
    // capture-unsupported sentence wins. On a device with no projection, "stop the recording first" is advice
    // the user cannot act on and would not help if they could — there is no recording, and the deeper truth
    // is that this phone will never take the shot. The shallower sentence must not shadow it.
    DockActionId.SCREENSHOT -> if (!signals.captureSupported) {
        DockUnavailableReason.CAPTURE_UNSUPPORTED
    } else if (signals.recording) {
        DockUnavailableReason.RECORDING_IN_PROGRESS
    } else {
        null
    }

    // ------------------------------------------------------------------------------ the rate stepper
    // Also two gates in a fixed order, and for a sharper version of the same reason: before the probe lands
    // the service has no idea how many rates this panel has, so the only honest thing it can say is that it
    // is still looking.
    DockActionId.REFRESH_RATE -> {
        // Bound to a local so the nullable is read once and the null branch does not lean on a smart cast
        // reaching through a property to get there.
        val rates = signals.selectableRates
        if (rates == null) {
            DockUnavailableReason.RATES_NOT_READ_YET
        } else if (rates < MIN_SELECTABLE_RATES) {
            DockUnavailableReason.SINGLE_REFRESH_RATE
        } else {
            null
        }
    }

    // ---------------------------------------------------------------------------- the trigger placer
    DockActionId.TRIGGER_POINT -> DockUnavailableReason.TRIGGER_NOT_READY.takeIf { !signals.triggerReady }

    // ------------------------------------------------------------------------ GameCore's own screens
    // No `systemStatsEnabled` preference exists and there is no capability signal behind the figures either —
    // they come from `/proc` and from the app's own process — so [DockActionId.isAlwaysAvailable] is true and
    // the honest answer is that this chip always opens. A gate invented here would be one the Settings screen
    // offers no way to clear.
    DockActionId.SYSTEM_STATS -> null
    // Preferences, both of them, which is why the code is about a switch and not about the device.
    DockActionId.SCREEN_EXTRACTION ->
        DockUnavailableReason.FEATURE_SWITCHED_OFF.takeIf { !signals.screenExtractionOffered }
    DockActionId.TOUCH_SAMPLING ->
        DockUnavailableReason.FEATURE_SWITCHED_OFF.takeIf { !signals.touchSamplingOffered }
}

/**
 * Whether `AppSettings.quickActionsEnabled` governs whether this control is drawn at all (§3.7.1, feature 5).
 *
 * True for every [DockActionKind.ACTION] cell and nothing else, because that preference is about the panel's
 * chip grid as a whole: its own KDoc (`core/model/SettingsModels.kt:360`) says that with it off "the dock
 * panel draws only its toggle grid and no action chips".
 *
 * Which is exactly why this is a separate boolean and **not** a [DockUnavailableReason]. The two mechanisms
 * look similar and behave nothing alike. An unavailable control is still there — drawn with a ✕, greyed, with
 * a sentence under its label saying what is missing, because the user placed it and deserves to know why it
 * is dim. A switched-off quick-actions grid is *not drawn*: no chips, no ✕, no sentence, and the
 * customisation screen explains the absence in its own words instead of showing an inert list. Folding this
 * into the reason codes would turn a group-level hide into seven greyed chips all carrying the same sentence,
 * which is both noise over a game and a misdescription of what happened.
 *
 * Derived from [DockActionId.kind] in one expression rather than listed, so it cannot drift: a control added
 * to the enum lands on the right side of this the moment its kind is chosen, with nothing here to update.
 */
val DockActionId.isGatedByQuickActions: Boolean
    get() = kind == DockActionKind.ACTION
