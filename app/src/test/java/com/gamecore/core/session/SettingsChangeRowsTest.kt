package com.gamecore.core.session

import com.gamecore.core.model.OptimizationAction
import com.gamecore.core.shizuku.WritableSetting
import com.gamecore.data.repository.PendingRestore
import com.gamecore.data.repository.RestorePointRepository
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mid-session settings-change card's mapping (§3.7.1, feature 7).
 *
 * The card lists what the applied profile changed on the device and offers a per-row revert, and the one
 * rule it has to obey is the user's: *do not show a row for a setting the restore ledger cannot actually
 * revert; no fake data.* That rule is a claim about `OptimizationManager.restoreOne`, which is a
 * suspending function wired to a shell, an `AudioManager`, a notification-policy call and a sysfs node —
 * none of which a JVM test can reach. So the claim is pinned here instead, on the pure mapping, one
 * assertion per refusal the restore path makes, and each test below is the bug that would follow if the
 * mapping and the restore path disagreed:
 *
 *  - A row the restore path refuses, shown anyway, is a revert button that can only ever print a failure
 *    sentence.
 *  - A row the restore path can discharge, dropped, is a device GameCore has changed and will not admit
 *    to until the session ends.
 *  - A value rendered from a scale the ledger does not record is a figure the user reads as fact — the
 *    reason `screen_brightness` appears as "128" here and not as a percentage.
 *
 * Hand-written [PendingRestore] rows throughout: the ledger row is a plain data class, so a fake is a
 * constructor call, and nothing here needs Room, Robolectric or a mocking framework — none of which is on
 * this module's unit-test classpath.
 */
class SettingsChangeRowsTest {

    // ----------------------------------------------------------------------- every settings key is shown

    /**
     * All 19 allow-listed keys survive the mapping, because `restoreSetting` writes
     * `previousValue ?: setting.restoreDefault` and so always has something to put back.
     *
     * Asserted over [WritableSetting.entries] rather than key by key so that a twentieth key cannot be
     * added, given `-> false` in the exhaustive `when`, and quietly disappear from the card.
     */
    @Test
    fun `every settings key GameCore may write produces a row`() {
        val ledger = WritableSetting.entries.mapIndexed { index, setting ->
            settingsRow(setting, recordedAtMillis = index.toLong())
        }

        val rows = SettingsChangeRows.from(ledger)

        assertEquals(WritableSetting.entries.size, rows.size)
        assertTrue(rows.all { it.revertable })
    }

    /** The row carries the table's primary key, which is the least that names exactly one row. */
    @Test
    fun `a settings row is identified by the namespace and key the ledger stores`() {
        val row = SettingsChangeRows.rowFor(settingsRow(WritableSetting.PEAK_REFRESH_RATE))

        assertEquals("system", row.namespace)
        assertEquals("peak_refresh_rate", row.key)
    }

    /**
     * The label is the setting's own description and not the action's.
     *
     * `SET_BRIGHTNESS` writes two keys, so an action label would put the same words on both rows and
     * leave the user guessing which revert button did what.
     */
    @Test
    fun `a settings row is labelled with the setting's own description`() {
        val level = SettingsChangeRows.rowFor(settingsRow(WritableSetting.SCREEN_BRIGHTNESS))
        val mode = SettingsChangeRows.rowFor(settingsRow(WritableSetting.SCREEN_BRIGHTNESS_MODE))

        assertEquals(WritableSetting.SCREEN_BRIGHTNESS.userDescription, level.label)
        assertEquals(WritableSetting.SCREEN_BRIGHTNESS_MODE.userDescription, mode.label)
        assertTrue(level.label != mode.label)
    }

    // ----------------------------------------------------------------------- what the ledger cannot revert

    /**
     * A settings-namespace row whose key this build has dropped from the allow-list produces nothing.
     *
     * The restore table's own KDoc claims such a row is still restorable — *"namespace and key are all
     * `settings put` needs"* — and `restoreOne` disagrees: a null `setting` falls into a `when (row.key)`
     * that knows only the five non-setting keys and ends at *"This version of GameCore does not know how
     * to restore …"*. No `settings put` is ever assembled from the raw strings. The source wins, and this
     * test is what stops the comment winning later.
     */
    @Test
    fun `a settings key this build no longer knows produces no row`() {
        val dropped = PendingRestore(
            namespace = "secure",
            key = "a_key_a_later_build_removed",
            previousValue = "1",
            packageName = GAME,
            recordedAtMillis = 0L,
            setting = null,
        )

        assertFalse(SettingsChangeRows.isRevertable(dropped))
        assertEquals(emptyList<SettingsChangeRow>(), SettingsChangeRows.from(listOf(dropped)))
    }

