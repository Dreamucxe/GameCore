package com.gamecore.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The type scale, tuned for an app that is mostly numbers.
 *
 * No font is bundled. A custom family would add a megabyte to an APK whose value is elsewhere, and the
 * platform default is the family the user's device already renders best; §27 asks for large readable
 * stats, which is a size and weight decision rather than a typeface one.
 *
 * Only the roles this app actually draws are overridden. The rest keep Material 3's defaults, so a
 * component pulled in later gets sensible type instead of whatever this file happened to define.
 */
val GameCoreTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.2).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 25.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
        lineHeight = 19.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
)

/**
 * The figure on a stat card, and the label under it.
 *
 * Monospaced, for the reason the overlay pill is: a proportional font changes the width of "9%" when it
 * becomes "10%", and a dashboard of six cards whose contents twitch every two seconds is unreadable
 * even when every number in it is correct.
 */
val StatValueStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
    fontSize = 26.sp,
    lineHeight = 30.sp,
    letterSpacing = (-0.5).sp,
)

/** The same figure where the layout is tighter — a list row rather than a card. */
val StatValueCompactStyle = StatValueStyle.copy(fontSize = 18.sp, lineHeight = 22.sp)

/** What the figure is. Small, spaced, and never the same weight as the figure itself. */
val StatLabelStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Medium,
    fontSize = 11.5.sp,
    lineHeight = 15.sp,
    letterSpacing = 0.6.sp,
)
