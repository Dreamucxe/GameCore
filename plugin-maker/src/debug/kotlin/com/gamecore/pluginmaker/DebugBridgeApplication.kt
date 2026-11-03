package com.gamecore.pluginmaker

import android.app.Application
import android.content.Intent
import android.util.Log

/**
 * Starts the debug-only studio bridge service when the maker process starts.
 *
 * This class exists only in the debug source set and is referenced only by
 * `src/debug/AndroidManifest.xml`. The release manifest has no application name, so the release
 * APK never starts a bridge and does not contain this class.
 *
 * The bridge itself lives in [StudioBridgeService], not here: a foreground service keeps the
 * process alive when the user switches to a terminal, while an application-owned bridge can be
 * frozen or reclaimed in the background. The app remains fully usable if the port is busy.
 */
class DebugBridgeApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        runCatching {
            startForegroundService(Intent(this, StudioBridgeService::class.java))
        }.onFailure { failure ->
            Log.w("StudioMcp", "The debug bridge service could not start: ${failure.javaClass.simpleName}")
        }
    }
}
