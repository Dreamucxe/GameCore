package com.gamecore.core.model

/**
 * A crosshair the user configured.
 *
 * This is a drawn overlay and nothing more. It does not read the game, does not know where
 * the game's own reticle is, and does not interact with the game process in any way — it is a
 * shape drawn in GameCore's own window at a position the user chose. That is worth stating in
 * the model because a crosshair overlay is the one feature in this app that could be mistaken
 * for an aim assist, and the distinction is what keeps it on the right side of §24: a static
 * shape on the screen is the same thing as a sticker on the glass.
 *
 * [name] and [imagePath] are the only user-supplied strings; both are sanitised before
 * storage, and the image is copied into GameCore's own storage rather than referenced by URI
 * so a revoked permission cannot leave the overlay with a hole in it.
 */
data class CrosshairPreset(
    val id: Long = 0L,
    val name: String,
    val design: CrosshairDesign = CrosshairDesign.CROSS,
    /** Pixels at the density the overlay is drawn on. Clamped by [normalised]. */
    val sizeDp: Int = DEFAULT_SIZE_DP,
    val thicknessDp: Int = DEFAULT_THICKNESS_DP,
    /** Gap at the centre, so a shot lands where the eye expects. */
    val centreGapDp: Int = 0,
    val opacityPercent: Int = 90,
    val rotationDegrees: Int = 0,
    val colorArgb: Int = DEFAULT_COLOR,
    val showDot: Boolean = false,
    val showOutline: Boolean = true,
    /** 0..1 of the screen; 0.5/0.5 is centred, which is where a new preset starts. */
    val xFraction: Float = 0.5f,
    val yFraction: Float = 0.5f,
    /**
     * A PNG the user imported, copied into app storage. Null for the drawn designs.
     *
     * Custom images are allowed for the same reason the drawn shapes are — they are pixels in
     * GameCore's window — but they are re-encoded on import rather than stored as received, so
     * a malformed file cannot reach the overlay renderer.
     */
    val imagePath: String? = null,
) {
    val usesImage: Boolean get() = design == CrosshairDesign.CUSTOM_IMAGE && imagePath != null

    fun normalised(): CrosshairPreset = copy(
        sizeDp = sizeDp.coerceIn(MIN_SIZE_DP, MAX_SIZE_DP),
        thicknessDp = thicknessDp.coerceIn(MIN_THICKNESS_DP, MAX_THICKNESS_DP),
        centreGapDp = centreGapDp.coerceIn(0, MAX_SIZE_DP / 2),
        opacityPercent = opacityPercent.coerceIn(MIN_OPACITY_PERCENT, 100),
        rotationDegrees = ((rotationDegrees % 360) + 360) % 360,
        xFraction = xFraction.coerceIn(0f, 1f),
        yFraction = yFraction.coerceIn(0f, 1f),
    )

    companion object {
        const val DEFAULT_SIZE_DP = 28
        const val MIN_SIZE_DP = 8
        const val MAX_SIZE_DP = 120
        const val DEFAULT_THICKNESS_DP = 2
        const val MIN_THICKNESS_DP = 1
        const val MAX_THICKNESS_DP = 12

        /** Same reasoning as the HUD widget: an invisible overlay cannot be switched off. */
        const val MIN_OPACITY_PERCENT = 15

        const val DEFAULT_COLOR = 0xFF00E5FF.toInt()
        const val MAX_NAME_LENGTH = 40

        fun default(name: String = "Crosshair") = CrosshairPreset(name = name)
    }
}

/**
 * The shapes the renderer knows how to draw.
 *
 * Each is drawn with Compose primitives at the size and thickness the preset specifies —
 * there are no bundled bitmaps, so every design is crisp at every size and the APK carries no
 * image assets for them.
 *
 * Deliberately shorter than the list of crosshairs a user can actually make, because most of the shapes
 * people ask for by name are already reachable by combining an entry here with a field on
 * [CrosshairPreset]. A cross with a hole in the middle is [CROSS] with [CrosshairPreset.centreGapDp]; a
 * cross with a centre dot is [CROSS] with [CrosshairPreset.showDot]; a diamond is [BOX] with
 * [CrosshairPreset.rotationDegrees] at 45; a T with a gap is [T_SHAPE] with the same gap slider. Adding
 * entries for those would give two ways to express one crosshair — so two rows in the database that draw
 * the same thing and disagree about which fields matter — and each one would need its own line in the
 * renderer's centre-dot guard, which is already the shape of exception this avoids. An entry is added here
 * only when no combination of the existing ones produces it, which is the test [BOX] passed and a diamond
 * did not.
 */
enum class CrosshairDesign(val label: String, val description: String) {
    CROSS("Cross", "Four lines with an adjustable centre gap."),
    DOT("Dot", "A single filled circle."),
    CIRCLE("Circle", "An open ring."),
    CIRCLE_DOT("Circle and dot", "A ring with a centre dot."),
    CROSS_CIRCLE("Cross in circle", "Four lines inside a ring."),
    T_SHAPE("T", "Left, right and bottom lines — nothing above the centre."),
    X_SHAPE("X", "Four diagonal lines."),
    CHEVRON("Chevron", "A downward arrowhead."),
    BRACKETS("Brackets", "Four corner marks around an open centre."),

    /**
     * A closed square outline.
     *
     * Added against the renderer's own note beside [BRACKETS] — "a square is not a crosshair" — because that
     * comment is arguing a narrower point than it sounds like. It is there to explain why the bracket arms
     * stop short: brackets that grow until they meet stop reading as four marks and become one shape, so
     * *drifting* into a square is a bug in the brackets. Asking for a square deliberately is a different
     * request. It is what a player wants when the thing being aimed at is a vehicle or a doorway rather than
     * a head — an outline that frames a target rather than marking a point inside it — and no combination of
     * the other nine produces it.
     *
     * It also earns its place by what it unlocks: a square turned 45° is a diamond, which is why there is no
     * `DIAMOND` entry. See the note on redundant designs below.
     */
    BOX("Box", "A square outline, for framing a target rather than marking a point."),

