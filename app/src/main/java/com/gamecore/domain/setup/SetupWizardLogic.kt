package com.gamecore.domain.setup

/**
 * The first-run setup wizard's decisions (spec §A1/§A2), as pure logic over the choices and signals the
 * caller hands in — no `android.*`, no navigation framework, no `SharedPreferences`. Every question the
 * wizard asks itself — should it appear at all, which steps to show, which permissions the picked
 * features need, where to resume after a rotation — is a pure function here, in the same shape as
 * [com.gamecore.core.overlay.HoldToConfirm] and [com.gamecore.domain.optimization.WriteLedger], so the
 * whole flow is testable without an Activity.
 */

/**
 * The wizard's coarse steps (spec §A2's 7 screens). [PERMISSIONS] is one enum value representing the
 * per-permission sub-flow the caller runs inside it — [requiredPermissionSteps] enumerates what that
 * sub-flow must cover, but the wizard's navigation stays at this step level.
 */
enum class WizardStep {
    WELCOME,
    FEATURES,
    PERMISSIONS,
    SHIZUKU,
    FIRST_GAME,
    SMART_FEATURES,
    DONE,
}

/**
 * What the user ticked on the Features step. All default false — nothing is opted into on the user's
 * behalf, which is what keeps [requiredPermissionSteps] and [visibleSteps] minimal for someone who skips
 * straight through.
 */
data class WizardFeatureChoices(
    val autoProfiles: Boolean = false,
    val overlay: Boolean = false,
    val dnd: Boolean = false,
    val refreshRateControl: Boolean = false,
    val aimLab: Boolean = false,
    val thermalDownshift: Boolean = false,
    val networkCheck: Boolean = false,
    val fullPerformance: Boolean = false,
)

/**
 * The stored facts the entry decision reads (spec §A1).
 *
 * @param hasCompletedVersion the app version at which the wizard was last completed, or null if never.
 * @param currentVersion the running app version.
 * @param dismissed whether the user dismissed the wizard / its home card for good.
 * @param hasProfiles whether the user already has profiles — evidence this is not a fresh install.
 * @param hasSessions whether the user already has recorded sessions — same.
 */
data class SetupSignals(
    val hasCompletedVersion: Int?,
    val currentVersion: Int,
    val dismissed: Boolean,
    val hasProfiles: Boolean,
    val hasSessions: Boolean,
)

/** How the wizard should present itself this launch. */
sealed interface WizardEntry {
    /** Fresh install: run the whole wizard. */
    data object FullWizard : WizardEntry
    /** Upgrade or partial state: offer it as a dismissible Home card, don't take over. */
    data object HomeCard : WizardEntry
    /** Nothing to show: completed at this version, or dismissed. */
    data object Nothing : WizardEntry
}

/**
 * Decides how the wizard appears (spec §A1).
 *
 * Dismissal wins outright — a user who said no is not asked again. Completion *at the current version*
 * is nothing to show; completion at an older version is an upgrade, offered as a Home card. A never-run
 * wizard with no profiles and no sessions is a fresh install and gets the full flow; a never-run wizard
 * that nonetheless has existing data is someone who upgraded from before the wizard existed, so it too
 * is a non-intrusive Home card rather than a takeover.
 */
fun decideEntry(signals: SetupSignals): WizardEntry {
    if (signals.dismissed) return WizardEntry.Nothing
    val completed = signals.hasCompletedVersion
    if (completed != null && completed >= signals.currentVersion) return WizardEntry.Nothing
    val fresh = completed == null && !signals.hasProfiles && !signals.hasSessions
    return if (fresh) WizardEntry.FullWizard else WizardEntry.HomeCard
}

/** A permission (or capability) a picked feature needs before it can work. */
enum class PermissionNeed {
    SYSTEM_ALERT_WINDOW,
    PACKAGE_USAGE_STATS,
    ACCESS_NOTIFICATION_POLICY,
    POST_NOTIFICATIONS,
    /** Not an OS permission — the feature needs Shizuku to reach a system setting. */
    SHIZUKU,
}

