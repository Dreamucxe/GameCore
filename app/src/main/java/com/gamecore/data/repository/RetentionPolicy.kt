package com.gamecore.data.repository

/**
 * The arithmetic behind "keep history for N days", kept pure so the boundary is testable without a database.
 *
 * Zero means keep forever — the user's default and the only value that never deletes anything. A positive
 * value turns into a cutoff instant; a session is eligible when its *end* is strictly before that instant.
 * Strictly before is deliberate: a session that ended exactly N days ago is exactly N days old, not older
 * than N days, and deleting it would make the setting mean N−ε days.
 *
 * Nothing here touches personal records or Aim Lab records. Retention is about the session log; a record is
 * the one thing a deleted session may still have produced, and it outlives the session on purpose.
 */
object RetentionPolicy {

    const val MILLIS_PER_DAY = 24L * 60L * 60L * 1_000L

    /**
     * The instant before which sessions are eligible for deletion, or null when retention is off.
     *
     * @param nowMillis the moment the purge is judged from.
     * @param retentionDays the stored setting; zero or negative means keep forever.
     */
    fun purgeBefore(nowMillis: Long, retentionDays: Int): Long? {
        if (retentionDays <= 0) return null
        return nowMillis - retentionDays.toLong() * MILLIS_PER_DAY
    }

    /**
     * Whether a session that ended at [endedAtMillis] is older than the cutoff.
     *
     * A session ending exactly at the cutoff is kept. The comparison lives here rather than in each
     * repository so the boundary cannot drift between game history and Aim Lab history.
     */
    fun isExpired(endedAtMillis: Long, cutoffMillis: Long): Boolean = endedAtMillis < cutoffMillis
}
