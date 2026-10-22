package com.gamecore.core.plugin.theme

import com.gamecore.core.plugin.settings.SettingDef
import com.gamecore.core.plugin.settings.SettingValue
import kotlin.math.roundToLong

/**
 * The bridge between a plugin's own numeric setting and a `glass` block's intensity.
 *
 * The Control Center is a slider the user drags between "none of this material" and "all of it", and
 * the thing it actually moves is a setting the plugin declared — which the plugin's author gave
 * whatever type and bounds suited them. This file is the one place those two languages meet, and it
 * is pure Kotlin so the arithmetic is pinned by a JVM test rather than by dragging a finger across a
 * screen and squinting.
 *
 * ## Why a *fraction* and not a value
 *
 * Everything here converts in both directions through a 0..1 fraction, because a fraction is the
 * only thing the slider, the material and the readout can all agree on. A plugin that declared
 * `int("glassLevel", default = 70, min = 0, max = 100)` and one that declared
 * `float("glassIntensity", default = 0.7, min = 0.0, max = 1.0)` are asking for the same control;
 * normalising against the definition each one wrote is what lets the host draw one slider for both
 * and write back a value each will accept.
 *
 * ## What makes a setting usable as a control
 *
 * [isGlassIntensityControl] is the gate, and it is stricter than "the type is a number" — which is
 * all the SDK builder checks, because the builder cannot see a plugin's runtime. A number whose
 * range cannot be recovered is a slider with no travel: dragging it would write a value that either
 * always clamps to the same end or is silently reinterpreted. So a setting qualifies only when its
 * range is *readable*, in one of the two ways below, and the registry rejects a theme whose control
 * does not qualify rather than rendering a slider that does nothing.
 */

/**
 * How a declaration's range is read, once it has been accepted.
 *
 * Two shapes, and no third. A declaration with both bounds gives its range outright. One without is
 * read as a plain 0..1 number, which is the shape a plugin writing `float("glassIntensity", 0.7)`
 * means and is the only unbounded case this can honour without inventing a range on the author's
 * behalf. A declaration with one bound, or with an integer default outside 0..1 and no bounds, is
 * not readable and is therefore not a control — see [isGlassIntensityControl].
 */
internal sealed interface GlassScale {
    /** The declaration's own min..max, both present and distinct. */
    data class Bounded(val min: Double, val max: Double) : GlassScale

    /** No bounds: the value *is* the fraction, and the plugin has promised it lives in 0..1. */
    data object Unit : GlassScale

    companion object {
        /** The scale [def] declares, or null when it declares none this can read. */
        fun of(def: SettingDef): GlassScale? = when (def) {
            is SettingDef.FloatDef -> bounded(def.min, def.max)
                ?: UNIT_IF_IN_RANGE(def.defaultValue)

            is SettingDef.IntDef -> bounded(
                def.min?.toDouble(),
                def.max?.toDouble(),
            ) ?: def.defaultValue.toDouble().let { UNIT_IF_IN_RANGE(it) }

            else -> null
        }

        private fun bounded(min: Double?, max: Double?): GlassScale? =
            if (min != null && max != null && max > min) Bounded(min, max) else null

        private fun UNIT_IF_IN_RANGE(default: Double): GlassScale? =
            if (default.isFinite() && default in 0.0..1.0) Unit else null
    }
}

/**
 * Whether [def] is a setting the Control Center can drive, and the scale to drive it on.
 *
 * Null for everything that is not: a boolean, a string, an enum, a number whose range cannot be
 * recovered. The two callers are the registry, which refuses a theme naming one of these, and the
 * Control Center, which offers a slider only when this returns a scale.
 */
internal fun SettingDef.glassIntensityScale(): GlassScale? = GlassScale.of(this)

/**
 * The current value of [def] as a 0..1 fraction, or null when [value] is not a number it can read.
 *
 * Clamped into 0..1 rather than trusted: the store's own coercion holds a stored value inside the
 * declaration's bounds, but a definition whose default sits outside them — one the host did not
 * write — would otherwise produce a fraction the slider could not draw.
 */
fun fractionOf(def: SettingDef, value: SettingValue): Float? {
    val scale = def.glassIntensityScale() ?: return null
    val raw = when (value) {
        is SettingValue.FloatValue -> value.value
        is SettingValue.IntValue -> value.value.toDouble()
        else -> return null
    }
    if (!raw.isFinite()) return null
    return when (scale) {
        is GlassScale.Bounded -> ((raw - scale.min) / (scale.max - scale.min)).toFloat()
        GlassScale.Unit -> raw.toFloat()
    }.coerceIn(0f, 1f)
}

/**
 * The value to store for [def] to place the control at [fraction], or null when [def] is not a
 * control.
 *
 * The exact inverse of [fractionOf], and it round-trips: reading a value and writing the fraction
 * back produces the same value for every bounded declaration, and for a unit one produces it to
 * within a float's precision. Integer declarations round to the nearest whole step, which is the one
 * place the round trip cannot be exact and the reason a plugin that wants fine control declares a
 * float.
 */
fun valueFor(def: SettingDef, fraction: Float): SettingValue? {
    val scale = def.glassIntensityScale() ?: return null
    val clamped = fraction.coerceIn(0f, 1f).toDouble()
    return when (scale) {
        is GlassScale.Bounded -> {
            val scaled = scale.min + (scale.max - scale.min) * clamped
            when (def) {
                is SettingDef.IntDef -> SettingValue.IntValue(scaled.roundToLong())
                else -> SettingValue.FloatValue(scaled)
            }
        }

        GlassScale.Unit -> when (def) {
            is SettingDef.IntDef -> SettingValue.IntValue(clamped.roundToLong())
            else -> SettingValue.FloatValue(clamped)
        }
    }
}
