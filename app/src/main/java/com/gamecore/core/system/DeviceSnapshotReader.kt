package com.gamecore.core.system

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import com.gamecore.core.backup.DeviceSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the handful of device facts a backup carries about where it came from.
 *
 * Small on purpose. The snapshot exists so a restore can say "this came from a Pixel 7 with a 120 Hz
 * panel" and name the settings that may not behave the same way here; it is not an identity and holds
 * nothing that could recognise a user. There is no serial, no Android ID, no account, no locale and no
 * timezone — every one of those would be a tracking field dressed up as compatibility data.
 *
 * The panel numbers come from the display's current [Display.Mode], which reports *physical* pixels.
 * Reading the logical size instead would record GameCore's own `wm size` override as the panel's size,
 * and every aspect and resolution comparison on the receiving device would then be made against the
 * wrong number.
 */
@Singleton
class DeviceSnapshotReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * The current device, as a backup would carry it.
     *
     * Every field past manufacturer and model is nullable, and stays null when the display does not
     * answer rather than becoming a zero. [DeviceMigration] treats null as "cannot compare" and a zero
     * would silently fail every comparison, which is the difference between an honest gap and a wrong
     * warning.
     */
    fun read(): DeviceSnapshot {
        val mode = runCatching {
            val manager = context.getSystemService(DisplayManager::class.java)
            manager?.getDisplay(Display.DEFAULT_DISPLAY)?.mode
        }.getOrNull()

        val peakRefresh = runCatching {
            val manager = context.getSystemService(DisplayManager::class.java)
            manager?.getDisplay(Display.DEFAULT_DISPLAY)
                ?.supportedModes
                ?.maxOfOrNull { it.refreshRate }
                ?.takeIf { it > 0f }
        }.getOrNull()

        return DeviceSnapshot(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            panelWidthPixels = mode?.physicalWidth?.takeIf { it > 0 },
            panelHeightPixels = mode?.physicalHeight?.takeIf { it > 0 },
            peakRefreshRateHz = peakRefresh,
        )
    }
}
