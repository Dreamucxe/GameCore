package com.gamecore.core.model

import com.gamecore.core.common.Formatters

/**
 * A game profile's effect as a list of discrete chips, rather than the one line [profileSummary]
 * produces.
 *
 * The redesigned Games card (§5) shows what a profile does as a wrapping row of small chips instead of a
 * single truncated sentence: "120 Hz", "Do not disturb", "Battery saver". A chip is easier to scan than a
 * dot-separated line, and it lets the card colour the two effects that touch *other* apps — closing
 * background apps, pinning CPU cores — differently from the ones that only change a device setting.
 *
 * Pure Kotlin and no `android.*`, for the same reason as [profileSummary] and [ThermalClassifier]: the
 * list a card draws is a claim about what a profile will do, and a claim belongs in a unit test rather
 * than in a composable that only runs on a device. The "null means leave it alone" rule holds throughout —
 * a field the profile does not set produces no chip, never a "default" chip, so the card can never imply a
 * profile writes something it leaves alone.
 *
 * **These chips answer a different question from [GameProfile.changesNothing], and the two deliberately
 * disagree on a fresh profile.** `changesNothing` asks "would applying this *write* anything?", and by its
 * own reasoning an overlay the profile raises is not a write: it leaves no device state to restore. These
 * chips ask "what will the user actually see happen?", and an overlay appearing is emphatically part of
 * that. So [GameProfile.Companion.forGame] — which starts a new profile with the floating button on and
 * nothing written — is `changesNothing == true` and yields exactly one chip, "Floating button". That is
 * not a contradiction; it is the card telling the truth about a profile that raises a button and writes
 * no setting. A Games card that wants "this writes nothing yet" should read `changesNothing` directly
 * rather than inferring it from an empty chip list.
 */

/**
 * What kind of thing a [ProfileChip] describes, so the card can tone it without re-deciding.
 *
 * The three that touch state beyond a plain device setting are called out on purpose:
 *  - [OVERLAY] is drawn-on-top UI — a pill, a button, a crosshair — that changes nothing about the device.
 *  - [PROCESS] is the pair whose effect is on *processes*, not settings: closing background apps and
 *    pinning the game to CPU cores. §5 wants these visually distinct because they are the profile's most
 *    consequential and least reversible effects.
 *  - [DEVICE] is everything else: a refresh rate, a brightness, a volume — a device setting the profile
 *    writes and restores on exit.
 *
 * The UI maps these to tones; this enum does not name colours, so the mapping stays in the theme layer
 * with the rest of the semantic-colour decisions.
 */
enum class ProfileChipKind {
    /** A device setting written and restored: refresh rate, brightness, volume, orientation, timeout, DND. */
    DEVICE,

    /** Overlay UI drawn while the game runs: stats pill, floating button, crosshair. Changes no device state. */
    OVERLAY,

    /** An effect on processes rather than settings: closing background apps, pinning CPU cores. */
    PROCESS,

    /** Session recording — a GameCore-side action, neither a device write nor an overlay. */
    TRACKING,
}

/**
 * One chip on a Games card: the text the user reads and [kind] the card tones it by.
 *
 * A value type rather than a formatted string, so a test can assert "this profile produces a PROCESS chip
 * reading 'Battery saver'" without parsing a sentence, and the card decides the colour from [kind] rather
 * than from the words.
 */
data class ProfileChip(
    val text: String,
    val kind: ProfileChipKind,
)

/**
 * Build the chips for a profile, in a fixed reading order: what the game will look and sound like first,
 * then the overlay it raises, then the process-level effects, then whether it records.
 *
 * Order is deliberate and stable so two cards line up and a profile edited between two states does not
 * reshuffle its chips. Only settings actually written appear — every `?.let` and every boolean guard is
 * the "null / false means leave it alone" rule, so [changesNothing]'s profile yields an empty list and the
 * card shows its own "changes nothing yet" note rather than a misleading chip.
 *
 * @return the chips in display order; empty when the profile writes nothing.
 */
fun profileChips(profile: GameProfile): List<ProfileChip> = buildList {
    // Display and audio: what the game looks and sounds like. Device settings, restored on exit.
    profile.targetRefreshRate?.let { add(ProfileChip(Formatters.hertz(it), ProfileChipKind.DEVICE)) }
    profile.brightnessPercent?.let { add(ProfileChip("Brightness $it%", ProfileChipKind.DEVICE)) }
    profile.mediaVolumePercent?.let { add(ProfileChip("Volume $it%", ProfileChipKind.DEVICE)) }
    profile.rotationLock?.let {
        // CURRENT means "whatever it is when the game starts" — i.e. leave it alone — so it is not a chip.
        if (it != ScreenOrientationLock.CURRENT) add(ProfileChip(it.label, ProfileChipKind.DEVICE))
    }
    profile.screenTimeoutMillis?.let {
        add(ProfileChip("Screen off ${Formatters.durationCoarse(it)}", ProfileChipKind.DEVICE))
    }
    if (profile.enableDoNotDisturb) add(ProfileChip("Do not disturb", ProfileChipKind.DEVICE))
    // The panel's own size is not known here (that needs the physical [DisplaySize] the shell reports), so
    // the preset name — "16:9" — cannot be resolved and the chip states the plain fact instead: this profile
    // stretches the display. The editor, which does have the panel, is where the ratio label belongs.
    if (profile.displaySize != null) add(ProfileChip("Display size", ProfileChipKind.DEVICE))
    if (profile.performanceMode != PerformanceMode.BALANCED) {
        add(ProfileChip(profile.performanceMode.label, ProfileChipKind.DEVICE))
    }
    if (profile.colorPresetId != null) add(ProfileChip("Colour preset", ProfileChipKind.DEVICE))

    // Overlay: drawn on top, changes no device state.
    if (profile.showPerformancePill) add(ProfileChip("Stats pill", ProfileChipKind.OVERLAY))
    if (profile.showFloatingButton) add(ProfileChip("Floating button", ProfileChipKind.OVERLAY))
    if (profile.showCrosshair) add(ProfileChip("Crosshair", ProfileChipKind.OVERLAY))

    // Process-level: the profile's most consequential effects, on other apps or the game's own process.
    if (profile.freeRamOnLaunch) add(ProfileChip("Frees RAM", ProfileChipKind.PROCESS))
    profile.cpuAffinity?.let { add(ProfileChip(it.label, ProfileChipKind.PROCESS)) }
    if (profile.useShizukuOptimizations) add(ProfileChip("Shizuku", ProfileChipKind.PROCESS))

    // Recording: a GameCore-side action.
    if (profile.trackSession) add(ProfileChip("Records a session", ProfileChipKind.TRACKING))
}
