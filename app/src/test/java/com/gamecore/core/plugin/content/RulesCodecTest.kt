package com.gamecore.core.plugin.content

import com.gamecore.core.plugin.BrokerAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the rules grammar: a plugin's automation file names triggers, operators and actions that
 * GameCore already enumerates, or it does not load at all.
 *
 * [RulesCodec] is the only way plugin-authored automation enters the host, and the one thing it must
 * never do is pass an action id through. If it did, a rule naming `"shell"` or `"settings_put"` would
 * reach the dispatcher as a string the broker had never vetted, and the closed [BrokerAction] registry
 * — the property the whole device-facing surface rests on — would be closed in name only. Every id is
 * resolved through [BrokerAction.fromId] at parse time, so a rule that survives decoding cannot reach
 * anything the broker does not already gate.
 *
 * The rest of the file pins the contract every codec in the platform honours: a refusal is a typed
 * [RulesCodec.Reason] the developer tools can show verbatim, never a dropped entry and never a throw.
 * A dropped entry is the dangerous failure of the two — the plugin would appear to work while doing
 * less than its author wrote, or (for a mis-read trigger) something else entirely.
 */
class RulesCodecTest {

    private fun rejection(raw: String?): RulesCodec.Reason =
        (RulesCodec.decode(raw) as RulesCodec.Result.Rejected).reason

    private fun parsed(raw: String): List<AutomationRule> =
        (RulesCodec.decode(raw) as RulesCodec.Result.Parsed).rules

    private fun document(ruleCount: Int): String {
        val rules = (0 until ruleCount).joinToString(",") { i ->
            """{"id":"r$i","trigger":"game_launched","actions":["${BrokerAction.SET_LOW_POWER.id}"]}"""
        }
        return """{"rules":[$rules]}"""
    }

    // ---------------------------------------------------------------- the happy path

    @Test
    fun `a well-formed document decodes with its trigger, conditions and actions intact`() {
        val rules = parsed(
            """
            {"rules":[
              {"id":"cool_down","trigger":"thermal_high",
               "conditions":[{"variable":"battery_percent","op":"lt","value":20}],
               "actions":["${BrokerAction.SET_LOW_POWER.id}"]}
            ]}
            """.trimIndent(),
        )
        val rule = rules.single()
        assertEquals("cool_down", rule.id)
        assertEquals(AutomationRule.Trigger.THERMAL_HIGH, rule.trigger)
        assertEquals("battery_percent", rule.conditions.single().variable)
        assertEquals(AutomationRule.Condition.Op.LT, rule.conditions.single().op)
        assertEquals(20.0, rule.conditions.single().value, 0.0)
        assertEquals(listOf(BrokerAction.SET_LOW_POWER.id), rule.actionIds)
    }

    @Test
    fun `trigger and operator names are case-insensitive but still resolved against the enum`() {
        val rule = parsed(
            """{"rules":[{"id":"r","trigger":" Session_Ended ",
               "conditions":[{"variable":"fps","op":"GTE","value":60}],
               "actions":["${BrokerAction.SET_MIN_REFRESH_RATE.id}"]}]}""",
        ).single()
        assertEquals(AutomationRule.Trigger.SESSION_ENDED, rule.trigger)
        assertEquals(AutomationRule.Condition.Op.GTE, rule.conditions.single().op)
    }

    // ---------------------------------------------------------------- the closed registries

    @Test
    fun `an unknown trigger is rejected, never dropped`() {
        assertEquals(
            RulesCodec.Reason.BAD_TRIGGER,
            rejection("""{"rules":[{"trigger":"on_boot","actions":["${BrokerAction.SET_LOW_POWER.id}"]}]}"""),
        )
        // A missing trigger takes the same path: there is no default event to fall back to, and a
        // rule that fired on "whatever came first" would be worse than one that refused to load.
        assertEquals(
            RulesCodec.Reason.BAD_TRIGGER,
            rejection("""{"rules":[{"actions":["${BrokerAction.SET_LOW_POWER.id}"]}]}"""),
        )
    }

    @Test
    fun `an unknown condition operator is rejected as BAD_OPERATOR`() {
        assertEquals(
            RulesCodec.Reason.BAD_OPERATOR,
            rejection(
                """{"rules":[{"trigger":"game_launched",
                   "conditions":[{"variable":"fps","op":"matches","value":1}],
                   "actions":["${BrokerAction.SET_LOW_POWER.id}"]}]}""",
            ),
        )
        // The operator set is a flat conjunction of comparisons; there is no boolean connective to
        // name, so "and" is as unknown as "matches".
        assertEquals(
            RulesCodec.Reason.BAD_OPERATOR,
            rejection(
                """{"rules":[{"trigger":"game_launched",
                   "conditions":[{"variable":"fps","op":"and","value":1}],
                   "actions":["${BrokerAction.SET_LOW_POWER.id}"]}]}""",
            ),
        )
    }

    @Test
    fun `an action id that is not a broker action is rejected as BAD_ACTION`() {
        assertEquals(
            RulesCodec.Reason.BAD_ACTION,
            rejection("""{"rules":[{"trigger":"game_launched","actions":["set_cpu_governor"]}]}"""),
        )
    }

    @Test
    fun `a reserved-sounding action id is refused exactly like any other unknown id`() {
        // This is the test that says there is no action-id passthrough. These names read like shell
        // verbs, and the codec does not special-case them — it simply cannot resolve them, which is
        // the stronger property. If any one of them ever parsed, the host would be handed an id the
        // broker had never seen, and every guarantee downstream of BrokerAction would be void.
        listOf(
            "shell", "sh", "exec", "install_package", "pm_install", "settings_put",
            "settings put system peak_refresh_rate 1", "am broadcast", "../set_low_power",
        ).forEach { hostile ->
            assertEquals(
                "\"$hostile\" must not resolve to an action",
                RulesCodec.Reason.BAD_ACTION,
                rejection("""{"rules":[{"trigger":"game_launched","actions":["$hostile"]}]}"""),
            )
        }
    }

