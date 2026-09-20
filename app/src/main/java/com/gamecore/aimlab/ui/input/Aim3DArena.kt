package com.gamecore.aimlab.ui.input

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gamecore.aimlab.engine.ControlLayout
import com.gamecore.aimlab.engine.ControlOrientation
import com.gamecore.aimlab.render.RenderQuality
import com.gamecore.aimlab.runtime.AimTrainingLoop3D
import com.gamecore.core.overlay.CrosshairOverlay
import com.gamecore.core.model.CrosshairPreset

/**
 * The shared first-person arena every 3D mode screen draws its run in (§1, §3, §5).
 *
 * One place hosts the GL surface, the fixed centre crosshair, and the mode's HUD, so the seven mode
 * screens differ only in the [hud] they overlay and the input they accept — not in how the 3D world is
 * shown. The GL view owns per-frame work; this composable forwards touch look/shoot into the loop and
 * pulls the loop's published [AimTrainingLoop3D.renderState] for the renderer, so neither touch nor
 * sensor events recompose the screen (§5).
 *
 * The crosshair is the existing [CrosshairOverlay] (§1: reuse the crosshair rendering), fixed dead centre
 * — in a first-person view the crosshair does not move, the camera does. If OpenGL cannot start on the
 * device, the arena shows a plain "3D view unavailable" panel instead of the surface, and never crashes
 * (§2).
 *
 * @param loop the run's 3D loop; its render snapshot drives the GL thread and its input methods take the
 *   touch look/shoot.
 * @param crosshair the user's crosshair preset, drawn centred over the view.
 * @param quality resolution-scale preset for the render surface.
 * @param onShot forwarded from a centre tap (position is irrelevant — the crosshair is fixed).
 * @param hud the mode's own overlay (stat strip, controls), drawn above the arena in a [BoxScope].
 */
@Composable
fun Aim3DArena(
    loop: AimTrainingLoop3D,
    crosshair: CrosshairPreset,
    quality: RenderQuality,
    onShot: () -> Unit,
    modifier: Modifier = Modifier,
    excludeTouch: ((xFraction: Float, yFraction: Float) -> Boolean)? = null,
    controlLayout: ControlLayout? = null,
    controlOrientation: ControlOrientation = ControlOrientation.PORTRAIT,
    horizontalFovDegrees: Int = 90,
    hud: @Composable BoxScope.() -> Unit = {},
) {
    var glError by remember { mutableStateOf<String?>(null) }
    val view = LocalView.current

    // Keep the loop's FOV and the gyro's display-rotation remap current, re-read whenever the view's
    // display rotation changes — which is exactly what a rotation triggers (§2/§3/§6). Cheap and idempotent.
    @Suppress("DEPRECATION")
    val rotation = view.display?.rotation ?: 0
    LaunchedEffect(horizontalFovDegrees, rotation) {
        loop.setHorizontalFov(horizontalFovDegrees.toFloat())
        loop.setDisplayRotation(rotation)
    }

    Box(modifier = modifier.fillMaxSize()) {
        val error = glError
        if (error == null) {
            TrainingSurface3D(
                renderStateSource = { loop.renderState.get() },
                onShot = onShot,
                onLookPixels = { dx, dy, w, aiming -> loop.onLookPixels(dx, dy, w, aiming) },
                quality = quality,
                onFrameTime = { /* wired to the debug overlay by the screen when enabled */ },
                onGlUnavailable = { glError = it },
                excludeTouch = excludeTouch,
                modifier = Modifier.fillMaxSize(),
            )
            // The on-screen controls, drawn ABOVE the 3D view (§4). ControlOverlay falls back to the
            // preset when the chosen orientation has no controls, so a selected layout never shows a bare
            // arena. Null means the mode takes no controls (e.g. flick/reaction without a layout picked).
            if (controlLayout != null) {
                ControlOverlay(
                    layout = controlLayout,
                    orientation = controlOrientation,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // The fixed centre crosshair, the existing renderer reused verbatim. It draws in its own
            // full-size box and centres itself, which is exactly where a first-person reticle belongs.
            CrosshairOverlay(preset = crosshair, modifier = Modifier.fillMaxSize())
        } else {
            GlUnavailable(reason = error, modifier = Modifier.fillMaxSize())
        }

        hud()
    }
}

/**
 * The fallback shown when a device cannot create a GLES 2.0 context or a shader will not compile (§2).
 *
 * Plain, honest, and non-fatal: the feature says it cannot draw the 3D view on this device rather than
 * crashing or pretending. The reason is included in small print for a bug report, never as an error the
 * user is asked to act on.
 */
@Composable
private fun GlUnavailable(reason: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                text = "3D view unavailable on this device",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp, start = 24.dp, end = 24.dp),
            )
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp, start = 24.dp, end = 24.dp),
            )
        }
    }
}
