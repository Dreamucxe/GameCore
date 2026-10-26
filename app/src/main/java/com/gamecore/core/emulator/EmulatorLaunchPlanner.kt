package com.gamecore.core.emulator

import java.util.Locale

/**
 * Which installed emulators can be offered for a ROM, in the order they should be offered.
 *
 * Pure Kotlin: the Android layer builds the actual intents, and this object only decides compatibility and
 * ranking. That split is what lets the rules be tested without a device, and it keeps a package-manager
 * query from being mistaken for a compatibility claim.
 *
 * An emulator is only a candidate when its descriptor names the ROM's system. A user-assigned system that
 * no installed emulator supports produces an empty result and an explanation, not a list of every emulator
 * on the phone with a hope that one of them accepts the file.
 */
data class LaunchChoices(
    val handOff: List<InstalledEmulator>,
    val openOnly: List<InstalledEmulator>,
) {
    val isEmpty: Boolean get() = handOff.isEmpty() && openOnly.isEmpty()
    val total: Int get() = handOff.size + openOnly.size
}

object EmulatorLaunchPlanner {

    /**
     * Ranks compatible emulators.
     *
     * A previously chosen package wins first, so a user who picked DuckStation for PS1 games is not asked
     * again on every launch. After that, emulators that accept a file intent come before open-only ones,
     * and the remainder is alphabetical so the order is stable across refreshes.
     *
     * [preferredPackage] is a hint, not a filter: an uninstalled or now-incompatible choice is simply
     * absent from the result, and the caller falls back to the ranked list.
     */
    fun choices(
        system: EmulatorSystem,
        installed: List<InstalledEmulator>,
        preferredPackage: String? = null,
    ): LaunchChoices {
        if (system == EmulatorSystem.UNKNOWN) return LaunchChoices(emptyList(), emptyList())

        val compatible = installed.filter { system in it.systems }
        val ranked = compatible.sortedWith(
            compareByDescending<InstalledEmulator> { it.packageName == preferredPackage }
                .thenByDescending { it.handlesFiles }
                .thenBy { it.label.lowercase(Locale.US) }
                .thenBy { it.packageName },
        )
        return LaunchChoices(
            handOff = ranked.filter { it.handlesFiles },
            openOnly = ranked.filterNot { it.handlesFiles },
        )
    }

    /** Whether any installed emulator names [system] at all, regardless of whether it accepts a file. */
    fun supports(system: EmulatorSystem, installed: List<InstalledEmulator>): Boolean =
        installed.any { system in it.systems }
}
