package com.gamecore.aimlab.engine

/**
 * The layout save/load contract, behind an interface so its behaviour is testable without Room (§6/§7).
 *
 * Room cannot run in a JVM unit test on this host, so the rules that broke — a layout must save *with* its
 * controls, and loading must self-heal an empty one — are stated here as a small interface the repository
 * implements over Room and a fake implements in memory. The test then proves the contract on the fake, and
 * the repository is a thin adapter that upholds the same contract against the database.
 *
 * [saveLayout] must persist every control. [loadLayout] must return a layout with controls: if the stored
 * one is empty, it is healed from its finger-count preset ([ControlLayout.healed]) and the repair is
 * written back, so the next load is already whole. [layoutsList] is the list source, and its per-layout
 * control counts must be the real saved rows.
 */
interface LayoutStore {
    suspend fun saveLayout(layout: ControlLayout): Long
    suspend fun loadLayout(id: Long): ControlLayout?
    suspend fun layoutsList(): List<ControlLayout>
}

/**
 * The shared self-heal step both implementations run on load, kept here so the repository and the fake
 * cannot diverge on it. Returns the layout unchanged when it already has controls; otherwise the healed
 * layout, which the caller persists.
 */
fun ControlLayout.healedForLoad(): ControlLayout = if (isEmpty) healed() else this
