package com.gamecore.core.plugin.content

import com.gamecore.core.plugin.BrokerAction

/**
 * The canonical value an automation applies for each [BrokerAction].
 *
 * An automation rule names an action by id; it carries no value of its own, because letting a
 * rule choose the number would hand a plugin back the very thing the broker's registry takes
 * away — a free parameter to the one device-affecting surface it can reach. Instead the host
 * supplies the value from here, and this object is where that single, host-owned choice lives
 * for every action in the registry.
 *
 * Two properties are load-bearing:
 *
 *  * **Exhaustive.** [valueFor] is a `when` over [BrokerAction] with no `else`, so the compiler
 *    refuses to build if a new action is added to the registry without a value chosen for it
 *    here. There is no silent default and no action an automation can reach without a reviewed
 *    value, which is the point: the registry and this map change together or not at all.
 *  * **Always valid.** Every value returned passes its action's own [com.gamecore.core.shizuku.ValueForm]
 *    (and sits inside any tighter clamp bound), so the broker's `plan` admits it as-is rather
 *    than clamping or rejecting it. The values are chosen for that, not merely plausible:
 *    battery-saver is the boolean-int `"1"`, not `"true"`; animation scales are the integer
 *    `"0"` that disables them within the 0..10 form; the refresh bounds are a real in-range Hz.
 *    `AutomationActionDefaultsTest` proves this by running each one through the real broker.
 *
 * The chosen values are performance-oriented, because that is what an automation on this
 * platform is for — pin a high refresh rate, cut animation overhead, drop into battery saver
 * when the charge runs low, lift brightness for play (which auto-reverts when the game exits).
 * Pure host-side Kotlin: one enum in, one string out, no device contact.
 */
object AutomationActionDefaults {

    fun valueFor(action: BrokerAction): String = when (action) {
        // Pin the panel high: writing both bounds to the same rate is what actually holds a
        // refresh rate on AOSP-derived builds (see WritableSetting.MIN_REFRESH_RATE).
        BrokerAction.SET_PEAK_REFRESH_RATE -> HIGH_REFRESH_HZ
        BrokerAction.SET_MIN_REFRESH_RATE -> HIGH_REFRESH_HZ

        // Disable the three animation scales: a real, reversible frame saving.
        BrokerAction.SET_WINDOW_ANIMATION_SCALE -> ANIMATIONS_OFF
        BrokerAction.SET_TRANSITION_ANIMATION_SCALE -> ANIMATIONS_OFF
        BrokerAction.SET_ANIMATOR_DURATION_SCALE -> ANIMATIONS_OFF

        // Turn Android's own battery saver on. BOOLEAN_INT accepts only "0"/"1".
        BrokerAction.SET_LOW_POWER -> BOOLEAN_ON

        // Lift brightness for play. This action auto-reverts, so the prior value is restored when
        // the game exits; the value only needs to be a valid 0..255 level.
        BrokerAction.SET_SCREEN_BRIGHTNESS -> GAMING_BRIGHTNESS
    }

    /** A high, universally-offered rate, inside the REFRESH_RATE form's 20..480 window. */
    private const val HIGH_REFRESH_HZ = "120"

    /** The animation-scale value that switches animations off, inside the 0..10 form. */
    private const val ANIMATIONS_OFF = "0"

    /** Battery saver on, in the only shape BOOLEAN_INT admits. */
    private const val BOOLEAN_ON = "1"

    /** A bright-but-not-maximal backlight, inside the BRIGHTNESS form's 0..255 window. */
    private const val GAMING_BRIGHTNESS = "200"
}
