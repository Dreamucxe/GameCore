package com.gamecore.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Drives the two §4/§6 decision helpers directly with plain values.
 *
 * The filter is exercised with a tiny local `Row` and a `kindOf` lambda so the test proves the generic
 * contract — order preserved, each chip keeps the right kind — without dragging in [GameSession]. The
 * hero tests pin the §4 precedence, and one of them makes the load-bearing interaction explicit: rule 1
 * reads history alone, so a DISABLED but most-recently-played candidate still beats an enabled one, while
 * rule 2 only ever considers enabled candidates.
 */
class HomeAndSessionLogicTest {

    // A stand-in for a session row: just enough for kindOf, and nothing the helper knows about.
    private data class Row(val name: String, val kind: SessionKind)

    private val rows = listOf(
        Row("valorant", SessionKind.GAME),
        Row("gridshot", SessionKind.AIMLAB),
        Row("genshin", SessionKind.GAME),
        Row("tracking", SessionKind.AIMLAB),
    )

    // ------------------------------------------------------------------ §6 filter

    @Test
    fun `ALL returns every row in the original order`() {
        assertEquals(rows, filterByKind(rows, SessionFilterKind.ALL) { it.kind })
    }

    @Test
    fun `GAMES returns only game rows in order`() {
        val expected = listOf(
            Row("valorant", SessionKind.GAME),
            Row("genshin", SessionKind.GAME),
        )
        assertEquals(expected, filterByKind(rows, SessionFilterKind.GAMES) { it.kind })
    }

    @Test
    fun `AIMLAB returns only aim-lab rows in order`() {
        val expected = listOf(
            Row("gridshot", SessionKind.AIMLAB),
            Row("tracking", SessionKind.AIMLAB),
        )
        assertEquals(expected, filterByKind(rows, SessionFilterKind.AIMLAB) { it.kind })
    }

    @Test
    fun `empty input yields empty output for every filter`() {
        val empty = emptyList<Row>()
        assertEquals(empty, filterByKind(empty, SessionFilterKind.ALL) { it.kind })
        assertEquals(empty, filterByKind(empty, SessionFilterKind.GAMES) { it.kind })
        assertEquals(empty, filterByKind(empty, SessionFilterKind.AIMLAB) { it.kind })
    }

    @Test
    fun `filter preserves relative order rather than grouping by kind`() {
        // Interleaved input; GAMES must keep valorant before genshin, not regroup them.
        val interleaved = listOf(
            Row("gridshot", SessionKind.AIMLAB),
            Row("valorant", SessionKind.GAME),
            Row("tracking", SessionKind.AIMLAB),
            Row("genshin", SessionKind.GAME),
        )
        assertEquals(
            listOf(Row("valorant", SessionKind.GAME), Row("genshin", SessionKind.GAME)),
            filterByKind(interleaved, SessionFilterKind.GAMES) { it.kind },
        )
    }

    @Test
    fun `filter works with Pair and a kindOf lambda over the second component`() {
        val pairs = listOf(
            "a" to SessionKind.GAME,
            "b" to SessionKind.AIMLAB,
            "c" to SessionKind.GAME,
        )
        assertEquals(
            listOf("b" to SessionKind.AIMLAB),
            filterByKind(pairs, SessionFilterKind.AIMLAB) { it.second },
        )
    }

    // ------------------------------------------------------------------ §4 hero

    @Test
    fun `rule 1 most-recently-played beats the first enabled candidate`() {
        val candidates = listOf(
            HeroCandidate("first.enabled", isEnabled = true),
            HeroCandidate("recent", isEnabled = true),
        )
        val history = mapOf("first.enabled" to 100L, "recent" to 200L)
        assertEquals(
            HeroSelection.Profile("recent"),
            selectHero(candidates, history),
        )
    }

