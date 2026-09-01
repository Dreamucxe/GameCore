package com.gamecore.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §31's HUD configuration, and §24B's frame-rate honesty where it is declared.
 *
 * The builder edits a layout entirely in memory — drag, resize, delete, undo — and only writes it
 * when the user leaves the screen, so [HudLayout.withWidget] is the single operation the whole
 * editor is built on. It has to be idempotent per widget id, or dragging a widget would duplicate
 * it on every frame of the gesture.
 */
class HudLayoutTest {

    private fun widget(id: String, stat: HudStat = HudStat.CPU_USAGE) =
        HudWidget(id = id, stat = stat)

    @Test
    fun `a new layout is empty and says so`() {
        val layout = HudLayout(name = "Racing")
        assertTrue(layout.isEmpty)
        assertEquals(0, layout.widgetCount)
        assertEquals(0L, layout.id)
        assertEquals(0L, layout.createdAtMillis)
    }

    @Test
    fun `adding widgets with new ids appends them in order`() {
        val layout = HudLayout(name = "Racing")
            .withWidget(widget("a"))
            .withWidget(widget("b", HudStat.RAM_USAGE))
        assertFalse(layout.isEmpty)
        assertEquals(2, layout.widgetCount)
        assertEquals(listOf("a", "b"), layout.widgets.map { it.id })
        assertEquals(HudStat.RAM_USAGE, layout.widgets[1].stat)
    }

    @Test
    fun `dragging a widget replaces it rather than adding a second copy`() {
        // The builder calls withWidget on every drag update. Twenty updates is one widget.
        var layout = HudLayout(name = "Racing").withWidget(widget("a"))
        repeat(20) { step ->
            layout = layout.withWidget(widget("a").copy(xFraction = step / 100f))
        }
        assertEquals(1, layout.widgetCount)
        assertEquals(0.19f, layout.widgets.single().xFraction, 0.0001f)
    }

    @Test
    fun `replacing a widget keeps the rest of the layout intact`() {
        val layout = HudLayout(name = "Racing")
            .withWidget(widget("a"))
            .withWidget(widget("b", HudStat.RAM_USAGE))
            .withWidget(widget("c", HudStat.BATTERY_LEVEL))
            .withWidget(widget("b", HudStat.NETWORK_LATENCY).copy(textSizeSp = 20))
        assertEquals(3, layout.widgetCount)
        assertEquals(setOf("a", "b", "c"), layout.widgets.map { it.id }.toSet())
        val replaced = layout.widgets.single { it.id == "b" }
        assertEquals(HudStat.NETWORK_LATENCY, replaced.stat)
        assertEquals(20, replaced.textSizeSp)
    }

    @Test
    fun `deleting a widget removes exactly one, and deleting nothing changes nothing`() {
        val layout = HudLayout(name = "Racing")
            .withWidget(widget("a"))
            .withWidget(widget("b"))
        assertEquals(listOf("b"), layout.withoutWidget("a").widgets.map { it.id })
        assertEquals(layout, layout.withoutWidget("nonexistent"))
        assertTrue(layout.withoutWidget("a").withoutWidget("b").isEmpty)
    }

    @Test
    fun `a widget's defaults are drawable without the builder touching anything`() {
        val fresh = widget("a")
        assertEquals(0.05f, fresh.xFraction, 0.0001f)
        assertEquals(0.05f, fresh.yFraction, 0.0001f)
        assertEquals(HudWidget.DEFAULT_TEXT_SIZE_SP, fresh.textSizeSp)
        assertEquals(85, fresh.opacityPercent)
        assertEquals(HudWidget.DEFAULT_COLOR, fresh.colorArgb)
        assertTrue(fresh.showLabel)
        assertTrue(fresh.showBackground)
        assertEquals(fresh, fresh.normalised())
    }

    @Test
    fun `an imported widget from off the edge of the screen is pulled back on`() {
        val imported = widget("a").copy(xFraction = 4.2f, yFraction = -0.9f)
        val fixed = imported.normalised()
        assertNotEquals(imported, fixed)
        assertEquals(1f, fixed.xFraction, 0.0001f)
        assertEquals(0f, fixed.yFraction, 0.0001f)
    }

