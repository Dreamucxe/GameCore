package com.gamecore.service

import android.app.Notification
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import com.gamecore.R
import com.gamecore.core.common.Formatters
import com.gamecore.core.common.NotificationChannels
import com.gamecore.core.model.HudStat
import com.gamecore.core.model.PerformanceSnapshot
import com.gamecore.data.preferences.SecurePreferenceStore
import com.gamecore.domain.BackgroundServiceGate
import com.gamecore.domain.gaming.GamingCoordinator
import com.gamecore.domain.monitoring.HudStatReader
import com.gamecore.domain.monitoring.PerformanceMonitor
import com.gamecore.domain.monitoring.ThermalAlertKind
import com.gamecore.domain.monitoring.ThermalWatch
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The service that keeps measuring when nothing is on screen to read the measurements.
 *
 * [PerformanceMonitor.snapshots] is a `stateIn(WhileSubscribed)` flow: the sample loop runs while
 * something is collecting it and stops shortly after the last collector leaves. That is the right
 * default — a Performance screen the user navigated away from should not keep reading `/proc/stat` —
 * but it means there are two situations where the measuring the user asked for would quietly not
 * happen, and this service is the subscription that covers them:
 *
 *  - **Background monitoring**, which is a setting the user turned on. Off by default, because it is
 *    the one option in this app that costs battery on its own.
 *  - **A recorded session with thermal warnings on.** The session's own samples come from the
 *    recorder inside [com.gamecore.service.GameDetectionService], but nothing there watches for the
 *    device starting to throttle, and a warning that only arrives when the user opens the app is a
 *    warning about heat they have already played through.
 *
 * Both conditions live in [BackgroundServiceGate.wantsMonitoring], which is also what starts this
 * service — so [watchReasons] can stop it by asking the same question rather than by keeping a second
 * copy of the rule that could disagree.
 *
 * `specialUse` is the foreground-service type. Android 14 has no type for "reads device counters",
 * and the manifest says exactly that with the justification string the platform asks for.
 *
 * What it does *not* do is sample anything itself. A second sampler would double the cost of the
 * thing it exists to make cheap, and two loops reading the same counters at different moments would
 * put two different CPU figures into one session.
 */
@AndroidEntryPoint
class PerformanceMonitorService : GameCoreService() {

    @Inject lateinit var monitor: PerformanceMonitor

    @Inject lateinit var coordinator: GamingCoordinator

    @Inject lateinit var preferences: SecurePreferenceStore

    @Inject lateinit var services: BackgroundServiceGate

    override val notificationId: Int = NotificationChannels.ID_MONITOR

    override val serviceType: Int = TYPE_SPECIAL_USE

    private var latest: PerformanceSnapshot? = null

    /** Thermal hysteresis, carried across samples so a flapping device produces one warning. */
    private var thermal: ThermalWatch = ThermalWatch.INITIAL

    /** True while a fast-drain warning is outstanding, so it is not posted once per sample. */
    private var drainAlerted = false

    private var postedText: String? = null

    private var postedAtMillis = 0L

    // CHUNK_2

    override fun buildNotification(): Notification = notificationWith(statsLine())

    override fun onCreate() {
        super.onCreate()
        if (!goForeground()) return
        lifecycleScope.launch { sample() }
        lifecycleScope.launch { watchBattery() }
        lifecycleScope.launch { watchReasons() }
    }

    /**
     * The subscription. Collecting is the work.
     *
     * Holding this collector is what keeps [PerformanceMonitor]'s single sample loop alive; everything
     * else in this method is a use for the samples that were going to be taken anyway.
     */
    private suspend fun sample() {
        monitor.snapshots.collect { snapshot ->
            if (snapshot == null) return@collect
            latest = snapshot
            describe(snapshot.capturedAtMillis)
            if (preferences.settings.value.showThermalWarnings) checkThermal(snapshot)
        }
    }

