package com.gamecore.core.system

import android.content.Context
import android.provider.Settings
import android.view.Surface
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.common.Observed
import com.gamecore.core.common.map
import com.gamecore.core.shizuku.WritableSetting
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Brightness and screen-orientation lock.
 *
 * Both are `Settings.System` writes, so both go through [SettingsWriter] and inherit its
 * read-back — a brightness slider that moves and changes nothing is the same class of lie
 * as a refresh-rate button that does.
 *
 * The brightness API has one trap worth stating. `screen_brightness` is only honoured while
 * `screen_brightness_mode` is manual: with automatic mode on, the platform's light sensor
 * overwrites the value within a frame or two. A control that wrote the level alone would
 * appear to work and then be undone by the ambient light sensor, so setting a level here
 * switches automatic mode off first — and [previousAutoBrightness] is captured so a profile
 * can put it back when the game exits.
 *
 * Nothing here writes the *window* brightness attribute. That would only dim GameCore's
 * own overlay, not the screen, and dimming an overlay while claiming to have dimmed the
 * display is the per-window/device-wide confusion again.
 */
@Singleton
class DisplayControls @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsWriter,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** 0..255, as the platform stores it. */
    suspend fun brightness(): Observed<Int> =
        settings.read(WritableSetting.SCREEN_BRIGHTNESS).toIntObserved()

    /**
     * The same level as a percentage, for a slider.
     *
     * Converted here rather than at the call site so the 0..255 scale and its [MIN_LEVEL] floor stay
     * inside the one class that knows about them. A caller dividing by 255 itself would have to know
     * that the lowest level GameCore will write is not zero.
     */
    suspend fun brightnessPercent(): Observed<Int> =
        brightness().map { (it.coerceIn(0, 255) * 100 / 255).coerceIn(0, 100) }

    /**
     * Whether a brightness write has a mechanism right now — the honest pre-check for a slider.
     *
     * Not [canWriteSystemSettings], which answers the narrower question "does GameCore hold
     * WRITE_SETTINGS". Shizuku can write `screen_brightness` when that permission is absent, so
     * asking [SettingsWriter] instead is what keeps a working control from being greyed out.
     */
    suspend fun canSetBrightness(): Boolean =
        settings.mechanismFor(WritableSetting.SCREEN_BRIGHTNESS).isUsable

    suspend fun isAutoBrightnessOn(): Observed<Boolean> =
        settings.read(WritableSetting.SCREEN_BRIGHTNESS_MODE).toBooleanObserved()

    /**
     * Sets a manual brightness level.
     *
     * [percent] rather than raw 0..255, because 0..255 is an implementation detail of the
     * settings key and the minimum usable level is not 0 on any real panel — a slider that
     * can set a black screen the user then cannot see to fix is a trap. The floor is
     * [MIN_LEVEL].
     */
    suspend fun setBrightnessPercent(percent: Int): ControlOutcome = withContext(io) {
        val level = (percent.coerceIn(0, 100) * 255 / 100).coerceIn(MIN_LEVEL, 255)

        // Automatic mode first: with it on, the light sensor overwrites the level.
        val auto = settings.write(WritableSetting.SCREEN_BRIGHTNESS_MODE, MODE_MANUAL)
        if (auto is SettingsWriteOutcome.RequiresAccess) {
            return@withContext ControlOutcome.RequiresAccess(auto.detail, auto.needsShizuku)
        }
        ControlOutcome.from(
            settings.write(WritableSetting.SCREEN_BRIGHTNESS, level.toString()),
        )
    }

    suspend fun setAutoBrightness(enabled: Boolean): ControlOutcome = withContext(io) {
        ControlOutcome.from(
            settings.write(
                WritableSetting.SCREEN_BRIGHTNESS_MODE,
                if (enabled) MODE_AUTOMATIC else MODE_MANUAL,
            ),
        )
    }

    /** The value to restore when a profile unwinds, captured before it changes anything. */
    suspend fun previousAutoBrightness(): String? =
        settings.read(WritableSetting.SCREEN_BRIGHTNESS_MODE).let {
            (it as? Observed.Value)?.value
        }

    // ------------------------------------------------------------------- orientation

    suspend fun isRotationLocked(): Observed<Boolean> = withContext(io) {
        settings.read(WritableSetting.ACCELEROMETER_ROTATION).toBooleanObserved().let { auto ->
            when (auto) {
                is Observed.Value -> Observed.of(!auto.value, auto.source)
                is Observed.Restricted -> auto
                is Observed.Failed -> auto
            }
        }
    }

    /**
     * Locks the screen to an orientation, or releases the lock.
     *
     * Two keys, in an order that matters: `user_rotation` is only consulted while
     * `accelerometer_rotation` is 0, so on the way *into* a lock the rotation is written
     * first and the auto-rotate flag cleared second — otherwise the platform rotates to
     * whatever the accelerometer says in the gap between the two writes, which the user
     * sees as a flicker into portrait and back.
     */
    suspend fun lockRotation(rotation: ScreenRotation): ControlOutcome = withContext(io) {
        val wrote = settings.write(
            WritableSetting.USER_ROTATION,
            rotation.surfaceConstant.toString(),
        )
        if (wrote is SettingsWriteOutcome.RequiresAccess) {
            return@withContext ControlOutcome.RequiresAccess(wrote.detail, wrote.needsShizuku)
        }
        ControlOutcome.from(settings.write(WritableSetting.ACCELEROMETER_ROTATION, "0"))
    }

    suspend fun unlockRotation(): ControlOutcome = withContext(io) {
        ControlOutcome.from(settings.write(WritableSetting.ACCELEROMETER_ROTATION, "1"))
    }

    // ----------------------------------------------------------------- screen timeout

    /**
     * Raises the screen-off timeout for the duration of a session.
     *
     * The alternative — holding a wake lock — keeps the screen on regardless of what the
     * user later decides, survives GameCore being killed badly, and is the mechanism
     * behind every app that has ever left a phone awake all night. A settings write is
     * reversible by the same restore path as every other profile setting, and the platform
     * still turns the screen off when the timeout expires.
     */
    suspend fun setScreenTimeout(millis: Long): ControlOutcome = withContext(io) {
        ControlOutcome.from(
            settings.write(
                WritableSetting.SCREEN_OFF_TIMEOUT,
                millis.coerceIn(15_000L, 1_800_000L).toString(),
            ),
        )
    }

    suspend fun screenTimeoutMillis(): Observed<Long> = withContext(io) {
        when (val raw = settings.read(WritableSetting.SCREEN_OFF_TIMEOUT)) {
            is Observed.Value -> raw.value.toLongOrNull()
                ?.let { Observed.of(it, raw.source) }
                ?: Observed.Failed("The screen timeout could not be read as a number")
            is Observed.Restricted -> raw
            is Observed.Failed -> raw
        }
    }

    // ------------------------------------------------------------------------- helpers

    /**
     * Whether an ordinary app can write these keys at all, for the UI to check before it
     * draws a slider. Brightness and rotation need WRITE_SETTINGS, which is a special
     * access rather than a runtime permission — there is no dialog, only a Settings screen.
     */
    fun canWriteSystemSettings(): Boolean = try {
        Settings.System.canWrite(context)
    } catch (error: Throwable) {
        false
    }

    private fun Observed<String>.toIntObserved(): Observed<Int> = when (this) {
        is Observed.Value -> value.toIntOrNull()
            ?.let { Observed.of(it, source) }
            ?: Observed.Failed("The value was not a number")
        is Observed.Restricted -> this
        is Observed.Failed -> this
    }

    private fun Observed<String>.toBooleanObserved(): Observed<Boolean> = when (this) {
        is Observed.Value -> when (value.trim()) {
            "1" -> Observed.of(true, source)
            "0" -> Observed.of(false, source)
            else -> Observed.Failed("The value was not 0 or 1")
        }
        is Observed.Restricted -> this
        is Observed.Failed -> this
    }

    private companion object {
        const val MODE_MANUAL = "0"
        const val MODE_AUTOMATIC = "1"

        /**
         * About 4%. Below this the screen is unreadable in a lit room, and a user who
         * cannot see the screen cannot undo the setting that made it invisible.
         */
        const val MIN_LEVEL = 10
    }
}

/**
 * The four orientations `user_rotation` accepts.
 *
 * An enum rather than the raw `Surface.ROTATION_*` ints, because those are indices whose
 * meaning depends on the device's natural orientation: `ROTATION_0` is portrait on a phone
 * and landscape on a tablet. The labels here describe what the user asked for on their own
 * device, and [DisplayReader] reports the actual rotation separately.
 */
enum class ScreenRotation(val surfaceConstant: Int, val label: String) {
    NATURAL(Surface.ROTATION_0, "Natural"),
    QUARTER(Surface.ROTATION_90, "Rotated 90°"),
    HALF(Surface.ROTATION_180, "Rotated 180°"),
    THREE_QUARTER(Surface.ROTATION_270, "Rotated 270°"),
    ;

    companion object {
        fun fromSurface(value: Int): ScreenRotation =
            entries.firstOrNull { it.surfaceConstant == value } ?: NATURAL
    }
}
