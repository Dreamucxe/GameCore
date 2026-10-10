package com.gamecore.core.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Part D gate: rate limiter, audit log, and the API facade that wires gate+limit+audit. */
class PluginApiFacadeTest {

    // ---------------------------------------------------------------- rate limiter

    @Test
    fun `rate limiter allows up to the limit then denies within the window`() {
        var t = 0L
        val rl = PluginRateLimiter(now = { t })
        repeat(3) { assertTrue(rl.allow("k", limit = 3, windowMs = 1000)) }
        assertFalse(rl.allow("k", limit = 3, windowMs = 1000))
    }

    @Test
    fun `rate limiter rolls the window after time passes`() {
        var t = 0L
        val rl = PluginRateLimiter(now = { t })
        repeat(3) { rl.allow("k", 3, 1000) }
        assertFalse(rl.allow("k", 3, 1000))
        t = 1000
        assertTrue(rl.allow("k", 3, 1000))
    }

    @Test
    fun `rate limiter keys are independent`() {
        var t = 0L
        val rl = PluginRateLimiter(now = { t })
        repeat(3) { rl.allow("a", 3, 1000) }
        assertFalse(rl.allow("a", 3, 1000))
        assertTrue(rl.allow("b", 3, 1000))
    }

    // ---------------------------------------------------------------- audit log

    @Test
    fun `audit log keeps a bounded tail per plugin`() {
        val log = PluginAuditLog(capacityPerPlugin = 5)
        repeat(10) { log.record(AuditEntry(it.toLong(), "p", "op", AuditEntry.Outcome.ALLOWED, "")) }
        val recent = log.recent("p")
        assertEquals(5, recent.size)
        assertEquals(5L, recent.first().timestamp) // oldest five dropped
    }

    // ---------------------------------------------------------------- facade

    private fun facade(t: () -> Long): Triple<PluginApiFacade, PluginAuditLog, PluginRateLimiter> {
        val log = PluginAuditLog()
        val rl = PluginRateLimiter(now = t)
        return Triple(PluginApiFacade(PluginPermissionGate(), rl, log, now = t), log, rl)
    }

    private fun manifest(vararg perms: PluginPermission) = PluginManifest(
        id = "com.example.plugin", name = "Example", version = 1, versionName = "1.0",
        api = 1, minHostVersion = 1, developer = "Tester", description = "",
        permissions = perms.map { RequestedPermission(it, emptySet()) },
        declares = emptySet(), changelog = "",
    )

    private fun grantedState(vararg perms: PluginPermission): PluginRuntimeState {
        val m = manifest(*perms)
        val g = PluginGrant("com.example.plugin", perms.associateWith { emptySet<String>() })
        return PluginRuntimeState.of(m, g, enabled = true, suspended = false, systemKillSwitch = false)
    }

    @Test
    fun `allowed call runs the block and audits ALLOWED`() {
        var t = 0L
        val (f, log, _) = facade { t }
        val r = f.call(grantedState(PluginPermission.CHANGE_UI), "restyle", PluginPermission.CHANGE_UI,
            rateLimit = 10, windowMs = 1000) { 42 }
        assertEquals(42, (r as ApiResult.Ok<Int>).value)
        assertEquals(AuditEntry.Outcome.ALLOWED, log.recent("com.example.plugin").last().outcome)
    }

    @Test
    fun `denied call never runs the block and audits DENIED`() {
        var t = 0L
        val (f, log, _) = facade { t }
        var ran = false
        val r = f.call(grantedState(/* nothing granted */), "restyle", PluginPermission.CHANGE_UI,
            rateLimit = 10, windowMs = 1000) { ran = true; 1 }
        assertTrue(r is ApiResult.Denied)
        assertFalse(ran)
        assertEquals(AuditEntry.Outcome.DENIED, log.recent("com.example.plugin").last().outcome)
    }

    @Test
    fun `rate-limited call is refused and audited`() {
        var t = 0L
        val (f, log, _) = facade { t }
        val s = grantedState(PluginPermission.CHANGE_UI)
        repeat(2) { f.call(s, "op", PluginPermission.CHANGE_UI, rateLimit = 2, windowMs = 1000) { 1 } }
        val r = f.call(s, "op", PluginPermission.CHANGE_UI, rateLimit = 2, windowMs = 1000) { 1 }
        assertTrue(r is ApiResult.RateLimited)
        assertEquals(AuditEntry.Outcome.RATE_LIMITED, log.recent("com.example.plugin").last().outcome)
    }

    @Test
    fun `a throwing block is absorbed into an Error result`() {
        var t = 0L
        val (f, log, _) = facade { t }
        val r = f.call(grantedState(PluginPermission.CHANGE_UI), "boom", PluginPermission.CHANGE_UI,
            rateLimit = 10, windowMs = 1000) { error("kaboom") }
        assertTrue(r is ApiResult.Error)
        assertEquals(AuditEntry.Outcome.ERROR, log.recent("com.example.plugin").last().outcome)
    }
}
