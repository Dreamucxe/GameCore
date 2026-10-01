package com.gamecore.core.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamecore.core.model.FractionPoint
import kotlin.math.roundToInt

/**
 * Where the volume trigger presses, drawn as a mark over the running game (§3.7.1, feature 5) — a sticker on
 * the glass and nothing more.
 *
 * This is the persistent, passive half of the feature. Once a point has been placed the player wants to see
 * it without opening anything, because a trigger still aimed at a fire button the game has since moved is a
 * key that quietly does nothing — and a key that quietly does nothing is indistinguishable, from the
 * player's side, from GameCore having stopped working. So the mark is simply drawn, and that is the whole
 * contract. It is [CrosshairOverlay]'s kind of content: hosted in a `FLAG_NOT_TOUCHABLE` window, taking no
 * touches, reading nothing of the game beneath it and never moving on its own. A marker that took touches
 * would turn the exact spot the trigger presses into a dead zone in the game, which is the one thing a mark
 * sitting on a fire button must never be.
 *
 * There is deliberately **no background and no scrim**. The window is full-screen because the mark can be
 * anywhere on the display, and a full-screen window that painted even a faint wash would tint the entire
 * game for as long as a trigger stayed configured — so the mark is drawn and everything else stays glass.
 *
 * The geometry is the in-app picker's mark to the dp — `com.gamecore.ui.trigger.VolumeTriggerPointPicker`
 * draws a [RING_RADIUS] ring, four [ARM_LENGTH] arms starting at the ring's edge and a filled
 * [CENTRE_DOT_RADIUS] dot with a gap between it and the arm roots, and so does this. The repetition is the
 * point rather than an accident: the user places the spot on a small screen-shaped preview in the profile
 * editor and then meets it for real over a match, and unless the two are recognisably the same mark the
 * in-game one reads as some second, unrelated overlay they did not ask for. It is drawn in [accent] rather
 * than the editor's theme primary because there is no `MaterialTheme` in a service composition, and it
 * carries none of [CrosshairOverlay]'s black outline — this is a mark glanced at between rounds, not a sight
 * aimed down, and an outline would make it the heaviest thing on the screen.
 *
 * @param point where the trigger presses, 0..1 on each axis. [FractionPoint.normalised] is applied here, so a
 *   value that came back out of storage out of range lands on the edge rather than off the display entirely.
 * @param accent the user's accent; the whole mark is drawn in it.
 * @param modifier applied to the full-screen root.
 */
@Composable
fun TriggerPointMarker(
    point: FractionPoint,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    // Clamped once per value rather than inside the draw scope: this window is redrawn whenever anything
    // above it in the service's composition changes, and the clamp has nothing to do with the frame.
    val normalised = remember(point) { point.normalised() }

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawTriggerMark(
                centre = Offset(x = size.width * normalised.x, y = size.height * normalised.y),
                colour = accent,
            )
        }
    }
}

// -------------------------------------------------------------------------- the placement surface

