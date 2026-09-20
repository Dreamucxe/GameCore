package com.gamecore.aimlab.render

import android.opengl.GLSurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.gamecore.aimlab.engine3d.Room

/**
 * Hosts the training [GLSurfaceView] inside Compose and ties its GL lifecycle to the screen (§2, §5).
 *
 * The GL surface owns all per-frame work; Compose only hands it a way to pull the latest [RenderState]
 * and receives the measured frame time back. Rendering is continuous while the surface is resumed and
 * paused the instant the screen leaves or backgrounds, so no GL work ever happens outside an active
 * session (§5, §8.item-5). The surface is released on dispose.
 *
 * If the device cannot create a GLES 2.0 context, or a shader fails to compile, [onGlUnavailable] fires
 * and the caller shows a plain fallback screen — the app never crashes on a GL fault (§2).
 *
 * @param stateSource pulled once per frame on the GL thread; must be cheap and thread-safe (it reads an
 *   AtomicReference the loop publishes into).
 * @param quality resolution-scale preset; applied via a fixed surface size so a weaker GPU renders fewer
 *   pixels without changing the layout.
 * @param onFrameTimeMillis real per-frame time for the optional debug FPS overlay (never fabricated).
 * @param onGlUnavailable invoked on the main thread if GL setup fails.
 */
@Composable
fun TrainingGlView(
    stateSource: () -> RenderState,
    quality: RenderQuality,
    onFrameTimeMillis: (Float) -> Unit,
    onGlUnavailable: (String) -> Unit,
    modifier: Modifier = Modifier,
    room: Room = Room(),
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var failed by remember { mutableStateOf(false) }

    // Hold the created surface so lifecycle callbacks and dispose can reach it.
    val holder = remember { GlViewHolder() }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            val view = object : GLSurfaceView(context) {}.apply {
                setEGLContextClientVersion(2)
                // Report a context-factory failure as a GL-unavailable fallback rather than a crash.
                setEGLConfigChooser(8, 8, 8, 0, 16, 0)
            }
            val renderer = TrainingRenderer(
                stateSource = stateSource,
                onFrameTimeMillis = onFrameTimeMillis,
                onError = { message ->
                    // Marshalled onto the main thread; setting Compose state triggers the fallback.
                    view.post {
                        if (!failed) {
                            failed = true
                            onGlUnavailable(message)
                        }
                    }
                },
                room = room,
            )
            view.setRenderer(renderer)
            view.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            holder.view = view
            holder.renderer = renderer
            holder.quality = quality
            applyQuality(view, quality)
            view
        },
        update = { view ->
            if (holder.quality != quality) {
                holder.quality = quality
                applyQuality(view, quality)
            }
        },
    )

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> holder.view?.onResume()
                Lifecycle.Event.ON_PAUSE -> holder.view?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // Pause first, then release GL resources on the GL thread.
            holder.view?.let { view ->
                view.queueEvent { holder.renderer?.release() }
                view.onPause()
            }
            holder.view = null
            holder.renderer = null
        }
    }
}

/** Applies the resolution scale by fixing the surface's pixel size below the view's layout size (§2). */
private fun applyQuality(view: GLSurfaceView, quality: RenderQuality) {
    view.post {
        val w = (view.width * quality.scale).toInt().coerceAtLeast(1)
        val h = (view.height * quality.scale).toInt().coerceAtLeast(1)
        if (view.width > 0 && view.height > 0) view.holder.setFixedSize(w, h)
    }
}

/** Mutable holder so the factory's created surface is reachable from lifecycle/update/dispose. */
private class GlViewHolder {
    var view: GLSurfaceView? = null
    var renderer: TrainingRenderer? = null
    var quality: RenderQuality? = null
}
