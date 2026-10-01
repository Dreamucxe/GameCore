package com.gamecore.core.session

import com.gamecore.core.common.Formatters
import com.gamecore.core.model.CpuAffinityMask
import com.gamecore.core.model.DisplaySize
import com.gamecore.core.model.OptimizationAction
import com.gamecore.core.shizuku.ValueForm
import com.gamecore.core.shizuku.WritableSetting
import com.gamecore.core.system.ColorProjection
import com.gamecore.core.system.DoNotDisturbState
import com.gamecore.data.repository.PendingRestore
import com.gamecore.data.repository.RestorePointRepository

/**
 * One line of the mid-session "what this profile changed" card (§3.7.1, feature 7).
 *
 * The card is shown while a session is being tracked and lists what the applied profile actually did to
 * the device, with a per-row revert so the user can put one setting back without ending the session. The
 * automatic end-of-session [com.gamecore.domain.optimization.OptimizationManager.restoreAll] stays as the
 * fallback; this is the manual, one-at-a-time path in front of it.
 *
 * Four fields, and the restraint is the design:
 *
 *  - [label] is the setting named in the user's own words, never a key.
 *  - [previousValue] is what a revert puts back, rendered honestly or not rendered at all — see
 *    [PreviousValueText] for the four cases and why a fifth ("print the integer and call it a
 *    percentage") is not one of them.
 *  - [namespace] and [key] are the whole identity, because they are the restore table's whole primary
 *    key (`@Entity(primaryKeys = ["namespace", "key"])`,
 *    `com.gamecore.data.database.RestorePointEntity`) and therefore the least a caller can hold and
 *    still name exactly one row. Carrying the previous value or the package name as well would let a UI
 *    layer assemble a [PendingRestore] of its own and hand a *stale* one to the restore path — a revert
 *    pressed thirty seconds after the card was composed would write back a value that is no longer the
 *    one in the table. The repository is re-read instead, and these two strings are what it is re-read
 *    with.
 *  - [revertable] is the decision recorded on the row rather than left implicit in whether the row
 *    exists. [SettingsChangeRows.from] builds the card out of the rows where it is true; keeping the
 *    field is what lets a test assert *why* a row was left out, and what keeps the drop a filter over a
 *    stated decision rather than an unstated side effect of the mapping.
 *
 * Pure: no `Context`, no Room, no coroutines, no Compose, nothing from `ui`. The mapping — which rows
 * survive, what each is called, how each value reads — is the part of this feature that can be wrong in
 * a way a screenshot does not show, so it is the part that is unit-tested on the JVM.
 */
data class SettingsChangeRow(
    val label: String,
    val previousValue: PreviousValueText,
    val namespace: String,
    val key: String,
    val revertable: Boolean,
)

/**
 * How one ledger value is allowed to reach the user (§3.7.1, feature 7).
 *
 * The recon behind this feature turned up one fact that shapes everything below: **nothing in the
 * codebase formats a restore-ledger value at all.** [PendingRestore.describe] names the key and stops;
 * the value is a bare provider string — `"1"`, `"0"`, `"1080x2400"`, a brightness somewhere in 0..255, a
 * volume percentage, a daltonizer mode index. A card that printed those raw would be useless, and a card
 * that guessed at them would be worse, so this type is the seam between the two:
 *
 *  - [Rendered] is a value whose meaning is written down *in this repository* — a boolean key's 0/1, a
 *    refresh rate in Hz, a timeout in milliseconds, kelvin, a documented 0..100 percentage, a display
 *    size in pixels. Each one cites its source at the branch that produces it.
 *  - [Raw] is the stored string verbatim: no unit, no scale, no sentence. It is what a brightness gets,
 *    because `screen_brightness` is an integer whose span is the panel's own `SCREEN_BRIGHTNESS_MAX` and
 *    this layer has no device to ask — rendering `"128"` as "50%" would be a fabricated figure on every
 *    panel that does not happen to stop at 255.
 *  - [Unset] is a key that had no value before GameCore wrote one, which is a real state and not a
 *    missing reading ([PendingRestore.restoresToUnset]). The display-size row is the one that reaches
 *    it, and it is the common case there: a revert clears the override rather than writing a size.
 *  - [NotStated] is the refusal. The row still appears — it is revertable, the device really was
 *    changed — but the card says nothing about the value, because there is nothing honest to say.
 *
 * [text] exists so the card draws one string either way. It is deliberately *not* the only thing on the
 * type: a caller that wants to set [Raw] in a monospaced face, or grey [NotStated] out, needs the case
 * and not just the words.
 */
