package com.gamecore.service

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService

/**
 * The key that unlocks the media session, and nothing else.
 *
 * `MediaSessionManager.getActiveSessions` will only answer a caller that owns an *enabled*
 * [NotificationListenerService] — the component is the credential, and there is no narrower one. Android
 * has no "media sessions only" permission, so reading which track is playing costs the same grant that
 * would let an app read every notification on the device.
 *
 * This class is how GameCore declines the rest of that grant. It overrides nothing:
 *
 *  - no `onNotificationPosted`, so a notification arriving is never delivered anywhere;
 *  - no `onNotificationRemoved`, so one leaving is not observed either;
 *  - no `onListenerConnected`, so `getActiveNotifications()` — the bulk read of everything currently on
 *    the shade — is never called, here or anywhere in the app.
 *
 * Which leaves an empty subclass that exists purely so [componentIn] can name it. The base class binds,
 * answers the system's lifecycle calls, and the default implementations of the callbacks do nothing with
 * what they are handed. §Media's promise to the user on the access screen — "reading media session
 * metadata, nothing else" — is enforced by this file being as empty as it looks, and any override added
 * below breaks that promise rather than extending it.
 *
 * Grep for the manifest entry before assuming this is dead code: it is bound by the system, never
 * constructed by GameCore, so every reference to it is either a string in `AndroidManifest.xml` or the
 * [ComponentName] below.
 */
class MediaAccessService : NotificationListenerService() {

    companion object {
        /**
         * The component to hand `getActiveSessions`, and the one whose package the enabled-listener check
         * looks for.
         *
         * Built from the class rather than from a literal string so a rename or a package move is a
         * compile error here instead of a silent `SecurityException` at the call site — the failure mode
         * of a stale listener name is "media never appears and nothing says why", which is the hardest
         * kind of bug to see from inside the overlay.
         */
        fun componentIn(context: Context): ComponentName =
            ComponentName(context, MediaAccessService::class.java)
    }
}