    /** A non-setting key a future build records without extending [NonSettingChange] is dropped too. */
    @Test
    fun `a non-setting key with no restore branch produces no row`() {
        val unknown = nonSettingRow("some_future_device_knob", previousValue = "1")

        assertFalse(SettingsChangeRows.isRevertable(unknown))
        assertEquals(emptyList<SettingsChangeRow>(), SettingsChangeRows.from(listOf(unknown)))
    }

    /** `restoreOne` refuses: *"No media volume was recorded to go back to."* */
    @Test
    fun `a media volume row with nothing recorded to go back to produces no row`() {
        val nothing = nonSettingRow(RestorePointRepository.KEY_MEDIA_VOLUME, previousValue = null)
        val unparseable = nonSettingRow(RestorePointRepository.KEY_MEDIA_VOLUME, previousValue = "loud")

        assertEquals(emptyList<SettingsChangeRow>(), SettingsChangeRows.from(listOf(nothing, unparseable)))
    }

    /**
     * A Do Not Disturb mode the device reported and this build cannot name produces no row.
     *
     * Two refusals stack here. `restoreOne` drops a name no `DoNotDisturbState` matches, and
     * `AudioControls.setDoNotDisturbState` drops `UNKNOWN` itself, because that state has no interruption
     * filter to write. `UNKNOWN` is the one that would otherwise slip through: the name resolves, so a
     * mapping that only checked the lookup would offer a revert nothing can perform.
     */
    @Test
    fun `a Do Not Disturb mode this build cannot name produces no row`() {
        val unknown = nonSettingRow(RestorePointRepository.KEY_DO_NOT_DISTURB, previousValue = "UNKNOWN")
        val nonsense = nonSettingRow(RestorePointRepository.KEY_DO_NOT_DISTURB, previousValue = "LOUD")
        val missing = nonSettingRow(RestorePointRepository.KEY_DO_NOT_DISTURB, previousValue = null)

        assertFalse(SettingsChangeRows.isRevertable(unknown))
        assertFalse(SettingsChangeRows.isRevertable(nonsense))
        assertFalse(SettingsChangeRows.isRevertable(missing))
        assertEquals(
            emptyList<SettingsChangeRow>(),
            SettingsChangeRows.from(listOf(unknown, nonsense, missing)),
        )
    }

    /**
     * A core assignment with no game to put it back on, or no mask to put back, produces no row.
     *
     * The subtle one of the three, and the reason the mapping cannot just ask whether the restore
     * "succeeds": `CpuAffinityController.restore` answers `NothingToRestore` in both cases, and
     * `CpuAffinityOutcome.isSuccess` counts that as success — so the row clears with nothing written.
     * Discharging an obligation is not reverting a setting, and a card offering a revert that tidies the
     * table while leaving the device exactly as it was is the fake data the rule forbids.
     */
    @Test
    fun `a core assignment with nothing to put back produces no row`() {
        val noGame = nonSettingRow(
            RestorePointRepository.KEY_CPU_AFFINITY,
            previousValue = "f0",
            packageName = null,
        )
        val noMask = nonSettingRow(RestorePointRepository.KEY_CPU_AFFINITY, previousValue = null)
        // A mask of zero pins a process to no cores at all, so `CpuAffinityMask.parse` reads it as a parse
        // that went wrong rather than as a value to write back.
        val emptyMask = nonSettingRow(RestorePointRepository.KEY_CPU_AFFINITY, previousValue = "0")

        assertFalse(SettingsChangeRows.isRevertable(noGame))
        assertFalse(SettingsChangeRows.isRevertable(noMask))
        assertFalse(SettingsChangeRows.isRevertable(emptyMask))
        assertEquals(
            emptyList<SettingsChangeRow>(),
            SettingsChangeRows.from(listOf(noGame, noMask, emptyMask)),
        )
    }

