package com.gamecore.core.plugin

import com.gamecore.core.shizuku.ElevatedShell
import com.gamecore.core.shizuku.ShellCommand
import com.gamecore.core.shizuku.WritableSetting
import kotlinx.coroutines.CancellationException

/**
 * The Shizuku Action Broker: the only path from a plugin to a device-affecting change, and
 * the place every security property of that path is enforced.
 *
 * A plugin hands the host an [ActionRequest] — an id and a value. The host, holding the
 * plugin's live [PluginRuntimeState], calls [run]. The broker then, in a fixed order that no
 * call site can reorder or skip:
 *
 *   1. asks the [PluginPermissionGate] whether this plugin may run SYSTEM_ACTIONS at all
 *      (ungranted / no advanced opt-in / suspended / kill switch → rejected);
 *   2. rate-limits per plugin, so a flood is cut off *before* anything is parsed or the
 *      shell is consulted;
 *   3. resolves the id against the closed [BrokerAction] registry (unknown → rejected);
 *   4. validates the value against the setting's [ValueForm] (wrong shape → rejected);
 *   5. clamps the value to the action's own tighter numeric bound if any (and records it);
 *   6. builds the command via [ShellCommand.putSetting] from the registry's [WritableSetting]
 *      and the validated value — the value is an argv element, never concatenated;
 *   7. asks [confirmHighRisk] for a HIGH-tier action, on top of the advanced opt-in;
 *   8. checks the [ElevatedShell] seam is there at all (absent → rejected, nothing attempted) —
 *      before the prior reading, not after, because a prior reading taken through a shell that
 *      cannot answer comes back empty, and the call would then be refused for having no restore
 *      point when the real reason is that there is no shell;
 *   9. for an auto-revert action, captures the prior reading so a restore is possible, and
 *      refuses the write outright if there is no prior reading to restore to;
 *  10. runs the command through the same seam;
 *  11. audits the outcome and, for an auto-revert action, returns the prior reading on the
 *      result ([BrokerResult.Applied.priorValue], or [BrokerResult.Clamped.priorValue] when the
 *      value was also clamped) so the host can schedule and perform the mandatory restore. The
 *      value travels on the result itself rather than in a separate token object so a caller
 *      cannot take the action without the thing it must undo.
 *
 * Step 9 is why an auto-revert action is never fire-and-forget: a reversible action whose prior
 * reading could not be captured — the read failed, or the platform reports the setting as unset —
 * is refused with [BrokerReject.NO_REVERT_POINT] rather than applied with nothing to restore to.
 *
 * The gate and the limiter come first because they are the two checks that are independent of
 * what was asked for: a plugin that may not act, or that is flooding, costs nothing to refuse
 * and must not be able to reach the value parser or the shell to find out. That ordering also
 * means the audit log stays bounded, since an ungranted or flooding plugin's calls are
 * recorded once and then cut off rather than one entry per attempt.
 *
 * The broker never receives, holds, or returns a shell, a binder, or a command string to the
 * plugin. The plugin's only influence on what runs is a single value that must survive the
 * form check and the clamp. The command template is GameCore's.
 *
 * [confirmHighRisk] is the host's per-use confirmation hook for HIGH-tier actions: the gate
 * guarantees the advanced opt-in, this guarantees the per-use prompt. It defaults to refusing
 * so a host that forgets to wire it fails closed. It is asked after the limiter, so a plugin
 * cannot spam a modal it does not own.
 */
