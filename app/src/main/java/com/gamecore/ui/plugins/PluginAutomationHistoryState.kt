package com.gamecore.ui.plugins

import com.gamecore.core.plugin.AuditEntry
import com.gamecore.core.plugin.AutomationHistoryRecord

/**
 * What the automation-history screen shows, and the pure rules that turn a trail of records into the
 * rows the user actually sees.
 *
 * This is a **host** screen that renders GameCore's own automation trail. A plugin that was granted the
 * automation-history read scope asked GameCore to show the user this record; the plugin runs no code
 * here and is handed nothing — the registry returns the trail to this screen, never to the plugin. So
 * everything below is about honestly presenting a record GameCore owns, and the filtering, searching and
 * sorting are done here, in memory, over the at-most-500 lines the store returned in one read. There is
 * no pager and this model does not pretend there is one: [records] is the whole working set.
 *
 * Three honesty decisions shape this file:
 *
 *  1. **"Not allowed" and "nothing yet" are different states.** [LoadPhase.Denied] means the plugin does
 *     not hold the scope and the registry refused the read; [LoadPhase.Ready] with an empty [records]
 *     means the read succeeded and nothing has run. The screen draws a different panel for each, because
 *     collapsing them would tell a user who revoked a scope that their automations simply never ran.
 *  2. **An unrecognised outcome is labelled, never dropped.** [outcome][AutomationHistoryRecord.outcome]
 *     is carried as a `String` for forward compatibility — a line written by a build whose
 *     [AuditEntry.Outcome] had grown a case is still readable here. [outcomeLabel] maps the known names to
 *     their words and anything else to its own title-cased form rather than discarding the row.
 *  3. **The counts are derived, not stored.** [totalCount] is read off [records]; the visible count is
 *     [visibleRecords] applied to this state. Nothing caches a filtered figure that could drift from the
 *     list beside it.
 *
 * The reducers are plain top-level functions over plain data for the same reason the consent screen's
 * are: a filter that silently matched the wrong field, or a sort that was not stable, is invisible on a
 * screenshot and a JVM test away from being pinned. They hold no Compose and no Android, so
 * [PluginAutomationHistoryStateTest] exercises them with hand-built records and no device.
 */
enum class LoadPhase {
    /** The first read has not come back yet. */
    Loading,

    /** The plugin does not hold the automation-history scope, so the registry refused the read. */
    Denied,

    /** The read succeeded. [PluginAutomationHistoryUiState.records] may still be empty — nothing ran. */
    Ready,
}

/**
 * The screen's whole state: the loaded trail plus the in-memory view the user has shaped over it.
 *
 * [records] is the raw most-recent-N the registry returned, held once. [query], [outcomeFilter] and
 * [newestFirst] are the user's view controls, and the rows on screen are always [visibleRecords] of this
 * state rather than a second list kept in step by hand. [phase] is the load outcome and is the one thing
 * that decides whether the list is drawn at all.
 */
data class PluginAutomationHistoryUiState(
    val phase: LoadPhase = LoadPhase.Loading,
    /** The most-recent lines the store returned, newest-first as it supplied them; the whole working set. */
    val records: List<AutomationHistoryRecord> = emptyList(),
    /** The case-insensitive search over operation, detail and plugin id. Blank matches everything. */
    val query: String = "",
    /** The outcome the user is filtering to, as the raw outcome string, or null for every outcome. */
    val outcomeFilter: String? = null,
    /** True for newest-first (the default), false for oldest-first. */
    val newestFirst: Boolean = true,
) {
    /** How many lines were loaded, before any search or filter. Derived so it cannot disagree with [records]. */
    val totalCount: Int get() = records.size

    /** The read came back with lines. False in [LoadPhase.Ready] means "nothing has run yet", not "denied". */
    val hasRecords: Boolean get() = records.isNotEmpty()
}

/**
 * The outcome names GameCore itself writes, in [AuditEntry.Outcome]'s own declaration order.
 *
 * Taken from the enum rather than re-typed so the "known" set here can never drift from the names the
 * audit boundary actually stores. Used to order the filter chips (known outcomes first, in this order)
 * and to decide whether a row's outcome has a label and tone of its own or is shown as a neutral
 * title-cased name.
 */
