package com.gamecore.core.system

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.WindowManager
import com.gamecore.core.common.DataSource
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.common.Observed
import com.gamecore.core.model.DisplayMode
import com.gamecore.core.model.DisplayReading
import com.gamecore.core.model.DisplaySize
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the panel is and what it can do.
 *
 * Ported from Device Capability Lab's display detector, and it keeps that class's central
 * distinction because it is the one every "120 Hz" claim in a utility app gets wrong:
 *
 *  * [Display.getSupportedModes] is the capability. It is the only reliable source for
 *    the rate list.
 *  * [Display.getRefreshRate] is the mode active at the instant of the call. Android
 *    switches modes constantly, so a 120 Hz panel showing a static screen reports 60 —
 *    and reading that as the device's capability would tell a user with a 120 Hz phone
 *    that they own a 60 Hz phone.
 *
 * Both are read and both are surfaced; nothing in GameCore derives one from the other.
 *
 * Everything here comes from `DisplayManager`, which is open to any app. There is no
 * Shizuku path in this class and no need for one — the shell adds nothing to *reading*
 * the mode list. It is only needed to confirm a *change*, which is
 * [RefreshRateController]'s problem.
 */
@Singleton
class DisplayReader @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    suspend fun read(): DisplayReading = withContext(io) {
        val display = defaultDisplay()
            ?: return@withContext DisplayReading.unavailable(
                "This device did not report a default display",
            )

        val modes = supportedModes(display)
        val size = logicalResolution(display)

