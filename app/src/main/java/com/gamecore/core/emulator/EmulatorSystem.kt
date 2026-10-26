package com.gamecore.core.emulator

import java.util.Locale

/**
 * A console family GameCore can name, not an emulator it contains.
 *
 * The distinction matters for every claim this feature makes. GameCore does not execute a ROM, does not
 * interpret an ISO and does not ship an emulator core; it records which family a file belongs to, which
 * installed application says it can open that family, and then hands the file over. A system is therefore
 * a piece of *metadata about the file*, never evidence that the file will run.
 *
 * [extensions] are hints, not authority. Four different families use `.iso`, and an emulator's own file
 * picker remains the final word on whether it accepts a particular disc image. The list exists so the
 * picker can offer sensible choices instead of asking the user to type a system name.
 */
enum class EmulatorSystem(
    val label: String,
    val shortLabel: String,
    val extensions: Set<String>,
) {
    GAMECUBE_WII(
        label = "GameCube / Wii",
        shortLabel = "GC / Wii",
        extensions = setOf("iso", "gcm", "ciso", "rvz", "wbfs", "wad", "dol", "elf"),
    ),
    PLAYSTATION_2(
        label = "PlayStation 2",
        shortLabel = "PS2",
        extensions = setOf("iso", "chd", "bin", "cue", "mdf", "nrg"),
    ),
    PLAYSTATION_PORTABLE(
        label = "PlayStation Portable",
        shortLabel = "PSP",
        extensions = setOf("iso", "cso", "pbp", "chd"),
    ),
    PLAYSTATION(
        label = "PlayStation",
        shortLabel = "PS1",
        extensions = setOf("bin", "cue", "chd", "pbp", "img", "iso"),
    ),
    DREAMCAST(
        label = "Dreamcast",
        shortLabel = "DC",
        extensions = setOf("gdi", "cdi", "chd", "cue", "bin"),
    ),
    NINTENDO_SWITCH(
        label = "Nintendo Switch",
        shortLabel = "Switch",
        extensions = setOf("nsp", "xci", "nca", "nro"),
    ),
    NINTENDO_DS(
        label = "Nintendo DS",
        shortLabel = "DS",
        extensions = setOf("nds", "dsi", "srl"),
    ),
    NINTENDO_3DS(
        label = "Nintendo 3DS",
        shortLabel = "3DS",
        extensions = setOf("3ds", "cia", "cci", "cxi"),
    ),
    NINTENDO_64(
        label = "Nintendo 64",
        shortLabel = "N64",
        extensions = setOf("n64", "z64", "v64", "rom"),
    ),
    GAME_BOY_ADVANCE(
        label = "Game Boy Advance",
        shortLabel = "GBA",
        extensions = setOf("gba", "agb"),
    ),
    SUPER_NINTENDO(
        label = "Super Nintendo",
        shortLabel = "SNES",
        extensions = setOf("sfc", "smc"),
    ),
    SEGA_GENESIS(
        label = "Sega Genesis",
        shortLabel = "Genesis",
        extensions = setOf("md", "gen", "smd"),
    ),
    ARCADE(
        label = "Arcade",
        shortLabel = "Arcade",
        extensions = setOf("zip", "7z"),
    ),
    WINDOWS(
        label = "Windows PC",
        shortLabel = "Windows",
        extensions = setOf("exe"),
    ),
    UNKNOWN(
        label = "Unknown system",
        shortLabel = "Unknown",
        extensions = emptySet(),
    ),
    ;

    /** Whether [extension] is one of this family's known file suffixes. Case-insensitive. */
    fun recognises(extension: String): Boolean =
        extension.trim().lowercase(Locale.US) in extensions

    companion object {
        /** Every family whose extension list contains [extension], in declaration order. */
        fun candidatesFor(extension: String): List<EmulatorSystem> {
            val normalised = extension.trim().lowercase(Locale.US)
            if (normalised.isEmpty()) return emptyList()
            return entries.filter { normalised in it.extensions }
        }

        /** Parses a stored enum name without throwing on a name this build does not know. */
        fun fromStoredName(name: String?): EmulatorSystem =
            entries.firstOrNull { it.name == name } ?: UNKNOWN
    }
}
