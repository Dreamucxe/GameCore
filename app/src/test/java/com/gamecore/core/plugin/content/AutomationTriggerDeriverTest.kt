package com.gamecore.core.plugin.content

import com.gamecore.core.common.AccessLevel
import com.gamecore.core.common.DataSource
import com.gamecore.core.common.Observed
import com.gamecore.core.model.BatteryReading
import com.gamecore.core.model.CpuReading
import com.gamecore.core.model.DisplayReading
import com.gamecore.core.model.MemoryReading
import com.gamecore.core.model.NetworkReading
import com.gamecore.core.model.PerformanceSnapshot
import com.gamecore.core.model.StorageReading
import com.gamecore.core.model.ThermalReading
import com.gamecore.core.model.ThermalStatus
import com.gamecore.domain.gaming.GamingState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The trigger-edge policy. These prove each of the seven triggers can be produced from a pair of
 * consecutive samples, that a steady state produces none, and that a first observation (null
 * predecessor) never fires a device edge — the properties that keep an automation firing on a
 * transition rather than every tick.
 */
class AutomationTriggerDeriverTest {

    // ------------------------------------------------------------------ lifecycle

    @Test
    fun `idle to playing launches the game and starts the session`() {
        val triggers = AutomationTriggerDeriver.lifecycleTriggers(GamingState.IDLE, playing())
        assertEquals(
            listOf(
                AutomationRule.Trigger.GAME_LAUNCHED,
                AutomationRule.Trigger.SESSION_STARTED,
            ),
            triggers,
        )
    }

    @Test
    fun `a null predecessor is treated as idle, so the first playing state is a launch`() {
        val triggers = AutomationTriggerDeriver.lifecycleTriggers(null, playing())
        assertEquals(
            listOf(
                AutomationRule.Trigger.GAME_LAUNCHED,
                AutomationRule.Trigger.SESSION_STARTED,
            ),
            triggers,
        )
    }

    @Test
    fun `playing to idle closes the game and ends the session`() {
        val triggers = AutomationTriggerDeriver.lifecycleTriggers(playing(), GamingState.IDLE)
        assertEquals(
            listOf(
                AutomationRule.Trigger.GAME_CLOSED,
                AutomationRule.Trigger.SESSION_ENDED,
            ),
            triggers,
        )
    }

    @Test
    fun `a steady playing state produces no lifecycle trigger`() {
        val triggers = AutomationTriggerDeriver.lifecycleTriggers(playing(), playing())
        assertEquals(emptyList<AutomationRule.Trigger>(), triggers)
    }

    @Test
    fun `idle to idle produces no lifecycle trigger`() {
        val triggers = AutomationTriggerDeriver.lifecycleTriggers(GamingState.IDLE, GamingState.IDLE)
        assertEquals(emptyList<AutomationRule.Trigger>(), triggers)
    }

    @Test
    fun `session edges track the session clock independently of the tracked package`() {
        // The session clock (startedAtMillis) is read on its own field: with the package held
        // constant, a 0 -> set step is a session start and a set -> 0 step a session end, with no
        // game edge. This locks the independent-field derivation even though today's GamingState
        // moves the two together.
        val gameNoClock = GamingState(playing = "com.game", startedAtMillis = 0L)
        val gameWithClock = GamingState(playing = "com.game", startedAtMillis = 100L)

        assertEquals(
            listOf(AutomationRule.Trigger.SESSION_STARTED),
            AutomationTriggerDeriver.lifecycleTriggers(gameNoClock, gameWithClock),
        )
        assertEquals(
            listOf(AutomationRule.Trigger.SESSION_ENDED),
            AutomationTriggerDeriver.lifecycleTriggers(gameWithClock, gameNoClock),
        )
    }

    // ------------------------------------------------------------------ device

    @Test
    fun `thermal rising into the high band fires THERMAL_HIGH once`() {
        val cool = snapshot(thermalStatus = status(ThermalStatus.MODERATE))
        val hot = snapshot(thermalStatus = status(ThermalStatus.SEVERE))
        assertEquals(
            listOf(AutomationRule.Trigger.THERMAL_HIGH),
            AutomationTriggerDeriver.deviceTriggers(cool, hot),
        )
        // Staying hot does not re-fire.
        assertEquals(
            emptyList<AutomationRule.Trigger>(),
            AutomationTriggerDeriver.deviceTriggers(hot, hot),
        )
    }

    @Test
    fun `battery falling to the low mark fires BATTERY_LOW once`() {
        val above = snapshot(battery = battery(level = 21))
        val atMark = snapshot(battery = battery(level = 20))
        val below = snapshot(battery = battery(level = 19))
        assertEquals(
            listOf(AutomationRule.Trigger.BATTERY_LOW),
            AutomationTriggerDeriver.deviceTriggers(above, atMark),
        )
        // Already low: no second edge.
        assertEquals(
            emptyList<AutomationRule.Trigger>(),
            AutomationTriggerDeriver.deviceTriggers(atMark, below),
        )
    }

    @Test
    fun `plugging in fires CHARGER_CONNECTED`() {
        val unplugged = snapshot(battery = battery(level = 50, charging = false))
        val plugged = snapshot(battery = battery(level = 50, charging = true))
        assertEquals(
            listOf(AutomationRule.Trigger.CHARGER_CONNECTED),
            AutomationTriggerDeriver.deviceTriggers(unplugged, plugged),
        )
    }

    @Test
    fun `a first snapshot with no predecessor fires no device trigger`() {
        // Already hot, already low, already charging — but there is no baseline to have crossed.
        val extreme = snapshot(
            thermalStatus = status(ThermalStatus.CRITICAL),
            battery = battery(level = 5, charging = true),
        )
        assertEquals(
            emptyList<AutomationRule.Trigger>(),
            AutomationTriggerDeriver.deviceTriggers(null, extreme),
        )
    }

    @Test
    fun `a steady low-and-hot state produces no device trigger`() {
        val s = snapshot(
            thermalStatus = status(ThermalStatus.SEVERE),
            battery = battery(level = 10),
        )
        assertEquals(emptyList<AutomationRule.Trigger>(), AutomationTriggerDeriver.deviceTriggers(s, s))
    }

    // ------------------------------------------------------------------ fixtures

    private fun playing() = GamingState(playing = "com.game", gameLabel = "Game", startedAtMillis = 1_000L)

    private fun status(value: ThermalStatus): Observed<ThermalStatus> =
        Observed.of(value, DataSource.POWER_MANAGER)

    private fun battery(level: Int, charging: Boolean = false): BatteryReading =
        BatteryReading.EMPTY.copy(levelPercent = level, isCharging = charging)

    private fun snapshot(
        thermalStatus: Observed<ThermalStatus> = Observed.notPresent("no status"),
        battery: BatteryReading = BatteryReading.EMPTY,
    ): PerformanceSnapshot = PerformanceSnapshot(
        capturedAtMillis = 0L,
        cpu = CpuReading.unavailable(coreCount = 8, absence = Observed.notPresent("x")),
        memory = MemoryReading.EMPTY,
        battery = battery,
        thermal = ThermalReading.unavailable("x", statusSupported = true).copy(status = thermalStatus),
        storage = StorageReading.EMPTY,
        display = DisplayReading.unavailable("x"),
        network = NetworkReading.DISCONNECTED,
        frameRate = Observed.notPresent("x"),
        latency = Observed.notPresent("x"),
        gpu = Observed.notPresent("x"),
        accessLevel = AccessLevel.NORMAL,
    )
}