/**
 * The permissions the picked features actually need (spec §A1's per-permission sub-flow). Only ticked
 * features contribute, so someone who picks nothing needs nothing. The mapping:
 *  - overlay → [PermissionNeed.SYSTEM_ALERT_WINDOW]
 *  - autoProfiles → [PermissionNeed.PACKAGE_USAGE_STATS]
 *  - dnd → [PermissionNeed.ACCESS_NOTIFICATION_POLICY]
 *  - refreshRateControl / thermalDownshift / fullPerformance → [PermissionNeed.SHIZUKU]
 *
 * @param wantsNotifications whether the wizard is offering notifications this run (the caller decides,
 *   e.g. only when it also wants a foreground-service notice).
 * @param isApi33OrHigher whether [PermissionNeed.POST_NOTIFICATIONS] is a runtime permission on this OS
 *   (it only exists on API 33+); below that, notifications need no grant and none is added.
 *
 * The result is de-duplicated and in a stable order, so refresh-rate and thermal both being picked adds
 * Shizuku once, not twice.
 */
fun requiredPermissionSteps(
    choices: WizardFeatureChoices,
    wantsNotifications: Boolean = false,
    isApi33OrHigher: Boolean = false,
): List<PermissionNeed> {
    val needs = LinkedHashSet<PermissionNeed>()
    if (choices.overlay) needs.add(PermissionNeed.SYSTEM_ALERT_WINDOW)
    if (choices.autoProfiles) needs.add(PermissionNeed.PACKAGE_USAGE_STATS)
    if (choices.dnd) needs.add(PermissionNeed.ACCESS_NOTIFICATION_POLICY)
    if (wantsNotifications && isApi33OrHigher) needs.add(PermissionNeed.POST_NOTIFICATIONS)
    if (choices.refreshRateControl || choices.thermalDownshift || choices.fullPerformance) {
        needs.add(PermissionNeed.SHIZUKU)
    }
    return needs.toList()
}

/** Whether any picked feature needs Shizuku — the condition for showing the [WizardStep.SHIZUKU] step. */
private fun needsShizuku(choices: WizardFeatureChoices): Boolean =
    choices.refreshRateControl || choices.thermalDownshift || choices.fullPerformance

/** Whether any picked feature is a "smart" one — the condition for the [WizardStep.SMART_FEATURES] step. */
private fun needsSmartFeatures(choices: WizardFeatureChoices): Boolean =
    choices.thermalDownshift || choices.networkCheck || choices.fullPerformance

/**
 * The steps this run of the wizard shows, in order (spec §A2). WELCOME, FEATURES, FIRST_GAME and DONE are
 * always present. SHIZUKU appears only when a Shizuku-needing feature was picked; SMART_FEATURES only when
 * a thermal / network / full-performance feature was picked. PERMISSIONS is always present — even a
 * feature-light setup wants to confirm there is nothing to grant.
 */
fun visibleSteps(choices: WizardFeatureChoices): List<WizardStep> {
    val steps = ArrayList<WizardStep>(WizardStep.entries.size)
    steps.add(WizardStep.WELCOME)
    steps.add(WizardStep.FEATURES)
    steps.add(WizardStep.PERMISSIONS)
    if (needsShizuku(choices)) steps.add(WizardStep.SHIZUKU)
    steps.add(WizardStep.FIRST_GAME)
    if (needsSmartFeatures(choices)) steps.add(WizardStep.SMART_FEATURES)
    steps.add(WizardStep.DONE)
    return steps
}

/**
 * The step after [current] in [visibleSteps], or [current] itself when it is the last (or absent from the
 * list). Pure navigation over the visible order, so a hidden step can never be reached.
 */
