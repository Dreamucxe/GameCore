package com.gamecore.ui.setup

import com.gamecore.core.model.DeviceCapabilities
import com.gamecore.core.model.ShizukuState
import com.gamecore.core.permissions.GamePermission
import com.gamecore.domain.setup.PermissionNeed
import com.gamecore.domain.setup.SetupPreset
import com.gamecore.domain.setup.WizardFeatureChoices
import com.gamecore.domain.setup.WizardStep
import com.gamecore.domain.setup.presetSummary
import com.gamecore.domain.setup.visibleSteps
import com.gamecore.ui.games.AppOption

/**
 * The first-run wizard's state, and the presentation decisions that can be made without a `Context`.
 *
 * Two things live in this file and nothing else does. The first is [SetupWizardUiState] — one immutable
 * snapshot of everything the seven screens draw, in the shape [com.gamecore.ui.games.ProfileEditorUiState]
 * uses: a data class of plain values, with the derived questions answered by `get()` properties rather
 * than recomputed at each call site. The second is the set of pure functions the screen would otherwise
 * inline — the progress fraction, the step's heading, whether Next is allowed to move — which are here so
 * they can be asserted in a JUnit test with no Activity, exactly as
 * [com.gamecore.domain.setup.SetupWizardLogic]'s own decisions are.
 *
 * The split between this file and `SetupWizardLogic.kt` is deliberate and worth stating, because the two
 * could easily grow into each other. `SetupWizardLogic` answers *what the wizard does*: whether it appears,
 * which steps exist, which permissions a set of choices needs, where to resume. This file answers *what the
 * user sees*: the words on a step, the fraction in the bar, whether a button is enabled. A decision that
 * would still be true in a command-line version of the wizard belongs there; a decision about a label
 * belongs here. Nothing in this file re-derives anything the engine already decides — [visibleSteps],
 * [com.gamecore.domain.setup.nextStep] and [presetSummary] are called, never reimplemented.
 *
 * No `android.*` import appears below, and none may be added. That is what keeps
 * `SetupWizardPresentationTest` a plain JUnit test.
 */

/**
 * Everything the wizard draws, in one snapshot.
 *
 * [choices] is the user's answer to step 2 and the input to almost everything else: it decides which steps
 * exist ([steps]), which permissions are asked for ([permissionRows]), and whether the Shizuku and smart
 * steps appear at all. Holding it as the engine's own [WizardFeatureChoices] rather than eight booleans on
 * this class means the ViewModel hands the engine exactly what it stores, with no translation step that
 * could drift.
 *
 * [permissionRows] and [shizuku] are *re-read facts*, not remembered ones. Every value in them came from a
 * real platform call in [SetupWizardViewModel.recheck], and the wizard re-runs that call on every resume —
 * §A2 step 3's "never assume a grant" is enforced by there being nowhere in this class to cache one.
 *
 * [isPersisting] is the same honesty [com.gamecore.ui.settings.SettingsUiState] shows: if the encrypted
 * preferences file will not open, the wizard still runs, but the step it saves and the "completed" flag it
 * writes at the end last only as long as the process. A user about to spend two minutes on a setup flow is
 * entitled to know that before they start, not after the wizard reappears on next launch.
 */
