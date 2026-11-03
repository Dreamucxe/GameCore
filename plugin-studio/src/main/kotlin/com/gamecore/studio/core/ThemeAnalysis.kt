package com.gamecore.studio.core

import com.gamecore.studio.model.ThemeDraft
import kotlin.math.roundToInt

/** How much attention a [DesignWarning] asks for. */
enum class Severity { INFO, WARNING, ERROR }

/**
 * One measured foreground/background pair.
 *
 * [ratio] is the WCAG contrast ratio after alpha compositing, `1..21`. [meetsAA] is the
 * normal-text threshold (`4.5:1`) and [meetsAAA] the enhanced one (`7:1`).
 */
data class ContrastCheck(
    val label: String,
    val foreground: Long,
    val background: Long,
    val ratio: Double,
    val meetsAA: Boolean,
    val meetsAAA: Boolean,
)

/**
 * One observation about a theme.
 *
 * [field] names the draft field the warning belongs to, in the same dotted form
 * [ThemeDraft.problems] uses, so the editor can underline the control that caused it.
 */
data class DesignWarning(
    val severity: Severity,
    val title: String,
    val detail: String,
    val field: String? = null,
)

/**
 * Measured design checks for a [ThemeDraft].
 *
 * Everything here follows from the colours and numbers in the draft. A theme is not a
 * component tree, so this file makes no claim about touch targets, spacing between controls
 * or the legibility of a button it has never seen; those belong to the host renderer. What a
 * theme *can* answer is whether its inks have enough contrast on the surfaces it describes,
 * whether its scales or corner radius sit at an extreme, and whether its accent is distinct
 * from its primary.
 *
 * The contrast pairs are composited the way the host would paint them: a translucent surface
 * is laid over the page, a nested surface over its parent, and the ink over the result. A
 * translucent background is finally laid over white so the comparison always describes
 * opaque pixels.
 */
object ThemeAnalysis {

    /** WCAG AA for normal text. */
    const val AA: Double = 4.5

    /** WCAG AAA for normal text. */
    const val AAA: Double = 7.0

    /** Below this ratio a pair is reported as an error rather than a warning. */
    const val VERY_LOW: Double = 3.0

    /** Ink below this fraction of opacity is flagged even when its measured ratio passes. */
    const val READABLE_INK_ALPHA: Double = 0.8

    /** Glass is made of light and blur; body ink this translucent is flagged. */
    const val GLASS_INK_ALPHA: Double = 0.9

    /** A primary and accent closer than this ratio are doing the same visual job. */
    const val ACCENT_DIFFERENCE: Double = 1.2

    /** UI scales this close to either end of the supported range are called out. */
    const val EXTREME_UI_LOW: Float = 0.85f
    const val EXTREME_UI_HIGH: Float = 1.35f
    const val EXTREME_TYPE_LOW: Float = 0.90f
    const val EXTREME_TYPE_HIGH: Float = 1.25f

    private const val LABEL_ON_PRIMARY = "On primary"
    private const val LABEL_ON_BACKGROUND = "On surface / background"
    private const val LABEL_ON_SURFACE = "On surface / surface"
    private const val LABEL_ACCENT = "Accent / background"
    private const val LABEL_ON_VARIANT = "On surface variant / surface variant"
    private const val LABEL_ON_ERROR = "On error"

    private const val WHITE = 0xFFFFFFFFL

    /**
     * The six pairs the editor presents first, measured with alpha compositing.
     *
     * The order is the order the roles are edited in: the main action, the two page inks, the
     * accent, the nested surface and the error state.
     */
    fun contrastChecks(theme: ThemeDraft): List<ContrastCheck> {
        val page = ColorMath.blendOver(theme.background, WHITE)
        val primary = ColorMath.blendOver(theme.primary, page)
        val surface = ColorMath.blendOver(theme.surface, page)
        val variant = ColorMath.blendOver(theme.surfaceVariant, surface)
        val error = ColorMath.blendOver(theme.error, page)
        return listOf(
            check(LABEL_ON_PRIMARY, theme.onPrimary, primary),
            check(LABEL_ON_BACKGROUND, theme.onSurface, page),
            check(LABEL_ON_SURFACE, theme.onSurface, surface),
            check(LABEL_ACCENT, theme.accent, page),
            check(LABEL_ON_VARIANT, theme.onSurfaceVariant, variant),
            check(LABEL_ON_ERROR, theme.onError, error),
        )
    }

