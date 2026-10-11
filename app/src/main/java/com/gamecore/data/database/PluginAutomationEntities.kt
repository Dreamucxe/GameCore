package com.gamecore.data.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * One durable line of plugin automation history, as it lives on disk (schema version 17).
 *
 * This is the database backing of the [com.gamecore.core.plugin.PluginAutomationHistoryStore] port:
 * where [com.gamecore.core.plugin.PluginAuditLog] keeps a bounded ring of recent calls in memory for
 * the current process, this table is the long-lived record the user reviews after the fact — the one
 * that survives a process death, so a plugin that behaves in review but misbehaves overnight still
 * leaves a trail. Each row is one [com.gamecore.core.plugin.AuditEntry] that has crossed the
 * persistence boundary and been given a stable [id].
 *
 * The [id] is `autoGenerate` on purpose, and it is the whole of why a plugin cannot forge the order
 * of its own history: the identity a line is ordered, paged and trimmed by is the database rowid,
 * assigned by SQLite at insert, never a value the plugin or the caller supplies. Two events can
 * therefore never collide on it, and a plugin cannot renumber itself to the top of the list or push
 * an incriminating line off the bottom.
 *
 * [outcome] is stored as the plain name of [com.gamecore.core.plugin.AuditEntry.Outcome], not the
 * enum, for the same forward-compatibility reason [com.gamecore.core.plugin.AutomationHistoryRecord]
 * documents: a row written by an older build stays readable by a newer one even if the enum later
 * grows a case, and the UI maps a name it does not recognise to a neutral label rather than failing
 * to decode the row. [detail] has already been capped and sanitised by the audit trail before it ever
 * reaches here — no secret, token, raw command, or PII can arrive — so this table stores it verbatim
 * and adds no sanitising of its own. [durationMillis] is nullable because not every operation is
 * timed: a call denied before it ran has no duration to report.
 *
 * There is deliberately no foreign key to any plugin table. History outlives the plugin that wrote
 * it: an uninstall clears a plugin's lines through the explicit `clearFor` path (so a reused plugin
 * id cannot read a predecessor's history), never as a cascade from some other delete — the same
 * "a record is forgotten only when the user asks" discipline [ConfigBackupEntity] keeps. Columns
 * carry the entity's own property names rather than snake_case `@ColumnInfo` aliases because this is a
 * self-contained new table read only through this DAO, and the [Index] below names the column by that
 * same spelling; there is no legacy name to preserve and no cross-table join that wants one.
 *
 * No `@TypeConverter` is involved, consistent with [GameCoreDatabase]: the one enum here is stored as
 * its name and parsed back defensively above the persistence line, where an unrecognised name becomes
 * a sane label instead of a thrown exception in generated code.
 */
@Entity(
    tableName = "plugin_automation_events",
    // The review screen reads one plugin's recent lines, and both an uninstall and the user's
    // per-plugin "clear" delete by plugin id. Indexing pluginId keeps those from scanning the whole
    // table as it fills toward its retention cap.
    indices = [Index("pluginId")],
)
data class PluginAutomationEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** When the underlying event happened, copied from [com.gamecore.core.plugin.AuditEntry.timestamp]. */
    val timestampMillis: Long,

    /** The plugin the line belongs to; the scope of a per-plugin review, clear and uninstall. */
    val pluginId: String,

    /** The API operation the plugin invoked, e.g. `setRefreshRate`. */
    val operation: String,

    /** [com.gamecore.core.plugin.AuditEntry.Outcome] by name — see the class doc for why a name, not the enum. */
    val outcome: String,

    /** The already-sanitised short detail; never a secret, token, raw command, or PII. */
    val detail: String,

    /** Operation duration, or null when the call was not timed (e.g. denied before it ran). */
    val durationMillis: Long?,
)

/**
 * The plugin automation history table.
 *
 * Append-only from a plugin's point of view: a plugin can cause a line to be written by acting
 * ([insert]), but there is no query here by which plugin data edits or removes a line already
 * written. Only the user, through [clearAll]/[clearFor], erases history, and an uninstall uses the
 * same per-plugin delete — a misbehaving plugin cannot scrub the evidence of itself. Retention is
 * bounded by [trimToCap] so the log cannot grow without limit into its own denial of service; past
 * the cap the oldest line goes first, which is the one the review screen cares least about because
 * the in-memory ring already covers the immediate tail.
 *
 * Reads are newest-first and always carry a `LIMIT`: there is no unbounded "all rows" query, because
 * no screen wants one and a table that fills to its cap should not offer a way to pull all of it into
 * memory — the same restraint [ConfigBackupDao] keeps.
 */
@Dao
interface PluginAutomationEventDao {

    /** Appends one line and returns its freshly assigned rowid. Plain insert: the autoincrement id never collides. */
    @Insert
    suspend fun insert(event: PluginAutomationEventEntity): Long

    /**
     * The most recent lines across every plugin, newest first, at most [limit].
     *
     * Ordered by [PluginAutomationEventEntity.timestampMillis] descending with the rowid as the
     * tiebreak, so lines sharing a timestamp still come back in a stable, insertion-order sequence
     * rather than an order SQLite is free to vary between reads.
     */
    @Query("SELECT * FROM plugin_automation_events ORDER BY timestampMillis DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<PluginAutomationEventEntity>

    /** The most recent lines for one plugin, newest first, at most [limit]. Scoped form of [recent]. */
    @Query(
        "SELECT * FROM plugin_automation_events WHERE pluginId = :pluginId " +
            "ORDER BY timestampMillis DESC, id DESC LIMIT :limit",
    )
    suspend fun recentFor(pluginId: String, limit: Int): List<PluginAutomationEventEntity>

    /** How many lines the table holds. A `COUNT(*)`, not a list — for the trim decision and diagnostics. */
    @Query("SELECT COUNT(*) FROM plugin_automation_events")
    suspend fun count(): Int

    /**
     * Drops every line but the newest [cap], across all plugins.
     *
     * The cap is global, matching the port's "bound the durable log as a whole": the newest [cap]
     * rowids are kept and everything older is deleted, so an append followed by a trim can never leave
     * the table above its ceiling. Keyed on the rowid rather than the timestamp because the rowid is
     * the identity the store assigns in insert order, so "the oldest lines" is unambiguous even if two
     * events shared a timestamp or a clock stepped backward. A no-op while the table is at or under
     * the cap, since the subquery then names every row.
     */
    @Query(
        "DELETE FROM plugin_automation_events WHERE id NOT IN (" +
            "SELECT id FROM plugin_automation_events ORDER BY id DESC LIMIT :cap)",
    )
    suspend fun trimToCap(cap: Int)

    /** The user's "clear all": erases the whole log. */
    @Query("DELETE FROM plugin_automation_events")
    suspend fun clearAll()

    /** Erases one plugin's lines — the user's per-plugin clear, and what an uninstall calls. */
    @Query("DELETE FROM plugin_automation_events WHERE pluginId = :pluginId")
    suspend fun clearFor(pluginId: String)
}
