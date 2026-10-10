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
) {
    private val consecutiveStartupCrashes = HashMap<String, Int>()
    private data class FailWindow(var start: Long, var count: Int)
    private val failures = HashMap<String, FailWindow>()

    /** Call when a plugin is about to start. Pair with [onStartupSucceeded]/[onStartupCrashed]. */
    @Synchronized
    fun onStartupSucceeded(pluginId: String) {
        consecutiveStartupCrashes.remove(pluginId)
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
     * Record an API/broker failure or rate-limit. Returns true and suspends the plugin if it
     * has failed [failureThreshold] times inside [failureWindowMs].
     */
    @Synchronized
    fun onRuntimeFailure(pluginId: String, detail: String): Boolean {
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
            if (1 < failureThreshold) return false
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
        return false
    }

    @Synchronized
    fun reset(pluginId: String) {
        consecutiveStartupCrashes.remove(pluginId)
        failures.remove(pluginId)
    }
}
