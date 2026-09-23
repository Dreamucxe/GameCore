package com.gamecore.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.gamecore.core.model.ThermalClass

/**
 * Semantic status colours — the redesign's §2 set that means the same thing in every theme: ok, warm,
 * hot, critical, info, unavailable.
 *
 * These are built on the colours that already sit *outside* the accent's reach ([StatusGood],
 * [ThermalWarning], [ThermalCritical] in Color.kt), for the reason documented there: a user whose accent
 * is amber must still be able to tell "the device is throttling" from "this is the accent colour". So
 * status is never the accent, and — the §2 rule — status is never conveyed by colour alone: every caller
 * pairs the colour with the word from [com.gamecore.core.model.ThermalClassifier] or an icon.
 *
 * `info` is the one that leans on the theme: it is the accent, because "here is a neutral note" is exactly
 * the place the accent belongs and carries no danger meaning. `unavailable` is the muted on-surface
 * colour, never red — an absent reading is not a fault (the audit's honesty contract).
 */
enum class StatusColor {
    Ok, Warm, Hot, Critical, Info, Unavailable,
}

@Composable
fun StatusColor.colour(): Color = when (this) {
    StatusColor.Ok -> StatusGood
    StatusColor.Warm -> ThermalWarning
    StatusColor.Hot -> ThermalWarning
    StatusColor.Critical -> ThermalCritical
    StatusColor.Info -> MaterialTheme.colorScheme.primary
    StatusColor.Unavailable -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * The one mapping from a classified temperature to a colour.
 *
 * The word comes from [ThermalClass.label] and the colour comes from here, both derived from the same
 * [ThermalClass] the shared classifier produced — so the old "red 85.3 °C labelled Normal" split cannot
 * recur: a caller cannot pick the colour from one source and the word from another, because both are this
 * one enum value.
 */
fun ThermalClass.statusColor(): StatusColor = when (this) {
    ThermalClass.UNAVAILABLE -> StatusColor.Unavailable
    ThermalClass.OK -> StatusColor.Ok
    ThermalClass.WARM -> StatusColor.Warm
    ThermalClass.HOT -> StatusColor.Hot
    ThermalClass.CRITICAL -> StatusColor.Critical
}
