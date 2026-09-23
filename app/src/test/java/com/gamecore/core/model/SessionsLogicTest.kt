package com.gamecore.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the redesigned §6 Sessions screen is allowed to say, pinned.
 *
 * Every assertion here is a rule the screen would otherwise be free to break quietly on a device
 * nobody is holding. Three of them are the reason this file exists at all:
 *
 * 1. **A figure Android never recorded is the word "Unavailable" and a reason.** All six aggregates
 *    §6's tiles are built from are nullable, and the failure mode is not a crash — it is a card that
 *    reads "0%" and looks like a measurement. Every absent case below asserts the word *and* that a
 *    reason came with it.
 * 2. **The temperature tile's word and its colour are one decision.** The tile carries a
 *    [ThermalClass] rather than a colour, so the bug the shared classifier was written for — 85.3 °C
 *    drawn red beside the word "Normal" — cannot come back through this screen.
 * 3. **The merged list is ordered across both repositories.** A sort that only understood game
 *    sessions would silently push every Aim Lab run to one end.
 */
class SessionsLogicTest {

    // ---------------------------------------------------------------- §6 game tiles

    @Test
    fun `a fully measured session fills the three tiles §6 asks for`() {
        val tiles = gameSessionTiles(
            session(
                peakTemperatureDeciCelsius = 471,
                averageTemperatureDeciCelsius = 402,
                averageMemoryPercent = 63.4f,
                peakMemoryPercent = 71f,
                averageCpuPercent = 38f,
                peakCpuPercent = 82f,
                sampleCount = 900,
            ),
        )
        assertEquals(listOf("Peak heat", "Avg. RAM", "Avg. CPU"), tiles.map { it.label })
        assertEquals(listOf("47.1°C", "63%", "38%"), tiles.map { it.value })
        assertTrue(tiles.all { it.isReading })
        assertTrue(tiles.all { it.detail.isNotBlank() })
    }

    @Test
    fun `a reading the device never took is the word and never a zero`() {
        // The ordinary Android case: no thermal zone, no memory figure, no processor statistics.
        val tiles = gameSessionTiles(session(sampleCount = 900))
        assertEquals(listOf(UNAVAILABLE_TEXT, UNAVAILABLE_TEXT, UNAVAILABLE_TEXT), tiles.map { it.value })
        assertTrue(tiles.none { it.isReading })
        // The point of the exercise: each absence says which absence it is.
        assertTrue(tiles.all { it.detail.isNotBlank() })
        assertFalse(tiles.any { it.value.contains("0") })
    }

    @Test
    fun `too few samples suppresses both averages and says how few`() {
        // Below GameSession.MIN_SAMPLES_FOR_AVERAGES the mean describes a loading screen, so the
        // figure is withheld — but the reason is a different one from "this device cannot measure it".
        val tiles = gameSessionTiles(
            session(
                averageMemoryPercent = 50f,
                averageCpuPercent = 90f,
                sampleCount = 3,
            ),
        )
        assertEquals(UNAVAILABLE_TEXT, tiles[1].value)
        assertEquals(UNAVAILABLE_TEXT, tiles[2].value)
        assertTrue(tiles[1].detail.contains("3 samples"))
        assertTrue(tiles[2].detail.contains("too few to average"))
    }

    @Test
    fun `the processor tile always says the figure is device-wide`() {
        // GameCore measures the whole device, not the game's share, and the card sits under a game's
        // name — so the qualification travels with the number whether or not a peak was recorded.
        val withPeak = gameSessionTiles(
            session(averageCpuPercent = 38f, peakCpuPercent = 82f, sampleCount = 900),
        )[2]
        val withoutPeak = gameSessionTiles(session(averageCpuPercent = 38f, sampleCount = 900))[2]
        assertTrue(withPeak.detail.contains("device-wide"))
        assertTrue(withoutPeak.detail.contains("device-wide"))
    }

    // ---------------------------------------------------------------- the temperature classification

    @Test
    fun `the heat tile's word and its class are the same verdict`() {
        val hot = gameSessionTiles(session(peakTemperatureDeciCelsius = 853))[0]
        assertEquals(ThermalClass.CRITICAL, hot.thermal)
        // The regression this classifier exists for: a critical reading must not read "Normal".
        assertTrue(hot.detail.startsWith(ThermalClass.CRITICAL.label))
        assertFalse(hot.detail.contains(ThermalClass.OK.label))
    }

    @Test
    fun `a normal temperature does not print the word, because the colour says nothing`() {
        // "Normal" on every card is a word users learn to stop reading; the word earns its place
        // from WARM upward, which is exactly where the tile stops being neutral.
        val normal = gameSessionTiles(
            session(peakTemperatureDeciCelsius = 380, averageTemperatureDeciCelsius = 351),
        )[0]
        assertEquals(ThermalClass.OK, normal.thermal)
        assertFalse(normal.detail.contains(ThermalClass.OK.label))
        assertTrue(normal.detail.contains("35.1°C"))
    }

