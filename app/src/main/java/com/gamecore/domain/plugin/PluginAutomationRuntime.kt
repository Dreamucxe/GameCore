package com.gamecore.domain.plugin

import com.gamecore.core.model.PerformanceSnapshot
import com.gamecore.core.plugin.ActionRequest
import com.gamecore.core.plugin.ActivePluginAutomation
import com.gamecore.core.plugin.AuditEntry
import com.gamecore.core.plugin.BrokerAction
import com.gamecore.core.plugin.BrokerReject
import com.gamecore.core.plugin.BrokerResult
import com.gamecore.core.plugin.PluginAutomationHistoryStore
import com.gamecore.core.plugin.PluginRegistry
import com.gamecore.core.plugin.PluginRuntimeState
import com.gamecore.core.plugin.ShizukuActionBroker
import com.gamecore.core.plugin.content.AutomationActionDefaults
import com.gamecore.core.plugin.content.AutomationEngine
import com.gamecore.core.plugin.content.AutomationRule
import com.gamecore.core.plugin.content.AutomationTriggerDeriver
import com.gamecore.core.plugin.content.AutomationVariables
import com.gamecore.domain.gaming.GamingCoordinator
import com.gamecore.domain.gaming.GamingState
import com.gamecore.domain.monitoring.PerformanceMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * The host-owned runtime that turns a plugin's validated automation *data* into real, gated,
 * audited device actions — and the one place the "a plugin may decide, only the host may act"
 * split is actually wired up.
 *
 * A plugin ships rules ([AutomationRule]): "on TRIGGER, if CONDITIONS, do ACTIONS". That is data,
 * checked at install by [AutomationEngine.validate] and never anything more — there is no code in
 * a plugin and nothing here loads any. This runtime makes those rules *happen* without ever
 * letting the plugin past the two gates between it and the device:
 *
 *  - **[AutomationEngine] decides, it does not act.** For a trigger and a snapshot of condition
 *    variables it returns the ids of the actions a rule wants, and nothing else. A rule naming an
 *    action that does not exist, or whose condition reads a variable the feed does not provide,
 *    simply yields nothing — an unmet precondition, not an error.
 *  - **[ShizukuActionBroker] acts, and re-checks everything.** Every id the engine returns is
 *    dispatched through [ShizukuActionBroker.run], which runs the permission gate, the rate
 *    limiter, the value form, the clamp and — for a HIGH-tier action — the per-use confirmation,
 *    in a fixed order no caller here can reorder or skip. This runtime neither re-implements nor
 *    bypasses any of it: it hands the broker an id and a value and takes back a typed [BrokerResult].
 *
 * The one security decision this class makes on its own is a *refusal*. An automation runs with no
 * user in front of it, so it can never satisfy the per-use HIGH-risk confirmation the broker
 * demands; it therefore hands the broker a confirmer that refuses everything ([REFUSE_HIGH_RISK]).
 * A HIGH-tier action requested by an automation is refused by the broker, every time — an
 * unattended confirmer that said yes would be exactly the bypass the per-use prompt exists to stop.
 * The broker already defaults its confirmer to the same refusal so a forgetful host fails closed;
 * this runtime passes it explicitly so the intent lives at the call site, not in a default.
 *
 * The decision half is split out as a pure, synchronous [plan] — no coroutine, no broker, no flow —
 * so "which actions fire for which (trigger, rules, variables)" is a plain JVM unit test, exactly
 * as the broker splits [ShizukuActionBroker.plan] out from [ShizukuActionBroker.run]. [dispatch] is
 * the thin suspending half that runs one decision through the broker and records the outcome; [run]
 * is the long-lived loop that watches the live feeds, derives triggers on each transition, and
 * feeds the two halves.
 */
