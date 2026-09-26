package com.gamecore.core.system

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.common.TextSanitizer
import com.gamecore.core.model.InstalledApp
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The installed-app list the game picker shows.
 *
 * Two things here are not incidental.
 *
 * **Every label is sanitised.** A package's `android:label` comes from another
 * developer's manifest and is attacker-controlled in exactly the sense user input is:
 * anyone can publish an APK whose label is a right-to-left override, a thousand combining
 * marks, or a megabyte of text. GameCore draws these labels into overlay windows and
 * notifications, so they go through [TextSanitizer.sanitizeName] before they are stored in
 * an [InstalledApp] — not at render time in one screen, but here, once, at the boundary.
 *
 * **[InstalledApp] carries five fields, not an `ApplicationInfo`.** §24A.2: a model the UI
 * consumes must not be a passthrough of a platform object. An `ApplicationInfo` holds the
 * app's data directory, uid, native library paths and full flag set — none of which a list
 * row draws, and all of which would be one field access away from any Composable that got
 * hold of it.
 *
 * Launcher-visible packages only. A game is something the user can start, so the query is
 * for `ACTION_MAIN`/`CATEGORY_LAUNCHER` rather than for every installed package; that also
 * keeps the list to a few dozen entries instead of several hundred services.
 */
@Singleton
class InstalledAppLister @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * Every launchable app, games first.
     *
     * Sorted rather than filtered by [InstalledApp.isLikelyGame]. The category flag is the
     * developer's own declaration — honest where it is set and frequently unset — so
     * filtering on it would make a wrongly-categorised game impossible to configure. The
     * user knows better than the heuristic; the heuristic only decides who goes first.
     */
    suspend fun list(includeSystemApps: Boolean = false): List<InstalledApp> = withContext(io) {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        val resolved = try {
            queryLauncherActivities(pm, launcherIntent)
        } catch (error: Throwable) {
            emptyList()
        }

        val seen = HashSet<String>(resolved.size)
        val apps = ArrayList<InstalledApp>(resolved.size)
        for (packageName in resolved) {
            if (packageName == context.packageName) continue
            if (!seen.add(packageName)) continue
            val app = describe(pm, packageName) ?: continue
            if (app.isSystemApp && !includeSystemApps) continue
            apps += app
        }

        apps.sortedWith(
            compareByDescending<InstalledApp> { it.isLikelyGame }
                .thenBy { it.label.lowercase() },
        )
    }

    /**
     * One package by name, for a profile whose game may since have been uninstalled.
     *
     * Returns null in that case, which is what lets the Games screen show a saved profile
     * as "no longer installed" instead of crashing on a `NameNotFoundException` or
     * silently dropping the user's configuration.
     */
    suspend fun describe(packageName: String): InstalledApp? = withContext(io) {
        val valid = TextSanitizer.validatePackageName(packageName) ?: return@withContext null
        describe(context.packageManager, valid)
    }

    suspend fun isInstalled(packageName: String): Boolean = describe(packageName) != null

    // ---------------------------------------------------------------------- internals

    @Suppress("DEPRECATION")
    private fun queryLauncherActivities(pm: PackageManager, intent: Intent): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(0L),
            )
        } else {
            pm.queryIntentActivities(intent, 0)
        }.mapNotNull { it.activityInfo?.packageName }

    @Suppress("DEPRECATION")
    private fun describe(pm: PackageManager, packageName: String): InstalledApp? = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L))
        } else {
            pm.getApplicationInfo(packageName, 0)
        }
        val packageInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                pm.getPackageInfo(packageName, 0)
            }
        } catch (error: Throwable) {
            null
        }
        val versionName = packageInfo?.versionName

        InstalledApp(
            packageName = packageName,
            // The label is other people's text. It is cleaned here, once, before it can
            // reach an overlay window or a notification.
            label = TextSanitizer.sanitizeName(
                pm.getApplicationLabel(info).toString().ifBlank { packageName },
            ).ifBlank { packageName },
            isLikelyGame = isLikelyGame(info),
            isSystemApp = info.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            versionName = versionName?.let { TextSanitizer.sanitizeName(it, maxLength = 32) },
            lastUpdateTime = packageInfo?.lastUpdateTime,
        )
    } catch (error: Throwable) {
        null
    }

    /**
     * The developer's own category declaration, and nothing else.
     *
     * `CATEGORY_GAME` is set by the app that owns it, which makes it the only honest
     * signal available — and it is often unset, which is why this is a sort key rather
     * than a filter. GameCore does not keep a list of known game package names: a
     * hard-coded list is stale the week it ships and tells a user with an unlisted game
     * that their game is not a game.
     */
    private fun isLikelyGame(info: ApplicationInfo): Boolean =
        info.category == ApplicationInfo.CATEGORY_GAME
}
