package com.gamecore.core.model

/**
 * One [ColorCorrection] as a single string, and back again.
 *
 * Exists for the two places a correction has to be stored as one value rather than as
 * fourteen columns: the session row, which records what was on screen while a session
 * was recorded, and any future export. Presets get real columns, because presets are
 * queried and edited; a session's colour reading is written once, read once, and never
 * filtered on.
 *
 * The encoding is versioned and positional, and [decode] is total: every malformed,
 * truncated or future-versioned string returns null rather than throwing, and an
 * unrecognised enum name falls back to the neutral value rather than dropping the whole
 * reading. A session report that renders "no colour correction" for a row it cannot
 * parse is a small loss; one that crashes the reports screen is not.
 */
object ColorCodec {

    private const val VERSION = "1"
    private const val SEPARATOR = "|"
    private const val FIELD_COUNT = 15

    fun encode(correction: ColorCorrection): String = with(correction.normalised()) {
        listOf(
            VERSION,
            redGain, greenGain, blueGain,
            gammaMode.name,
            gamma, redGamma, greenGamma, blueGamma,
            saturation, contrast, hueDegrees, brightnessOffset,
            visionFilter.name,
            if (invertColors) "1" else "0",
        ).joinToString(SEPARATOR)
    }

    fun decode(raw: String?): ColorCorrection? {
        val parts = raw?.split(SEPARATOR) ?: return null
        if (parts.size != FIELD_COUNT || parts[0] != VERSION) return null
        val neutral = ColorCorrection.NEUTRAL
        return ColorCorrection(
            redGain = parts.int(1),
            greenGain = parts.int(2),
            blueGain = parts.int(3),
            gammaMode = GammaMode.entries.firstOrNull { it.name == parts[4] } ?: neutral.gammaMode,
            gamma = parts.int(5),
            redGamma = parts.int(6),
            greenGamma = parts.int(7),
            blueGamma = parts.int(8),
            saturation = parts.int(9),
            contrast = parts.int(10),
            hueDegrees = parts.int(11),
            brightnessOffset = parts.int(12),
            visionFilter = ColorVisionFilter.entries.firstOrNull { it.name == parts[13] }
                ?: neutral.visionFilter,
            invertColors = parts[14] == "1",
        ).normalised()
    }

    private fun List<String>.int(index: Int): Int = this[index].trim().toIntOrNull() ?: 0
}