    /**
     * Re-posts the notification, but not on every sample.
     *
     * The sampler ticks as often as every second at the user's chosen interval, and a `notify` per
     * second from a service whose entire justification is that it is cheap would be self-defeating.
     * So: only when the text has actually changed, and never more often than
     * [NOTIFICATION_INTERVAL_MILLIS]. The channel is `IMPORTANCE_MIN`, so this is a line in the shade
     * that the user looks at deliberately rather than something competing for their attention.
     */
    private fun describe(nowMillis: Long) {
        val text = statsLine()
        if (text == postedText) return
        if (nowMillis - postedAtMillis < NOTIFICATION_INTERVAL_MILLIS) return
        postedText = text
        postedAtMillis = nowMillis
        refresh(notificationWith(text))
    }

    // CHUNK_3

    /**
     * The headline stats, or a line saying none have arrived yet.
     *
     * Built by [HudStatReader], the same resolver the overlay pill uses, so a temperature this device
     * does not expose is absent here for the same reason and with the same wording as it is absent
     * there. Unavailable stats are dropped rather than rendered as "n/a": the pill has a fixed layout
     * to keep, a notification line does not, and four placeholders would say less than nothing.
     */
    private fun statsLine(): String {
        val readings = HudStatReader.readAll(NOTIFICATION_STATS, latest)
        val line = readings.filter { it.isAvailable }.joinToString(SEPARATOR) { it.displayLabelled() }
        return line.ifEmpty { getString(R.string.notification_monitor_waiting) }
    }

    private fun notificationWith(text: String): Notification = ServiceNotifications.ongoing(
        context = this,
        channelId = NotificationChannels.MONITOR,
        title = getString(R.string.notification_monitor_title),
        text = text,
        stopTarget = PerformanceMonitorService::class.java,
        notificationId = notificationId,
    )

    /**
     * Folds one sample's thermal status into the hysteresis, and posts what comes out of it.
     *
     * A [ThermalAlertKind.RECOVERED] takes the warning down rather than replacing it with a second
     * notification. The alerts channel is the one that is allowed to vibrate, and buzzing a phone to
     * announce that it is no longer too hot would teach the user to silence the channel that carries
     * the warning they do want.
     */
    private fun checkThermal(snapshot: PerformanceSnapshot) {
        thermal = thermal.update(snapshot.thermal.status, snapshot.capturedAtMillis)
        val alert = thermal.alert ?: return
        thermal = thermal.consumed()
        when (alert.kind) {
            ThermalAlertKind.WARNING -> post(
                NotificationChannels.ID_ALERT_THERMAL,
                ServiceNotifications.alert(this, alert.headline, alert.detail),
            )
            ThermalAlertKind.RECOVERED -> cancel(NotificationChannels.ID_ALERT_THERMAL)
        }
    }

    // CHUNK_4

    /**
     * Warns once when the battery is going down unusually fast, and only if the user asked to be told.
     *
     * Gated on `backgroundMonitoring` rather than on the thermal setting: a user who turned that on
     * asked GameCore to keep an eye on the device, whereas a session running with thermal warnings on
     * asked about heat and nothing else. The drain rate itself is on the Performance screen and in
     * every session report either way — this is only about whether it is worth an interruption.
     *
     * [com.gamecore.core.model.BatteryDrain.percentPerHour] is null until the window is long enough to
     * quote a rate from, which is what keeps this from firing on the one percentage point a device
     * happens to drop in the first minute. The alert latches until the rate falls back below
     * [DRAIN_CLEAR_PERCENT_PER_HOUR] or a charger goes in, so a long heavy session produces one
     * notification rather than one per sample.
     */
    private suspend fun watchBattery() {
        monitor.batteryTrend.collect { trend ->
            if (!preferences.settings.value.backgroundMonitoring) return@collect
            if (trend.isCharging) {
                drainAlerted = false
                return@collect
            }
            val drain = trend.drain() ?: return@collect
            val rate = drain.percentPerHour ?: return@collect
            if (rate < DRAIN_CLEAR_PERCENT_PER_HOUR) drainAlerted = false
            if (drainAlerted || rate < DRAIN_ALERT_PERCENT_PER_HOUR) return@collect
            val measured = Formatters.drainPerHour(drain.pointsLost.toFloat(), drain.elapsedMillis)
                ?: return@collect
            drainAlerted = true
            post(
                NotificationChannels.ID_ALERT_BATTERY,
                ServiceNotifications.alert(
                    context = this,
                    title = getString(R.string.alert_battery_title),
                    text = getString(
                        R.string.alert_battery_text,
                        measured,
                        Formatters.durationCoarse(drain.elapsedMillis),
                    ),
                ),
            )
        }
    }

