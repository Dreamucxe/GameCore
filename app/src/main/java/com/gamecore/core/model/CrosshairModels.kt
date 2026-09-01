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
    CUSTOM_IMAGE("Custom image", "A PNG you imported, drawn at the size you choose."),
    ;

    val isDrawn: Boolean get() = this != CUSTOM_IMAGE
}
