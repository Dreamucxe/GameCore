package com.gamecore.ui.dashboard

import com.gamecore.aimlab.engine.Difficulty
import com.gamecore.aimlab.engine.SessionSummary
import com.gamecore.aimlab.engine.TrainingMode
import com.gamecore.core.model.BuiltinDashboardComponent
import com.gamecore.core.model.DashboardItem
import com.gamecore.core.model.DashboardLayout
import com.gamecore.core.plugin.ActiveDashboardWidget
import com.gamecore.core.plugin.dashboard.PluginDashboardWidget
import com.gamecore.core.plugin.dashboard.WidgetSize
import com.gamecore.core.plugin.ui.ComponentNode
import com.gamecore.core.plugin.ui.ComponentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure core behind the Dashboard Composer, pinned on the JVM with hand-built snapshots and no
 * Compose, Android, coroutines or a registry fake — the whole reason [resolveItem], [aimAccuracy],
 * [addablePluginWidgets], [addableBuiltins] and [reconcile] are top-level functions over plain values.
 *
 * The invariant that earns most of this file is the honest-boundary rule [resolveItem] enforces: a
 * placed plugin widget is called unavailable only for a cause the host can prove, and the six causes
 * are weighed in a fixed worst-first order so the reason returned is the first that is true. A widget
 * labelled "disabled" when the real cause was the kill switch, or "not provided" when the plugin was
 * actually suspended, is a quiet lie on a screen the user is trying to reason about — invisible on a
 * screenshot and one plain assertion away from being caught, so every reason and its precedence gets a
 * case. The rest pins the two places a figure could be fabricated ([aimAccuracy]'s null-not-zero empty
 * result and its 2D/invalid filter) and the add-list and capacity bookkeeping [reconcile] owns.
 */
class DashboardComposerStateTest {

    // ------------------------------------------------------------------------------ fixtures

    private fun node() = ComponentNode(ComponentType.TEXT, emptyMap())

    private fun widget(pluginId: String, pluginName: String, widgetId: String, title: String) =
        ActiveDashboardWidget(
            pluginId = pluginId,
            pluginName = pluginName,
            widget = PluginDashboardWidget(id = widgetId, title = title, size = WidgetSize.MEDIUM, content = node()),
        )

    private val pluginItem = DashboardItem.pluginWidget(pluginId = "com.example.widget", widgetId = "w1", position = 0)
    private val widgetKey = PluginAvailabilitySnapshot.widgetKey("com.example.widget", "w1")

    private fun presence(
        name: String = "Presence Name",
        enabled: Boolean = true,
        suspended: Boolean = false,
        runnable: Boolean = true,
    ) = PluginPresence(name = name, enabled = enabled, suspended = suspended, runnable = runnable)

    private fun activeFor(pluginName: String = "Active Plugin Name", title: String = "Widget Title") =
        widget(pluginId = "com.example.widget", pluginName = pluginName, widgetId = "w1", title = title)

    private fun snapshot(
        killSwitchOn: Boolean = false,
        plugins: Map<String, PluginPresence> = emptyMap(),
        activeWidgets: Map<String, ActiveDashboardWidget> = emptyMap(),
    ) = PluginAvailabilitySnapshot(killSwitchOn = killSwitchOn, plugins = plugins, activeWidgets = activeWidgets)

    private fun reasonOf(item: DashboardItem, snapshot: PluginAvailabilitySnapshot): ItemUnavailableReason {
        val resolved = resolveItem(item, snapshot)
        assertTrue("expected Unavailable, got ${resolved.content}", resolved.content is ResolvedContent.Unavailable)
        return (resolved.content as ResolvedContent.Unavailable).reason
    }

    private fun run(
        startedAtMillis: Long,
        hits: Int,
        shots: Int,
        scoringVersion: Int = SessionSummary.SCORING_VERSION_3D,
        endedAtMillis: Long = startedAtMillis + 1_000L,
    ) = SessionSummary(
        mode = TrainingMode.FLICK,
        difficulty = Difficulty.NORMAL,
        startedAtMillis = startedAtMillis,
        endedAtMillis = endedAtMillis,
        hits = hits,
        shots = shots,
        scoringVersion = scoringVersion,
    )

