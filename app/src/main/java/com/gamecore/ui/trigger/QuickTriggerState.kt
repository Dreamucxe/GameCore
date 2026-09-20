package com.gamecore.ui.trigger

import com.gamecore.core.model.QuickTriggerMethod
import com.gamecore.core.model.QuickTriggerSettings
import com.gamecore.core.model.TriggerAvailability

/**
 * The Quick Trigger screen's state: the stored configuration, and what the device will actually allow.
 *
 * The two halves are kept apart on purpose. [settings] is what the user chose and it is stored; [availability]
 * is read from the live device — the sensor list, the overlay permission, the accessibility setting — every
 * time the screen resumes, so a permission granted in the system settings and returned from is reflected
 * without the user having to guess why a method is still greyed out.
 *
 * A method Android cannot run on this device at all is [TriggerAvailability.Status.UNSUPPORTED], and the
 * update's rule is that such a method is not offered: [offeredMethods] filters it out of the chooser rather
 * than showing a control that could never fire.
 */
data class QuickTriggerUiState(
    val isLoaded: Boolean = false,
    val settings: QuickTriggerSettings = QuickTriggerSettings(),
    val availability: List<TriggerAvailability> = emptyList(),
    val accessibilityEnabled: Boolean = false,
    val canRequestTile: Boolean = false,
    /** The double-tap window under the thumb during a drag, in milliseconds. Null when not dragging. */
    val windowDraft: Int? = null,
    /** The shake sensitivity under the thumb during a drag, 0–100. Null when not dragging. */
    val shakeDraft: Int? = null,
    val message: String? = null,
) {
    /** Every method the current device can actually run, in declaration order. */
    val offeredMethods: List<QuickTriggerMethod>
        get() = availability
            .filter { it.status != TriggerAvailability.Status.UNSUPPORTED }
            .map { it.method }

    /** The availability of the currently-selected method, if it is one the device offers. */
    val selected: TriggerAvailability?
        get() = availability.firstOrNull { it.method == settings.method }

    val windowValue: Int get() = windowDraft ?: settings.windowMillis.toInt()

    val shakeValue: Int get() = shakeDraft ?: settings.shakeSensitivity
}