    CUSTOM_IMAGE("Custom image", "A PNG you imported, drawn at the size you choose."),
    ;

    val isDrawn: Boolean get() = this != CUSTOM_IMAGE
}

/**
 * The colours a crosshair can be drawn in without opening a picker.
 *
 * A short fixed list in front of the full picker rather than instead of it, chosen for contrast against a
 * game rather than for prettiness: a crosshair is only useful if the eye finds it instantly on whatever is
 * behind it. White and cyan read on the most scenes, red is here because it is what people expect, and the
 * dark outline switch covers the cases none of them survive on their own. Anything not here is two taps
 * away through "More colours", and what the user mixes there joins the row as [CustomCrosshairColours].
 *
 * Longer than the HUD's list because a crosshair is one shape on one background, where a HUD is text that
 * has to stay readable — the constraint is looser, so there is room for the colours people ask for.
 *
 * Here in the model rather than beside the crosshair screen that first needed it, because it is now read
 * from two places that must offer the same colours: the screen's swatch row, and the quick-pick row the
 * control panel opens over a game. Those are a settings screen and a service, so a constant living in
 * either one would have the other importing across a layer to read a list of eight integers.
 */
val CROSSHAIR_COLOURS: List<Int> = listOf(
    0xFFFFFFFF.toInt(),
    0xFF00E5FF.toInt(),
    0xFF4CE07A.toInt(),
    0xFFC6FF00.toInt(),
    0xFFFFB300.toInt(),
    0xFFFF5A87.toInt(),
    0xFFFF1744.toInt(),
    0xFF9C6BFF.toInt(),
)

/**
 * The colours the user mixed, and the storage format for them.
 *
 * A short list kept beside [CROSSHAIR_COLOURS] rather than an unbounded history, and remembered rather
 * than merely applied, because the reason a picker needs persistence is not the colour on the current
 * crosshair — that is already stored on the preset — but the *second* crosshair the user wants in the
 * same shade. Mixing a team colour once and then hunting for it again in an HSV square is the failure
 * this exists to prevent.
 *
 * Every rule here is enforced in [normalise], which both the read and the write go through, so a value
 * that reaches storage past a future caller still comes back clean:
 *
 * - **Opaque.** The alpha byte is forced to `0xFF`. A crosshair's transparency is
 *   [CrosshairPreset.opacityPercent] and lives on the preset, so a colour carrying its own alpha would
 *   give one visible property two owners — and a user who dragged opacity to 100 and still had a faint
 *   crosshair would have no control on any screen that explained it.
 * - **Most recent first, and distinct.** The list is a history, so the colour just mixed belongs at the
 *   front where the eye starts.
 * - **Capped at [MAX].** Two rows of four beside the eight built in. Past that the row stops being a
 *   shortcut and becomes a second thing to search.
 * - **No duplicates of the built-in row.** A colour already in [CROSSHAIR_COLOURS] is dropped, because
 *   the same swatch twice in one row reads as a rendering bug rather than as a history.
 *
 * The format is plain 8-digit hex per entry joined by `|`, which is what [encode] writes and [decode]
 * reads. Hex rather than the signed decimal `Int.toString` produces, because these are read back by a
 * human exactly once — when something has gone wrong in a preferences file — and `FF00E5FF` is a colour
 * where `-16723969` is a number. Nothing here is user-supplied text: the picker hands over an integer it
 * computed from slider positions, so there is no string to sanitise, only a format to refuse.
 */
object CustomCrosshairColours {

    /** Two rows of four under the built-in eight. See the class KDoc for why there is a limit at all. */
    const val MAX = 8

    /** Same `|` the rest of the settings file uses for ordered lists. Cannot occur in a hex digit. */
    private const val SEPARATOR = "|"

    private const val OPAQUE = 0xFF000000.toInt()

    private const val HEX_DIGITS = 8

    fun normalise(colours: List<Int>): List<Int> = colours
        .map { it or OPAQUE }
        .filterNot { it in CROSSHAIR_COLOURS }
        .distinct()
        .take(MAX)

    /**
     * [argb] moved to the front of [existing].
     *
     * A separate function from [normalise] because "remember this one" is the only way the list ever
     * grows, and putting the new colour at the head before normalising is what makes the cap drop the
     * *oldest* entry rather than refuse the newest. A cap that rejected new colours once it was full
     * would leave the user unable to add a ninth without knowing why.
     */
    fun remember(existing: List<Int>, argb: Int): List<Int> = normalise(listOf(argb) + existing)

    fun encode(colours: List<Int>): String = normalise(colours)
        .joinToString(SEPARATOR) { "%08X".format(it) }

    /**
     * Reads [raw] back, dropping anything that is not an 8-digit hex number.
     *
     * Absent, empty and malformed all come back as an empty list rather than as a default set, because
     * unlike the pill's stat list there is nothing sensible to fall back to — a *custom* colour GameCore
     * chose is not custom. A single unparseable entry costs that entry and nothing else.
     */
    fun decode(raw: String?): List<Int> {
        if (raw.isNullOrBlank()) return emptyList()
        return normalise(
            raw.split(SEPARATOR).mapNotNull { part ->
                val text = part.trim()
                if (text.length != HEX_DIGITS) return@mapNotNull null
                text.toUIntOrNull(radix = 16)?.toInt()
            },
        )
    }
}
