package com.gamecore.data.plugin

import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.plugin.AuditEntry
import com.gamecore.core.plugin.AutomationHistoryRecord
import com.gamecore.core.plugin.PluginAutomationHistoryStore
import com.gamecore.data.database.PluginAutomationEventDao
import com.gamecore.data.database.PluginAutomationEventEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The database-backed [PluginAutomationHistoryStore] — the production binding of the port.
 *
 * This is the device half of the split the port describes: the policy and review logic above it stay
 * a pure JVM unit test against an in-memory fake, and everything that needs a real database is
 * confined here, exactly as [SecurePluginGrantStore] confines the grant store's storage engine. The
 * store holds no plugin-trust decisions of its own — [append] is a sink, not a policy point, so it
 * copies the already-settled [AuditEntry] through without re-checking or re-sanitising what the audit
 * trail decided.
 *
 * Every method hops to [io] before touching Room: Room's suspend DAOs already move their own query
 * off the caller's dispatcher, but doing the hop here keeps the whole operation — the insert and the
 * trim that follows it, the limit guard and the read — on one IO context, and matches how every other
 * repository in this app frames its database work.
 */
@Singleton
class RoomPluginAutomationHistoryStore @Inject constructor(
    private val dao: PluginAutomationEventDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) : PluginAutomationHistoryStore {

    /**
     * Appends one line, then trims the log back to [PluginAutomationHistoryStore.MAX_RETAINED].
     *
     * The trim runs on every append rather than periodically, so the table can never sit above its
     * cap between a write and some later sweep — the bound is maintained as an invariant, not an
     * eventual property. [AuditEntry.outcome] is stored by name for the forward-compatibility reason
     * [AutomationHistoryRecord] documents; everything else is copied straight across.
     */
    override suspend fun append(entry: AuditEntry, durationMs: Long?) = withContext(io) {
        dao.insert(
            PluginAutomationEventEntity(
                timestampMillis = entry.timestamp,
                pluginId = entry.pluginId,
                operation = entry.operation,
                outcome = entry.outcome.name,
                detail = entry.detail,
                durationMillis = durationMs,
            ),
        )
        dao.trimToCap(PluginAutomationHistoryStore.MAX_RETAINED)
    }

    /**
     * The newest [limit] lines, across every plugin when [pluginId] is null or scoped to it when set.
     *
     * A non-positive [limit] returns nothing without issuing a query at all, which is the point of the
     * guard: a caller that computes a limit of zero cannot accidentally pull the whole table, and the
     * DAO's `LIMIT :limit` is never handed a value that would mean "no bound".
     */
    override suspend fun recent(pluginId: String?, limit: Int): List<AutomationHistoryRecord> {
        if (limit <= 0) return emptyList()
        return withContext(io) {
            val rows = if (pluginId == null) dao.recent(limit) else dao.recentFor(pluginId, limit)
            rows.map { it.toRecord() }
        }
    }

    /**
     * Erases history — the whole log when [pluginId] is null, otherwise just that plugin's lines.
     *
     * The scoped form is also what an uninstall calls, so a removed plugin leaves no history to be
     * read against a later plugin that reuses its id.
     */
    override suspend fun clear(pluginId: String?) = withContext(io) {
        if (pluginId == null) dao.clearAll() else dao.clearFor(pluginId)
    }
}

/**
 * Maps a stored row to the port's [AutomationHistoryRecord]. A straight copy: the row already holds
 * [PluginAutomationEventEntity.outcome] as a name and [PluginAutomationEventEntity.detail] sanitised,
 * so there is nothing to translate or re-check on the way out.
 */
private fun PluginAutomationEventEntity.toRecord(): AutomationHistoryRecord =
    AutomationHistoryRecord(
        id = id,
        timestampMillis = timestampMillis,
        pluginId = pluginId,
        operation = operation,
        outcome = outcome,
        detail = detail,
        durationMillis = durationMillis,
    )
