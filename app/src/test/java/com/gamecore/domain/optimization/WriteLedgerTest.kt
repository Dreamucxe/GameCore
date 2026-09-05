package com.gamecore.domain.optimization

import com.gamecore.core.shizuku.WritableSetting
import com.gamecore.data.repository.RestorePointRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether GameCore can tell its own change from one the user made.
 *
 * This is the bug reported as "if you used the option for dnd and go into the game then turn DND off
 * from your phone's noti tab, when a popup comes up it'll revert back and turn on DND again", and the
 * ledger is the whole of the fix. [OptimizationManager] reads a key's current value as its restore
 * point and then writes the profile's value over it, so the value the user chose thirty seconds ago
 * and the value GameCore wrote itself reach the next write looking identical. The popup is only the
 * trigger: it holds the foreground long enough for the session to end, and the game coming back is a
 * fresh start that applies the profile again.
 *
 * So the tests below are sequences rather than single questions. A ledger that answered each one
 * correctly in isolation and forgot in between would leave the reported behaviour exactly as it was —
 * twice over, because the session's own restore writes the recorded mode back over the manual change
 * on the way out and the next start re-asserts the profile on the way in.
 */
class WriteLedgerTest {

    @Test
    fun `a key GameCore has never written does not block the first write`() {
        val ledger = WriteLedger()
        assertFalse(ledger.tracks(DND))
        assertFalse(ledger.userChanged(DND, "ALL"))
    }

    @Test
    fun `a key still holding what GameCore left is GameCore's to write again`() {
        val ledger = WriteLedger().wrote(DND, SILENCE)
        assertTrue(ledger.tracks(DND))
        assertFalse(ledger.userChanged(DND, SILENCE))
    }

    @Test
    fun `a key holding anything else is the user's`() {
        val ledger = WriteLedger().wrote(DND, SILENCE)
        assertTrue(ledger.userChanged(DND, OFF))
    }

    /**
     * The false positive that would matter more than the bug being fixed.
     *
     * The settings provider normalises numbers, so a pinned 120 Hz reads back as "120.0". Calling that
     * a manual change would drop the refresh rate from every profile on every device that normalises,
     * and say nothing about why.
     */
    @Test
    fun `a value the provider normalised is not a manual change`() {
        val ledger = WriteLedger()
            .wrote(PEAK_RATE, "120")
            .wrote(ANIMATION, "0")
        assertFalse(ledger.userChanged(PEAK_RATE, "120.0"))
        assertFalse(ledger.userChanged(ANIMATION, "0.0"))
        assertTrue(ledger.userChanged(PEAK_RATE, "60.0"))
    }

    // ------------------------------------------------------------------- unset is a state

    @Test
    fun `a key recorded as unset is not the same as one that was never written`() {
        val ledger = WriteLedger().wrote(MIN_RATE, null)
        assertTrue(ledger.tracks(MIN_RATE))
        assertFalse(ledger.userChanged(MIN_RATE, null))
        assertTrue(ledger.userChanged(MIN_RATE, "60"))
    }

    @Test
    fun `a key GameCore set and something has since cleared is the user's`() {
        val ledger = WriteLedger().wrote(MIN_RATE, "120")
        assertTrue(ledger.userChanged(MIN_RATE, null))
    }

    // ------------------------------------------------------------------ the two sequences

    /**
     * The reported bug, as the ledger sees it.
     *
     * A claim is not consumed by being read, and that is what makes the refusal stick. At session end
     * the row is cleared — GameCore owes the user nothing on a key they have taken over — but the claim
     * survives, so the game coming back after the popup asks the same question and gets the same
     * answer. A ledger that dropped its claim once asked would restore the reported behaviour in full:
     * the next start would read "off", find nothing contradicting it, and switch Do Not Disturb on
     * again.
     */
    @Test
    fun `asking whether a key is the user's does not settle it`() {
        val ledger = WriteLedger().wrote(DND, SILENCE)
        // Once at session end, where the restore is refused and its row cleared.
        assertTrue(ledger.userChanged(DND, OFF))
        // Again when the game returns from the popup and the profile is applied afresh.
        assertTrue(ledger.userChanged(DND, OFF))
        assertTrue(ledger.tracks(DND))
    }

