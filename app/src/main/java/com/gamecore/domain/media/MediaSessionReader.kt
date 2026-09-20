package com.gamecore.domain.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationManagerCompat
import com.gamecore.core.common.IoDispatcher
import com.gamecore.service.MediaAccessService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Whatever is playing on this device, and the three buttons that talk back to it.
 *
 * Reads `MediaSessionManager`, which is the only source that is app-agnostic by construction: every app
 * that wants a lock-screen card, a Bluetooth button or a headset click already publishes a
 * [android.media.session.MediaSession], so Spotify, YouTube, YouTube Music, a podcast player and a
 * browser tab all arrive here through the same interface with no per-app handling anywhere in this file.
 * There is no package allow-list and no package name compared against a literal — the only use of a
 * package name is [appLabel], which turns it into text for the user to read.
 *
 * Three things about this API shape the class:
 *
 *  - **The credential is a component, not a permission.** `getActiveSessions` takes the [ComponentName]
 *    of a [android.service.notification.NotificationListenerService] the caller owns, and throws
 *    `SecurityException` unless the user has enabled it. That is why [MediaAccessService] exists, and why
 *    "not granted" is a first-class state here rather than an error — see [NowPlaying.NeedsAccess].
 *  - **The list is already ranked.** `getActiveSessions` returns controllers in the platform's own
 *    priority order, most recently active first, so §Media's "show the most recently active one" is the
 *    head of the list rather than a timestamp comparison GameCore would have to keep itself.
 *  - **Nothing is guaranteed.** Every field of a [MediaMetadata] is optional, [PlaybackState] may be
 *    null, and an app may publish a session it never updates. So every read below is defensive, and the
 *    panel is told what is missing rather than shown a plausible substitute.
 *
 * Display and control are kept in agreement by construction: [send] does not remember the controller
 * [watch] last described, it re-runs [select] over a fresh session list at the moment of the tap. A
 * remembered controller is a stale controller the instant the user switches apps — and a Pause that
 * pauses the app they were listening to five minutes ago is worse than one that does nothing.
 */