    // ----------------------------------------------------------------------- the five non-setting rows

    /** Each of the five, with a value its restore path accepts, produces exactly one labelled row. */
    @Test
    fun `every non-setting change the restore path knows produces a row`() {
        val rows = SettingsChangeRows.from(revertableNonSettingLedger())

        assertEquals(NonSettingChange.entries.size, rows.size)
        assertTrue(rows.all { it.revertable })
        assertEquals(NonSettingChange.entries.map { it.key }, rows.map { it.key })
        assertTrue(rows.all { it.namespace == RestorePointRepository.NON_SETTING_NAMESPACE })
    }

    /**
     * The labels are the action labels already on screen in the apply-diff line, not words coined here.
     *
     * [PendingRestore.describe] falls back to the raw provider key for these five, which is right for a
     * diagnostic line and useless under a revert button — "charge_bypass" is not something a user can act
     * on.
     */
    @Test
    fun `a non-setting row is labelled with the action that writes it`() {
        val rows = SettingsChangeRows.from(revertableNonSettingLedger()).associateBy { it.key }

        assertEquals(
            OptimizationAction.SET_MEDIA_VOLUME.label,
            rows.getValue(RestorePointRepository.KEY_MEDIA_VOLUME).label,
        )
        assertEquals(
            OptimizationAction.ENABLE_DO_NOT_DISTURB.label,
            rows.getValue(RestorePointRepository.KEY_DO_NOT_DISTURB).label,
        )
        assertEquals(
            OptimizationAction.SET_DISPLAY_SIZE.label,
            rows.getValue(RestorePointRepository.KEY_DISPLAY_SIZE).label,
        )
        assertEquals(
            OptimizationAction.SET_CPU_AFFINITY.label,
            rows.getValue(RestorePointRepository.KEY_CPU_AFFINITY).label,
        )
        assertEquals(
            OptimizationAction.SET_CHARGE_BYPASS.label,
            rows.getValue(RestorePointRepository.KEY_CHARGE_BYPASS).label,
        )
    }

    /**
     * A display override recorded against a display that had none still produces a row.
     *
     * Null is the ordinary case for this key and the one that matters most: a `wm size` override outlives
     * a reboot, so the row whose revert is `wm size reset` is the single most important one on the card.
     * It reads as not set rather than as a missing reading.
     */
    @Test
    fun `a display size override with no previous size is shown as not set`() {
        val row = SettingsChangeRows.rowFor(
            nonSettingRow(RestorePointRepository.KEY_DISPLAY_SIZE, previousValue = null),
        )

        assertTrue(row.revertable)
        assertEquals(PreviousValueText.Unset, row.previousValue)
    }

    // ----------------------------------------------------------------------- ordering and the empty card

    /**
     * The card reads in recording order, which is the order the writes happened and the order
     * `restoreAll` replays them in.
     *
     * Fed deliberately out of order. The repository sorts already, but this is a pure function with no way
     * to check that a caller honoured a contract written somewhere else, and the pairs are the reason it
     * matters: brightness level before brightness mode, `min_refresh_rate` before `peak_refresh_rate`.
     * Sorting by label instead would split every pair.
     */
    @Test
    fun `the card follows the order the writes happened`() {
        val ledger = listOf(
            settingsRow(WritableSetting.SCREEN_BRIGHTNESS_MODE, recordedAtMillis = 40L),
            settingsRow(WritableSetting.PEAK_REFRESH_RATE, recordedAtMillis = 20L),
            settingsRow(WritableSetting.MIN_REFRESH_RATE, recordedAtMillis = 10L),
            settingsRow(WritableSetting.SCREEN_BRIGHTNESS, recordedAtMillis = 30L),
        )

        val keys = SettingsChangeRows.from(ledger).map { it.key }

        assertEquals(
            listOf("min_refresh_rate", "peak_refresh_rate", "screen_brightness", "screen_brightness_mode"),
            keys,
        )
    }

