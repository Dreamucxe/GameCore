package com.gamecore.core.backup

import com.gamecore.aimlab.engine.Difficulty
import com.gamecore.aimlab.engine.PersonalRecord
import com.gamecore.aimlab.engine.ReactionStats
import com.gamecore.aimlab.engine.RecordMetric
import com.gamecore.aimlab.engine.SessionSummary
import com.gamecore.aimlab.engine.TrainingMode
import com.gamecore.core.model.AccentChoice
import com.gamecore.core.model.AnimationsMode
import com.gamecore.core.model.AppSettings
import com.gamecore.core.model.ColorCorrection
import com.gamecore.core.model.ColorPreset
import com.gamecore.core.model.ColorVisionFilter
import com.gamecore.core.model.CrosshairDesign
import com.gamecore.core.model.CrosshairPreset
import com.gamecore.core.model.DockActionId
import com.gamecore.core.model.DockActions
import com.gamecore.core.model.DockConfig
import com.gamecore.core.model.FloatingButtonConfig
import com.gamecore.core.model.GammaMode
import com.gamecore.core.model.GameSession
import com.gamecore.core.model.HudDisplayMode
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
            showBuiltInHomeStats = false,
            uiScalePercent = 110,
            animations = AnimationsMode.OFF,
            hapticsEnabled = false,
            latencyHost = "8.8.8.8",
            showResolutionOverrideNotice = false,
            showConfigEditNotice = false,
            showInstantReplayNotice = false,
            neverKillPackages = listOf("com.example.game"),
            aimLabHorizontalFovDegrees = 100,
            advancedHudEnabled = false,
            networkStabilityEnabled = false,
            customModulesEnabled = false,
            screenExtractionEnabled = false,
            touchSamplingEnabled = false,
            volumePointTriggerEnabled = false,
            dockCustomizationEnabled = false,
            quickActionsEnabled = false,
            lastSeenWhatsNewVersionCode = 25,
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
            hudDisplayMode = HudDisplayMode.COMPACT,
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
        // A deliberately rearranged dock: a non-default order, two controls switched off, and a landscape
        // placement — so the assertion below would fail if any one of the three were dropped in transit.
        val dockRearranged = listOf(DockActionId.SCREENSHOT, DockActionId.CROSSHAIR, DockActionId.MAGNIFIER)
        val dock = DockConfig(
            show = true,
            actionOrder = dockRearranged + DockActionId.entries.filterNot { it in dockRearranged },
            hiddenActions = setOf(DockActionId.WHEELS, DockActionId.HUNT),
            sizeDp = 52,
            opacity = 0.75f,
            snapToEdge = false,
            hapticFeedback = false,
            panelWidthDp = 260,
            x = 12,
            y = 340,
            landscapeXFraction = 0.8f,
            landscapeYFraction = 0.35f,
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
                settings, overlay, button, dock, colour, listOf(layout),
                emptyList(), emptyList(), macros, profiles,
            ),
        )

        assertEquals(settings.normalised(), decoded.settings)
        assertEquals(overlay.normalised(), decoded.overlay)
        assertEquals(button.normalised(), decoded.button)
        assertEquals(dock.normalised(), decoded.dock)
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
                AppSettings(), OverlayConfig(), FloatingButtonConfig(), DockConfig(), ColorCorrection(),
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
                AppSettings(), OverlayConfig(), FloatingButtonConfig(), DockConfig(), ColorCorrection(),
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
        assertNull(decoded.dock)
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
        // A file from before this option existed restores the default: Home's own tiles stay on, so
        // the setting is opt-out and an upgrade never silently hides readings.
        assertTrue(decoded.settings!!.showBuiltInHomeStats)
        assertNull(decoded.overlay)
        assertNull(decoded.button)
        assertNull(decoded.dock)
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
    fun `a dock section with no arrangement keys falls back to the shipped default panel`() {
        // What a hand-trimmed file looks like, and the shape every pre-3.7.1 backup has for the dock:
        // placement without an arrangement. The user must end up with the five controls the dock shipped
        // with, not an empty panel.
        val decoded = restored(envelope(""","settings":{"dock":{"show":true,"sizeDp":50}}"""))
        val dock = decoded.dock!!
        assertEquals(DockActionId.DEFAULT_ORDER, dock.actionOrder)
        assertEquals(DockActionId.DEFAULT_HIDDEN, dock.hiddenActions)
        assertEquals(DockActionId.DEFAULT_VISIBLE, dock.visibleActions())
        assertEquals(50, dock.sizeDp)
    }

    @Test
    fun `an unknown dock action name is dropped and the order still comes out complete`() {
        val decoded = restored(
            envelope(
                ""","settings":{"dock":{"actionOrder":["SCREENSHOT","TELEPORT","CROSSHAIR"],""" +
                    """"hiddenActions":["WHEELS","TELEPORT"]}}""",
            ),
        )
        val dock = decoded.dock!!
        // The two it knows keep the position the file gave them; the junk one is gone; everything the file
        // never mentioned is appended, so the stored order is a complete permutation either way.
        assertEquals(listOf(DockActionId.SCREENSHOT, DockActionId.CROSSHAIR), dock.actionOrder.take(2))
        assertEquals(DockActionId.entries.size, dock.actionOrder.size)
        assertEquals(DockActionId.entries.toSet(), dock.actionOrder.toSet())
        assertEquals(setOf(DockActionId.WHEELS), dock.hiddenActions)
    }

    @Test
    fun `an empty hidden list is honoured as every action shown, unlike an absent one`() {
        // The absent-versus-empty distinction: absent means "this file predates dock customisation" and
        // falls back to the defaults, while an empty array is a real choice the user made.
        val decoded = restored(envelope(""","settings":{"dock":{"hiddenActions":[]}}"""))
        val dock = decoded.dock!!
        assertTrue(dock.hiddenActions.isEmpty())
        assertEquals(DockActions.MAX_VISIBLE, dock.visibleActions().size)
    }

    @Test
    fun `a dock size and opacity outside the model's range are clamped`() {
        val high = restored(envelope(""","settings":{"dock":{"sizeDp":9000,"opacity":4.0}}"""))
        assertEquals(DockConfig.MAX_SIZE_DP, high.dock!!.sizeDp)
        assertEquals(DockConfig.MAX_OPACITY, high.dock!!.opacity, DELTA)
        val low = restored(envelope(""","settings":{"dock":{"sizeDp":1,"opacity":-1.0}}"""))
        assertEquals(DockConfig.MIN_SIZE_DP, low.dock!!.sizeDp)
        assertEquals(DockConfig.MIN_OPACITY, low.dock!!.opacity, DELTA)
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

    // --- Version 2 history ------------------------------------------------------------------------

    /** A finished game session with every field the conditions feature added actually set. */
    private val finishedGameSession = GameSession(
        id = 42L,
        packageName = "com.example.game",
        gameLabel = "Example Game",
        startedAtMillis = 1_700_000_000_000L,
        endedAtMillis = 1_700_000_600_000L,
        batteryStartPercent = 80,
        batteryEndPercent = 64,
        wasCharging = false,
        averageCpuPercent = 41.5f,
        averageTemperatureDeciCelsius = 395,
        averageRefreshRate = 120f,
        averageFrameRate = 118.5f,
        profileApplied = true,
        sampleCount = 300,
        thermalStatus = "MODERATE",
        powerSaver = true,
        refreshCapped = false,
    )

    private val finishedAimLabRun = SessionSummary(
        id = 7L,
        mode = TrainingMode.FLICK,
        difficulty = Difficulty.NORMAL,
        startedAtMillis = 1_700_000_700_000L,
        endedAtMillis = 1_700_000_760_000L,
        weaponName = "Rifle",
        score = 1234,
        hits = 88,
        shots = 100,
        targetsMissed = 5,
        reactionStats = ReactionStats(
            attempts = 3,
            fastestMillis = 210L,
            slowestMillis = 380L,
            averageMillis = 280f,
            medianMillis = 275f,
        ),
        averageAcquireMillis = 0.5f,
        trackingErrorAverage = 0.25f,
        timeOnTargetFraction = 0.75f,
        recoilCompensation = 0.5f,
        gyroStability = 0.25f,
    )

    private val personalRecord = PersonalRecord(
        mode = TrainingMode.FLICK,
        difficulty = Difficulty.NORMAL,
        weaponName = "Rifle",
        metric = RecordMetric.SCORE,
        value = 1234f,
        previousValue = 1000f,
        achievedAtMillis = 1_700_000_760_000L,
    )

    @Test
    fun `a version 2 file round trips finished history and the conditions it ran under`() {
        val json = BackupCodec.encode(
            settings = AppSettings(),
            overlay = OverlayConfig(),
            button = FloatingButtonConfig(),
            dock = DockConfig(),
            colour = ColorCorrection(),
            layouts = emptyList(),
            crosshairs = emptyList(),
            colours = emptyList(),
            macros = JSONArray(),
            profiles = JSONObject(),
            sessions = listOf(finishedGameSession),
            aimLabSessions = listOf(finishedAimLabRun),
            personalRecords = listOf(personalRecord),
        )

        val data = restored(json)

        assertEquals(BackupCodec.VERSION, data.version)
        assertEquals(listOf(finishedGameSession), data.sessions)
        assertEquals(listOf(finishedAimLabRun), data.aimLabSessions)
        assertEquals(listOf(personalRecord), data.personalRecords)
    }

    @Test
    fun `a version 2 file with no history writes empty sections and reads them back as empty`() {
        val json = BackupCodec.encode(
            settings = AppSettings(),
            overlay = OverlayConfig(),
            button = FloatingButtonConfig(),
            dock = DockConfig(),
            colour = ColorCorrection(),
            layouts = emptyList(),
            crosshairs = emptyList(),
            colours = emptyList(),
            macros = JSONArray(),
            profiles = JSONObject(),
        )

        val data = restored(json)

        assertTrue(data.sessions.isEmpty())
        assertTrue(data.aimLabSessions.isEmpty())
        assertTrue(data.personalRecords.isEmpty())
    }

    @Test
    fun `a version 1 file carries no history and decodes with empty history rather than failing`() {
        // The whole of a version 1 document that matters here: the format tag and its version. Every
        // section it predates must default to absent-or-empty, not throw — an old backup is still a backup.
        val data = restored("""{"format":"${BackupCodec.FORMAT}","version":1}""")

        assertEquals(1, data.version)
        assertTrue(data.sessions.isEmpty())
        assertTrue(data.aimLabSessions.isEmpty())
        assertTrue(data.personalRecords.isEmpty())
    }

    @Test
    fun `history rows that never finished are dropped rather than restored as running`() {
        val data = restored(
            envelope(
                ""","sessions":[{"packageName":"com.example.game","startedAtMillis":1700000000000},""" +
                    """{"packageName":"com.example.game","startedAtMillis":1700000500000,""" +
                    """"endedAtMillis":1700000400000}]""",
            ),
        )

        // One has no finish time and the other ends before it starts; neither is a finished session, and
        // a loader that let either through would create a permanently "running" row from a hand-edited file.
        assertTrue(data.sessions.isEmpty())
    }

    @Test
    fun `absent condition fields decode as null rather than fabricated defaults`() {
        val data = restored(
            envelope(
                ""","sessions":[{"packageName":"com.example.game","startedAtMillis":1700000000000,""" +
                    """"endedAtMillis":1700000060000,"batteryStartPercent":50}]""",
            ),
        )

        val session = data.sessions.single()
        // The device that recorded this file exposed no thermal or power reading; null is that truth, and
        // false would be a claim the device never made.
        assertNull(session.thermalStatus)
        assertNull(session.powerSaver)
        assertNull(session.refreshCapped)
    }

    @Test
    fun `an unknown Aim Lab mode drops the row rather than guessing one`() {
        val data = restored(
            envelope(
                ""","aimLabSessions":[{"mode":"NOT_A_MODE","startedAtMillis":1700000000000,""" +
                    """"endedAtMillis":1700000060000}]""",
            ),
        )

        // A mode this build does not know cannot be mapped to one it does; dropping the row is the only
        // option that does not invent a training result.
        assertTrue(data.aimLabSessions.isEmpty())
    }

    // --- Version 3 device snapshot ---------------------------------------------------------------------

    /** The minimum encode call: every configuration section present but empty, so only the device varies. */
    private fun encodeWith(device: DeviceSnapshot? = null): String = BackupCodec.encode(
        AppSettings(),
        OverlayConfig(),
        FloatingButtonConfig(),
        DockConfig(),
        ColorCorrection(),
        emptyList(),
        emptyList(),
        emptyList(),
        JSONArray(),
        JSONObject(),
        device = device,
    )

    @Test
    fun `a device snapshot survives an encode and decode`() {
        val device = DeviceSnapshot(
            manufacturer = "Google",
            model = "Pixel 7",
            sdkInt = 34,
            panelWidthPixels = 1080,
            panelHeightPixels = 2400,
            peakRefreshRateHz = 120f,
        )

        val data = restored(encodeWith(device = device))

        val decoded = data.device
        assertNotNull(decoded)
        assertEquals("Google", decoded!!.manufacturer)
        assertEquals("Pixel 7", decoded.model)
        assertEquals(34, decoded.sdkInt)
        assertEquals(1080, decoded.panelWidthPixels)
        assertEquals(2400, decoded.panelHeightPixels)
        assertEquals(120f, decoded.peakRefreshRateHz!!, DELTA)
        assertEquals(3, data.version)
    }

    @Test
    fun `a file with no device section reads as unknown origin rather than a guessed one`() {
        // The v1/v2 shape: no `device` key at all.
        val data = restored(envelope(""))

        assertNull(data.device)
    }

    @Test
    fun `a v2 file still restores its history with an unknown origin`() {
        val json = """{"format":"${BackupCodec.FORMAT}","version":2,"sessions":[]}"""

        val data = restored(json)

        assertNull(data.device)
        assertTrue(data.sessions.isEmpty())
    }

    @Test
    fun `encoding without a snapshot writes no device key`() {
        val json = encodeWith()

        assertTrue(!JSONObject(json).has("device"))
    }

    @Test
    fun `a device section with neither name nor model reads as absent`() {
        val data = restored(envelope(""","device":{"sdkInt":34}"""))

        assertNull(data.device)
    }

    @Test
    fun `a hostile device model is bounded and stripped`() {
        val longModel = "A".repeat(400) + "\u0000\n"
        val json = encodeWith(DeviceSnapshot(manufacturer = "M", model = longModel))

        val decoded = restored(json).device
        assertNotNull(decoded)
        assertTrue(decoded!!.model.length <= 48)
        assertTrue(!decoded.model.contains("\u0000"))
        assertTrue(!decoded.model.contains("\n"))
    }

    @Test
    fun `a negative or non-finite panel reading is not carried as a measurement`() {
        val json = envelope(
            ""","device":{"manufacturer":"M","model":"X","panelWidth":-1080,""" +
                """"panelHeight":-2400,"peakRefreshHz":"NaN"}""",
        )

        val decoded = restored(json).device
        assertNotNull(decoded)
        assertEquals(0, decoded!!.panelWidthPixels)
        assertEquals(0, decoded.panelHeightPixels)
        assertNull(decoded.peakRefreshRateHz)
    }

    @Test
    fun `the device section does not disturb the other sections`() {
        val device = DeviceSnapshot(manufacturer = "M", model = "X", sdkInt = 33)
        val json = encodeWith(device)

        val parsed = JSONObject(json)
        assertEquals(BackupCodec.VERSION, parsed.getInt("version"))
        assertEquals(BackupCodec.FORMAT, parsed.getString("format"))
        assertTrue(parsed.has("settings") || parsed.has("profiles"))
    }

    private companion object {
        /** The dock's opacity comes off a coerceIn, so compare with a delta rather than bit-exactly. */
        const val DELTA = 1e-6f
    }
}