    /**
     * Everything the draft's own values can tell us.
     *
     * Contrast failures are emitted once per pair: below [VERY_LOW] is an [Severity.ERROR],
     * below [AA] is a [Severity.WARNING]. Translucent inks, scales at the edge of their range,
     * a square theme, an accent that matches the primary and glass over translucent body ink
     * are all warnings or notes rather than validation errors — the package is still legal.
     */
    fun warnings(theme: ThemeDraft): List<DesignWarning> = buildList {
        contrastChecks(theme).forEach { check ->
            val field = fieldFor(check.label)
            when {
                check.ratio < VERY_LOW -> add(
                    DesignWarning(
                        severity = Severity.ERROR,
                        title = "Very low contrast: ${check.label}",
                        detail = "${check.label} measures ${ratioText(check.ratio)}; " +
                            "text below ${ratioText(VERY_LOW)} is not reliably readable.",
                        field = field,
                    ),
                )

                check.ratio < AA -> add(
                    DesignWarning(
                        severity = Severity.WARNING,
                        title = "Low contrast: ${check.label}",
                        detail = "${check.label} measures ${ratioText(check.ratio)}; " +
                            "WCAG AA asks for ${ratioText(AA)} for normal text.",
                        field = field,
                    ),
                )
            }
        }

        inks(theme).forEach { ink ->
            val alpha = ColorMath.alpha(ink.color)
            if (alpha / 255.0 < READABLE_INK_ALPHA) {
                add(
                    DesignWarning(
                        severity = Severity.WARNING,
                        title = "Translucent ink: ${ink.label}",
                        detail = "${ink.label} is ${percent(alpha)} opaque; ink below " +
                            "${percent((READABLE_INK_ALPHA * 255).roundToInt())} can lose " +
                            "contrast over a busy or translucent panel.",
                        field = ink.field,
                    ),
                )
            }
        }

        if (theme.uiScale <= EXTREME_UI_LOW || theme.uiScale >= EXTREME_UI_HIGH) {
            add(
                DesignWarning(
                    severity = Severity.WARNING,
                    title = "UI scale near the limit",
                    detail = "UI scale ${theme.uiScale} sits at the edge of the supported " +
                        "${ThemeDraft.MIN_UI_SCALE}–${ThemeDraft.MAX_UI_SCALE} range; " +
                        "check the densest screens at this size.",
                    field = "theme.uiScale",
                ),
            )
        }

        if (theme.typographyScale <= EXTREME_TYPE_LOW || theme.typographyScale >= EXTREME_TYPE_HIGH) {
            add(
                DesignWarning(
                    severity = Severity.WARNING,
                    title = "Type scale near the limit",
                    detail = "Type scale ${theme.typographyScale} sits at the edge of the " +
                        "supported ${ThemeDraft.MIN_TYPO_SCALE}–${ThemeDraft.MAX_TYPO_SCALE} " +
                        "range; long labels may wrap or clip.",
                    field = "theme.typographyScale",
                ),
            )
        }

        if (theme.cornerRadiusDp == 0) {
            add(
                DesignWarning(
                    severity = Severity.INFO,
                    title = "Square corners",
                    detail = "Corner radius is 0 dp. This is a valid style; panels simply have " +
                        "hard corners.",
                    field = "theme.cornerRadiusDp",
                ),
            )
        }

        val page = ColorMath.blendOver(theme.background, WHITE)
        val accent = ColorMath.blendOver(theme.accent, page)
        val primary = ColorMath.blendOver(theme.primary, page)
        val separation = ColorMath.contrastRatio(accent, primary)
        if (separation < ACCENT_DIFFERENCE) {
            add(
                DesignWarning(
                    severity = Severity.WARNING,
                    title = "Accent and primary read alike",
                    detail = "Accent and primary differ by only ${ratioText(separation)}; " +
                        "below ${ratioText(ACCENT_DIFFERENCE)} they tend to read as the same " +
                        "colour, so a selected state may not stand out.",
                    field = "theme.accent",
                ),
            )
        }

        if (theme.glass != null && ColorMath.alpha(theme.onSurface) / 255.0 < GLASS_INK_ALPHA) {
            add(
                DesignWarning(
                    severity = Severity.WARNING,
                    title = "Glass over translucent ink",
                    detail = "This theme uses glass, but onSurface is only " +
                        "${percent(ColorMath.alpha(theme.onSurface))} opaque. Glass panels are " +
                        "made of light and blur, so body ink should stay at " +
                        "${percent((GLASS_INK_ALPHA * 255).roundToInt())} or above.",
                    field = "theme.onSurface",
                ),
            )
        }
    }

    /**
     * A 0–100 summary of the warnings above.
     *
     * Starts at 100, subtracts 12 for every [Severity.ERROR] and 5 for every
     * [Severity.WARNING], and never goes below zero. [Severity.INFO] notes are free, because
     * a square corner or a deliberately extreme scale is a style, not a defect.
     */
    fun score(theme: ThemeDraft): Int {
        var result = 100
        warnings(theme).forEach { warning ->
            when (warning.severity) {
                Severity.ERROR -> result -= 12
                Severity.WARNING -> result -= 5
                Severity.INFO -> Unit
            }
        }
        return result.coerceAtLeast(0)
    }

    // ------------------------------------------------------------------------- internals

    private data class Ink(val field: String, val label: String, val color: Long)

    private fun inks(theme: ThemeDraft): List<Ink> = listOf(
        Ink("theme.onPrimary", "On primary", theme.onPrimary),
        Ink("theme.onSurface", "On surface", theme.onSurface),
        Ink("theme.onSurfaceVariant", "On surface variant", theme.onSurfaceVariant),
        Ink("theme.onError", "On error", theme.onError),
    )

    private fun check(label: String, foreground: Long, background: Long): ContrastCheck {
        val ratio = ColorMath.contrastRatio(foreground, background)
        return ContrastCheck(
            label = label,
            foreground = foreground,
            background = background,
            ratio = ratio,
            meetsAA = ratio >= AA,
            meetsAAA = ratio >= AAA,
        )
    }

    private fun fieldFor(label: String): String? = when (label) {
        LABEL_ON_PRIMARY -> "theme.onPrimary"
        LABEL_ON_BACKGROUND, LABEL_ON_SURFACE -> "theme.onSurface"
        LABEL_ACCENT -> "theme.accent"
        LABEL_ON_VARIANT -> "theme.onSurfaceVariant"
        LABEL_ON_ERROR -> "theme.onError"
        else -> null
    }

    /** `4.76:1`, with two decimals and no locale-dependent separator. */
    private fun ratioText(ratio: Double): String {
        val hundredths = (ratio * 100.0).roundToInt()
        return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}:1"
    }

    /** `80%`, rounded for display. */
    private fun percent(alpha: Int): String = "${(alpha / 255.0 * 100.0).roundToInt()}%"
}
