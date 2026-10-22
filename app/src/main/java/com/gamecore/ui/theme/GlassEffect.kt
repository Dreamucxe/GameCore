package com.gamecore.ui.theme

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import com.gamecore.core.plugin.theme.PluginGlass
import com.gamecore.core.plugin.theme.PluginTheme
import com.gamecore.core.plugin.theme.resolvedGlass

/**
 * The host's half of a schema-4 theme's `glass` block: panels drawn as material instead of as fill.
 *
 * The same bargain [GlitchLayer] strikes, for the same reason. A `.gcplugin` is data; a theme says
 * *how much* glass it wants — how transparent, how glossy, how far the backdrop softens, how it
 * should settle — and this file, which ships inside the APK, decides what that becomes on the device
 * in the user's hand. Nothing here is reachable from a package.
 *
 * ## What "glass" is without a blur
 *
 * Android applies a real backdrop blur to a layer from API 31. Below that there is no per-pixel
 * filter to reach for, so a material built only out of blur would render as a flat translucent
 * rectangle on every device older than Android 12 — which is not glass, it is a tint. So the
 * material is built out of five things, of which the blur is the *last*:
 *
 *  1. **Translucency.** The panel's own fill loses alpha, down to the ceiling the theme's own
 *     `surface` colour set — see [glassFillAlpha]. A theme whose surface is opaque may ask for
 *     transparency but cannot exceed what it declared; a theme that ships `#CC101216` keeps that as
 *     its ceiling. What shows through is [GlassBackdrop]'s bloom and the user's wallpaper, which is
 *     why those are drawn at all.
 *  2. **A specular rim.** A one-pixel edge in the theme's own ink, and a brighter one along the top
 *     only, where a light above the device would catch a real pane. This is the single strongest
 *     glass cue and it costs one stroke.
 *  3. **The gloss.** A white gradient falling from the top edge to about two thirds of the way
 *     down the face, scaled by the theme's `gloss`. This is what reads as "polished" rather than
 *     "translucent".
 *  4. **The sheen.** The same highlight drifting slowly across the face on a long, slow loop — see
 *     [glassSheenCentre]. Motion is what separates a material from a colour, and one shared clock
 *     drives every panel in the app rather than one per panel.
 *  5. **The blur**, where the platform has one. The *backdrop* is softened by `blurRadiusDp` on API
 *     31 and up; below that the panels are drawn against a sharp backdrop and the material is
 *     everything else. [PluginGlass.usesBackdropBlur] is that question and the Control Center
 *     reports its answer rather than implying a blur that is not there.
 *
 * ## Why one shared clock
 *
 * The sheen and the settle are driven from [rememberGlassState], called once by `GameCoreTheme`, and
 * handed to every panel through [LocalGlassMaterial]. A per-panel `InfiniteTransition` would be one
 * animation per card on screen, all drifting out of phase with each other — which reads as noise, not
 * as light — and would cost a frame callback per card. One clock, in phase, is both cheaper and the
 * correct look.
 *
 * ## Everything here is bounded before it arrives
 *
 * [GlassState] only ever holds a [PluginGlass] that [com.gamecore.core.plugin.theme.PluginThemeCodec]
 * already clamped; the pure functions below clamp again on their own inputs, so a value that reached
 * the renderer by some other route still cannot ask for a 200% gloss or a fill alpha above one.
 */

/**
 * The live glass material plus the two clocks the panels read, or null when no glass theme is active.
 *
 * A composition local rather than a parameter because the panels that have to change are the app's
 * own cards, drawn a hundred call sites deep, and threading a material through every one of them
 * would be a change to the whole app for a feature none of those call sites know about. The local is
 * `compositionLocalOf` rather than `staticCompositionLocalOf` because its identity changes when the
 * user moves the Control Center slider, and that change has to reach the panels.
 *
 * Null is the common case and the only case for every theme before schema 4: a card that reads null
 * takes the branch it has always taken and allocates nothing new.
 */
val LocalGlassMaterial = compositionLocalOf<GlassState?> { null }

/**
 * The resolved material and its clocks.
 *
 * The two animated values are exposed as functions rather than as `State` properties so that reading
 * one *inside a draw block* subscribes the draw to it, while reading it in composition would
 * recompose. That distinction is the whole cost model of this file: a panel that read the phase in
 * composition would recompose sixty times a second for a highlight that only ever moves pixels.
 */
