package com.gamecore.core.plugin

/**
 * The one surface a plugin's declared behaviour reaches GameCore through.
 *
 * A plugin is data, so it never calls this directly — the host does, on the plugin's behalf,
 * when it renders a themed screen, ticks an automation, or reads a stat the plugin's module
 * displays. But every one of those host-side actions is funnelled here first, so the four
 * guarantees the platform rests on are applied in one place and cannot be skipped by a new
 * call site:
 *
 *  1. **Permission** — [PluginPermissionGate.check] against the live runtime state. No call
 *     proceeds without an [PermissionDecision.Allow].
 *  2. **Rate limit** — counted per (plugin, operation); a flood is cut off and recorded.
 *  3. **Clamp** — numeric and enum inputs are bounded by the caller's typed request, never
 *     trusted as given. (The clamping itself lives with each typed operation; this facade
 *     guarantees the *audit* records when a clamp happened.)
 *  4. **Audit** — allowed, denied, clamped, or limited, every call leaves an [AuditEntry].
 *
 * The facade returns a typed [ApiResult] and never throws for a denied, limited, or clamped
 * call — those are ordinary outcomes a well-behaved host must handle, not exceptions. An
 * unexpected error inside an operation is caught and returned as [ApiResult.Error] with the
 * audit outcome ERROR, so one plugin's broken operation cannot propagate out of the facade.
 */
class PluginApiFacade(
    private val gate: PluginPermissionGate,
    private val rateLimiter: PluginRateLimiter,
    private val auditLog: PluginAuditLog,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * Run [block] on behalf of [state]'s plugin, named [operation], requiring [permission]
     * (optionally [scope]), under [rateLimit] calls per [windowMs]. Every gate, limit and
     * outcome is audited. [block] receives nothing it can misuse — it is the host's closure
     * that performs the already-validated work and returns a value.
     */
    fun <T> call(
        state: PluginRuntimeState,
        operation: String,
        permission: PluginPermission,
        scope: String? = null,
        rateLimit: Int,
        windowMs: Long,
        block: () -> T,
    ): ApiResult<T> {
        val pluginId = state.grant.pluginId

        when (val decision = gate.check(state, permission, scope)) {
            is PermissionDecision.Allow -> Unit
            is PermissionDecision.Deny -> {
                audit(pluginId, operation, AuditEntry.Outcome.DENIED, "${decision.reason}: ${decision.detail}")
                return ApiResult.Denied(decision.reason, decision.detail)
            }
        }

        val limiterKey = "$pluginId::$operation"
        if (!rateLimiter.allow(limiterKey, rateLimit, windowMs)) {
            audit(pluginId, operation, AuditEntry.Outcome.RATE_LIMITED, "$rateLimit per ${windowMs}ms")
            return ApiResult.RateLimited
        }

        return try {
            val value = block()
            audit(pluginId, operation, AuditEntry.Outcome.ALLOWED, "")
            ApiResult.Ok(value)
        } catch (t: Throwable) {
            // A plugin's declared behaviour must never crash the host; an operation that
            // blows up is logged and absorbed into a typed error.
            audit(pluginId, operation, AuditEntry.Outcome.ERROR, t.message.orEmpty().take(200))
            ApiResult.Error(t.message.orEmpty())
        }
    }

    /** Record that an operation clamped an input, for the audit tail. */
    fun noteClamp(pluginId: String, operation: String, detail: String) {
        audit(pluginId, operation, AuditEntry.Outcome.CLAMPED, detail.take(200))
    }

    private fun audit(pluginId: String, operation: String, outcome: AuditEntry.Outcome, detail: String) {
        auditLog.record(AuditEntry(now(), pluginId, operation, outcome, detail))
    }
}

/**
 * The result of a facade call. A denial and a rate-limit are first-class outcomes, not
 * errors — a host handles them by falling back to default behaviour, never by crashing.
 */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    data class Denied(val reason: DenyReason, val detail: String) : ApiResult<Nothing>
    data object RateLimited : ApiResult<Nothing>
    data class Error(val message: String) : ApiResult<Nothing>

    val isOk: Boolean get() = this is Ok<*>

    /** The value if ok, else null — the usual "use it or fall back to default" shape. */
    fun orNull(): T? = (this as? Ok<T>)?.value
}