sealed interface PreviousValueText {

    /** The words the card shows. */
    val text: String

    /** A value with its unit, where this repository states what the unit is. */
    data class Rendered(override val text: String) : PreviousValueText

    /** The stored string exactly as the ledger holds it, claiming nothing about what it means. */
    data class Raw(override val text: String) : PreviousValueText

    /** The key had no value before GameCore wrote one, so a revert clears it rather than writing. */
    data object Unset : PreviousValueText {
        override val text: String = "Not set"
    }

    /** Nothing honest can be said about this value, so nothing is. */
    data object NotStated : PreviousValueText {
        override val text: String = "Not stated"
    }
}

/**
 * The five ledger rows that are not settings keys, as an enum (§3.7.1, feature 7).
 *
 * [RestorePointRepository] stores these five under its own [RestorePointRepository.NON_SETTING_NAMESPACE]
 * because none of them has a `settings` key an app can write — media volume goes through `AudioManager`,
 * Do Not Disturb through the notification-policy API, the display size through `wm size`, the affinity
 * mask through `taskset`, the charge bypass through a sysfs node. They are held there as loose `const val`
 * strings, which is right for a storage key and wrong for a decision: a `when` over strings needs an
 * `else`, and an `else` is exactly what this file must not have.
 *
 * So they are collected here as an enum, and every `when` over it below is exhaustive with **no `else`**
 * — the pattern `DockActionId.route()` (`com.gamecore.core.overlay.DockActionRoutes`) and
 * [com.gamecore.core.overlay.OverlayAction.macroBehavior] use, for the same reason and against the same
 * bug. A sixth non-setting key added to the ledger without a branch here does not compile. The failure it
 * rules out is specific and silent: a new key would otherwise fall through to "not revertable", vanish
 * from the card, and leave the user looking at a device GameCore has changed and is not admitting to —
 * or, with the polarity the other way round, at a revert button that writes nothing.
 *
 * [action] is where the label comes from, and it is a real user-facing string rather than one invented
 * here: it is the same [OptimizationAction.label] the apply-diff line already shows for this change
 * (`com.gamecore.core.overlay.ApplyDiff.summarize`). The correspondence between key and action is read
 * off the source in each case — `readKeys` records the volume and Do Not Disturb rows for
 * [OptimizationAction.SET_MEDIA_VOLUME] and [OptimizationAction.ENABLE_DO_NOT_DISTURB], and the three
 * controllers (`DisplaySizeController.KEY`, `CpuAffinityController.KEY`, `ChargeBypassController.KEY`)
 * record the rest for the three actions whose `settingsTouchedBy` branch is empty precisely because the
 * controller owns the row.
 *
 * Declared in the order [RestorePointRepository] declares the constants, which is also the order
 * `OptimizationManager.restoreOne` dispatches them in. Three orderings that agree are worth keeping that
 * way.
 */
enum class NonSettingChange(val key: String, val action: OptimizationAction) {
    MEDIA_VOLUME(RestorePointRepository.KEY_MEDIA_VOLUME, OptimizationAction.SET_MEDIA_VOLUME),
    DO_NOT_DISTURB(RestorePointRepository.KEY_DO_NOT_DISTURB, OptimizationAction.ENABLE_DO_NOT_DISTURB),
    DISPLAY_SIZE(RestorePointRepository.KEY_DISPLAY_SIZE, OptimizationAction.SET_DISPLAY_SIZE),
    CPU_AFFINITY(RestorePointRepository.KEY_CPU_AFFINITY, OptimizationAction.SET_CPU_AFFINITY),
    CHARGE_BYPASS(RestorePointRepository.KEY_CHARGE_BYPASS, OptimizationAction.SET_CHARGE_BYPASS),
    ;

    /** The user-facing name of this change, taken from the action that writes it. */
    val label: String get() = action.label