    @Test
    fun `rule 1 reads history alone so a disabled most-recently-played candidate still wins over an enabled one`() {
        // The explicit enabled-vs-history interaction: "recent" is DISABLED but was played last, and it
        // beats the enabled "first.enabled". Rule 1 never looks at isEnabled.
        val candidates = listOf(
            HeroCandidate("first.enabled", isEnabled = true),
            HeroCandidate("recent", isEnabled = false),
        )
        val history = mapOf("first.enabled" to 100L, "recent" to 999L)
        assertEquals(
            HeroSelection.Profile("recent"),
            selectHero(candidates, history),
        )
    }

    @Test
    fun `rule 2 no history picks the first enabled candidate skipping a disabled first entry`() {
        val candidates = listOf(
            HeroCandidate("disabled.first", isEnabled = false),
            HeroCandidate("enabled.second", isEnabled = true),
            HeroCandidate("enabled.third", isEnabled = true),
        )
        assertEquals(
            HeroSelection.Profile("enabled.second"),
            selectHero(candidates, emptyMap()),
        )
    }

    @Test
    fun `rule 3 no history and none enabled yields Empty`() {
        val candidates = listOf(
            HeroCandidate("a", isEnabled = false),
            HeroCandidate("b", isEnabled = false),
        )
        assertEquals(HeroSelection.Empty, selectHero(candidates, emptyMap()))
    }

    @Test
    fun `empty candidates yields Empty`() {
        assertEquals(HeroSelection.Empty, selectHero(emptyList(), mapOf("x" to 1L)))
    }

    @Test
    fun `a tie in lastPlayed resolves to the candidate earliest in the list`() {
        val candidates = listOf(
            HeroCandidate("earlier", isEnabled = true),
            HeroCandidate("later", isEnabled = true),
        )
        val history = mapOf("earlier" to 500L, "later" to 500L)
        assertEquals(
            HeroSelection.Profile("earlier"),
            selectHero(candidates, history),
        )
    }

    @Test
    fun `a package in history but not among the candidates is ignored`() {
        // "ghost" is the most recent thing ever played but is not a candidate, so it cannot be the hero;
        // among the actual candidates only "played" has history, so it wins over the enabled-first fallback.
        val candidates = listOf(
            HeroCandidate("enabled.first", isEnabled = true),
            HeroCandidate("played", isEnabled = true),
        )
        val history = mapOf("ghost" to 10_000L, "played" to 300L)
        assertEquals(
            HeroSelection.Profile("played"),
            selectHero(candidates, history),
        )
    }

    @Test
    fun `with no candidate in history the enabled fallback applies even when history is non-empty`() {
        // History exists but names only non-candidates, so rule 1 finds nothing and rule 2 takes over.
        val candidates = listOf(
            HeroCandidate("disabled", isEnabled = false),
            HeroCandidate("enabled", isEnabled = true),
        )
        val history = mapOf("someone.else" to 42L)
        assertEquals(
            HeroSelection.Profile("enabled"),
            selectHero(candidates, history),
        )
    }

    // -------------------------------------------------------- §4 last played, the hero's input

    @Test
    fun `last played keeps the newest session per package whatever order they arrive in`() {
        // Deliberately not sorted: the repository hands over newest-first today, and the helper must not
        // depend on that. Both orders have to produce the same answer.
        val plays = listOf(
            PlayRecord("valorant", 100L),
            PlayRecord("genshin", 900L),
            PlayRecord("valorant", 500L),
            PlayRecord("genshin", 300L),
        )
        val expected = mapOf("valorant" to 500L, "genshin" to 900L)
        assertEquals(expected, lastPlayedByPackage(plays))
        assertEquals(expected, lastPlayedByPackage(plays.reversed()))
    }

    @Test
    fun `last played of nothing is an empty map, not a map of zeroes`() {
        assertEquals(emptyMap<String, Long>(), lastPlayedByPackage(emptyList()))
    }

    @Test
    fun `last played feeds the hero rule end to end`() {
        val plays = listOf(PlayRecord("old", 10L), PlayRecord("recent", 20L), PlayRecord("old", 15L))
        val candidates = listOf(
            HeroCandidate("old", isEnabled = true),
            HeroCandidate("recent", isEnabled = false),
        )
        // "recent" wins on history alone, even though it is the disabled one — rule 1 ignores the switch.
        assertEquals(
            HeroSelection.Profile("recent"),
            selectHero(candidates, lastPlayedByPackage(plays)),
        )
    }