/**
 * The in-game placement surface the dock's Trigger chip opens ([DockRoute.TriggerPoint], §3.7.1, feature 5) —
 * **pure UI**, and the only overlay in this package the player aims *through* rather than at.
 *
 * It exists because of the one thing the profile editor's preview cannot do. A trigger point is placed to
 * land on something in a *particular* game — a fire button, a scope, a reload — and the editor's
 * screen-shaped box is an empty rectangle with none of those in it, so placing a point there is guesswork
 * checked by going back into the match and trying it. Here the game itself is the reference: the player opens
 * the chip mid-round, puts the mark on the thing they want pressed, and presses Done, and the fraction that
 * reaches storage is one chosen while looking at the target.
 *
 * **Nothing is reported until Done.** The working point lives in this composable's own state, seeded once
 * from [initialPoint] — or from dead centre when that is null, because a placement surface with no mark on it
 * gives the finger nothing to pick up, and the centre is the one spot that is never wrong to start from. A
 * drag produces a position sixty times a second; were each of those frames to call [onCommit] the service
 * would write the profile sixty times, every one of them a disk write and a re-emission of the profile flow,
 * for a point the user had not finished choosing. So [onCommit] fires exactly once, on Done, carrying the
 * point as it then stands, and [onCancel] leaves the stored value exactly as it was found. The seed is
 * deliberately **not** keyed on [initialPoint]: re-seeding would mean a profile reload arriving for any
 * reason at all — and the write Done itself causes is one of them — could snatch the mark out from under a
 * finger that was still placing it.
 *
 * **The ✕ is the only way out, so it is drawn like it.** This is hosted in a `fullScreen = true,
 * touchable = true` window, and for a window of that shape [OverlayWindowSpec.dismissOnOutsideTouch] is a
 * no-op: the window covers the display, so there is no outside left to tap and no touch the window system
 * could ever deliver as one. A player who cannot find the way out of a layer sitting over their match has to
 * force-stop GameCore to get the game back, which is the failure this surface is one tap away from at all
 * times. Cancel is therefore a full chip the same size as Done, carrying both the ✕ glyph and the word,
 * always drawn, never collapsed to a bare mark in a corner and never left to a gesture the user has to guess.
 *
 * **The plate moves out of the way rather than waiting to be moved.** There is one compact instruction plate,
 * and it sits against the top edge while the mark is in the lower half of the screen and against the bottom
 * edge while it is in the upper half. A plate in a fixed corner is a placement surface whose own instructions
 * cover the spot a user is trying to reach, and the only fix for that is asking them to drag the plate away
 * first. The swap happens on the half, so the plate is never closer to the mark than roughly a quarter of the
 * screen, and the aim never has to be interrupted to read the position.
 *
 * The dimming is [OverlayPalette.Plate] at [SCRIM_ALPHA] and not an opaque plate, which is the whole
 * difference between this surface and [DockPanel]: the user is aiming at something *in the game*, so the game
 * has to stay readable through it. The wash is there to say "this layer is taking your touches now" and to
 * lift the mark off a bright scene, not to hide what is underneath — and it is derived from the palette's own
 * plate colour rather than mixed fresh, so the overlay keeps the single dark surface colour [OverlayPalette]
 * exists to give it.
 *
 * Like [DockPanel] and [QuickSheet] it knows nothing of the service, Hilt, preferences or the window it is
 * drawn in — every value arrives as a parameter and the only two things it can cause are its two lambdas —
 * uses [OverlayPalette] rather than `MaterialTheme` for the reason that object's KDoc gives, and draws its
 * glyphs as text so the file needs no icon dependency. State is shown by shape and word, never colour alone
 * (spec §6): Done is filled *and* prefixed ✓, Cancel is outlined *and* prefixed ✕ *and* says "Cancel", and
 * the position is read out in whole percentages rather than left implied by where a dot happens to sit.
 *
 * @param initialPoint the point already stored for this key, or null when none has been placed yet; used only
 *   to seed the working point and never read again.
 * @param accent the user's accent, for the mark and for the Done chip's fill.
 * @param keyLabel which physical key this point belongs to — "Volume Up", "Volume Down" — drawn on the plate
 *   so a user who has bound both keys can see which of the two they are placing. Null draws **nothing** about
 *   a key rather than a placeholder: a surface opened without a key named is one where silence is the honest
 *   answer, and a word like "Unknown" on the plate reads as a bug the user has no way to act on.
 * @param onCommit the finished point, fired once, on Done.
 * @param onCancel leave without reporting anything; the ✕ calls this and the service tears the window down.
 * @param modifier applied to the full-screen root.
 */
