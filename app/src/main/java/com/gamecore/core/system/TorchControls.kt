package com.gamecore.core.system

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import com.gamecore.core.common.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The flashlight.
 *
 * `CameraManager.setTorchMode()` needs no camera permission — that is the whole reason it
 * exists as a separate API from opening the camera — but it does need a camera id whose
 * `FLASH_INFO_AVAILABLE` characteristic is true, and there are three real device situations
 * a floating panel button has to survive:
 *
 *  - No flash unit at all, or a build whose camera HAL does not advertise one. Reported as
 *    [ControlOutcome.Unsupported] via [isAvailable], which the UI checks before it draws the
 *    button, so §24's "not supported on this device" is answered by the control not being
 *    offered rather than by a button that fails when pressed.
 *  - Another app holding the camera. `setTorchMode` throws `CameraAccessException` with
 *    `CAMERA_IN_USE` / `MAX_CAMERAS_IN_USE`, which is a temporary fact about the device, not
 *    a bug — a user with the camera open in another window gets told that.
 *  - The torch being turned off by the system without anyone asking: the platform kills the
 *    torch when the camera is opened, on an overheat, and on some builds when the screen
 *    turns off. So the on/off state is *observed* through a
 *    [CameraManager.TorchCallback] rather than remembered from the last call. A remembered
 *    flag would leave the panel showing "on" over a dark flash.
 *
 * The callback is registered lazily on first use and never unregistered: it is one
 * process-lifetime listener on a `@Singleton`, it costs nothing while the torch is idle,
 * and unregistering it would put the state back to being a guess.
 */
@Singleton
class TorchControls @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    private val torchIsOn = AtomicBoolean(false)
    private val callbackRegistered = AtomicBoolean(false)

    /**
     * Tracks what the torch is actually doing, including changes GameCore did not cause.
     *
     * `TorchCallback` is the only honest source: the platform turns the torch off on camera
     * open and on thermal throttling, and neither of those goes through [setEnabled].
     */
    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == flashCameraId) torchIsOn.set(enabled)
        }

        override fun onTorchModeUnavailable(cameraId: String) {
            if (cameraId == flashCameraId) torchIsOn.set(false)
        }
    }

    /** Resolved once; a camera id does not change while the process lives. */
    private val flashCameraId: String? by lazy { findFlashCamera() }

    /**
     * Whether this device has a controllable torch, for the UI to ask before it draws.
     *
     * Both halves are checked, because they disagree in practice: the feature flag can be
     * declared by a build whose HAL then reports no flash-capable camera, and a few tablets
     * have a flash unit the camera service will not expose to `setTorchMode`.
     */
    suspend fun isAvailable(): Boolean = withContext(io) {
        hasFlashFeature() && flashCameraId != null
    }

    /** The observed state, not a remembered one. */
    fun isOn(): Boolean {
        ensureCallback()
        return torchIsOn.get()
    }

    suspend fun setEnabled(enabled: Boolean): ControlOutcome = withContext(io) {
        val manager = cameraManager()
            ?: return@withContext ControlOutcome.Unsupported("This device has no camera service.")
        val id = flashCameraId
            ?: return@withContext ControlOutcome.Unsupported(
                if (hasFlashFeature()) {
                    "This device declares a flash but does not expose it to apps."
                } else {
                    "This device has no flashlight."
                },
            )
        ensureCallback()
        try {
            manager.setTorchMode(id, enabled)
            // The callback delivers the real state; the local write only covers the gap
            // before it arrives, and the callback overwrites it either way.
            torchIsOn.set(enabled)
            ControlOutcome.Applied()
        } catch (error: android.hardware.camera2.CameraAccessException) {
            when (error.reason) {
                android.hardware.camera2.CameraAccessException.CAMERA_IN_USE,
                android.hardware.camera2.CameraAccessException.MAX_CAMERAS_IN_USE,
                ->
                    ControlOutcome.Failed(
                        "Another app is using the camera, so the flashlight is unavailable.",
                    )
                android.hardware.camera2.CameraAccessException.CAMERA_DISABLED ->
                    ControlOutcome.RequiresAccess(
                        "The camera is disabled by a device policy on this device.",
                    )
                else -> ControlOutcome.Failed("The flashlight could not be switched on this device.")
            }
        } catch (error: IllegalArgumentException) {
            ControlOutcome.Unsupported("This device's camera does not accept torch control.")
        } catch (error: Throwable) {
            ControlOutcome.Failed("The flashlight could not be switched on this device.")
        }
    }

    suspend fun toggle(): ControlOutcome = setEnabled(!isOn())

    /**
     * Sets the torch brightness where the platform supports levels, and says so where it
     * does not.
     *
     * `turnOnTorchWithStrengthLevel` is API 33 and is genuinely absent below it — reported
     * as [ControlOutcome.Unsupported] rather than silently falling back to a plain on,
     * because a slider that maps every position to "full" is a fake control.
     */
    suspend fun setStrength(percent: Int): ControlOutcome = withContext(io) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return@withContext ControlOutcome.Unsupported(
                "Flashlight brightness needs Android 13 or newer.",
            )
        }
        val manager = cameraManager()
            ?: return@withContext ControlOutcome.Unsupported("This device has no camera service.")
        val id = flashCameraId
            ?: return@withContext ControlOutcome.Unsupported("This device has no flashlight.")
        val maxLevel = try {
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
        } catch (error: Throwable) {
            1
        }
        if (maxLevel <= 1) {
            return@withContext ControlOutcome.Unsupported(
                "This device's flashlight has a single brightness level.",
            )
        }
        ensureCallback()
        val level = Math.round(percent.coerceIn(1, 100) * maxLevel / 100f).coerceIn(1, maxLevel)
        try {
            manager.turnOnTorchWithStrengthLevel(id, level)
            torchIsOn.set(true)
            ControlOutcome.Applied()
        } catch (error: Throwable) {
            ControlOutcome.Failed("The flashlight brightness could not be set on this device.")
        }
    }

    // ---------------------------------------------------------------------- internals

    private fun ensureCallback() {
        if (!callbackRegistered.compareAndSet(false, true)) return
        try {
            cameraManager()?.registerTorchCallback(torchCallback, null)
        } catch (error: Throwable) {
            // A build that will not take the callback leaves the state as last written,
            // which is the same position an app without the callback is in.
            callbackRegistered.set(false)
        }
    }

    /**
     * The first camera that reports a flash unit.
     *
     * Back-camera-first is not assumed: the id list is walked and the characteristic read,
     * because some devices expose a flash on a logical multi-camera whose id is neither
     * "0" nor "1".
     */
    private fun findFlashCamera(): String? {
        val manager = cameraManager() ?: return null
        return try {
            manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (error: Throwable) {
            null
        }
    }

    private fun hasFlashFeature(): Boolean = try {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)
    } catch (error: Throwable) {
        false
    }

    private fun cameraManager(): CameraManager? = try {
        context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    } catch (error: Throwable) {
        null
    }
}
