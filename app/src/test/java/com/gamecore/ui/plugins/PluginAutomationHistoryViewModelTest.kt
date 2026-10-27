package com.gamecore.ui.plugins

import androidx.lifecycle.SavedStateHandle
import com.gamecore.core.plugin.ActiveDashboardWidget
import com.gamecore.core.plugin.ActiveIcon
import com.gamecore.core.plugin.ActiveOverlayModule
import com.gamecore.core.plugin.ActivePluginAutomation
import com.gamecore.core.plugin.ActiveTheme
import com.gamecore.core.plugin.ApiResult
import com.gamecore.core.plugin.AutomationHistoryRecord
import com.gamecore.core.plugin.DataScope
import com.gamecore.core.plugin.InstallOutcome
import com.gamecore.core.plugin.InstalledPlugin
import com.gamecore.core.plugin.PluginGrant
import com.gamecore.core.plugin.PluginOrigin
import com.gamecore.core.plugin.PluginRegistry
import com.gamecore.core.plugin.PluginSections
import com.gamecore.core.plugin.ScheduledTheme
import com.gamecore.core.plugin.settings.PluginSettingsSchema
import com.gamecore.core.plugin.settings.SettingValue
import com.gamecore.ui.Destination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The one behaviour the automation-history holder owns, pinned without a device: it tells "not allowed"
 * apart from "nothing has run" by the shape of the registry's answer. A granted read, empty or not, is
 * [LoadPhase.Ready]; a refused read is [LoadPhase.Denied] with the records cleared so no stale list sits
 * under the "not allowed" panel. The view controls mutate state in memory and never re-read.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PluginAutomationHistoryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val id = "com.example.plugin"

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun handle() = SavedStateHandle(mapOf(Destination.ARG_PLUGIN_ID to id))

    private fun viewModel(granted: Boolean, records: List<AutomationHistoryRecord> = emptyList()) =
        PluginAutomationHistoryViewModel(FakeRegistry(granted, records), handle())

    private fun rec(
        id: Long,
        timestampMillis: Long = id,
        outcome: String = "ALLOWED",
        operation: String = "op",
    ) = AutomationHistoryRecord(
        id = id,
        timestampMillis = timestampMillis,
        pluginId = "com.example.plugin",
        operation = operation,
        outcome = outcome,
        detail = "",
        durationMillis = null,
    )
    @Test
    fun `a granted read with records settles on Ready carrying them`() = runTest(dispatcher) {
        val vm = viewModel(granted = true, records = listOf(rec(id = 1), rec(id = 2)))
        advanceUntilIdle()
        val state = vm.state.value
        assertEquals(LoadPhase.Ready, state.phase)
        assertEquals(2, state.totalCount)
        assertTrue(state.hasRecords)
    }

    @Test
    fun `a granted read with no records is an empty Ready, not Denied`() = runTest(dispatcher) {
        val vm = viewModel(granted = true)
        advanceUntilIdle()
        val state = vm.state.value
        // The distinction the whole screen hinges on: scope held, nothing has run.
        assertEquals(LoadPhase.Ready, state.phase)
        assertFalse(state.hasRecords)
    }

    @Test
    fun `a refused read is Denied with no records, never an empty Ready`() = runTest(dispatcher) {
        val vm = viewModel(granted = false, records = listOf(rec(id = 1)))
        advanceUntilIdle()
        val state = vm.state.value
        assertEquals(LoadPhase.Denied, state.phase)
        // A stale list must not sit under the "not allowed" panel.
        assertFalse(state.hasRecords)
    }
    @Test
    fun `confirming a clear reloads to an empty Ready`() = runTest(dispatcher) {
        val vm = viewModel(granted = true, records = listOf(rec(id = 1), rec(id = 2)))
        advanceUntilIdle()
        assertTrue(vm.state.value.hasRecords)
        vm.onConfirmClear()
        advanceUntilIdle()
        val state = vm.state.value
        assertEquals(LoadPhase.Ready, state.phase)
        assertFalse(state.hasRecords)
    }

    @Test
    fun `the view controls change state and re-derive the rows without re-reading`() =
        runTest(dispatcher) {
            val vm = viewModel(
                granted = true,
                records = listOf(
                    rec(id = 1, timestampMillis = 100, outcome = "ALLOWED", operation = "startOverlay"),
                    rec(id = 2, timestampMillis = 200, outcome = "DENIED", operation = "startOverlay"),
                    rec(id = 3, timestampMillis = 300, outcome = "ALLOWED", operation = "stopOverlay"),
                ),
            )
            advanceUntilIdle()

            vm.onSearchChange("start")
            assertEquals("start", vm.state.value.query)

            vm.onOutcomeFilterChange("ALLOWED")
            assertEquals("ALLOWED", vm.state.value.outcomeFilter)
            // ALLOWED narrows to {1,3}; "start" narrows to {1}; so only id 1 is visible.
            assertEquals(listOf(1L), visibleRecords(vm.state.value).map { it.id })

            val before = vm.state.value.newestFirst
            vm.onToggleSort()
            assertEquals(!before, vm.state.value.newestFirst)
        }
    private class FakeRegistry(
        private val granted: Boolean,
        initial: List<AutomationHistoryRecord>,
    ) : PluginRegistry {
        private val records = initial.toMutableList()

        override val installed = MutableStateFlow<List<InstalledPlugin>>(emptyList())
        override val activeTheme = MutableStateFlow<ActiveTheme?>(null)
        override val activeIcon = MutableStateFlow<ActiveIcon?>(null)
        override val iconOffers = MutableStateFlow<List<ActiveIcon>>(emptyList())
        override val activeOverlayModules = MutableStateFlow<List<ActiveOverlayModule>>(emptyList())
        override val activeDashboardWidgets = MutableStateFlow<List<ActiveDashboardWidget>>(emptyList())
        override val activeAutomations = MutableStateFlow<List<ActivePluginAutomation>>(emptyList())
        override val killSwitchOn = MutableStateFlow(false)
        override val isLoaded = MutableStateFlow(true)

        // The VM gates on the shape of automationHistory's result, not on this, so it stays minimal.
        override fun grantedScopesFor(pluginId: String): Set<DataScope> = emptySet()

        override suspend fun automationHistory(
            pluginId: String,
            limit: Int,
        ): ApiResult<List<AutomationHistoryRecord>> =
            if (granted) ApiResult.Ok(records.sortedByDescending { it.timestampMillis }.take(limit))
            else ApiResult.Error("automation history scope not held")

        override suspend fun clearAutomationHistory(pluginId: String): ApiResult<Unit> =
            if (granted) {
                records.clear()
                ApiResult.Ok(Unit)
            } else {
                ApiResult.Error("automation history scope not held")
            }

        override suspend fun sectionsOf(pluginId: String): PluginSections? = null
        override suspend fun readPluginSetting(pluginId: String, schema: PluginSettingsSchema, id: String): ApiResult<SettingValue> = ApiResult.Error("unused")
        override suspend fun setPluginSetting(pluginId: String, schema: PluginSettingsSchema, id: String, value: SettingValue): ApiResult<SettingValue> = ApiResult.Error("unused")
        override suspend fun refresh() {}
        override suspend fun setActiveIcon(pluginId: String?) {}
        override suspend fun resetToPluginIcon() {}
        override suspend fun iconArtwork(pluginId: String): ByteArray? = null
        override suspend fun install(bytes: ByteArray, origin: PluginOrigin): InstallOutcome = InstallOutcome.StorageFailed("unused")
        override suspend fun remove(pluginId: String): Boolean = false
        override suspend fun setEnabled(pluginId: String, enabled: Boolean) {}
        override suspend fun grant(pluginId: String, grant: PluginGrant) {}
        override suspend fun setActiveTheme(pluginId: String?) {}
        override suspend fun setScheduledTheme(override: ScheduledTheme?) {}
        override suspend fun resetToDefaultTheme() {}
        override suspend fun setKillSwitch(on: Boolean) {}
        override suspend fun clearSuspension(pluginId: String) {}
    }

}
