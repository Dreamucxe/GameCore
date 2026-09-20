package com.gamecore.aimlab.ui.input

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.gamecore.aimlab.engine.ControlLayout
import com.gamecore.aimlab.engine.ControlOrientation
import com.gamecore.aimlab.engine.ControlShape
import com.gamecore.aimlab.engine.ControlWidget
import com.gamecore.aimlab.engine.LayoutPreset

/**
 * Draws a control layout's enabled controls onto a surface, honouring each control's size, shape and
 * opacity, for the given [orientation] (§4).
 *
 * This is a renderer only — it takes no touch input; the training surface owns the pointer handling and
 * hit-tests against the same [ControlLayout] via [TouchMath]. Keeping draw and input apart means the same
 * overlay can render the live training controls and the editor preview without either growing the other's
 * concerns.
 *
 * **Never draws nothing when a layout is selected (§4 fallback).** If the chosen orientation's control set
 * is empty — an old layout that has no landscape arrangement yet, or a row that lost its controls — the
 * overlay falls back to the finger-count preset for that orientation rather than showing a bare arena. The
 * fallback is display-only; it does not write anything.
 *
 * A control faded near its opacity floor is still given a faint visible outline, so a low-opacity control
 * is never completely invisible on the surface — the same protection the HUD widgets get.
 */
@Composable
fun ControlOverlay(
    layout: ControlLayout,
    modifier: Modifier = Modifier,
    orientation: ControlOrientation = ControlOrientation.PORTRAIT,
    tint: Color = Color.White,
    pressedRole: String? = null,
) {
    // The enabled set for this orientation, resolved once per (layout, orientation) change rather than in
    // the draw lambda. Empty falls back to the preset the layout's control count implies, so a selected
    // layout always shows something to press.
    val enabled = remember(layout, orientation) {
        val own = layout.enabledControlsFor(orientation)
        if (own.isNotEmpty()) {
            own
        } else {
            val preset = LayoutPreset.forControlCount(layout.controls.size)
            ControlLayout.preset(preset).enabledControlsFor(orientation)
        }
    }
    Canvas(modifier = modifier.fillMaxSize()) {
        enabled.forEach { control ->
            drawControl(control, tint, pressed = control.role.name == pressedRole)
        }
    }
}

/**
 * One control. [ControlWidget.opacityPercent] scales the fill alpha; a pressed control is drawn brighter so
 * a button lights up when a finger is on it (the button-tester behaviour §11 asks for, applied live).
 */
fun DrawScope.drawControl(control: ControlWidget, tint: Color, pressed: Boolean) {
    val cx = control.xFraction * size.width
    val cy = control.yFraction * size.height
    val w = control.widthFraction * size.width
    val h = control.heightFraction * size.height
    val alpha = (control.opacityPercent / 100f).coerceIn(0.15f, 1f)
    val fill = tint.copy(alpha = if (pressed) (alpha + 0.3f).coerceAtMost(1f) else alpha)
    val topLeft = Offset(cx - w / 2f, cy - h / 2f)
    val boxSize = Size(w, h)

    when (control.shape) {
        ControlShape.CIRCLE -> {
            val r = minOf(w, h) / 2f
            drawCircle(color = fill, radius = r, center = Offset(cx, cy))
            drawCircle(color = tint.copy(alpha = OUTLINE_ALPHA), radius = r, center = Offset(cx, cy), style = Stroke(width = OUTLINE_WIDTH))
        }
        ControlShape.SQUARE -> {
            drawRect(color = fill, topLeft = topLeft, size = boxSize)
            drawRect(color = tint.copy(alpha = OUTLINE_ALPHA), topLeft = topLeft, size = boxSize, style = Stroke(width = OUTLINE_WIDTH))
        }
        ControlShape.ROUNDED -> {
            val corner = androidx.compose.ui.geometry.CornerRadius(minOf(w, h) * 0.28f)
            drawRoundRect(color = fill, topLeft = topLeft, size = boxSize, cornerRadius = corner)
            drawRoundRect(color = tint.copy(alpha = OUTLINE_ALPHA), topLeft = topLeft, size = boxSize, cornerRadius = corner, style = Stroke(width = OUTLINE_WIDTH))
        }
    }
}

private const val OUTLINE_ALPHA = 0.9f
private const val OUTLINE_WIDTH = 3f
