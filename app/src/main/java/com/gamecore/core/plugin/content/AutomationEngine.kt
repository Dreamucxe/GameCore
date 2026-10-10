package com.gamecore.core.plugin.content

import com.gamecore.core.plugin.BrokerAction

/**
 * A plugin automation rule and the engine that decides whether it should fire.
 *
 * A rule is "when TRIGGER, if CONDITIONS, do ACTIONS" — the shape every automation system
 * uses — but confined so a plugin cannot turn it into a general program. There are no loops,
 * no rule can invoke another rule, conditions are a flat conjunction of simple comparisons
 * against the approved stat/event feed, and every action is a reference to a declared
 * [BrokerAction] (so the automation ultimately dispatches through the gated broker, with all
 * of its validation, clamping, and confirmation, rather than doing anything itself).
 *
 * The engine here is the *decision* half: given a trigger event and a snapshot of condition
 * variables, it returns the list of [BrokerAction] ids the rule wants to run — bounded in
 * count. Actually running them is the host's job, through the broker, which re-checks
 * permission on every one. The engine deliberately cannot run anything; it can only decide.
 */
data class AutomationRule(
    val id: String,
    val trigger: Trigger,
    val conditions: List<Condition>,
    val actionIds: List<String>,
) {
    enum class Trigger { GAME_LAUNCHED, GAME_CLOSED, SESSION_STARTED, SESSION_ENDED, THERMAL_HIGH, BATTERY_LOW, CHARGER_CONNECTED }

    /** A single comparison: variable OP literal. No boolean operators, no nesting. */
    data class Condition(val variable: String, val op: Op, val value: Double) {
        enum class Op { EQ, NEQ, LT, LTE, GT, GTE }
    }
}

class AutomationEngine(
    private val maxActionsPerRule: Int = 8,
    private val maxConditions: Int = 10,
) {

    sealed interface Validation {
        data object Valid : Validation
        data class Invalid(val reasons: List<String>) : Validation
    }

    /**
     * Static validation of a rule at install time: action ids must resolve to real broker
     * actions (a rule cannot name an action that does not exist, and therefore cannot reach
     * anything the broker does not already gate), and the rule is bounded.
     */
    fun validate(rule: AutomationRule): Validation {
        val reasons = mutableListOf<String>()
        if (rule.actionIds.isEmpty()) reasons += "rule '${rule.id}' has no actions"
        if (rule.actionIds.size > maxActionsPerRule) reasons += "rule '${rule.id}' has more than $maxActionsPerRule actions"
        if (rule.conditions.size > maxConditions) reasons += "rule '${rule.id}' has more than $maxConditions conditions"
        rule.actionIds.forEach { id ->
            if (BrokerAction.fromId(id) == null) reasons += "rule '${rule.id}' names unknown action '$id'"
        }
        return if (reasons.isEmpty()) Validation.Valid else Validation.Invalid(reasons.distinct())
    }

    /**
     * Decide which action ids a rule wants to run for [event], given [vars]. Returns empty if
     * the trigger does not match or any condition is unmet or references an unknown variable
     * (an unmet precondition, not an error — a rule referencing a variable the feed does not
     * provide simply never fires). Never runs anything.
     */
    fun evaluate(
        rule: AutomationRule,
        event: AutomationRule.Trigger,
        vars: Map<String, Double>,
    ): List<String> {
        if (rule.trigger != event) return emptyList()
        val allMet = rule.conditions.all { c ->
            val actual = vars[c.variable] ?: return emptyList()
            when (c.op) {
                AutomationRule.Condition.Op.EQ -> actual == c.value
                AutomationRule.Condition.Op.NEQ -> actual != c.value
                AutomationRule.Condition.Op.LT -> actual < c.value
                AutomationRule.Condition.Op.LTE -> actual <= c.value
                AutomationRule.Condition.Op.GT -> actual > c.value
                AutomationRule.Condition.Op.GTE -> actual >= c.value
            }
        }
        return if (allMet) rule.actionIds.take(maxActionsPerRule) else emptyList()
    }
}
