package com.gamecore.core.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import com.gamecore.core.permissions.PermissionChecker

/** The windows GameCore can put on screen. One live view each, at most. */
enum class OverlaySlot {
    /** The draggable button of §7. Takes touches. */
    BUTTON,

    /** What the button expands into. Takes touches, and dismisses when one lands outside it. */
    PANEL,

    /**
     * The quick sheet of §4: a narrow edge-anchored strip of pinned toggles that a single tap on the
     * button opens (the full [PANEL] is the double-tap). Takes touches, and dismisses on an outside tap
     * like the panel — it is narrow, so "outside" exists. A separate slot from [PANEL] so the two never
     * share a window: opening one must not hide the other through the same [hide] call.
     */
    QUICK_SHEET,

    /** The stats pill of §8. Never takes touches. */
    PILL,

    /** The crosshair of §9. Full screen, never takes touches. */
    CROSSHAIR,

    /** The user's HUD layout from §10. Full screen, never takes touches. */
    HUD,

    /**
     * The pinned magnifier of §13. Full screen, never takes touches: like the crosshair and the HUD it is
     * a decoration the player looks *through*, so it must pass every touch to the game. The window covers
     * the screen but the loupe it draws is a single corner rectangle computed from the [MediaProjection]
     * frame; the rest is transparent. A separate slot so hiding the magnifier never disturbs the crosshair
     * or the HUD sharing the screen with it.
     */
    MAGNIFIER,
}

/**
 * How one overlay window should be laid out.
 *
 * [touchable] is the field that matters most. A window that takes touches and covers part of a game is
 * a dead zone in that game — taps land on GameCore and the player's ability to shoot at whatever is
 * behind the pill quietly disappears. Only the button and the panel are touchable; the pill, the
 * crosshair and the HUD are decoration and must pass every touch through.
 */
data class OverlayWindowSpec(
    val x: Int = 0,
    val y: Int = 0,
    /** [WindowManager.LayoutParams.WRAP_CONTENT] unless the window is full screen. */
    val width: Int = WindowManager.LayoutParams.WRAP_CONTENT,
    val height: Int = WindowManager.LayoutParams.WRAP_CONTENT,
    val touchable: Boolean = false,
    /** True for the crosshair and the HUD, which are positioned by their own content. */
    val fullScreen: Boolean = false,
    /** Delivers outside taps to the view so the panel can close itself. Implies [touchable]. */
    val dismissOnOutsideTouch: Boolean = false,
    /**
     * Measures [y] up from the bottom edge of the screen instead of down from the top, so the window
     * grows upwards.
     *
     * For the control panel, whose height depends on how many action buttons, sliders and stats it ends
     * up drawing — the one measurement that cannot be known before it is laid out. The panel opens
     * against the floating button, and a button sitting low on the screen needs the panel to grow *up*
     * from it. Computing a y for that would need the height; anchoring the bottom edge at
     * `screenHeight - buttonTop` lets the window system do the same arithmetic with the real
     * measurement, which is exact rather than an estimate.
     */
    val anchorBottom: Boolean = false,
)

/**
 * Everything GameCore does to `WindowManager`, in one place.
 *
 * Not a singleton and not injected: it holds live `View`s, and the lifetime of a live view is the
 * lifetime of the service that owns the window. A singleton would outlive
 * [com.gamecore.service.GamingOverlayService] and leak every view the last run added — visible to the
 * user as a floating button that cannot be removed without a force-stop.
 *
 * Every entry point returns a `Boolean` rather than throwing. The three failures are all reachable on a
 * device the user is holding: overlay permission revoked from Settings while the service runs
 * (`BadTokenException` on the *next* `addView`, not at revocation time), a manufacturer ROM that
 * refuses overlay windows to non-system apps (`SecurityException`), and a view removed twice because
 * two stop paths raced (`IllegalArgumentException`). §28 asks for all of them handled without a crash,
 * and a crash here is particularly bad: it takes the foreground service down mid-game.
 */