    // CHUNK_5

    /**
     * Stops the service the moment it has nothing left to watch.
     *
     * The question is put to [BackgroundServiceGate.wantsMonitoring] — the same predicate that decides
     * whether to start it — so switching background monitoring off from the Performance screen takes
     * this service down without that screen knowing a service was involved, and a session ending takes
     * it down without the detection service having to stop it twice.
     *
     * Deliberately *not* `distinctUntilChanged`: the settings flow is here as a trigger, and the case
     * that matters is a settings change while the session state is unchanged, which is exactly what
     * collapsing duplicate booleans would swallow. It also means a start this service should never
     * have had — a stale intent reviving it after the user switched everything off — is answered by
     * stopping on the first emission rather than by running until something else notices.
     */
    private suspend fun watchReasons() {
        combine(preferences.settings, coordinator.session) { _, session -> session != null }
            .collect { sessionActive ->
                if (!services.wantsMonitoring(sessionActive)) stopSelf()
            }
    }

    /**
     * Honours Stop by removing the reason, not just the service.
     *
     * A Stop that only stopped the process would be undone by the next thing that calls
     * [BackgroundServiceGate.syncMonitoring] — the next session starting, or the app being opened —
     * and a button that turns a feature off until you play another game is a button that does not
     * work. So the setting that justifies this service goes off with it. A run that exists only for a
     * session's thermal warnings has no setting to clear and simply stops; the warnings stay on for
     * the next session, which is what that setting means.
     */
    override fun onStopRequested(): Boolean {
        if (preferences.settings.value.backgroundMonitoring) {
            preferences.updateSettings { it.copy(backgroundMonitoring = false) }
        }
        return true
    }

    override fun onDestroy() {
        // Both alerts describe something that was being observed. Nothing is observing now, so nothing
        // is left on screen making a claim about the device that cannot be updated or withdrawn.
        cancel(NotificationChannels.ID_ALERT_THERMAL)
        cancel(NotificationChannels.ID_ALERT_BATTERY)
        super.onDestroy()
    }

    // CHUNK_6

    /**
     * Posts one of the two alerts.
     *
     * Separate ids from the service's own notification, and on the alerts channel rather than the
     * monitor one: the ongoing line is a platform requirement the user did not ask for, and these are
     * the two things they did. Silently dropped when notifications are denied, like every other post
     * in this app — the service is still allowed to run, and crashing over a revoked permission would
     * lose the session it was watching.
     */
    private fun post(id: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(this).notify(id, notification)
        } catch (denied: SecurityException) {
            // POST_NOTIFICATIONS revoked while running. The measuring continues either way.
        }
    }

    private fun cancel(id: Int) = NotificationManagerCompat.from(this).cancel(id)

    private companion object {

        /**
         * What the notification line shows, in this order.
         *
         * The four that answer "is this device coping": load, memory pressure, how much battery is
         * left and how hot it has got. Frame rate is not among them on purpose — it is unavailable on
         * most devices, and a shade line is the last place to start implying otherwise.
         */
        val NOTIFICATION_STATS = listOf(
            HudStat.CPU_USAGE,
            HudStat.RAM_USAGE,
            HudStat.BATTERY_LEVEL,
            HudStat.CPU_TEMPERATURE,
        )

        const val SEPARATOR = " · "

        /** Ten seconds. Slow enough to be free, fast enough that the shade is not stale. */
        const val NOTIFICATION_INTERVAL_MILLIS = 10_000L

        /**
         * 40% an hour: a full battery gone in two and a half hours, which is faster than a demanding
         * game on a healthy device and worth a look. The clear threshold is lower so a rate hovering
         * at the line cannot alternate.
         */
        const val DRAIN_ALERT_PERCENT_PER_HOUR = 40f

        const val DRAIN_CLEAR_PERCENT_PER_HOUR = 30f
    }
}
