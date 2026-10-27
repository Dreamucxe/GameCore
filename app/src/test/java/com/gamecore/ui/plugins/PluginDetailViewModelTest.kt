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
import com.gamecore.core.plugin.PluginApi
import com.gamecore.core.plugin.PluginApiFacade
import com.gamecore.core.plugin.PluginAuditLog
import com.gamecore.core.plugin.PluginGrant
import com.gamecore.core.plugin.PluginGrantStore
import com.gamecore.core.plugin.PluginManifest
import com.gamecore.core.plugin.PluginNamespace
import com.gamecore.core.plugin.PluginOrigin
import com.gamecore.core.plugin.PluginPermission
import com.gamecore.core.plugin.PluginPermissionGate
import com.gamecore.core.plugin.PluginRateLimiter
import com.gamecore.core.plugin.PluginRegistry
import com.gamecore.core.plugin.PluginSections
import com.gamecore.core.plugin.RequestedPermission
import com.gamecore.core.plugin.ScheduledTheme
import com.gamecore.core.plugin.settings.PluginSettings
import com.gamecore.core.plugin.settings.PluginSettingsSchema
import com.gamecore.core.plugin.settings.SettingDef
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The settings wiring on the consent screen's state holder, tested without a device.
 *
 * The ViewModel reads the schema from the registry, reads each value through the registry's narrow
 * settings door, and writes back only the coerced value the store kept. Those are the behaviours a user
 * can be lied to by, so they are pinned here against a fake registry that delegates to the real
 * [PluginSettings] surface over an in-memory store — so the clamp and truncate are the production ones,
 * not a stub's idea of them. The gate decision is driven by the plugin's own runtime state (its granted
 * permissions), which is why Storage-denied reads fall back to schema defaults.
 *
 * [Dispatchers.setMain] is installed because the ViewModel collects on viewModelScope; there was no
 * prior setMain precedent in this tree, so the rule lives in this file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PluginDetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val id = "com.example.plugin"

    private val schema = PluginSettingsSchema(
        schema = 1,
        definitions = listOf(
            SettingDef.IntDef("hint", defaultValue = 120, min = 60, max = 240),
            SettingDef.BoolDef("haptics", defaultValue = true),
            SettingDef.EnumDef("units", options = listOf("metric", "imperial"), defaultValue = "metric"),
            SettingDef.StringDef("label", defaultValue = "", maxLength = 16),
        ),
    )

    private fun manifest() = PluginManifest(
        id = id,
        name = "Example",
        version = 1,
        versionName = "1.0",
        api = PluginApi.CURRENT,
        minHostVersion = 1,
        developer = "Tester",
        description = "An example.",
        permissions = listOf(RequestedPermission(PluginPermission.STORE_DATA, emptySet())),
        declares = emptySet(),
        changelog = "First release.",
    )

    private fun plugin(storageGranted: Boolean): InstalledPlugin {
        val granted: Map<PluginPermission, Set<String>> =
            if (storageGranted) mapOf(PluginPermission.STORE_DATA to emptySet()) else emptyMap()
        return InstalledPlugin(
            manifest = manifest(),
            sha256 = "a".repeat(64),
            origin = PluginOrigin.SIDELOADED,
            enabled = true,
            grant = PluginGrant(id, granted),
        )
    }

    private fun viewModel(storageGranted: Boolean = true): PluginDetailViewModel =
        PluginDetailViewModel(
            FakeRegistry(plugin(storageGranted), schema),
            SavedStateHandle(mapOf(Destination.ARG_PLUGIN_ID to id)),
        )

    @Test
    fun `an absent setting loads its schema default`() = runTest(dispatcher) {
        val vm = viewModel(storageGranted = true)
        advanceUntilIdle()
        val state = vm.state.value
        assertEquals(schema, state.settingsSchema)
        assertTrue(state.settingsEditable)
        assertEquals(SettingValue.IntValue(120), state.settingValues["hint"])
        assertEquals(SettingValue.BoolValue(true), state.settingValues["haptics"])
    }

    @Test
    fun `setting an out-of-range number stores the clamped value`() = runTest(dispatcher) {
        val vm = viewModel(storageGranted = true)
        advanceUntilIdle()
        vm.onSetSetting("hint", SettingValue.IntValue(9999))
        advanceUntilIdle()
        assertEquals(SettingValue.IntValue(240), vm.state.value.settingValues["hint"])
    }

    @Test
    fun `setting an over-long string stores the truncated value`() = runTest(dispatcher) {
        val vm = viewModel(storageGranted = true)
        advanceUntilIdle()
        vm.onSetSetting("label", SettingValue.StringValue("x".repeat(50)))
        advanceUntilIdle()
        assertEquals(SettingValue.StringValue("x".repeat(16)), vm.state.value.settingValues["label"])
    }

    @Test
    fun `setting an unknown id changes nothing`() = runTest(dispatcher) {
        val vm = viewModel(storageGranted = true)
        advanceUntilIdle()
        val before = vm.state.value.settingValues
        vm.onSetSetting("nope", SettingValue.IntValue(1))
        advanceUntilIdle()
        assertEquals(before, vm.state.value.settingValues)
        assertNull(vm.state.value.settingValues["nope"])
    }

    @Test
    fun `without Storage the values are schema defaults and the controls are not editable`() =
        runTest(dispatcher) {
            val vm = viewModel(storageGranted = false)
            advanceUntilIdle()
            val state = vm.state.value
            assertFalse(state.settingsEditable)
            // Reads are denied without STORE_DATA, so the definition's default stands in.
            assertEquals(SettingValue.IntValue(120), state.settingValues["hint"])
            assertEquals(SettingValue.BoolValue(true), state.settingValues["haptics"])
        }

    @Test
    fun `without Storage a write is refused and the value is unchanged`() = runTest(dispatcher) {
        val vm = viewModel(storageGranted = false)
        advanceUntilIdle()
        vm.onSetSetting("hint", SettingValue.IntValue(100))
        advanceUntilIdle()
        assertEquals(SettingValue.IntValue(120), vm.state.value.settingValues["hint"])
    }

    // ------------------------------------------------------------------ fakes

    private class FakeRegistry(
        private val plugin: InstalledPlugin,
        private val schema: PluginSettingsSchema,
    ) : PluginRegistry {
        override val installed = MutableStateFlow(listOf(plugin))
        override val activeTheme = MutableStateFlow<ActiveTheme?>(null)
        override val activeIcon = MutableStateFlow<ActiveIcon?>(null)
        override val iconOffers = MutableStateFlow<List<ActiveIcon>>(emptyList())
        override val activeOverlayModules = MutableStateFlow<List<ActiveOverlayModule>>(emptyList())
        override val activeDashboardWidgets = MutableStateFlow<List<ActiveDashboardWidget>>(emptyList())
        override val activeAutomations = MutableStateFlow<List<ActivePluginAutomation>>(emptyList())
        override val killSwitchOn = MutableStateFlow(false)
        override val isLoaded = MutableStateFlow(true)
        override fun grantedScopesFor(pluginId: String): Set<DataScope> = emptySet()

        private val store = FakeGrantStore()
        private val settings = PluginSettings(
            PluginApiFacade(
                PluginPermissionGate(),
                PluginRateLimiter(now = { 0L }),
                PluginAuditLog(),
                now = { 0L },
            ),
            store,
        )

        private fun stateFor(pluginId: String) =
            installed.value.firstOrNull { it.id == pluginId }?.runtimeState(killSwitchOn.value)

        override suspend fun readPluginSetting(
            pluginId: String,
            schema: PluginSettingsSchema,
            id: String,
        ): ApiResult<SettingValue> {
            val state = stateFor(pluginId) ?: return ApiResult.Error("unknown plugin")
            return settings.get(state, schema, id)
        }

        override suspend fun setPluginSetting(
            pluginId: String,
            schema: PluginSettingsSchema,
            id: String,
            value: SettingValue,
        ): ApiResult<SettingValue> {
            val state = stateFor(pluginId) ?: return ApiResult.Error("unknown plugin")
            return settings.set(state, schema, id, value)
        }

        override suspend fun sectionsOf(pluginId: String): PluginSections? =
            if (pluginId == plugin.id) PluginSections(settings = schema) else null

        override suspend fun refresh() {}
        override suspend fun install(bytes: ByteArray, origin: PluginOrigin): InstallOutcome =
            InstallOutcome.StorageFailed("unused")
        override suspend fun remove(pluginId: String): Boolean = false
        override suspend fun setEnabled(pluginId: String, enabled: Boolean) {}
        override suspend fun grant(pluginId: String, grant: PluginGrant) {}
        override suspend fun setActiveTheme(pluginId: String?) {}
        override suspend fun setScheduledTheme(override: ScheduledTheme?) {}
        override suspend fun resetToDefaultTheme() {}
        override suspend fun setActiveIcon(pluginId: String?) {}
        override suspend fun resetToPluginIcon() {}
        override suspend fun iconArtwork(pluginId: String): ByteArray? = null
        override suspend fun setKillSwitch(on: Boolean) {}
        override suspend fun clearSuspension(pluginId: String) {}
        override suspend fun automationHistory(
            pluginId: String,
            limit: Int,
        ): ApiResult<List<AutomationHistoryRecord>> = ApiResult.Ok(emptyList())
        override suspend fun clearAutomationHistory(pluginId: String): ApiResult<Unit> =
            ApiResult.Ok(Unit)
    }

    private class FakeGrantStore : PluginGrantStore {
        private val values = HashMap<String, String>()
        override fun readValue(qualifiedKey: String): String? = values[qualifiedKey]
        override fun writeValue(qualifiedKey: String, value: String?) {
            if (value == null) values.remove(qualifiedKey) else values[qualifiedKey] = value
        }
        override fun clearNamespace(pluginId: String) {
            values.keys.filter { it.startsWith(PluginNamespace.of(pluginId)) }.toList()
                .forEach { values.remove(it) }
        }
        override fun grantFor(pluginId: String): PluginGrant = PluginGrant.none(pluginId)
        override fun saveGrant(grant: PluginGrant) {}
        override fun clearGrant(pluginId: String) {}
        override fun isEnabled(pluginId: String): Boolean = true
        override fun setEnabled(pluginId: String, enabled: Boolean) {}
        override fun isSuspended(pluginId: String): Boolean = false
        override fun suspend(pluginId: String, detail: String) {}
        override fun clearSuspension(pluginId: String) {}
        override fun isSystemKillSwitchOn(): Boolean = false
        override fun setSystemKillSwitch(on: Boolean) {}
    }
}
