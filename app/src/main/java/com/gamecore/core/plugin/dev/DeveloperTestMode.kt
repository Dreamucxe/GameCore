package com.gamecore.core.plugin.dev

/**
 * The state machine behind "test this plugin" in Settings → Developer → Plugin Development.
 *
 * Developer mode lets a plugin author load an unpackaged folder and try it live, which is
 * exactly the capability that, left unbounded, would be a way around the install flow. Two
 * invariants keep it from being that, and both are enforced here rather than in the UI:
 *
 *  - **Test mode always expires.** A plugin under test is applied for at most
 *    [TEST_WINDOW_MS] (60s) and then auto-reverts, whether or not the developer is still
 *    looking at it. A crashed or forgotten test session cannot leave a half-applied plugin in
 *    effect — [isActiveAt] goes false the instant the deadline passes, and [remainingMs]
 *    drives the countdown the UI shows.
 *  - **Validation is never relaxed.** This class carries no "skip checks" flag. A locally
 *    loaded folder is parsed and validated by the *same* codecs a released package goes
 *    through; developer mode changes where the bytes come from, not whether they are trusted.
 *    The absence of a bypass is the point, so there is deliberately no field for one.
 *
 * Pure and clock-injectable so the expiry is a unit test, not something only observable by
 * leaving a device idle for a minute.
 */
class DeveloperTestMode(
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class Session(val pluginId: String, val startedAt: Long, val expiresAt: Long)

    private var session: Session? = null

    /** Begin (or restart) a 60-second test of [pluginId]. Any prior session is replaced. */
    fun start(pluginId: String): Session {
        val t = now()
        return Session(pluginId, t, t + TEST_WINDOW_MS).also { session = it }
    }

    /** Stop the current test immediately (the developer tapped "revert" or loaded another). */
    fun stop() { session = null }

    /** The plugin currently applied under test, or null if none / expired. */
    fun activePluginId(): String? = session?.takeIf { isActiveAt(now()) }?.pluginId

    fun isActiveAt(t: Long): Boolean = session?.let { t < it.expiresAt } ?: false

    /** Milliseconds left before auto-revert, clamped at zero. */
    fun remainingMs(): Long = session?.let { (it.expiresAt - now()).coerceAtLeast(0L) } ?: 0L

    /**
     * Call on a timer tick. Returns the plugin id that just auto-reverted (so the host can
     * tear down its applied effects), or null if nothing expired. Idempotent after expiry.
     */
    fun pollExpiry(): String? {
        val s = session ?: return null
        if (now() >= s.expiresAt) {
            session = null
            return s.pluginId
        }
        return null
    }

    companion object {
        const val TEST_WINDOW_MS = 60_000L
    }
}
