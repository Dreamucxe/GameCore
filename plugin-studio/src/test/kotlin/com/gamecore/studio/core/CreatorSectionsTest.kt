package com.gamecore.studio.core

import com.gamecore.studio.model.BoolSetting
import com.gamecore.studio.model.BrokerActionId
import com.gamecore.studio.model.BuiltinIcon
import com.gamecore.studio.model.CreatorSections
import com.gamecore.studio.model.EnumSetting
import com.gamecore.studio.model.FloatSetting
import com.gamecore.studio.model.IntSetting
import com.gamecore.studio.model.NodeDraft
import com.gamecore.studio.model.NodeKind
import com.gamecore.studio.model.NodeSurface
import com.gamecore.studio.model.NodeValue
import com.gamecore.studio.model.Operator
import com.gamecore.studio.model.OverlayDraft
import com.gamecore.studio.model.RuleCondition
import com.gamecore.studio.model.RuleDraft
import com.gamecore.studio.model.RuleVariable
import com.gamecore.studio.model.ScreenDraft
import com.gamecore.studio.model.SettingDraft
import com.gamecore.studio.model.StatId
import com.gamecore.studio.model.StringSetting
import com.gamecore.studio.model.Trigger
import com.gamecore.studio.model.WidgetDraft
import com.gamecore.studio.model.WidgetSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM coverage for the creator's closed vocabularies, bounds, and capability derivation. */
class CreatorSectionsTest {

    private val text = NodeDraft.text("Ready")

    @Test
    fun `the default creator is a valid theme-only draft`() {
        val creator = CreatorSections()

        assertTrue(creator.problems().isEmpty())
        assertEquals(setOf("theme"), creator.declaredSections())
        assertEquals(setOf("change_ui"), creator.requiredPermissions())
        assertTrue(creator.requiredScopes().isEmpty())
    }

    @Test
    fun `all section models are valid together and declare host section ids`() {
        val creator = CreatorSections(
            screens = listOf(
                ScreenDraft(
                    id = "overview",
                    title = "Overview",
                    icon = BuiltinIcon.INSIGHTS.id,
                    root = NodeDraft.column(listOf(NodeDraft.stat(StatId.FPS), text)),
                ),
            ),
            overlay = OverlayDraft(
                id = "battery-badge",
                title = "Battery badge",
                content = NodeDraft.row(
                    listOf(NodeDraft.stat(StatId.BATTERY), NodeDraft.stat(StatId.MOVEMENT), text),
                ),
            ),
            settings = validSettings(),
            widgets = listOf(
                WidgetDraft(
                    id = "motion-history",
                    title = "Motion history",
                    size = WidgetSize.FULL,
                    content = NodeDraft.scroll(
                        listOf(NodeDraft.chart(stat = StatId.RAM, stat2 = StatId.CPU)),
                    ),
                ),
            ),
            rules = listOf(
                RuleDraft(
                    id = "cool-down",
                    trigger = Trigger.THERMAL_HIGH,
                    conditions = listOf(RuleCondition(RuleVariable.TEMP, Operator.GTE, 45.0)),
                    actionIds = listOf(BrokerActionId.SET_LOW_POWER),
                ),
            ),
        )

        assertTrue("unexpected problems: ${creator.problems()}", creator.problems().isEmpty())
        assertEquals(
            setOf("theme", "pill", "screens", "rules", "settings", "widgets"),
            creator.declaredSections(),
        )
        assertEquals(setOf("device_stats", "thermals", "motion"), creator.requiredScopes())
        assertEquals(
            setOf(
                "change_ui",
                "create_overlay",
                "add_dashboard_widgets",
                "store_data",
                "run_automations",
                "system_actions",
                "read_approved_data",
            ),
            creator.requiredPermissions(),
        )
    }

    @Test
    fun `node kinds deliberately omit every interactive host leaf`() {
        assertEquals(
            setOf("column", "row", "grid", "scroll", "card", "text", "stat", "spacer", "image", "chart"),
            NodeKind.entries.map { it.id }.toSet(),
        )
        assertFalse(NodeKind.entries.any { it.id in setOf("button", "slider", "switch") })
        assertEquals(4, listOf(NodeValue.Text("x"), NodeValue.Number(1), NodeValue.Bool(true), NodeValue.Color(0L)).size)
    }

