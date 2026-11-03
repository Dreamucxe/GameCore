package com.gamecore.studio.core

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A colour in hue/saturation/value form.
 *
 * [hue] is in degrees, `0..360`; [saturation] and [value] are fractions, `0..1`.
 */
data class Hsv(val hue: Float, val saturation: Float, val value: Float)

/**
 * A colour in hue/saturation/lightness form.
 *
 * [hue] is in degrees, `0..360`; [saturation] and [lightness] are fractions, `0..1`.
 */
data class Hsl(val hue: Float, val saturation: Float, val lightness: Float)

/** An 8-bit sRGB colour. Alpha is held separately by the ARGB long. */
data class Rgb(val red: Int, val green: Int, val blue: Int)

/**
 * The three common colour-vision deficiencies this file can approximate.
 *
 * These names describe a simulation, not a diagnosis: real colour vision varies between
 * people with the same label, and a simulated palette is not a medical or accessibility
 * certificate. Use the values as a design prompt, then test the theme with people.
 */
enum class ColorVision { PROTANOPIA, DEUTERANOPIA, TRITANOPIA }

/**
 * Colour arithmetic for the studio.
 *
 * Colours are ARGB longs, the same representation [com.gamecore.studio.model.ThemeDraft]
 * uses: the top byte is alpha, then red, green and blue. Colour components are sRGB, not
 * linear RGB, except inside [relativeLuminance] and [simulate] where the WCAG and LMS
 * formulas require linearisation.
 *
 * The formulas are deliberately small and dependency-free so the editor can run them on
 * every slider drag on the JVM:
 *
 *  - HSV and HSL use the standard hexagonal sector formulas.
 *  - [relativeLuminance] is WCAG 2.x: linearise each channel, then
 *    `0.2126 R + 0.7152 G + 0.0722 B`.
 *  - [contrastRatio] is `(lighter + 0.05) / (darker + 0.05)`.
 *  - [simulate] uses the Vienot 1999 LMS approximations in linear sRGB.
 *
 * Nothing here reads or writes Android state, so it is safe in plain JUnit tests.
 */
object ColorMath {

    /** The alpha value of an opaque colour. */
    const val OPAQUE: Int = 255

    private const val OPAQUE_WHITE = 0xFFFFFFFFL
    private const val OPAQUE_BLACK = 0xFF000000L

    /** The sRGB channels of [color]. Alpha is ignored. */
    fun rgb(color: Long): Rgb {
        val value = color and 0xFFFFFFFFL
        return Rgb(
            red = ((value ushr 16) and 0xFFL).toInt(),
            green = ((value ushr 8) and 0xFFL).toInt(),
            blue = (value and 0xFFL).toInt(),
        )
    }

    /** The alpha byte of [color], `0..255`. */
    fun alpha(color: Long): Int = ((color ushr 24) and 0xFFL).toInt()

    /**
     * Packs the four channels into an ARGB long.
     *
     * Each channel is clamped into `0..255`, so a slider that overshoots by a fraction of a
     * pixel cannot wrap around into a different colour.
     */
    fun argb(red: Int, green: Int, blue: Int, alpha: Int = OPAQUE): Long =
        (alpha.coerceIn(0, 255).toLong() shl 24) or
            (red.coerceIn(0, 255).toLong() shl 16) or
            (green.coerceIn(0, 255).toLong() shl 8) or
            blue.coerceIn(0, 255).toLong()

    /** [color] with its alpha replaced. The RGB channels are untouched. */
    fun withAlpha(color: Long, alpha: Int): Long =
        argb(rgb(color).red, rgb(color).green, rgb(color).blue, alpha)

    // ------------------------------------------------------------------------- HSV and HSL

    /** Converts [color] to HSV. A grey colour has hue `0` and saturation `0`. */
    fun hsv(color: Long): Hsv {
        val c = rgb(color)
        val r = c.red / 255.0
        val g = c.green / 255.0
        val b = c.blue / 255.0
        val hi = max(r, max(g, b))
        val lo = min(r, min(g, b))
        val delta = hi - lo
        val saturation = if (hi <= 0.0) 0.0 else delta / hi
        return Hsv(
            hue = hueOf(hi, delta, r, g, b).toFloat(),
            saturation = saturation.toFloat().coerceIn(0f, 1f),
            value = hi.toFloat().coerceIn(0f, 1f),
        )
    }

