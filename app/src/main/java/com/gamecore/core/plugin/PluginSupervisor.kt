package com.gamecore.core.plugin

/**
 * The watchdog that keeps a misbehaving plugin from taking the app down with it.
 *
 * Two independent failure modes are guarded here, and both end in the plugin being disabled
 * or suspended rather than the host degrading:
 *
 *  - **Crash loop.** A plugin that crashes during startup twice in a row is auto-disabled.
 *    The count is of *consecutive* startup crashes: a plugin that starts cleanly resets its
 *    counter, so an occasional crash does not accumulate toward disablement over weeks. Two
 *    is the threshold because the first crash might be a one-off (a transient device state),
 *    but a second on the very next start is a pattern, and a user should not have to fight an
 *    app that re-crashes every launch because of one bad plugin.
 *  - **Repeated API failure / rate-limit abuse.** A plugin whose broker or API calls fail or
 *    get rate-limited past a threshold within a window is auto-suspended — a softer state
 *    than disabled, cleared when the user re-enables, that stops the plugin acting while
 *    leaving it installed and inspectable.
 *
 * This class is pure bookkeeping over an injected clock and a [PluginGrantStore]; it decides
 * and records, the host carries out the disable/suspend. Keeping it pure means the exact
 * thresholds are unit tests, not something only observable by making a plugin misbehave on a
 * device.
 */
class PluginSupervisor(
    private val store: PluginGrantStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val crashLoopThreshold: Int = 2,
    private val failureThreshold: Int = 10,
    private val failureWindowMs: Long = 60_000L,
    /**
     * How many consecutive runtime failures quarantine a plugin.
     *
     * Quarantine is stronger than the this-run suspension at [failureThreshold]: it persists across
     * restarts and is only cleared by the user. Three is deliberately lower than the suspension
     * threshold because a plugin that has failed three times in a row is not having a transient
     * moment, and the user should not have to wait for ten before the host protects itself.
     */
    private val quarantineThreshold: Int = 3,
) {
    private val consecutiveStartupCrashes = HashMap<String, Int>()
    private data class FailWindow(var start: Long, var count: Int)
    private val failures = HashMap<String, FailWindow>()

    /**
     * Runtime failures since the last clean start or [reset], independent of the rolling window.
     *
     * The window answers "how fast is it failing"; this answers "how many times in a row". A plugin
     * that fails three times across three windows is still failing on every attempt, which is the
     * pattern quarantine exists for.
     */
    private val consecutiveFailures = HashMap<String, Int>()

    /** Call when a plugin is about to start. Pair with [onStartupSucceeded]/[onStartupCrashed]. */
    @Synchronized
    fun onStartupSucceeded(pluginId: String) {
        consecutiveStartupCrashes.remove(pluginId)
        consecutiveFailures.remove(pluginId)
    }

    /**
     * Record a startup crash. Returns true and disables the plugin if it has now crashed on
     * startup [crashLoopThreshold] times in a row.
     */
    @Synchronized
    fun onStartupCrashed(pluginId: String): Boolean {
        val n = (consecutiveStartupCrashes[pluginId] ?: 0) + 1
        consecutiveStartupCrashes[pluginId] = n
        if (n >= crashLoopThreshold) {
            store.setEnabled(pluginId, false)
            consecutiveStartupCrashes.remove(pluginId)
            return true
        }
        return false
    }

    /**
     * Record an API/broker failure or rate-limit. Returns true when this call stopped the plugin,
     * either by suspending it for this run ([failureThreshold] within [failureWindowMs]) or by
     * quarantining it after [quarantineThreshold] consecutive failures.
     */
    @Synchronized
    fun onRuntimeFailure(pluginId: String, detail: String): Boolean {
        val quarantined = recordConsecutiveFailure(pluginId, detail)
        val t = now()
        val w = failures[pluginId]
        if (w == null || t - w.start >= failureWindowMs) {
            // The first failure of a window is still a failure, and must be compared against the
            // threshold like any other. Returning false unconditionally here would make a
            // threshold of 1 — "suspend on the first failure" — never fire at all: every failure
            // would open a fresh window and take this branch, so the plugin would fail forever
            // and never be suspended. Failing closed is also the right default for this class,
            // which exists to stop a plugin, not to keep it running.
            failures[pluginId] = FailWindow(t, 1)
            if (1 < failureThreshold) return quarantined
            store.suspend(pluginId, "Auto-suspended after repeated failures: ${detail.take(80)}")
            failures.remove(pluginId)
            return true
        }
        w.count += 1
        if (w.count >= failureThreshold) {
            store.suspend(pluginId, "Auto-suspended after repeated failures: ${detail.take(80)}")
            failures.remove(pluginId)
            return true
        }
        return quarantined
    }

    /**
     * Counts a failure across windows and quarantines on the configured streak.
     *
     * The detail is host text plus a bounded slice of the failure description; the registry sanitises
     * it again before it reaches a row.
     */
    private fun recordConsecutiveFailure(pluginId: String, detail: String): Boolean {
        val n = (consecutiveFailures[pluginId] ?: 0) + 1
        consecutiveFailures[pluginId] = n
        if (n < quarantineThreshold) return false
        store.quarantine(
            pluginId,
            "Auto-quarantined after $n consecutive failures: ${detail.take(80)}",
        )
        consecutiveFailures.remove(pluginId)
        return true
    }

    @Synchronized
    fun reset(pluginId: String) {
        consecutiveStartupCrashes.remove(pluginId)
        failures.remove(pluginId)
        consecutiveFailures.remove(pluginId)
    }
}
