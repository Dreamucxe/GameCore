package com.gamecore.core.system

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import com.gamecore.core.common.DataSource
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.common.Observed
import com.gamecore.core.permissions.PermissionChecker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Volume, media transport and Do Not Disturb.
 *
 * `AudioManager` needs no special access for volume — MODIFY_AUDIO_SETTINGS is a normal
 * permission granted at install — with one exception that matters here: from API 23,
 * changing volume *while Do Not Disturb is on* requires notification-policy access, and
 * `setStreamVolume` throws `SecurityException` rather than failing quietly. That is caught
 * and reported as [ControlOutcome.RequiresAccess] rather than crashing a floating panel.
 *
 * Do Not Disturb itself is `setInterruptionFilter`, behind the notification-policy special
 * access. A vendor build that has removed the API throws, and a build that has restricted
 * which filters an app may set silently keeps the old one — so the filter is read back and
 * compared, the same rule as everywhere else.
 *
 * Media transport is `dispatchMediaKeyEvent`, which routes a KEYCODE_MEDIA_* to whichever
 * app currently holds audio focus. It is the mechanism the system's own volume dialog uses,
 * it needs no permission, and it cannot be aimed at a specific app — GameCore does not know
 * or need to know which player is listening.
 */
@Singleton
class AudioControls @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissions: PermissionChecker,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    // ------------------------------------------------------------------------ volume

    /** The music stream's level as a percentage, which is the stream a game plays on. */
    suspend fun mediaVolumePercent(): Observed<Int> = withContext(io) {
        val manager = audioManager()
            ?: return@withContext Observed.notPresent("This device has no audio service")
        try {
            val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (max <= 0) {
                return@withContext Observed.Failed("The media stream reported no range")
            }
            val current = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
            Observed.of((current * 100f / max).toInt().coerceIn(0, 100), DataSource.AUDIO_MANAGER)
        } catch (error: Throwable) {
            Observed.Failed("The media volume could not be read", error.message)
        }
    }

    /**
     * Sets the media volume.
     *
     * `STREAM_MUSIC` specifically, not the ring or notification stream: a gaming profile
     * that raised the ringer would be changing something the user did not ask about, and
     * game audio is on the music stream on every Android device.
     */
    suspend fun setMediaVolumePercent(percent: Int): ControlOutcome = withContext(io) {
        val manager = audioManager()
            ?: return@withContext ControlOutcome.Unsupported("This device has no audio service.")
        try {
            val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (max <= 0) {
                return@withContext ControlOutcome.Unsupported(
                    "This device does not report a media volume range.",
                )
            }
            val index = Math.round(percent.coerceIn(0, 100) * max / 100f)
            manager.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)

            // Read back: some builds clamp to a vendor minimum, and a few refuse while a
            // call is active without raising anything.
            val actual = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (actual == index) {
                ControlOutcome.Applied()
            } else {
                ControlOutcome.Applied(
                    "Set to ${(actual * 100f / max).toInt()}%, the closest level this device allows.",
                )
            }
        } catch (error: SecurityException) {
            // The documented API 23+ behaviour: volume changes need notification-policy
            // access while Do Not Disturb is on.
            ControlOutcome.RequiresAccess(
                "Changing volume while Do Not Disturb is on needs notification access.",
            )
        } catch (error: Throwable) {
            ControlOutcome.Failed("The media volume could not be changed on this device.")
        }
    }

    suspend fun adjustMediaVolume(up: Boolean): ControlOutcome = withContext(io) {
        val manager = audioManager()
            ?: return@withContext ControlOutcome.Unsupported("This device has no audio service.")
        try {
            manager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI,
            )
            ControlOutcome.Applied()
        } catch (error: SecurityException) {
            ControlOutcome.RequiresAccess(
                "Changing volume while Do Not Disturb is on needs notification access.",
            )
        } catch (error: Throwable) {
            ControlOutcome.Failed("The media volume could not be changed on this device.")
        }
    }

    // --------------------------------------------------------------- do not disturb

    suspend fun doNotDisturbState(): Observed<DoNotDisturbState> = withContext(io) {
        if (!permissions.hasNotificationPolicyAccess()) {
            return@withContext Observed.needsPermission(
                "Reading the Do Not Disturb state needs notification access",
            )
        }
        val manager = notificationManager()
            ?: return@withContext Observed.notPresent("This device has no notification service")
        try {
            Observed.of(
                DoNotDisturbState.fromFilter(manager.currentInterruptionFilter),
                DataSource.NOTIFICATION_MANAGER,
            )
        } catch (error: Throwable) {
            Observed.Failed("The Do Not Disturb state could not be read", error.message)
        }
    }

    /**
     * Switches Do Not Disturb on or off, and confirms it.
     *
     * The read-back is not ceremony here either: several vendor builds accept
     * `setInterruptionFilter` and apply their own filter instead — a "priority only" mode
     * where the app asked for total silence — and a profile that reported success would be
     * telling the user their notifications are silenced when a call will still ring through.
     */
    suspend fun setDoNotDisturb(enabled: Boolean): ControlOutcome = withContext(io) {
        val wanted = if (enabled) {
            NotificationManager.INTERRUPTION_FILTER_NONE
        } else {
            NotificationManager.INTERRUPTION_FILTER_ALL
        }
        applyFilter(wanted, exact = false)
    }

    /**
     * Puts an exact Do Not Disturb mode back.
     *
     * [setDoNotDisturb] offers on and off, which is the choice a profile makes. A restore has a
     * harder job: a phone that was on "priority only" before the session has to end up on "priority
     * only", not off and not total silence, so the exact filter has to be settable. Without this the
     * restore would have to approximate, and an approximate restore is a change GameCore left behind
     * while reporting that it had tidied up.
     *
     * [DoNotDisturbState.UNKNOWN] is refused rather than approximated: the device reported a filter
     * this build has no name for, and guessing which one it meant is how a phone ends up silent.
     */
    suspend fun setDoNotDisturbState(state: DoNotDisturbState): ControlOutcome = withContext(io) {
        val wanted = state.filter
            ?: return@withContext ControlOutcome.Unsupported(
                "This device reported a Do Not Disturb mode GameCore does not recognise, so it " +
                    "cannot set that mode back.",
            )
        applyFilter(wanted, exact = true)
    }

    /**
     * The write both entry points share.
     *
     * [exact] decides what a partial result means. Switching DND *on* is a request the device may
     * legitimately soften — several vendor builds apply priority-only when asked for total silence —
     * and saying so is enough, because the user's intent was "stop interrupting me". A restore has no
     * such latitude: landing on a different filter than the recorded one is a failure, so the row
     * stays in the restore table and the dashboard keeps offering to retry.
     */
    private fun applyFilter(wanted: Int, exact: Boolean): ControlOutcome {
        if (!permissions.hasNotificationPolicyAccess()) {
            return ControlOutcome.RequiresAccess(
                "Do Not Disturb needs notification access, which is granted in Settings.",
            )
        }
        val manager = notificationManager()
            ?: return ControlOutcome.Unsupported("This device has no notification service.")
        try {
            manager.setInterruptionFilter(wanted)
        } catch (error: SecurityException) {
            return ControlOutcome.RequiresAccess(
                "This device did not allow GameCore to change Do Not Disturb.",
            )
        } catch (error: Throwable) {
            return ControlOutcome.Unsupported(
                "This Android build does not expose Do Not Disturb to apps.",
            )
        }

        val actual = try {
            manager.currentInterruptionFilter
        } catch (error: Throwable) {
            return ControlOutcome.Applied(
                "Applied, but the Do Not Disturb state could not be read back.",
            )
        }
        val wantedLabel = DoNotDisturbState.fromFilter(wanted).label
        val actualLabel = DoNotDisturbState.fromFilter(actual).label
        return when {
            actual == wanted -> ControlOutcome.Applied()
            exact -> ControlOutcome.Failed(
                "GameCore asked for $wantedLabel and this device applied $actualLabel.",
            )
            // Silenced, but not as completely as asked. Said plainly.
            wanted != NotificationManager.INTERRUPTION_FILTER_ALL &&
                actual != NotificationManager.INTERRUPTION_FILTER_ALL ->
                ControlOutcome.Applied("This device applied $actualLabel rather than $wantedLabel.")
            else -> ControlOutcome.Failed(
                "This device accepted the Do Not Disturb change and did not apply it.",
            )
        }
    }

    // ------------------------------------------------------------------ media keys

    /**
     * Sends a media key to whichever app holds audio focus.
     *
     * Both halves of the key event are dispatched, because a receiver that only sees the
     * up event ignores it — the platform's own volume dialog does the same.
     */
    suspend fun sendMediaKey(key: MediaKey): ControlOutcome = withContext(io) {
        val manager = audioManager()
            ?: return@withContext ControlOutcome.Unsupported("This device has no audio service.")
        try {
            manager.dispatchMediaKeyEvent(
                android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, key.keyCode),
            )
            manager.dispatchMediaKeyEvent(
                android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, key.keyCode),
            )
            ControlOutcome.Applied()
        } catch (error: Throwable) {
            ControlOutcome.Failed("The media key could not be sent on this device.")
        }
    }

    /** Whether anything is currently playing audio, so the panel can show a state. */
    suspend fun isMusicActive(): Boolean = withContext(io) {
        try {
            audioManager()?.isMusicActive == true
        } catch (error: Throwable) {
            false
        }
    }

    // --------------------------------------------------------------------- services

    private fun audioManager(): AudioManager? = try {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    } catch (error: Throwable) {
        null
    }

    private fun notificationManager(): NotificationManager? = try {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    } catch (error: Throwable) {
        null
    }
}