data class SetupWizardUiState(
    val step: WizardStep = WizardStep.WELCOME,
    val choices: WizardFeatureChoices = WizardFeatureChoices(),
    /** True until the saved step and choices have been read back, so the screen does not flash step 1. */
    val isLoaded: Boolean = false,
    val isPersisting: Boolean = true,
    /** One row per permission the picked features need, in [com.gamecore.domain.setup.requiredPermissionSteps] order. */
    val permissionRows: List<PermissionRow> = emptyList(),
    /** Whether this build is API 33 or higher, which is the only thing that makes POST_NOTIFICATIONS a step. */
    val isApi33OrHigher: Boolean = false,
    val shizuku: ShizukuSummary = ShizukuSummary(),
    /** The app picked on step 5, or null while the picker has not been used. Null is a skippable state. */
    val pickedGame: AppOption? = null,
    val isPickerOpen: Boolean = false,
    val isLoadingApps: Boolean = false,
    val apps: List<AppOption> = emptyList(),
    /** The preset selected inside step 5. Null until the user picks one; a profile saves fine without one. */
    val preset: SetupPreset? = null,
    /** Each preset's lines, built by the engine from what this device supports. Empty before capabilities land. */
    val presetViews: List<PresetChoiceView> = emptyList(),
    /** Step 6's three switches. Defaults off, per §A2 step 6 — nothing is opted into on the user's behalf. */
    val smart: SmartFeatureStates = SmartFeatureStates(),
    val isSaving: Boolean = false,
    /** Set once the wizard has written its completion flag, so the screen knows to leave for Home. */
    val isFinished: Boolean = false,
    val message: String? = null,
) {

    /** The steps this run shows, from the engine. Recomputed from [choices] rather than stored. */
    val steps: List<WizardStep> get() = visibleSteps(choices)

    /** Where the user is, as "Step 3 of 6" plus a fraction for the bar. */
    val progress: WizardProgress get() = progressOf(step, steps)

    /** True when the current step is the last one, which is what turns Next into Finish. */
    val isLastStep: Boolean get() = steps.lastOrNull() == step

    /** True when there is somewhere behind the current step. Drives whether Back is drawn at all. */
    val canGoBack: Boolean get() = steps.indexOf(step) > 0

    /** The permission rows Android currently reports as not granted — what the Done step has to report. */
    val outstandingPermissions: List<PermissionRow> get() = permissionRows.filterNot { it.isGranted }

    /**
     * The preset the user picked, with its lines, or null.
     *
     * Looked up rather than stored as a second copy, so [preset] stays the single answer to "what did they
     * choose" and there is no way for the selection and the summary beside it to disagree.
     */
    val selectedPresetView: PresetChoiceView? get() = presetViews.firstOrNull { it.preset == preset }

    /**
     * True when Next should move the wizard on.
     *
     * Delegated to [canAdvance] rather than written here, because it is the one piece of button state that
     * is worth a test: the rule is "no step blocks", and the value of writing it down is that a future step
     * which *does* want to block has one function to change instead of a condition inlined in a button.
     */
    val canAdvanceNow: Boolean get() = canAdvance(step, this)
}

/**
 * Where the wizard is, for the indicator and for the screen reader.
 *
 * [label] is part of the data rather than built in the composable because it is what a screen reader
 * announces for the progress bar — a `LinearProgressIndicator` with a bare fraction says nothing, and §A5's
 * "state never by colour alone" applies to a bar exactly as it does to a chip. [fraction] is the same
 * information for the eye.
 *
 * [index] is one-based, since it is only ever shown to a person.
 */
data class WizardProgress(
    val index: Int,
    val total: Int,
    val fraction: Float,
    val label: String,
)

/**
 * Where the wizard is in [visible], as a fraction and as a sentence.
 *
 * A step that is not in the list — which happens for exactly one frame after the user changes their feature
 * picks and a previously-visible step disappears — reports the first position rather than a negative index.
 * [com.gamecore.domain.setup.resumeAt] is what actually moves the user in that case; this only has to avoid
 * drawing a bar at −12%.
 *
 * The fraction is *completed* steps over total, not position over total: standing on step 1 of 6 is zero
 * progress, and standing on the last step is a full bar because everything before it is behind the user.
 * A bar that started at 1/6 on a screen where nothing has been done yet reads as progress that was made
 * for you.
 */
fun progressOf(current: WizardStep, visible: List<WizardStep>): WizardProgress {
    val total = visible.size.coerceAtLeast(1)
    val position = visible.indexOf(current).coerceAtLeast(0)
    val index = position + 1
    return WizardProgress(
        index = index,
        total = total,
        fraction = (position.toFloat() / (total - 1).coerceAtLeast(1)).coerceIn(0f, 1f),
        label = "Step $index of $total",
    )
}

/**
 * The heading at the top of a step.
 *
 * Here rather than in the composable so the seven titles sit in one list that can be read at once — the
 * wizard's whole argument is the order of these seven sentences, and that argument is invisible when each
 * one is buried in its own `@Composable`.
 */
fun stepTitle(step: WizardStep): String = when (step) {
    WizardStep.WELCOME -> "What GameCore does"
    WizardStep.FEATURES -> "What do you want it to do?"
    WizardStep.PERMISSIONS -> "What Android needs you to allow"
    WizardStep.SHIZUKU -> "Shizuku (optional)"
    WizardStep.FIRST_GAME -> "Your first game"
    WizardStep.SMART_FEATURES -> "Automatic adjustments"
    WizardStep.DONE -> "You are set up"
}

