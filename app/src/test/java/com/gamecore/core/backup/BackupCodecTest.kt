package com.gamecore.core.backup

import com.gamecore.core.model.AccentChoice
import com.gamecore.core.model.AnimationsMode
import com.gamecore.core.model.AppSettings
import com.gamecore.core.model.ColorCorrection
import com.gamecore.core.model.ColorPreset
import com.gamecore.core.model.ColorVisionFilter
import com.gamecore.core.model.CrosshairDesign
import com.gamecore.core.model.CrosshairPreset
import com.gamecore.core.model.FloatingButtonConfig
import com.gamecore.core.model.GammaMode
import com.gamecore.core.model.HudLayout
import com.gamecore.core.model.HudStat
import com.gamecore.core.model.HudWidget
import com.gamecore.core.model.OverlayConfig
import com.gamecore.core.model.PanelLayoutStyle
import com.gamecore.core.model.PillDisplayMode
import com.gamecore.core.model.ThemeChoice
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BackupCodec]'s round trip and its foreign-file rejection (§20), asserted on what a real `org.json`
 * actually parses — the mirror of [com.gamecore.core.overlay.MacroCodec]'s test.
 *
 * Where the macro store codec is *internal* and total (a bad key self-heals to fewer macros), this reads a
 * *foreign* file and so reports why it refused with a typed reason rather than throwing or returning a silent
 * empty. The two halves below are exactly that contract: every configuration section survives an encode and
 * decode unchanged, and every shape of a hostile or wrong file degrades to a [BackupCodec.RejectReason] — or,
 * for valid-JSON-but-wrong-typed sections, to an absent (null) section — without a single throw escaping.
 *
 * These are only meaningful because a real `org.json` is on the unit-test classpath; the `android.jar` stub
 * would let the round trip pass without parsing a character.
 */
class BackupCodecTest {

    private fun restored(json: String?): BackupCodec.BackupData {
        val result = BackupCodec.decode(json)
        assertTrue("expected Restored, was $result", result is BackupCodec.BackupResult.Restored)
        return (result as BackupCodec.BackupResult.Restored).data
    }

    private fun rejected(json: String?): BackupCodec.RejectReason {
        val result = BackupCodec.decode(json)
        assertTrue("expected Rejected, was $result", result is BackupCodec.BackupResult.Rejected)
        return (result as BackupCodec.BackupResult.Rejected).reason
    }

    private fun envelope(body: String): String =
        """{"format":"${BackupCodec.FORMAT}","version":${BackupCodec.VERSION}$body}"""

    // --- Round trip -----------------------------------------------------------------------------------

    @Test
    fun `every configuration section survives an encode and decode unchanged`() {
        val settings = AppSettings(
            theme = ThemeChoice.DARK,
            accent = AccentChoice.VIOLET,
            uiScalePercent = 110,
            animations = AnimationsMode.OFF,
            hapticsEnabled = false,
            latencyHost = "8.8.8.8",
            showResolutionOverrideNotice = false,
            showConfigEditNotice = false,
            neverKillPackages = listOf("com.example.game"),
            aimLabHorizontalFovDegrees = 100,
        )
        val overlay = OverlayConfig(
            showPill = true,
            pillX = 30,
            pillY = 300,
            stats = listOf(HudStat.CPU_USAGE, HudStat.RAM_USAGE, HudStat.FRAME_RATE),
            updateIntervalMillis = 2_000L,
            textSizeSp = 14,
            opacityPercent = 70,
            cornerRadiusDp = 16,
            isVertical = true,
            showLabels = false,
            displayMode = PillDisplayMode.COMPACT,
            quickPins = listOf("FLASHLIGHT", "DND"),
            quickAutoClose = false,
        )
        val button = FloatingButtonConfig(
            show = false,
            sizeDp = 60,
            opacityPercent = 90,
            snapToEdge = false,
            idleOpacityPercent = 55,
            hapticFeedback = false,
            doubleTapForPanel = true,
            panelWidthDp = 300,
            panelLayout = PanelLayoutStyle.SPLIT_EDGES,
            showQuickApps = true,
            quickAppPackages = listOf("com.example.app"),
            portraitXFraction = 0.25f,
            portraitYFraction = 0.5f,
        )
        val colour = ColorCorrection(
            redGain = 20,
            greenGain = -10,
            blueGain = 5,
            gammaMode = GammaMode.PER_CHANNEL,
            redGamma = 10,
            greenGamma = -20,
            blueGamma = 30,
            saturation = 45,
            contrast = 15,
            hueDegrees = 90,
            brightnessOffset = -25,
            visionFilter = ColorVisionFilter.DEUTERANOPIA,
            invertColors = true,
        )
        val widget = HudWidget(
            id = "w1",
            stat = HudStat.CPU_USAGE,
            xFraction = 0.1f,
            yFraction = 0.2f,
            textSizeSp = 14,
            opacityPercent = 90,
            showLabel = true,
            showBackground = false,
            colorArgb = 0xFF00FF00.toInt(),
        )
        val layout = HudLayout(7L, "My Layout", listOf(widget), createdAtMillis = 111L, updatedAtMillis = 222L)
        val macros = JSONArray().put(
            JSONObject().put("id", 1).put("name", "Warm").put("actions", JSONArray().put("PILL").put("HUD")),
        )
        val profiles = JSONObject()
            .put("profiles", JSONArray().put(JSONObject().put("id", 1).put("name", "FPS")))
            .put("presets", JSONObject().put("crosshairs", JSONArray()))

        val decoded = restored(
            BackupCodec.encode(
                settings, overlay, button, colour, listOf(layout),
                emptyList(), emptyList(), macros, profiles,
            ),
        )

        assertEquals(settings.normalised(), decoded.settings)
        assertEquals(overlay.normalised(), decoded.overlay)
        assertEquals(button.normalised(), decoded.button)
        assertEquals(colour.normalised(), decoded.colour)
        assertEquals(listOf(layout), decoded.layouts)
        assertEquals(1, decoded.macros!!.length())
        assertEquals("Warm", decoded.macros!!.getJSONObject(0).getString("name"))
        assertNotNull(decoded.profiles)
        assertEquals("FPS", decoded.profiles!!.getJSONArray("profiles").getJSONObject(0).getString("name"))
        assertTrue(decoded.profiles!!.getJSONObject("presets").has("crosshairs"))
    }

