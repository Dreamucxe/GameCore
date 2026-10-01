package com.gamecore.ui.extraction

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.core.system.capture.CropRect
import com.gamecore.core.system.capture.CropSize
import com.gamecore.ui.components.ActionRow
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.OnResume
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.startIntentSafely
import kotlin.math.roundToInt

/**
 * §Screen-Extraction: the screen, captured, cropped, saved or shared.
 *
 * The composable is a pure reading of [ScreenExtractionState]: the feature-off, no-projection, awaiting-consent
 * and frame-in-hand states are branches here, never guesses, and there is never a control that does nothing —
 * a device that cannot capture shows a sentence, not a dead button. The crop box is drawn and dragged entirely
 * in preview pixels; the view-model turns it into bitmap pixels through
 * [com.gamecore.core.system.capture.CropMath] only when the user saves.
 *
 * The held frame is dropped in `onDispose`, so a full-screen bitmap is never kept alive behind a back press.
 */
@Composable
fun ScreenExtractionScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ScreenExtractionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    OnResume { viewModel.refresh() }
    DisposableEffect(Unit) { onDispose { viewModel.clearFrame() } }

    val launch: (Intent?) -> Unit = { intent -> intent?.let { context.startIntentSafely(it) } }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScreenHeader(title = SCREEN_TITLE, subtitle = SCREEN_SUBTITLE, onBack = onBack) }

        state.message?.let { message ->
            item {
                NoteBanner(
                    text = message,
                    tone = Tone.Accent,
                    action = { TextButton(onClick = viewModel::dismissMessage) { Text("OK") } },
                )
            }
        }
        when {
            !state.enabled -> item {
                SectionCard(title = SECTION_TITLE, action = { StatusChip("Off", Tone.Muted) }) {
                    Note(DISABLED_NOTE)
                }
            }

            !state.captureSupported -> item {
                SectionCard(title = SECTION_TITLE, action = { StatusChip("Unavailable", Tone.Muted) }) {
                    Note(UNAVAILABLE_NOTE)
                }
            }

            else -> {
                item { ExtractControls(state = state, onExtract = viewModel::extract) }

                val frame = state.frame
                if (frame != null) {
                    item(key = System.identityHashCode(frame)) {
                        CropCard(
                            frame = frame,
                            saved = state.savedUri != null,
                            busy = state.busy,
                            onCrop = viewModel::setCrop,
                            onSave = viewModel::save,
                            onShare = { launch(viewModel.share()) },
                        )
                    }
                }
            }
        }
    }
}

/** A muted explanatory sentence — the honest stand-in wherever a control would otherwise do nothing. */
@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ExtractControls(state: ScreenExtractionState, onExtract: () -> Unit) {
    SectionCard(
        title = SECTION_TITLE,
        action = {
            when {
                state.busy -> StatusChip("Working", Tone.Accent)
                state.hasConsent -> StatusChip("Ready", Tone.Good)
                else -> Unit
            }
        },
    ) {
        Note(if (state.hasConsent) EXTRACT_NOTE else CONSENT_NOTE)
        Spacer(modifier = Modifier.height(10.dp))
        ActionRow {
            Button(onClick = onExtract, enabled = !state.busy) {
                Text(if (state.frame == null) "Extract screen" else "Re-extract")
            }
        }
    }
}

/**
 * The captured frame, with a crop box laid over it that the user can drag to move and resize.
 *
 * The box lives in preview pixels as local state so dragging is smooth without recomposing the whole screen;
 * it is reported to the view-model on release (and once, at its full-frame default, when the preview is first
 * measured), which is all [onSave] needs. The preview is sized to the frame's own aspect within a bounded box,
 * so the crop coordinates map straight onto the drawn image with no letterboxing to correct for.
 */
