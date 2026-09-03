package com.gamecore.core.system

import com.gamecore.core.common.DataSource
import com.gamecore.core.common.Observed
import com.gamecore.core.model.DisplaySize
import com.gamecore.core.model.DisplaySizeState

/**
 * The two lines `wm size` prints.
 *
 * Kept out of [DumpsysParsers] because this is not a `dumpsys` section — it is one shell command with a
 * fixed, small output — but it follows the same rule, which is the one that matters: a parse that does not
 * match returns [Observed.Failed], never a guess. A guess here would be worse than in a thermal reading. The
 * physical size is what "Reset to native" goes back to and what every preset is computed from, so a
 * fabricated one would stretch the display to a shape derived from a number nobody read.
 *
 * ```
 * Physical size: 1080x2400
 * Override size: 1080x1440
 * ```
 *
 * The second line is absent when nothing has set a size, which is the normal case and not a failure. An
 * override equal to the physical size is reported as an override, because that is what it is: a row in the
 * display manager's settings that outlives GameCore and that only `wm size reset` removes.
 */
internal object DisplaySizeParser {

    fun parse(text: String): Observed<DisplaySizeState> {
        val physical = PHYSICAL.find(text)?.groupValues?.getOrNull(1)?.let(DisplaySize::parse)
            ?: return Observed.Failed("This device's window manager did not report a display size")
        val override = OVERRIDE.find(text)?.groupValues?.getOrNull(1)?.let(DisplaySize::parse)
        return Observed.of(
            DisplaySizeState(physical = physical, override = override),
            DataSource.SHELL_SHIZUKU,
        )
    }

    /** `Physical size: 1080x2400`. The panel, whatever is currently sitting on top of it. */
    private val PHYSICAL = Regex("""Physical size:\s*(\d+\s*[xX]\s*\d+)""", RegexOption.IGNORE_CASE)

    /** `Override size: 1080x1440`. Printed only while an override is set. */
    private val OVERRIDE = Regex("""Override size:\s*(\d+\s*[xX]\s*\d+)""", RegexOption.IGNORE_CASE)
}
