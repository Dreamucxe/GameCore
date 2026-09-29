package com.gamecore.core.model

/**
 * The grades the hunting filter (§Hunt) can lay over the game.
 *
 * A closed set on purpose, in the §32 sense: the picker renders one card per entry and the service's
 * draw path branches on it with no `else`, so a new look cannot be added without the compiler pointing
 * at every place that must handle it. Persisted by [name] and read back through [of], which falls to the
 * capture-free [MOVIE] whenever a stored name no longer maps — the one grade that is always safe to draw.
 *
 * [needsCapture] is the honest split. [MOVIE] tints and vignettes GameCore's own transparent overlay and
 * needs no projection, so it can come up the instant the toggle is hit and can be restored across a
 * process death. The other three re-grade the actual screen, which means reading the shared capture feed
 * (the same one the loupe and Scout draw from), transforming each frame and painting it back opaque — so
 * they only run while a projection is live and are never silently restored. None of them read game
 * internals or another app's memory: every grade is a function of the pixels already on the glass.
 */
enum class HuntFilter(
    val label: String,
    val description: String,
    val needsCapture: Boolean,
) {
    /** Warm cinematic tint with a soft vignette. Drawn over GameCore's own surface — no capture, always available. */
    MOVIE(
        label = "Movie",
        description = "Warm cinematic tint and a soft vignette. Draws instantly with no screen capture.",
        needsCapture = false,
    ),

    /** High-contrast desaturated readout that pushes edges and mid-tones apart so shapes read fast. */
    INSTRUMENT(
        label = "Instrument",
        description = "High-contrast, near-monochrome readout that makes shapes and edges stand out.",
        needsCapture = true,
    ),

    /** Colour inversion — the classic accessibility grade repurposed to separate targets from a busy scene. */
    FILM(
        label = "Film",
        description = "Inverts colour like a photographic negative to separate targets from the background.",
        needsCapture = true,
    ),

    /** Hard, near-two-tone black-and-white grade that reduces the scene to stark light and dark. */
    SKETCH(
        label = "Sketch",
        description = "Hard black-and-white grade that reduces the scene to stark light and dark.",
        needsCapture = true,
    );

    companion object {
        /** The default and the fallback: the one grade that needs no projection. */
        val DEFAULT = MOVIE

        /** Resolve a persisted [name], falling to [DEFAULT] for anything unknown. */
        fun of(name: String?): HuntFilter = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
