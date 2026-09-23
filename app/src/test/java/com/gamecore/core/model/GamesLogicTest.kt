package com.gamecore.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The §5 Games screen's claims, proved off-device.
 *
 * Three groups of these matter more than the rest and are worth saying out loud:
 *
 *  - **[profileClaim]** must keep reading `changesNothing` directly. [profileChips]' own KDoc warns that
 *    the chip list and `changesNothing` deliberately disagree on a fresh profile, and the tempting
 *    simplification — "no chips means it writes nothing" — is wrong in both directions. There are tests
 *    below that fail if anyone makes it.
 *  - **[deleteProfileMessage]** is held to the audit's §3 finding: the delete is one row of
 *    `game_profiles`, no cascade, nothing of the game's own touched. The tests assert the required §5
 *    sentence is present *and* that the wording does not over-claim, because over-claiming is the failure
 *    mode that actually costs the user something.
 *  - **[gameCardState]** is the word that keeps the dimmed card from being status-by-alpha alone, so its
 *    precedence is pinned rather than left to whatever order a `when` happens to be written in.
 */
class GamesLogicTest {

    /** A profile that writes nothing, shows nothing and records nothing — the genuine zero. */
    private fun bare(): GameProfile = GameProfile.forGame("com.example.game", "Example").copy(
        showFloatingButton = false,
        showPerformancePill = false,
        showCrosshair = false,
        trackSession = false,
    )

    private fun chip(text: String, kind: ProfileChipKind = ProfileChipKind.DEVICE) =
        ProfileChip(text, kind)

    // --------------------------------------------------------------------------------- card state

    @Test
    fun `an enabled installed profile that is not running reads On`() {
        assertEquals(
            GameCardState.ON,
            gameCardState(isEnabled = true, isInstalled = true, isPlaying = false),
        )
    }

    @Test
    fun `a switched-off profile reads Off, so dimming is never the only signal`() {
        val state = gameCardState(isEnabled = false, isInstalled = true, isPlaying = false)
        assertEquals(GameCardState.OFF, state)
        assertEquals("Off", state.label)
    }

    @Test
    fun `an uninstalled game outranks the switch, because it explains more`() {
        assertEquals(
            GameCardState.NOT_INSTALLED,
            gameCardState(isEnabled = true, isInstalled = false, isPlaying = false),
        )
        assertEquals(
            GameCardState.NOT_INSTALLED,
            gameCardState(isEnabled = false, isInstalled = false, isPlaying = false),
        )
    }

    /** What the device is doing now beats what the profile is configured to do. */
    @Test
    fun `playing outranks every other state`() {
        assertEquals(
            GameCardState.PLAYING,
            gameCardState(isEnabled = false, isInstalled = true, isPlaying = true),
        )
    }

    @Test
    fun `every card state carries a non-blank word`() {
        GameCardState.entries.forEach { state ->
            assertTrue("${state.name} needs a word, not just a colour", state.label.isNotBlank())
        }
    }

    // ------------------------------------------------------------------------------ profile claim

    @Test
    fun `a profile that writes nothing and shows nothing does nothing`() {
        val profile = bare()
        assertTrue(profile.changesNothing)
        assertTrue(profileChips(profile).isEmpty())
        assertEquals(
            ProfileClaim.DOES_NOTHING,
            profileClaim(profileChips(profile).size, profile.changesNothing),
        )
    }

    /**
     * The case the whole function exists for: a brand-new profile raises the floating button, so it has a
     * chip *and* `changesNothing == true`. Inferring "writes nothing" from an empty chip list would
     * misreport this as an ordinary settings-writing profile.
     */
    @Test
    fun `a fresh profile shows an overlay while writing no device setting`() {
        val fresh = GameProfile.forGame("com.example.game", "Example").copy(trackSession = false)
        assertTrue("forGame is expected to write nothing", fresh.changesNothing)
        assertEquals(1, profileChips(fresh).size)
        assertEquals(
            ProfileClaim.NO_DEVICE_WRITES,
            profileClaim(profileChips(fresh).size, fresh.changesNothing),
        )
    }

