package com.gamecore

import android.app.Application
import com.gamecore.core.common.ApplicationScope
import com.gamecore.core.common.CrashLog
import com.gamecore.core.common.NotificationChannels
import com.gamecore.core.shizuku.ShizukuShell
import com.gamecore.data.repository.BackupManager
import com.gamecore.domain.display.DisplayOverrideRecovery
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The application entry point.
 *
 * Deliberately almost empty. `Application.onCreate` runs on the main thread before the first frame,
 * and every millisecond spent here is a millisecond the user waits looking at a blank window — so the
 * things that genuinely have to happen at process start happen, and nothing else that blocks. No
 * samplers are begun and no database is read on this thread: the two pieces of deferred work below run on
 * the application scope after start, so the first frame never waits on either.
 */
@HiltAndroidApp
class GameCoreApplication : Application() {

    /**
     * Injected here for one reason: the Shizuku listeners must be registered before any screen can ask
     * about the connection state, and `addBinderReceivedListenerSticky` only reports a binder that
     * arrived earlier if it is registered when that binder arrives. Shizuku's handshake happens through
     * the content provider during process start, which is before this runs but after the listener list
     * exists.
     */
    @Inject
    lateinit var shizukuShell: ShizukuShell

    /**
     * Lazy so the graph behind it — repositories, preferences, the database handle — is resolved on the
     * sweep's own thread rather than on the main thread before the first frame. The sweep is the only
     * thing that touches this field.
     */
    @Inject
    lateinit var backups: Lazy<BackupManager>

    /**
     * The display-override dead-man's switch, for the launch half of it.
     *
     * A `wm size` override outlives the process, so a session that was killed — by the user, by Android,
     * by a crash — leaves the display stretched with nobody having answered the question. Waiting for the
     * user to reopen GameCore would mean waiting for somebody who cannot read the screen to navigate to a
     * screen they cannot see, so the next launch answers it.
     *
     * Lazy for the same reason [backups] is: the graph behind it holds the restore-point repository and
     * the elevated shell, and resolving either on the main thread before the first frame is startup
     * latency for a check that will almost always find nothing. The field is read once, on the application
     * scope, by the launch below — and the class it resolves keeps no state of the Application's.
     */
    @Inject
    lateinit var displayRecovery: Lazy<DisplayOverrideRecovery>

    /** The application-lifetime scope; the sweep must outlive no screen in particular. */
    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()

        // First, so a crash anywhere below is written down. A release build has no reachable logcat on a
        // phone without adb, and an unexplained "it just closes" is otherwise undiagnosable from the
        // outside.
        CrashLog.install(this)

        // Channels have to exist before any notification is posted, and a foreground service can be
        // started by the system restarting us before any Activity has run. Creating an existing channel
        // is a no-op, so this is cheap. The dead-man's switch's recovery posts on one of them.
        NotificationChannels.createAll(this)

        // Guarded internally: on a device with no Shizuku this touches a class that may not load, and a
        // throw here is a launch crash.
        shizukuShell.attachListeners()

        // The one history task that must happen even if no screen is ever opened: the retention window
        // the user set is a promise that old sessions do not stay forever, and launch is the natural
        // place to keep it. It runs on the application scope (IO) rather than in this call, so the main
        // thread pays nothing beyond the launch, and it reads one setting and exits when the window is
        // off. A failure is swallowed — a failed sweep must never take down the app.
        applicationScope.launch {
            runCatching { backups.get().purgeExpired() }
        }

        // The dead-man's switch's launch half. Its own launch rather than an extra statement in the sweep
        // above, so that a pending display override is answered even while the sweep is still working
        // through a large session history — and so neither failure can swallow the other. The recovery
        // guards itself: a device with no elevated access posts instructions instead of restoring, and a
        // throw there would otherwise be a crash on launch, which is a far worse outcome than a display
        // that is wrong.
        applicationScope.launch {
            runCatching { displayRecovery.get().onProcessStart() }
        }
    }
}
