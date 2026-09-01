package com.gamecore.data.repository

import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.shizuku.SettingsNamespace
import com.gamecore.core.shizuku.WritableSetting
import com.gamecore.data.database.RestorePointDao
import com.gamecore.data.database.RestorePointEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The settings GameCore has changed and still owes the device back.
 *
 * This is the table that makes a profile reversible across a process death, and the rule that makes
 * it correct is in [record]: **the first value recorded for a key wins**. Applying a profile reads
 * the current value and records it before writing; if a second write touches the same key later in
 * the same session — a control panel slider after a profile applied, say — recording again would
 * overwrite the pre-GameCore value with a value GameCore itself wrote, and the restore would put the
 * device back to a state the user never chose. `@Insert(OnConflictStrategy.IGNORE)` in the DAO is
 * what enforces it, at the primary key, rather than a read-then-write here that two coroutines could
 * interleave.
 *
 * Rows are keyed by the provider's own namespace and key strings, not by [WritableSetting]'s enum
 * name. A future GameCore that drops a key from its allow-list can still restore a value this
 * version wrote, because restoring a value the app itself recorded is safe regardless of whether the
 * app would still choose to write it.
 */
@Singleton
class RestorePointRepository @Inject constructor(
    private val dao: RestorePointDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * Records the value a setting had before GameCore touched it.
     *
     * [previousValue] is null when the key was unset — which is different from "0" and has to be
     * restorable as unset, because a key the platform was defaulting is not a key with a value.
     *
     * Called *before* the write, never after. A restore point recorded after a successful write
     * would hold the new value.
     */
    suspend fun record(
        setting: WritableSetting,
        previousValue: String?,
        packageName: String?,
        nowMillis: Long = System.currentTimeMillis(),
    ) = withContext(io) {
        dao.insertIfAbsent(
            RestorePointEntity(
                namespace = setting.namespace.token,
                key = setting.key,
                previousValue = previousValue,
                packageName = packageName,
                recordedAtMillis = nowMillis,
            ),
        )
    }

    /**
     * Records device state that is not a `settings` key at all.
     *
     * Media volume goes through `AudioManager` and Do Not Disturb through the notification-policy
     * API; neither has a `settings` key an app can reliably write, so neither can be described by a
     * [WritableSetting]. They still have to be restorable, because a game session that ends with the
     * phone silent is the same failure as one that ends with the display pinned.
     *
     * Stored under [NON_SETTING_NAMESPACE], which is not one of [SettingsNamespace]'s tokens, so
     * these rows resolve to a null [PendingRestore.setting] and the restore path dispatches on
     * [key] rather than trying to `settings put` them.
     */
    suspend fun record(
        key: String,
        previousValue: String?,
        packageName: String?,
        nowMillis: Long = System.currentTimeMillis(),
    ) = withContext(io) {
        dao.insertIfAbsent(
            RestorePointEntity(
                namespace = NON_SETTING_NAMESPACE,
                key = key,
                previousValue = previousValue,
                packageName = packageName,
                recordedAtMillis = nowMillis,
            ),
        )
    }

    /**
     * Everything still owed back, oldest first.
     *
     * Ordered by when it was recorded so a restore replays in the order the writes happened. It
     * matters for the refresh-rate pair: writing `peak` below the current `min` is rejected by some
     * builds, so restoring them in recording order is the only sequence guaranteed to be accepted.
     */
    suspend fun pending(): List<PendingRestore> = withContext(io) {
        dao.all()
            .sortedBy { it.recordedAtMillis }
            .map { entity ->
                PendingRestore(
                    namespace = entity.namespace,
                    key = entity.key,
                    previousValue = entity.previousValue,
                    packageName = entity.packageName,
                    recordedAtMillis = entity.recordedAtMillis,
                    setting = resolve(entity.namespace, entity.key),
                )
            }
    }

    /** For the dashboard's "settings pending restore" note. A `COUNT(*)`, not a list. */
    suspend fun pendingCount(): Int = withContext(io) { dao.count() }

    /**
     * Removes a row once the value has actually gone back.
     *
     * Called only after the write was read back and confirmed. A restore that is cleared on the
     * strength of the call not throwing would leave the device changed and GameCore convinced it
     * had tidied up — the same failure this app refuses everywhere else.
     */
    suspend fun clear(namespace: String, key: String) = withContext(io) {
        dao.delete(namespace, key)
    }

    suspend fun clear(setting: WritableSetting) = clear(setting.namespace.token, setting.key)

    /**
     * Drops every row without restoring anything.
     *
     * Two callers, both deliberate: the user choosing "forget pending changes" after being told what
     * that means, and the point where a restore has failed so many times that retrying on every
     * launch is worse than stopping. Not called as cleanup after a successful restore — that uses
     * [clear] per row, so a partial restore leaves the rest still owed.
     */
    suspend fun forgetAll() = withContext(io) { dao.deleteAll() }

    private fun resolve(namespace: String, key: String): WritableSetting? =
        WritableSetting.entries.firstOrNull {
            it.key == key && it.namespace.token == namespace
        }

    companion object {
        /**
         * The namespace for state that is not a system setting.
         *
         * Deliberately not one of [SettingsNamespace]'s tokens, so nothing can mistake one of these
         * rows for something `settings put` could restore.
         */
        const val NON_SETTING_NAMESPACE = "gamecore"

        /** Percent, as the user's own level before a profile changed it. */
        const val KEY_MEDIA_VOLUME = "media_volume"

        /** The [android.app.NotificationManager] interruption filter, by name. */
        const val KEY_DO_NOT_DISTURB = "do_not_disturb"
    }
}

/**
 * One setting waiting to be put back.
 *
 * [setting] is null when the stored key is not in this build's allow-list — the case the table was
 * designed for. The restore still runs: [namespace] and [key] are all `settings put` needs, and
 * [describe] gives the UI something to show without a `WritableSetting` to read a description from.
 */
data class PendingRestore(
    val namespace: String,
    val key: String,
    val previousValue: String?,
    val packageName: String?,
    val recordedAtMillis: Long,
    val setting: WritableSetting?,
) {
    /** True when the key was unset before GameCore wrote it, so the restore clears it. */
    val restoresToUnset: Boolean get() = previousValue == null

    val resolvedNamespace: SettingsNamespace?
        get() = SettingsNamespace.entries.firstOrNull { it.token == namespace }

    /**
     * A line for the UI.
     *
     * Falls back to the raw key rather than to "Unknown setting": a user looking at a pending
     * restore they want to understand is better served by `peak_refresh_rate` than by a placeholder.
     */
    fun describe(): String = setting?.userDescription ?: key
}