    companion object {
        /** The change [key] names, or null for a key this build has no non-setting branch for. */
        fun of(key: String): NonSettingChange? = entries.firstOrNull { it.key == key }
    }
}

/**
 * The restore ledger as the mid-session card reads it (§3.7.1, feature 7).
 *
 * ## The rule this file exists to enforce
 *
 * > "Do not show a row for a setting the restore ledger cannot actually revert. No fake data."
 *
 * That is a harder constraint than it sounds, because the ledger's own documentation is more generous
 * than its restore path. [RestorePointRepository]'s class KDoc says a dropped key is still restorable —
 * *"[namespace] and [key] are all `settings put` needs"* — and the source disagrees:
 * `OptimizationManager.restoreOne` branches on [PendingRestore.setting] first, and a null setting falls
 * into a `when (row.key)` that knows only the five non-setting keys, ending at
 *
 * ```
 * else -> "This version of GameCore does not know how to restore \"${row.key}\"."
 * ```
 *
 * No `settings put` is ever assembled from the raw strings. So a settings-namespace row whose key is no
 * longer in this build's allow-list **cannot be reverted**, whatever the table's comment says, and this
 * mapping drops it. Source wins over KDoc; the discrepancy is noted here so the next reader does not
 * resolve it the other way and re-introduce a revert button that can only ever print that sentence.
 *
 * ## What the ledger can and cannot put back
 *
 * Twenty-four rows can exist: the 19 [WritableSetting] keys and the five [NonSettingChange] keys.
 *
 *  - **All 19 settings keys: revertable.** `restoreOne` hands them to `restoreSetting`, which calls
 *    `SettingsWriter.restore`, which writes `previousValue ?: setting.restoreDefault`. There is always
 *    something to write, and the default is the platform's own rather than a GameCore invention.
 *  - **`media_volume`: revertable only when the recorded value parses as an integer.** `restoreOne`
 *    refuses otherwise — *"No media volume was recorded to go back to."*
 *  - **`do_not_disturb`: revertable only when the recorded value names a [DoNotDisturbState] other than
 *    [DoNotDisturbState.UNKNOWN].** `restoreOne` refuses an unknown name outright, and
 *    `AudioControls.setDoNotDisturbState` refuses [DoNotDisturbState.UNKNOWN] itself, because that state
 *    has no interruption filter to write. A row recorded off a device that reported a filter this build
 *    cannot name is a row no revert can discharge.
 *  - **`display_size`: revertable, always.** A null previous value is the ordinary case rather than a
 *    missing reading: it means the display had no override, and the way back is `wm size reset`.
 *  - **`cpu_affinity`: revertable only with a package name *and* a mask that parses.**
 *    `CpuAffinityController.restore` answers `NothingToRestore` without either, and
 *    `CpuAffinityOutcome.isSuccess` counts `NothingToRestore` as success — so the row would clear with
 *    nothing written. Discharging an obligation is not reverting a setting, and a revert button that
 *    tidies the table while leaving the device as it was is the fake data the rule forbids. Mid-session,
 *    with the game running and a mask recorded, this is true; it is the stale row from a session that
 *    ended badly that it excludes.
 *  - **`charge_bypass`: revertable, always.** `ChargeBypassController.restore` falls back to the node's
 *    own normal value, and refuses to write back a captured value that would itself leave charging off.
 *  - **Anything else: not revertable**, by the `else` quoted above. That covers both a settings key this
 *    build has dropped and a non-setting key a future build records without extending
 *    [NonSettingChange].
 *
 * ## What is deliberately not done here
 *
 * No label, unit or previous value is invented. Settings rows are labelled with
 * [PendingRestore.describe], which is [WritableSetting.userDescription] — sentence-cased and
 * full-stopped, because that is how the source wrote them and re-punctuating a user-facing string in a
 * mapping layer is how two screens come to call one setting two things. Non-setting rows are labelled
 * with [NonSettingChange.label], which is an [OptimizationAction.label] already on screen elsewhere.
 * Values are rendered only where this repository says what the stored number means, and
 * [PreviousValueText.Raw] or [PreviousValueText.NotStated] everywhere else.
 */
