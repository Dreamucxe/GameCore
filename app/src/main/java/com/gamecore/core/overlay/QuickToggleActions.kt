package com.gamecore.core.overlay

/**
 * The bridge between a quick-sheet pin ([QuickToggle]) and the panel action it stands for ([OverlayAction]),
 * spec §4.
 *
 * The quick sheet does not invent behaviour: tapping its Crosshair pin must do exactly what the full panel's
 * Crosshair tile does, run through the same `onAction` in the service (spec §0 — "the surface never decides
 * what a control does"). This mapping is the one place that correspondence is written down, so the service's
 * pin wiring, the availability predicate, and the on-state read all agree by construction rather than by
 * three separate `when`s that could drift apart.
 *
 * It lives here, beside both enums in `com.gamecore.core.overlay`, as pure Kotlin — no Android, no service —
 * so it is unit-tested on the JVM. The `when` is exhaustive with no `else`: adding a [QuickToggle] without
 * giving it an action fails to compile, which is the "pin that does nothing" this file exists to prevent.
 */
fun QuickToggle.toOverlayAction(): OverlayAction = when (this) {
    QuickToggle.STATS -> OverlayAction.PILL
    QuickToggle.CROSSHAIR -> OverlayAction.CROSSHAIR
    QuickToggle.HUD -> OverlayAction.HUD
    QuickToggle.SCREENSHOT -> OverlayAction.SCREENSHOT
    QuickToggle.RECORD -> OverlayAction.RECORD
    QuickToggle.TORCH -> OverlayAction.FLASHLIGHT
    QuickToggle.SILENCE -> OverlayAction.DO_NOT_DISTURB
    QuickToggle.ROTATION -> OverlayAction.ROTATION_LOCK
    QuickToggle.REFRESH -> OverlayAction.REFRESH_RATE
}