@Composable
fun TriggerPointPlacement(
    initialPoint: FractionPoint?,
    accent: Color,
    keyLabel: String?,
    onCommit: (FractionPoint) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Seeded once, deliberately unkeyed — see the KDoc. The clamp is applied to the seed because a stored
    // point is trusted no more here than it is in VolumeTriggerBinding.normalised().
    var working by remember { mutableStateOf(initialPoint?.normalised() ?: CENTRE) }

    // The layer's pixel size, needed to turn a pointer position into a fraction. Read through onSizeChanged
    // rather than assumed from the display: the window is MATCH_PARENT, but what that resolves to depends on
    // the cutout and the bars, and dividing by a guessed width would place the tap slightly off every time.
    var canvas by remember { mutableStateOf(IntSize.Zero) }

    Box(modifier = modifier.fillMaxSize()) {
        // The aiming layer: the scrim, the mark and both gesture detectors, all on one full-screen child that
        // sits *under* the plate. Hanging them on the root instead would make the plate a descendant of the
        // gesture node, and then a drag beginning on the plate — a thumb resting there, a swipe at the ✕ that
        // started slightly wide — would be read as a placement and would throw the mark under the very plate
        // the user was reaching for. Drawn after it as a sibling, the plate absorbs those touches itself.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(OverlayPalette.Plate.copy(alpha = SCRIM_ALPHA))
                .onSizeChanged { canvas = it }
                // Tap and drag as two separate detectors, arranged exactly as the in-app picker arranges
                // them: the tap places on release, the drag only begins once the finger has passed the
                // platform's touch slop, so the two never fight over one gesture and a tap that wobbles a
                // couple of pixels still lands where it was aimed instead of being read as a tiny drag.
                .pointerInput(canvas) {
                    // Nothing is placed before the layer has been measured; a zero width would make every
                    // fraction NaN, and a NaN fraction draws the mark nowhere and stores a point that can
                    // never be tapped.
                    if (canvas.width <= 0 || canvas.height <= 0) return@pointerInput
                    detectTapGestures { offset -> working = offset.toFraction(canvas) }
                }
                .pointerInput(canvas) {
                    if (canvas.width <= 0 || canvas.height <= 0) return@pointerInput
                    detectDragGestures { change, _ -> working = change.position.toFraction(canvas) }
                },
        ) {
            // `working` is read inside the draw lambda rather than passed in, so a drag repaints the mark
            // without recomposing anything — the plate above is the only part that has to recompose, and it
            // only does so because its percentages genuinely changed.
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawTriggerMark(
                    centre = Offset(x = size.width * working.x, y = size.height * working.y),
                    colour = accent,
                )
            }
        }

        InstructionPlate(
            point = working,
            accent = accent,
            keyLabel = keyLabel,
            onDone = { onCommit(working) },
            onCancel = onCancel,
            modifier = Modifier
                // The far edge from the mark. Below the halfway line the plate goes to the top, above it the
                // plate goes to the bottom, so the instructions are always in the half the finger is not in.
                .align(if (working.y > HALF) Alignment.TopCenter else Alignment.BottomCenter)
                // Held inside the system bars before the cosmetic margin is added. This window is full screen,
                // so it carries FLAG_LAYOUT_NO_LIMITS and the short-edges cutout mode (OverlayWindows) and its
                // own edges are the physical display's — the status bar, the navigation bar and the cutout all
                // sit inside it. A plate inset only [PLATE_EDGE_MARGIN] from those edges is not clear of them,
                // and the first-run seed makes that fatal: `working` starts at [CENTRE], so `working.y > HALF`
                // is `0.5 > 0.5` which is false, the plate anchors to the bottom, and its Done/Cancel row lands
                // under the navigation bar — a system window drawn on top that keeps the touch — leaving the one
                // surface built to be escapable with no reachable way out. This is the content-side form of the
                // rule in [OverlayViewHost]: a positioned window is moved clear of the bars, but a window
                // obliged to cover the whole display cannot move, so the one interactive thing on it insets
                // itself instead. Only the plate honours the insets; the aiming layer stays edge-to-edge, since
                // a target may sit behind a bar and must still be reachable by a tap.
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(PLATE_EDGE_MARGIN.dp),
        )
    }
}

// --------------------------------------------------------------------------------------- the mark

/**
 * The one mark both composables draw, so there is exactly one copy of the geometry in this file.
 *
 * A ring, four arms starting at the ring's edge, and a filled centre dot — and the gap between the dot and
 * the arm roots is load-bearing rather than slack. Arms that ran all the way in would meet the dot and the
 * whole thing would read as a solid blob at arm's length, which is precisely when the user is looking at it;
 * the clear space is what keeps the exact centre visible, and the centre is the pixel the synthetic tap is
 * actually sent to.
 *
 * A `DrawScope` extension and not a composable, because `DrawScope` is itself a `Density`: the dp figures can
 * be resolved at draw time, which keeps the mark one function with no density plumbed through it.
 */
private fun DrawScope.drawTriggerMark(centre: Offset, colour: Color) {
    val ring = RING_RADIUS.dp.toPx()
    val arm = ARM_LENGTH.dp.toPx()
    val stroke = MARKER_STROKE.dp.toPx()
    val cx = centre.x
    val cy = centre.y
    drawLine(colour, Offset(cx - ring - arm, cy), Offset(cx - ring, cy), stroke)
    drawLine(colour, Offset(cx + ring, cy), Offset(cx + ring + arm, cy), stroke)
    drawLine(colour, Offset(cx, cy - ring - arm), Offset(cx, cy - ring), stroke)
    drawLine(colour, Offset(cx, cy + ring), Offset(cx, cy + ring + arm), stroke)
    drawCircle(colour, radius = ring, center = centre, style = Stroke(width = stroke))
    drawCircle(colour, radius = CENTRE_DOT_RADIUS.dp.toPx(), center = centre)
}

// -------------------------------------------------------------------------- the instruction plate