object SettingsChangeRows {

    // ------------------------------------------------------------------------------- the card

    /**
     * The card's rows, oldest change first, non-revertable rows dropped.
     *
     * Recording order rather than anything cleverer, and it is the only order that reads correctly. It is
     * the order the writes happened, so the card reads as a log of what the profile did; it is the order
     * `restoreAll` replays, so the card and the automatic restore agree; and it keeps pairs together in
     * the sequence that makes them legible — brightness level then brightness mode, `min_refresh_rate`
     * then `peak_refresh_rate`. Sorting by label would split every pair and shuffle the two refresh-rate
     * bounds into an order some builds reject a write in.
     *
     * [RestorePointRepository.pending] already sorts by [PendingRestore.recordedAtMillis], so this sort
     * is normally a no-op. It is here anyway because this is a pure function with no way to check that
     * its caller honoured a contract documented somewhere else, and [sortedBy] is stable, so rows
     * recorded in the same millisecond keep the order they arrived in.
     */
    fun from(pending: List<PendingRestore>): List<SettingsChangeRow> =
        pending
            .sortedBy { it.recordedAtMillis }
            .map(::rowFor)
            .filter { it.revertable }

    /**
     * One ledger row as a card row, revertable or not.
     *
     * Public alongside [from] so the decision is inspectable rather than only observable as an absence. A
     * test that wants to assert *why* `cpu_affinity` was dropped asks here; the card asks [from].
     */
    fun rowFor(row: PendingRestore): SettingsChangeRow = SettingsChangeRow(
        label = labelFor(row),
        previousValue = previousValueText(row),
        namespace = row.namespace,
        key = row.key,
        revertable = isRevertable(row),
    )

    // ------------------------------------------------------------------------------- revertability

    /**
     * Whether `OptimizationManager.restoreOne` would genuinely put this row's value back.
     *
     * "Genuinely" is doing work: the question is not whether the call returns without a sentence, it is
     * whether the device ends up holding the recorded value. The two come apart for `cpu_affinity`,
     * where a `NothingToRestore` outcome counts as success and clears the row having written nothing —
     * see the class KDoc.
     */
    fun isRevertable(row: PendingRestore): Boolean {
        val setting = row.setting
        if (setting != null) return settingIsRevertable(setting)
        val change = nonSettingChangeFor(row) ?: return false
        return nonSettingIsRevertable(change, row)
    }

    /**
     * Every settings key is revertable, written out one entry at a time.
     *
     * A reviewer's first instinct is that this function should be `= true`, and that instinct is the bug
     * it prevents. The `when` is exhaustive with **no `else`**, so a twentieth [WritableSetting] added to
     * the allow-list does not compile until someone has looked at it and said which side of this line it
     * falls on. An `else -> true` would let a key with no working restore path draw a revert button; an
     * `else -> false` would let a key GameCore really does change vanish from the card. Both are
     * failures the compiler can refuse, which is cheaper than either reaching a device.
     *
     * All 19 answer the same way today for one reason, and it is worth stating so the next reader does
     * not have to re-derive it: `restoreSetting` writes `previousValue ?: setting.restoreDefault`, so
     * every one of these keys has a value to write back even when the pre-change reading was never
     * captured.
     */
    private fun settingIsRevertable(setting: WritableSetting): Boolean = when (setting) {
        WritableSetting.MIN_REFRESH_RATE,
        WritableSetting.PEAK_REFRESH_RATE,
        WritableSetting.SCREEN_BRIGHTNESS,
        WritableSetting.SCREEN_BRIGHTNESS_MODE,
        WritableSetting.ACCELEROMETER_ROTATION,
        WritableSetting.USER_ROTATION,
        WritableSetting.SCREEN_OFF_TIMEOUT,
        WritableSetting.WINDOW_ANIMATION_SCALE,
        WritableSetting.TRANSITION_ANIMATION_SCALE,
        WritableSetting.ANIMATOR_DURATION_SCALE,
        WritableSetting.LOW_POWER,
        WritableSetting.NIGHT_DISPLAY_ACTIVATED,
        WritableSetting.NIGHT_DISPLAY_COLOR_TEMPERATURE,
        WritableSetting.DISPLAY_COLOR_MODE,
        WritableSetting.DALTONIZER_ENABLED,
        WritableSetting.DALTONIZER_MODE,
        WritableSetting.REDUCE_BRIGHT_COLORS_ACTIVATED,
        WritableSetting.REDUCE_BRIGHT_COLORS_LEVEL,
        WritableSetting.COLOR_INVERSION_ENABLED,
        -> true
    }

