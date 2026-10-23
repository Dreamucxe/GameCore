package com.gamecore.ui.backup

import android.content.Intent
import com.gamecore.data.repository.BackupPreview
import com.gamecore.data.repository.RetentionStatus
import com.gamecore.ui.components.Tone

/**
 * What the §20 backup screen draws.
 *
 * Unlike the macro editor's render-store state, this screen has no list to mirror — a backup is an action,
 * not a document — so the state is the shape of the *last* action: whether one is in flight ([isBusy]), the
 * one-line outcome of it ([message] with its [messageTone]), and a pending hand-off to the share sheet
 * ([share]) that the screen consumes once and clears.
 *
 * Two more fields arrived with schema v2. [preview] holds the decoded contents of a picked file until the
 * user confirms it — nothing is written while it is set — and [retention] is the history card's two numbers.
 * Both are plain reads of what the manager reported; neither ever invents a value.
 */
data class BackupRestoreUiState(
    val isBusy: Boolean = false,
    val message: String? = null,
    val messageTone: Tone = Tone.Muted,
    val share: ShareRequest? = null,
    /** The picked file, decoded and counted but not applied until the user confirms it. */
    val preview: BackupPreview.Ready? = null,
    /** The stored retention window and what "Delete now" would remove; null until the first read lands. */
    val retention: RetentionStatus? = null,
) {
    /** Buttons are live only when no export, restore or purge is running, so a double tap cannot start two. */
    val canAct: Boolean get() = !isBusy
}

/**
 * A one-shot request to open the share sheet for a just-written backup.
 *
 * Carries the [Intent] rather than firing it from the ViewModel, because launching an activity is the
 * screen's job and needs its `Context`. [id] rises on every export so a `LaunchedEffect` keyed on it fires
 * once per share and never re-fires across an unrelated recomposition.
 */
data class ShareRequest(val id: Long, val intent: Intent)