    // ------------------------------------------------------------------------------ resolveItem: built-ins

    @Test
    fun `a built-in with a known id resolves to that component and is available`() {
        val item = DashboardItem.builtin(BuiltinDashboardComponent.GAME_PLAYTIME.id, position = 0)
        val resolved = resolveItem(item, PluginAvailabilitySnapshot.EMPTY)
        assertTrue(resolved.content is ResolvedContent.Builtin)
        assertEquals(BuiltinDashboardComponent.GAME_PLAYTIME, (resolved.content as ResolvedContent.Builtin).component)
        assertEquals(BuiltinDashboardComponent.GAME_PLAYTIME.title, resolved.title)
        assertTrue(resolved.isAvailable)
    }

    @Test
    fun `a built-in whose id this build no longer knows is WIDGET_NOT_PROVIDED, not guessed past`() {
        val item = DashboardItem.builtin("component_from_a_newer_build", position = 0)
        val resolved = resolveItem(item, PluginAvailabilitySnapshot.EMPTY)
        assertEquals(
            ItemUnavailableReason.WIDGET_NOT_PROVIDED,
            (resolved.content as ResolvedContent.Unavailable).reason,
        )
        assertFalse(resolved.isAvailable)
    }

    // ------------------------------------------------------------------- resolveItem: the worst-first order

    @Test
    fun `the kill switch outranks an otherwise fully available plugin widget`() {
        val snap = snapshot(
            killSwitchOn = true,
            plugins = mapOf("com.example.widget" to presence()),
            activeWidgets = mapOf(widgetKey to activeFor()),
        )
        assertEquals(ItemUnavailableReason.KILL_SWITCH_ON, reasonOf(pluginItem, snap))
    }

    @Test
    fun `a plugin absent from the snapshot is PLUGIN_NOT_INSTALLED even if a widget is somehow active`() {
        val snap = snapshot(plugins = emptyMap(), activeWidgets = mapOf(widgetKey to activeFor()))
        assertEquals(ItemUnavailableReason.PLUGIN_NOT_INSTALLED, reasonOf(pluginItem, snap))
    }

    @Test
    fun `a disabled plugin is PLUGIN_DISABLED, outranking suspended, incompatible and not-provided`() {
        // enabled=false with every later condition also true: the first true reason must still win.
        val snap = snapshot(
            plugins = mapOf("com.example.widget" to presence(enabled = false, suspended = true, runnable = false)),
        )
        assertEquals(ItemUnavailableReason.PLUGIN_DISABLED, reasonOf(pluginItem, snap))
    }

    @Test
    fun `a suspended plugin is PLUGIN_SUSPENDED, outranking incompatible and not-provided`() {
        val snap = snapshot(
            plugins = mapOf("com.example.widget" to presence(enabled = true, suspended = true, runnable = false)),
        )
        assertEquals(ItemUnavailableReason.PLUGIN_SUSPENDED, reasonOf(pluginItem, snap))
    }

    @Test
    fun `an incompatible plugin is PLUGIN_INCOMPATIBLE, outranking not-provided`() {
        val snap = snapshot(
            plugins = mapOf("com.example.widget" to presence(enabled = true, suspended = false, runnable = false)),
        )
        assertEquals(ItemUnavailableReason.PLUGIN_INCOMPATIBLE, reasonOf(pluginItem, snap))
    }

    @Test
    fun `a present runnable plugin the host is not drawing the widget for is WIDGET_NOT_PROVIDED`() {
        val snap = snapshot(plugins = mapOf("com.example.widget" to presence()))
        assertEquals(ItemUnavailableReason.WIDGET_NOT_PROVIDED, reasonOf(pluginItem, snap))
    }

    // ------------------------------------------------- resolveItem: the available case and its chrome