@Immutable
class GlassState(
    /** The theme's block, already scaled by the user's Control Center value. */
    val material: PluginGlass,
    /**
     * The theme's own `surface` colour, **alpha intact**.
     *
     * Not `MaterialTheme.colorScheme.surface`, which the bridge has already forced opaque: this is
     * the ceiling [glassFillAlpha] measures the material's transparency against, so a theme that
     * declared a translucent surface keeps it and one that declared an opaque surface cannot have
     * one conjured for it.
     */
    val surfaceArgb: Int,
    /** The theme's own `onSurface`, for the rim and the edge highlight. */
    val inkArgb: Int,
    /** Whether the platform can blur a backdrop at all — API 31 and up. */
    val blurAvailable: Boolean,
    private val sheen: State<Float>,
    private val settle: State<Float>,
) {
    /** Where the drifting highlight is, 0..1 across the face, right now. */
    fun sheenPhase(): Float = if (material.sheen) sheen.value else 0f

    /**
     * How far the material has settled toward its current intensity, 0..1.
     *
     * Reads 1 whenever the block names no spring or the user has animation off, so the common path
     * does no arithmetic it does not need. Between values it is the spring's own curve — see
     * [glassSpringOf] — which is what gives the Control Center slider its weight.
     */
    fun settleFraction(): Float = settle.value
}

/**
 * Builds the state [GameCoreTheme] publishes, or null when there is nothing to publish.
 *
 * [animationsEnabled] is the user's own veto, applied here rather than at each panel: a user who has
 * turned animation off gets the material at rest — the translucency, the rim, the gloss, all of it —
 * with a stationary highlight and no settle. That is the same bargain [GlitchLayer] makes, and it is
 * the reason the veto lives above the renderer instead of inside it.
 *
 * The settle tracks the material's *intensity*, so it fires when the Control Center slider moves and
 * settles once at launch. It is deliberately not keyed on the whole block: a theme switch replaces
 * the state rather than animating across two different designs, which is what a theme switch should
 * look like.
 */
@Composable
fun rememberGlassState(
    theme: PluginTheme?,
    scale: Float,
    animationsEnabled: Boolean,
): GlassState? = rememberGlassStateFor(
    // The user's Control Center position, applied here so that everything downstream — the panels,
    // the ceiling the fill is measured against, the blur radius — is already the material the user
    // dialled in. `scaledBy` is the only thing that moves a block along its own axis, and it is the
    // same call the store makes, so a slider and a theme cannot produce two different materials.
    material = theme?.resolvedGlass()?.scaledBy(scale),
    // The theme's raw colours, so the material's ceiling is what its author wrote rather than what
    // the Material bridge floored to opaque. Taken from the theme, not from the scheme.
    surfaceArgb = theme?.surface ?: OPAQUE_BLACK,
    inkArgb = theme?.onSurface ?: OPAQUE_WHITE,
    animationsEnabled = animationsEnabled,
)

/**
 * The same state over a material the caller supplies, for a preview that is not the app's own theme.
 *
 * The Control Center draws a panel from the material its slider is *currently* pointing at, which is
 * not yet the material the app is wearing — the write lands when the finger lifts. Using the real
 * clocks here rather than a still frame is the point: a preview that did not drift its highlight
 * would be a picture of the material rather than the material, and the reader would have no way to
 * judge the sheen's period from it.
 *
 * [theme] is used for its two raw colours and nothing else, so a preview can be drawn for a theme
 * that is not in force — which is what the Control Center needs, since it is reachable while the
 * theme it describes is the active one and must keep working while the user switches away from it.
 */
@Composable
fun rememberGlassPreviewState(
    material: PluginGlass?,
    theme: PluginTheme?,
    animationsEnabled: Boolean,
): GlassState? = rememberGlassPreviewState(
    material = material,
    surfaceArgb = theme?.surface ?: OPAQUE_BLACK,
    inkArgb = theme?.onSurface ?: OPAQUE_WHITE,
    animationsEnabled = animationsEnabled,
)

/**
 * The same preview over a pair of colours the caller supplies directly.
 *
 * The Control Center reaches this one: it draws the material its slider is pointing at over the
 * *screen's own* colours, because the screen can be open over any theme — including, briefly, none.
 * Handing it a [PluginTheme] would mean naming a theme that may not be the one the user is about to
 * see, and inventing a ceiling for the fill from a theme that is not on screen is exactly the kind of
 * confident wrong number this renderer's docs spend their time avoiding.
 */
