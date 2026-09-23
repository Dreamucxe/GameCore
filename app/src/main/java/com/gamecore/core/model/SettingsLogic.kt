package com.gamecore.core.model

/**
 * The decisions the redesigned Settings screen (§7) makes before anything is drawn.
 *
 * Pure Kotlin, no `android.*` and no Compose, for the same reason as [profileClaim] and the thermal
 * classifier: everything here is a *claim the user reads* — which text-size preset they are on, what
 * version they are running — and a claim belongs in a unit test rather than in a composable that can only
 * be checked by looking at a phone.
 *
 * The one genuinely subtle thing in this file is the relationship between the Font scale slider and the
 * Text size presets. §7 requires that they "share ONE stored multiplier", and the temptation is to give
 * the presets their own stored field so each control owns its own state. That is exactly the bug: two
 * fields describing one visible property drift apart the first time the slider is moved, and then the
 * screen shows "Medium" over a slider reading 122%. There is one number — [AppSettings.uiScalePercent] —
 * and the preset is *derived* from it by [textSizePreset], never stored.
 */

// ------------------------------------------------------------------------------- text size presets

/**
 * The named stops on the font-scale slider.
 *
 * [percent] is the stored multiplier a preset selects, and it is null for [CUSTOM] — because "Custom" is
 * not a value the user can pick *into*, it is the name for "you are between the stops". Making it nullable
 * rather than giving it a sentinel number means the type itself refuses to answer "what percent is Custom?",
 * which is the question that would otherwise snap a user's deliberate 122% back to a stop the moment the
 * screen recomposed.
 *
 * The three real values sit inside [AppSettings.MIN_UI_SCALE]..[AppSettings.MAX_UI_SCALE] deliberately, and
 * a test pins that — a preset outside the slider's own bounds would be selectable and then immediately
 * clamped to something else, which reads as the app refusing the tap.
 */
enum class TextSizePreset(val label: String, val percent: Int?) {
    SMALL("Small", 90),
    MEDIUM("Medium", 100),
    LARGE("Large", 115),

    /** Not a choice the user makes, but the honest name for any value that is not one of the stops. */
    CUSTOM("Custom", null),
}

/**
 * Which preset a stored multiplier corresponds to, or [TextSizePreset.CUSTOM] when it is between stops.
 *
 * This is the "stay in sync" half of §7: the presets are a view of the slider's number rather than a
 * second setting, so dragging the slider to 90% lights up "Small" without anything being written twice.
 */
fun textSizePreset(uiScalePercent: Int): TextSizePreset =
    TextSizePreset.entries.firstOrNull { it.percent == uiScalePercent } ?: TextSizePreset.CUSTOM

/**
 * The multiplier to store when a preset is tapped.
 *
 * Tapping "Custom" is a no-op that returns [current] unchanged, because there is no value it names. The
 * alternative — treating it as a fourth stop — would mean the user taps "Custom" and the size jumps to
 * whatever number was chosen to represent it, which is the opposite of what the word promises.
 */
fun scaleForPreset(preset: TextSizePreset, current: Int): Int = preset.percent ?: current

/** The slider's own read-out. A percentage, because the setting is a multiplier and not a point size. */
fun uiScaleLabel(uiScalePercent: Int): String = "$uiScalePercent%"

// ------------------------------------------------------------------------------------ custom accent

/**
 * An accent colour as the `#RRGGBB` a user would write down or paste back in.
 *
 * The alpha byte is dropped rather than printed. It is forced opaque everywhere an accent is stored (see
 * [AppSettings.normalised]), so showing `#FF00E5FF` would put a byte on screen that the user cannot change
 * and did not choose, and would not round-trip through the hex field in the picker.
 */
fun accentHex(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

/**
 * What the "Custom colour" row says on its right-hand side.
 *
 * Deliberately distinguishes *chosen but switched off* from *never chosen*. A user who picked a colour,
 * turned the toggle off and came back is owed the sight of their colour still being there; telling them
 * "Not set" would suggest the pick was thrown away, and they would do it again.
 */
fun customAccentLabel(customAccentArgb: Int?, useCustomAccent: Boolean): String = when {
    customAccentArgb == null -> "Not chosen"
    useCustomAccent -> accentHex(customAccentArgb)
    else -> "${accentHex(customAccentArgb)} · not in use"
}

// ------------------------------------------------------------------------------------------- about

/**
 * The version line for the About card, or null when the package manager would not say.
 *
 * Null rather than a fallback string, so the caller has to reach for the honest "Unavailable" path with
 * the *real* reason attached (§0: no fake data). A hard-coded `BuildConfig` constant would always produce
 * an answer here, which is precisely why §7 asks for the real package info instead — a constant compiled
 * into the APK cannot tell the user which build is actually installed on the device in their hand.
 *
 * @param versionName the package's own `versionName`.
 * @param versionCode the package's `longVersionCode`, shown in brackets as the build number.
 */
fun appVersionLabel(versionName: String?, versionCode: Long?): String? = when {
    versionName.isNullOrBlank() && versionCode == null -> null
    versionName.isNullOrBlank() -> "Build $versionCode"
    versionCode == null -> versionName
    else -> "$versionName ($versionCode)"
}
