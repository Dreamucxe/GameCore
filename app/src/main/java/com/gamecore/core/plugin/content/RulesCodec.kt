package com.gamecore.core.plugin.content

import org.json.JSONException
import org.json.JSONObject

/**
 * Parses a plugin's rules JSON (the `rules/` section) into validated [AutomationRule]s.
 *
 * A rules file is a small, fixed grammar: a list of rules, each a trigger name, an optional
 * flat list of `variable op value` conditions, and a list of action ids. The codec resolves
 * every enum (trigger, condition operator) and every action id against the real registries —
 * an unknown trigger or an action id that is not a [BrokerAction] is a rejection, not a
 * dropped entry — so a rule that survives parsing is one the [AutomationEngine] can run
 * entirely through the gated broker. There is no expression, no boolean nesting, no way to
 * name an action the broker does not already enumerate.
 */
object RulesCodec {

    enum class Reason { EMPTY, MALFORMED, BAD_TRIGGER, BAD_OPERATOR, BAD_ACTION, TOO_MANY }

    sealed interface Result {
        data class Parsed(val rules: List<AutomationRule>) : Result
        data class Rejected(val reason: Reason, val detail: String = "") : Result
    }

    private const val MAX_RULES = 32

    fun decode(raw: String?): Result {
        if (raw.isNullOrBlank()) return Result.Rejected(Reason.EMPTY)
        val root = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            return Result.Rejected(Reason.MALFORMED, e.message.orEmpty())
        }

        val arr = root.optJSONArray("rules") ?: return Result.Rejected(Reason.MALFORMED, "missing \"rules\" array")
        if (arr.length() > MAX_RULES) return Result.Rejected(Reason.TOO_MANY, "> $MAX_RULES rules")

        val out = ArrayList<AutomationRule>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: return Result.Rejected(Reason.MALFORMED, "rule $i is not an object")
            val id = obj.optString("id", "rule_$i")

            val trigger = runCatching { AutomationRule.Trigger.valueOf(obj.optString("trigger").trim().uppercase()) }
                .getOrNull() ?: return Result.Rejected(Reason.BAD_TRIGGER, "rule $id: \"${obj.optString("trigger")}\"")

            val conditions = ArrayList<AutomationRule.Condition>()
            obj.optJSONArray("conditions")?.let { conds ->
                for (c in 0 until conds.length()) {
                    val co = conds.optJSONObject(c) ?: return Result.Rejected(Reason.MALFORMED, "rule $id: bad condition")
                    val op = runCatching { AutomationRule.Condition.Op.valueOf(co.optString("op").trim().uppercase()) }
                        .getOrNull() ?: return Result.Rejected(Reason.BAD_OPERATOR, "rule $id: \"${co.optString("op")}\"")
                    conditions += AutomationRule.Condition(co.optString("variable"), op, co.optDouble("value", 0.0))
                }
            }

            val actionIds = ArrayList<String>()
            val acts = obj.optJSONArray("actions")
            if (acts != null) {
                for (a in 0 until acts.length()) {
                    val actId = acts.optString(a).trim()
                    if (com.gamecore.core.plugin.BrokerAction.fromId(actId) == null) {
                        return Result.Rejected(Reason.BAD_ACTION, "rule $id: unknown action \"$actId\"")
                    }
                    actionIds += actId
                }
            }

            out += AutomationRule(id, trigger, conditions, actionIds)
        }
        return Result.Parsed(out)
    }
}
