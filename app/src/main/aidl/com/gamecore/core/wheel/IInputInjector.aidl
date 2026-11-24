package com.gamecore.core.wheel;

/**
 * The binder contract for the High Sensitivity Wheel's privileged injector.
 *
 * This interface is compiled by the app and implemented by [InputInjectorService], which Shizuku starts
 * with shell uid 2000 inside its own process. That process cannot see GameCore's Kotlin objects or
 * coroutines, so the contract carries only primitives. Each call is synchronous: the wheel needs to know
 * whether the event was accepted before it reports a sample as injected, and `injectInputEvent` with
 * asynchronous delivery returns in well under a millisecond.
 *
 * Coordinates are absolute screen pixels. [downTime] and [eventTime] are `SystemClock.uptimeMillis` from
 * the gesture that produced them. The service keeps the down time so every event in one stream shares it,
 * which is part of a well-formed pointer stream.
 */
interface IInputInjector {
    /** Starts a pointer stream at (x, y). Returns whether the dispatcher accepted the event. */
    boolean down(int pointerId, float x, float y, long downTime, long eventTime);

    /** Moves the open pointer. Returns whether the dispatcher accepted the event. */
    boolean move(int pointerId, float x, float y, long downTime, long eventTime);

    /** Ends the open pointer at (x, y). Returns whether the dispatcher accepted the event. */
    boolean up(int pointerId, float x, float y, long downTime, long eventTime);

    /** Cancels the open pointer. A cancel is a valid release, not an absent one. */
    boolean cancel(int pointerId, long downTime, long eventTime);

    /**
     * Cancels every pointer this service believes it holds.
     *
     * Called on service unbind, on bridge death, on screen loss and whenever the controller loses a
     * gesture without receiving an UP. Idempotent; safe when nothing is held.
     */
    void releaseAll();

    /** True when the reflected injection path resolved and the service is ready to accept events. */
    boolean isReady();

    /**
     * True when a target uid is set and this build resolved `injectInputEventToTarget`.
     *
     * The controller uses this to decide whether the first DOWN can be attempted immediately: a targeted
     * event that still sees the touchable wheel is refused, which is safe to retry. Without targeting, the
     * first attempt must wait for the window flag to reach the dispatcher, because an untargeted event
     * would be delivered back to the overlay.
     */
    boolean isTargeted();

    /**
     * Selects the uid injected events are addressed to, or -1 for the default ("all windows") target.
     *
     * On Android 13 and newer the helper calls `injectInputEventToTarget` when this is set, so the input
     * dispatcher checks that the topmost touchable window at the event coordinates belongs to that uid.
     * That is an extra guard against a GameCore overlay swallowing an event meant for the game; it cannot
     * bypass a touchable overlay, and it is best-effort because the hidden method is not on every build.
     * Where the targeted path is unavailable the helper injects untargeted and says so in [diagnostics].
     */
    void setTargetUid(int uid);

    /**
     * Human-readable injector facts: resolved InputManager path, accepted/rejected counts and the last
     * failure. Deliberately makes no end-to-end latency claim; only the binder round-trip is measured,
     * by the app-side bridge.
     */
    String diagnostics();

    /**
     * Releases every pointer and ends the process. Called after [releaseAll] when the user disables the
     * wheel for good, so a privileged helper does not stay resident after its job is done.
     */
    void destroy();
}