@Composable
fun rememberGlassPreviewState(
    material: PluginGlass?,
    surfaceArgb: Int,
    inkArgb: Int,
    animationsEnabled: Boolean,
): GlassState? = rememberGlassStateFor(
    material = material,
    surfaceArgb = surfaceArgb,
    inkArgb = inkArgb,
    animationsEnabled = animationsEnabled,
)

/**
 * The one builder both entry points above go through.
 *
 * A material that asks for nothing visible resolves to null here rather than at each caller, which is
 * the single place "this theme is not wearing glass" is decided — so the shell, the panels and the
 * preview cannot disagree about it.
 */
@Composable
private fun rememberGlassStateFor(
    material: PluginGlass?,
    surfaceArgb: Int,
    inkArgb: Int,
    animationsEnabled: Boolean,
): GlassState? {
    if (material == null || !material.isVisible) return null
    val blurAvailable = Build.VERSION.SDK_INT >= PluginGlass.FIRST_BLUR_SDK

    // One infinite transition for the whole app, started only when the block actually asks for a
    // sheen and the user has not vetoed motion. `rememberInfiniteTransition` is created
    // unconditionally — inside a `remember` that returns a constant when idle — so the call order
    // in this composable never changes between a theme that sheens and one that does not.
    val sheen: State<Float> = if (material.sheen && animationsEnabled) {
        val transition = rememberInfiniteTransition(label = "glassSheen")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(material.sheenPeriodMs, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "glassSheen",
        )
    } else {
        remember { IdleFloat(0f) }
    }

    // The settle: a spring that follows the material's own intensity. It starts at nothing and is
    // pulled to full on the first frame, so a theme arriving settles into place; a Control Center
    // drag moves the target every frame, and `animateTo` cancels and restarts from wherever the
    // value currently is — a spring chasing a moving target, which is the feel the slider wants and
    // is deliberately *not* a `snapTo` per frame, which would strobe the whole app's panels.
    val settle = remember { Animatable(0f) }
    LaunchedEffect(material.intensity, material.spring, animationsEnabled) {
        if (!animationsEnabled || !material.spring.animates) {
            settle.snapTo(SETTLED)
            return@LaunchedEffect
        }
        settle.animateTo(SETTLED, glassSpringOf(material.spring, SETTLE_THRESHOLD))
    }

    return remember(material, surfaceArgb, inkArgb, blurAvailable, sheen, settle) {
        GlassState(
            material = material,
            surfaceArgb = surfaceArgb,
            inkArgb = inkArgb,
            blurAvailable = blurAvailable,
            sheen = sheen,
            settle = settle.asState(),
        )
    }
}

/** A [State] that never changes, for the paths where nothing animates. Allocates once, then never. */
@Immutable
private class IdleFloat(private val constant: Float) : State<Float> {
    override val value: Float get() = constant
}

/** The value the settle is pulled to: the material fully present. */
private const val SETTLED = 1f

/** Stand-ins for a preview drawn without a theme, where nothing is painted from either. */
private const val OPAQUE_BLACK = 0xFF000000.toInt()
private const val OPAQUE_WHITE = 0xFFFFFFFF.toInt()

/**
 * The panel modifier: everything in this file's contract, drawn behind a surface's content.
 *
 * Applied to a `Surface` whose own colour is transparent, so the `Surface` keeps the parts this
 * modifier cannot supply — the shape clip, the content colour, the click ripple and the role a
 * screen reader announces — while the pixels come from here.
 *
 * Returns `this` unchanged when no glass theme is active, so the branch a card takes today costs one
 * composition-local read and one identity comparison.
 */
