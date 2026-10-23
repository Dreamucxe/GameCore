package com.gamecore

import android.app.Application
import com.gamecore.core.common.ApplicationScope
import com.gamecore.core.common.CrashLog
import com.gamecore.core.common.NotificationChannels
import com.gamecore.core.shizuku.ShizukuShell
import com.gamecore.data.repository.BackupManager
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The application entry point.
 *
 * Deliberately almost empty. `Application.onCreate` runs on the main thread before the
 * first frame, and every millisecond spent here is a millisecond the user waits
 * looking at a blank window — so the things that genuinely have to happen at process
 * start happen, and nothing else that blocks. No samplers are begun and no database is
 * read on this thread: the one piece of deferred work — the retention sweep, below —
 * runs on the application scope after start, so the first frame never waits on it.
 */
@HiltAndroidApp
class GameCoreApplication : Application() {

    /**
     * Injected here for one reason: the Shizuku listeners must be registered before
     * any screen can ask about the connection state, and `addBinderReceivedListenerSticky`
     * only reports a binder that arrived earlier if it is registered when that binder
     * arrives. Shizuku's handshake happens through the content provider during process
     * start, which is before this runs but after the listener list exists.
     */
    @Inject
    lateinit var shizukuShell: ShizukuShell

    /**
     * Lazy so the graph behind it — repositories, preferences, the database handle — is
     * resolved on the sweep's own thread rather than on the main thread before the first
     * frame. The sweep is the only thing that touches this field.
     */
    @Inject
    lateinit var backups: Lazy<BackupManager>

    /** The application-lifetime scope; the sweep must outlive no screen in particular. */
    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()

        // First, so a crash anywhere below is written down. A release build has no reachable
        // logcat on a phone without adb, and an unexplained "it just closes" is otherwise
        // undiagnosable from the outside.
        CrashLog.install(this)

        // Channels have to exist before any notification is posted, and a foreground
        // service can be started by the system restarting us before any Activity has
        // run. Creating an existing channel is a no-op, so this is cheap.
        NotificationChannels.createAll(this)

        // Guarded internally: on a device with no Shizuku this touches a class that may
        // not load, and a throw here is a launch crash.
        shizukuShell.attachListeners()

        // The one history task that must happen even if no screen is ever opened: the retention
        // window the user set is a promise that old sessions do not stay forever, and launch is the
        // natural place to keep it. It runs on the application scope (IO) rather than in this call,
        // so the main thread pays nothing beyond the launch, and it reads one setting and exits when
        // the window is off. A failure is swallowed — a failed sweep must never take down the app.
        applicationScope.launch {
            runCatching { backups.get().purgeExpired() }
        }
    }
}