    /** Rows recorded in the same millisecond keep the order they arrived in; the sort is stable. */
    @Test
    fun `changes recorded in the same millisecond keep the order they arrived in`() {
        val ledger = listOf(
            settingsRow(WritableSetting.MIN_REFRESH_RATE, recordedAtMillis = 7L),
            settingsRow(WritableSetting.PEAK_REFRESH_RATE, recordedAtMillis = 7L),
        )

        assertEquals(
            listOf("min_refresh_rate", "peak_refresh_rate"),
            SettingsChangeRows.from(ledger).map { it.key },
        )
    }

    /** Nothing owed back is an empty list, never a placeholder row. */
    @Test
    fun `an empty ledger produces an empty card`() {
        assertEquals(emptyList<SettingsChangeRow>(), SettingsChangeRows.from(emptyList()))
    }

    // ----------------------------------------------------------------------- values the card will state

    /**
     * The renderings the source can justify, written out literally.
     *
     * Literal expected strings rather than a second call to [com.gamecore.core.common.Formatters], for
     * [com.gamecore.core.common.FormattersTest]'s reason: a test that recomputes the value it is checking
     * agrees with any mistake the code makes.
     */
    @Test
    fun `a value whose unit the source states is rendered with it`() {
        assertRendered("120 Hz", WritableSetting.PEAK_REFRESH_RATE, "120")
        assertRendered("1m 0s", WritableSetting.SCREEN_OFF_TIMEOUT, "60000")
        assertRendered("4000 K", WritableSetting.NIGHT_DISPLAY_COLOR_TEMPERATURE, "4000")
        assertRendered("40%", WritableSetting.REDUCE_BRIGHT_COLORS_LEVEL, "40")
        assertRendered("1.0×", WritableSetting.WINDOW_ANIMATION_SCALE, "1.0")
        assertRendered("90°", WritableSetting.USER_ROTATION, "1")
        assertRendered("Natural", WritableSetting.DISPLAY_COLOR_MODE, "0")
        assertRendered("Deuteranomaly", WritableSetting.DALTONIZER_MODE, "12")
    }

    /**
     * A boolean key reads as a polarity, because its label already carries the subject.
     *
     * Every [WritableSetting] on `BOOLEAN_INT` is described "Whether …", so "On" beside "Whether
     * brightness follows the light sensor." is a complete statement and needs no invented sentence.
     */
    @Test
    fun `a boolean key reads as on or off`() {
        assertRendered("On", WritableSetting.LOW_POWER, "1")
        assertRendered("Off", WritableSetting.LOW_POWER, "0")
        assertRendered("On", WritableSetting.NIGHT_DISPLAY_ACTIVATED, "1")
    }

    /** Zero is the absence of a bound rather than a rate, which is how a refresh-rate pin is released. */
    @Test
    fun `a refresh rate of zero reads as no bound rather than as zero hertz`() {
        assertRendered("No bound", WritableSetting.MIN_REFRESH_RATE, "0")
    }

    /**
     * With no reading captured, the card states the value the revert will write.
     *
     * `SettingsWriter.restore` substitutes [WritableSetting.restoreDefault] for a null previous value, so
     * that figure — the platform's own documented default, not a GameCore invention — is what the user is
     * deciding about when they look at the button.
     */
    @Test
    fun `a settings row with no reading captured states the default the revert will write`() {
        val row = SettingsChangeRows.rowFor(
            settingsRow(WritableSetting.NIGHT_DISPLAY_COLOR_TEMPERATURE, previousValue = null),
        )

        assertEquals("4000", WritableSetting.NIGHT_DISPLAY_COLOR_TEMPERATURE.restoreDefault)
        assertEquals(PreviousValueText.Rendered("4000 K"), row.previousValue)
    }

    /** The five non-setting values, each rendered only from something the source states. */
    @Test
    fun `a non-setting value is rendered only where the source says what it means`() {
        val rows = SettingsChangeRows.from(revertableNonSettingLedger()).associateBy { it.key }

        // Documented as a percentage at KEY_MEDIA_VOLUME, and scaled against getStreamMaxVolume before it
        // was ever recorded — the stream index never reaches the ledger.
        assertEquals(
            PreviousValueText.Rendered("70%"),
            rows.getValue(RestorePointRepository.KEY_MEDIA_VOLUME).previousValue,
        )
        // The state's own label, not its enum name.
        assertEquals(
            PreviousValueText.Rendered("Priority only"),
            rows.getValue(RestorePointRepository.KEY_DO_NOT_DISTURB).previousValue,
        )
        assertEquals(
            PreviousValueText.Rendered("1080 × 2400"),
            rows.getValue(RestorePointRepository.KEY_DISPLAY_SIZE).previousValue,
        )
        assertEquals(
            PreviousValueText.Rendered("Cores 4, 5, 6, 7"),
            rows.getValue(RestorePointRepository.KEY_CPU_AFFINITY).previousValue,
        )
    }

