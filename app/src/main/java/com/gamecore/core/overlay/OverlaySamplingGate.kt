package com.gamecore.core.overlay

/**
 * Whether the overlay needs live readings right now.
 *
 * Sampling is the expensive thing the overlay does. The pill and the HUD are fed by a loop that holds a
 * subscription to the shared performance sampler, and that sampler reads `/proc`, `/sys`, the battery and
 * the network counters every tick. Deciding when that loop may run is therefore a battery decision, and it
 * is the one decision in the overlay worth stating as a function rather than as a condition buried in a
 * `combine`.
 *
 * Two independent questions, ANDed:
 *
 *  **Is anything on screen that shows a reading?** The pill, the HUD and the full panel each display
 *  sampled figures, so any one of them being up is reason enough to sample. A floating button on its own
 *  is not — it draws a thermal dot, but the dot is defined to be absent until a sample lands, so a button
 *  alone costs nothing and is meant to.
 *
 *  **Can the user see it?** This is the part that was missing. The readings loop is a `delay()` in a
 *  foreground-service scope and the app holds a doze exemption, so with the display off it kept ticking —
 *  sampling, allocating and publishing into windows nobody was looking at, for as long as the phone sat in
 *  a pocket with the overlay still requested. That is the §9 leak.
 *
 * The quick sheet is deliberately **not** a term here. It shows a game label, a session clock and the two
 * level sliders, all of which come from the panel probe rather than from the sampler, so opening it must not
 * start a sampling loop. Adding it would look like a consistency fix and would actually be a regression.
 *
 * Pure, so the rule can be asserted without an Android device, a display or a running sampler.
 */
object OverlaySamplingGate {

    /**
     * @param pillVisible the stats pill is up.
     * @param hudVisible the HUD is up.
     * @param panelOpen the full panel is open (it shows the same readings in its own layout).
     * @param screenInteractive the display is on. False means nothing drawn can be seen.
     */
    fun shouldSample(
        pillVisible: Boolean,
        hudVisible: Boolean,
        panelOpen: Boolean,
        screenInteractive: Boolean,
    ): Boolean = screenInteractive && (pillVisible || hudVisible || panelOpen)
}
