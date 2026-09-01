package com.gamecore.core.permissions

/**
 * Every access GameCore can ask for, with the reason and the consequence of saying no.
 *
 * This exists as data rather than as strings in a screen because the specification
 * requires the permissions centre to explain each permission and offer a button to
 * the right settings page, and because three other places need the same wording: the
 * capability list, the first-run guidance, and the inline prompt a feature shows when
 * the user switches it on without the access it needs. One list, one explanation.
 *
 * [whatBreaks] is the field that matters. "GameCore needs usage access" tells a user
 * nothing; "without it, profiles have to be applied by hand because Android does not
 * let an app see which game is running" tells them what they are choosing between.
 *
 * Nothing here is required for the app to start. GameCore with every one of these
 * declined is a working performance monitor for its own process's view of the device;
 * each grant adds a feature, and the UI says which.
 */
enum class GamePermission(
    val title: String,
    val kind: AccessKind,
    val why: String,
    val whatBreaks: String,
) {
    OVERLAY(
        title = "Display over other apps",
        kind = AccessKind.SPECIAL_ACCESS,
        why = "Every on-screen feature — the floating button, the performance pill, the " +
            "crosshair and the HUD — is a window drawn on top of the game. Android gates " +
            "that behind a dedicated toggle in Settings.",
        whatBreaks = "Without it there is no overlay of any kind. The dashboard, session " +
            "history and profiles still work.",
    ),

    USAGE_ACCESS(
        title = "Usage access",
        kind = AccessKind.APP_OP,
        why = "From Android 9 an app can no longer see which other app is running. Usage " +
            "access is the only permitted way to learn that a game has come to the " +
            "foreground, which is what lets a profile apply itself.",
        whatBreaks = "Automatic game detection is switched off and profiles are applied by " +
            "hand. Session tracking still works when started manually.",
    ),

    WRITE_SETTINGS(
        title = "Modify system settings",
        kind = AccessKind.APP_OP,
        why = "Brightness, rotation lock and the screen timeout are values in the system " +
            "settings table. Writing them needs this access, which lives on its own " +
            "Settings page rather than in a permission dialog.",
        whatBreaks = "Those three controls are unavailable and profiles that set them skip " +
            "that step and say so.",
    ),

    NOTIFICATION_POLICY(
        title = "Do Not Disturb access",
        kind = AccessKind.SPECIAL_ACCESS,
        why = "Silencing notifications during a game means changing the interruption filter, " +
            "which Android only allows an app the user has explicitly trusted with it.",
        whatBreaks = "The Do Not Disturb toggle and the profile setting that uses it are " +
            "unavailable.",
    ),

    POST_NOTIFICATIONS(
        title = "Notifications",
        kind = AccessKind.RUNTIME,
        why = "A background service must show a notification while it runs, and from " +
            "Android 13 posting one needs the user's permission. It is also how a thermal " +
            "warning reaches you while a game is full-screen.",
        whatBreaks = "Overlays, session tracking and monitoring cannot run in the background, " +
            "because the services that host them are required to post a notification.",
    ),

    BATTERY_OPTIMISATION_EXEMPTION(
        title = "Unrestricted battery use",
        kind = AccessKind.SPECIAL_ACCESS,
        why = "Android throttles background work to save power. A sampler that is throttled " +
            "mid-session leaves gaps in the graph, and an overlay service can be killed " +
            "outright during a long game.",
        whatBreaks = "Long sessions may be truncated and overlays may disappear. GameCore " +
            "reports a truncated session as truncated rather than silently shortening it.",
    ),

    PACKAGE_VISIBILITY(
        title = "See installed apps",
        kind = AccessKind.RUNTIME,
        why = "From Android 11 an app sees a filtered list of what is installed. The game " +
            "picker needs the unfiltered one, or the game you want to configure may simply " +
            "not appear in it.",
        whatBreaks = "The game list may be incomplete. It is granted at install and is " +
            "listed here for completeness.",
    ),
    ;

    /** True for the ones that can be requested with a system dialog. */
    val isRuntimeRequestable: Boolean get() = kind == AccessKind.RUNTIME
}

/**
 * How an access is obtained, which decides what the button next to it does.
 *
 * The distinction is not cosmetic. `checkSelfPermission` returns GRANTED for
 * PACKAGE_USAGE_STATS the moment it appears in the manifest, while the appop that
 * actually gates the data is still denied — so an app that checks the permission
 * reports itself ready and then takes a SecurityException on the first read. The two
 * are queried differently, and separating them here is what keeps that from happening.
 */
enum class AccessKind(val label: String) {
    /** A normal runtime permission, requestable with a dialog. */
    RUNTIME("Permission"),

    /** A toggle on a dedicated Settings page. Checked with its own platform call. */
    SPECIAL_ACCESS("Special access"),

    /**
     * An app-op behind a Settings page, where the manifest permission is not the real
     * gate. Queried through `AppOpsManager`, never through `checkSelfPermission`.
     */
    APP_OP("Special access"),
}