    @Test
    fun `every action the broker enumerates is nameable, and one id off the end fails the document`() {
        BrokerAction.entries.forEach { action ->
            val rule = parsed(
                """{"rules":[{"id":"r","trigger":"game_launched","actions":["${action.id}"]}]}""",
            ).single()
            assertEquals(action, BrokerAction.fromId(rule.actionIds.single()))
        }

        // A document whose every id but one is real is still refused whole. Partial acceptance would
        // ship an automation that silently does less than the author wrote.
        val allIds = BrokerAction.entries.joinToString(",") { "\"" + it.id + "\"" }
        assertEquals(
            RulesCodec.Reason.BAD_ACTION,
            rejection("""{"rules":[{"trigger":"game_launched","actions":[$allIds,"set_everything"]}]}"""),
        )
    }

    @Test
    fun `an action id resolves case-insensitively but is stored as the plugin wrote it`() {
        // Dispatch is unaffected — BrokerAction.fromId trims and lowercases again on the way to the
        // broker — but the stored string is the plugin's, not the registry's canonical id. Anything
        // that compared actionIds to BrokerAction.id by string equality would miss, so the behaviour
        // is pinned here rather than assumed by the next reader.
        val rule = parsed(
            """{"rules":[{"trigger":"game_launched","actions":["  SET_LOW_POWER  "]}]}""",
        ).single()
        assertEquals("SET_LOW_POWER", rule.actionIds.single())
        assertEquals(BrokerAction.SET_LOW_POWER, BrokerAction.fromId(rule.actionIds.single()))
    }

    // ---------------------------------------------------------------- bounds

    @Test
    fun `the rule ceiling admits the last allowed rule and refuses the next`() {
        // The ceiling is private, so the boundary is pinned by behaviour: 32 rules parse, 33 do not.
        // An unbounded rules file is a parse-time denial of service against the install flow.
        assertEquals(32, parsed(document(32)).size)
        assertEquals(RulesCodec.Reason.TOO_MANY, rejection(document(33)))
    }

    @Test
    fun `a rule's own bounds belong to the engine, not the codec`() {
        // RulesCodec owns the grammar and the per-document rule ceiling; AutomationEngine owns "a
        // rule must do something", "at most 8 actions" and "at most 10 conditions". Pinning the split
        // stops a future reader assuming either side checks both — and keeps the threat model honest
        // about which file enforces which ceiling.
        val engine = AutomationEngine()

        val noActions = parsed("""{"rules":[{"id":"idle","trigger":"game_closed","actions":[]}]}""").single()
        assertTrue(noActions.actionIds.isEmpty())
        assertTrue(engine.validate(noActions) is AutomationEngine.Validation.Invalid)

        // An absent "actions" key takes the same path as an empty one.
        val noActionsKey = parsed("""{"rules":[{"id":"idle","trigger":"game_closed"}]}""").single()
        assertTrue(engine.validate(noActionsKey) is AutomationEngine.Validation.Invalid)

        val quoted = "\"" + BrokerAction.SET_LOW_POWER.id + "\""
        val nineActions = (0 until 9).joinToString(",") { quoted }
        val elevenConditions = (0 until 11).joinToString(",") { """{"variable":"v$it","op":"gt","value":0}""" }
        val oversized = parsed(
            """{"rules":[{"id":"big","trigger":"game_launched",
               "conditions":[$elevenConditions],"actions":[$nineActions]}]}""",
        ).single()
        assertEquals(9, oversized.actionIds.size)
        assertEquals(11, oversized.conditions.size)
        assertTrue(engine.validate(oversized) is AutomationEngine.Validation.Invalid)
    }

    // ---------------------------------------------------------------- malformed input never throws

    @Test
    fun `null, empty and blank input are rejected as EMPTY`() {
        assertEquals(RulesCodec.Reason.EMPTY, rejection(null))
        assertEquals(RulesCodec.Reason.EMPTY, rejection(""))
        assertEquals(RulesCodec.Reason.EMPTY, rejection("   \n "))
    }

    @Test
    fun `malformed json is a typed rejection, not a throw`() {
        assertEquals(RulesCodec.Reason.MALFORMED, rejection("{"))
        assertEquals(RulesCodec.Reason.MALFORMED, rejection("not json at all"))
        assertEquals(RulesCodec.Reason.MALFORMED, rejection("""{"rules":{"not":"an array"}}"""))
        // A rule entry that is not an object, and a condition that is not an object.
        assertEquals(RulesCodec.Reason.MALFORMED, rejection("""{"rules":["game_launched"]}"""))
        assertEquals(
            RulesCodec.Reason.MALFORMED,
            rejection(
                """{"rules":[{"trigger":"game_launched","conditions":["fps > 1"],
                   "actions":["${BrokerAction.SET_LOW_POWER.id}"]}]}""",
            ),
        )
    }

    @Test
    fun `a document with no rules array is malformed rather than an empty rule set`() {
        // Reading a typo'd key as "no rules" would ship a plugin whose automations never run and
        // whose author has no way to find out. The refusal is what tells them.
        assertEquals(RulesCodec.Reason.MALFORMED, rejection("""{"ruels":[]}"""))
        assertEquals(RulesCodec.Reason.MALFORMED, rejection("{}"))
    }
}
