package com.gamecore.ui.media

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.permissions.GamePermission
import com.gamecore.core.permissions.PermissionChecker
import com.gamecore.domain.media.MediaSessionReader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The explanation screen for the one access the overlay's media strip needs.
 *
 * Nothing is observed across a resume here, for the same reason [com.gamecore.ui.permissions
 * .PermissionsViewModel] observes nothing: the switch is thrown in Android's Settings app, which does not
 * call back. [onResume] is the only moment GameCore reliably learns the answer changed, so it re-asks.
 *
 * What it also does is restart the reader. [MediaSessionReader.watch] settles its access question once per
 * collection — a collection that began before the grant would sit on [com.gamecore.domain.media
 * .NowPlaying.NeedsAccess] forever — so the collection is torn down and rebuilt on every resume rather
 * than being left running across the trip to Settings. That is cheap: the trip to Settings is the only
 * thing that happens on this screen, and a collection that outlived it would be showing the wrong answer
 * at exactly the moment the user came back to check.
 *
 * The reader is watched at all because the honest way to say "this is all GameCore can see" is to show
 * it. The card lists the four fields the strip uses, filled in with this device's current values, and
 * that list *is* the disclosure rather than a promise about one.
 */
@HiltViewModel
class MediaAccessViewModel @Inject constructor(
    private val permissions: PermissionChecker,
    private val media: MediaSessionReader,
) : ViewModel() {

    private val editing = MutableStateFlow(MediaAccessUiState())
    val state: StateFlow<MediaAccessUiState> = editing.asStateFlow()

    /** The collection of [MediaSessionReader.watch], replaced on every resume. */
    private var watching: Job? = null

    init {
        onResume()
    }

    /** Re-reads the switch and restarts the reader. Called from the screen's `OnResume`. */
    fun onResume() {
        val granted = permissions.hasNotificationListenerAccess()
        editing.value = editing.value.copy(
            isLoaded = true,
            hasAccess = granted,
            // Cleared rather than kept: what the previous collection last reported was true of the moment
            // the user left for Settings, and re-showing it here would be the stale data this feature is
            // built to avoid. The card says it is checking until the new collection answers.
            nowPlaying = null,
        )
        watching?.cancel()
        watching = viewModelScope.launch {
            media.watch().collect { playing ->
                editing.value = editing.value.copy(nowPlaying = playing)
            }
        }
    }

    /**
     * Android's list of notification listeners, for the screen to launch.
     *
     * Through [PermissionChecker] rather than built here, so that the resolve-before-returning guarantee
     * that every other Settings link in this app has applies to this one too — §25's boundary, and the
     * reason no composable in GameCore constructs an `Intent`.
     */
    fun settingsIntent(): Intent? =
        permissions.settingsIntentFor(GamePermission.NOTIFICATION_LISTENER)

    /** §28: a resolved intent can still fail to start, and the user hears about it rather than tapping on. */
    fun onIntentFailed() {
        editing.value = editing.value.copy(message = NO_SETTINGS_PAGE)
    }

    fun dismissMessage() {
        editing.value = editing.value.copy(message = null)
    }

    private companion object {
        const val NO_SETTINGS_PAGE =
            "This build of Android would not open that page. The switch lives under Settings, then " +
                "Notifications, then device and app notifications — the name varies by manufacturer."
    }
}