class OverlayWindows(
    private val context: Context,
    private val permissions: PermissionChecker,
) {

    private val windowManager: WindowManager? =
        context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

    private val live = mutableMapOf<OverlaySlot, View>()

    /** Slots currently on screen, for [com.gamecore.core.model.OverlayStatus]. */
    val visible: Set<OverlaySlot> get() = live.keys.toSet()

    /** True when a window could be added right now. Checked before every add, not cached. */
    fun canDraw(): Boolean = windowManager != null && permissions.hasOverlayPermission()

    fun isVisible(slot: OverlaySlot): Boolean = live.containsKey(slot)

    /**
     * The screen as [OverlayFrame] sees it, for a window of the given size.
     *
     * `currentWindowMetrics` on R and up because `getMetrics` is deprecated there and, more to the
     * point, returns the *application* bounds, which on a device in split-screen is not the screen the
     * overlay window is placed in. Below R there is no alternative and the difference does not arise,
     * because an overlay window in split-screen is not something Android 8 and 9 produce.
     */
    @Suppress("DEPRECATION")
    fun frameFor(windowWidth: Int, windowHeight: Int, marginPx: Int = 0): OverlayFrame {
        val manager = windowManager ?: return OverlayFrame.UNMEASURED
        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            manager.currentWindowMetrics.bounds
        } else {
            null
        }
        val width: Int
        val height: Int
        if (bounds != null) {
            width = bounds.width()
            height = bounds.height()
        } else {
            val metrics = android.util.DisplayMetrics()
            manager.defaultDisplay.getRealMetrics(metrics)
            width = metrics.widthPixels
            height = metrics.heightPixels
        }
        return OverlayFrame(
            screenWidth = width,
            screenHeight = height,
            windowWidth = windowWidth,
            windowHeight = windowHeight,
            marginPx = marginPx,
        )
    }

    /**
     * The frame for a window that is already up, measured from the view itself.
     *
     * The pill's width is whatever its stats add up to and the button's is a configured size in dp; both
     * are known exactly once the view has been laid out, and clamping against an estimate is how a window
     * ends up a few pixels short of the edge it was told to snap to. Null when the slot is not showing or
     * has not been measured yet, which is the caller's cue to fall back to its own estimate — a window is
     * unmeasured for exactly one frame after it is added.
     */
    fun frameFor(slot: OverlaySlot, marginPx: Int = 0): OverlayFrame? {
        val view = live[slot] ?: return null
        if (view.width <= 0 || view.height <= 0) return null
        return frameFor(view.width, view.height, marginPx)
    }

    /**
     * Adds a window, or replaces the one already in that slot.
     *
     * Replacing rather than refusing: a slot's content changes when the user edits its config, and the
     * caller should not have to remember to remove first. Returns false if the window could not be
     * added, in which case nothing is left in the slot.
     */
    fun show(
        slot: OverlaySlot,
        spec: OverlayWindowSpec,
        host: OverlayViewHost,
        content: @Composable () -> Unit,
    ): Boolean {
        val manager = windowManager ?: return false
        if (!host.isRunning || !canDraw()) return false
        hide(slot)
        val view = host.attach(context, content)
        return try {
            manager.addView(view, spec.toLayoutParams())
            live[slot] = view
            true
        } catch (denied: WindowManager.BadTokenException) {
            false
        } catch (refused: SecurityException) {
            false
        } catch (rejected: IllegalStateException) {
            false
        }
    }

    /**
     * Moves a live window. A no-op for a slot that is not showing.
     *
     * `updateViewLayout` rather than remove-and-add, because re-adding a `ComposeView` restarts its
     * composition — the pill would flash and its animations would restart on every drag frame.
     */
    fun move(slot: OverlaySlot, x: Int, y: Int): Boolean {
        val manager = windowManager ?: return false
        val view = live[slot] ?: return false
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return false
        if (params.x == x && params.y == y) return true
        params.x = x
        params.y = y
        return try {
            manager.updateViewLayout(view, params)
            true
        } catch (gone: IllegalArgumentException) {
            // The view was detached between the lookup and the update. Forget it rather than leaving a
            // dead entry that makes `isVisible` lie.
            live.remove(slot)
            false
        }
    }

    /** Reads a live window's current position, so a drag can start from where it actually is. */
    fun positionOf(slot: OverlaySlot): OverlayPlacement? {
        val params = live[slot]?.layoutParams as? WindowManager.LayoutParams ?: return null
        return OverlayPlacement(params.x, params.y, ScreenEdge.FLOATING)
    }

    /**
     * Resizes a live window's width, keeping its right edge on screen. A no-op for a slot that is
     * not showing.
     *
     * A sibling of [move] rather than a widening of it, because [move] compares only x and y and
     * returns early when both already match — a width-only change through it would be dropped
     * without a sign. The two things a caller cannot do separately are done together here: widening
     * a window whose left edge is already well to the right pushes its right edge off screen, and
     * the x that fixes that depends on the new width, so setting the width and re-clamping x in one
     * `updateViewLayout` is what stops the panel from spending a frame hanging over the edge.
     *
     * [marginPx] is the same edge inset [frameFor] and the panel's opening x are given, so a resize
     * lands the window exactly where a fresh open would have.
     *
     * Also `updateViewLayout` rather than remove-and-add, for the reason [move] gives: re-adding a
     * `ComposeView` restarts composition, and here that would mean the panel's sliders snapping back
     * to their loaded values in the middle of a drag.
     */
    fun resize(slot: OverlaySlot, width: Int, marginPx: Int = 0): Boolean {
        val manager = windowManager ?: return false
        val view = live[slot] ?: return false
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return false
        val frame = frameFor(width, view.height, marginPx)
        val x = if (frame.isMeasured) {
            params.x.coerceIn(marginPx.coerceAtMost(frame.maxX), frame.maxX)
        } else {
            params.x
        }
        if (params.width == width && params.x == x) return true
        params.width = width
        params.x = x
        return try {
            manager.updateViewLayout(view, params)
            true
        } catch (gone: IllegalArgumentException) {
            live.remove(slot)
            false
        }
    }

    fun hide(slot: OverlaySlot) {
        val view = live.remove(slot) ?: return
        try {
            windowManager?.removeView(view)
        } catch (absent: IllegalArgumentException) {
            // Already gone — the window was removed by the system, or a second stop path beat us here.
        }
    }

    /** Removes every window. Called from the owning service's `onDestroy`. */
    fun hideAll() {
        OverlaySlot.entries.forEach(::hide)
    }

    /**
     * The flag set, which is where an overlay is made either invisible to the app behind it or a
     * permanent obstruction of it.
     *
     * `FLAG_NOT_FOCUSABLE` on everything: an overlay that takes focus steals the hardware back button
     * and the IME from the game underneath, so a player who opens the chat box in a game finds their
     * keyboard talking to nothing.
     *
     * `FLAG_NOT_TOUCHABLE` on everything that is not [OverlayWindowSpec.touchable] — see that field.
     *
     * `FLAG_LAYOUT_NO_LIMITS` only for full-screen windows, so the crosshair reaches under the status
     * bar and the notch cutout and its centre is the centre of the *screen* rather than of the visible
     * rectangle. Applying it to the button would let it be dragged into the cutout.
     *
     * `Gravity.LEFT`, not `START`: x is a stored pixel offset from the left edge of the screen, and
     * `START` resolves against layout direction, so on an RTL locale the same saved coordinate would
     * put the button on the opposite side from where the user left it.
     */
    private fun OverlayWindowSpec.toLayoutParams(): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (!touchable && !dismissOnOutsideTouch) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        if (dismissOnOutsideTouch) {
            flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        }
        if (fullScreen) {
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }
        return WindowManager.LayoutParams(
            if (fullScreen) WindowManager.LayoutParams.MATCH_PARENT else width,
            if (fullScreen) WindowManager.LayoutParams.MATCH_PARENT else height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = if (anchorBottom) {
                Gravity.BOTTOM or Gravity.LEFT
            } else {
                Gravity.TOP or Gravity.LEFT
            }
            this.x = this@toLayoutParams.x
            this.y = this@toLayoutParams.y
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }
}