    @Test
    fun `screen tree enforces full-screen node depth child grid and string caps`() {
        val tooMany = NodeDraft.column(List(500) { text }) // root + 500 leaves
        val tooDeep = chain(depthBelowRoot = 25)
        val tooManyChildren = NodeDraft.column(List(101) { text })
        val wideGrid = NodeDraft.grid(columns = 9, children = listOf(text))
        val longText = NodeDraft.text("x".repeat(2_001))

        assertProblem(tooMany.problems(NodeSurface.SCREEN), "more than 500 nodes")
        assertProblem(tooDeep.problems(NodeSurface.SCREEN), "deeper than 24")
        assertProblem(tooManyChildren.problems(NodeSurface.SCREEN), "at most 100 children")
        assertProblem(wideGrid.problems(NodeSurface.SCREEN), "1 to 8")
        assertProblem(longText.problems(NodeSurface.SCREEN), "at most 2000 characters")
    }

    @Test
    fun `overlay enforces compact caps and rejects scroll and live chart series`() {
        val tooMany = NodeDraft.column(List(40) { text })
        val tooDeep = chain(depthBelowRoot = 7)
        val tooManyChildren = NodeDraft.column(List(13) { text })
        val wideGrid = NodeDraft.grid(columns = 5, children = listOf(text))
        val longText = NodeDraft.text("x".repeat(121))
        val scroll = NodeDraft.scroll(listOf(text))
        val liveChart = NodeDraft.chart(stat = StatId.FPS)

        assertProblem(tooMany.problems(NodeSurface.OVERLAY), "more than 40 nodes")
        assertProblem(tooDeep.problems(NodeSurface.OVERLAY), "deeper than 6")
        assertProblem(tooManyChildren.problems(NodeSurface.OVERLAY), "at most 12 children")
        assertProblem(wideGrid.problems(NodeSurface.OVERLAY), "1 to 4")
        assertProblem(longText.problems(NodeSurface.OVERLAY), "at most 120 characters")
        assertProblem(scroll.problems(NodeSurface.OVERLAY), "cannot contain a scroll")
        assertProblem(liveChart.problems(NodeSurface.OVERLAY), "literal-only")
    }

    @Test
    fun `widget uses compact caps but permits scroll and approved live chart series`() {
        val widgetTree = NodeDraft.scroll(
            listOf(
                NodeDraft.chart(stat = StatId.FPS, stat2 = StatId.TEMP),
                NodeDraft.stat(StatId.GPU),
            ),
        )

        assertTrue(widgetTree.problems(NodeSurface.WIDGET).isEmpty())
        assertProblem(NodeDraft.grid(5).problems(NodeSurface.WIDGET), "1 to 4")
        assertProblem(chain(7).problems(NodeSurface.WIDGET), "deeper than 6")
    }

    @Test
    fun `screen and widget refuse readings their runtime resolvers cannot supply`() {
        assertProblem(
            NodeDraft.stat(StatId.MOVEMENT).problems(NodeSurface.SCREEN),
            "unavailable on a full screen",
        )
        assertProblem(
            NodeDraft.stat(StatId.ROTATION_RATE).problems(NodeSurface.WIDGET),
            "unavailable on a dashboard widget",
        )
        assertProblem(
            NodeDraft.chart(stat = StatId.GPU).problems(NodeSurface.SCREEN),
            "host retains no series",
        )
        assertProblem(
            NodeDraft.chart(stat = StatId.BATTERY).problems(NodeSurface.WIDGET),
            "host retains no series",
        )
    }

    @Test
    fun `leaf shape stat ids and built-in image and screen icons are validated`() {
        val leafWithChild = NodeDraft(NodeKind.TEXT, children = listOf(text))
        val unknownStat = NodeDraft.stat("framez")
        val unknownImage = NodeDraft.image("downloaded-file")
        val screen = ScreenDraft("home", "Home", "downloaded-file", text)

        assertProblem(leafWithChild.problems(), "cannot have children")
        assertProblem(unknownStat.problems(), "must name one of")
        assertProblem(unknownImage.problems(), "built-in icons")
        assertProblem(screen.problems(), "not a built-in")
    }

    @Test
    fun `non-finite numbers invalid colors and fractional grid columns are refused`() {
        val node = NodeDraft(
            NodeKind.GRID,
            props = mapOf(
                "columns" to NodeValue.Number(2.5),
                "gap" to NodeValue.Number(Double.NaN),
                "color" to NodeValue.Color(0x1_0000_0000L),
            ),
        )

        val problems = node.problems()
        assertProblem(problems, "whole number")
        assertProblem(problems, "must be finite")
        assertProblem(problems, "32-bit ARGB")
    }