    /**
     * Whether one of the five non-setting rows can be put back, mirroring `restoreOne`'s own guards.
     *
     * Exhaustive, no `else`, for [NonSettingChange]'s reason. Each branch is the same test the restore
     * path makes, written the same way round, because a predicate that was merely *close* to the restore
     * path's would be worse than none: the card would offer a revert the restore then refuses, and the
     * user would press it twice and conclude the app is broken.
     *
     * The Do Not Disturb branch excludes [DoNotDisturbState.UNKNOWN] rather than testing
     * `DoNotDisturbState.filter != null`, which is the condition `setDoNotDisturbState` actually applies.
     * The two are equivalent — `filter` is null for [DoNotDisturbState.UNKNOWN] alone — and the enum
     * comparison keeps this file off `android.app.NotificationManager`, whose constants that getter
     * reads. `TapCoordinateMapper` records what happens when a pure value touches a platform constant in
     * a unit test: the test never starts.
     */
    private fun nonSettingIsRevertable(change: NonSettingChange, row: PendingRestore): Boolean =
        when (change) {
            NonSettingChange.MEDIA_VOLUME -> row.previousValue?.toIntOrNull() != null

            NonSettingChange.DO_NOT_DISTURB ->
                doNotDisturbState(row.previousValue)?.let { it != DoNotDisturbState.UNKNOWN } == true

            NonSettingChange.DISPLAY_SIZE -> true

            NonSettingChange.CPU_AFFINITY ->
                row.packageName != null && CpuAffinityMask.parse(row.previousValue) != null

            NonSettingChange.CHARGE_BYPASS -> true
        }

    // ------------------------------------------------------------------------------- labels

    /**
     * What to call this row's setting.
     *
     * [PendingRestore.describe] for a settings row, which resolves to [WritableSetting.userDescription]
     * — the only per-key user-facing string the codebase has, and per-key is what a card of individual
     * revert buttons needs. The obvious alternative, [OptimizationAction.label], is per-*action*, and
     * `settingsTouchedBy` shows why that fails here: `SET_BRIGHTNESS` writes two keys, so two rows would
     * both read "Set brightness" and the user would have no way to tell which button put which back.
     *
     * [NonSettingChange.label] for the five that have no `WritableSetting`, where [PendingRestore.describe]
     * falls back to the raw provider key. That fallback is right for a diagnostic line and wrong for this
     * card: "charge_bypass" under a revert button is not a sentence a user can act on.
     */
    private fun labelFor(row: PendingRestore): String =
        nonSettingChangeFor(row)?.label ?: row.describe()

    // ------------------------------------------------------------------------------- values

    /**
     * What a revert puts back, rendered as honestly as the stored string allows.
     *
     * Note which of two nearly-identical questions this answers. For a settings row with no captured
     * reading, "what the device was at" is *unset* and "what a revert writes" is
     * [WritableSetting.restoreDefault] — `SettingsWriter.restore` substitutes it. The card shows the
     * second, because the user is deciding whether to press a button that performs a write, and the
     * figure that write uses is the one that answers them. It is the platform's own documented default,
     * not a GameCore invention, so showing it claims nothing untrue.
     *
     * A row this build cannot place at all gets [PreviousValueText.NotStated]. Such a row is dropped by
     * [from] anyway; the branch is here so [rowFor] never has to produce a value it cannot defend.
     */
    fun previousValueText(row: PendingRestore): PreviousValueText {
        val setting = row.setting
        if (setting != null) {
            return renderSettingValue(setting.form, row.previousValue ?: setting.restoreDefault)
        }
        val change = nonSettingChangeFor(row) ?: return PreviousValueText.NotStated
        return renderNonSettingValue(change, row.previousValue)
    }