class PluginAutomationRuntime(
    private val engine: AutomationEngine,
    private val broker: ShizukuActionBroker,
    private val registry: PluginRegistry,
    private val monitor: PerformanceMonitor,
    private val coordinator: GamingCoordinator,
    private val history: PluginAutomationHistoryStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * One resolved thing to do: run [action] with [value] for the plugin named by [pluginId],
     * against that plugin's live [state].
     *
     * It is the output of the pure [plan] and the input to the suspending [dispatch]. It carries
     * the plugin's [PluginRuntimeState] rather than letting [dispatch] look it up later, so the
     * decision and the permission check see the *same* snapshot: a plugin disabled in the instant
     * between deciding and dispatching is caught by the broker's gate on this very state, not by a
     * second read that could race the first.
     */
    data class Decision(
        val pluginId: String,
        val state: PluginRuntimeState,
        val action: BrokerAction,
        val value: String,
    )

    /**
     * One tick of either live feed, folded into a single stream so both are read by one sequential
     * collector and the "previous" of each can be held in a plain local with no lock: [merge]
     * interleaves the emissions, but the downstream handles them one at a time.
     */
    private sealed interface Signal {
        data class Lifecycle(val next: GamingState) : Signal
        data class Device(val next: PerformanceSnapshot?) : Signal
    }

    /**
     * Watch the gaming and performance feeds for as long as [scope] lives, and act on every rule a
     * transition fires.
     *
     * The two feeds are [merge]d into one so a single sequential collector holds the previous value
     * of each. Trigger derivation is edge-triggered — [AutomationTriggerDeriver] takes the previous
     * and the next and emits a trigger only on the transition — so a sustained thermal-high, or a
     * game that stays open, fires once rather than once per tick. That is the whole flood story on
     * the input side.
     *
     * Variables are rebuilt from the latest of *both* feeds on every transition, because a rule
     * triggered by a lifecycle event may still gate on a device variable and vice versa. A feed with
     * no value yet contributes nothing, so a rule reading a variable that is not there simply does
     * not fire. A null device snapshot (before the first sample) yields no device triggers.
     *
     * Each [Decision] is dispatched on [scope] rather than inline, so a broker call blocked on a
     * shell round trip never stalls the next trigger's derivation. This cannot become a flood: the
     * decisions from one transition are bounded (plugins x rules x the engine's own per-rule action
     * cap), the deriver only fires on edges, and the broker's per-plugin rate limiter caps what
     * actually reaches the device and rejects the rest cheaply, before any shell work. Nothing is
     * caught here that the broker does not already catch.
     */
    suspend fun run(scope: CoroutineScope) {
        var prevGaming: GamingState? = null
        var prevDevice: PerformanceSnapshot? = null
        var latestGaming: GamingState = GamingState.IDLE
        var latestDevice: PerformanceSnapshot? = null

        val lifecycle: Flow<Signal> = coordinator.gaming.map { Signal.Lifecycle(it) }
        val device: Flow<Signal> = monitor.snapshots.map { Signal.Device(it) }

        merge(lifecycle, device).collect { signal ->
            val triggers: List<AutomationRule.Trigger> = when (signal) {
                is Signal.Lifecycle -> {
                    val next = signal.next
                    AutomationTriggerDeriver.lifecycleTriggers(prevGaming, next).also {
                        prevGaming = next
                        latestGaming = next
                    }
                }
                is Signal.Device -> {
                    val next = signal.next
                    val derived = if (next == null) emptyList()
                    else AutomationTriggerDeriver.deviceTriggers(prevDevice, next)
                    prevDevice = next
                    latestDevice = next
                    derived
                }
            }
            if (triggers.isEmpty()) return@collect

            val vars = AutomationVariables.of(latestDevice, latestGaming)
            val automations = registry.activeAutomations.value
            for (trigger in triggers) {
                for (decision in plan(trigger, automations, vars)) {
                    scope.launch { dispatch(decision) }
                }
            }
        }
    }

    /**
     * The decision half, over the injected [engine]. Pure: it returns what the rules *want*,
     * touches no device and suspends on nothing. The logic lives in the static [plan]; this
     * overload exists so call sites read as `plan(trigger, automations, vars)`.
     */
    fun plan(
        event: AutomationRule.Trigger,
        automations: List<ActivePluginAutomation>,
        vars: Map<String, Double>,
    ): List<Decision> = plan(engine, event, automations, vars)

    /**
     * The acting half: run one [decision] through the broker and record the outcome, timing the
     * broker call for the history entry's duration. Thin on purpose — every security property is
     * the broker's, and the only policy added here is the refusing confirmer. The body lives in the
     * static [dispatch]; this overload binds the injected broker, history sink and clock.
     */
    suspend fun dispatch(decision: Decision): BrokerResult =
        dispatch(decision, now, broker::run, history::append)

    companion object {
        /**
         * The per-use HIGH-risk confirmer an automation hands the broker: it refuses every action,
         * always, because an unattended automation can never stand in for the user at a per-use
         * prompt. A confirmer that returned true for any action would defeat the one check the
         * broker cannot make for itself.
         */
        val REFUSE_HIGH_RISK: suspend (BrokerAction) -> Boolean = { false }

        /**
         * Decide which [Decision]s [automations] want for [event] under [vars] — the pure core.
         *
         * For each active automation and each of its rules, [AutomationEngine.evaluate] returns the
         * action ids the rule wants (empty if the trigger does not match, a condition is unmet, or a
         * condition reads a variable the feed does not provide). Each id is resolved against the
         * closed [BrokerAction] registry — one that does not resolve is dropped rather than carried,
         * defence in depth behind install-time validation — and paired with the value
         * [AutomationActionDefaults] supplies for that action. Nothing here acts; the result is a
         * list of intentions for [dispatch] to run through the broker.
         */
        fun plan(
            engine: AutomationEngine,
            event: AutomationRule.Trigger,
            automations: List<ActivePluginAutomation>,
            vars: Map<String, Double>,
        ): List<Decision> {
            val decisions = ArrayList<Decision>()
            for (automation in automations) {
                for (rule in automation.rules) {
                    for (id in engine.evaluate(rule, event, vars)) {
                        val action = BrokerAction.fromId(id) ?: continue
                        decisions += Decision(
                            pluginId = automation.pluginId,
                            state = automation.state,
                            action = action,
                            value = AutomationActionDefaults.valueFor(action),
                        )
                    }
                }
            }
            return decisions
        }

        /**
         * Run one [decision] through [run] (the broker's `run`, taken as a value so this is
         * testable without standing up the whole broker) and record the outcome through [append]
         * (the history store's `append`, taken the same way), timing the call for the entry's
         * duration.
         *
         * The confirmer passed to the broker is always [REFUSE_HIGH_RISK]: a HIGH-tier action is
         * refused, never run unattended. Every [BrokerResult] is recorded — allowed or not — in the
         * broker's own audit vocabulary, so the history a user reviews says what the automation did
         * and what it tried to do. Nothing the broker catches is caught here: a cancellation
         * propagates out as the scope going away, and no history line is written for it, since the
         * broker has already audited the abandoned call in its own log.
         */
        suspend fun dispatch(
            decision: Decision,
            now: () -> Long,
            run: suspend (PluginRuntimeState, ActionRequest, suspend (BrokerAction) -> Boolean) -> BrokerResult,
            append: suspend (AuditEntry, Long?) -> Unit,
        ): BrokerResult {
            val start = now()
            val result = run(decision.state, ActionRequest(decision.action.id, decision.value), REFUSE_HIGH_RISK)
            val end = now()
            append(auditEntryOf(end, decision, result), end - start)
            return result
        }

        /**
         * Map a [BrokerResult] to the one audit line that records it, reusing the broker's own
         * outcome vocabulary so a runtime entry reads the same as a direct broker entry. The
         * operation is prefixed `automation:` to say where the call came from. Every detail is
         * already short and sanitised — a [BrokerResult]'s value and reason are the broker's, never
         * raw plugin text — and is capped again here as defence in depth.
         */
        private fun auditEntryOf(timestamp: Long, decision: Decision, result: BrokerResult): AuditEntry {
            val (outcome, detail) = when (result) {
                is BrokerResult.Applied -> AuditEntry.Outcome.ALLOWED to result.appliedValue
                is BrokerResult.Clamped ->
                    AuditEntry.Outcome.CLAMPED to "${result.requested} -> ${result.appliedValue}"
                is BrokerResult.Rejected -> outcomeFor(result.reason) to
                    if (result.detail.isBlank()) result.reason.name else "${result.reason}: ${result.detail}"
            }
            return AuditEntry(
                timestamp = timestamp,
                pluginId = decision.pluginId,
                operation = "automation:${decision.action.id}",
                outcome = outcome,
                detail = detail.take(AUDIT_DETAIL_MAX),
            )
        }

        /**
         * The broker's own rejection-to-outcome mapping: a rate-limited call is [RATE_LIMITED], an
         * execution or build failure is an [ERROR], and every policy refusal (permission, unknown
         * action, invalid value, no shell, no revert point) is a [DENIED] — the same way the broker
         * records them in its internal audit log.
         */
        private fun outcomeFor(reason: BrokerReject): AuditEntry.Outcome = when (reason) {
            BrokerReject.RATE_LIMITED -> AuditEntry.Outcome.RATE_LIMITED
            BrokerReject.EXECUTION_FAILED, BrokerReject.COMMAND_BUILD_FAILED -> AuditEntry.Outcome.ERROR
            BrokerReject.UNKNOWN_ACTION,
            BrokerReject.PERMISSION_DENIED,
            BrokerReject.INVALID_VALUE,
            BrokerReject.SHIZUKU_UNAVAILABLE,
            BrokerReject.NO_REVERT_POINT -> AuditEntry.Outcome.DENIED
        }

        /** Long enough for any `requested -> applied` or `reason: detail`, short enough to bound a row. */
        private const val AUDIT_DETAIL_MAX = 120
    }
}
