package com.gamecore.core.plugin

/**
 * One durable line of plugin automation history, as the user's review screen reads it back.
 *
 * Where an [AuditEntry] is the live, in-flight record a single call produces and
 * [PluginAuditLog] keeps a bounded ring of them in memory for the current process, an
 * [AutomationHistoryRecord] is that same event *after* it has been handed to the history
 * store and given a stable [id] — the identity the list is ordered and paged by, and the one
 * the store trims by when it reaches its cap. The id is assigned by the store, never by the
 * plugin or the caller, so two events can never collide on it and a plugin cannot forge the
 * ordering of its own history.
 *
 * The [outcome] is carried as the plain name of [AuditEntry.Outcome] rather than the enum
 * itself, on purpose: this record is what crosses the persistence boundary, and storing the
 * stable string keeps a row written by an older build readable by a newer one even if the
 * enum later grows a case — the UI maps a name it does not recognise to a neutral label
 * rather than failing to decode the row. Everything else is copied straight from the entry,
 * which has already capped and sanitised [detail] before it ever reached the audit trail, so
 * no secret, token, raw command, or PII can arrive here. [durationMillis] is nullable because
 * not every operation is timed — a denied-before-it-ran call has no duration to report.
 */
data class AutomationHistoryRecord(
    val id: Long,
    val timestampMillis: Long,
    val pluginId: String,
    val operation: String,
    val outcome: String,
    val detail: String,
    val durationMillis: Long?,
)

/**
 * The persistence port for the plugin automation history — the long-lived record the user
 * reviews, as opposed to the volatile [PluginAuditLog] ring that lives only as long as the
 * process. The app binds this to a database-backed implementation; tests bind an in-memory
 * fake. Defining it as an interface here, holding no Android types and doing no IO in its
 * contract, is the same move [PluginGrantStore] makes: it keeps the policy and the review
 * logic above it a plain JVM unit test, and confines the storage engine to one place that a
 * device can exercise.
 *
 * The store is append-only from a plugin's point of view — a plugin can cause a line to be
 * written by acting, but there is deliberately no API by which plugin data edits or removes a
 * line already written. Only the user, through [clear], can erase history; a plugin that
 * misbehaves cannot scrub the evidence of it. Retention is bounded by [MAX_RETAINED] so the
 * log cannot grow without limit into its own denial of service; past the cap the oldest line
 * goes first, because the review screen is about what a plugin did *recently* and the live
 * ring already covers the immediate tail.
 */
interface PluginAutomationHistoryStore {

    /**
     * Record one [entry] as a new line, stamping it with the next [AutomationHistoryRecord.id]
     * and attaching [durationMs] (null when the operation was not timed). The entry's fields
     * are copied as-is — [append] is a sink, not a policy point, so it neither re-checks nor
     * re-sanitises what the audit trail already settled.
     */
    suspend fun append(entry: AuditEntry, durationMs: Long?)

    /**
     * The most recent lines, newest first, at most [limit] of them. With [pluginId] null the
     * history is read across every plugin (the "all plugins" review); with it set the result
     * is confined to that one plugin. A non-positive [limit] returns nothing rather than
     * everything, so a caller that computes a limit of zero cannot accidentally pull the
     * whole table.
     */
    suspend fun recent(pluginId: String? = null, limit: Int = 100): List<AutomationHistoryRecord>

    /**
     * Erase history. With [pluginId] null the whole log is cleared — the user's "clear all";
     * with it set only that plugin's lines go, which is also what an uninstall calls so a
     * removed plugin leaves no history behind to be read against a later plugin that happens
     * to reuse its id.
     */
    suspend fun clear(pluginId: String? = null)

    companion object {
        /**
         * The ceiling on lines the store keeps. Comfortably more than the review screen ever
         * shows at once, so a user scrolling recent activity never hits it, and small enough
         * that the table stays a trivial read on a phone — past it the oldest line is dropped
         * on each append, the same bound-the-cost reasoning [PluginAuditLog] applies to its
         * per-plugin ring, applied here to the durable log as a whole.
         */
        const val MAX_RETAINED = 500
    }
}
