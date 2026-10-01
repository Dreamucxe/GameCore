package com.gamecore.ui.trigger

/**
 * Whether the volume-button point trigger can actually fire right now, and — when it cannot — the one
 * honest sentence that says what is missing.
 *
 * This is the rare feature in GameCore that needs two grants at once. Reading a volume key while a *game*
 * holds input focus is only possible through GameCore's accessibility service — an ordinary app never sees
 * keys delivered to another window (see [com.gamecore.core.model.QuickTriggerMethod]) — and injecting the
 * tap that the key press stands in for needs Shizuku's ADB-level authority (see
 * [com.gamecore.core.model.ShizukuState]). Miss either and the trigger is inert, so the UI must say so and
 * offer the fix rather than show a control that does nothing (§24/§32).
 *
 * Kept free of any Android type on purpose: the integrator fills the two booleans from live device state,
 * and the honest-sentence rule this whole feature turns on is then a pure function that a unit test pins.
 */
data class VolumeTriggerAvailability(
    val accessibilityEnabled: Boolean,
    val shizukuGranted: Boolean,
) {
    /** Both grants present: the trigger can read the key and inject the tap. The only usable state. */
    val isUsable: Boolean get() = accessibilityEnabled && shizukuGranted

    /** The reason it cannot fire, or null when it can. [describe] is the single source of truth. */
    val reason: String? get() = describe(this)
}

/**
 * The honest reason the trigger cannot fire, or null when it is fully available.
 *
 * Names both gaps when both are missing rather than the first one only, so a user does not grant one thing,
 * come back, and be told about a second they were never warned of. Accessibility is named first because it
 * is the grant a user is likelier to already understand from the Quick Trigger screen.
 */
fun describe(availability: VolumeTriggerAvailability): String? = when {
    availability.accessibilityEnabled && availability.shizukuGranted -> null
    !availability.accessibilityEnabled && !availability.shizukuGranted ->
        "Enable GameCore's accessibility service to read volume keys, and grant Shizuku to inject taps"
    !availability.accessibilityEnabled ->
        "Enable GameCore's accessibility service to read volume keys"
    else ->
        "Grant Shizuku to inject taps"
}