/**
 * The sentence under the heading: why this step exists, in the user's terms.
 *
 * Every one of these is a fact about the app rather than an instruction. "Pick what you want" tells the
 * user what to do with a screen they can already see; "only the things you pick will ask for a permission"
 * tells them what their choice costs, which is the thing they are actually deciding.
 */
fun stepLead(step: WizardStep): String = when (step) {
    WizardStep.WELCOME ->
        "A performance monitor, a set of per-game profiles, an on-screen overlay and an aim trainer. " +
            "Everything below is optional and can be changed later in Settings."
    WizardStep.FEATURES ->
        "Only what you tick here decides which permissions are asked for. Nothing is turned on for you."
    WizardStep.PERMISSIONS ->
        "One screen each. GameCore checks the real setting when you come back — it never assumes a " +
            "grant went through."
    WizardStep.SHIZUKU ->
        "A separate app that gives GameCore ADB-level access without a computer being attached. " +
            "GameCore works without it; some controls simply are not reachable."
    WizardStep.FIRST_GAME ->
        "Pick a game and GameCore will create a profile for it. A preset fills the profile in; every " +
            "value stays editable afterwards."
    WizardStep.SMART_FEATURES ->
        "Three adjustments GameCore can make on its own during a session. All off unless you turn them on."
    WizardStep.DONE ->
        "Here is what is ready and what was skipped. Anything skipped can be set up later from " +
            "Settings › Setup health."
}

/**
 * Whether Next may move on from [step].
 *
 * Always true today, and written as a function anyway. §A2 says every step is skippable, which means no
 * step may gate the flow — not even step 5, where it is tempting to require a game before continuing. It
 * is tempting and it is wrong: a user who opened the wizard to grant the overlay permission should not be
 * made to create a profile to get out of it.
 *
 * The [state] parameter is unused for the same reason the function exists: the day a step does need to
 * block, the condition goes here with the rest of the wizard's rules and its test goes next to the others,
 * rather than appearing as an `enabled =` expression on a button where nobody will find it.
 */
@Suppress("UNUSED_PARAMETER")
fun canAdvance(step: WizardStep, state: SetupWizardUiState): Boolean = true

/**
 * Whether the step offers a Skip as well as a Next.
 *
 * Every step but the last. On the last there is nothing left to skip — the button there is Finish, and a
 * Skip beside it would be a second button doing the same thing with a word that suggests something was
 * lost. §32's rule about buttons that do nothing, applied to a button that does the same thing twice.
 */
fun canSkip(step: WizardStep): Boolean = step != WizardStep.DONE

/** The word on the forward button: the last step finishes rather than advancing. */
fun forwardLabel(step: WizardStep): String = if (step == WizardStep.DONE) "Finish" else "Next"

// --------------------------------------------------------------------------------- the feature picker

/**
 * The eight things step 2 offers, with the words shown beside each.
 *
 * An enum rather than a list of `SwitchRow` calls in the composable, for the reason
 * [com.gamecore.core.permissions.GamePermission] is an enum: three places need the same wording — the
 * picker, the Done summary, and Setup health's explanation of why a permission is being asked for — and a
 * screen that spelled its own labels would be the one that drifts.
 *
 * [description] states the cost, not the benefit. "Detects which game is in front" is what the feature does;
 * "needs usage access" is what the user is agreeing to, and that is the half a checklist usually omits.
 * Where a pick has no permission cost at all — Aim Lab, the network check — the description says what it is
 * rather than inventing a caveat.
 */
enum class WizardFeature(val title: String, val description: String) {
    AUTO_PROFILES(
        title = "Automatic game profiles",
        description = "Applies a game's profile when it comes to the front. Needs usage access.",
    ),
    OVERLAY(
        title = "On-screen overlay",
        description = "The performance pill and the floating button, drawn over the game. Needs the " +
            "display-over-other-apps permission.",
    ),
    DND(
        title = "Do Not Disturb during games",
        description = "Silences notifications for the length of a session. Needs Do Not Disturb access.",
    ),
    REFRESH_RATE(
        title = "Refresh-rate control",
        description = "Pins the display's rate for a session. Needs Shizuku on most devices.",
    ),
    AIM_LAB(
        title = "Aim Lab",
        description = "The aim trainer and its history. Needs no permission.",
    ),
    THERMAL_DOWNSHIFT(
        title = "Thermal auto-downshift",
        description = "Steps the refresh rate down when the device gets hot. Needs Shizuku.",
    ),
    NETWORK_CHECK(
        title = "Network check",
        description = "Signal, link type and an optional latency probe. The probe is the one thing " +
            "GameCore sends off the device.",
    ),
    FULL_PERFORMANCE(
        title = "Full performance under battery saver",
        description = "Turns Android's battery saver off for a session, and back on afterwards. " +
            "Needs Shizuku.",
    ),
    ;