@Composable
private fun CropCard(
    frame: Bitmap,
    saved: Boolean,
    busy: Boolean,
    onCrop: (CropRect?, CropSize) -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
) {
    val image = remember(frame) { frame.asImageBitmap() }
    val aspect = remember(frame) { frame.width.toFloat() / frame.height.coerceAtLeast(1) }
    var previewPx by remember(frame) { mutableStateOf(IntSize.Zero) }
    var box by remember(frame) { mutableStateOf<Rect?>(null) }

    val borderColour = MaterialTheme.colorScheme.primary
    val scrimColour = Color.Black.copy(alpha = 0.5f)
    val density = LocalDensity.current
    val handleRadius = with(density) { 8.dp.toPx() }
    val strokeWidth = with(density) { 2.dp.toPx() }

    // First real measurement: default the box to the whole frame and report it, so a save with no dragging
    // keeps the full screen rather than nothing.
    LaunchedEffect(previewPx) {
        if (box == null && previewPx.width > 0 && previewPx.height > 0) {
            val full = Rect(0f, 0f, previewPx.width.toFloat(), previewPx.height.toFloat())
            box = full
            onCrop(full.toCropRect(), CropSize(previewPx.width, previewPx.height))
        }
    }

    SectionCard(
        title = PREVIEW_TITLE,
        action = { if (saved) StatusChip("Saved", Tone.Good) },
    ) {
        Note(CROP_NOTE)
        Spacer(modifier = Modifier.height(10.dp))
        BoxWithConstraints(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val heightCap = 460.dp
            var previewWidth = maxWidth
            var previewHeight = maxWidth / aspect
            if (previewHeight > heightCap) {
                previewHeight = heightCap
                previewWidth = heightCap * aspect
            }
            Box(
                modifier = Modifier
                    .size(previewWidth, previewHeight)
                    .clip(MaterialTheme.shapes.medium)
                    .onSizeChanged { previewPx = it },
            ) {
                Image(
                    bitmap = image,
                    contentDescription = PREVIEW_DESC,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.matchParentSize(),
                )
                Canvas(
                    modifier = Modifier
                        .matchParentSize()
                        .pointerInput(previewPx) {
                            var mode = DragMode.None
                            detectDragGestures(
                                onDragStart = { position ->
                                    val current = box
                                    mode = when {
                                        current == null -> DragMode.None
                                        current.nearBottomRight(position) -> DragMode.Resize
                                        current.contains(position) -> DragMode.Move
                                        else -> DragMode.None
                                    }
                                },
                                onDrag = { _, delta ->
                                    val current = box
                                    if (current != null && mode != DragMode.None) {
                                        box = if (mode == DragMode.Resize) {
                                            current.resizedBy(delta, previewPx)
                                        } else {
                                            current.movedBy(delta, previewPx)
                                        }
                                    }
                                },
                                onDragEnd = {
                                    val current = box ?: return@detectDragGestures
                                    onCrop(
                                        current.toCropRect(),
                                        CropSize(previewPx.width, previewPx.height),
                                    )
                                },
                            )
                        },
                ) {
                    val current = box ?: return@Canvas
                    // Dim everything outside the box so the kept region reads as the selection.
                    drawRect(scrimColour, Offset.Zero, Size(size.width, current.top))
                    drawRect(scrimColour, Offset(0f, current.bottom), Size(size.width, size.height - current.bottom))
                    drawRect(scrimColour, Offset(0f, current.top), Size(current.left, current.height))
                    drawRect(scrimColour, Offset(current.right, current.top), Size(size.width - current.right, current.height))
                    drawRect(
                        color = borderColour,
                        topLeft = Offset(current.left, current.top),
                        size = Size(current.width, current.height),
                        style = Stroke(width = strokeWidth),
                    )
                    drawCircle(borderColour, radius = handleRadius, center = Offset(current.right, current.bottom))
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        ActionRow {
            Button(onClick = onSave, enabled = !busy) { Text("Save") }
            Button(onClick = onShare, enabled = saved && !busy) { Text("Share") }
        }
        if (!saved) {
            Spacer(modifier = Modifier.height(6.dp))
            Note(SHARE_HINT)
        }
        if (busy) {
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

private enum class DragMode { None, Move, Resize }

private fun Rect.movedBy(delta: Offset, bounds: IntSize): Rect {
    val maxLeft = (bounds.width - width).coerceAtLeast(0f)
    val maxTop = (bounds.height - height).coerceAtLeast(0f)
    val newLeft = (left + delta.x).coerceIn(0f, maxLeft)
    val newTop = (top + delta.y).coerceIn(0f, maxTop)
    return Rect(newLeft, newTop, newLeft + width, newTop + height)
}

private fun Rect.resizedBy(delta: Offset, bounds: IntSize): Rect {
    val maxRight = bounds.width.toFloat().coerceAtLeast(left + CROP_MIN_SIZE_PX)
    val maxBottom = bounds.height.toFloat().coerceAtLeast(top + CROP_MIN_SIZE_PX)
    val newRight = (right + delta.x).coerceIn(left + CROP_MIN_SIZE_PX, maxRight)
    val newBottom = (bottom + delta.y).coerceIn(top + CROP_MIN_SIZE_PX, maxBottom)
    return Rect(left, top, newRight, newBottom)
}

private fun Rect.nearBottomRight(point: Offset): Boolean =
    (point - Offset(right, bottom)).getDistance() <= CROP_HANDLE_TOUCH_PX

private fun Rect.toCropRect(): CropRect =
    CropRect(left.roundToInt(), top.roundToInt(), right.roundToInt(), bottom.roundToInt())

/** The smallest crop the resize handle will allow, in preview pixels: below this a box is not worth saving. */
private const val CROP_MIN_SIZE_PX = 48f

/** How close to the bottom-right corner a touch counts as grabbing the resize handle, in preview pixels. */
private const val CROP_HANDLE_TOUCH_PX = 56f

private const val SCREEN_TITLE = "Screen Extraction"
private const val SCREEN_SUBTITLE = "Capture the screen, crop it, then save or share."
private const val SECTION_TITLE = "Extract"
private const val PREVIEW_TITLE = "Crop"
private const val PREVIEW_DESC = "The captured screen"
private const val DISABLED_NOTE =
    "Screen Extraction is turned off. Enable it in Settings to capture and crop the screen."
private const val UNAVAILABLE_NOTE =
    "This device does not provide a screen capture, so there is nothing to extract here."
private const val CONSENT_NOTE =
    "The first extraction asks to capture your screen. Grant it once and the frame appears here to crop."
private const val EXTRACT_NOTE =
    "Extracts one frame of the current screen. Only this single frame is captured — nothing is recorded."
private const val CROP_NOTE =
    "Drag inside the box to move it, or its corner to resize. Save keeps what is inside the box."
private const val SHARE_HINT = "Save the crop first, then you can share it."
