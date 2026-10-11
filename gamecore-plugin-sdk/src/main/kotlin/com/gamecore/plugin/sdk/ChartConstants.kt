package com.gamecore.plugin.sdk

/**
 * The chart kinds a plugin may author, mirroring the host's `ChartKind`.
 *
 * Same mirror-not-shared-type arrangement as every set in [Permissions] and friends: the SDK is a
 * pure-JVM module that cannot import the host enum, so this transcribes it and this module's tests
 * pin the two together. The one drift risk is a kind added host-side that nobody reflected here.
 *
 * The host does **not** reject an unknown `kind` — it falls back to [LINE] — so a kind outside this
 * set is a chart that silently draws as a line rather than one that refuses to load. Writing the
 * kind as a named constant is what turns that silent fallback into a compile error in the plugin's
 * own build.
 */
object ChartKinds {
    const val LINE = "line"
    const val BAR = "bar"

    val ALL = setOf(LINE, BAR)
}

/**
 * The bounds the host puts on a declarative chart, mirrored so an author meets them at build time.
 *
 * A chart carries its series as a bounded, delimited numeric string rather than a JSON array,
 * because the node format's prop values are a closed set of primitives with no array among them —
 * a series that rode as an array would be a hole in that guarantee. So the real limit on a *literal*
 * chart is not a point count but the length of that string, and the two caps below exist for the two
 * different hosts a chart can land in:
 *
 *  - [MAX_POINTS] mirrors the host's `MetricHistory.CAPACITY` — the length of the rolling history a
 *    *live* series resolves against, and therefore the most points any chart can meaningfully show.
 *  - [MAX_VALUE_STRING_LENGTH] is the length cap the host's screen validator applies to the values
 *    string itself. It is set to [OverlayBounds.MAX_STRING_LENGTH] because an overlay module's
 *    validator is the tighter of the two (120 against a full screen's 2000): a values string that
 *    fits an overlay fits a screen, so authoring to this one cap keeps a chart legal in either host.
 *    At roughly five characters per point this bounds a literal overlay chart to a sparkline of ~20
 *    points, which is the whole of what a badge-sized plot can show anyway.
 *
 * Where the host would *clamp or fall back*, this SDK **refuses** instead — a chart the host quietly
 * truncated or re-scaled is one the author never saw on their own screen, the same doctrine the rest
 * of this file follows.
 */
object ChartBounds {
    /** The most points a chart can meaningfully carry; mirrors the host `MetricHistory.CAPACITY`. */
    const val MAX_POINTS = 120

    /** A chart plots at most two series — a primary and one comparison line. */
    const val MAX_SERIES = 2

    /**
     * The cap on an emitted `values` / `values2` string, equal to the host's tightest string cap.
     * A literal series whose encoded form is longer is refused by the authoring DSL.
     */
    const val MAX_VALUE_STRING_LENGTH = OverlayBounds.MAX_STRING_LENGTH
}
