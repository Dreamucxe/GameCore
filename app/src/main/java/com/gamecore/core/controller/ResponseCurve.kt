package com.gamecore.core.controller

import kotlin.math.hypot
import kotlin.math.pow

/** A normalized stick position. Not tied to a device or an axis; just two components in `-1f..1f`. */
data class StickReading(
    val x: Float,
    val y: Float,
) {
    val magnitude: Float get() = hypot(x, y)
    val isFinite: Boolean get() = x.isFinite() && y.isFinite()

    companion object {
        val CENTRE = StickReading(0f, 0f)
    }
}

/** The transfer shape offered by the curve lab. */
enum class ResponseCurveKind(val label: String) {
    LINEAR("Linear"),
    EXPONENTIAL("Exponential"),
    SMOOTH("Smooth"),
}

/**
 * A radial dead zone and a response curve, as a reference setting.
 *
 * These values are **not applied to any game**. Android exposes no supported way for GameCore to change
 * how another app reads a controller, and the app does not intercept controller input outside its own
 * focused screen. The lab simulates what a game using these settings would do with the same stick position;
 * it cannot make a game use them. Profiles save the values so runs can be compared and reproduced.
 */
data class ResponseCurveSettings(
    val deadzone: Float = 0.08f,
    val kind: ResponseCurveKind = ResponseCurveKind.EXPONENTIAL,
    val exponent: Float = 2f,
) {
    /** Bounds the UI and imports use. Never silently stores a dead zone that swallows the whole stick. */
    fun sanitized(): ResponseCurveSettings = copy(
        deadzone = deadzone.coerceIn(0f, MAX_DEADZONE),
        exponent = exponent.coerceIn(MIN_EXPONENT, MAX_EXPONENT),
    )

    companion object {
        const val MIN_DEADZONE = 0f
        const val MAX_DEADZONE = 0.50f
        const val MIN_EXPONENT = 1f
        const val MAX_EXPONENT = 4f
    }
}

/**
 * The radial dead-zone and curve arithmetic.
 *
 * A radial dead zone is used rather than a per-axis one because a per-axis dead zone changes direction as
 * well as magnitude: a stick pushed diagonally would come out skewed toward an axis. Here the input vector's
 * direction is preserved and only its radius is shaped, which is what a game with a radial response curve
 * does.
 *
 * The transfer function is applied to the travel that remains after the dead zone, so the result still
 * reaches 1 at full deflection. [ResponseCurveKind.SMOOTH] is the classic smoothstep and ignores
 * [ResponseCurveSettings.exponent]; the other two use it, with 1 meaning linear for both.
 */
object ResponseCurve {

    fun apply(input: StickReading, settings: ResponseCurveSettings): StickReading {
        if (!input.isFinite) return StickReading.CENTRE
        val safe = settings.sanitized()
        val length = input.magnitude
        val radius = length.coerceIn(0f, 1f)
        if (radius <= safe.deadzone) return StickReading.CENTRE
        val travel = (radius - safe.deadzone) / (1f - safe.deadzone)
        val shaped = transfer(travel, safe.kind, safe.exponent)
        val scale = if (length <= 0f) 0f else shaped / length
        return StickReading(input.x * scale, input.y * scale)
    }

    /** The shaped magnitude for an input radius in `0f..1f`. Useful for drawing the transfer graph. */
    fun magnitude(radius: Float, settings: ResponseCurveSettings): Float {
        if (!radius.isFinite()) return 0f
        val safe = settings.sanitized()
        val clamped = radius.coerceIn(0f, 1f)
        if (clamped <= safe.deadzone) return 0f
        return transfer((clamped - safe.deadzone) / (1f - safe.deadzone), safe.kind, safe.exponent)
    }

    /**
     * The curve alone, on remaining travel `0f..1f`. Kept separate from the dead zone so a graph can plot
     * the transfer without recomputing the radius arithmetic.
     */
    fun transfer(travel: Float, kind: ResponseCurveKind, exponent: Float): Float {
        val t = travel.coerceIn(0f, 1f)
        return when (kind) {
            ResponseCurveKind.LINEAR -> t
            ResponseCurveKind.EXPONENTIAL -> t.pow(exponent.coerceIn(ResponseCurveSettings.MIN_EXPONENT, ResponseCurveSettings.MAX_EXPONENT))
            ResponseCurveKind.SMOOTH -> t * t * (3f - 2f * t)
        }.coerceIn(0f, 1f)
    }
}
