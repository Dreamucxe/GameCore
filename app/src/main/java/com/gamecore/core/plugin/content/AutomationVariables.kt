package com.gamecore.core.plugin.content

import com.gamecore.core.common.valueOrNull
import com.gamecore.core.model.PerformanceSnapshot
import com.gamecore.domain.gaming.GamingState

/**
 * Builds the condition-variable snapshot an [AutomationEngine] evaluates a rule's conditions
 * against: a flat `name -> number` map of exactly the six live figures a rule may compare.
 *
 * The six — [FPS], [CPU], [GPU], [RAM], [BATTERY], [TEMP] — are the whole published vocabulary,
 * the same ids the plugin SDK's `RuleVariables` mirrors. A rule cannot name any other variable;
 * [AutomationEngine.evaluate] treats a condition on an unknown name as unmet, so adding a key
 * here is the only way to make a new figure comparable, and a plugin cannot smuggle one in.
 * Motion is deliberately *not* among them: it is a gesture signal, not a monitored stat, and a
 * rule has no business steering device actions off it, so it is absent here by omission.
 *
 * Two rules govern what goes in the map, and both matter for safety:
 *
 *  1. **Same extraction as the HUD.** Each value is read from the same snapshot field, in the
 *     same unit, with the same whole-number rounding the overlay draws (CPU/GPU/RAM/battery as
 *     whole percent, FPS as a whole frame count, temperature as whole degrees Celsius taken from
 *     the *battery* sensor, mirroring `HudStatReader`). A rule author writing `temp GT 45` is
 *     comparing against the number they read off their own screen, not a hidden fractional one.
 *  2. **Absent means omitted, never zero.** A figure the device does not expose — FPS on a
 *     device with no readable frame source, GPU load where no counter exists, CPU before its
 *     second sample, battery temperature on a device that withholds it — is left out of the map
 *     entirely. [AutomationEngine] then reads a condition on that key as unmet and the rule
 *     simply does not fire. The alternative, a placeholder `0.0`, would make `fps LT 30` fire
 *     continuously on a device that cannot measure FPS at all, which is the exact "fabricated
 *     0 that reads as a real reading" failure the [com.gamecore.core.common.Observed] discipline
 *     exists to prevent. RAM and battery level are the only two the platform always provides, so
 *     they are always present; the other four appear only when really read.
 *
 * Pure host-side Kotlin: two immutable inputs in, one map out, no Android types and no state.
 */
object AutomationVariables {

    const val FPS = "fps"
    const val CPU = "cpu"
    const val GPU = "gpu"
    const val RAM = "ram"
    const val BATTERY = "battery"
    const val TEMP = "temp"

    /**
     * The condition variables for [perf], or an empty map when there is no snapshot yet.
     *
     * [gaming] is accepted so the automation runtime hands both halves of the runtime one
     * consistent `(perf, gaming)` context, but it contributes no key: none of the six published
     * variables is a session figure — the session facts drive *triggers*
     * ([AutomationTriggerDeriver]), not condition variables. A null [perf] yields an empty map,
     * which, by rule 2 above, means every condition is unmet and no rule fires until real metrics
     * arrive.
     */
    fun of(perf: PerformanceSnapshot?, gaming: GamingState): Map<String, Double> {
        if (perf == null) return emptyMap()

        val vars = LinkedHashMap<String, Double>(6)

        perf.frameRate.valueOrNull?.let { vars[FPS] = whole(it.averageFps) }
        perf.cpu.overallPercent.valueOrNull?.let { vars[CPU] = whole(it) }
        perf.gpu.valueOrNull?.let { vars[GPU] = whole(it.loadPercent) }
        vars[RAM] = whole(perf.memory.usedPercent)
        vars[BATTERY] = perf.battery.levelPercent.toDouble()
        perf.battery.temperatureDeciCelsius.valueOrNull?.let { vars[TEMP] = degrees(it) }

        return vars
    }

    /** A percentage or frame count as the HUD shows it: rounded to a whole number. */
    private fun whole(value: Float): Double = Math.round(value).toDouble()

    /** Tenths of a degree Celsius as the HUD shows it: whole degrees Celsius. */
    private fun degrees(deciCelsius: Int): Double = Math.round(deciCelsius / 10.0f).toDouble()
}