@Composable
fun Modifier.glassPanel(glass: GlassState, shape: Shape): Modifier {
    val fillArgb = glass.surfaceArgb
    val inkArgb = glass.inkArgb
    val material = glass.material
    return this
        .clip(shape)
        .drawWithCache {
            // Everything that depends on size and nothing that depends on the clock is computed
            // here, in the cache block, which re-runs on a size or shape change and not per frame.
            val outline: Outline = shape.createOutline(size, layoutDirection, this)
            val path = Path().apply { addOutline(outline) }
            val rimWidth = 1.dp.toPx()
            val glossHeight = size.height * GLOSS_FACE_FRACTION
            val fill = Color(fillArgb)
            onDrawBehind {
                val settle = glass.settleFraction()
                // The settle scales the *presence* of the material, not its geometry: a panel that
                // has just been raised from nothing arrives as a thin film and thickens into glass,
                // which is what a spring is for. At rest this is 1 and every draw below is the plain
                // value.
                val presence = SETTLE_FLOOR + (1f - SETTLE_FLOOR) * settle
                val alpha = glassFillAlpha(fillArgb, material) * presence

                drawPath(path, fill.copy(alpha = alpha))

                // The gloss and the sheen are clipped to the shape: without this a gradient drawn
                // against the panel's bounding box would square off a rounded corner.
                clipPath(path) {
                    val glossColour = glassGlossColour(inkArgb, material, presence)
                    if (glossColour.alpha > 0f) {
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(glossColour, Color.Transparent),
                                startY = 0f,
                                endY = glossHeight,
                            ),
                            size = Size(size.width, glossHeight),
                        )
                    }
                    val sheenColour = glassSheenColour(material, presence)
                    if (sheenColour.alpha > 0f) {
                        val centre = glassSheenCentre(glass.sheenPhase())
                        drawRect(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    sheenColour,
                                    Color.Transparent,
                                ),
                                start = Offset(size.width * (centre - SHEEN_HALF_WIDTH), 0f),
                                end = Offset(size.width * (centre + SHEEN_HALF_WIDTH), size.height),
                            ),
                        )
                    }
                }

                // The rim last, over the gloss, so the edge stays crisp where the highlight meets it.
                drawPath(path, glassRimColour(inkArgb, material, presence), style = Stroke(rimWidth))
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(glassTopEdgeColour(inkArgb, material, presence), Color.Transparent),
                        startY = 0f,
                        endY = rimWidth * TOP_EDGE_FADE_STEPS,
                    ),
                    size = Size(size.width, rimWidth * TOP_EDGE_FADE_STEPS),
                )
            }
        }
}

/**
 * The ambient bloom the panels sit on, or nothing at all.
 *
 * Drawn under everything, inside the shell's own background `Surface`, so a translucent panel has
 * something to pick up. Without it "translucent" means "a slightly different shade of the same flat
 * colour", which is a tint and not a material — and a theme is allowed to be a material.
 *
 * Two soft radial blooms in the theme's own primary and accent, placed at opposite corners and
 * scaled by the material's intensity, over nothing: the shell's background is already painted, so
 * this only adds light. Where the user has chosen a wallpaper the wallpaper is drawn on top of this
 * and the blooms are hidden, which is correct — the wallpaper is then what shows through the glass.
 *
 * The blur is the one place `blurRadiusDp` becomes a real per-pixel filter, and only where the
 * platform has one. Below API 31 the blooms are drawn sharp, which they survive because they are
 * gradients: a gradient is already as soft as a blurred gradient, so the material loses nothing
 * visible and the Control Center says so.
 */
@Composable
fun GlassBackdrop(
    material: PluginGlass?,
    primaryColour: Color,
    accentColour: Color,
    modifier: Modifier = Modifier,
) {
    if (material == null || !material.isVisible) return
    val blurred = Build.VERSION.SDK_INT >= PluginGlass.FIRST_BLUR_SDK && material.blurRadiusDp > 0
    val blurRadius = material.blurRadiusDp.dp
    Box(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (blurred) {
                    Modifier.blur(radius = blurRadius, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                } else {
                    Modifier
                },
            )
            .drawWithCache {
                // Radii in pixels, from the material's own number, so the bloom's spread is the same
                // quantity the blur would have used rather than a second, unrelated constant.
                val spread = (material.blurRadiusDp.dp.toPx() * BLOOM_SPREAD_FACTOR)
                    .coerceAtLeast(size.minDimension * MIN_BLOOM_FRACTION)
                val intensity = material.intensity
                val glowFraction = size.minDimension * BLOOM_RADIUS_FRACTION
                onDrawBehind {
                    val radius = glowFraction
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                primaryColour.copy(alpha = BLOOM_ALPHA * intensity),
                                Color.Transparent,
                            ),
                            center = Offset(spread, spread),
                            radius = radius,
                        ),
                        radius = radius,
                        center = Offset(spread, spread),
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                accentColour.copy(alpha = BLOOM_ALPHA * intensity),
                                Color.Transparent,
                            ),
                            center = Offset(size.width - spread, size.height - spread),
                            radius = radius,
                        ),
                        radius = radius,
                        center = Offset(size.width - spread, size.height - spread),
                    )
                }
            },
    )
}

