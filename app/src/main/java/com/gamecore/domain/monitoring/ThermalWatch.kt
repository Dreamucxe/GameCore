package com.gamecore.domain.monitoring

import com.gamecore.core.common.Observed
import com.gamecore.core.common.unavailabilityText
import com.gamecore.core.common.valueOrNull
import com.gamecore.core.model.ThermalStatus

/** What kind of thing just happened to the device's temperature. */
enum class ThermalAlertKind {
    /** The system started limiting performance. Worth interrupting the user for. */
    WARNING,

    /** It stopped. Emitted so the warning can be taken down rather than left on screen. */
    RECOVERED,
}

/**
 * One thing to tell the user about heat, ready to become a notification.
 *
 * [detail] is [ThermalStatus.explanation] — the platform's own account of what it is doing — because
 * the useful message at this moment is "the system is clamping clocks to shed heat, so the frame drop
 * you just saw is the phone protecting itself", not "your device is hot". GameCore cannot cool a
 * phone and does not suggest it can; §22 forbids touching the thermal service at all.
 */
data class ThermalAlert(
    val kind: ThermalAlertKind,
    val status: ThermalStatus,
    val headline: String,
    val detail: String,
)

/**
 * Thermal state with hysteresis, as an immutable value.
 *
 * A phone at the edge of throttling does not sit still: `getCurrentThermalStatus()` flips between
 * MODERATE and SEVERE every couple of seconds while the governor works, and a monitor that notified
 * on every transition would post a dozen notifications a minute and be switched off within one.
 *
 * The rules, in the order they are applied by [update]:
 *
 *  1. Nothing below [ThermalStatus.warrantsAlert] ever alerts. That threshold is SEVERE, which is the
 *     first level at which the platform is actually taking performance away.
 *  2. Getting worse than anything already reported always alerts. A device that goes CRITICAL after a
 *     SEVERE warning is new information and the user gets told, regardless of any interval.
 *  3. Re-entering a level already warned about requires the device to have dropped below the alert
 *     threshold in between *and* [MIN_ALERT_GAP_MILLIS] to have passed. That is the hysteresis: a
 *     phone bouncing between MODERATE and SEVERE every few seconds produces one notification.
 *  4. Falling below the threshold emits one [ThermalAlertKind.RECOVERED] so the notification can be
 *     cancelled. A warning left up after the device recovered is the same kind of lie as a stale
 *     figure.
 *
 * The cooled-off condition is `!warrantsAlert` rather than a second threshold constant, so the level
 * that starts a warning and the level that clears one can never drift apart.
 *
 * A status the device does not report — API 28, or a build with no thermal service — leaves the state
 * untouched and [unavailability] set. It never counts as "cool", because not knowing and being fine
 * are different states and only one of them is worth showing a user.
 *
 * Pure and self-contained, so §31's tests drive it with a list of statuses and a fake clock.
 */
data class ThermalWatch(
    val status: ThermalStatus? = null,
    val peakLevel: Int = -1,
    val unavailability: String? = null,
    /** Produced by the most recent [update] and consumed once. Null on a tick with nothing to say. */
    val alert: ThermalAlert? = null,
    private val alertedLevel: Int = -1,
    private val lastAlertAtMillis: Long = 0L,
    private val cooledSinceAlert: Boolean = true,
) {

    /** True while the platform is limiting performance, for the dashboard's throttling badge. */
    val isThrottling: Boolean get() = status?.warrantsAlert == true

    val peakStatus: ThermalStatus? get() = ThermalStatus.fromPlatform(peakLevel)

    fun update(reading: Observed<ThermalStatus>, nowMillis: Long): ThermalWatch {
        val next = reading.valueOrNull
            ?: return copy(alert = null, unavailability = reading.unavailabilityText())
        return update(next, nowMillis)
    }

    fun update(next: ThermalStatus, nowMillis: Long): ThermalWatch {
        val base = copy(
            status = next,
            peakLevel = maxOf(peakLevel, next.level),
            unavailability = null,
            alert = null,
        )

        if (!next.warrantsAlert) {
            val outstanding = !cooledSinceAlert && alertedLevel >= 0
            return base.copy(
                alert = if (outstanding) recoveryAlert(next) else null,
                cooledSinceAlert = true,
            )
        }

        val worseThanReported = next.level > alertedLevel
        val fairReEntry = cooledSinceAlert && nowMillis - lastAlertAtMillis >= MIN_ALERT_GAP_MILLIS
        if (!worseThanReported && !fairReEntry) return base.copy(cooledSinceAlert = false)

        return base.copy(
            alert = warningAlert(next),
            alertedLevel = next.level,
            lastAlertAtMillis = nowMillis,
            cooledSinceAlert = false,
        )
    }

    /** Clears [alert] once it has been raised, so the next tick does not raise it twice. */
    fun consumed(): ThermalWatch = if (alert == null) this else copy(alert = null)

    private fun warningAlert(status: ThermalStatus) = ThermalAlert(
        kind = ThermalAlertKind.WARNING,
        status = status,
        headline = "Device is throttling · ${status.label}",
        detail = status.explanation,
    )

    private fun recoveryAlert(status: ThermalStatus) = ThermalAlert(
        kind = ThermalAlertKind.RECOVERED,
        status = status,
        headline = "Temperature back to normal",
        detail = "The system is no longer limiting performance. Current state: ${status.label}.",
    )

    companion object {
        /** Two minutes. Long enough that a fast flap cannot produce a stream of notifications. */
        const val MIN_ALERT_GAP_MILLIS = 120_000L

        val INITIAL = ThermalWatch()
    }
}