        DisplayReading(
            widthPixels = size.x,
            heightPixels = size.y,
            densityDpi = context.resources.displayMetrics.densityDpi,
            currentRefreshRate = currentRate(display),
            supportedRates = distinctRates(modes),
            activeMode = activeMode(display),
            modes = modes,
            rotationDegrees = rotationDegrees(display),
            isHdr = isHdr(display),
        )
    }

    /**
     * Every rate the panel offers, descending and de-duplicated.
     *
     * De-duplicated because a device advertises one mode per resolution *per* rate, so a
     * phone with two resolutions and three rates lists six modes and offers three rates.
     * Presenting the mode count as the rate count is how a 60/90/120 phone ends up
     * offering the user "120 Hz" twice.
     */
    suspend fun supportedRates(): Observed<List<Float>> = withContext(io) {
        val display = defaultDisplay()
            ?: return@withContext Observed.notPresent("This device has no default display")
        distinctRates(supportedModes(display))
    }

    /**
     * True when the panel offers more than one rate *at the resolution it is running*.
     *
     * The comparison has to be within a resolution group. A device whose only extra mode
     * is 1080p60 beside 1440p60 has two modes and one rate, and calling that variable
     * refresh would put a rate selector in front of a user who has no rates to select.
     * Device Capability Lab's test, unchanged.
     */
    suspend fun hasVariableRefreshRate(): Boolean = withContext(io) {
        val display = defaultDisplay() ?: return@withContext false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return@withContext false
        val modes = try {
            display.supportedModes?.toList().orEmpty()
        } catch (error: Throwable) {
            emptyList()
        }
        modes.groupBy { it.physicalWidth to it.physicalHeight }
            .any { (_, group) -> group.map { it.refreshRate }.distinct().size > 1 }
    }

    /**
     * The display's size in pixels, as the platform reports it *to this process*.
     *
     * The same number [read] already carries, exposed on its own because it is asked for a different
     * reason and by a caller that cannot afford the rest of [read]: it is the second witness
     * `DisplaySizeController` uses to confirm a size override took effect. Independent of the shell's
     * account of it, which is the point — `wm size` reports the window manager's settings row, and
     * this reports the size the app's own configuration was rebuilt with.
     *
     * Note what it is not: the *native* size. Once an override is set this reports the overridden
     * size, because that is what the display now is. Only `wm size` distinguishes the two, and nothing
     * here pretends otherwise.
     */
    suspend fun logicalSize(): Observed<DisplaySize> = withContext(io) {
        val display = defaultDisplay()
            ?: return@withContext Observed.notPresent("This device has no default display")
        val size = logicalResolution(display)
        if (size.x > 0 && size.y > 0) {
            Observed.of(DisplaySize(size.x, size.y), windowSource())
        } else {
            Observed.notPresent("This display did not report its size in pixels")
        }
    }

    // ---------------------------------------------------------------------- pieces

    /** Which service answered [logicalResolution], which changes at API 30. */
    private fun windowSource(): DataSource =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            DataSource.WINDOW_MANAGER
        } else {
            DataSource.DISPLAY_MANAGER
        }

    private fun defaultDisplay(): Display? = try {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        manager?.getDisplay(Display.DEFAULT_DISPLAY)
    } catch (error: Throwable) {
        null
    }

    /**
     * The mode table.
     *
     * `getSupportedModes` arrived in API 23, and GameCore's floor is 26, so the version
     * guard is for the compiler rather than for a device — but an empty or absent array
     * is a real outcome on emulators and a few vendor builds, and it is reported as an
     * absence rather than as a single fabricated mode.
     */
    private fun supportedModes(display: Display): Observed<List<DisplayMode>> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return Observed.needsNewerApi("Android 6.0 introduced the display mode list")
        }
        return try {
            val modes = display.supportedModes
                ?.map {
                    DisplayMode(
                        modeId = it.modeId,
                        widthPixels = it.physicalWidth,
                        heightPixels = it.physicalHeight,
                        refreshRate = it.refreshRate,
                    )
                }
                ?.filter { it.refreshRate >= 1f && it.refreshRate <= 480f }
                .orEmpty()
            if (modes.isEmpty()) {
                Observed.notPresent("This display does not report its supported modes")
            } else {
                Observed.of(modes.sortedByDescending { it.refreshRate }, DataSource.DISPLAY_MANAGER)
            }
        } catch (error: Throwable) {
            Observed.Failed("The display mode list could not be read", error.message)
        }
    }

    private fun activeMode(display: Display): Observed<DisplayMode> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return Observed.needsNewerApi("Android 6.0 introduced the display mode API")
        }
        return try {
            display.mode
                ?.let {
                    Observed.of(
                        DisplayMode(
                            modeId = it.modeId,
                            widthPixels = it.physicalWidth,
                            heightPixels = it.physicalHeight,
                            refreshRate = it.refreshRate,
                        ),
                        DataSource.DISPLAY_MANAGER,
                    )
                }
                ?: Observed.notPresent("This display does not report an active mode")
        } catch (error: Throwable) {
            Observed.Failed("The active display mode could not be read", error.message)
        }
    }

    private fun distinctRates(modes: Observed<List<DisplayMode>>): Observed<List<Float>> =
        when (modes) {
            is Observed.Value -> modes.value
                .map { it.refreshRate }
                .distinct()
                .sortedDescending()
                .let { Observed.of(it, modes.source) }
            is Observed.Restricted -> modes
            is Observed.Failed -> modes
        }

    /**
     * The rate right now.
     *
     * Reported with a plausibility floor because a handful of builds return 0.0 from this
     * before the first frame of the calling process has been composited, and a "0 Hz"
     * badge on the dashboard is worse than saying it could not be read.
     */
    private fun currentRate(display: Display): Observed<Float> = try {
        val rate = display.refreshRate
        if (rate >= 1f && rate <= 480f) {
            Observed.of(rate, DataSource.DISPLAY_MANAGER)
        } else {
            Observed.notPresent("This display did not report its current refresh rate")
        }
    } catch (error: Throwable) {
        Observed.Failed("The current refresh rate could not be read", error.message)
    }

    /**
     * The panel's pixel dimensions.
     *
     * `maximumWindowMetrics` on API 30+ because `getSize()` was deprecated there and
     * returns the *app's* bounds on a large screen or in a freeform window, which on a
     * foldable is not the panel. Below 30 `getSize` on the default display is the
     * documented route and is what it was always used for.
     */
    @Suppress("DEPRECATION")
    private fun logicalResolution(display: Display): Point =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                val bounds = wm?.maximumWindowMetrics?.bounds
                if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                    Point(bounds.width(), bounds.height())
                } else {
                    legacySize(display)
                }
            } catch (error: Throwable) {
                legacySize(display)
            }
        } else {
            legacySize(display)
        }

    @Suppress("DEPRECATION")
    private fun legacySize(display: Display): Point = try {
        Point().also { display.getSize(it) }
    } catch (error: Throwable) {
        Point(0, 0)
    }

    private fun rotationDegrees(display: Display): Int = try {
        when (display.rotation) {
            android.view.Surface.ROTATION_90 -> 90
            android.view.Surface.ROTATION_180 -> 180
            android.view.Surface.ROTATION_270 -> 270
            else -> 0
        }
    } catch (error: Throwable) {
        0
    }

    /**
     * Whether the panel advertises any HDR type.
     *
     * `isHdr` on API 26+ is a plain boolean about the display, not about content, and it
     * is shown as device information only — GameCore does not change HDR state.
     */
    private fun isHdr(display: Display): Boolean = try {
        display.isHdr
    } catch (error: Throwable) {
        false
    }
}