    @Test
    fun `an absent temperature is unavailable and never an alarm`() {
        val absent = gameSessionTiles(session())[0]
        assertEquals(UNAVAILABLE_TEXT, absent.value)
        assertEquals(ThermalClass.UNAVAILABLE, absent.thermal)
    }

    @Test
    fun `a peak with no average still explains what it is`() {
        val tile = gameSessionTiles(session(peakTemperatureDeciCelsius = 366))[0]
        assertEquals("36.6°C", tile.value)
        assertTrue(tile.detail.isNotBlank())
    }

    // ---------------------------------------------------------------- §6 Aim Lab tiles

    @Test
    fun `an aim lab run fills the same three slots with its own fields`() {
        val tiles = aimLabTiles(
            modeLabel = "Flick training",
            difficultyLabel = "Hard",
            isScored = true,
            score = 4_120,
            hits = 37,
            shots = 50,
            accuracyPercent = 74,
            isLegacyScoring = false,
        )
        assertEquals(listOf("Mode", "Score", "Accuracy"), tiles.map { it.label })
        assertEquals(listOf("Flick training", "4120", "74%"), tiles.map { it.value })
        assertEquals("Hard", tiles[0].detail)
        assertTrue(tiles[2].detail.contains("37 of 50 shots"))
    }

    @Test
    fun `an unscored mode has no score rather than a zero`() {
        // Free practice is scored = false. The stored row holds 0, and 0 is not a result.
        val tiles = aimLabTiles(
            modeLabel = "Free practice",
            difficultyLabel = "Normal",
            isScored = false,
            score = 0,
            hits = 12,
            shots = 20,
            accuracyPercent = 60,
            isLegacyScoring = false,
        )
        assertEquals(UNAVAILABLE_TEXT, tiles[1].value)
        assertTrue(tiles[1].detail.contains("Free practice"))
        // Accuracy was still measured, so it is still printed: one absence does not blank the card.
        assertEquals("60%", tiles[2].value)
    }

    @Test
    fun `a run with no shots has no accuracy rather than nought percent`() {
        val tiles = aimLabTiles(
            modeLabel = "Reaction test",
            difficultyLabel = "Easy",
            isScored = true,
            score = 900,
            hits = 0,
            shots = 0,
            accuracyPercent = 0,
            isLegacyScoring = false,
        )
        assertEquals(UNAVAILABLE_TEXT, tiles[2].value)
        assertTrue(tiles[2].detail.contains("No shots"))
        assertEquals("900", tiles[1].value)
    }

    @Test
    fun `a legacy 2D score says it is not comparable`() {
        val tiles = aimLabTiles(
            modeLabel = "Flick training",
            difficultyLabel = "Normal",
            isScored = true,
            score = 4_000,
            hits = 10,
            shots = 10,
            accuracyPercent = 100,
            isLegacyScoring = true,
        )
        assertEquals("4000", tiles[1].value)
        assertTrue(tiles[1].detail.contains("2D"))
    }

    @Test
    fun `aim lab tiles carry no thermal classification`() {
        // Nothing about a training run is a temperature, and a stray class here would tint a score.
        val tiles = aimLabTiles("Gyro training", "Hard", true, 10, 1, 2, 50, false)
        tiles.forEach { assertNull(it.thermal) }
    }

    // ---------------------------------------------------------------- what the card must print

    @Test
    fun `a tile with no reading always prints its reason`() {
        // "Unavailable" on its own is the failure this rule exists to stop: it reads as a defect in
        // GameCore rather than as a fact about the device.
        gameSessionTiles(session(sampleCount = 900)).forEach { tile ->
            assertTrue(tile.label, tile.needsDetailOnScreen)
        }
    }

    @Test
    fun `a tinted temperature always prints the word beside it`() {
        // §2: never status by colour alone. WARM is where the tint starts, so it is where the word
        // must become visible rather than staying in the spoken description.
        assertTrue(gameSessionTiles(session(peakTemperatureDeciCelsius = 460))[0].needsDetailOnScreen)
        assertTrue(gameSessionTiles(session(peakTemperatureDeciCelsius = 853))[0].needsDetailOnScreen)
    }

    @Test
    fun `an ordinary reading keeps its supporting line for the reader only`() {
        // Three sentences under three tiles would turn a list row into the report screen.
        val tiles = gameSessionTiles(
            session(
                peakTemperatureDeciCelsius = 380,
                averageMemoryPercent = 63f,
                peakMemoryPercent = 70f,
                averageCpuPercent = 38f,
                peakCpuPercent = 80f,
                sampleCount = 900,
            ),
        )
        assertTrue(tiles.none { it.needsDetailOnScreen })
        assertTrue(tiles.all { it.detail.isNotBlank() })
    }