// ------------------------------------------------------------------------------------ the arithmetic

/**
 * The alpha a panel's fill is drawn at: the theme's own ceiling, thinned by the material.
 *
 * The ceiling is what the theme declared — `#CC101216` caps at 0.8 whatever the block says — held up
 * to [MIN_FILL_ALPHA] so a theme that shipped a practically invisible surface still gets a panel
 * rather than a ghost. What the material may do to that ceiling is *remove*: transparency thins it,
 * and [MIN_FILL_ALPHA] is the floor it can be thinned to and no further.
 *
 * That floor is the one place the block cannot go, and it is not decoration: the ink a theme chose
 * for its surface was measured against that surface, so a panel drawn at zero alpha is an invisible
 * card with live text floating on the wallpaper. A user may ask for as little material as they like;
 * they cannot ask for an unreadable screen.
 */
internal fun glassFillAlpha(surfaceArgb: Int, glass: PluginGlass): Float {
    val ceiling = (((surfaceArgb ushr 24) and 0xFF) / 255f).coerceAtLeast(MIN_FILL_ALPHA)
    val thinned = ceiling * (1f - glass.transparency.coerceIn(0f, 1f))
    return thinned.coerceIn(MIN_FILL_ALPHA, ceiling)
}

/**
 * The colour of the gloss falling from the top edge.
 *
 * White rather than the theme's ink, because a gloss is reflected light and light is not the theme's
 * colour — it is what a bright room does to a pane whatever the pane is tinted. Scaled by `gloss`
 * and by the panel's current presence, and capped at [MAX_GLOSS_ALPHA]: past that the gradient stops
 * reading as a reflection and starts washing the content underneath it out.
 *
 * A theme on a *light* palette gets a weaker gloss, not a darker one. A white wash on a pale surface
 * does nothing visible, so the number is scaled down rather than inverted — inventing a shadow for
 * the light case would be this file deciding something the theme never asked for.
 */
internal fun glassGlossColour(inkArgb: Int, glass: PluginGlass, presence: Float): Color {
    val gloss = (glass.gloss.coerceIn(0f, 1f) * presence.coerceIn(0f, 1f))
        .coerceIn(0f, 1f)
    val light = isLightInk(inkArgb)
    val ceiling = if (light) MAX_GLOSS_ALPHA * LIGHT_PALETTE_GLOSS_SCALE else MAX_GLOSS_ALPHA
    return Color.White.copy(alpha = (gloss * ceiling).coerceIn(0f, ceiling))
}

/** The drifting highlight's colour: the same light as [glassGlossColour], much fainter. */
internal fun glassSheenColour(glass: PluginGlass, presence: Float): Color {
    if (!glass.sheen) return Color.Transparent
    val gloss = glass.gloss.coerceIn(0f, 1f) * presence.coerceIn(0f, 1f)
    return Color.White.copy(alpha = (gloss * MAX_SHEEN_ALPHA).coerceIn(0f, MAX_SHEEN_ALPHA))
}

/** The hairline edge: the theme's own ink at a low, gloss-scaled alpha so it sits inside the palette. */
internal fun glassRimColour(inkArgb: Int, glass: PluginGlass, presence: Float): Color {
    val gloss = glass.gloss.coerceIn(0f, 1f) * presence.coerceIn(0f, 1f)
    val alpha = (RIM_BASE_ALPHA + RIM_GLOSS_ALPHA * gloss).coerceIn(RIM_BASE_ALPHA, RIM_BASE_ALPHA + RIM_GLOSS_ALPHA)
    return Color(inkArgb).copy(alpha = alpha)
}

/** The brighter line along the top edge, where a light above the device catches the pane. */
internal fun glassTopEdgeColour(inkArgb: Int, glass: PluginGlass, presence: Float): Color {
    val gloss = glass.gloss.coerceIn(0f, 1f) * presence.coerceIn(0f, 1f)
    return Color.White.copy(alpha = (gloss * MAX_TOP_EDGE_ALPHA).coerceIn(0f, MAX_TOP_EDGE_ALPHA))
}

/**
 * Where the drifting highlight is on the face, given [phase] from 0 to 1.
 *
 * Runs from [SHEEN_ENTRY] to [SHEEN_EXIT] — both outside 0..1 — so the highlight is fully off the
 * panel at each end of the loop and the restart is invisible. A sweep that turned around inside the
 * face would read as a bounce; one that started at the left edge would pop.
 */