    /** Whether this feature is ticked in [choices]. The enum's half of the mapping to the engine's fields. */
    fun isPicked(choices: WizardFeatureChoices): Boolean = when (this) {
        AUTO_PROFILES -> choices.autoProfiles
        OVERLAY -> choices.overlay
        DND -> choices.dnd
        REFRESH_RATE -> choices.refreshRateControl
        AIM_LAB -> choices.aimLab
        THERMAL_DOWNSHIFT -> choices.thermalDownshift
        NETWORK_CHECK -> choices.networkCheck
        FULL_PERFORMANCE -> choices.fullPerformance
    }

    /**
     * [choices] with this feature set to [picked].
     *
     * The other half of the mapping, kept next to [isPicked] so the two cannot fall out of step. A `when`
     * over the enum rather than reflection or a map of setters: the compiler fails the build if a ninth
     * feature is added and one of the two branches is forgotten, which is exactly the error worth catching.
     */
    fun applyTo(choices: WizardFeatureChoices, picked: Boolean): WizardFeatureChoices = when (this) {
        AUTO_PROFILES -> choices.copy(autoProfiles = picked)
        OVERLAY -> choices.copy(overlay = picked)
        DND -> choices.copy(dnd = picked)
        REFRESH_RATE -> choices.copy(refreshRateControl = picked)
        AIM_LAB -> choices.copy(aimLab = picked)
        THERMAL_DOWNSHIFT -> choices.copy(thermalDownshift = picked)
        NETWORK_CHECK -> choices.copy(networkCheck = picked)
        FULL_PERFORMANCE -> choices.copy(fullPerformance = picked)
    }
}

// ------------------------------------------------------------------------------------ the permissions

/**
 * One permission step (§A2 step 3), as the screen draws it.
 *
 * [need] is the engine's own enum and [catalogue] is the app's existing explanation of it, looked up by
 * [catalogueEntryFor]. Nothing here re-words a permission: the "why" and the "what stops working" the spec
 * asks for are [GamePermission.why] and [GamePermission.whatBreaks], already written once and already shown
 * on the Permissions screen. A wizard that wrote its own second explanation would be the copy that goes
 * stale.
 *
 * [catalogue] is null for exactly one row, [PermissionNeed.SHIZUKU], which is not an Android permission and
 * has no Settings page — it is a separate app, and the Shizuku step handles it. The row still exists so the
 * permissions list is a complete account of what is outstanding.
 *
 * [isGranted] is a fact from [com.gamecore.core.permissions.PermissionChecker], re-read on every resume. It
 * is a `Boolean` and not a tri-state because every check in that class already collapses "threw on this
 * OEM build" to false: a permission GameCore cannot confirm is one it does not have.
 */
data class PermissionRow(
    val need: PermissionNeed,
    val catalogue: GamePermission?,
    val isGranted: Boolean,
    /** True when a system dialog can ask for it; false when only a Settings page will do. */
    val isRuntimeRequestable: Boolean = catalogue?.isRuntimeRequestable == true,
) {
    /** The heading for this row. Falls back to the Shizuku wording for the one row with no catalogue entry. */
    val title: String get() = catalogue?.title ?: "Shizuku"

    /** "Granted" / "Not granted" — the word §A5 requires beside the tint, never the tint alone. */
    val stateLabel: String get() = if (isGranted) "Granted" else "Not granted"
}

/**
 * The catalogue entry that explains a [PermissionNeed], or null for the one that is not a permission.
 *
 * The bridge between the engine's vocabulary and the app's. [com.gamecore.domain.setup.SetupWizardLogic] is
 * pure and deliberately knows nothing about [GamePermission]; the permissions centre has known about it
 * since before the wizard existed. This function is the whole of the translation, in one place, so a
 * permission added to either side fails to compile here rather than silently rendering an empty row.
 */
fun catalogueEntryFor(need: PermissionNeed): GamePermission? = when (need) {
    PermissionNeed.SYSTEM_ALERT_WINDOW -> GamePermission.OVERLAY
    PermissionNeed.PACKAGE_USAGE_STATS -> GamePermission.USAGE_ACCESS
    PermissionNeed.ACCESS_NOTIFICATION_POLICY -> GamePermission.NOTIFICATION_POLICY
    PermissionNeed.POST_NOTIFICATIONS -> GamePermission.POST_NOTIFICATIONS
    PermissionNeed.SHIZUKU -> null
}