    // --- Design A: referenced items travel via the profile bundle, standalone ones travel top-level --

    @Test
    fun `a profile-referenced layout is excluded from the top-level layouts section`() {
        val widget = HudWidget(id = "w", stat = HudStat.CPU_USAGE)
        val referenced = HudLayout(7L, "Referenced", listOf(widget))
        val standalone = HudLayout(9L, "Standalone", listOf(widget))

        // The orchestrator computes the referenced-id set from the profiles it exports and hands only the
        // remainder here, so a layout a profile points at never appears top-level as well as inside #5.
        val top = BackupCodec.standaloneLayouts(listOf(referenced, standalone), setOf(7L))
        assertEquals(listOf(standalone), top)

        val decoded = restored(
            BackupCodec.encode(
                AppSettings(), OverlayConfig(), FloatingButtonConfig(), ColorCorrection(),
                top, emptyList(), emptyList(), JSONArray(), JSONObject(),
            ),
        )
        assertEquals(listOf(9L), decoded.layouts!!.map { it.id })
    }

    @Test
    fun `a profile-referenced crosshair or colour preset is excluded from its top-level section`() {
        val referencedCrosshair = CrosshairPreset(id = 3L, name = "Ref")
        val standaloneCrosshair = CrosshairPreset(id = 4L, name = "Std")
        assertEquals(
            listOf(standaloneCrosshair),
            BackupCodec.standaloneCrosshairs(listOf(referencedCrosshair, standaloneCrosshair), setOf(3L)),
        )

        val referencedColour = ColorPreset(id = 5L, name = "Ref")
        val standaloneColour = ColorPreset(id = 6L, name = "Std")
        assertEquals(
            listOf(standaloneColour),
            BackupCodec.standaloneColours(listOf(referencedColour, standaloneColour), setOf(5L)),
        )
    }

    @Test
    fun `a standalone crosshair and colour preset survive an encode and decode`() {
        val crosshair = CrosshairPreset(
            id = 12L,
            name = "Sniper",
            design = CrosshairDesign.CIRCLE_DOT,
            sizeDp = 40,
            thicknessDp = 3,
            centreGapDp = 6,
            opacityPercent = 80,
            rotationDegrees = 45,
            colorArgb = 0xFFFF0000.toInt(),
            showDot = true,
            showOutline = false,
            xFraction = 0.4f,
            yFraction = 0.6f,
            imagePath = null,
        )
        val colour = ColorPreset(
            id = 21L,
            name = "Night",
            correction = ColorCorrection(redGain = 10, saturation = -20, invertColors = true),
        )

        val decoded = restored(
            BackupCodec.encode(
                AppSettings(), OverlayConfig(), FloatingButtonConfig(), ColorCorrection(),
                emptyList(), listOf(crosshair), listOf(colour), JSONArray(), JSONObject(),
            ),
        )
        assertEquals(listOf(crosshair.normalised()), decoded.crosshairs)
        assertEquals(listOf(colour.normalised()), decoded.colours)
    }

