package com.gamecore.ui.theme

import androidx.compose.ui.unit.dp

/**
 * The spacing scale — the redesign's §2 token set (4, 8, 12, 16, 24, 32).
 *
 * The audit found ~460 raw `.dp` literals scattered across the screens and no named scale to reach for,
 * which is how a 10.dp here and an 11.dp there creep in and two cards that should line up do not. These
 * six steps are the only gaps a layout should use; a call site that wants "a small gap" says
 * [Spacing.sm] rather than picking a number, so the rhythm is decided once and stays consistent when the
 * whole app is scaled by the density multiplier.
 *
 * Deliberately small and fixed. A scale with a value for every occasion is a scale nobody follows; six
 * steps cover padding, gaps between cards, and the inset inside them, and anything that genuinely needs a
 * one-off (a hairline divider, an icon nudge) is a one-off and says so at the call site.
 */
object Spacing {
    /** 4.dp — hairline gaps, the space between a label and the value right under it. */
    val xs = 4.dp

    /** 8.dp — tight internal padding, gaps between chips. */
    val sm = 8.dp

    /** 12.dp — the gap between stacked rows inside a card. */
    val md = 12.dp

    /** 16.dp — a card's inner padding and the standard gap between cards. Matches [ScreenPadding]. */
    val lg = 16.dp

    /** 24.dp — the gap between sections of a screen. */
    val xl = 24.dp

    /** 32.dp — the largest step: a hero's breathing room, an empty state's vertical rhythm. */
    val xxl = 32.dp
}

/**
 * The compact-density adjustment (§3 "Compact density option").
 *
 * When the user turns on compact density, the between-things spacing tightens by one notch while the
 * inside-things padding is left alone — a denser list without cramping the touch targets, which stay at
 * their 48.dp floor regardless. A screen asks [compact] for its section gap; it does not re-derive the
 * rule. The value is a plain multiplier so it composes with the density scale rather than fighting it.
 */
object Density {
    /** Multiplier applied to *between-element* spacing when compact density is on. */
    const val COMPACT_FACTOR = 0.75f
}