    // ---------------------------------------------------------------- what a screen reader hears

    @Test
    fun `the spoken strip keeps every reason, not just the headline`() {
        // The failure this guards: a one-line summary that says "Unavailable" and drops the sentence
        // explaining it, leaving a non-sighted user with strictly less than a sighted one.
        val spoken = tilesSentence(gameSessionTiles(session(sampleCount = 900)))
        assertEquals(3, spoken.split(UNAVAILABLE_TEXT).size - 1)
        assertTrue(spoken.contains("No temperature sensor was readable"))
        assertTrue(spoken.contains("Memory use was not recorded"))
        assertTrue(spoken.contains("Processor use was not readable"))
    }

    @Test
    fun `the spoken strip ends each tile in a full stop so it is not read as one run-on`() {
        val spoken = tilesSentence(
            gameSessionTiles(
                session(
                    peakTemperatureDeciCelsius = 471,
                    averageMemoryPercent = 63f,
                    averageCpuPercent = 38f,
                    sampleCount = 900,
                ),
            ),
        )
        assertTrue(spoken.startsWith("Peak heat 47.1°C."))
        assertTrue(spoken.endsWith("."))
        assertFalse(spoken.contains(".."))
    }

    // ---------------------------------------------------------------- ordering across both kinds
    @Test
    fun `newest first orders game sessions and aim lab runs together`() {
        val rows = listOf(
            "old game" to order(startedAtMillis = 1_000L),
            "new run" to order(startedAtMillis = 9_000L),
            "middle game" to order(startedAtMillis = 5_000L),
        )
        assertEquals(
            listOf("new run", "middle game", "old game"),
            sortSessions(rows, SessionSort.NEWEST_FIRST) { it.second }.map { it.first },
        )
        assertEquals(
            listOf("old game", "middle game", "new run"),
            sortSessions(rows, SessionSort.OLDEST_FIRST) { it.second }.map { it.first },
        )
    }

    @Test
    fun `longest first measures both kinds by their own duration`() {
        val rows = listOf(
            "short run" to order(durationMillis = 60_000L),
            "long game" to order(durationMillis = 3_600_000L),
        )
        assertEquals(
            listOf("long game", "short run"),
            sortSessions(rows, SessionSort.LONGEST_FIRST) { it.second }.map { it.first },
        )
    }

    @Test
    fun `rows with no battery rate sort last instead of being dropped`() {
        // Every Aim Lab run and every game session too short for an honest rate lands here. The user
        // asked for an order, not a filter, so the list length must not change with the sort.
        val rows = listOf(
            "run" to order(batteryPercentPerHour = null),
            "idle game" to order(batteryPercentPerHour = 0f),
            "heavy game" to order(batteryPercentPerHour = 14.2f),
        )
        val sorted = sortSessions(rows, SessionSort.HIGHEST_DRAIN) { it.second }
        assertEquals(listOf("heavy game", "idle game", "run"), sorted.map { it.first })
        assertEquals(rows.size, sorted.size)
    }

    @Test
    fun `every sort keeps every row`() {
        val rows = List(5) { index -> "row $index" to order(startedAtMillis = index.toLong()) }
        SessionSort.entries.forEach { sort ->
            assertEquals(rows.size, sortSessions(rows, sort) { it.second }.size)
        }
    }

    // ---------------------------------------------------------------- §6 chips

    @Test
    fun `the three chips are always all three, with real counts`() {
        val chips = kindChips(gameCount = 12, aimLabCount = 4)
        assertEquals(
            listOf(SessionFilterKind.ALL, SessionFilterKind.GAMES, SessionFilterKind.AIMLAB),
            chips.map { it.kind },
        )
        assertEquals(listOf("All", "Games", "Aim Lab"), chips.map { it.label })
        assertEquals(listOf(16, 12, 4), chips.map { it.count })
    }

    @Test
    fun `an empty kind still gets its chip`() {
        // A user who has never opened Aim Lab must still be able to learn the screen lists runs.
        val chips = kindChips(gameCount = 3, aimLabCount = 0)
        assertEquals(3, chips.size)
        assertEquals(0, chips.last().count)
    }

    @Test
    fun `all is the sum of the two kinds and never anything else`() {
        assertEquals(0, kindChips(0, 0).first().count)
        assertEquals(7, kindChips(7, 0).first().count)
        assertEquals(7, kindChips(0, 7).first().count)
    }

    @Test
    fun `each kind's empty state names what would fill it`() {
        SessionFilterKind.entries.forEach { kind ->
            assertTrue(emptyKindTitle(kind).isNotBlank())
            assertTrue(emptyKindMessage(kind).isNotBlank())
        }
        assertTrue(emptyKindMessage(SessionFilterKind.AIMLAB).contains("Aim Lab"))
        assertTrue(emptyKindMessage(SessionFilterKind.GAMES).contains("profile"))
    }