    @Test
    fun `section counts slug grammars and duplicate ids are reported`() {
        val creator = CreatorSections(
            themeEnabled = false,
            screens = List(33) { ScreenDraft("screen-$it", "Screen $it", root = text) } +
                listOf(ScreenDraft("screen-0", "Duplicate", root = text)),
            settings = List(65) { BoolSetting("setting-$it") } + listOf(BoolSetting("setting-0")),
            widgets = List(33) { WidgetDraft("widget-$it", "Widget $it", content = text) } +
                listOf(WidgetDraft("widget-0", "Duplicate", content = text)),
            rules = List(33) {
                RuleDraft("rule-$it", Trigger.GAME_LAUNCHED, actionIds = listOf(BrokerActionId.SET_LOW_POWER))
            } + listOf(
                RuleDraft("rule-0", Trigger.GAME_CLOSED, actionIds = listOf(BrokerActionId.SET_LOW_POWER)),
            ),
        )

        val problems = creator.problems()
        listOf("at most 32 screens", "at most 64 settings", "at most 32 dashboard", "at most 32 rules").forEach {
            assertProblem(problems, it)
        }
        listOf("Two screens", "Two settings", "Two widgets", "Two rules").forEach {
            assertProblem(problems, it)
        }

        assertProblem(ScreenDraft("Bad Id", "Title", root = text).problems(), "lowercase slug")
        assertProblem(OverlayDraft("Bad Id", "Title", content = text).problems(), "lowercase slug")
        assertProblem(WidgetDraft("Bad Id", "Title", content = text).problems(), "lowercase slug")
    }

    @Test
    fun `all five settings types and their host bounds are validated`() {
        assertTrue(validSettings().flatMap { it.problems() }.isEmpty())

        val invalid = listOf<SettingDraft>(
            BoolSetting("Bad.Id"),
            IntSetting("integer", default = 5, min = 10, max = 1),
            FloatSetting("float", default = Double.NaN, min = 2.0, max = 1.0),
            EnumSetting("enum", options = List(33) { "v$it" } + "v0", default = "missing"),
            StringSetting("string", default = "toolong", maxLength = 3),
        )
        val problems = invalid.flatMap { it.problems() }

        listOf(
            "lowercase storage-key slug",
            "minimum cannot exceed",
            "float default must be finite",
            "at most 32 options",
            "cannot repeat option",
            "default must be one",
            "default is longer",
        ).forEach { assertProblem(problems, it) }
    }

    @Test
    fun `rules enforce flat host bounds finite values nonempty actions and unique ids`() {
        val tooLarge = RuleDraft(
            id = "large",
            trigger = Trigger.SESSION_STARTED,
            conditions = List(11) { RuleCondition(RuleVariable.CPU, Operator.GT, it.toDouble()) } +
                RuleCondition(RuleVariable.FPS, Operator.EQ, Double.POSITIVE_INFINITY),
            actionIds = List(9) { BrokerActionId.SET_LOW_POWER },
        )
        val noActions = RuleDraft("idle", Trigger.SESSION_ENDED)
        val duplicate = CreatorSections(
            themeEnabled = false,
            rules = listOf(
                RuleDraft("same", Trigger.GAME_LAUNCHED, actionIds = listOf(BrokerActionId.SET_LOW_POWER)),
                RuleDraft("same", Trigger.GAME_CLOSED, actionIds = listOf(BrokerActionId.SET_LOW_POWER)),
            ),
        )

        assertProblem(tooLarge.problems(), "at most 10 conditions")
        assertProblem(tooLarge.problems(), "must be finite")
        assertProblem(tooLarge.problems(), "at most 8 actions")
        assertProblem(noActions.problems(), "at least one broker action")
        assertProblem(duplicate.problems(), "Two rules share")
        assertEquals(7, Trigger.entries.size)
        assertEquals(6, RuleVariable.entries.size)
        assertEquals(6, Operator.entries.size)
        assertEquals(7, BrokerActionId.entries.size)
    }

    private fun validSettings(): List<SettingDraft> = listOf(
        BoolSetting("haptics", default = true, label = "Haptics", description = "Studio only"),
        IntSetting("refresh", default = 120, min = 60, max = 240),
        FloatSetting("gamma", default = 1.0, min = 0.5, max = 2.0),
        EnumSetting("units", options = listOf("metric", "imperial"), default = "metric"),
        StringSetting("label", default = "Player", maxLength = 32),
    )

    private fun chain(depthBelowRoot: Int): NodeDraft {
        var node = text
        repeat(depthBelowRoot) { node = NodeDraft.column(listOf(node)) }
        return node
    }

    private fun assertProblem(problems: List<com.gamecore.studio.model.DraftProblem>, phrase: String) {
        assertTrue("expected a problem containing '$phrase', got $problems", problems.any { phrase in it.message })
    }
}
