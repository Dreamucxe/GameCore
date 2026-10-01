package com.gamecore.ui.touch

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.core.common.Formatters
import com.gamecore.ui.components.ActionRow
import com.gamecore.ui.components.EmptyState
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.ReadoutRow
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.readout
import com.gamecore.ui.components.screenAspectRatio
import java.util.Locale

/**
 * Touch Sampling Monitor: how fast, and how honestly, this device hands touch to an app.
 *
 * Its own capture pad, and only that pad — like the touch heatmap, Android gives an app the pointer events
 * dispatched to its own windows and nothing else, so the figures here describe the path from this pad to
 * this process and make no claim about the panel underneath. The three readings are deliberately different
 * kinds of statement: a delivered throughput, an *observed* sub-frame cadence that only exists once the
 * framework batches samples, and a touch-to-photon latency that Android does not expose and this screen
 * therefore refuses to invent. Each renders through [ReadoutRow], so an absent figure shows as "—" with a
 * reason, never as a fabricated zero.
 *
 * The live state is collected with [collectAsStateWithLifecycle], so the pad and the rebuild pulse stop the
 * moment the screen is left. When touch sampling is switched off in Settings the pad never listens and the
 * screen shows a disabled state.
 */
@Composable
fun SamplingMonitorScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SamplingMonitorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val padded = Modifier.padding(horizontal = ScreenPadding)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Touch sampling",
                subtitle = "Input timing, measured on this device's own pad",
                onBack = onBack,
                action = {
                    if (state.isCapturing) StatusChip("Capturing", Tone.Accent, Icons.Filled.TouchApp)
                },
            )
        }

        if (!state.enabled) {
            item { DisabledCard(modifier = padded) }
            return@LazyColumn
        }

        item {
            CaptureCard(
                isCapturing = state.isCapturing,
                onCapturing = viewModel::setCapturing,
                onSamples = viewModel::onSamples,
                modifier = padded,
            )
        }
        item { MetricsCard(state = state, modifier = padded) }
        item { LatencyCard(state = state, modifier = padded) }
        item { PrivacyCard(modifier = padded) }
    }
}

/**
 * The pad and the switch that decides whether it listens.
 *
 * The pad takes the gesture only while capturing, the same truthful default the heatmap uses: with capture
 * off it declines the touch so the list scrolls under the finger, and nothing is measured because nothing
 * was delivered.
 */
@Composable
private fun CaptureCard(
    isCapturing: Boolean,
    onCapturing: (Boolean) -> Unit,
    onSamples: (LongArray, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Capture pad",
        modifier = modifier,
        subtitle = if (isCapturing) CAPTURE_ON else CAPTURE_OFF,
        icon = Icons.Filled.TouchApp,
    ) {
        SamplingPad(isCapturing = isCapturing, onSamples = onSamples)
        Spacer(modifier = Modifier.height(10.dp))
        ActionRow {
            if (isCapturing) {
                Button(onClick = { onCapturing(false) }) { Text("Stop capture") }
            } else {
                Button(onClick = { onCapturing(true) }) { Text("Start capture") }
            }
        }
    }
}

/**
 * The surface itself, at this device's aspect ratio.
 *
 * Every sample here is one Android dispatched to this composable. Each delivered [change] carries its own
 * `uptimeMillis` and, when the framework batched sub-frame samples, a list of `historical` samples with
 * their own timestamps; both are forwarded so the monitor can tell a batched delivery from a single one.
 * The block consumes what it receives so the surrounding list cannot steal a drag mid-swipe.
 *
 * `PointerInputChange.historical` is experimental, and opted into for the same reason the overlay windows
 * opt in: it is the only way to see the samples the framework coalesced into one frame, and without them
 * this screen would report the frame rate rather than the touch rate — which is the one number it exists to
 * measure. Nothing else on this screen depends on it, so an API change would cost this function and no more.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SamplingPad(
    isCapturing: Boolean,
    onSamples: (LongArray, Long) -> Unit,
) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    val surface = MaterialTheme.colorScheme.surfaceVariant
    val ratio = screenAspectRatio()

    val listening = if (!isCapturing) {
        Modifier
    } else {
        Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    for (change in event.changes) {
                        if (change.pressed || change.previousPressed) {
                            val historical = change.historical
                            val times = LongArray(historical.size + 1)
                            for (i in historical.indices) times[i] = historical[i].uptimeMillis
                            times[historical.size] = change.uptimeMillis
                            onSamples(times, SystemClock.uptimeMillis())
                        }
                        change.consume()
                    }
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(14.dp))
            .then(listening),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(color = surface)
            drawRect(color = outline, size = size, style = Stroke(width = 2f))
        }
        Text(
            text = if (isCapturing) PAD_DRAW else PAD_IDLE,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 24.dp),
        )
    }
}

/**
 * The two rates the timestamps can honestly support, drawn straight from the monitor's [com.gamecore.core.common.Observed] figures.
 *
 * Each row's detail is shown only when there is a figure to caveat: the delivered rate says it is a
 * throughput and not the panel's rate, and the observed cadence carries the resampling caveat with it, so a
 * number never appears on this screen without the sentence that keeps it honest.
 */
