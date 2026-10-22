package com.gamecore.core.plugin.theme

/**
 * The schema-4 material contract: what a theme may ask the host to make its panels out of.
 *
 * A theme could already recolour and reshape, and schema 3 let it ask for motion. This block is the
 * third thing a surface can be, and the one the house schemes could never express: *material*. It
 * asks for panels that let light through, catch a highlight along their top edge, carry a highlight
 * that drifts slowly across the face, and settle into place with the weight of a physical object.
 *
 * Like [PluginGlitch] this is a request, not a program. The theme names a degree — how transparent,
 * how glossy, how blurred, how it should land — and the host owns what that becomes on the device in
 * the user's hand. Nothing here reaches the canvas, the window manager or the frame clock, and a
 * `.gcplugin` stays data the whole way down.
 *
 * **The intensity control is a setting.** [intensitySetting] names one of the plugin's own settings —
 * the slider the host already renders in the plugin's detail screen — and the host reads that value
 * live and scales the material by it. That is the whole mechanism behind the Control Center: no new
 * permission, no code, no host API for a package to call. [PluginThemeCodec] refuses a block whose
 * setting the plugin does not declare, or declares as something other than a number, because both
 * failures would look to the user like a slider that does nothing.
 *
 * **The platform is not uniform, and the type says so.** A real backdrop blur is an Android 12
 * feature; below that the host still renders translucency, the rim, the face highlight and the
 * sheen, and the surface reads as glass against a sharp backdrop. [blurRadiusDp] is what the theme
 * asks for, and [usesBackdropBlur] is what the host must be told the device can actually do — the
 * Control Center says which of the two the user is looking at rather than implying the blur is
 * there when it is not.
 *
 * Pure Kotlin, no Android, so the bounds are unit-tested on the JVM.
 */