/**
 * The interruption filters, named as the platform's own settings name them.
 *
 * Four cases rather than a boolean, because "Do Not Disturb is off" and "Do Not Disturb is
 * on in priority-only mode" are different states and a switch that showed the second as
 * simply "on" would tell a user their alarms are silenced when they are not.
 */
enum class DoNotDisturbState(val label: String) {
    OFF("Off"),
    PRIORITY_ONLY("Priority only"),
    ALARMS_ONLY("Alarms only"),
    TOTAL_SILENCE("Total silence"),
    UNKNOWN("Unknown"),
    ;

    val isSilencing: Boolean get() = this != OFF && this != UNKNOWN

    /**
     * The platform constant for this mode, or null for [UNKNOWN].
     *
     * Null rather than a fallback to [OFF], because "the device reported a filter this build cannot
     * name" and "notifications are allowed through" are different facts and only the caller knows
     * whether guessing between them is acceptable. Nothing in GameCore decides it is.
     */
    val filter: Int?
        get() = when (this) {
            OFF -> NotificationManager.INTERRUPTION_FILTER_ALL
            PRIORITY_ONLY -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
            ALARMS_ONLY -> NotificationManager.INTERRUPTION_FILTER_ALARMS
            TOTAL_SILENCE -> NotificationManager.INTERRUPTION_FILTER_NONE
            UNKNOWN -> null
        }

    companion object {
        fun fromFilter(filter: Int): DoNotDisturbState = when (filter) {
            NotificationManager.INTERRUPTION_FILTER_ALL -> OFF
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> PRIORITY_ONLY
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> ALARMS_ONLY
            NotificationManager.INTERRUPTION_FILTER_NONE -> TOTAL_SILENCE
            else -> UNKNOWN
        }
    }
}

/** The transport keys the floating panel offers. */
enum class MediaKey(val keyCode: Int, val label: String) {
    PLAY_PAUSE(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "Play / pause"),
    NEXT(android.view.KeyEvent.KEYCODE_MEDIA_NEXT, "Next"),
    PREVIOUS(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS, "Previous"),
}