    @Test
    fun `a fully available plugin widget resolves to the widget, titled from it and subtitled with its plugin name`() {
        val active = activeFor(pluginName = "Acme Overlays", title = "Frame rate")
        val snap = snapshot(
            plugins = mapOf("com.example.widget" to presence(name = "Presence Name")),
            activeWidgets = mapOf(widgetKey to active),
        )
        val resolved = resolveItem(pluginItem, snap)
        assertTrue(resolved.content is ResolvedContent.PluginWidget)
        assertSame(active, (resolved.content as ResolvedContent.PluginWidget).active)
        assertEquals("Frame rate", resolved.title)
        // The available subtitle is the active widget's own plugin name, not the projected presence name.
        assertEquals("Acme Overlays", resolved.subtitle)
        assertTrue(resolved.isAvailable)
    }

    @Test
    fun `an unavailable plugin item keeps the plugin name as its subtitle so the user sees whose widget has gone`() {
        val snap = snapshot(plugins = mapOf("com.example.widget" to presence(name = "Acme Overlays")))
        val resolved = resolveItem(pluginItem, snap)
        assertEquals("Plugin widget", resolved.title)
        assertEquals("Acme Overlays", resolved.subtitle)
    }

    @Test
    fun `an unavailable plugin item falls back to the plugin id when the presence name is blank`() {
        val snap = snapshot(plugins = mapOf("com.example.widget" to presence(name = "   ")))
        assertEquals("com.example.widget", resolveItem(pluginItem, snap).subtitle)
    }

    // ------------------------------------------------------------------------------ aimAccuracy

    @Test
    fun `aimAccuracy drops legacy-2D and invalid runs, orders the rest oldest-first, and derives count best and mean`() {
        val runs = listOf(
            run(startedAtMillis = 3_000L, hits = 20, shots = 100), // 20%, newest
            run(startedAtMillis = 500L, hits = 100, shots = 100, scoringVersion = SessionSummary.SCORING_VERSION_2D), // legacy
            run(startedAtMillis = 1_000L, hits = 80, shots = 100), // 80%, oldest
            run(startedAtMillis = 0L, hits = 50, shots = 50), // invalid: no start time
            run(startedAtMillis = 2_000L, hits = 50, shots = 100), // 50%, middle
        )
        val data = aimAccuracy(runs)
        assertEquals(3, data.aimRunCount)
        // Oldest-first by startedAtMillis: 80% (1000), 50% (2000), 20% (3000).
        assertEquals(listOf(80f, 50f, 20f), data.aimAccuracySeries)
        assertEquals(80, data.aimBestAccuracyPercent)
        assertEquals(50.0f, data.aimAverageAccuracyPercent!!, 0.0001f)
    }

    @Test
    fun `aimAccuracy on no surviving runs yields zero count, an empty series and null stats, never a fabricated zero`() {
        val onlyFiltered = listOf(
            run(startedAtMillis = 500L, hits = 100, shots = 100, scoringVersion = SessionSummary.SCORING_VERSION_2D),
            run(startedAtMillis = 0L, hits = 50, shots = 50),
        )
        for (input in listOf(emptyList<SessionSummary>(), onlyFiltered)) {
            val data = aimAccuracy(input)
            assertEquals(0, data.aimRunCount)
            assertTrue(data.aimAccuracySeries.isEmpty())
            assertNull(data.aimBestAccuracyPercent)
            assertNull(data.aimAverageAccuracyPercent)
        }
    }

    // ------------------------------------------------------------------------------ the add-lists

    @Test
    fun `addableBuiltins offers every built-in except those already placed`() {
        val layout = DashboardLayout.empty().copy(
            items = listOf(DashboardItem.builtin(BuiltinDashboardComponent.GAME_PLAYTIME.id, position = 0)),
        )
        val addable = addableBuiltins(layout)
        assertFalse(addable.contains(BuiltinDashboardComponent.GAME_PLAYTIME))
        assertEquals(BuiltinDashboardComponent.ALL.size - 1, addable.size)
        assertTrue(addable.contains(BuiltinDashboardComponent.GAME_STATS))
    }