    /**
     * A settings value, rendered per [ValueForm].
     *
     * Dispatching on the form rather than on the key is what keeps this short and keeps it correct: the
     * form is already the repository's statement of what shape a value has, it is already range-checked
     * by [ValueForm.accepts], and nineteen keys share ten forms. Exhaustive with no `else`, so an
     * eleventh [ValueForm] cannot arrive and be rendered by accident.
     *
     * Every branch falls back to [PreviousValueText.Raw] when the string does not parse as its form.
     * That is not defensive padding: the ledger holds whatever the provider returned, a row can outlive
     * the build that wrote it, and a value that fails its own form is exactly the value this card must
     * not dress up. Showing `"fast"` where a refresh rate belongs tells the user something true.
     *
     * The honesty of each unit is sourced, not assumed:
     *
     *  - **Hz** and the 0-means-no-bound case are [ValueForm.REFRESH_RATE]'s own: *"0 means 'no bound',
     *    which is how a pin is released."* [Formatters.hertz] renders 0 as an em dash, so 0 is caught
     *    before it gets there.
     *  - **On/Off** for [ValueForm.BOOLEAN_INT] is safe because every boolean key's
     *    [WritableSetting.userDescription] is phrased "Whether …", so the label supplies the subject and
     *    the value supplies only the polarity.
     *  - **Brightness stays raw.** `screen_brightness` is an integer against the panel's own maximum,
     *    which this layer cannot read; [ValueForm.BRIGHTNESS]'s 0..255 is the allow-list's bound, not a
     *    promise about the device. A percentage computed from it would be wrong on every panel with a
     *    wider range, and wrong invisibly.
     *  - **Degrees** for [ValueForm.ROTATION] rest on its own KDoc — *"one of the Surface rotation
     *    constants"* — together with `ScreenRotation`, which is this repository writing down that 1 is a
     *    quarter turn.
     *  - **Milliseconds** are [ValueForm.TIMEOUT_MILLIS]'s unit by name. [Formatters.durationCoarse]
     *    rather than [Formatters.durationTotal], which rounds a 30-second timeout to "0m".
     *  - **A multiplier** for [ValueForm.ANIMATION_SCALE], whose KDoc says *"0 disables animations, 1 is
     *    normal"*, so 0 reads as off and the rest as ×.
     *  - **Kelvin** is both [ValueForm.COLOR_TEMPERATURE]'s unit and the word in
     *    [WritableSetting.NIGHT_DISPLAY_COLOR_TEMPERATURE]'s description.
     *  - **Colour-mode and daltonizer names** come from [ColorProjection]'s constants and from the two
     *    [ValueForm] KDocs that enumerate them. A vendor colour mode in 256..511 has no name anywhere, so
     *    it stays raw rather than being called "Natural".
     *  - **Percent** for [ValueForm.PERCENT] is its KDoc's *"0..100, as the platform's own strength keys
     *    are scaled"*. It is the one percentage this file will print, and it prints it because the source
     *    states the scale.
     */
    private fun renderSettingValue(form: ValueForm, value: String): PreviousValueText = when (form) {
        ValueForm.REFRESH_RATE -> value.toFloatOrNull()
            ?.let { hz ->
                if (hz == 0f) {
                    PreviousValueText.Rendered(NO_REFRESH_RATE_BOUND)
                } else {
                    PreviousValueText.Rendered(Formatters.hertz(hz))
                }
            }
            ?: PreviousValueText.Raw(value)

        ValueForm.BOOLEAN_INT -> when (value) {
            "1" -> PreviousValueText.Rendered(ON)
            "0" -> PreviousValueText.Rendered(OFF)
            else -> PreviousValueText.Raw(value)
        }

        ValueForm.BRIGHTNESS -> PreviousValueText.Raw(value)

        ValueForm.ROTATION -> when (value.toIntOrNull()) {
            ROTATION_0 -> PreviousValueText.Rendered("0°")
            ROTATION_90 -> PreviousValueText.Rendered("90°")
            ROTATION_180 -> PreviousValueText.Rendered("180°")
            ROTATION_270 -> PreviousValueText.Rendered("270°")
            else -> PreviousValueText.Raw(value)
        }

        ValueForm.TIMEOUT_MILLIS -> value.toLongOrNull()
            ?.let { PreviousValueText.Rendered(Formatters.durationCoarse(it)) }
            ?: PreviousValueText.Raw(value)

        ValueForm.ANIMATION_SCALE -> value.toFloatOrNull()
            ?.let { scale ->
                if (scale == 0f) {
                    PreviousValueText.Rendered(OFF)
                } else {
                    PreviousValueText.Rendered("$scale×")
                }
            }
            ?: PreviousValueText.Raw(value)

        ValueForm.COLOR_TEMPERATURE -> value.toIntOrNull()
            ?.let { PreviousValueText.Rendered("$it K") }
            ?: PreviousValueText.Raw(value)

        ValueForm.COLOR_MODE -> when (value.toIntOrNull()) {
            ColorProjection.MODE_NATURAL -> PreviousValueText.Rendered("Natural")
            ColorProjection.MODE_BOOSTED -> PreviousValueText.Rendered("Boosted")
            ColorProjection.MODE_SATURATED -> PreviousValueText.Rendered("Saturated")
            COLOR_MODE_AUTOMATIC -> PreviousValueText.Rendered("Automatic")
            else -> PreviousValueText.Raw(value)
        }

        ValueForm.DALTONIZER_MODE -> when (value.toIntOrNull()) {
            DALTONIZER_DISABLED -> PreviousValueText.Rendered(OFF)
            ColorProjection.MODE_MONOCHROMACY -> PreviousValueText.Rendered("Monochromacy")
            ColorProjection.MODE_PROTANOMALY -> PreviousValueText.Rendered("Protanomaly")
            ColorProjection.MODE_DEUTERANOMALY -> PreviousValueText.Rendered("Deuteranomaly")
            ColorProjection.MODE_TRITANOMALY -> PreviousValueText.Rendered("Tritanomaly")
            else -> PreviousValueText.Raw(value)
        }

        ValueForm.PERCENT -> value.toIntOrNull()
            ?.let { PreviousValueText.Rendered(Formatters.percentValue(it.toFloat())) }
            ?: PreviousValueText.Raw(value)
    }