data class PluginGlass(
    /** How far every other number leans toward its maximum, 0..1, before [intensitySetting] is read. */
    val intensity: Float = DEFAULT_INTENSITY,

    /**
     * How much of the surface's own alpha is removed, 0..1.
     *
     * This only ever *removes*: a theme whose colours are opaque stays opaque here, and one that
     * ships `#CC101216` keeps that as its ceiling. A material that could add alpha would let the
     * block contradict the palette its author wrote beside it, which is the one thing a theme is
     * not allowed to do to itself. The host floors the result at its own minimum fill alpha — a
     * panel may be thinned to a film and no further, because the ink the theme chose for that
     * surface was measured against it and a card drawn at nothing is a card that cannot be read.
     */
    val transparency: Float = DEFAULT_TRANSPARENCY,

    /** The specular rim along the top edge and the highlight falling across the face, 0..1. */
    val gloss: Float = DEFAULT_GLOSS,

    /** The backdrop blur radius in dp, 0..32. Ignored on a device whose OS cannot blur. */
    val blurRadiusDp: Int = DEFAULT_BLUR_DP,

    /** Drift a highlight slowly across each panel, so the surface reads as glass and not as a fill. */
    val sheen: Boolean = true,

    /** One full drift of that highlight, in milliseconds. */
    val sheenPeriodMs: Int = DEFAULT_SHEEN_MS,

    /** How a panel settles when the material changes. */
    val spring: Spring = Spring.SNAPPY,

    /**
     * Let a hot or busy device thin the material rather than spend its frame budget on glass.
     *
     * On, a device the platform reports as throttled gets a shorter blur and a slower sheen. Off,
     * the effect reads no sensors at all. Default off: unlike a glitch — where the device's own
     * struggle *is* the effect — glass is a finish, and a finish that visibly degrades because the
     * phone got warm is a bug the user would rightly report.
     */
    val hardwareReactive: Boolean = false,

    /**
     * The id of one of this plugin's own settings to scale [intensity] by, or null for a fixed value.
     *
     * The stored value is read on the host's side and multiplied into [intensity]; the setting's own
     * declared bounds are what the slider offers the user, so a plugin that wants a 0..1 control
     * declares `float("glassIntensity", default = 0.7, min = 0.0, max = 1.0)` and names it here.
     */
    val intensitySetting: String? = null,
) {
    /**
     * This block with every value back inside the format's bounds.
     *
     * The codec already clamps on the way in, so this is the same belt-and-braces the corner radius
     * and the glitch block get: a value that reached the renderer by some other route still cannot
     * ask for a 200dp blur or a 40ms strobe.
     */
    fun clamped(): PluginGlass = copy(
        intensity = intensity.coerceIn(MIN_INTENSITY, MAX_INTENSITY),
        transparency = transparency.coerceIn(MIN_TRANSPARENCY, MAX_TRANSPARENCY),
        gloss = gloss.coerceIn(MIN_GLOSS, MAX_GLOSS),
        blurRadiusDp = blurRadiusDp.coerceIn(MIN_BLUR_DP, MAX_BLUR_DP),
        sheenPeriodMs = sheenPeriodMs.coerceIn(MIN_SHEEN_MS, MAX_SHEEN_MS),
        intensitySetting = intensitySetting?.trim()?.takeIf { it.isNotEmpty() },
    )

    /**
     * This block scaled to [factor] — the user's Control Center value.
     *
     * Scales the three quantities that read as *how much glass* rather than re-running the block
     * through its own clamps: intensity, transparency and the blur radius all move together, while
     * the sheen's period and the spring do not. That asymmetry is the point. A user dragging the
     * control is asking for more or less material; they are not asking for the animation to speed up
     * into a strobe or for the panel to start landing differently under their finger.
     */
    fun scaledBy(factor: Float): PluginGlass {
        val f = factor.coerceIn(MIN_INTENSITY, MAX_INTENSITY)
        return copy(
            intensity = (intensity * f).coerceIn(MIN_INTENSITY, MAX_INTENSITY),
            transparency = (transparency * f).coerceIn(MIN_TRANSPARENCY, MAX_TRANSPARENCY),
            blurRadiusDp = (blurRadiusDp * f).toInt().coerceIn(MIN_BLUR_DP, MAX_BLUR_DP),
        )
    }

    /**
     * Whether this block asks for anything visible at all.
     *
     * A theme that has faded every quantity to its floor asked for the *idea* of glass and none of
     * the material, which is legal and should cost nothing: there is no alpha to composite, no rim
     * to draw and no layer to record.
     */
    val isVisible: Boolean
        get() = intensity > 0f && (transparency > 0f || gloss > 0f || blurRadiusDp > 0)

    /**
     * Whether the backdrop blur this block asks for is one the device can actually draw.
     *
     * Android applies a real blur to a layer from API 31. Below that the host renders everything
     * else in this block and leaves the backdrop sharp — so this is the honest answer to "is the
     * frosted part of this material present", and it is what the Control Center reports.
     */
    fun usesBackdropBlur(sdkInt: Int): Boolean = blurRadiusDp > 0 && sdkInt >= FIRST_BLUR_SDK

    /**
     * How a panel settles, named for the feel rather than the numbers.
     *
     * The numbers are here because the host's renderer needs them and because they are pure maths —
     * no Android — so a unit test can pin them. They are the host's to choose; a plugin only ever
     * names one of the four.
     */
    enum class Spring(
        val id: String,
        /** 1.0 is critically damped: no overshoot. Below that the panel passes its target and returns. */
        val dampingRatio: Float,
        /** How hard it is pulled home. Higher is quicker and tighter. */
        val stiffness: Float,
    ) {
        /** No motion. The material changes in place, which is what an animations-off user gets anyway. */
        NONE("none", 1f, STIFFNESS_MEDIUM),

        /** Critically damped and unhurried: the panel arrives and stops. The iOS "settle". */
        GENTLE("gentle", 1f, STIFFNESS_LOW),

        /** A little give, a quick arrival. The default, and the one that reads as a switch. */
        SNAPPY("snappy", 0.85f, STIFFNESS_MEDIUM),

        /** Passes the target and comes back: the elastic feel of a sheet being dismissed. */
        BOUNCY("bouncy", 0.6f, STIFFNESS_MEDIUM),

        ;

        /** Whether this spring moves anything. The renderer checks this before starting a frame loop. */
        val animates: Boolean get() = this != NONE

        companion object {
            fun fromId(raw: String?): Spring? =
                entries.firstOrNull { it.id == raw?.trim()?.lowercase() }
        }
    }

    companion object {
        const val MIN_INTENSITY = 0f
        const val MAX_INTENSITY = 1f
        const val DEFAULT_INTENSITY = 0.7f

        const val MIN_TRANSPARENCY = 0f
        const val MAX_TRANSPARENCY = 1f
        const val DEFAULT_TRANSPARENCY = 0.55f

        const val MIN_GLOSS = 0f
        const val MAX_GLOSS = 1f
        const val DEFAULT_GLOSS = 0.45f

        /**
         * Past 32dp a backdrop is unreadable mush on a phone-sized panel, and the layer the blur
         * costs starts to show up in the frame time on the devices that can draw it at all.
         */
        const val MIN_BLUR_DP = 0
        const val MAX_BLUR_DP = 32
        const val DEFAULT_BLUR_DP = 16

        /** Faster than 1.5s the drift stops reading as light and starts reading as a strobe. */
        const val MIN_SHEEN_MS = 1500
        const val MAX_SHEEN_MS = 20000
        const val DEFAULT_SHEEN_MS = 6000

        /** The first Android release that blurs a layer's backdrop. */
        const val FIRST_BLUR_SDK = 31

        /** Spring physics, in the units Compose's `spring()` takes. */
        const val STIFFNESS_LOW = 200f
        const val STIFFNESS_MEDIUM = 400f

        /** Everything at its default, for the SDK and for tests that only care about one field. */
        val DEFAULT = PluginGlass()
    }
}

/**
 * The material block [this] theme asked for, clamped — or null when it asked for none.
 *
 * Null covers two cases that mean the same thing to a renderer: a theme with no `glass` block at all
 * (every theme before schema 4, and every v4 theme that took only the palette or the motion), and
 * one whose every quantity has been faded to its floor — which is a legal way of asking for the
 * *idea* of glass and none of the material, and should therefore cost nothing.
 *
 * Lives here rather than beside the other theme-to-renderer bridges because two layers need it: the
 * UI, which draws from it, and the store that scales it by the user's Control Center value before
 * the UI ever sees it. One definition, so the two cannot disagree about what "no material" means.
 *
 * What it deliberately does *not* do is apply that Control Center value. The number lives in the
 * plugin's own settings store rather than in the theme file, and [PluginGlass.scaledBy] is what
 * applies it — at the one place that can see both.
 */
fun PluginTheme.resolvedGlass(): PluginGlass? = glass?.clamped()?.takeIf { it.isVisible }
