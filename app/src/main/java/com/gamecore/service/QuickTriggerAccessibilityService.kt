package com.gamecore.service

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.gamecore.domain.trigger.QuickTriggerCoordinator
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The optional service that lets the volume-key shortcut work from inside another app.
 *
 * Android sends hardware key events to the focused window and to nothing else. That is not a permission an
 * app can ask for, it is how input dispatch works, so a normal app's volume-key shortcut works while its
 * own window has focus and stops working the moment the user opens a game. There is exactly one supported
 * mechanism that changes it — an accessibility service holding `FLAG_REQUEST_FILTER_KEY_EVENTS`, which the
 * user grants by hand in system settings — and this is it.
 *
 * Which is why it is optional and why nothing in the app requires it. The settings screen offers it as the
 * way to lift a limit it has already explained, and the volume methods are marked AVAILABLE without it
 * because they do work; they work in one place.
 *
 * ### What this service is scoped to
 *
 * It subscribes to **no accessibility events**: `res/xml/quick_trigger_accessibility.xml` declares no
 * `accessibilityEventTypes`, so the platform dispatches none, and `canRetrieveWindowContent` is false.
 * [onAccessibilityEvent] is a required override and is a no-op because there is nothing arriving for it to
 * handle. The service reads the key codes in
 * [com.gamecore.core.model.QuickTriggerMethod] and hands them straight to [QuickTriggerCoordinator]; it
 * cannot read window contents, cannot read text the user types, and sends nothing anywhere.
 *
 * ### Why it does not consume the keys
 *
 * [QuickTriggerCoordinator.onKeyEvent] returns whether to swallow the event, and it says no unless the
 * user has deliberately turned the pass-through off. A volume key that stops changing the volume is a bug
 * from the user's side of the screen, and this service sits in front of *every* app's volume keys — the
 * cost of getting that wrong is much higher here than in the activity.
 */
@AndroidEntryPoint
class QuickTriggerAccessibilityService : AccessibilityService() {

    @Inject lateinit var coordinator: QuickTriggerCoordinator

    /**
     * Every hardware key, before the focused app sees it. Returns whether to swallow it.
     *
     * The coordinator does its own filtering — it ignores everything but the configured key while the
     * trigger is armed — so there is no second copy of that rule here to fall out of step with it. A
     * throwing coordinator must not take the user's volume keys with it, hence the catch: the event is
     * passed through and the shortcut simply does not fire.
     */
    override fun onKeyEvent(event: KeyEvent?): Boolean {
        val key = event ?: return false
        return try {
            coordinator.onKeyEvent(
                keyCode = key.keyCode,
                action = key.action,
                repeatCount = key.repeatCount,
                eventTimeMillis = key.eventTime,
            )
        } catch (error: Throwable) {
            false
        }
    }

    /** Required by the base class. Nothing is subscribed, so nothing arrives here. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    /**
     * Called when the system interrupts the service's feedback.
     *
     * There is no feedback to interrupt — this service speaks nothing, vibrates nothing and draws nothing
     * — so there is nothing to stop.
     */
    override fun onInterrupt() = Unit
}