@Singleton
class MediaSessionReader @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * Callbacks are delivered here, and [refresh] runs nowhere else.
     *
     * An explicit looper because the alternative is not one: the no-Handler overloads of
     * `addOnActiveSessionsChangedListener` construct a `Handler()` from the calling thread's looper, and
     * this class is driven from a coroutine on a pool thread that has none — which is a crash on
     * registration, not a fallback to main. Pinning both registrations to one thread also makes
     * [watched] single-threaded state rather than something to lock.
     */
    private val callbackHandler = Handler(Looper.getMainLooper())

    /** The most recently scaled art and the key it was scaled for. See [artFor]. */
    private var artKey: String? = null
    private var artValue: Bitmap? = null

    /**
     * Whether the listener component is enabled, which is the whole of what this feature needs.
     *
     * Asked at the package level because GameCore declares exactly one listener, so "this package has an
     * enabled listener" and "[MediaAccessService] is enabled" are the same fact — and the package-level
     * question has a support-library answer that works on every API level this app runs on, where the
     * component-level one (`NotificationManager.isNotificationListenerAccessGranted`) arrived in 27.
     * If a second listener is ever added this has to become the component check.
     */
    fun hasAccess(): Boolean = try {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    } catch (error: Throwable) {
        false
    }

    /**
     * The current session, re-emitted whenever it changes.
     *
     * Two listeners, because the two questions are independent: `OnActiveSessionsChangedListener` fires
     * when the set of sessions changes — an app starting or dying — and [MediaController.Callback] fires
     * when the session GameCore is following changes what it is doing. Neither implies the other, and a
     * panel with only the first would sit on a stale title for the length of an album.
     *
     * Both handlers do the same thing: re-read, re-select, re-describe. Re-selecting on a mere playback
     * change looks excessive and is not — the selection rule *is* a function of playback state, so
     * YouTube starting while Spotify is paused changes which session the panel should be showing without
     * changing the set of sessions at all.
     *
     * Access is evaluated once per collection rather than polled. Every collection of this flow begins
     * when the control panel opens, and the panel's own "Enable" prompt closes it on the way to settings,
     * so a grant is always followed by a fresh collection.
     */
    fun watch(): Flow<NowPlaying> = callbackFlow {
        val manager = sessionManager()
        if (manager == null) {
            trySend(NowPlaying.Unavailable(NO_SERVICE))
            awaitClose { /* Nothing was registered, so there is nothing to undo. */ }
            return@callbackFlow
        }
        if (!hasAccess()) {
            trySend(NowPlaying.NeedsAccess)
            awaitClose { /* As above. The prompt stays until the collector goes away. */ }
            return@callbackFlow
        }

        val watch = SessionWatch(manager, MediaAccessService.componentIn(context)) { trySend(it) }
        if (!watch.start()) {
            trySend(NowPlaying.Unavailable(READ_FAILED))
            awaitClose { /* `start` unwound itself. */ }
            return@callbackFlow
        }
        awaitClose { watch.stop() }
    }.distinctUntilChanged()

    /**
     * One collection's worth of registrations.
     *
     * A class rather than a handful of locals inside [watch] because the three pieces refer to each
     * other in a cycle — the controller callback calls [refresh], [refresh] calls [follow], and [follow]
     * is what registers the controller callback — and local declarations in Kotlin are only in scope
     * after the line that makes them. Members have no such ordering, so the cycle is expressible here
     * and would need a mutable forward reference there.
     *
     * Everything below runs on [callbackHandler] and is single-threaded because of it. [start] and
     * [stop] are the two exceptions, and both are careful about it.
     */
    private inner class SessionWatch(
        private val manager: MediaSessionManager,
        private val component: ComponentName,
        private val emit: (NowPlaying) -> Unit,
    ) {

        /** The session whose callback is currently registered. Written only by [follow]. */
        private var watched: MediaController? = null

        private val controllerCallback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
            override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()

            /**
             * The session being followed has gone.
             *
             * Still a full [refresh] rather than an emission of [NowPlaying.Silent]: another app may
             * already hold a session, and the common case for this callback is a handover — one player
             * releasing as another takes over — not silence.
             */
            override fun onSessionDestroyed() = refresh()
        }

        private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { refresh() }

        /** Registers, and takes the first reading. False if the platform refused the registration. */
        fun start(): Boolean {
            try {
                manager.addOnActiveSessionsChangedListener(sessionsListener, component, callbackHandler)
            } catch (error: Throwable) {
                return false
            }
            // Posted rather than called, so that `watched` is written by one thread from the first
            // read onwards — `start` runs on whichever thread collected the flow.
            callbackHandler.post { refresh() }
            return true
        }

        fun stop() {
            // The sessions listener first and synchronously: once it is gone nothing can schedule
            // another refresh, so the posted unregister below cannot race a registration.
            try {
                manager.removeOnActiveSessionsChangedListener(sessionsListener)
            } catch (error: Throwable) {
                // Nothing left to do about it; the listener holds no resource of ours.
            }
            callbackHandler.post { follow(null) }
        }

        /** Re-reads, re-selects, re-describes. The only writer of [watched]. */
        fun refresh() {
            val controllers = try {
                manager.getActiveSessions(component)
            } catch (error: SecurityException) {
                // The user revoked the access while the panel was open. Not a failure — the same
                // prompt that got them here is the right thing to show again.
                follow(null)
                emit(NowPlaying.NeedsAccess)
                return
            } catch (error: Throwable) {
                follow(null)
                emit(NowPlaying.Unavailable(READ_FAILED))
                return
            }
            val chosen = select(controllers)
            follow(chosen)
            emit(chosen?.let { describe(it) } ?: NowPlaying.Silent)
        }

        /** Moves the controller callback onto [next], which may be the same session or none. */
        private fun follow(next: MediaController?) {
            val current = watched
            if (current != null && current.sessionToken == next?.sessionToken) return
            if (current != null) {
                try {
                    current.unregisterCallback(controllerCallback)
                } catch (error: Throwable) {
                    // Already dead. The callback dies with the session either way.
                }
            }
            watched = next
            if (next != null) {
                try {
                    next.registerCallback(controllerCallback, callbackHandler)
                } catch (error: Throwable) {
                    // Registration refused: the panel still draws the snapshot this call was made
                    // for, it just will not update until the session set changes. Better than
                    // dropping the track entirely.
                    watched = null
                }
            }
        }
    }

    /**
     * Tells the app that owns the current session to do one thing.
     *
     * Returns false when there was nothing to send to or the call was refused, which the overlay turns
     * into a brief note rather than a silent no-op — a transport button that does nothing and says
     * nothing is indistinguishable from a frozen panel.
     *
     * [MediaCommand.PLAY_PAUSE] resolves to `play` or `pause` from the state read here, not from the
     * state the panel was drawn with. The two differ exactly when they matter: the track ended, or the
     * user paused from their headphones, between the panel drawing and the tap landing.
     */
    suspend fun send(command: MediaCommand): Boolean = withContext(io) {
        val manager = sessionManager() ?: return@withContext false
        val controllers = try {
            manager.getActiveSessions(MediaAccessService.componentIn(context))
        } catch (error: Throwable) {
            return@withContext false
        }
        val controller = select(controllers) ?: return@withContext false
        try {
            val controls = controller.transportControls
            when (command) {
                MediaCommand.PREVIOUS -> controls.skipToPrevious()
                MediaCommand.NEXT -> controls.skipToNext()
                MediaCommand.PLAY_PAUSE ->
                    if (isPlaying(controller.playbackState)) controls.pause() else controls.play()
            }
            true
        } catch (error: Throwable) {
            // A session can die between the read above and the call. The panel's next emission will
            // already be showing whatever replaced it.
            false
        }
    }

    // ------------------------------------------------------------------------------------ selection

    /**
     * Which of the active sessions the panel is about.
     *
     * Playing beats paused, and within each group the platform's own order wins — so the answer is "the
     * one making sound, or failing that the one most recently doing so". Deliberately not "the loudest"
     * or "the one in the foreground": neither is knowable from here, and both would change their mind
     * while the user's finger was moving.
     *
     * The filter drops sessions that would draw as an empty plate — no title, and not playing — which is
     * mostly games and navigation apps that publish a session to grab the media buttons and never put
     * anything in it. A session that *is* playing survives the filter even with empty metadata, because
     * a Pause button is worth offering for audio the user can hear whatever it is called.
     */
    private fun select(controllers: List<MediaController>): MediaController? {
        val showable = controllers.filter { titleOf(it.metadata) != null || isActive(it.playbackState) }
        return showable.firstOrNull { isActive(it.playbackState) } ?: showable.firstOrNull()
    }

    /** Playing *or* buffering: a track that is loading is one the user is waiting to hear. */
    private fun isActive(state: PlaybackState?): Boolean = when (state?.state) {
        PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING -> true
        else -> false
    }

    /** What the play/pause button should do next, which is the inverse of what it should look like. */
    private fun isPlaying(state: PlaybackState?): Boolean = isActive(state)

    // ----------------------------------------------------------------------------------- describing

    private fun describe(controller: MediaController): NowPlaying {
        val metadata = try {
            controller.metadata
        } catch (error: Throwable) {
            null
        }
        val state = try {
            controller.playbackState
        } catch (error: Throwable) {
            null
        }
        val app = appLabel(controller.packageName)
        // The app's name stands in for a missing title rather than a placeholder like "Unknown": it is
        // both true and useful, because the session really is that app's, and it gives the transport
        // buttons an owner the user recognises. Only ever a fallback — a real title always wins.
        val title = titleOf(metadata) ?: app ?: controller.packageName
        val artist = firstOf(
            metadata,
            MediaMetadata.METADATA_KEY_ARTIST,
            MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
            MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
        )
        val actions = state?.actions ?: 0L
        return NowPlaying.Track(
            title = title,
            artist = artist,
            // Suppressed when it is already the title, which happens whenever the fallback above fired.
            // Two lines of "Spotify" is a layout that looks broken rather than one that says more.
            appLabel = app?.takeIf { it != title },
            isPlaying = isActive(state),
            art = artFor(metadata, title, artist),
            canPlayPause = allows(
                actions,
                PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE,
            ),
            canSkipNext = allows(actions, PlaybackState.ACTION_SKIP_TO_NEXT),
            canSkipPrevious = allows(actions, PlaybackState.ACTION_SKIP_TO_PREVIOUS),
        )
    }

    /**
     * Whether a transport button should be live.
     *
     * An empty action set is treated as "allowed", not as "nothing works". Apps that publish no actions
     * at all are common — the field is optional and plenty of players leave it at zero while responding
     * to every transport call — so reading zero as a refusal would grey out all three buttons on a track
     * that is playing fine. GameCore cannot honestly predict what an app that declared nothing will
     * accept, so it lets the tap through and reports the refusal if one comes.
     */
    private fun allows(actions: Long, wanted: Long): Boolean = actions == 0L || (actions and wanted) != 0L

    private fun titleOf(metadata: MediaMetadata?): String? = firstOf(
        metadata,
        MediaMetadata.METADATA_KEY_TITLE,
        MediaMetadata.METADATA_KEY_DISPLAY_TITLE,
    )

    /** The first of [keys] that holds readable text. Blank is treated as absent, because it is. */
    private fun firstOf(metadata: MediaMetadata?, vararg keys: String): String? {
        val source = metadata ?: return null
        for (key in keys) {
            val value = try {
                source.getString(key)
            } catch (error: Throwable) {
                null
            }
            val trimmed = value?.trim()
            if (!trimmed.isNullOrEmpty()) return trimmed
        }
        return null
    }

    /**
     * The album art, downscaled once per track rather than once per callback.
     *
     * The cache is what makes [distinctUntilChanged] on [watch] worth having. `MediaController.metadata`
     * is an IPC that unparcels a fresh [Bitmap] every call, so without this the flow would emit a
     * structurally identical [NowPlaying.Track] carrying a new bitmap identity on every position update —
     * never equal to the last one, and so never filtered.
     *
     * Keyed on the track text and the source dimensions, which is the cheapest key that changes when the
     * art does. Two different covers with identical titles, artists and pixel dimensions would reuse the
     * first — a trade accepted knowingly, since the alternative is hashing a bitmap on every tick.
     */
    private fun artFor(metadata: MediaMetadata?, title: String, artist: String?): Bitmap? {
        val source = rawArt(metadata)
        if (source == null) {
            artKey = null
            artValue = null
            return null
        }
        val key = "$title $artist ${source.width}x${source.height}"
        val cached = artValue
        if (cached != null && artKey == key && !cached.isRecycled) return cached
        val scaled = downscale(source)
        artKey = key
        artValue = scaled
        return scaled
    }

    /**
     * Art in the order the platform prefers it.
     *
     * `ALBUM_ART` and `ART` are the same picture for music and differ for anything else — a podcast's
     * episode image against its show's cover — so the episode-specific one is asked for first.
     * `DISPLAY_ICON` and the description's icon are the fallbacks apps that publish no artwork often
     * still fill in, which is most video and browser sessions.
     */
    private fun rawArt(metadata: MediaMetadata?): Bitmap? {
        val source = metadata ?: return null
        val keys = arrayOf(
            MediaMetadata.METADATA_KEY_ALBUM_ART,
            MediaMetadata.METADATA_KEY_ART,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON,
        )
        for (key in keys) {
            val bitmap = try {
                source.getBitmap(key)
            } catch (error: Throwable) {
                null
            }
            if (bitmap != null && !bitmap.isRecycled) return bitmap
        }
        return try {
            source.description?.iconBitmap?.takeIf { !it.isRecycled }
        } catch (error: Throwable) {
            null
        }
    }

    /**
     * Cuts the art down to something a 34dp thumbnail can use.
     *
     * Album art arrives at whatever size the app publishes, which is routinely 1024² and occasionally
     * larger — several megabytes of bitmap held live by an overlay that draws it a hundredth of that
     * size. On failure the original is returned rather than dropped: a hardware-backed bitmap cannot
     * always be copied, and an oversized cover the user can see beats no cover at all.
     *
     * The source is never recycled. It belongs to the app that published it.
     */
    private fun downscale(source: Bitmap): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= ART_MAX_PX || longest <= 0) return source
        val factor = ART_MAX_PX.toFloat() / longest
        return try {
            Bitmap.createScaledBitmap(
                source,
                (source.width * factor).roundToInt().coerceAtLeast(1),
                (source.height * factor).roundToInt().coerceAtLeast(1),
                true,
            )
        } catch (error: Throwable) {
            source
        }
    }

    /** The owning app's name, for the user to read. Falls back to nothing, never to a guess. */
    private fun appLabel(packageName: String?): String? {
        val name = packageName?.takeIf { it.isNotBlank() } ?: return null
        return try {
            val packages = context.packageManager
            packages.getApplicationLabel(packages.getApplicationInfo(name, 0)).toString()
                .trim()
                .takeIf { it.isNotEmpty() }
        } catch (error: Throwable) {
            null
        }
    }

    private fun sessionManager(): MediaSessionManager? = try {
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
    } catch (error: Throwable) {
        null
    }

    private companion object {
        /**
         * The longest edge the panel's thumbnail is ever asked to draw, in pixels.
         *
         * Sized for a 34dp tile on a 4x-density display with room to spare, which covers every phone
         * this app runs on. Fixed rather than derived from the display, because it is a cache key as
         * well as a size and one that changed with configuration would invalidate the cache on rotation.
         */
        const val ART_MAX_PX = 128

        const val NO_SERVICE = "This device has no media session service."
        const val READ_FAILED = "The media session could not be read on this device."
    }
}