    /** Converts [hsv] back to an ARGB colour, carrying [alpha]. */
    fun fromHsv(hsv: Hsv, alpha: Int = OPAQUE): Long {
        val chroma = hsv.value.coerceIn(0f, 1f) * hsv.saturation.coerceIn(0f, 1f)
        return fromSector(
            hue = hsv.hue,
            chroma = chroma.toDouble(),
            offset = (hsv.value.coerceIn(0f, 1f) - chroma).toDouble(),
            alpha = alpha,
        )
    }

    /** Converts [color] to HSL. A grey colour has hue `0` and saturation `0`. */
    fun hsl(color: Long): Hsl {
        val c = rgb(color)
        val r = c.red / 255.0
        val g = c.green / 255.0
        val b = c.blue / 255.0
        val hi = max(r, max(g, b))
        val lo = min(r, min(g, b))
        val delta = hi - lo
        val lightness = (hi + lo) / 2.0
        val denominator = 1.0 - abs(2.0 * lightness - 1.0)
        val saturation = if (delta == 0.0 || denominator == 0.0) 0.0 else delta / denominator
        return Hsl(
            hue = hueOf(hi, delta, r, g, b).toFloat(),
            saturation = saturation.toFloat().coerceIn(0f, 1f),
            lightness = lightness.toFloat().coerceIn(0f, 1f),
        )
    }

    /** Converts [hsl] back to an ARGB colour, carrying [alpha]. */
    fun fromHsl(hsl: Hsl, alpha: Int = OPAQUE): Long {
        val chroma = (1.0 - abs(2.0 * hsl.lightness.coerceIn(0f, 1f) - 1.0)) *
            hsl.saturation.coerceIn(0f, 1f).toDouble()
        return fromSector(
            hue = hsl.hue,
            chroma = chroma,
            offset = hsl.lightness.coerceIn(0f, 1f) - chroma / 2.0,
            alpha = alpha,
        )
    }

    // ------------------------------------------------------------------------- compositing

    /**
     * Source-over compositing: [foreground] painted on top of [background].
     *
     * The result is opaque. The background's own alpha is taken into account while mixing,
     * then the mixed colour is normalised so the caller gets an RGB value rather than a
     * partially transparent one. For an opaque background this is the familiar
     * `fg * fgA + bg * (1 - fgA)`.
     */
    fun blendOver(foreground: Long, background: Long): Long {
        val fg = rgb(foreground)
        val bg = rgb(background)
        val fgAlpha = alpha(foreground) / 255.0
        val bgAlpha = alpha(background) / 255.0
        val outAlpha = fgAlpha + bgAlpha * (1.0 - fgAlpha)
        if (outAlpha <= 0.0) return OPAQUE_BLACK
        fun mix(fgChannel: Int, bgChannel: Int): Int =
            ((fgChannel * fgAlpha + bgChannel * bgAlpha * (1.0 - fgAlpha)) / outAlpha)
                .roundToInt()
                .coerceIn(0, 255)
        return argb(
            red = mix(fg.red, bg.red),
            green = mix(fg.green, bg.green),
            blue = mix(fg.blue, bg.blue),
            alpha = OPAQUE,
        )
    }

    // ------------------------------------------------------------------------- contrast

    /**
     * The WCAG 2.x relative luminance of [color]'s RGB channels, `0..1`.
     *
     * Alpha is ignored: pass an opaque colour, or use [contrastRatio], which flattens both
     * colours before measuring them. A translucent colour has no single luminance until its
     * backdrop is known.
     */
    fun relativeLuminance(color: Long): Double {
        val c = rgb(color)
        return 0.2126 * linearize(c.red) +
            0.7152 * linearize(c.green) +
            0.0722 * linearize(c.blue)
    }

