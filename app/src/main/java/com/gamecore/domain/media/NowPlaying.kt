package com.gamecore.domain.media

import android.graphics.Bitmap

/**
 * What the control panel's media plate is currently able to say.
 *
 * Four states, and the reason they are a sealed hierarchy rather than a nullable [Track] is that three of
 * them are different sentences to a user. "Nothing is playing" invites them to start something; "GameCore
 * cannot see what is playing" invites them to grant an access; "this device has no media session service"
 * invites them to do nothing at all. Collapsing those into one absent value would leave the panel
 * guessing which to draw, and the guess it would reach for — "nothing playing" — is the one that is
 * wrong in the two cases the user could act on.
 *
 * There is deliberately no "last known track" state. §Media's requirement is that the panel never shows
 * stale or invented data, so when the session goes away the track goes with it: a plate still offering a
 * Pause button for a song that stopped two minutes ago is worse than an empty one, because the button
 * does nothing and the user has no way to tell that from a button that failed.
 */
sealed interface NowPlaying {

    /**
     * Notification listener access has not been granted, so no session can be read.
     *
     * Not an error and not a failure — it is the ordinary first state for every install, and the panel
     * draws it as a one-tap prompt rather than as a problem.
     */
    data object NeedsAccess : NowPlaying

    /**
     * Access is granted and no app has a session worth showing.
     *
     * Named for the outcome and not for the absence: `Nothing` would shadow [kotlin.Nothing] at every
     * use site inside a `when`, and `None` reads as an error code next to [Unavailable].
     */
    data object Silent : NowPlaying

    /**
     * The media session service could not be reached or refused the read.
     *
     * [reason] is shown verbatim, so it has to be a sentence about this device rather than an exception
     * name. Distinct from [NeedsAccess] because there is nothing here for the user to grant.
     */
    data class Unavailable(val reason: String) : NowPlaying

    /**
     * A session, as the panel draws it.
     *
     * [title] is the only field guaranteed to be non-empty — it is what the selection in
     * [MediaSessionReader] keys on, and a session with no title is not shown at all. Everything else is
     * optional because every field of a [android.media.MediaMetadata] is optional: a podcast app may
     * publish an episode name and no artist, a browser may publish a page title and no art, and a game
     * streaming its own audio may publish nothing but a package name.
     *
     * [appLabel] is the owning app's name, resolved for display only. The panel shows it so the user can
     * tell which of two music apps the transport buttons are about to talk to — not so GameCore can
     * branch on it. Nothing in this feature reads it as an identifier.
     *
     * The three `can…` flags come from the session's own declared [android.media.session.PlaybackState]
     * actions. They dim a button rather than hide it, because a transport control that disappears when a
     * track changes is a moving target in a panel the user is tapping at a glance.
     */
    data class Track(
        val title: String,
        val artist: String?,
        val appLabel: String?,
        val isPlaying: Boolean,
        val art: Bitmap?,
        val canPlayPause: Boolean,
        val canSkipNext: Boolean,
        val canSkipPrevious: Boolean,
    ) : NowPlaying
}

/**
 * The three things the panel can ask the owning app to do.
 *
 * An enum and not three methods so the overlay's tap handling stays one branch wide — the panel emits a
 * command, the service forwards it, and [MediaSessionReader] is the only place that knows a command is
 * really a call on [android.media.session.MediaController.TransportControls].
 */
enum class MediaCommand {
    PREVIOUS,
    PLAY_PAUSE,
    NEXT,
}