    @Test
    fun `text size is clamped to what is legible without being a billboard`() {
        assertEquals(8, widget("a").copy(textSizeSp = 1).normalised().textSizeSp)
        assertEquals(8, widget("a").copy(textSizeSp = -40).normalised().textSizeSp)
        assertEquals(28, widget("a").copy(textSizeSp = 400).normalised().textSizeSp)
        assertEquals(8, HudWidget.MIN_TEXT_SIZE_SP)
        assertEquals(28, HudWidget.MAX_TEXT_SIZE_SP)
        // The whole legitimate range survives untouched.
        (HudWidget.MIN_TEXT_SIZE_SP..HudWidget.MAX_TEXT_SIZE_SP).forEach { sp ->
            assertEquals(sp, widget("a").copy(textSizeSp = sp).normalised().textSizeSp)
        }
    }

    @Test
    fun `opacity has a floor, so a widget can never become invisible and undeletable`() {
        assertEquals(15, widget("a").copy(opacityPercent = 0).normalised().opacityPercent)
        assertEquals(15, widget("a").copy(opacityPercent = -30).normalised().opacityPercent)
        assertEquals(15, widget("a").copy(opacityPercent = 14).normalised().opacityPercent)
        assertEquals(15, widget("a").copy(opacityPercent = 15).normalised().opacityPercent)
        assertEquals(100, widget("a").copy(opacityPercent = 900).normalised().opacityPercent)
        assertEquals(15, HudWidget.MIN_OPACITY_PERCENT)
    }

    @Test
    fun `normalising leaves everything it does not clamp exactly as it was`() {
        val styled = widget("a", HudStat.FRAME_RATE).copy(
            xFraction = 0.4f,
            yFraction = 0.8f,
            textSizeSp = 18,
            opacityPercent = 60,
            showLabel = false,
            showBackground = false,
            colorArgb = 0xFF00FF00.toInt(),
        )
        assertEquals(styled, styled.normalised())
        assertEquals(0xFF00FF00.toInt(), styled.normalised().colorArgb)
    }

    @Test
    fun `the caps the builder enforces are the ones a HUD can actually be read at`() {
        assertEquals(12, HudLayout.MAX_WIDGETS)
        assertEquals(40, HudLayout.MAX_NAME_LENGTH)
        // A layout is a plain value: the cap belongs to the editor, and holding the maximum is
        // still a valid layout the renderer can draw.
        val full = (1..HudLayout.MAX_WIDGETS).fold(HudLayout(name = "Full")) { layout, index ->
            layout.withWidget(widget("w$index"))
        }
        assertEquals(HudLayout.MAX_WIDGETS, full.widgetCount)
    }

    @Test
    fun `the default set is four stats that need no permission and no network`() {
        assertEquals(
            listOf(HudStat.CPU_USAGE, HudStat.RAM_USAGE, HudStat.BATTERY_LEVEL, HudStat.REFRESH_RATE),
            HudStat.DEFAULT_SET,
        )
        HudStat.DEFAULT_SET.forEach { assertTrue(it.name, it.isAlwaysAvailable) }
    }

    @Test
    fun `frame rate is offered and marked conditional, because §24B says it usually is not there`() {
        // The widget exists; what it renders when no reliable signal exists is "n/a" with the
        // reason, which is why this flag is part of the model rather than a UI detail.
        assertFalse(HudStat.FRAME_RATE.isAlwaysAvailable)
        assertEquals("fps", HudStat.FRAME_RATE.unit)
        assertEquals("FPS", HudStat.FRAME_RATE.shortLabel)
    }

    @Test
    fun `the conditional stats are exactly the ones that need hardware, a network or a grant`() {
        val conditional = HudStat.entries.filterNot { it.isAlwaysAvailable }.toSet()
        assertEquals(
            setOf(
                HudStat.CPU_TEMPERATURE,
                HudStat.BATTERY_CURRENT,
                HudStat.FRAME_RATE,
                HudStat.NETWORK_LATENCY,
                HudStat.NETWORK_DOWN,
                HudStat.NETWORK_UP,
                HudStat.THERMAL_STATUS,
            ),
            conditional,
        )
    }

    @Test
    fun `every stat carries a label, and the clock is the one that draws its own`() {
        assertEquals(16, HudStat.entries.size)
        HudStat.entries.forEach { assertTrue(it.name, it.label.isNotBlank()) }
        // CLOCK renders a time, so a "Clock:" prefix and a unit would both be noise.
        assertEquals("", HudStat.CLOCK.shortLabel)
        assertEquals("", HudStat.CLOCK.unit)
        assertEquals("", HudStat.SESSION_DURATION.unit)
        assertEquals("%", HudStat.CPU_USAGE.unit)
        assertEquals("Hz", HudStat.REFRESH_RATE.shortLabel)
    }
}
