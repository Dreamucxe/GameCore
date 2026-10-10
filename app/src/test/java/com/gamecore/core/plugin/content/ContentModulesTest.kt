package com.gamecore.core.plugin.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Part G gate: Aim Lab rejection, the bounded stat language, and the automation engine. */
class ContentModulesTest {

    // ---------------------------------------------------------------- Aim Lab

    private val aim = AimLabContentValidator()

    @Test
    fun `a primitive-shape pack validates`() {
        val pack = AimLabContentValidator.PackSpec(
            "Flick Drills",
            listOf(
                AimLabContentValidator.TargetSpec("small sphere", "sphere", "dot.png"),
                AimLabContentValidator.TargetSpec("wide disc", "disc"),
            ),
        )
        assertTrue(aim.validate(pack) is AimLabContentValidator.Result.Valid)
    }

    @Test
    fun `a humanoid target is rejected`() {
        val pack = AimLabContentValidator.PackSpec(
            "bad", listOf(AimLabContentValidator.TargetSpec("enemy silhouette", "box")),
        )
        assertTrue(aim.validate(pack) is AimLabContentValidator.Result.Invalid)
    }

    @Test
    fun `a weapon asset is rejected`() {
        val pack = AimLabContentValidator.PackSpec(
            "bad", listOf(AimLabContentValidator.TargetSpec("target", "sphere", "rifle.png")),
        )
        assertTrue(aim.validate(pack) is AimLabContentValidator.Result.Invalid)
    }

    @Test
    fun `a 3d mesh asset is rejected even with a clean name`() {
        val pack = AimLabContentValidator.PackSpec(
            "bad", listOf(AimLabContentValidator.TargetSpec("blob", "sphere", "model.glb")),
        )
        assertTrue(aim.validate(pack) is AimLabContentValidator.Result.Invalid)
    }

    @Test
    fun `an unknown shape is rejected`() {
        val pack = AimLabContentValidator.PackSpec(
            "bad", listOf(AimLabContentValidator.TargetSpec("thing", "dragon")),
        )
        assertTrue(aim.validate(pack) is AimLabContentValidator.Result.Invalid)
    }

    // ---------------------------------------------------------------- stat expression

    @Test
    fun `arithmetic with variables evaluates`() {
        val c = StatExpression.compile("(kills / deaths) * 100") as StatExpression.CompileResult.Ok
        val r = c.expression.evaluate(mapOf("kills" to 30.0, "deaths" to 10.0)) as StatExpression.Result.Ok
        assertEquals(300.0, r.value, 0.0001)
    }

    @Test
    fun `division by zero is defined, not a crash`() {
        val c = StatExpression.compile("kills / deaths") as StatExpression.CompileResult.Ok
        val r = c.expression.evaluate(mapOf("kills" to 5.0, "deaths" to 0.0)) as StatExpression.Result.Ok
        assertEquals(0.0, r.value, 0.0)
        assertTrue(r.clampedDivByZero)
    }

    @Test
    fun `an unknown variable is an error`() {
        val c = StatExpression.compile("foo + 1") as StatExpression.CompileResult.Ok
        assertTrue(c.expression.evaluate(emptyMap()) is StatExpression.Result.Error)
    }

    @Test
    fun `illegal characters and oversize input are rejected at compile`() {
        assertTrue(StatExpression.compile("kills ; drop") is StatExpression.CompileResult.Error)
        assertTrue(StatExpression.compile("1 + ".repeat(100) + "1") is StatExpression.CompileResult.Error)
    }

    @Test
    fun `there is no loop or assignment syntax to abuse`() {
        // '=' and keywords are not tokens the language accepts.
        assertTrue(StatExpression.compile("x = 1") is StatExpression.CompileResult.Error)
    }

    // ---------------------------------------------------------------- automation

    private val engine = AutomationEngine()

    @Test
    fun `a rule naming an unknown action fails validation`() {
        val rule = AutomationRule("r", AutomationRule.Trigger.GAME_LAUNCHED, emptyList(), listOf("do_evil"))
        assertTrue(engine.validate(rule) is AutomationEngine.Validation.Invalid)
    }

    @Test
    fun `a valid rule fires only when the trigger and conditions match`() {
        val rule = AutomationRule(
            "r", AutomationRule.Trigger.THERMAL_HIGH,
            listOf(AutomationRule.Condition("temp", AutomationRule.Condition.Op.GT, 45.0)),
            listOf("set_peak_refresh_rate"),
        )
        assertTrue(engine.validate(rule) is AutomationEngine.Validation.Valid)
        assertEquals(listOf("set_peak_refresh_rate"), engine.evaluate(rule, AutomationRule.Trigger.THERMAL_HIGH, mapOf("temp" to 50.0)))
        assertTrue(engine.evaluate(rule, AutomationRule.Trigger.THERMAL_HIGH, mapOf("temp" to 40.0)).isEmpty())
        assertTrue(engine.evaluate(rule, AutomationRule.Trigger.GAME_CLOSED, mapOf("temp" to 50.0)).isEmpty())
    }

    @Test
    fun `a rule referencing an absent variable does not fire`() {
        val rule = AutomationRule(
            "r", AutomationRule.Trigger.GAME_LAUNCHED,
            listOf(AutomationRule.Condition("missing", AutomationRule.Condition.Op.GT, 1.0)),
            listOf("set_peak_refresh_rate"),
        )
        assertTrue(engine.evaluate(rule, AutomationRule.Trigger.GAME_LAUNCHED, emptyMap()).isEmpty())
    }
}
