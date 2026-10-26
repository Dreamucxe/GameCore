package com.gamecore.core.emulator

/**
 * MIME hints for handing a document to an emulator.
 *
 * There is no single correct MIME type for a disc image. Android's own database often reports `.iso` as
 * `application/octet-stream`, while some emulators register only `application/x-iso9660-image`, and a few
 * register a wildcard. The launcher therefore tries the specific type, then `application/octet-stream`,
 * then a wildcard, and only claims a hand-off when `PackageManager.resolveActivity` returns an activity for one of
 * them. A miss is reported as "opened the emulator" rather than as a successful launch.
 */
object RomMime {

    /**
     * The most specific type GameCore knows for [extension], or `application/octet-stream` when it has no
     * better answer. This is a hint, never a claim that the emulator accepts the file.
     */
    fun specific(extension: String): String = when (extension.trim().lowercase()) {
        "iso" -> "application/x-iso9660-image"
        "gcm" -> "application/x-gcm"
        "rvz" -> "application/x-rvz"
        "wbfs" -> "application/x-wbfs"
        "cso" -> "application/x-cso"
        "pbp" -> "application/x-pbp"
        "chd" -> "application/x-chd"
        "zip" -> "application/zip"
        "7z" -> "application/x-7z-compressed"
        "gba", "agb" -> "application/x-gba-rom"
        "nds", "dsi", "srl" -> "application/x-nds-rom"
        "3ds" -> "application/x-3ds-rom"
        "cia" -> "application/x-cia"
        "cci", "cxi" -> "application/x-ctr-rom"
        "n64", "z64", "v64", "rom" -> "application/x-n64-rom"
        "sfc", "smc" -> "application/x-snes-rom"
        "md", "gen", "smd" -> "application/x-genesis-rom"
        "nsp" -> "application/x-nsp"
        "xci" -> "application/x-xci"
        "nro" -> "application/x-nro"
        "exe" -> "application/x-msdownload"
        "gdi" -> "application/x-gdi"
        "cdi" -> "application/x-cdi"
        "cue" -> "application/x-cue"
        "bin" -> "application/octet-stream"
        else -> "application/octet-stream"
    }

    /**
     * The types to try, most specific first and de-duplicated.
     *
     * The wildcard is last on purpose: an emulator that registers only a wildcard still deserves the
     * hand-off, but a specific type must be allowed to win when one exists so the chooser is not widened
     * unnecessarily.
     */
    fun candidates(extension: String): List<String> =
        listOf(specific(extension), "application/octet-stream", "*/*").distinct()
}