/**
 * The single plate on the placement surface: what to do, which key it is for, where the mark is, and the two
 * ways out.
 *
 * Deliberately one plate and not a bar at each edge. Everything here is read in a glance between aims, and a
 * surface with two pieces of chrome on it gives the user a second thing to look past — so the plate carries
 * all four items and moves wholesale, which is also what makes "the half the finger is not in" a rule with
 * nothing left over to place.
 *
 * The percentages are monospaced for [PerformancePill]'s reason: they change continuously under a drag, and
 * in a proportional font the plate's own width would twitch every time a figure crossed from 9 to 10, which
 * on a surface the user is trying to aim on reads as the overlay being unstable.
 *
 * Cancel sits on the left and Done on the right, the order a user coming from any dialogue on the platform
 * expects, and neither is ever disabled — there is no invalid working point, since a fraction clamped to the
 * frame is always somewhere the tap can be sent, so a greyed-out Done here would be a button with no
 * reachable reason to be grey.
 */
@Composable
private fun InstructionPlate(
    point: FractionPoint,
    accent: Color,
    keyLabel: String?,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            // Bounded rather than fillMaxWidth: a plate stretched across a landscape phone would put its two
            // buttons at opposite ends of the screen, and a thumb cannot reach both without moving the hand
            // that is holding the game.
            .widthIn(min = PLATE_MIN_WIDTH.dp, max = PLATE_MAX_WIDTH.dp)
            .clip(shape)
            .background(OverlayPalette.PanelPlate)
            .border(BORDER_DP.dp, OverlayPalette.Divider, shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(
            text = INSTRUCTION,
            modifier = Modifier.fillMaxWidth(),
            style = TextStyle(
                color = OverlayPalette.Text,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            ),
        )

        // Only when the caller named a key. Null says nothing at all rather than drawing a line with a
        // placeholder in it — see the surface's KDoc for why an invented word is worse than a missing one.
        if (keyLabel != null) {
            BasicText(
                text = "$KEY_PREFIX$keyLabel",
                maxLines = 1,
                modifier = Modifier.fillMaxWidth(),
                style = TextStyle(
                    color = OverlayPalette.Muted,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                ),
            )
        }

        BasicText(
            text = positionWords(point),
            maxLines = 1,
            modifier = Modifier.fillMaxWidth(),
            style = TextStyle(
                color = OverlayPalette.Text,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
            ),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PlateButton(
                glyph = CANCEL_GLYPH,
                label = CANCEL_LABEL,
                outline = OverlayPalette.Text,
                fill = null,
                description = CANCEL_DESCRIPTION,
                onClick = onCancel,
                modifier = Modifier.weight(1f),
            )
            PlateButton(
                glyph = DONE_GLYPH,
                label = DONE_LABEL,
                outline = accent,
                fill = accent,
                description = DONE_DESCRIPTION,
                onClick = onDone,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * One of the plate's two buttons: a glyph and a word, filled for the commit and outlined for the way out.
 *
 * [BUTTON_MIN_HEIGHT] rather than the [DockPanel] action chip's 40dp, and that is not a rounding of taste.
 * This is pressed with a thumb that is in the middle of a match and that has just been dragging a mark
 * around; a miss on Done loses the placement the user had just made, and a miss on Cancel leaves them still
 * trapped on a layer covering their game. Both get the larger target, and both get it at the same size so
 * neither reads as the afterthought.
 *
 * [fill] null is the outlined form. The glyph is always drawn alongside the word, so the difference between
 * the two buttons survives a colour-blind read and a washed-out screen (spec §6) — the fill is the last of
 * the three cues, not the only one.
 */
@Composable
private fun PlateButton(
    glyph: String,
    label: String,
    outline: Color,
    fill: Color?,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    val contentColour = if (fill != null) OverlayPalette.Plate else OverlayPalette.Text
    Row(
        modifier = modifier
            .heightIn(min = BUTTON_MIN_HEIGHT.dp)
            .clip(shape)
            .clickable(onClick = onClick)
            .background(fill ?: OverlayPalette.Plate)
            .border(BORDER_DP.dp, outline, shape)
            .semantics { contentDescription = description }
            .padding(vertical = 10.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        BasicText(
            text = glyph,
            style = TextStyle(color = contentColour, fontSize = 13.sp, fontWeight = FontWeight.Bold),
        )
        BasicText(
            text = label,
            maxLines = 1,
            style = TextStyle(color = contentColour, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
        )
    }
}

/**
 * The working point in words a user can check against the screen they are looking at.
 *
 * Whole percentages and the words "across" and "down", rather than a pair of decimals or an `x`/`y` label.
 * The stored value is a fraction, but "0.48, 0.72" is a number the user has no way to verify by looking, and
 * two bare percentages in a row leave which one is which to be guessed — whereas "48% across" is checkable
 * against the thing their finger is on. Rounded rather than truncated, so a mark nudged to the very edge
 * reads as 100% instead of 99%.
 */
private fun positionWords(point: FractionPoint): String {
    val across = (point.x * PERCENT).roundToInt()
    val down = (point.y * PERCENT).roundToInt()
    return "$across% across · $down% down"
}

/**
 * A pointer position in this layer turned into a stored fraction, clamped to the frame.
 *
 * The clamp is the in-app picker's and is here for the same reason: a drag carried past an edge would
 * otherwise store a fraction outside 0..1, and the dispatcher multiplies that by the live display's size to
 * get a pixel — so the tap would be sent off the screen and the trigger would appear to do nothing at all.
 */
private fun Offset.toFraction(canvas: IntSize): FractionPoint = FractionPoint(
    x = (x / canvas.width).coerceIn(0f, 1f),
    y = (y / canvas.height).coerceIn(0f, 1f),
)

// --------------------------------------------------------------------------- geometry and metrics

/**
 * The mark's four figures, in dp, copied from the in-app picker rather than re-chosen.
 *
 * [RING_RADIUS] is the ring, [ARM_LENGTH] is how far each arm reaches beyond it — so the mark's full span is
 * `(RING_RADIUS + ARM_LENGTH) * 2` across, 54dp — [MARKER_STROKE] is the line weight of both the ring and the
 * arms, and [CENTRE_DOT_RADIUS] is the filled dot that names the exact pixel. Changing one of these here and
 * not in the picker is the bug worth watching for: the two drawings would drift apart and the mark the user
 * placed in the editor would stop being the mark they meet over the game.
 */
private const val RING_RADIUS = 15f
private const val ARM_LENGTH = 12f
private const val MARKER_STROKE = 2f
private const val CENTRE_DOT_RADIUS = 2.5f

/** The plate's hairline, matching [DockPanel]'s so the two surfaces read as one family. */
private const val BORDER_DP = 1f

/**
 * How dark the placement surface washes the game.
 *
 * Low on purpose. The user is aiming at something they have to be able to see, so this is the most dimming
 * that still leaves a dark game scene legible — enough to say the layer is live and to lift the mark off a
 * bright sky, and nowhere near enough to hide the fire button being aimed at.
 */
private const val SCRIM_ALPHA = 0.42f

/**
 * The plate's width band and its cosmetic inset from the safe area.
 *
 * The band exists so the two buttons stay a thumb's width apart on a landscape phone rather than being flung
 * to opposite edges. The inset is a plain gap applied *inside* the safe area that
 * `windowInsetsPadding(WindowInsets.safeDrawing)` establishes in [TriggerPointPlacement] — it is not what
 * clears the bars. The status bar, the navigation bar and the cutout are cleared by that inset padding, since
 * this window covers the physical display; this constant is only the breathing room between the plate and the
 * edge of the area left clear of them.
 */
private const val PLATE_MIN_WIDTH = 220f
private const val PLATE_MAX_WIDTH = 320f
private const val PLATE_EDGE_MARGIN = 16f

/** Done and Cancel are both thumb-sized, and larger than a dock chip — see [PlateButton]. */
private const val BUTTON_MIN_HEIGHT = 44f

/** The halfway line the plate's edge is chosen against, and the seed when no point has been placed yet. */
private const val HALF = 0.5f
private val CENTRE = FractionPoint(x = HALF, y = HALF)

/** A fraction read out as a percentage. */
private const val PERCENT = 100f

// ------------------------------------------------------------------------------- user-facing copy

/** Says both halves of the contract in one line: how to place it, and that nothing is stored until Done. */
private const val INSTRUCTION = "Tap or drag to aim — nothing is saved until Done."

private const val KEY_PREFIX = "Bound to "

private const val DONE_LABEL = "Done"
private const val CANCEL_LABEL = "Cancel"

/** Glyphs, not icons: this surface is foundation-only, and each button carries its own mark beside its word. */
private const val DONE_GLYPH = "✓"
private const val CANCEL_GLYPH = "✕"

private const val DONE_DESCRIPTION = "Save the trigger point here"
private const val CANCEL_DESCRIPTION = "Cancel without changing the trigger point"
