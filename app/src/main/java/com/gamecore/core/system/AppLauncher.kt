package com.gamecore.core.system

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.common.TextSanitizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts another app — the "Play" button on a game profile.
 *
 * Separate from [InstalledAppLister] on purpose. That class answers what is installed and is read-only;
 * this one leaves GameCore's own process boundary, and the checks that belong to doing so belong in one
 * place: a validated package name, a launch intent the system resolved rather than one GameCore
 * assembled, and every refusal turned into a [ControlOutcome] instead of an exception.
 *
 * Three things it deliberately does not do.
 *
 * **It does not apply the profile.** A profile is applied by the coordinator, on the detector seeing the
 * game come to the front, and again from "Apply now" beside this button. Launching and applying from one
 * tap would mean the same button did different amounts of work depending on whether automatic
 * application was on, and a user who wanted only to start their game would have their refresh rate and
 * brightness changed without asking.
 *
 * **It does not name an activity.** `getLaunchIntentForPackage` returns the entry point the app itself
 * declares; a hard-coded component would break the moment a game renamed its launcher activity, and
 * starting an arbitrary non-exported activity is not something an ordinary app may do anyway.
 *
 * **It does not pretend a package with no launcher is startable.** Some games — instant-app stubs,
 * packages left behind by an uninstall that kept data — resolve as installed and have nothing to start.
 * That is [ControlOutcome.Unsupported] with a sentence, per §24's rule about saying so.
 */
@Singleton
class AppLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * Brings the app to the front, starting it if it is not already running.
     *
     * `NEW_TASK` because the caller may be a service or a screen in GameCore's own task, and a game
     * started into GameCore's task would come back on GameCore's back stack.
     * `RESET_TASK_IF_NEEDED` is what makes a second tap resume the running game at its own root rather
     * than stacking another copy of its launcher activity on top of wherever the player had got to.
     */
    suspend fun launch(packageName: String): ControlOutcome = withContext(io) {
        val valid = TextSanitizer.validatePackageName(packageName)
            ?: return@withContext ControlOutcome.Failed("\"$packageName\" is not a package name.")

        val intent = try {
            context.packageManager.getLaunchIntentForPackage(valid)
        } catch (error: Throwable) {
            // A package uninstalled between the list and the tap throws here on some builds and
            // returns null on others. Both mean the same thing to the user.
            null
        } ?: return@withContext ControlOutcome.Unsupported(
            "This app does not have a screen GameCore can open. Start it from your launcher.",
        )

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            context.startActivity(intent)
            ControlOutcome.Applied()
        } catch (missing: ActivityNotFoundException) {
            ControlOutcome.Failed("The app could not be started. It may have just been uninstalled.")
        } catch (refused: SecurityException) {
            // The launcher activity exists but is not exported, or a work-profile boundary is in the way.
            ControlOutcome.RequiresAccess("Android would not let GameCore start this app.")
        } catch (rejected: IllegalStateException) {
            // Background activity starts are restricted from Android 10 up. From a screen the user is
            // looking at this does not arise; from a service without the overlay exemption it does.
            ControlOutcome.Failed("The app could not be started from the background.")
        }
    }
}
