package com.gamecore.ui

import com.gamecore.core.i18n.Strings

/**
 * The bottom bar's word for a tab, in the user's language.
 *
 * [Destination.Top.label] stays the English name and is not replaced: routes, tests, logs and the
 * plugin API all refer to tabs by their stable English names, and a translated label would make the
 * route depend on a catalogue. Only the two places that draw the word — the bar here and the
 * destination list a screen might show — call this.
 */
fun Destination.Top.localizedLabel(strings: Strings): String = when (this) {
    Destination.Home -> strings.navHome
    Destination.Games -> strings.navGames
    Destination.AimLabHome -> strings.navAimLab
    Destination.Sessions -> strings.navSessions
    Destination.Settings -> strings.navSettings
}