    // ------------------------------------------------------------------- §4.1 the header line

    @Test
    fun `a tracked game outranks the profile count in the header`() {
        assertEquals("Playing Valorant", homeSubtitle(isLoaded = true, profileCount = 4, "Valorant"))
        // Even before the profiles have loaded: the session is the more certain fact of the two.
        assertEquals("Playing Valorant", homeSubtitle(isLoaded = false, profileCount = 0, "Valorant"))
    }

    @Test
    fun `a blank game label is not a game`() {
        // GamingState carries an empty label until the game is resolved; "Playing " is not a sentence.
        assertEquals("No game profiles yet", homeSubtitle(isLoaded = true, profileCount = 0, ""))
    }

    @Test
    fun `the header says Loading rather than claiming no profiles before the first emission`() {
        assertEquals("Loading", homeSubtitle(isLoaded = false, profileCount = 0, null))
    }

    @Test
    fun `the header counts profiles once they have loaded`() {
        assertEquals("No game profiles yet", homeSubtitle(isLoaded = true, profileCount = 0, null))
        assertEquals("1 game profile", homeSubtitle(isLoaded = true, profileCount = 1, null))
        assertEquals("4 game profiles", homeSubtitle(isLoaded = true, profileCount = 4, null))
    }

    // ------------------------------------------------------------------ §4.1 the Automatic pill

    @Test
    fun `the pill watches only when auto-apply is on and something can see a game start`() {
        assertEquals(WatchState.WATCHING, watchState(autoApply = true, detectionAvailable = true))
    }

    @Test
    fun `auto-apply off is Manual whatever the detector says`() {
        assertEquals(WatchState.MANUAL, watchState(autoApply = false, detectionAvailable = true))
        assertEquals(WatchState.MANUAL, watchState(autoApply = false, detectionAvailable = false))
    }

    @Test
    fun `the only pill state that asks for attention is configured-but-blind`() {
        assertEquals(WatchState.BLIND, watchState(autoApply = true, detectionAvailable = false))
        assertEquals(true, WatchState.BLIND.needsAttention)
        assertEquals(false, WatchState.WATCHING.needsAttention)
        assertEquals(false, WatchState.MANUAL.needsAttention)
    }

    @Test
    fun `every pill state has a word, so the tint is never the only signal`() {
        WatchState.entries.forEach { state ->
            assertEquals(true, state.label.isNotBlank())
        }
    }

    // ------------------------------------------------------------------ §4.5 the status tiles

    private fun statuses(
        shizuku: ShizukuState = ShizukuState.NOT_INSTALLED,
        overlay: OverlayStatus = OverlayStatus.OFF,
        usableControlCount: Int = 3,
        controlCount: Int = 8,
        profileCount: Int = 2,
    ) = homeStatuses(shizuku, overlay, usableControlCount, controlCount, profileCount)

    private fun attention(list: List<HomeStatus>, kind: HomeStatusKind): Boolean =
        list.first { it.kind == kind }.needsAttention

    @Test
    fun `the four tiles are produced in the order the section draws them`() {
        assertEquals(
            listOf(
                HomeStatusKind.SHIZUKU,
                HomeStatusKind.OVERLAYS,
                HomeStatusKind.OPTIMIZATIONS,
                HomeStatusKind.PROFILES,
            ),
            statuses().map { it.kind },
        )
    }

    @Test
    fun `a missing optional is not a fault`() {
        // No Shizuku, no overlays up, a probe that found some usable controls, profiles configured:
        // a perfectly ordinary device, and nothing on it should be marked.
        val list = statuses()
        assertEquals(emptyList<HomeStatusKind>(), list.filter { it.needsAttention }.map { it.kind })
    }

