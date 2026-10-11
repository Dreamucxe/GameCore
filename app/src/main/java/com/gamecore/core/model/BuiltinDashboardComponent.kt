package com.gamecore.core.model

/**
 * The host-provided components a dashboard can hold alongside plugin widgets.
 *
 * These are the one thing the Home plugin-widgets section does not offer: panels drawn by GameCore itself
 * from the user's own recorded data — game sessions and Aim Lab runs — rather than by a plugin. They read
 * only data the host already owns and the user already sees elsewhere in the app, so each exists with no
 * plugin, no grant and no data scope; [dataSummary] states, in the composer, exactly which of the user's
 * own records a component reads, so adding one is never a blind choice.
 *
 * Every component renders from already-stored data. When that data is too thin to be worth drawing — too
 * few runs for a trend, no sessions yet — the component shows its own honest empty state rather than a
 * zeroed or invented figure. A built-in is therefore always *available* (the host can always draw it); only
 * its data may be empty, which is a different thing and said differently.
 */
enum class BuiltinDashboardComponent(
    val id: String,
    val title: String,
    val summary: String,
    val dataSummary: String,
) {
    GAME_PLAYTIME(
        id = "game_playtime",
        title = "Playtime",
        summary = "Total time GameCore has tracked across your games.",
        dataSummary = "Reads your recorded game sessions. No plugin or permission involved.",
    ),
    GAME_STATS(
        id = "game_stats",
        title = "Session stats",
        summary = "Sessions recorded, your longest session, and average battery drain while playing.",
        dataSummary = "Reads your recorded game sessions. No plugin or permission involved.",
    ),
    AIMLAB_TREND(
        id = "aimlab_trend",
        title = "Aim Lab accuracy trend",
        summary = "Your accuracy across recent 3D training runs.",
        dataSummary = "Reads your Aim Lab run history (3D runs only). No plugin or permission involved.",
    ),
    AIMLAB_SUMMARY(
        id = "aimlab_summary",
        title = "Aim Lab summary",
        summary = "How many 3D runs you've recorded and your best and average accuracy.",
        dataSummary = "Reads your Aim Lab run history (3D runs only). No plugin or permission involved.",
    ),
    ;

    companion object {
        val ALL: List<BuiltinDashboardComponent> = entries

        private val byId = entries.associateBy { it.id }

        /** The component for a stored id, or null if a newer build stored one this build no longer knows. */
        fun fromId(id: String): BuiltinDashboardComponent? = byId[id.trim()]
    }
}
