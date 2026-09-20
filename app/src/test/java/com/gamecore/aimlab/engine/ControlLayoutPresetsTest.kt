package com.gamecore.aimlab.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The starting layouts §12 promises for two, three, four and five fingers.
 *
 * A preset is the first thing a user sees before they ever drag a control, so it has to be playable as
 * shipped: every button fully on screen, no two buttons under the same fingertip, and a shoot button and a
 * movement stick always present. Those are asserted here for every preset rather than for one sample,
 * because the tables are hand-written coordinates and a mistyped digit is invisible in review. The
 * structural promise is the other half: each higher finger count keeps everything the one below it placed
 * and adds to it, so switching preset never silently loses a control the user relies on.
 */
class ControlLayoutPresetsTest {

    private val eps = 1e-5f

    private val two = ControlLayout.preset(LayoutPreset.TWO_FINGER)
    private val three = ControlLayout.preset(LayoutPreset.THREE_FINGER)
    private val four = ControlLayout.preset(LayoutPreset.FOUR_FINGER)
    private val five = ControlLayout.preset(LayoutPreset.FIVE_FINGER)
    private val custom = ControlLayout.preset(LayoutPreset.CUSTOM)

    private val all = listOf(two, three, four, five, custom)

    @Test
    fun `every preset is named after itself and gives the hands something to shoot and move with`() {
        assertEquals("2 finger", two.name)
        assertEquals("3 finger", three.name)
        assertEquals("4 finger", four.name)
        assertEquals("5 finger", five.name)
        assertEquals("Custom", custom.name)
        for (preset in LayoutPreset.entries) {
            val layout = ControlLayout.preset(preset)
            assertEquals(preset.label, layout.name)
            assertTrue("$preset has no shoot button", layout.controls.any { it.role == ControlRole.SHOOT })
            assertTrue("$preset has no move stick", layout.controls.any { it.role == ControlRole.MOVE_STICK })
        }
    }

    @Test
    fun `the two-finger preset is a thumb on the stick and a thumb on the trigger`() {
        assertEquals(
            listOf(ControlRole.MOVE_STICK, ControlRole.SHOOT, ControlRole.ADS),
            two.controls.map { it.role },
        )
        // ADS is placed but switched off, so exactly two controls are live for two fingers.
        assertEquals(
            listOf(ControlRole.MOVE_STICK, ControlRole.SHOOT),
            two.enabledControls.map { it.role },
        )
        assertFalse(two.controls.first { it.role == ControlRole.ADS }.enabled)
    }

    @Test
    fun `each higher finger count keeps every control the one below it placed`() {
        assertTrue("three finger dropped a two finger role", three.controls.map { it.role }.containsAll(two.controls.map { it.role }))
        assertTrue("four finger moved a three finger control", four.controls.containsAll(three.controls))
        assertTrue("five finger moved a four finger control", five.controls.containsAll(four.controls))

        assertEquals(3, two.controls.size)
        assertEquals(5, three.controls.size)
        assertEquals(7, four.controls.size)
        assertEquals(9, five.controls.size)

        // The extra fingers earn the extra controls.
        assertEquals(listOf(ControlRole.CROUCH, ControlRole.WEAPON_SWITCH), four.controls.drop(three.controls.size).map { it.role })
        assertEquals(listOf(ControlRole.SPRINT, ControlRole.MELEE), five.controls.drop(four.controls.size).map { it.role })
    }

    @Test
    fun `custom starts from the three-finger base under its own name`() {
        assertEquals(three.controls, custom.controls)
        assertNotEquals(three.name, custom.name)
        assertNotEquals(three, custom)
    }

    @Test
    fun `no preset places the same role twice`() {
        for (layout in all) {
            val roles = layout.controls.map { it.role }
            assertEquals("${layout.name} repeats a role: $roles", roles.size, roles.distinct().size)
        }
    }

    @Test
    fun `every control body sits fully inside the arena`() {
        for (layout in all) {
            for (c in layout.controls) {
                val halfW = c.widthFraction / 2f
                val halfH = c.heightFraction / 2f
                assertTrue("${layout.name}/${c.role} off the left edge", c.xFraction - halfW >= -eps)
                assertTrue("${layout.name}/${c.role} off the right edge", c.xFraction + halfW <= 1f + eps)
                assertTrue("${layout.name}/${c.role} off the top edge", c.yFraction - halfH >= -eps)
                assertTrue("${layout.name}/${c.role} off the bottom edge", c.yFraction + halfH <= 1f + eps)
            }
        }
    }

    @Test
    fun `every control is a usable size and visible`() {
        for (layout in all) {
            for (c in layout.controls) {
                assertTrue("${layout.name}/${c.role} width ${c.widthFraction}", c.widthFraction in ControlWidget.MIN_SIZE..ControlWidget.MAX_SIZE)
                assertTrue("${layout.name}/${c.role} height ${c.heightFraction}", c.heightFraction in ControlWidget.MIN_SIZE..ControlWidget.MAX_SIZE)
                assertTrue("${layout.name}/${c.role} opacity ${c.opacityPercent}", c.opacityPercent >= ControlWidget.MIN_OPACITY)
                assertTrue("${layout.name}/${c.role} opacity ${c.opacityPercent}", c.opacityPercent <= 100)
            }
        }
    }

    @Test
    fun `no two controls in a preset overlap`() {
        for (layout in all) {
            val controls = layout.controls
            for (i in controls.indices) {
                for (j in i + 1 until controls.size) {
                    val a = controls[i]
                    val b = controls[j]
                    val separatedOnX =
                        a.xFraction + a.widthFraction / 2f <= b.xFraction - b.widthFraction / 2f ||
                            b.xFraction + b.widthFraction / 2f <= a.xFraction - a.widthFraction / 2f
                    val separatedOnY =
                        a.yFraction + a.heightFraction / 2f <= b.yFraction - b.heightFraction / 2f ||
                            b.yFraction + b.heightFraction / 2f <= a.yFraction - a.heightFraction / 2f
                    assertTrue("${layout.name}: ${a.role} overlaps ${b.role}", separatedOnX || separatedOnY)
                }
            }
        }
    }

    @Test
    fun `the four finger-count presets are genuinely different layouts`() {
        val layouts = listOf(two, three, four, five)
        for (i in layouts.indices) {
            for (j in i + 1 until layouts.size) {
                assertNotEquals("${layouts[i].name} and ${layouts[j].name} are identical", layouts[i].controls, layouts[j].controls)
            }
        }
    }

    @Test
    fun `a preset survives being clamped to a real safe area without losing a control`() {
        val safe = SafeArea(left = 0.05f, top = 0.06f, right = 0.95f, bottom = 0.94f)
        val clamped = five.clampedTo(safe)
        assertEquals(five.controls.size, clamped.controls.size)
        assertEquals(five.controls.map { it.role }, clamped.controls.map { it.role })
        for (c in clamped.controls) {
            assertTrue("${c.role} left of the safe area", c.xFraction - c.widthFraction / 2f >= safe.left - eps)
            assertTrue("${c.role} right of the safe area", c.xFraction + c.widthFraction / 2f <= safe.right + eps)
            assertTrue("${c.role} above the safe area", c.yFraction - c.heightFraction / 2f >= safe.top - eps)
            assertTrue("${c.role} below the safe area", c.yFraction + c.heightFraction / 2f <= safe.bottom + eps)
        }
    }
}