    @Test
    fun `Shizuku is marked only while it is running and waiting on a decision`() {
        assertEquals(true, attention(statuses(ShizukuState.RUNNING_PERMISSION_UNKNOWN), HomeStatusKind.SHIZUKU))
        assertEquals(true, attention(statuses(ShizukuState.RUNNING_PERMISSION_DENIED), HomeStatusKind.SHIZUKU))
        assertEquals(false, attention(statuses(ShizukuState.NOT_INSTALLED), HomeStatusKind.SHIZUKU))
        assertEquals(false, attention(statuses(ShizukuState.INSTALLED_NOT_RUNNING), HomeStatusKind.SHIZUKU))
        assertEquals(
            false,
            attention(statuses(ShizukuState.RUNNING_PERMISSION_GRANTED), HomeStatusKind.SHIZUKU),
        )
    }

    @Test
    fun `overlays are marked only when one is wanted and the permission is missing`() {
        val wantedButBlocked = OverlayStatus(pillVisible = true, hasPermission = false)
        assertEquals(true, attention(statuses(overlay = wantedButBlocked), HomeStatusKind.OVERLAYS))

        // Permission missing but nothing switched on: nothing is being prevented, so nothing is marked.
        assertEquals(true, !attention(statuses(overlay = OverlayStatus.OFF), HomeStatusKind.OVERLAYS))

        val granted = OverlayStatus(serviceRunning = true, pillVisible = true, hasPermission = true)
        assertEquals(false, attention(statuses(overlay = granted), HomeStatusKind.OVERLAYS))
    }

    @Test
    fun `optimizations are marked when the probe found controls and none of them is usable`() {
        val none = statuses(usableControlCount = 0, controlCount = 8)
        assertEquals(true, attention(none, HomeStatusKind.OPTIMIZATIONS))
        assertEquals("0 of 8", none.first { it.kind == HomeStatusKind.OPTIMIZATIONS }.value)

        // A probe that has not produced a finding yet has nothing to warn about.
        assertEquals(
            false,
            attention(statuses(usableControlCount = 0, controlCount = 0), HomeStatusKind.OPTIMIZATIONS),
        )
    }

    @Test
    fun `no profiles is the state worth marking, one profile is not`() {
        assertEquals(true, attention(statuses(profileCount = 0), HomeStatusKind.PROFILES))
        assertEquals(false, attention(statuses(profileCount = 1), HomeStatusKind.PROFILES))
        assertEquals("1", statuses(profileCount = 1).first { it.kind == HomeStatusKind.PROFILES }.value)
    }

    @Test
    fun `every tile carries the real state as words, never an empty value`() {
        statuses(shizuku = ShizukuState.RUNNING_PERMISSION_GRANTED).forEach { status ->
            assertEquals(true, status.value.isNotBlank())
            assertEquals(true, status.kind.label.isNotBlank())
        }
    }

    // ---------------------------------------------------------------- §4.3 temperature caption

    @Test
    fun `the caption names the sensor, the classifier's word and the platform's own`() {
        assertEquals(
            "CPU sensor · Hot · System: Normal",
            temperatureCaption(ThermalSensorType.CPU, ThermalClass.HOT, ThermalStatus.NONE),
        )
        assertEquals(
            "Battery sensor · Warm · System: Moderate",
            temperatureCaption(ThermalSensorType.BATTERY, ThermalClass.WARM, ThermalStatus.MODERATE),
        )
    }

    @Test
    fun `a device that does not report a thermal status gets no invented one`() {
        assertEquals(
            "CPU sensor · Normal",
            temperatureCaption(ThermalSensorType.CPU, ThermalClass.OK, null),
        )
    }

    @Test
    fun `the caption's word is always the classifier's, so it cannot contradict the colour`() {
        // The regression the shared classifier exists for: a hot sensor while the platform still says
        // NONE. The caption prints both and takes its own word from the classification, never from the
        // platform label, so the tile can never read "Normal" in the colour of "Critical".
        val classification = ThermalClassifier.classify(
            deciCelsius = 853,
            sensor = ThermalSensorType.CPU,
            platformStatus = ThermalStatus.NONE,
        )
        val caption = temperatureCaption(ThermalSensorType.CPU, classification.level, ThermalStatus.NONE)
        assertEquals("CPU sensor · Critical · System: Normal", caption)
        assertEquals(ThermalClass.CRITICAL, classification.level)
    }
}
