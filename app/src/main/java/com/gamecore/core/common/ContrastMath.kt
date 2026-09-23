package com.gamecore.core.common

import kotlin.math.pow

/**
 * WCAG contrast maths on plain ARGB `Int`s.
 *
 * The redesign's §3 (colour) and §11 (accessibility) sign-off both rest on the
 * same two numbers — 4.5:1 for body text, 3:1 for large text and icons — so the
 * arithmetic that produces them lives here, as pure Kotlin with no `android.*` or
 * Compose dependency. That is deliberate: a `Color` in Compose is a packed
 * `ULong` in a wide gamut and its channels are already linear, whereas the
 * ratios a designer quotes come from sRGB 8-bit values. Keeping this on `Int`
 * means the whole of it is covered by fast JVM tests and the reference figures
 * (black-on-white is 21:1) can be checked against any online contrast tool.
 *
 * The formulae are WCAG 2.1, "Relative luminance" and "Contrast ratio"
 * (https://www.w3.org/TR/WCAG21/#dfn-relative-luminance and #dfn-contrast-ratio).
 * Alpha is ignored: a contrast figure is only meaningful once the foreground is
 * composited onto the background, so the caller is expected to flatten any
 * translucency first and hand two opaque colours in.
 */
object ContrastMath {

    /** Body text and any text smaller than large: WCAG AA wants at least this. */
    const val BODY_TEXT_MIN = 4.5

    /**
     * Large text (18pt regular / 14pt bold and up) and meaningful graphics such
     * as icons: WCAG AA relaxes the requirement to this because a bigger, bolder
     * shape stays legible at a lower contrast.
     */
    const val LARGE_TEXT_MIN = 3.0

    /**
     * Relative luminance of an ARGB colour, 0.0 (black) to 1.0 (white).
     *
     * Each 8-bit channel is taken to 0..1, linearised out of the sRGB transfer
     * curve — a straight line below the 0.03928 knee, a 2.4 power above it — and
     * then weighted by the eye's sensitivity to each primary (green dominates,
     * blue barely registers). Alpha is not read.
     */
    fun relativeLuminance(argb: Int): Double {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return 0.2126 * linearize(r) + 0.7152 * linearize(g) + 0.0722 * linearize(b)
    }

    /**
     * Contrast ratio between two opaque ARGB colours, from 1.0 (identical) to
     * 21.0 (black against white).
     *
     * `(L_lighter + 0.05) / (L_darker + 0.05)`. Ordering the two luminances so
     * the brighter is always on top makes the result independent of which colour
     * is foreground and which is background, and never less than 1.0.
     */
    fun contrastRatio(fgArgb: Int, bgArgb: Int): Double {
        val l1 = relativeLuminance(fgArgb)
        val l2 = relativeLuminance(bgArgb)
        val lighter = maxOf(l1, l2)
        val darker = minOf(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /** Whether [fg] on [bg] clears the 4.5:1 body-text bar. */
    fun meetsBodyText(fg: Int, bg: Int): Boolean = contrastRatio(fg, bg) >= BODY_TEXT_MIN

    /** Whether [fg] on [bg] clears the 3:1 bar for large text and icons. */
    fun meetsLargeText(fg: Int, bg: Int): Boolean = contrastRatio(fg, bg) >= LARGE_TEXT_MIN

    /** One sRGB channel (0..255) to its linear-light value (0..1). */
    private fun linearize(channel: Int): Double {
        val c = channel / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
}