/**
 * The "Toggle greyed out?" text (§A2 step 3), shown only on permission steps and only in an expander.
 *
 * In an expander because it is wrong for most users: the greyed-out toggle is a restricted-settings block
 * that Android applies to apps installed from outside a store, and a user who installed from Play will
 * never see it. Put inline, it would read as a warning that something is wrong with their device.
 *
 * The three steps are named the way Android's own screens name them and no further — GameCore cannot know
 * what an OEM has relabelled the overflow menu to, and inventing a label the user cannot find is worse than
 * describing the position.
 */
const val RESTRICTED_SETTINGS_HELP: String =
    "If the switch on the Settings page is greyed out, Android has restricted it because GameCore was " +
        "installed from outside an app store. Open Settings, then Apps, then GameCore, then the " +
        "three-dot menu in the corner, and choose \"Allow restricted settings\". The switch becomes " +
        "usable straight away. Some builds do not show that menu item at all, in which case the " +
        "permission cannot be granted on this device."

// ---------------------------------------------------------------------------------------- Shizuku

/**
 * The Shizuku step's facts (§A2 step 4), all of them read from the existing detection.
 *
 * Nothing here is new logic. [state] is [com.gamecore.core.shizuku.ShizukuManager]'s own answer and carries
 * its own label, explanation and action word; [usableControls] and [totalControls] are
 * [DeviceCapabilities.usableControlCount] and [DeviceCapabilities.controlCount], the same pair the Shizuku
 * screen shows. The wizard's job on that step is to show them earlier, not to compute them differently.
 *
 * [isInstalled] is separate from [state] because it decides which button is drawn — Open versus nothing —
 * and [ShizukuState.NOT_INSTALLED] is not quite the same question: a manager app can be present while the
 * state is still unknown.
 */
data class ShizukuSummary(
    val state: ShizukuState = ShizukuState.RUNNING_PERMISSION_UNKNOWN,
    val isInstalled: Boolean = false,
    val usableControls: Int = 0,
    val totalControls: Int = DeviceCapabilities.UNKNOWN.controlCount,
) {
    val isConnected: Boolean get() = state.isUsable

    /** "3 of 8 controls available on this device" — the fraction §A2 step 4 asks for, not a yes. */
    val controlSummary: String get() = "$usableControls of $totalControls controls available on this device"
}

/**
 * How to start Shizuku, in two sentences that name no button in another app's UI.
 *
 * §A2 step 4 is explicit that the instructions must be generic. Shizuku's own screens have been relabelled
 * more than once and GameCore has no way to read them, so naming a button here would be a guess that reads
 * as a fact. What is stated instead is the two routes that exist and the one consequence that surprises
 * people, which is that the service does not survive a reboot.
 */
const val SHIZUKU_START_HELP: String =
    "Shizuku's service is started from inside the Shizuku app, either through Android's wireless " +
        "debugging or, on a rooted device, directly. It has to be started again after every reboot — " +
        "GameCore will show it as not running until it is."

// ------------------------------------------------------------------------------- presets and profile

/**
 * One preset chip and the exact list of what it would set (§A2 step 5).
 *
 * [lines] comes from [presetSummary] and is not filtered here. That is the point of the type: a preset's
 * unavailable fields arrive as [com.gamecore.domain.setup.PresetLine.NotAvailable] carrying the reason, and
 * the screen draws them as "Not available here: …" rather than dropping them. A summary that silently
 * omitted what this device cannot do would leave two users with different devices reading the same three
 * lines and getting different results.
 */
data class PresetChoiceView(
    val preset: SetupPreset,
    val lines: List<com.gamecore.domain.setup.PresetLine>,
) {
    val label: String get() = preset.label
}

/**
 * Step 6's three switches, default off (§A2 step 6).
 *
 * Separate from [WizardFeatureChoices] even though two of the three have a feature-picker counterpart, and
 * the distinction is the one the whole wizard turns on: a tick on step 2 says "I want this feature to
 * exist", a switch here says "turn it on for my first profile now". Someone can want thermal downshift
 * available and not want it applied to the one game they just added, and collapsing the two would make that
 * unsayable.
 *
 * [thermalBlockedReason] is non-null when the switch is drawn disabled — §A2 step 6 asks for the *reason*
 * next to a disabled control rather than a greyed switch with nothing beside it.
 */
