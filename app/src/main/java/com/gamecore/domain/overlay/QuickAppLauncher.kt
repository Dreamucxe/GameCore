package com.gamecore.domain.overlay

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.common.TextSanitizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns the stored quick-launch package names into a drawable row, and opens the one that was tapped.
 *
 * Two jobs and they are the same job seen twice: what a package can be shown as, and what happens when
 * it is touched. Both answers come from `PackageManager.getLaunchIntentForPackage`, which is the only
 * thing GameCore needs to know about another app here — no elevated shell, no Shizuku, no `am start`.
 * A plain launch intent is what a launcher uses, it is what this uses, and it works on a device that has
 * never heard of Shizuku.
 *
 * **Nothing is cached.** [resolve] walks the list and asks the platform every time. That costs a few
 * milliseconds per app, off the main thread, and it buys the property this feature is judged on: an app
 * uninstalled while the panel was closed is drawn as unavailable the next time the panel opens, rather
 * than as a bright icon that does nothing. Caching the resolved row would be caching the answer to
 * "is this app still here", which is a question whose answer changes without asking us.
 *
 * **The order is the user's.** [resolve] preserves the order it is given and does not sort. These are
 * positions under a thumb — see [com.gamecore.core.model.FloatingButtonConfig.quickAppPackages].
 *
 * **Every label is sanitised**, for the reason [com.gamecore.core.system.InstalledAppLister] gives at
 * length: a third-party `android:label` is attacker-controlled text, this draws it over a game, and
 * cleaning it at the boundary rather than at the point of render is what makes that safe everywhere.
 */
@Singleton
class QuickAppLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * The row, in the order given, with an entry for every package — including the missing ones.
     *
     * An uninstalled app keeps its place rather than being dropped, and that is the §24 rule applied to
     * a row of icons: a list that silently shrinks tells the user nothing, while a marked gap says the
     * app they chose is gone and can be taken off the list. The package name stands in for the label,
     * because it is the only true thing left to say about it.
     */
    suspend fun resolve(packages: List<String>): List<QuickApp> = withContext(io) {
        packages.mapNotNull { raw ->
            val packageName = TextSanitizer.validatePackageName(raw) ?: return@mapNotNull null
            describe(packageName)
        }
    }

    /**
     * Opens one app, and says why if it did not open.
     *
     * `FLAG_ACTIVITY_NEW_TASK` because the caller is a service and a service has no task of its own to
     * start an activity in. The launch intent from `PackageManager` already carries it along with
     * `FLAG_ACTIVITY_RESET_TASK_IF_NEEDED`; setting it here is belt and braces against a device whose
     * implementation does not, and costs nothing when it does.
     *
     * Deliberately *not* doing anything else. No panel is closed, no session is ended, no detection is
     * stopped — this is the same event as the user switching apps by any other means, and GameCore
     * carries on exactly as it does then.
     *
     * The null check and the two catches are three different ways the same thing can be true: the app is
     * not there any more. It can be uninstalled between the row being drawn and the icon being tapped,
     * which is a real race on a device that is updating apps in the background, so the tap path checks
     * again rather than trusting what [resolve] found a moment ago.
     */
    suspend fun launch(packageName: String): QuickLaunchOutcome = withContext(io) {
        val valid = TextSanitizer.validatePackageName(packageName)
            ?: return@withContext QuickLaunchOutcome.Failed(NOT_INSTALLED)
        val intent = launchIntent(valid)
            ?: return@withContext QuickLaunchOutcome.Failed(NOT_INSTALLED)
        try {
            context.startActivity(
                Intent(intent).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
                ),
            )
            QuickLaunchOutcome.Launched
        } catch (missing: ActivityNotFoundException) {
            QuickLaunchOutcome.Failed(NOT_INSTALLED)
        } catch (denied: SecurityException) {
            // The activity exists but will not start for this caller. Background activity starts are
            // restricted from Android 10 and the exemption GameCore runs on is the overlay permission
            // itself, so this is what a revoked overlay permission looks like from here.
            QuickLaunchOutcome.Failed(REFUSED)
        } catch (refused: IllegalStateException) {
            QuickLaunchOutcome.Failed(REFUSED)
        }
    }

    // ---------------------------------------------------------------------------------- internals

    private fun describe(packageName: String): QuickApp {
        val pm = context.packageManager
        val intent = launchIntent(packageName)
        val label = try {
            TextSanitizer.sanitizeName(
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString(),
            ).ifBlank { packageName }
        } catch (error: Throwable) {
            packageName
        }
        return QuickApp(
            packageName = packageName,
            label = if (intent == null) packageName else label,
            icon = if (intent == null) null else icon(packageName),
            isAvailable = intent != null,
        )
    }

    /**
     * The one platform call this whole feature rests on.
     *
     * Wrapped because it is documented to return null for a package with no launcher activity but
     * throws on some implementations for a package that is not installed at all, and to this feature
     * those are the same outcome: there is nothing here to open.
     */
    private fun launchIntent(packageName: String): Intent? = try {
        context.packageManager.getLaunchIntentForPackage(packageName)
    } catch (error: Throwable) {
        null
    }

    /**
     * The app's icon, rasterised at a fixed size.
     *
     * A [Bitmap] rather than the `Drawable` it comes as, because the row is drawn in an overlay window
     * by Compose and a `Drawable` would have to be re-rendered on every recomposition of a panel that
     * sits over a game at sixty frames a second. [ICON_PX] square is a little over a 40 dp icon at
     * xxhdpi — sharp at every width the panel can be dragged to, and six of them cost about half a
     * megabyte, which is the right trade against rasterising them again each frame.
     *
     * An adaptive icon has no intrinsic size worth trusting, which is why the size is passed rather than
     * read from the drawable: `toBitmap()` on a drawable reporting -1 throws.
     */
    private fun icon(packageName: String): Bitmap? = try {
        context.packageManager
            .getApplicationIcon(packageName)
            .toBitmap(width = ICON_PX, height = ICON_PX)
    } catch (error: Throwable) {
        // An icon that will not decode is not a reason to drop the app from the row. The tile falls
        // back to the first letter of its label, which is still something to aim a thumb at.
        null
    }

    private companion object {
        const val ICON_PX = 128

        const val NOT_INSTALLED =
            "That app is not installed any more, so there is nothing to open. Remove it from the row in " +
                "Overlay settings."

        const val REFUSED =
            "Android refused to start that app from here. If GameCore's permission to draw over other " +
                "apps was turned off, granting it again will fix this."
    }
}
