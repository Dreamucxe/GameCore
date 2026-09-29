package com.gamecore.core.model

/**
 * Display density for the on-screen performance HUD.
 *
 * Mirrors [PillDisplayMode] for the performance pill: a single GLOBAL preference carried on
 * [OverlayConfig] (field `hudDisplayMode`) and persisted by name in EncryptedSharedPreferences.
 * There is deliberately no per-layout column, so adding this mode requires no database migration —
 * an absent/legacy value reads back as [EXPANDED], preserving the current appearance.
 */
enum class HudDisplayMode(val label: String) {
    /** Value-only, no labels or background plates — the densest form. */
    COMPACT("Compact"),

    /** The full widget appearance: optional label, background plate and per-widget styling. */
    EXPANDED("Expanded");

    companion object {
        /** Resolve a persisted name back to a mode, defaulting to [EXPANDED] for unknown/legacy values. */
        fun of(name: String?): HudDisplayMode = entries.firstOrNull { it.name == name } ?: EXPANDED
    }
}
