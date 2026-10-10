package com.gamecore.core.plugin

/**
 * A fixed-window rate limiter, one window per (plugin, action) pair.
 *
 * Every plugin API call is counted. A plugin that floods a call — a runaway automation, a
 * buggy loop, a deliberate attempt to pin the device changing refresh rate a thousand times
 * a second — is cut off at the window's ceiling and told to wait, rather than being allowed
 * to drive the broker or the data layer as fast as it can ask. The limiter is the first line;
 * repeated limiting is itself a signal that [PluginSupervisor] escalates to suspension.
 *
 * Fixed window rather than token bucket on purpose: it is trivial to reason about and to
 * test (N calls allowed per T ms, counted from the first call in a window), and the plugin
 * API is not latency-sensitive enough to need the smoothness a bucket buys. Time is injected
 * as a lambda so the whole thing is a deterministic JVM test with a fake clock.
 */
class PluginRateLimiter(
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Window(var start: Long, var count: Int)

    private val windows = HashMap<String, Window>()

    /**
     * Record one call against [key] and report whether it is within [limit] per [windowMs].
     * The call is counted whether or not it is allowed, so a plugin cannot reset its own
     * window by hammering — the window only rolls when real time passes.
     */
    @Synchronized
    fun allow(key: String, limit: Int, windowMs: Long): Boolean {
        val t = now()
        val w = windows[key]
        if (w == null || t - w.start >= windowMs) {
            // A granted plugin contributes one key per operation, so in normal use this map is
            // tiny and nothing below runs. The sweep is for the abnormal case: a plugin that
            // names a different operation every call would otherwise leave one entry per name
            // for the life of the process — an unbounded map inside the component that exists
            // to bound a plugin's resource use, and one no "evict on uninstall" hook could
            // reach, because the keys of a removed plugin are not known here. Expired windows
            // carry no information (the next call on that key would start a fresh window
            // anyway), so dropping them is free, and it is the oldest ones that go first.
            if (windows.size >= MAX_TRACKED_KEYS) sweepExpired(t, windowMs)
            windows[key] = Window(t, 1)
            return 1 <= limit
        }
        w.count += 1
        return w.count <= limit
    }

    /** Drop every window that has already rolled over, then — if that was not enough — the oldest. */
    private fun sweepExpired(now: Long, windowMs: Long) {
        // Collect first, remove after: both the expiry scan and the overflow eviction touch the
        // same map, and collecting keeps the removal list from being a function of the removals.
        val expired = windows.filterValues { now - it.start >= windowMs }.keys.toList()
        for (key in expired) windows.remove(key)

        var overflow = windows.size - (MAX_TRACKED_KEYS / 2)
        if (overflow <= 0) return
        val oldestFirst = windows.entries.sortedBy { it.value.start }.map { it.key }
        for (key in oldestFirst) {
            windows.remove(key)
            if (--overflow <= 0) break
        }
    }

    @Synchronized
    fun reset(key: String) {
        windows.remove(key)
    }

    @Synchronized
    fun resetAll() {
        windows.clear()
    }

    private companion object {
        /**
         * Far above any real plugin's operation count and far below anything that would matter
         * on a phone, so the sweep only ever fires for an abusive or broken caller.
         */
        const val MAX_TRACKED_KEYS = 4_096
    }
}

/**
 * One line in the plugin audit trail.
 *
 * Every call a plugin makes — allowed or denied — becomes an [AuditEntry], so a user
 * reviewing a plugin can see exactly what it did and what it tried to do, and so a plugin
 * that behaves well in review but misbehaves later leaves a record. The entry names the
 * plugin, the operation, the decision, and a short, already-sanitised detail; it never
 * carries a secret, a token, a raw command, or PII, because the things a plugin can act on
 * do not include any of those and the detail is capped and sanitised before it lands here.
 */
data class AuditEntry(
    val timestamp: Long,
    val pluginId: String,
    val operation: String,
    val outcome: Outcome,
    val detail: String,
) {
    enum class Outcome { ALLOWED, DENIED, CLAMPED, RATE_LIMITED, ERROR }
}

/**
 * A bounded, in-memory ring of recent audit entries per plugin, plus a sink the app can
 * drain to durable storage. Bounded because an audit log that grows without limit is its own
 * denial of service; the UI shows the recent tail, and anything the user needs kept is the
 * app's to persist from [onEntry].
 */
class PluginAuditLog(
    private val capacityPerPlugin: Int = 200,
    private val onEntry: (AuditEntry) -> Unit = {},
) {
    private val byPlugin = HashMap<String, ArrayDeque<AuditEntry>>()

    @Synchronized
    fun record(entry: AuditEntry) {
        val ring = byPlugin.getOrPut(entry.pluginId) { ArrayDeque() }
        ring.addLast(entry)
        while (ring.size > capacityPerPlugin) ring.removeFirst()
        onEntry(entry)
    }

    @Synchronized
    fun recent(pluginId: String): List<AuditEntry> = byPlugin[pluginId]?.toList().orEmpty()

    @Synchronized
    fun clear(pluginId: String) {
        byPlugin.remove(pluginId)
    }
}