@Composable
private fun MetricsCard(state: SamplingMonitorState, modifier: Modifier = Modifier) {
    SectionCard(
        title = "Live sampling",
        modifier = modifier,
        subtitle = "Measured from the timestamps this pad was handed",
        icon = Icons.Filled.Insights,
    ) {
        ReadoutRow(
            state.deliveredEventRate.readout(
                label = "Delivered event rate",
                tone = Tone.Accent,
                detailOf = { DELIVERED_DETAIL },
                format = { perSecond(it) },
            ),
        )
        ReadoutRow(
            state.observedSampleRateHz.readout(
                label = "Observed input sample rate",
                detailOf = { SAMPLE_RATE_DETAIL },
                format = { Formatters.hertz(it) },
            ),
        )
        ReadoutRow(
            state.inputProcessingDelay.readout(
                label = "Input processing delay",
                detailOf = { PROCESSING_DETAIL },
                format = { millisEstimate(it) },
            ),
        )
    }
}

/**
 * The reading this screen refuses to turn into a number, and why.
 *
 * The row renders "—" through [ReadoutRow]'s generic wording; the paragraph beneath is the honest reason in
 * full, in the same terms the controller reader uses, because the auto-detail is deliberately generic and
 * this particular absence is the whole point of the card.
 */
@Composable
private fun LatencyCard(state: SamplingMonitorState, modifier: Modifier = Modifier) {
    SectionCard(
        title = "Hardware touch latency",
        modifier = modifier,
        icon = Icons.Filled.Bolt,
    ) {
        ReadoutRow(
            state.hardwareTouchLatency.readout(
                label = "Touch-to-photon latency",
                format = { "" },
            ),
        )
        Spacer(modifier = Modifier.height(6.dp))
        NoteBanner(text = LATENCY_EXPLANATION, tone = Tone.Muted, icon = Icons.Filled.Info)
    }
}

/** What this screen does not have access to, said plainly rather than left to be assumed. */
@Composable
private fun PrivacyCard(modifier: Modifier = Modifier) {
    SectionCard(
        title = "What this can and cannot see",
        modifier = modifier,
        icon = Icons.Filled.PrivacyTip,
    ) {
        Text(
            text = SCOPE_EXPLANATION,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(10.dp))
        NoteBanner(text = LOCAL_ONLY, tone = Tone.Muted, icon = Icons.Filled.PrivacyTip)
    }
}

/** Shown in place of everything when the user has switched touch sampling off in Settings. */
@Composable
private fun DisabledCard(modifier: Modifier = Modifier) {
    SectionCard(
        title = "Touch sampling",
        modifier = modifier,
        icon = Icons.Filled.TouchApp,
    ) {
        EmptyState(
            icon = Icons.Filled.TouchApp,
            title = "Sampling is off",
            message = DISABLED_MESSAGE,
        )
    }
}

// ------------------------------------------------------------------------------------ formatting

private fun perSecond(rate: Float): String = String.format(Locale.US, "%.0f /s", rate)

private fun millisEstimate(ms: Float): String = String.format(Locale.US, "~%.1f ms", ms)

private const val CAPTURE_ON = "Move a finger on the pad below to measure"

private const val CAPTURE_OFF = "Capture is off — the pad passes touches through"

private const val PAD_DRAW = "Move your finger here"

private const val PAD_IDLE = "Start capture, then move your finger here"

private const val DELIVERED_DETAIL =
    "Input samples Android delivered to this pad per second, over at least a second of capture. Frame " +
        "delivery batches these, so it is throughput to this app, not the panel's own rate."

private const val SAMPLE_RATE_DETAIL =
    "The cadence the batched sample timestamps imply while your finger moves. Android may resample touch " +
        "input, so this is an observed rate, not a reading of the panel's hardware sampling rate."

private const val PROCESSING_DETAIL =
    "Estimated: the clock now minus the newest sample's timestamp — queue delay inside this app, not " +
        "touch-to-photon latency."

private const val LATENCY_EXPLANATION =
    "Android timestamps a touch when the framework receives it — after the digitizer, the driver and the " +
        "input pipeline — and never exposes the moment your finger met the glass. Any figure derived from " +
        "those timestamps would describe queueing inside this app, not the hardware, so GameCore reports " +
        "the timestamps it has and makes no touch-to-photon latency claim at all."

private const val SCOPE_EXPLANATION =
    "This measures the touch samples Android delivers to the pad on this screen. That is the whole of what " +
        "an ordinary app is given: pointer events aimed at its own windows. It reads no input device, opens " +
        "no panel, and is told nothing about a touch anywhere else on this device."

private const val LOCAL_ONLY =
    "Nothing here is recorded, saved or uploaded. The figures are computed live from the timestamps and " +
        "vanish when you leave the screen — there is no account, no server and no file."

private const val DISABLED_MESSAGE =
    "Touch sampling is switched off in GameCore's settings. Turn it on to measure input timing on this " +
        "device's own pad."