    // ----------------------------------------------------------------------- values the card refuses

    /**
     * A brightness is shown as the integer the ledger holds, never as a percentage.
     *
     * `screen_brightness` runs against the panel's own maximum, which this layer cannot read —
     * `ValueForm.BRIGHTNESS`'s 0..255 is the allow-list's bound, not a statement about the device. A
     * percentage derived from it would be wrong on every panel with a wider range, and wrong invisibly,
     * which is the worst kind. The 2047 case is a real device: several builds report brightness on a
     * 0..2047 scale.
     */
    @Test
    fun `a brightness is shown raw because its scale is the panel's own`() {
        assertRaw("128", WritableSetting.SCREEN_BRIGHTNESS, "128")
        assertRaw("2047", WritableSetting.SCREEN_BRIGHTNESS, "2047")
    }

    /** A vendor colour mode has no name in this repository, so it keeps its number. */
    @Test
    fun `a vendor colour mode keeps its number rather than borrowing a name`() {
        assertRaw("300", WritableSetting.DISPLAY_COLOR_MODE, "300")
    }

    /**
     * A value that does not parse as its own form is shown exactly as stored.
     *
     * Not defensive padding: the ledger holds whatever the provider returned, and a row can outlive the
     * build that wrote it. Showing "fast" where a refresh rate belongs tells the user something true;
     * rendering it as a rate would not.
     */
    @Test
    fun `a value that does not parse as its own form is shown exactly as stored`() {
        assertRaw("fast", WritableSetting.PEAK_REFRESH_RATE, "fast")
        assertRaw("maybe", WritableSetting.LOW_POWER, "maybe")
        assertRaw("sideways", WritableSetting.USER_ROTATION, "sideways")
    }

    /**
     * The charge bypass states no value, because the digit's meaning lives on the device.
     *
     * `ChargeBypassController` reads `normalValue` and `stopValue` off whichever sysfs node the device
     * happens to have, so "0" means charging on some and stopped on others. The row still appears — the
     * revert genuinely works — and the card simply does not claim to know what the stored digit meant. The
     * null case is the same refusal from the other side: the revert will write the node's own normal
     * value, which this layer cannot name.
     */
    @Test
    fun `a charge bypass value is never dressed up as a sentence`() {
        val digit = SettingsChangeRows.rowFor(
            nonSettingRow(RestorePointRepository.KEY_CHARGE_BYPASS, previousValue = "0"),
        )
        val nothing = SettingsChangeRows.rowFor(
            nonSettingRow(RestorePointRepository.KEY_CHARGE_BYPASS, previousValue = null),
        )

        assertTrue(digit.revertable)
        assertEquals(PreviousValueText.Raw("0"), digit.previousValue)

        assertTrue(nothing.revertable)
        assertEquals(PreviousValueText.NotStated, nothing.previousValue)
    }

    /** A display size the ledger holds in a shape `DisplaySize` cannot parse keeps its stored text. */
    @Test
    fun `a display size that does not parse keeps its stored text`() {
        val row = SettingsChangeRows.rowFor(
            nonSettingRow(RestorePointRepository.KEY_DISPLAY_SIZE, previousValue = "huge"),
        )

        assertEquals(PreviousValueText.Raw("huge"), row.previousValue)
    }

    // ----------------------------------------------------------------------- the compile gate