class ShizukuActionBroker(
    private val shell: ElevatedShell,
    private val gate: PluginPermissionGate,
    private val rateLimiter: PluginRateLimiter,
    private val auditLog: PluginAuditLog,
    private val now: () -> Long = System::currentTimeMillis,
    private val actionsPerWindow: Int = 20,
    private val windowMs: Long = 10_000L,
) {

    /**
     * Pure, synchronous validation + clamp of a request against the registry. Separated from
     * execution so the whole decision — which is where the security lives — is a JVM unit
     * test with no shell and no coroutine. Returns either the exact command to run plus the
     * resolved action and the value that will actually be applied, or a typed rejection.
     */
    fun plan(request: ActionRequest): Plan {
        val action = BrokerAction.fromId(request.actionId)
            ?: return Plan.Reject(BrokerReject.UNKNOWN_ACTION, auditSafeId(request.actionId))

        val raw = request.value.trim()

        // Clamp numeric values to the action's tighter bound before the form check, so a
        // slightly-out-of-range request is honoured at the bound rather than refused — but
        // only when the request is a number at all; a non-numeric value falls through to the
        // form check, which rejects it.
        val (applied, clamped) = clampIfNeeded(action, raw)

        if (!action.setting.accepts(applied)) {
            return Plan.Reject(BrokerReject.INVALID_VALUE, "'${raw.take(32)}' is not valid for ${action.id}")
        }

        val command = ShellCommand.putSetting(action.setting, applied)
            ?: return Plan.Reject(BrokerReject.COMMAND_BUILD_FAILED, action.id)

        return Plan.Ready(action, applied, raw, clamped, command)
    }

    /**
     * An action id reduced to what the audit trail may carry, for the rejections that happen
     * before an id resolves to a [BrokerAction].
     *
     * The id on a request that never resolved is plugin-supplied text, and the audit trail is
     * the one place in the platform that is required to be bounded: [AuditEntry]'s own contract
     * is that its detail is short and already sanitised. An unrecognised id therefore reaches
     * the log — and the UI that draws the log — trimmed, lowercased and capped at
     * [AUDIT_ID_MAX_LENGTH], with everything outside the registry's own id grammar dropped, so a
     * plugin cannot write a paragraph or a control character into a row. It cannot collide with
     * a real action either: a real id is printed as itself.
     */
    private fun auditSafeId(rawId: String): String {
        val capped = rawId.trim().lowercase().take(AUDIT_ID_MAX_LENGTH)
        val safe = capped.filter { it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '.' || it == '-' }
        return safe.ifEmpty { "(blank)" }
    }

    sealed interface Plan {
        data class Ready(
            val action: BrokerAction,
            val appliedValue: String,
            val requestedValue: String,
            val wasClamped: Boolean,
            val command: ShellCommand,
        ) : Plan
        data class Reject(val reason: BrokerReject, val detail: String) : Plan
    }

    private fun clampIfNeeded(action: BrokerAction, raw: String): Pair<String, Boolean> {
        val min = action.clampMin
        val max = action.clampMax
        if (min == null && max == null) return raw to false
        val n = raw.toFloatOrNull() ?: return raw to false
        val lo = min ?: Float.NEGATIVE_INFINITY
        val hi = max ?: Float.POSITIVE_INFINITY
        val c = n.coerceIn(lo, hi)
        if (c == n) return raw to false
        // Format back the way putSetting(Float) would, so the applied string matches.
        val formatted = if (c == c.toLong().toFloat()) c.toLong().toString() + ".0" else c.toString()
        return formatted to true
    }

    /**
     * Run [request] for [state]'s plugin. Suspends only for the shell call. Every branch
     * returns a typed [BrokerResult]; nothing throws out of here.
     */
    suspend fun run(
        state: PluginRuntimeState,
        request: ActionRequest,
        confirmHighRisk: suspend (BrokerAction) -> Boolean = { false },
    ): BrokerResult {
        val pluginId = state.grant.pluginId

        // Permission first, then the limiter, then everything that costs work.
        //
        // The order is the security: a plugin the gate refuses, or one that is flooding, is
        // stopped by two map lookups and never reaches the value parser, the registry or the
        // shell. Planning first instead would let an ungranted plugin probe the action registry
        // and the value grammar through the difference between UNKNOWN_ACTION, INVALID_VALUE and
        // PERMISSION_DENIED, and would put one audit record per hostile call into the store
        // before permission had been consulted at all.
        //
        // The cost of refusing before the id is resolved is that the audit line for a denied call
        // names the *raw* id, which is plugin-supplied text. It is bounded and sanitised by
        // [auditSafeId] for exactly that reason, and no command is ever built from it.
        val decision = gate.check(state, PluginPermission.SYSTEM_ACTIONS)
        if (decision is PermissionDecision.Deny) {
            audit(pluginId, auditSafeId(request.actionId), AuditEntry.Outcome.DENIED, "${decision.reason}")
            return BrokerResult.Rejected(BrokerReject.PERMISSION_DENIED, decision.reason.name)
        }

        if (!rateLimiter.allow("$pluginId::broker", actionsPerWindow, windowMs)) {
            audit(pluginId, auditSafeId(request.actionId), AuditEntry.Outcome.RATE_LIMITED, "")
            return BrokerResult.Rejected(BrokerReject.RATE_LIMITED)
        }

        // Resolve + validate + clamp + build, now that the plugin is known to be allowed to ask.
        val plan = when (val p = plan(request)) {
            is Plan.Reject -> {
                audit(pluginId, auditSafeId(request.actionId), AuditEntry.Outcome.DENIED, "${p.reason}: ${p.detail}")
                return BrokerResult.Rejected(p.reason, p.detail)
            }
            is Plan.Ready -> p
        }

        // Per-use confirmation for HIGH-tier actions, on top of the advanced opt-in, and after
        // the limiter so a plugin cannot spam a modal it does not own.
        if (plan.action.tier == RiskTier.HIGH && !confirmHighRisk(plan.action)) {
            audit(pluginId, plan.action.id, AuditEntry.Outcome.DENIED, "high-risk confirmation refused")
            return BrokerResult.Rejected(BrokerReject.PERMISSION_DENIED, "high_risk_unconfirmed")
        }

        if (!shell.isAvailable()) {
            audit(pluginId, plan.action.id, AuditEntry.Outcome.DENIED, "shizuku unavailable")
            return BrokerResult.Rejected(BrokerReject.SHIZUKU_UNAVAILABLE)
        }

        // Capture the prior reading before an auto-revert write so a restore is possible even
        // if GameCore is killed between the write and the user leaving the game. An action that
        // declares itself reversible and cannot say what to revert *to* is refused here rather
        // than run unrevertible: a read that failed, or a setting the platform reports as unset,
        // is no restore point, and writing anyway would leave the change with nothing to undo it.
        val prior: String? = if (plan.action.autoRevert) {
            val read = try {
                shell.execute(ShellCommand.getSetting(plan.action.setting)).singleValue()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                null
            }
            if (read == null) {
                audit(
                    pluginId, plan.action.id, AuditEntry.Outcome.DENIED,
                    "no prior reading to revert to",
                )
                return BrokerResult.Rejected(BrokerReject.NO_REVERT_POINT)
            }
            read
        } else null

        val result = try {
            shell.execute(plan.command)
        } catch (cancelled: CancellationException) {
            // A cancelled call is the host going away, not the command failing. AUDIT it as an
            // error and rethrow: swallowing it into a typed rejection would make an abandoned
            // write look like a refused one, and the host would have no way to tell that the
            // command may in fact have run.
            audit(pluginId, plan.action.id, AuditEntry.Outcome.ERROR, "cancelled")
            throw cancelled
        } catch (t: Throwable) {
            audit(pluginId, plan.action.id, AuditEntry.Outcome.ERROR, t.message.orEmpty().take(120))
            return BrokerResult.Rejected(BrokerReject.EXECUTION_FAILED, t.message.orEmpty().take(120))
        }

        if (!result.isSuccess) {
            audit(pluginId, plan.action.id, AuditEntry.Outcome.ERROR, result.failureReason())
            return BrokerResult.Rejected(BrokerReject.EXECUTION_FAILED, result.failureReason())
        }

        // One outcome per call, whichever it is. A clamped call that is recorded as both CLAMPED
        // and ALLOWED would put two contradictory rows in the tail the user reads.
        if (plan.wasClamped) {
            audit(pluginId, plan.action.id, AuditEntry.Outcome.CLAMPED, "${plan.requestedValue} -> ${plan.appliedValue}")
            return BrokerResult.Clamped(plan.action, plan.requestedValue, plan.appliedValue, prior)
        }

        audit(pluginId, plan.action.id, AuditEntry.Outcome.ALLOWED, plan.appliedValue)
        return BrokerResult.Applied(plan.action, plan.appliedValue, prior)
    }

    private fun audit(pluginId: String, op: String, outcome: AuditEntry.Outcome, detail: String) {
        auditLog.record(AuditEntry(now(), pluginId, "broker:$op", outcome, detail))
    }

    private companion object {
        /** Long enough for any real action id, short enough that a hostile one cannot fill a row. */
        const val AUDIT_ID_MAX_LENGTH = 64
    }
}
