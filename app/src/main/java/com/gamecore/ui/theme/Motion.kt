package com.gamecore.ui.theme

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Motion tokens — the redesign's §2 range (150-250 ms) that honours the system animator duration scale.
 *
 * Two rules the spec is firm about:
 *  - Animations are short: 150 ms for a small state flip, 250 ms for a panel that slides in. Anything
 *    longer reads as lag in an app whose whole point is responsiveness.
 *  - The system animator duration scale wins. A user who has set "Animator duration scale" to 0 in
 *    Developer options (or turned animations off for motion sensitivity) gets no animation — duration 0 —
 *    not a hard-coded 200 ms that ignores them. The scale multiplies our base duration, and 0× means off.
 *
 * [animationScale] reads `Settings.Global.ANIMATOR_DURATION_SCALE` once per composition that asks; a
 * screen calls [MotionTokens.short]/[medium] to get the already-scaled millis and feeds it to a
 * `tween`. When the app's own Animations setting (System/On/Off) is Off, the caller passes scale 0f.
 */
object MotionTokens {
    /** A small state change: a toggle filling, a chip selecting. */
    const val SHORT_MILLIS = 150

    /** A larger move: a card expanding, a sheet sliding in. */
    const val MEDIUM_MILLIS = 250

    /** [SHORT_MILLIS] scaled by the effective animator scale, floored at 0 (no animation). */
    fun short(scale: Float): Int = (SHORT_MILLIS * scale).toInt().coerceAtLeast(0)

    fun medium(scale: Float): Int = (MEDIUM_MILLIS * scale).toInt().coerceAtLeast(0)
}

/**
 * The effective animator duration scale for this device, 0f when the system (or the user's Animations
 * setting, applied by the caller) has motion switched off.
 *
 * Read from the platform rather than assumed. `ANIMATOR_DURATION_SCALE` is the same knob accessibility
 * and Developer options write, so respecting it here is how "reduce motion" actually reduces ours.
 */
@Composable
fun systemAnimationScale(): Float {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
    }
}