internal fun glassSheenCentre(phase: Float): Float =
    SHEEN_ENTRY + (SHEEN_EXIT - SHEEN_ENTRY) * phase.coerceIn(0f, 1f)

/**
 * The Compose spring a block's named spring resolves to.
 *
 * The two numbers come from [PluginGlass.Spring], which is pure Kotlin and unit-tested; this is the
 * one-line translation into the animation library. `visibilityThreshold` is supplied by the caller
 * because what counts as "arrived" depends on what is being animated.
 */
internal fun glassSpringOf(settle: PluginGlass.Spring, visibilityThreshold: Float): SpringSpec<Float> =
    spring(
        dampingRatio = settle.dampingRatio,
        stiffness = settle.stiffness,
        visibilityThreshold = visibilityThreshold,
    )
/**
 * Whether a colour is a light one, by its perceived luminance.
 *
 * Uses the same Rec. 709 coefficients the rest of the app's contrast arithmetic does, on 8-bit
 * channels — the space these ratios are defined in. Only ever asked about an ink, and only to decide
 * whether a white gloss will be visible against it.
 */
internal fun isLightInk(argb: Int): Boolean {
    val r = ((argb ushr 16) and 0xFF) / 255f
    val g = ((argb ushr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    return (LUMA_R * r + LUMA_G * g + LUMA_B * b) > LIGHT_INK_THRESHOLD
}

/** Rec. 709 coefficients, as fractions: the same ones `ContrastMath` measures in. */
private const val LUMA_R = 0.2126f
private const val LUMA_G = 0.7152f
private const val LUMA_B = 0.0722f

/** Above this perceived luminance an ink counts as light, and the gloss is weakened for it. */
private const val LIGHT_INK_THRESHOLD = 0.6f

/**
 * The floor a panel's own colour is held to, and the floor its fill is thinned to.
 *
 * 0.35 keeps a panel's surface present enough that the ink the theme chose for it still sits on the
 * theme's own design rather than on the wallpaper. Two ways of reading it, and both are true: a
 * theme whose declared `surface` is more transparent than this is drawn at this, and a material
 * whose transparency would thin the fill past this stops here. A theme may ask for a very translucent
 * panel; it cannot ask for an invisible card with live text on it.
 */
internal const val MIN_FILL_ALPHA = 0.35f

/** How far down the face the gloss falls, as a fraction of the panel's height. */
private const val GLOSS_FACE_FRACTION = 0.62f

/** Ceiling on the gloss, before the light-palette weakening. */
private const val MAX_GLOSS_ALPHA = 0.22f

/** The ceiling on the sheen: a sixth of the gloss, because it sweeps rather than sits. */
private const val MAX_SHEEN_ALPHA = 0.05f

/** How much of the gloss a light palette gets. A white wash on a pale face is barely visible anyway. */
private const val LIGHT_PALETTE_GLOSS_SCALE = 0.45f

private const val RIM_BASE_ALPHA = 0.10f
private const val RIM_GLOSS_ALPHA = 0.16f
private const val MAX_TOP_EDGE_ALPHA = 0.30f

/** How many rim-widths the top edge highlight fades over. Three is a hairline, not a bar. */
private const val TOP_EDGE_FADE_STEPS = 3f

/** The highlight's half-width as a fraction of the panel, and its travel across and past the face. */
private const val SHEEN_HALF_WIDTH = 0.28f
private const val SHEEN_ENTRY = -0.45f
private const val SHEEN_EXIT = 1.45f

/** The bloom's radius as a fraction of the smaller screen dimension, and its inset from the corners. */
private const val BLOOM_RADIUS_FRACTION = 0.85f
private const val BLOOM_SPREAD_FACTOR = 2.4f
private const val MIN_BLOOM_FRACTION = 0.05f
private const val BLOOM_ALPHA = 0.30f

/** A spring aimed at an alpha is arrived at well before the default threshold would say so. */
private const val SETTLE_THRESHOLD = 0.01f

/**
 * How much of the material is present when the settle is at zero.
 *
 * Not zero, and the difference matters: the settle runs on the material's *intensity*, so a user who
 * drags the Control Center slider to nothing would otherwise watch every panel in the app vanish
 * outright. At [SETTLE_FLOOR] the panel is a thin film of its own colour — still a card, still
 * readable — and the spring's job is the travel from there to full.
 */
private const val SETTLE_FLOOR = 0.25f