    // ---------------------------------------------------------------- what Clear promises

    @Test
    fun `clearing says it leaves aim lab runs alone, when there are any`() {
        // The merged list is the reason this sentence exists: the button sits above rows it will not
        // delete, and a user is entitled to know that before pressing it, not afterwards.
        val message = clearHistoryMessage(aimLabCount = 12)
        assertTrue(message.contains("12 Aim Lab runs stay untouched"))
        assertTrue(message.contains("cannot be undone"))
    }

    @Test
    fun `one run is spoken of in the singular`() {
        assertTrue(clearHistoryMessage(aimLabCount = 1).contains("1 Aim Lab run stays untouched"))
    }

    @Test
    fun `with no runs the caveat is left out rather than raising a question`() {
        val message = clearHistoryMessage(aimLabCount = 0)
        assertFalse(message.contains("Aim Lab"))
        assertTrue(message.contains("cannot be undone"))
    }

    // ---------------------------------------------------------------- the line under the title

    @Test
    fun `the subtitle says which of its five states the screen is in`() {
        assertEquals(
            "Reading the history",
            sessionsSubtitle(isLoading = true, liveLabel = null, shown = 0, total = 0, isFiltered = false),
        )
        assertEquals(
            "Nothing recorded yet",
            sessionsSubtitle(isLoading = false, liveLabel = null, shown = 0, total = 0, isFiltered = false),
        )
        assertEquals(
            "Recording Rocket Racer now",
            sessionsSubtitle(isLoading = false, liveLabel = "Rocket Racer", shown = 4, total = 4, isFiltered = false),
        )
        assertEquals(
            "23 recorded on this device",
            sessionsSubtitle(isLoading = false, liveLabel = null, shown = 23, total = 23, isFiltered = false),
        )
    }

    @Test
    fun `a filtered list shows both numbers so the history does not look smaller`() {
        assertEquals(
            "7 of 23 recorded on this device",
            sessionsSubtitle(isLoading = false, liveLabel = null, shown = 7, total = 23, isFiltered = true),
        )
    }

    @Test
    fun `loading wins over every other state`() {
        // The first frame has no totals yet; claiming "nothing recorded yet" there is the one lie
        // that makes a user with months of history think it has been wiped.
        assertEquals(
            "Reading the history",
            sessionsSubtitle(isLoading = true, liveLabel = "Rocket Racer", shown = 0, total = 0, isFiltered = true),
        )
    }

    // ---------------------------------------------------------------- the filter itself

    @Test
    fun `the kind filter keeps both repositories' rows apart`() {
        // filterByKind is HomeAndSessionLogic's and already has its own tests; this asserts the one
        // thing §6 adds — that a merged list actually round-trips through it.
        val merged = listOf(
            "game a" to SessionKind.GAME,
            "run a" to SessionKind.AIMLAB,
            "game b" to SessionKind.GAME,
        )
        assertEquals(3, filterByKind(merged, SessionFilterKind.ALL) { it.second }.size)
        assertEquals(
            listOf("game a", "game b"),
            filterByKind(merged, SessionFilterKind.GAMES) { it.second }.map { it.first },
        )
        assertEquals(
            listOf("run a"),
            filterByKind(merged, SessionFilterKind.AIMLAB) { it.second }.map { it.first },
        )
    }

    // ---------------------------------------------------------------- fixtures

    private fun session(
        peakTemperatureDeciCelsius: Int? = null,
        averageTemperatureDeciCelsius: Int? = null,
        averageMemoryPercent: Float? = null,
        peakMemoryPercent: Float? = null,
        averageCpuPercent: Float? = null,
        peakCpuPercent: Float? = null,
        sampleCount: Int = 0,
    ) = GameSession(
        id = 1L,
        packageName = "com.example.game",
        gameLabel = "Rocket Racer",
        startedAtMillis = START,
        endedAtMillis = START + 30 * 60_000L,
        batteryStartPercent = 80,
        batteryEndPercent = 68,
        averageCpuPercent = averageCpuPercent,
        peakCpuPercent = peakCpuPercent,
        averageMemoryPercent = averageMemoryPercent,
        peakMemoryPercent = peakMemoryPercent,
        averageTemperatureDeciCelsius = averageTemperatureDeciCelsius,
        peakTemperatureDeciCelsius = peakTemperatureDeciCelsius,
        sampleCount = sampleCount,
    )

    private fun order(
        startedAtMillis: Long = 0L,
        durationMillis: Long = 0L,
        batteryPercentPerHour: Float? = null,
    ) = SessionOrder(startedAtMillis, durationMillis, batteryPercentPerHour)

    private companion object {
        const val START = 1_700_000_000_000L
    }
}