data class SmartFeatureStates(
    val thermalDownshift: Boolean = false,
    val networkCheck: Boolean = false,
    val fullPerformance: Boolean = false,
    val thermalBlockedReason: String? = null,
    val fullPerformanceBlockedReason: String? = null,
)

// ------------------------------------------------------------------------------------ the Done step

/**
 * What the last step reports: what works now, and what was left (§A2 step 7).
 *
 * Two lists of sentences rather than a paragraph, because the user is checking a list against their
 * intentions. [skipped] is never presented as a failure — a skipped optional is a choice, and §A3's rule
 * that skipped items never nag starts here, on the screen where the user first sees them named.
 */
data class DoneSummary(
    val ready: List<String>,
    val skipped: List<String>,
)

/**
 * Builds the Done step's two lists from what the device actually reports.
 *
 * Pure, and tested: it is the one place where a grant, a Shizuku connection and a saved profile are turned
 * into the sentence the user reads last, and getting it wrong means the wizard's closing claim disagrees
 * with the Setup health screen they open ten seconds later.
 *
 * Only *picked* features are mentioned at all. A user who never ticked the overlay is not told the overlay
 * permission was skipped — they were never offered it, and listing it would read as a missed step rather
 * than a choice they made.
 */
fun doneSummary(
    choices: WizardFeatureChoices,
    permissionRows: List<PermissionRow>,
    shizuku: ShizukuSummary,
    profileName: String?,
): DoneSummary {
    val ready = ArrayList<String>()
    val skipped = ArrayList<String>()

    permissionRows.forEach { row ->
        val sentence = "${row.title} — ${row.stateLabel.lowercase()}"
        if (row.isGranted) ready.add(sentence) else skipped.add(sentence)
    }

    if (WizardFeature.REFRESH_RATE.isPicked(choices) ||
        WizardFeature.THERMAL_DOWNSHIFT.isPicked(choices) ||
        WizardFeature.FULL_PERFORMANCE.isPicked(choices)
    ) {
        if (shizuku.isConnected) {
            ready.add("Shizuku — connected, ${shizuku.controlSummary.lowercase()}")
        } else {
            skipped.add("Shizuku — ${shizuku.state.label.lowercase()}")
        }
    }

    if (profileName != null) {
        ready.add("Profile for $profileName")
    } else {
        skipped.add("No game profile yet — you can add one from the Games tab")
    }

    if (WizardFeature.AIM_LAB.isPicked(choices)) ready.add("Aim Lab — on")
    if (WizardFeature.NETWORK_CHECK.isPicked(choices)) ready.add("Network check — on")

    return DoneSummary(ready = ready, skipped = skipped)
}

// ------------------------------------------------------------------------------------ the welcome

/**
 * The privacy note on step 1 (§A2 step 1), built from the 3.4 audit rather than from a template.
 *
 * Every clause here is checkable against the code, which is the only reason it is allowed to be this
 * confident. GameCore holds two `INTERNET`-using surfaces in the audit's permission table, and after the
 * ad SDK was removed in 3.4 exactly one remains: the latency probe, which opens a TCP connection to a host
 * the user chooses (`AppSettings.latencyHost`, default `1.1.1.1`) and sends no payload. There is no
 * account, no server of ours, no analytics and no crash reporter, which is the same claim the README makes
 * and is why the two are worded to match.
 *
 * What it deliberately does *not* say is "nothing leaves the device". That sentence is false while the
 * probe exists, and §A2 step 1 forbids claiming it without proof. The probe is named, its default host is
 * named, and the setting that switches it off is named, so the note is a statement the user can verify
 * rather than a reassurance they have to accept.
 */
const val PRIVACY_NOTE_STAYS_HERE: String =
    "Everything GameCore records stays on this device: your profiles, your session history, your Aim " +
        "Lab results and your settings live in an encrypted store in the app's own storage. There is no " +
        "account, no server of ours, and no analytics or crash reporting of any kind."

/** The other half of the note: the one thing that does connect out, named exactly. */
const val PRIVACY_NOTE_CONNECTS_OUT: String =
    "One feature connects out. The optional latency check opens a connection to a host you choose — " +
        "1.1.1.1 unless you change it — purely to time the handshake, and sends nothing with it. It is " +
        "off the moment you turn the network check off, in Settings › Network."
