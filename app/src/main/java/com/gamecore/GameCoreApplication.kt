package com.gamecore

import android.app.Application
import com.gamecore.core.common.NotificationChannels
import com.gamecore.core.shizuku.ShizukuShell
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * The application entry point.
 *
 * Deliberately almost empty. `Application.onCreate` runs on the main thread before the
 * first frame, and every millisecond spent here is a millisecond the user waits
 * looking at a blank window — so the two things that genuinely have to happen at
 * process start happen, and nothing else. No samplers are begun, no database is
 * opened, no capability detection is run: those are started by the screen or service
 * that needs them, which is also what lets the app be launched into a state where
 * none of them are needed at all.
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

    override fun onCreate() {
        super.onCreate()

        // Channels have to exist before any notification is posted, and a foreground
        // service can be started by the system restarting us before any Activity has
        // run. Creating an existing channel is a no-op, so this is cheap.
        NotificationChannels.createAll(this)

        // Guarded internally: on a device with no Shizuku this touches a class that may
        // not load, and a throw here is a launch crash.
        shizukuShell.attachListeners()
    }
}
