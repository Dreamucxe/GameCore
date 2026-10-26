package com.gamecore.core.emulator

/**
 * The emulators GameCore can recognise, and the download pages it is willing to point at.
 *
 * This is deliberately a curated list rather than a scan of every installed package. A package name is the
 * only reliable way to know that `org.ppsspp.ppsspp` is PPSSPP and not a clone wearing the same label; a
 * label-keyword heuristic would produce confident-looking rows for apps that cannot open a ROM. A user with
 * an emulator that is not listed can still open it themselves — GameCore simply does not claim to manage it.
 *
 * No emulator is bundled, downloaded automatically or linked into the APK. Every URL below points at the
 * project's own release page, opened in the user's browser. That keeps GPL and proprietary emulators out of
 * GameCore's own distribution and keeps the choice to install one with the person who owns the device.
 */
object EmulatorRegistry {

    val all: List<EmulatorDescriptor> = listOf(
        EmulatorDescriptor(
            id = "retroarch",
            displayName = "RetroArch",
            packages = listOf("com.retroarch.aarch64", "com.retroarch"),
            systems = setOf(
                EmulatorSystem.PLAYSTATION,
                EmulatorSystem.SUPER_NINTENDO,
                EmulatorSystem.GAME_BOY_ADVANCE,
                EmulatorSystem.SEGA_GENESIS,
                EmulatorSystem.NINTENDO_64,
                EmulatorSystem.NINTENDO_DS,
                EmulatorSystem.ARCADE,
            ),
            downloadUrl = "https://www.retroarch.com/?page=platforms",
            note = "RetroArch opens a ROM through its own core selection. Which cores are installed decides " +
                "what actually runs.",
        ),
        EmulatorDescriptor(
            id = "dolphin",
            displayName = "Dolphin",
            packages = listOf("org.dolphinemu.dolphinemu"),
            systems = setOf(EmulatorSystem.GAMECUBE_WII),
            downloadUrl = "https://dolphin-emu.org/download/",
        ),
        EmulatorDescriptor(
            id = "ppsspp",
            displayName = "PPSSPP",
            packages = listOf("org.ppsspp.ppsspp", "org.ppsspp.ppssppgold"),
            systems = setOf(EmulatorSystem.PLAYSTATION_PORTABLE),
            downloadUrl = "https://github.com/hrydgard/ppsspp/releases",
        ),
        EmulatorDescriptor(
            id = "aethersx2",
            displayName = "AetherSX2",
            packages = listOf("xyz.aethersx2.android"),
            systems = setOf(EmulatorSystem.PLAYSTATION_2),
            note = "AetherSX2 is no longer developed upstream. GameCore detects an existing install and " +
                "does not link to a download it cannot vouch for.",
        ),
        EmulatorDescriptor(
            id = "melonds",
            displayName = "melonDS",
            packages = listOf("me.magnum.melonds"),
            systems = setOf(EmulatorSystem.NINTENDO_DS),
            downloadUrl = "https://github.com/melonDS-emu/melonDS/releases",
        ),
        EmulatorDescriptor(
            id = "drastic",
            displayName = "DraStic",
            packages = listOf("com.dsemu.drastic"),
            systems = setOf(EmulatorSystem.NINTENDO_DS),
            downloadUrl = "https://drastic.dsemu.com/",
        ),
        EmulatorDescriptor(
            id = "citra",
            displayName = "Citra",
            packages = listOf("org.citra.citra_emu", "org.citra.citra_emu.canary"),
            systems = setOf(EmulatorSystem.NINTENDO_3DS),
            downloadUrl = "https://github.com/citra-emu/citra",
            note = "Citra's upstream Android builds are archived. An existing install is still detected.",
        ),
        EmulatorDescriptor(
            id = "duckstation",
            displayName = "DuckStation",
            packages = listOf("com.github.stenzek.duckstation"),
            systems = setOf(EmulatorSystem.PLAYSTATION),
            downloadUrl = "https://github.com/stenzek/duckstation/releases",
        ),
        EmulatorDescriptor(
            id = "flycast",
            displayName = "Flycast",
            packages = listOf("com.flycast.emulator"),
            systems = setOf(EmulatorSystem.DREAMCAST),
            downloadUrl = "https://github.com/flyinghead/flycast/releases",
        ),
        EmulatorDescriptor(
            id = "m64plusfz",
            displayName = "M64Plus FZ",
            packages = listOf("org.mupen64plusae.v3.fzurita"),
            systems = setOf(EmulatorSystem.NINTENDO_64),
            downloadUrl = "https://github.com/mupen64plus-ae/mupen64plus-ae",
        ),
        EmulatorDescriptor(
            id = "yuzu",
            displayName = "yuzu",
            packages = listOf("org.yuzu.yuzu_emu"),
            systems = setOf(EmulatorSystem.NINTENDO_SWITCH),
            downloadUrl = "https://github.com/yuzu-emu/yuzu",
            note = "yuzu's upstream builds are archived. An existing install is still detected.",
        ),
        EmulatorDescriptor(
            id = "sudachi",
            displayName = "Sudachi",
            packages = listOf("org.sudachi.sudachi_emu"),
            systems = setOf(EmulatorSystem.NINTENDO_SWITCH),
            note = "Sudachi is detected by package name. GameCore does not link to a download for it.",
        ),
        EmulatorDescriptor(
            id = "skyline",
            displayName = "Skyline",
            packages = listOf("skyline.emu"),
            systems = setOf(EmulatorSystem.NINTENDO_SWITCH),
            downloadUrl = "https://github.com/skyline-emu/skyline",
            note = "Skyline is no longer developed upstream. An existing install is still detected.",
        ),
        EmulatorDescriptor(
            id = "winlator",
            displayName = "Winlator",
            packages = listOf("com.winlator"),
            systems = setOf(EmulatorSystem.WINDOWS),
            handlesFiles = false,
            downloadUrl = "https://github.com/brunodev85/winlator/releases",
            note = "Winlator runs Windows programs inside its own container. GameCore can open Winlator, " +
                "but it cannot hand it a specific .exe or promise that the program will run.",
        ),
        EmulatorDescriptor(
            id = "epsxe",
            displayName = "ePSXe",
            packages = listOf("com.epsxe.ePSXe"),
            systems = setOf(EmulatorSystem.PLAYSTATION),
            downloadUrl = "https://www.epsxe.com/android/",
        ),
        EmulatorDescriptor(
            id = "redream",
            displayName = "redream",
            packages = listOf("io.recompiled.redream"),
            systems = setOf(EmulatorSystem.DREAMCAST),
            downloadUrl = "https://redream.io/download",
        ),
    )

    /** The descriptor that owns [packageName], or null for an application GameCore does not manage. */
    fun descriptorFor(packageName: String): EmulatorDescriptor? =
        all.firstOrNull { packageName in it.packages }

    /** Descriptors that publish a page a user can open to install them. */
    val withDownloadPage: List<EmulatorDescriptor> = all.filter { it.downloadUrl != null }

    /** Descriptors with no download page, shown as detection-only entries in the "get" sheet. */
    val detectionOnly: List<EmulatorDescriptor> = all.filter { it.downloadUrl == null }
}
