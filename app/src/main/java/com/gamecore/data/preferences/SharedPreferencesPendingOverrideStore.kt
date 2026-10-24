package com.gamecore.data.preferences

import android.content.Context
import android.content.SharedPreferences
import com.gamecore.domain.display.PendingOverridePersistence
import com.gamecore.domain.display.StoredPendingOverride
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The pending display override, on disk, in its own file.
 *
 * Deliberately *not* the encrypted preference store. The row is written immediately before `wm size`
 * runs, and read by the first thing `Application.onCreate` schedules — at which point the encrypted
 * store may not be keyed yet, and a row that cannot be read is a row that cannot save a display nobody
 * can read. A plain file in this app's own storage holds three strings and a timestamp and is worth
 * exactly as much trust as that.
 *
 * Every write is `commit()`-equivalent in ordering terms: `apply()` flushes to disk asynchronously and
 * survives process death, which is the whole requirement here. The next process either sees the row or
 * sees nothing, and a half-visible row is not a state this store can produce — `putString` calls are
 * applied together or not at all.
 */
@Singleton
class SharedPreferencesPendingOverrideStore @Inject constructor(
    @ApplicationContext context: Context,
) : PendingOverridePersistence {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * Reads exactly what is stored, with nothing invented.
     *
     * The token is the row's liveness marker: without it there is nothing to confirm and no question
     * to answer, so a partially-written file reads as nothing rather than as an override nobody asked
     * for. A missing requested size is the same — the row would say nothing the user could act on.
     */
    override fun read(): StoredPendingOverride? {
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        val requested = prefs.getString(KEY_REQUESTED, null) ?: return null
        return StoredPendingOverride(
            // A null previous value and a key that was never written both read back as null, because
            // `putString` with a null value removes the key. That is the correct outcome rather than a
            // defect: null means "the display had no override", which is one thing, not two.
            previousValue = prefs.getString(KEY_PREVIOUS, null),
            requestedArgument = requested,
            appliedAtMillis = prefs.getLong(KEY_APPLIED_AT, 0L),
            token = token,
        )
    }

    override fun write(row: StoredPendingOverride) {
        prefs.edit()
            .putString(KEY_PREVIOUS, row.previousValue)
            .putString(KEY_REQUESTED, row.requestedArgument)
            .putLong(KEY_APPLIED_AT, row.appliedAtMillis)
            .putString(KEY_TOKEN, row.token)
            .apply()
    }

    override fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val FILE = "display-override-guard"

        const val KEY_PREVIOUS = "previous"
        const val KEY_REQUESTED = "requested"
        const val KEY_APPLIED_AT = "appliedAt"
        const val KEY_TOKEN = "token"
    }
}