    /**
     * Freeing RAM is emphatically not "nothing" — it closes the user's other apps — and
     * [GameProfile.changesNothing] says so: it is **false** for a profile that frees RAM, which is the
     * shipped rule this redesign is not allowed to reinterpret.
     *
     * So the card must not reach [ProfileClaim.NO_DEVICE_WRITES] here, and that is the right way round.
     * That state's whole payload is the sentence "there is nothing to put back afterwards" — a promise
     * about restoring, and closing six of the user's background apps is precisely the effect no restore
     * puts back. The card says what happened through its "Frees RAM" chip and adds no caveat under it,
     * rather than offering a reassurance it cannot honour.
     */
    @Test
    fun `freeing RAM is never called a profile that changes nothing`() {
        val profile = bare().copy(freeRamOnLaunch = true)
        assertFalse("closing the user's other apps is not a no-op", profile.changesNothing)

        val claim = profileClaim(profileChips(profile).size, profile.changesNothing)
        assertEquals(ProfileClaim.WRITES_SETTINGS, claim)
        assertNull("a freed-RAM profile must not be promised a restore", profileClaimNote(claim))

        // The effect is still reported — it is carried by the chip, not by the claim.
        assertTrue(profileChips(profile).any { it.text == "Frees RAM" })
    }

    @Test
    fun `a profile that sets a refresh rate writes settings`() {
        val profile = bare().copy(targetRefreshRate = 120f)
        assertFalse(profile.changesNothing)
        assertEquals(
            ProfileClaim.WRITES_SETTINGS,
            profileClaim(profileChips(profile).size, profile.changesNothing),
        )
    }

    /**
     * The guard against the tempting simplification, stated as an input pair rather than a profile: a
     * caller passing "writes settings" must get [ProfileClaim.WRITES_SETTINGS] whatever the chip count is,
     * so nobody can reintroduce a chip-count shortcut without this failing.
     */
    @Test
    fun `chip count never overrides changesNothing`() {
        assertEquals(ProfileClaim.WRITES_SETTINGS, profileClaim(chipCount = 0, changesNothing = false))
        assertEquals(ProfileClaim.NO_DEVICE_WRITES, profileClaim(chipCount = 9, changesNothing = true))
    }

    @Test
    fun `only the two caveat states get a note, and the ordinary case stays silent`() {
        assertNotNull(profileClaimNote(ProfileClaim.DOES_NOTHING))
        assertNotNull(profileClaimNote(ProfileClaim.NO_DEVICE_WRITES))
        assertNull(profileClaimNote(ProfileClaim.WRITES_SETTINGS))
    }

    /** "Nothing to put back" is the promise a writing profile must never be given by accident. */
    @Test
    fun `the no-device-writes note says there is nothing to restore`() {
        val note = profileClaimNote(ProfileClaim.NO_DEVICE_WRITES).orEmpty()
        assertTrue(note, note.contains("nothing to put back"))
    }

    // ---------------------------------------------------------------------------- chips and overflow

    @Test
    fun `a short chip list is shown whole with no overflow`() {
        val chips = listOf(chip("120 Hz"), chip("Do not disturb"))
        val shown = shownChips(chips, max = 6)
        assertEquals(chips, shown.shown)
        assertEquals(0, shown.overflow)
    }

    @Test
    fun `a long chip list keeps the leading chips and counts the rest`() {
        val chips = (1..10).map { chip("Effect $it") }
        val shown = shownChips(chips, max = 6)
        assertEquals(6, shown.shown.size)
        assertEquals(4, shown.overflow)
        assertEquals("Effect 1", shown.shown.first().text)
        assertEquals("Effect 6", shown.shown.last().text)
    }