    @Test
    fun `addablePluginWidgets drops already-placed widgets and sorts by plugin name then widget title`() {
        // Inserted deliberately out of order so the ordering is the function's doing, not the map's.
        val widgets = linkedMapOf(
            PluginAvailabilitySnapshot.widgetKey("com.b.plugin", "za") to widget("com.b.plugin", "Beta", "za", "Zebra"),
            PluginAvailabilitySnapshot.widgetKey("com.a.plugin", "mb") to widget("com.a.plugin", "Alpha", "mb", "Mango"),
            PluginAvailabilitySnapshot.widgetKey("com.a.plugin", "ap") to widget("com.a.plugin", "Alpha", "ap", "Apple"),
        )
        val snap = snapshot(activeWidgets = widgets)

        // Nothing placed yet: all three, sorted (Alpha/Apple, Alpha/Mango, Beta/Zebra).
        val none = addablePluginWidgets(DashboardLayout.empty(), snap)
        assertEquals(listOf("Apple", "Mango", "Zebra"), none.map { it.widget.title })

        // Place Alpha/Mango: it drops out, and the order of the rest is kept.
        val layout = DashboardLayout.empty().copy(
            items = listOf(DashboardItem.pluginWidget("com.a.plugin", "mb", position = 0)),
        )
        assertEquals(listOf("Apple", "Zebra"), addablePluginWidgets(layout, snap).map { it.widget.title })
    }

    // ------------------------------------------------------------------------------ reconcile

    @Test
    fun `reconcile finishes loading, normalises the layout, resolves one item each, and passes chart data and edit flag through`() {
        val chartData = DashboardChartData.EMPTY.copy(aimRunCount = 9)
        val layout = DashboardLayout.empty().copy(
            items = listOf(
                DashboardItem.builtin(BuiltinDashboardComponent.GAME_STATS.id, position = 3),
                DashboardItem.builtin(BuiltinDashboardComponent.GAME_PLAYTIME.id, position = 1),
            ),
        )
        val state = reconcile(layout, PluginAvailabilitySnapshot.EMPTY, chartData, isEditing = true)
        assertFalse(state.isLoading)
        assertTrue(state.isEditing)
        assertEquals(layout.normalised(), state.layout)
        assertEquals(state.layout.items.size, state.resolvedItems.size)
        assertEquals(state.layout.items, state.resolvedItems.map { it.item })
        assertFalse(state.atPluginCapacity)
        assertSame(chartData, state.chartData)
    }

    @Test
    fun `reconcile at plugin-widget capacity reports it and offers no plugin widgets to add`() {
        val full = (0 until DashboardLayout.MAX_PLUGIN_WIDGET_ITEMS).map {
            DashboardItem.pluginWidget("com.cap.plugin", "w$it", position = it)
        }
        val layout = DashboardLayout.empty().copy(items = full)
        // A widget that is not placed and would otherwise be addable, to prove the empty add-list is the
        // capacity gate at work rather than merely an empty snapshot.
        val snap = snapshot(
            activeWidgets = mapOf(
                PluginAvailabilitySnapshot.widgetKey("com.other.plugin", "x") to
                    widget("com.other.plugin", "Other", "x", "Spare"),
            ),
        )
        val state = reconcile(layout, snap, DashboardChartData.EMPTY, isEditing = false)
        assertEquals(DashboardLayout.MAX_PLUGIN_WIDGET_ITEMS, state.resolvedItems.size)
        assertTrue(state.atPluginCapacity)
        assertTrue(state.addablePluginWidgets.isEmpty())
    }

    // ----------------------------------------------------------------------- DashboardComposerUiState

    @Test
    fun `visibleItems hides items toggled off while isEmpty reflects the whole resolved set`() {
        val shown = resolveItem(
            DashboardItem.builtin(BuiltinDashboardComponent.GAME_PLAYTIME.id, position = 0),
            PluginAvailabilitySnapshot.EMPTY,
        )
        val hidden = resolveItem(
            DashboardItem.builtin(BuiltinDashboardComponent.GAME_STATS.id, position = 1).copy(visible = false),
            PluginAvailabilitySnapshot.EMPTY,
        )
        val state = DashboardComposerUiState(resolvedItems = listOf(shown, hidden))
        assertEquals(listOf(shown), state.visibleItems)
        assertFalse(state.isEmpty)
        // A fresh state with nothing resolved is empty — and that is distinct from "hidden by a toggle".
        assertTrue(DashboardComposerUiState().isEmpty)
    }
}