internal val KNOWN_OUTCOME_ORDER: List<String> = AuditEntry.Outcome.entries.map { it.name }

/** The same set, for membership tests. */
internal val KNOWN_OUTCOMES: Set<String> = KNOWN_OUTCOME_ORDER.toSet()

/** Is this one of the outcomes GameCore writes today, as opposed to a name from another build? */
internal fun isKnownOutcome(outcome: String): Boolean = outcome in KNOWN_OUTCOMES

/**
 * A human label for an outcome string, known or not.
 *
 * The known names ("RATE_LIMITED") and anything unrecognised are run through the same title-casing, so a
 * name this build has never seen reads as "Rate limited" would — a neutral, honest label — rather than
 * being dropped or forced to "Other". A genuinely empty string, which the data should never carry, is
 * the one case that has no title to make, so it falls back to "Other".
 */
internal fun outcomeLabel(outcome: String): String {
    val trimmed = outcome.trim()
    if (trimmed.isEmpty()) return "Other"
    return trimmed.lowercase().replace('_', ' ').replaceFirstChar { it.uppercaseChar() }
}

/**
 * The distinct outcomes actually present in [records], known ones first in [KNOWN_OUTCOME_ORDER] and any
 * unrecognised names after, sorted. This is the chip set the screen offers beside "All": a filter for an
 * outcome that is not in the data would be a dead control, so the chips are built from what is there.
 */
internal fun outcomesPresent(records: List<AutomationHistoryRecord>): List<String> {
    val present = records.mapTo(LinkedHashSet()) { it.outcome }
    val known = KNOWN_OUTCOME_ORDER.filter { it in present }
    val unknown = present.filter { it !in KNOWN_OUTCOMES }.sorted()
    return known + unknown
}

/** Keeps only the records with [outcome], or every record when [outcome] is null. Exact-match, as stored. */
internal fun filterByOutcome(
    records: List<AutomationHistoryRecord>,
    outcome: String?,
): List<AutomationHistoryRecord> =
    if (outcome == null) records else records.filter { it.outcome == outcome }

/**
 * Does this record match the search? Case-insensitive over the operation, the (already sanitised) detail
 * and the plugin id — the three fields a user would type a fragment of to find a line. A blank query
 * matches everything, so an empty search box hides nothing.
 */
internal fun matchesSearch(record: AutomationHistoryRecord, query: String): Boolean {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return true
    return record.operation.lowercase().contains(needle) ||
        record.detail.lowercase().contains(needle) ||
        record.pluginId.lowercase().contains(needle)
}

/** Keeps only the records matching [query]; a blank query keeps all of them. */
internal fun filterBySearch(
    records: List<AutomationHistoryRecord>,
    query: String,
): List<AutomationHistoryRecord> =
    if (query.isBlank()) records else records.filter { matchesSearch(it, query) }

/**
 * Orders by timestamp, newest or oldest first, tie-broken by [AutomationHistoryRecord.id] in the same
 * direction so the order is total and stable — two lines sharing a millisecond never swap between
 * recompositions, and the store assigns the id in append order so the higher id is the later line.
 */
internal fun sortByTimestamp(
    records: List<AutomationHistoryRecord>,
    newestFirst: Boolean,
): List<AutomationHistoryRecord> =
    if (newestFirst) {
        records.sortedWith(compareByDescending<AutomationHistoryRecord> { it.timestampMillis }.thenByDescending { it.id })
    } else {
        records.sortedWith(compareBy<AutomationHistoryRecord> { it.timestampMillis }.thenBy { it.id })
    }

/**
 * The rows the screen draws: the loaded records narrowed by the outcome filter, then by the search, then
 * ordered. The composition order is deliberate — filtering before sorting keeps the sort over the
 * smaller set — but the result is the same list the three steps would give in any order, because each is
 * a pure narrowing or reordering of the one before it.
 */
internal fun visibleRecords(state: PluginAutomationHistoryUiState): List<AutomationHistoryRecord> {
    val byOutcome = filterByOutcome(state.records, state.outcomeFilter)
    val bySearch = filterBySearch(byOutcome, state.query)
    return sortByTimestamp(bySearch, state.newestFirst)
}
