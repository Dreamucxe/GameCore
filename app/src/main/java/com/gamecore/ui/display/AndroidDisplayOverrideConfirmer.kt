package com.gamecore.ui.display

import android.content.Context
import com.gamecore.core.permissions.PermissionChecker
import com.gamecore.domain.display.DisplayOverrideConfirmer
import com.gamecore.domain.display.DisplayOverrideNotices
import com.gamecore.domain.display.PendingDisplayOverride
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Puts the confirmation in front of the user, by whichever surface will actually reach them.
 *
 * The full-screen Activity is preferred and is used wherever it can be started, because it survives the
 * very thing it is asking about: a display override small enough to make GameCore's own windows
 * unreadable. A notification is the fallback, not a lesser option — it is the surface that works from the
 * one place the Activity usually cannot be started from, which is the overlay panel's aspect tile while
 * a game is in front and GameCore itself is nowhere on screen.
 *
 * The choice between them is made from one fact rather than guessed: the "display over other apps"
 * permission is one of Android's documented exemptions from the background-activity-start restriction,
 * so while GameCore holds it the screen can be started from anywhere. Without it, a start from the
 * background is dropped rather than refused — it throws nothing — and a question that was silently
 * dropped is worse than one asked in the shade, where it interrupts. So without the exemption the
 * notification is used, and the two answers travel as actions on it either way.
 *
 * What is never done here: a second copy of the question. The row in [PendingDisplayOverride] is the only
 * thing that says a confirmation is waiting, this class is the only thing that asks it, and the token on
 * the row is what both surfaces check before acting.
 */
@Singleton
class AndroidDisplayOverrideConfirmer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissions: PermissionChecker,
    private val notices: DisplayOverrideNotices,
) : DisplayOverrideConfirmer {

    /** Asks the question, and reports nothing about whether it worked. The row is the answer trail. */
    override fun confirm(row: PendingDisplayOverride) {
        if (permissions.hasOverlayPermission() && startScreen(row.token)) return
        // Either there is no exemption, or the start was refused outright. Either way the shade is the
        // surface that will interrupt, and the countdown continues from the row regardless.
        notices.confirmation(row)
    }

    private fun startScreen(token: String): Boolean = try {
        context.startActivity(DisplaySizeConfirmActivity.intentFor(context, token))
        true
    } catch (refused: Exception) {
        // A ROM that refuses the start with a SecurityException rather than dropping it, plus the
        // impossible ActivityNotFoundException for our own component. Both mean the same thing here.
        false
    }
}