    /** Reading order comes from [profileChips] and the cut must not disturb it. */
    @Test
    fun `the cut preserves the chip generator's order`() {
        val profile = bare().copy(
            targetRefreshRate = 120f,
            showPerformancePill = true,
            freeRamOnLaunch = true,
            trackSession = true,
        )
        val all = profileChips(profile)
        val shown = shownChips(all, max = 2)
        assertEquals(all.take(2), shown.shown)
        assertEquals(all.size - 2, shown.overflow)
    }

    @Test
    fun `a nonsensical cap still shows one chip rather than none`() {
        val chips = (1..4).map { chip("Effect $it") }
        assertEquals(1, shownChips(chips, max = 0).shown.size)
        assertEquals(3, shownChips(chips, max = 0).overflow)
        assertEquals(1, shownChips(chips, max = -5).shown.size)
    }

    @Test
    fun `an empty chip list has nothing to show and nothing to count`() {
        val shown = shownChips(emptyList(), max = 6)
        assertTrue(shown.shown.isEmpty())
        assertEquals(0, shown.overflow)
    }

    @Test
    fun `the spoken sentence lists every visible chip in order`() {
        val chips = listOf(chip("120 Hz"), chip("Stats pill", ProfileChipKind.OVERLAY))
        assertEquals("120 Hz, Stats pill", chipsSentence(chips))
    }

    @Test
    fun `there is no sentence to speak when there are no chips`() {
        assertNull(chipsSentence(emptyList()))
    }

    // ------------------------------------------------------------------------------ delete dialog

    @Test
    fun `the delete dialog asks the question §5 specifies`() {
        assertEquals("Delete profile?", DELETE_PROFILE_TITLE)
    }

    @Test
    fun `the delete dialog carries the required sentence verbatim`() {
        val message = deleteProfileMessage("Example")
        assertTrue(message, message.contains("This will remove the profile from your list."))
    }

    @Test
    fun `the delete dialog names the profile it is about`() {
        assertTrue(deleteProfileMessage("Rocket Racer").contains("Rocket Racer"))
    }

    /**
     * Audit §3: recorded sessions are not cascaded away with the profile, and the user about to tap a
     * destructive button is entitled to know that before they tap it.
     */
    @Test
    fun `the delete dialog says recorded sessions are kept`() {
        val message = deleteProfileMessage("Example").lowercase()
        assertTrue(message, message.contains("session") && message.contains("kept"))
    }

    /**
     * Audit §3: the delete touches one row of `game_profiles`. GameCore cannot see the game's own files
     * and never asks to, so the dialog must not suggest otherwise. These are the phrases that would mean
     * it had started to.
     */
    @Test
    fun `the delete dialog never claims to touch the game or its data`() {
        val message = deleteProfileMessage("Example").lowercase()
        listOf(
            "delete the game",
            "uninstall",
            "game data",
            "save data",
            "progress",
            "erase everything",
            "all data",
        ).forEach { overclaim ->
            assertFalse("dialog must not claim: $overclaim", message.contains(overclaim))
        }
        assertTrue(message, message.contains("untouched"))
    }

    // ----------------------------------------------------------------------------------- subtitle

    @Test
    fun `the subtitle says Loading before the first emission, never zero profiles`() {
        assertEquals("Loading", gamesSubtitle(isLoaded = false, profileCount = 0, enabledCount = 0))
    }

    @Test
    fun `an empty loaded list says so plainly`() {
        assertEquals(
            "Nothing configured yet",
            gamesSubtitle(isLoaded = true, profileCount = 0, enabledCount = 0),
        )
    }

    @Test
    fun `the enabled count is dropped when everything is on`() {
        assertEquals("4 profiles", gamesSubtitle(isLoaded = true, profileCount = 4, enabledCount = 4))
        assertEquals("1 profile", gamesSubtitle(isLoaded = true, profileCount = 1, enabledCount = 1))
    }

    @Test
    fun `a partly switched-off list reports how many are on`() {
        assertEquals("4 profiles · 2 on", gamesSubtitle(isLoaded = true, profileCount = 4, enabledCount = 2))
    }
}