    /**
     * A non-setting value. Exhaustive, no `else`, for [NonSettingChange]'s reason.
     *
     *  - **Media volume is a percentage**, and this is the only place that is true by documentation
     *    rather than by guess: [RestorePointRepository.KEY_MEDIA_VOLUME] is *"Percent, as the user's own
     *    level before a profile changed it"*, and `AudioControls.mediaVolumePercent` scales the stream
     *    index against `getStreamMaxVolume` before it is recorded. The stream *index* never reaches the
     *    ledger, so the percentage is not a conversion done here.
     *  - **Do Not Disturb** is stored as a [DoNotDisturbState] name and shown as that state's own
     *    [DoNotDisturbState.label] — "Priority only", not `PRIORITY_ONLY`, and not a sentence invented
     *    here.
     *  - **Display size** is `WxH`, parsed by [DisplaySize.parse] and shown as [DisplaySize.label] so it
     *    reads "1080 × 2400" exactly as it does everywhere else in the app. Null is
     *    [PreviousValueText.Unset] and not a failure: the display had no override, and the revert is
     *    `wm size reset`.
     *  - **The affinity mask** is a hex mask, and the honest rendering is the core indices it names —
     *    [CpuAffinityMask.cores] is the repository's own statement of that. The hex fallback in
     *    [affinityText] is unreachable today, since `CpuClusterLayout.MAX_CORES` is 32 and
     *    [CpuAffinityMask.parse] caps a mask at eight hex digits, so every bit a parsed mask can hold is
     *    a core [CpuAffinityMask.cores] walks; it is written anyway rather than leaving an
     *    `indices.first()` to throw if either of those two numbers ever moves.
     *  - **The charge bypass stays raw, and null says nothing at all.** The stored digit is whatever the
     *    one sysfs node this device happens to have held before GameCore wrote to it, and
     *    `ChargeBypassController` reads `normalValue` and `stopValue` off that node at runtime — so "0"
     *    means charging on some devices and stopped on others. Rendering it as "Charging on" would be a
     *    coin flip presented as a fact. With nothing recorded the revert writes the node's own normal
     *    value, which this layer cannot name, so the card states no value and still offers the revert.
     */
    private fun renderNonSettingValue(
        change: NonSettingChange,
        value: String?,
    ): PreviousValueText = when (change) {
        NonSettingChange.MEDIA_VOLUME -> value?.toIntOrNull()
            ?.let { PreviousValueText.Rendered(Formatters.percentValue(it.toFloat())) }
            ?: PreviousValueText.NotStated

        NonSettingChange.DO_NOT_DISTURB -> doNotDisturbState(value)
            ?.let { PreviousValueText.Rendered(it.label) }
            ?: PreviousValueText.NotStated

        NonSettingChange.DISPLAY_SIZE -> value
            ?.let { text ->
                DisplaySize.parse(text)
                    ?.let { PreviousValueText.Rendered(it.label) }
                    ?: PreviousValueText.Raw(text)
            }
            ?: PreviousValueText.Unset

        NonSettingChange.CPU_AFFINITY -> CpuAffinityMask.parse(value)
            ?.let(::affinityText)
            ?: PreviousValueText.NotStated

        NonSettingChange.CHARGE_BYPASS -> value
            ?.let { PreviousValueText.Raw(it) }
            ?: PreviousValueText.NotStated
    }