fun nextStep(current: WizardStep, visibleSteps: List<WizardStep>): WizardStep {
    val i = visibleSteps.indexOf(current)
    if (i < 0 || i == visibleSteps.lastIndex) return current
    return visibleSteps[i + 1]
}

/**
 * The step before [current] in [visibleSteps], or [current] itself when it is the first (or absent).
 */
fun previousStep(current: WizardStep, visibleSteps: List<WizardStep>): WizardStep {
    val i = visibleSteps.indexOf(current)
    if (i <= 0) return current
    return visibleSteps[i - 1]
}

/**
 * Where to resume after a process death or rotation. A saved step that is still in [visibleSteps] is
 * returned as-is; anything else — null, or a step that is no longer shown because the choices changed —
 * falls back to the first visible step. The same call handles rotation: re-invoke it with the saved step
 * and it returns the same place, since [visibleSteps] is derived from the same choices.
 */
fun resumeAt(savedStep: WizardStep?, visibleSteps: List<WizardStep>): WizardStep {
    val first = visibleSteps.firstOrNull() ?: WizardStep.WELCOME
    if (savedStep == null) return first
    return if (savedStep in visibleSteps) savedStep else first
}

/** The three presets offered on the wizard's preset step (spec §A2 step 5). */
enum class SetupPreset(val label: String) {
    BALANCED("Balanced"),
    MAX_PERFORMANCE("Max performance"),
    BATTERY_SAVER("Battery saver"),
}

/**
 * Which optional settings this device / build can actually apply, so the preset summary can say what it
 * will do versus what is not available here. All default true; the caller flips off what the device or
 * permissions don't support.
 */
data class PresetSupport(
    val refreshRate: Boolean = true,
    val thermalDownshift: Boolean = true,
    val dnd: Boolean = true,
    val animationScale: Boolean = true,
)

/** One line of a preset summary: either a setting it will apply, or a reason it cannot here. */
sealed interface PresetLine {
    /** A setting the preset will apply, with the human-readable value it will set. */
    data class WillSet(val setting: String, val value: String) : PresetLine
    /** A setting the preset would touch but cannot on this device, with the reason. */
    data class NotAvailable(val setting: String, val reason: String) : PresetLine
}

/** One field a preset touches: the setting it maps to and the value this preset would give it. */
private data class PresetField(val setting: String, val value: String, val supported: Boolean, val reason: String)

/**
 * The lines a preset's summary shows (spec §A2 step 5). Each field the preset touches becomes either a
 * "will set" line when this device supports it, or a "not available here" line when it does not — nothing
 * is silently dropped, so the user sees both what will change and what won't. Kept to a handful of
 * documented settings per preset.
 */
fun presetSummary(preset: SetupPreset, supported: PresetSupport): List<PresetLine> {
    val fields = when (preset) {
        SetupPreset.BALANCED -> listOf(
            PresetField("Refresh rate", "Adaptive", supported.refreshRate, "Refresh-rate control needs Shizuku."),
            PresetField("Do Not Disturb", "On while gaming", supported.dnd, "Do Not Disturb access not granted."),
        )
        SetupPreset.MAX_PERFORMANCE -> listOf(
            PresetField("Refresh rate", "Highest", supported.refreshRate, "Refresh-rate control needs Shizuku."),
            PresetField("Thermal downshift", "Off", supported.thermalDownshift, "Thermal control needs Shizuku."),
            PresetField("Animation scale", "0.5x", supported.animationScale, "Animation-scale control not available."),
        )
        SetupPreset.BATTERY_SAVER -> listOf(
            PresetField("Refresh rate", "60 Hz", supported.refreshRate, "Refresh-rate control needs Shizuku."),
            PresetField("Thermal downshift", "Aggressive", supported.thermalDownshift, "Thermal control needs Shizuku."),
        )
    }
    return fields.map {
        if (it.supported) PresetLine.WillSet(it.setting, it.value)
        else PresetLine.NotAvailable(it.setting, it.reason)
    }
}