    @Test
    fun `content signatures are stable across a save, so a second merge of the same backup adds nothing`() {
        val widget = HudWidget(id = "w", stat = HudStat.CPU_USAGE)
        val layout = HudLayout(0L, "L", listOf(widget))
        // A save mints a new row id and fresh timestamps; the content signature ignores all three.
        val savedLayout = layout.copy(id = 55L, createdAtMillis = 999L, updatedAtMillis = 1000L)
        assertEquals(BackupCodec.hudSignature(layout), BackupCodec.hudSignature(savedLayout))

        val crosshair = CrosshairPreset(id = 0L, name = "C")
        // A save mints an id and may copy in a device-local image path; neither is part of the signature.
        val savedCrosshair = crosshair.copy(id = 88L, imagePath = "/data/user/0/app/files/c.png")
        assertEquals(BackupCodec.crosshairSignature(crosshair), BackupCodec.crosshairSignature(savedCrosshair))

        val preset = ColorPreset(id = 0L, name = "N")
        assertEquals(BackupCodec.colourSignature(preset), BackupCodec.colourSignature(preset.copy(id = 91L)))

        // A pure stand-in for BackupManager.importLayouts' create-or-match under MERGE: a row is created only
        // when no device row shares its content signature. The DB path is Android, but the decision is pure.
        fun mergeCreated(device: MutableMap<String, Long>, incoming: List<HudLayout>): Int {
            var created = 0
            var nextId = 100L
            for (l in incoming) {
                if (device.putIfAbsent(BackupCodec.hudSignature(l), nextId) == null) {
                    nextId++
                    created++
                }
            }
            return created
        }

        val incoming = listOf(layout, HudLayout(0L, "M", listOf(widget.copy(stat = HudStat.RAM_USAGE))))
        assertEquals(incoming.size, mergeCreated(HashMap(), incoming))
        // The first merge saved its rows with new ids and timestamps; a second merge of the same file matches
        // every one of them by signature and so creates nothing.
        val saved = incoming.mapIndexed { i, l -> l.copy(id = 500L + i, createdAtMillis = 7L, updatedAtMillis = 8L) }
        val deviceAfterSave = HashMap<String, Long>()
        saved.forEach { deviceAfterSave[BackupCodec.hudSignature(it)] = it.id }
        assertEquals(0, mergeCreated(deviceAfterSave, incoming))
    }

    // --- Rejection: never a throw, always a typed reason ----------------------------------------------

    @Test
    fun `an empty or blank document is rejected as empty`() {
        assertEquals(BackupCodec.RejectReason.EMPTY, rejected(null))
        assertEquals(BackupCodec.RejectReason.EMPTY, rejected(""))
        assertEquals(BackupCodec.RejectReason.EMPTY, rejected("   \n  "))
    }

    @Test
    fun `garbage or truncated JSON is rejected as malformed rather than throwing`() {
        assertEquals(BackupCodec.RejectReason.MALFORMED, rejected("not json at all"))
        assertEquals(BackupCodec.RejectReason.MALFORMED, rejected("<xml/>"))
        assertEquals(BackupCodec.RejectReason.MALFORMED, rejected("[1,2,3]"))
        assertEquals(
            BackupCodec.RejectReason.MALFORMED,
            rejected("""{"format":"${BackupCodec.FORMAT}","version":1,"settings":"""),
        )
    }

    @Test
    fun `valid JSON that is not a GameCore backup is rejected on its format tag`() {
        assertEquals(BackupCodec.RejectReason.WRONG_FORMAT, rejected("{}"))
        assertEquals(BackupCodec.RejectReason.WRONG_FORMAT, rejected("""{"format":"something.else"}"""))
    }

    @Test
    fun `a backup from another version is rejected as unsupported`() {
        assertEquals(
            BackupCodec.RejectReason.UNSUPPORTED_VERSION,
            rejected("""{"format":"${BackupCodec.FORMAT}","version":99}"""),
        )
        // A backup missing its version reads as 0, which is equally not this build's version.
        assertEquals(
            BackupCodec.RejectReason.UNSUPPORTED_VERSION,
            rejected("""{"format":"${BackupCodec.FORMAT}"}"""),
        )
    }

    // --- Absent sections are null, not empty ----------------------------------------------------------

