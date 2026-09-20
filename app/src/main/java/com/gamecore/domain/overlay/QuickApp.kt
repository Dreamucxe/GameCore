package com.gamecore.domain.overlay

import android.graphics.Bitmap

/**
 * One app in the panel's quick-launch row, as the row draws it.
 *
 * Resolved fresh from `PackageManager` every time the row is built — see [QuickAppLauncher] — rather
 * than stored, because the only thing GameCore keeps is the package name. A label can change on an
 * update, an icon can change with a theme, and an app can be uninstalled between one panel open and the
 * next; a copy of any of those held in the settings file would be a fact that quietly stopped being true.
 *
 * Carries a [Bitmap] and three plain fields rather than a `PackageManager`, an `ApplicationInfo` or a
 * `Drawable` — §24A.2, the same rule [com.gamecore.core.model.InstalledApp] follows and the same one
 * [com.gamecore.domain.media.NowPlaying.Track] follows by carrying its artwork as a [Bitmap]. The row is
 * a Composable, and a Composable one field access away from a live `ApplicationInfo` is a Composable that
 * can read another app's data directory.
 *
 * [isAvailable] is false when the package has no launch intent — it was uninstalled, or it is installed
 * but has no launcher activity to open. The row draws those dim and marked rather than hiding them: an
 * icon that vanishes tells the user nothing, and §32 forbids a control that is present and does nothing
 * silently. [icon] is null in that case, because there is nothing to launch and a bright icon over a
 * game would invite a tap that cannot work.
 */
data class QuickApp(
    val packageName: String,
    val label: String,
    val icon: Bitmap?,
    val isAvailable: Boolean,
)

/**
 * What a tap on a quick-launch icon did.
 *
 * A result and not a `Boolean` for the reason the media strip's failure is a toast and not a silent
 * repaint: when a launch does not happen the user is owed the reason, and "not installed" and "the
 * system refused to start it" are different sentences. [Launched] is silent — the app is now in front,
 * which the user can see.
 */
sealed interface QuickLaunchOutcome {

    /** The launch intent was started. Nothing is said; the app came to the foreground. */
    data object Launched : QuickLaunchOutcome

    /** Nothing started, and [reason] is a sentence to show the user verbatim. */
    data class Failed(val reason: String) : QuickLaunchOutcome
}
