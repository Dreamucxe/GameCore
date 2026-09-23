package com.gamecore.domain.setup

import com.gamecore.data.preferences.SecurePreferenceStore

/**
 * The wizard's own types over the store's primitives (spec §A).
 *
 * [SecurePreferenceStore] keeps the half-finished wizard as a step *name* and a choice *bitmask*, and
 * nothing more, because `data` does not import `domain` anywhere in this app — the dependency runs
 * `domain → data` throughout, and a store that named [WizardStep] would be the one place it did not. The
 * mapping belongs on this side of that line, so it lives here as extensions and the wizard's ViewModel
 * reads `preferences.setupStep` and `preferences.setupChoices` as if the store were typed.
 *
 * Neither of these is observable, and that is deliberate: they are scratch for one screen, written on
 * every Next tap and cleared the moment the wizard finishes or is dismissed. What the wizard *decided*
 * is in [com.gamecore.core.model.AppSettings] and is observable like every other setting.
 */

/**
 * The step a half-finished wizard was last on, or null when there is nothing to resume.
 *
 * A name that no longer matches any step reads back as null rather than throwing. That is not a defensive
 * flourish: an older build opening a file a newer one wrote is an ordinary thing here, and [resumeAt]
 * already treats null as "start at the first visible step", which is exactly the right answer.
 */
var SecurePreferenceStore.setupStep: WizardStep?
    get() {
        val name = setupStepName ?: return null
        return WizardStep.entries.firstOrNull { it.name == name }
    }
    set(value) {
        setupStepName = value?.name
    }

/**
 * What the user has ticked on the Features step so far.
 *
 * An absent or zero mask is every choice false, which is [WizardFeatureChoices]'s own default and the
 * right answer for a user who has ticked nothing. A bit set by a newer build is not read by an older one,
 * and — because the setter writes the whole mask — round-tripping through an older build drops it. That is
 * acceptable for scratch state that is cleared on completion anyway, and is the reason this is not where
 * anything durable is kept.
 */
var SecurePreferenceStore.setupChoices: WizardFeatureChoices
    get() {
        val bits = setupChoiceBits
        return WizardFeatureChoices(
            autoProfiles = bits and BIT_AUTO_PROFILES != 0,
            overlay = bits and BIT_OVERLAY != 0,
            dnd = bits and BIT_DND != 0,
            refreshRateControl = bits and BIT_REFRESH_RATE != 0,
            aimLab = bits and BIT_AIM_LAB != 0,
            thermalDownshift = bits and BIT_THERMAL != 0,
            networkCheck = bits and BIT_NETWORK != 0,
            fullPerformance = bits and BIT_FULL_PERFORMANCE != 0,
        )
    }
    set(value) {
        var bits = 0
        if (value.autoProfiles) bits = bits or BIT_AUTO_PROFILES
        if (value.overlay) bits = bits or BIT_OVERLAY
        if (value.dnd) bits = bits or BIT_DND
        if (value.refreshRateControl) bits = bits or BIT_REFRESH_RATE
        if (value.aimLab) bits = bits or BIT_AIM_LAB
        if (value.thermalDownshift) bits = bits or BIT_THERMAL
        if (value.networkCheck) bits = bits or BIT_NETWORK
        if (value.fullPerformance) bits = bits or BIT_FULL_PERFORMANCE
        setupChoiceBits = bits
    }

/*
 * The bit positions, fixed once written. A stored mask outlives the build that wrote it, so these are
 * append-only: a new choice takes the next free bit and an abandoned one leaves its bit retired rather
 * than reusing it, which would silently turn an old user's answer into a different answer.
 */
internal const val BIT_AUTO_PROFILES = 1 shl 0
internal const val BIT_OVERLAY = 1 shl 1
internal const val BIT_DND = 1 shl 2
internal const val BIT_REFRESH_RATE = 1 shl 3
internal const val BIT_AIM_LAB = 1 shl 4
internal const val BIT_THERMAL = 1 shl 5
internal const val BIT_NETWORK = 1 shl 6
internal const val BIT_FULL_PERFORMANCE = 1 shl 7