    /**
     * The other half, and the reason a claim cannot simply be permanent.
     *
     * A restore that went through leaves the device at the user's own pre-session value. Keeping the
     * claim would mean every later session read that value, called it a manual change, and refused the
     * profile — the fix turning into a worse version of the bug it replaced.
     */
    @Test
    fun `a key that was restored is GameCore's to write again next session`() {
        val ledger = WriteLedger()
            .wrote(DND, SILENCE)
            .released(DND)
        assertFalse(ledger.tracks(DND))
        assertFalse(ledger.userChanged(DND, OFF))
    }

    @Test
    fun `the newest write is the one the user is measured against`() {
        val ledger = WriteLedger()
            .wrote(PEAK_RATE, "60")
            .wrote(PEAK_RATE, "120")
        assertFalse(ledger.userChanged(PEAK_RATE, "120"))
        assertTrue(ledger.userChanged(PEAK_RATE, "60"))
    }

    @Test
    fun `one key being the user's says nothing about another`() {
        val ledger = WriteLedger()
            .wrote(MIN_RATE, "120")
            .wrote(PEAK_RATE, "120")
        assertTrue(ledger.userChanged(MIN_RATE, "60"))
        assertFalse(ledger.userChanged(PEAK_RATE, "120"))
    }

    /** Releasing a key nobody claimed is a no-op rather than an entry, so it stays untracked. */
    @Test
    fun `releasing a key that was never written leaves nothing behind`() {
        val ledger = WriteLedger().released(DND)
        assertFalse(ledger.tracks(DND))
    }

    /** The namespace is half the identity, exactly as it is in the restore table's primary key. */
    @Test
    fun `a settings key and a non-setting key of the same name are different keys`() {
        assertNotEquals(
            DeviceKey.of(WritableSetting.PEAK_REFRESH_RATE),
            DeviceKey.nonSetting(WritableSetting.PEAK_REFRESH_RATE.key),
        )
        assertNotEquals(
            DeviceKey.of(WritableSetting.MIN_REFRESH_RATE),
            DeviceKey.of(WritableSetting.PEAK_REFRESH_RATE),
        )
    }

    // -------------------------------------------------------------------- the shared log

    /**
     * Why [DeviceWriteLog] exists rather than a ledger field on each writer.
     *
     * [OptimizationManager] writes Do Not Disturb and the refresh rate;
     * [com.gamecore.domain.color.ColorCorrectionController] writes the display's colour keys itself.
     * Two ledgers would fix the reported bug for the first set and leave the second behaving exactly
     * as it did — and night light and greyscale are shade tiles, so they are as easy to change
     * mid-game as Do Not Disturb is.
     */
    @Test
    fun `a claim made through the shared log is there for the next reader`() {
        val log = DeviceWriteLog()
        log.wrote(DND, SILENCE)
        assertFalse(log.current().userChanged(DND, SILENCE))
        assertTrue(log.current().userChanged(DND, OFF))
        log.release(DND)
        assertFalse(log.current().tracks(DND))
    }

    /** The pair case: two keys claimed as one step, so no reader sees one of them claimed alone. */
    @Test
    fun `folding several claims into one update leaves all of them`() {
        val log = DeviceWriteLog()
        log.update { it.wrote(MIN_RATE, "120").wrote(PEAK_RATE, "120") }
        assertTrue(log.current().tracks(MIN_RATE))
        assertTrue(log.current().tracks(PEAK_RATE))
    }

    private companion object {
        val DND = DeviceKey.nonSetting(RestorePointRepository.KEY_DO_NOT_DISTURB)
        val MIN_RATE = DeviceKey.of(WritableSetting.MIN_REFRESH_RATE)
        val PEAK_RATE = DeviceKey.of(WritableSetting.PEAK_REFRESH_RATE)
        val ANIMATION = DeviceKey.of(WritableSetting.WINDOW_ANIMATION_SCALE)

        /** As the restore table stores an interruption filter: the enum's own name. */
        const val SILENCE = "TOTAL_SILENCE"
        const val OFF = "ALL"
    }
}