    // ------------------------------------------------------------------------------- internals

    /**
     * The non-setting change this row is, or null.
     *
     * Gated on the namespace as well as the key, which `restoreOne` is not — it dispatches on
     * `row.key` alone. The difference only shows up for a row that cannot currently be written, a
     * settings-namespace row holding one of the five non-setting keys: `restoreOne` would reach for the
     * media volume, which is not a revert of that row at all. Neither `record` overload can produce such
     * a row — the typed one always writes a [com.gamecore.core.shizuku.SettingsNamespace] token and the
     * untyped one always writes [RestorePointRepository.NON_SETTING_NAMESPACE] — so this costs nothing
     * today and refuses to promise a revert it cannot deliver if that ever changes.
     */
    private fun nonSettingChangeFor(row: PendingRestore): NonSettingChange? =
        if (row.namespace != RestorePointRepository.NON_SETTING_NAMESPACE) {
            null
        } else {
            NonSettingChange.of(row.key)
        }

    /**
     * The [DoNotDisturbState] a stored name denotes, by exactly the lookup `restoreOne` uses.
     *
     * By name rather than by ordinal, and that is the ledger's rule rather than a preference: rows are
     * stored as strings so they survive a build that reorders or drops an entry, and an ordinal lookup
     * would silently restore the wrong filter after such a change.
     */
    private fun doNotDisturbState(value: String?): DoNotDisturbState? =
        value?.let { name -> DoNotDisturbState.entries.firstOrNull { it.name == name } }

    /** The cores a mask names, or the mask itself when it names none this build can list. */
    private fun affinityText(mask: Int): PreviousValueText {
        val cores = CpuAffinityMask.cores(mask)
        return when {
            cores.isEmpty() -> PreviousValueText.Raw(CpuAffinityMask.hex(mask))
            cores.size == 1 -> PreviousValueText.Rendered("Core ${cores.first()}")
            else -> PreviousValueText.Rendered("Cores ${cores.joinToString(", ")}")
        }
    }

    /** [ValueForm.REFRESH_RATE]'s own reading of 0: not a rate, the absence of a bound. */
    private const val NO_REFRESH_RATE_BOUND = "No bound"

    private const val ON = "On"
    private const val OFF = "Off"

    /** The Surface rotation constants, as `ScreenRotation` names them. */
    private const val ROTATION_0 = 0
    private const val ROTATION_90 = 1
    private const val ROTATION_180 = 2
    private const val ROTATION_270 = 3

    /**
     * The fourth platform colour mode, which [ColorProjection] has no constant for because nothing in
     * GameCore ever writes it — [ValueForm.COLOR_MODE] names it, and a restore has to be able to read
     * back a device that was already using it.
     */
    private const val COLOR_MODE_AUTOMATIC = 3

    /** [ValueForm.DALTONIZER_MODE]'s "-1 disabled". [ColorProjection] holds the four live modes. */
    private const val DALTONIZER_DISABLED = -1
}