    @Test
    fun `a partial file restores only the sections it carries`() {
        val decoded = restored(envelope(""","macros":[{"id":1,"name":"M","actions":["PILL"]}]"""))
        assertNull(decoded.settings)
        assertNull(decoded.overlay)
        assertNull(decoded.button)
        assertNull(decoded.colour)
        assertNull(decoded.layouts)
        assertNull(decoded.profiles)
        assertNotNull(decoded.macros)
    }

    @Test
    fun `a settings block with only the app record leaves overlay button and colour absent`() {
        val decoded = restored(envelope(""","settings":{"app":{"theme":"LIGHT"}}"""))
        assertNotNull(decoded.settings)
        assertEquals(ThemeChoice.LIGHT, decoded.settings!!.theme)
        assertNull(decoded.overlay)
        assertNull(decoded.button)
        assertNull(decoded.colour)
    }

    @Test
    fun `hostile but well-formed JSON with wrong-typed sections degrades to an empty restore`() {
        // Every section is a string where an object or array was expected; none must throw, and each
        // simply reads as absent rather than aborting the whole restore.
        val decoded = restored(envelope(""","settings":"x","layouts":"x","macros":"x","presets":"x""""))
        assertNull(decoded.settings)
        assertNull(decoded.layouts)
        assertNull(decoded.macros)
    }

    // --- Boundary validation: enums, clamps, dropped widgets ------------------------------------------

    @Test
    fun `an unknown enum name falls back to the model default`() {
        val decoded = restored(envelope(""","settings":{"app":{"theme":"NEON","accent":"MAGENTA"}}"""))
        assertEquals(ThemeChoice.SYSTEM, decoded.settings!!.theme)
        assertEquals(AccentChoice.CYAN, decoded.settings!!.accent)
    }

    @Test
    fun `an out-of-range numeric field is clamped into the model's range`() {
        val high = restored(envelope(""","settings":{"app":{"uiScalePercent":5000}}"""))
        assertEquals(AppSettings.MAX_UI_SCALE, high.settings!!.uiScalePercent)
        val low = restored(envelope(""","settings":{"app":{"uiScalePercent":5}}"""))
        assertEquals(AppSettings.MIN_UI_SCALE, low.settings!!.uiScalePercent)
    }

    @Test
    fun `a widget with an unknown stat is dropped and the rest of the layout kept`() {
        val decoded = restored(
            envelope(
                ""","layouts":[{"name":"L","widgets":[""" +
                    """{"id":"a","stat":"CPU_USAGE"},{"id":"b","stat":"NONSENSE"}]}]""",
            ),
        )
        val widgets = decoded.layouts!!.single().widgets
        assertEquals(1, widgets.size)
        assertEquals(HudStat.CPU_USAGE, widgets.single().stat)
    }

    @Test
    fun `a widget with a duplicate id is dropped`() {
        val decoded = restored(
            envelope(
                ""","layouts":[{"name":"L","widgets":[""" +
                    """{"id":"x","stat":"CPU_USAGE"},{"id":"x","stat":"RAM_USAGE"}]}]""",
            ),
        )
        val widgets = decoded.layouts!!.single().widgets
        assertEquals(1, widgets.size)
        assertEquals(HudStat.CPU_USAGE, widgets.single().stat)
    }

    @Test
    fun `a widget's out-of-range position and opacity are clamped`() {
        val decoded = restored(
            envelope(
                ""","layouts":[{"name":"L","widgets":[""" +
                    """{"id":"a","stat":"CPU_USAGE","xFraction":5.0,"opacityPercent":5}]}]""",
            ),
        )
        val widget = decoded.layouts!!.single().widgets.single()
        assertEquals(1f, widget.xFraction, 0f)
        assertEquals(HudWidget.MIN_OPACITY_PERCENT, widget.opacityPercent)
    }

    @Test
    fun `a layout whose name sanitises to nothing is given a default rather than dropped`() {
        val decoded = restored(envelope(""","layouts":[{"name":"   ","widgets":[]}]"""))
        assertEquals(1, decoded.layouts!!.size)
        assertTrue(decoded.layouts!!.single().name.isNotBlank())
    }

    @Test
    fun `a layout with more widgets than the cap is trimmed to the cap`() {
        val widgets = (1..HudLayout.MAX_WIDGETS + 5)
            .joinToString(",") { """{"id":"w$it","stat":"CPU_USAGE"}""" }
        val decoded = restored(envelope(""","layouts":[{"name":"L","widgets":[$widgets]}]"""))
        assertEquals(HudLayout.MAX_WIDGETS, decoded.layouts!!.single().widgets.size)
    }
}