    /**
     * The WCAG contrast ratio between [foreground] and [background], `1..21`.
     *
     * The foreground is composited over the background first; a translucent background is
     * itself composited over white so both inputs describe actual opaque pixels. This is why
     * a translucent ink can pass on one backdrop and fail on another even though its ARGB
     * value never changed.
     */
    fun contrastRatio(foreground: Long, background: Long): Double {
        val backdrop = blendOver(background, OPAQUE_WHITE)
        val ink = blendOver(foreground, backdrop)
        val a = relativeLuminance(ink)
        val b = relativeLuminance(backdrop)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    /** Black or white, whichever reads better on [background]. */
    fun readableOn(background: Long): Long =
        if (contrastRatio(OPAQUE_BLACK, background) >= contrastRatio(OPAQUE_WHITE, background)) {
            OPAQUE_BLACK
        } else {
            OPAQUE_WHITE
        }

    // ------------------------------------------------------------------------- palette moves

    /** Mixes [color] toward white by [amount] (`0..1`), keeping alpha. */
    fun lighten(color: Long, amount: Float): Long {
        val t = amount.coerceIn(0f, 1f)
        val c = rgb(color)
        return argb(
            red = (c.red + (255 - c.red) * t).roundToInt(),
            green = (c.green + (255 - c.green) * t).roundToInt(),
            blue = (c.blue + (255 - c.blue) * t).roundToInt(),
            alpha = alpha(color),
        )
    }

    /** Mixes [color] toward black by [amount] (`0..1`), keeping alpha. */
    fun darken(color: Long, amount: Float): Long {
        val t = amount.coerceIn(0f, 1f)
        val c = rgb(color)
        return argb(
            red = (c.red * (1f - t)).roundToInt(),
            green = (c.green * (1f - t)).roundToInt(),
            blue = (c.blue * (1f - t)).roundToInt(),
            alpha = alpha(color),
        )
    }

    /** The hue rotated by 180 degrees, keeping saturation, lightness and alpha. */
    fun complementary(color: Long): Long {
        val h = hsv(color)
        return fromHsv(h.copy(hue = (h.hue + 180f) % 360f), alpha(color))
    }

    /** The three-colour analogue scheme at `-30`, `0` and `+30` degrees. */
    fun analogous(color: Long): List<Long> {
        val h = hsv(color)
        fun shift(degrees: Float): Long =
            fromHsv(h.copy(hue = ((h.hue + degrees) % 360f + 360f) % 360f), alpha(color))
        return listOf(shift(-30f), color, shift(30f))
    }

    /**
     * Five steps of the same hue from darker to lighter.
     *
     * The lightness levels are fixed (`0.12`, `0.28`, `0.45`, `0.65`, `0.85`) rather than
     * offsets from the source lightness, so a colour near either end still produces five
     * distinct swatches instead of collapsing into duplicates. Alpha is preserved.
     */
    fun monochromatic(color: Long): List<Long> {
        val h = hsl(color)
        return listOf(0.12f, 0.28f, 0.45f, 0.65f, 0.85f).map { lightness ->
            fromHsl(h.copy(lightness = lightness), alpha(color))
        }
    }

    // ------------------------------------------------------------------------- colour vision

    /**
     * Approximates how [color] might look with [vision], preserving alpha.
     *
     * The conversion linearises sRGB, applies the Vienot 1999 LMS matrix for the named
     * deficiency, then encodes back to sRGB. These matrices are one common approximation,
     * not a medical tool: the result is a design aid for spotting colour-only distinctions,
     * not a diagnosis of any person's vision.
     */
    fun simulate(color: Long, vision: ColorVision): Long {
        val c = rgb(color)
        val r = linearize(c.red)
        val g = linearize(c.green)
        val b = linearize(c.blue)
        val matrix = when (vision) {
            ColorVision.PROTANOPIA -> PROTANOPIA
            ColorVision.DEUTERANOPIA -> DEUTERANOPIA
            ColorVision.TRITANOPIA -> TRITANOPIA
        }
        val outRed = matrix[0][0] * r + matrix[0][1] * g + matrix[0][2] * b
        val outGreen = matrix[1][0] * r + matrix[1][1] * g + matrix[1][2] * b
        val outBlue = matrix[2][0] * r + matrix[2][1] * g + matrix[2][2] * b
        return argb(
            red = encodeSrgb(outRed),
            green = encodeSrgb(outGreen),
            blue = encodeSrgb(outBlue),
            alpha = alpha(color),
        )
    }

    // ------------------------------------------------------------------------- hex

    /**
     * The colour as `#RRGGBB`, or `#AARRGGBB` when it carries an alpha below opaque.
     *
     * This is the same pair of forms [com.gamecore.studio.core.PluginComposer] writes, so a
     * value copied out of the colour picker and a value read back from a package agree.
     */
    fun hex(color: Long): String {
        val value = color and 0xFFFFFFFFL
        val alpha = (value ushr 24) and 0xFFL
        val rgb = value and 0xFFFFFFL
        return if (alpha == 0xFFL) {
            "#${digits(rgb, 6)}"
        } else {
            "#${digits(value, 8)}"
        }
    }

    /**
     * Parses `#RGB`, `#RRGGBB` or `#AARRGGBB` (the leading `#` is optional).
     *
     * `#RGB` expands each nibble in the CSS style: `#F84` is `#FF8844`. Six digits are an
     * opaque colour; eight digits carry alpha in the leading byte. Anything else, including
     * a non-hex character, returns null rather than a silently repaired colour.
     */
    fun parseHex(text: String): Long? {
        val trimmed = text.trim()
        val body = if (trimmed.startsWith("#")) trimmed.substring(1) else trimmed
        if (body.isEmpty() || body.any { it.digitToIntOrNull(16) == null }) return null
        return when (body.length) {
            3 -> {
                val r = body[0].digitToInt(16) * 17
                val g = body[1].digitToInt(16) * 17
                val b = body[2].digitToInt(16) * 17
                argb(r, g, b)
            }
            6 -> 0xFF000000L or body.toLong(16)
            8 -> body.toLong(16)
            else -> null
        }
    }

    // ------------------------------------------------------------------------- internals

    /** The hue of the max/min/delta triple, in `0..360`, shared by HSV and HSL. */
    private fun hueOf(hi: Double, delta: Double, r: Double, g: Double, b: Double): Double {
        if (delta == 0.0) return 0.0
        val raw = when (hi) {
            r -> 60.0 * (((g - b) / delta) % 6.0)
            g -> 60.0 * (((b - r) / delta) + 2.0)
            else -> 60.0 * (((r - g) / delta) + 4.0)
        }
        return ((raw % 360.0) + 360.0) % 360.0
    }

    /**
     * The hexagonal-sector conversion shared by HSV and HSL.
     *
     * [chroma] is `C` and [offset] is the value added to every channel (`m` in the usual
     * formulation). Keeping one implementation means the two spaces cannot disagree about
     * which sector a hue falls into.
     */
    private fun fromSector(hue: Float, chroma: Double, offset: Double, alpha: Int): Long {
        val h = if (hue.isFinite()) ((hue % 360f) + 360f) % 360f else 0f
        val sectorPosition = h / 60.0
        val sector = floor(sectorPosition).toInt() % 6
        val x = chroma * (1.0 - abs(sectorPosition % 2.0 - 1.0))
        val (r, g, b) = when (sector) {
            0 -> Triple(chroma, x, 0.0)
            1 -> Triple(x, chroma, 0.0)
            2 -> Triple(0.0, chroma, x)
            3 -> Triple(0.0, x, chroma)
            4 -> Triple(x, 0.0, chroma)
            else -> Triple(chroma, 0.0, x)
        }
        return argb(
            red = ((r + offset) * 255.0).roundToInt().coerceIn(0, 255),
            green = ((g + offset) * 255.0).roundToInt().coerceIn(0, 255),
            blue = ((b + offset) * 255.0).roundToInt().coerceIn(0, 255),
            alpha = alpha,
        )
    }

    /** The WCAG inverse gamma for one sRGB channel, `0..1`. */
    private fun linearize(channel: Int): Double {
        val s = channel / 255.0
        return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }

    /** The sRGB gamma for one linear channel, clamped and rounded to a byte. */
    private fun encodeSrgb(linear: Double): Int {
        val clamped = linear.coerceIn(0.0, 1.0)
        val s = if (clamped <= 0.0031308) 12.92 * clamped else 1.055 * clamped.pow(1.0 / 2.4) - 0.055
        return (s * 255.0).roundToInt().coerceIn(0, 255)
    }

    private fun digits(value: Long, width: Int): String =
        value.toString(16).uppercase().padStart(width, '0')

    // Vienot 1999 LMS approximations. Rows sum to 1, so a neutral grey stays neutral.
    private val PROTANOPIA = arrayOf(
        doubleArrayOf(0.152286, 1.052583, -0.204868),
        doubleArrayOf(0.114503, 0.786281, 0.099216),
        doubleArrayOf(-0.003882, -0.048116, 1.051998),
    )
    private val DEUTERANOPIA = arrayOf(
        doubleArrayOf(0.367322, 0.860646, -0.227968),
        doubleArrayOf(0.280085, 0.672501, 0.047413),
        doubleArrayOf(-0.011820, 0.042940, 0.968881),
    )
    private val TRITANOPIA = arrayOf(
        doubleArrayOf(1.255528, -0.076749, -0.178779),
        doubleArrayOf(-0.078411, 0.930809, 0.147602),
        doubleArrayOf(0.004733, 0.691367, 0.303900),
    )
}
