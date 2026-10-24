package com.gamecore.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One event on a session's timeline.
 *
 * A separate table rather than a column on `sessions` or a JSON blob. Events are the only part of a
 * session that is a list, they are queried in time order for one session at a time, and the backup
 * needs to stream them without parsing a document. The table is narrow and the index is the session
 * id, which is the only predicate the read side ever uses.
 *
 * No foreign key, matching `session_samples`: the session and its children are written and deleted as
 * one unit by [SessionDao], and a cascade would add a constraint to a migration for no gain. The
 * delete methods remove events explicitly so a deleted session does not leave orphan rows behind.
 *
 * `kind` stores [com.gamecore.core.model.SessionEventKind] by name. A name that this build does not
 * know — a backup from a newer version, for instance — is dropped by the mapper rather than guessed
 * at, because an event misread as another kind is worse than an event not shown.
 */
@Entity(
    tableName = "session_events",
    indices = [Index("session_id")],
)
data class SessionEventEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "session_id")
    val sessionId: Long,

    /** Offset from the session's start. The session row's `started_at` is the only clock reference. */
    @ColumnInfo(name = "elapsed_millis")
    val elapsedMillis: Long,

    @ColumnInfo(name = "kind")
    val kind: String,

    /** Short human-readable context, or null when the kind is the whole story. */
    @ColumnInfo(name = "detail")
    val detail: String? = null,

    /** One number the event carries, if it has one: level, p99 frame millis, probe round trip. */
    @ColumnInfo(name = "value")
    val value: Double? = null,
)