    /**
     * The test that fails when a ledger key is added without anyone deciding whether it is revertable.
     *
     * Three gates guard that decision, and only the first two are the compiler's. A new
     * [WritableSetting] breaks the exhaustive `when` in `settingIsRevertable`; a new [NonSettingChange]
     * breaks the two exhaustive `when`s over it. Neither fires for the third case, which is the one a
     * reviewer would actually miss: a new `KEY_…` constant added to [RestorePointRepository] and recorded
     * through its untyped `record` overload, with no [NonSettingChange] entry to match. That row would
     * compile, reach `restoreOne`'s *"does not know how to restore"* branch, and silently never appear on
     * the card — so the constants are read back by reflection and checked against the enum.
     *
     * The counts are asserted too, which is what catches the opposite mistake: a twentieth
     * [WritableSetting] given `-> false` to make the build go green.
     */
    @Test
    fun `every ledger key this build can write has been decided one way or the other`() {
        val declaredKeys = RestorePointRepository::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.name.startsWith("KEY_") }
            .map { it.get(null) as String }
            .toSet()

        assertEquals(NonSettingChange.entries.map { it.key }.toSet(), declaredKeys)

        // 19 settings keys plus 5 non-setting keys is the whole ledger: 24 rows can exist, no more.
        assertEquals(19, WritableSetting.entries.size)
        assertEquals(5, NonSettingChange.entries.size)
        assertEquals(
            24,
            SettingsChangeRows.from(
                WritableSetting.entries.mapIndexed { index, setting ->
                    settingsRow(setting, recordedAtMillis = index.toLong())
                } + revertableNonSettingLedger(),
            ).size,
        )
    }

    /** No two non-setting entries may claim the same ledger key, or one would shadow the other. */
    @Test
    fun `each non-setting change owns a distinct ledger key`() {
        assertEquals(
            NonSettingChange.entries.size,
            NonSettingChange.entries.map { it.key }.toSet().size,
        )
        NonSettingChange.entries.forEach { change ->
            assertEquals(change, NonSettingChange.of(change.key))
        }
    }

    // ---------------------------------------------------------------------------------------------- fakes

    private fun assertRendered(expected: String, setting: WritableSetting, stored: String) {
        assertEquals(
            PreviousValueText.Rendered(expected),
            SettingsChangeRows.rowFor(settingsRow(setting, previousValue = stored)).previousValue,
        )
    }

    private fun assertRaw(expected: String, setting: WritableSetting, stored: String) {
        assertEquals(
            PreviousValueText.Raw(expected),
            SettingsChangeRows.rowFor(settingsRow(setting, previousValue = stored)).previousValue,
        )
    }

    /** A ledger row for an allow-listed key, exactly as `record(setting, …)` would have written it. */
    private fun settingsRow(
        setting: WritableSetting,
        previousValue: String? = null,
        recordedAtMillis: Long = 0L,
    ) = PendingRestore(
        namespace = setting.namespace.token,
        key = setting.key,
        previousValue = previousValue,
        packageName = GAME,
        recordedAtMillis = recordedAtMillis,
        setting = setting,
    )

    /** A ledger row for a key with no [WritableSetting], as the untyped `record` overload writes it. */
    private fun nonSettingRow(
        key: String,
        previousValue: String?,
        packageName: String? = GAME,
        recordedAtMillis: Long = 0L,
    ) = PendingRestore(
        namespace = RestorePointRepository.NON_SETTING_NAMESPACE,
        key = key,
        previousValue = previousValue,
        packageName = packageName,
        recordedAtMillis = recordedAtMillis,
        setting = null,
    )

    /** One row per [NonSettingChange], each carrying a value its restore path accepts. */
    private fun revertableNonSettingLedger(): List<PendingRestore> = listOf(
        nonSettingRow(RestorePointRepository.KEY_MEDIA_VOLUME, "70", recordedAtMillis = 1L),
        nonSettingRow(RestorePointRepository.KEY_DO_NOT_DISTURB, "PRIORITY_ONLY", recordedAtMillis = 2L),
        nonSettingRow(RestorePointRepository.KEY_DISPLAY_SIZE, "1080x2400", recordedAtMillis = 3L),
        // 0xf0 is the big cluster on an eight-core phone: cores 4 to 7.
        nonSettingRow(RestorePointRepository.KEY_CPU_AFFINITY, "f0", recordedAtMillis = 4L),
        nonSettingRow(RestorePointRepository.KEY_CHARGE_BYPASS, "0", recordedAtMillis = 5L),
    )

    private companion object {
        const val GAME = "com.example.game"
    }
}
