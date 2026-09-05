package com.gamecore.domain.color

import android.os.Build
import com.gamecore.core.common.DataSource
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.common.Observed
import com.gamecore.core.model.ChangeOrigin
import com.gamecore.core.model.ColorCorrection
import com.gamecore.core.model.ColorField
import com.gamecore.core.shizuku.WritableSetting
import com.gamecore.core.system.ColorLimit
import com.gamecore.core.system.ColorPlan
import com.gamecore.core.system.ColorProjection
import com.gamecore.core.system.ColorWrite
import com.gamecore.core.system.SettingsWriteOutcome
import com.gamecore.core.system.SettingsWriter
import com.gamecore.core.system.WriteMechanism
import com.gamecore.data.repository.RestorePointRepository
import com.gamecore.domain.optimization.DeviceKey
import com.gamecore.domain.optimization.DeviceWriteLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies a colour correction to the display, and gives it back afterwards.
 *
 * [ColorProjection] decides *what* to write; this decides *whether*, records what the
 * device is about to lose, and puts back the sinks a new correction no longer needs.
 * Everything reaching the settings provider goes through [SettingsWriter], so every
 * colour write inherits the read-back — a slider that moves and changes nothing is
 * reported as [SettingsWriteOutcome.NotHonoured] rather than as success.
 *
 * Three decisions are worth stating.
 *
 * **A sink is never zeroed to turn it off.** Disengaging a sink restores the value
 * recorded before GameCore first touched it, through the same [RestorePointRepository]
 * every other reversible change uses. Writing a hard-coded neutral instead would set
 * `display_color_mode` to 0 on a device whose user had chosen a vendor mode of 256, and
 * "reverted to the system default" would be a lie about which system.
 *
 * **A sink GameCore never engaged is left alone.** No restore row means the value on the
 * device is the user's own, and clearing it would be GameCore changing a setting in the
 * course of claiming to undo its own changes.
 *
 * **A value that cannot be read is not written.** Same rule as the optimizer: a change
 * with no recorded previous value cannot be put back, so it is refused and said so.
 *
 * **A sink the user has set themselves since GameCore wrote it is the user's.** Night light,
 * greyscale and extra dim are one tap from the notification shade, so a correction applied
 * automatically at the start of a session must not write back over a change made during the
 * last one. Each write claims what it left in [DeviceWriteLog]; an automatic apply reads the
 * claims first and passes over the sinks that no longer hold them. The same log guards the
 * restore, in [com.gamecore.domain.optimization.OptimizationManager], because these rows
 * share its table.
 */
@Singleton
class ColorCorrectionController @Inject constructor(
    private val settings: SettingsWriter,
    private val restorePoints: RestorePointRepository,
    private val writeLog: DeviceWriteLog,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * What this device would do with a correction, without touching anything.
     *
     * Pure and synchronous, so the editor can call it on every slider movement to show
     * which fields this device cannot honour. The API level is read here and passed in,
     * which is what keeps [ColorProjection] unit-testable on the JVM.
     */
    fun preview(correction: ColorCorrection): ColorPlan =
        ColorProjection.plan(correction, Build.VERSION.SDK_INT)

    /**
     * Whether one field has anywhere to go on this device, asked before it is touched.
     *
     * [preview] answers for a correction the user has already set, which means it says
     * nothing about a field still sitting at neutral — see [ColorProjection.limitFor] for
     * why that is the right behaviour there and the wrong one for a control deciding
     * whether to draw itself live. Kept beside [preview] rather than called directly so
     * that `Build.VERSION` stays in this class and out of the UI and the service.
     */
    fun reachability(field: ColorField): ColorLimit? =
        ColorProjection.limitFor(field, Build.VERSION.SDK_INT)

    /**
     * Whether a colour write has a mechanism at all right now.
     *
     * Every colour key lives in `Settings.Secure`, needs WRITE_SECURE_SETTINGS, and is
     * reachable by exactly the two paths [SettingsWriter] knows about — the in-process
     * write once the permission has been granted through Shizuku, and the elevated shell.
     * One key is asked on behalf of all eight because they share a namespace and an
     * access level; asking per key would be eight identical answers.
     *
     * The restricted answer names Shizuku as the unlock, which is true and actionable:
     * `pm grant` from a uid-2000 shell is the only way this permission is ever held by an
     * installed app, and the Shizuku screen has a one-tap button for it.
     */
    suspend fun access(): Observed<WriteMechanism> = withContext(io) {
        val mechanism = settings.mechanismFor(WritableSetting.NIGHT_DISPLAY_ACTIVATED)
        if (mechanism.isUsable) {
            Observed.of(mechanism, DataSource.SETTINGS_PROVIDER)
        } else {
            Observed.needsElevation(
                "Changing the screen's colour needs the WRITE_SECURE_SETTINGS permission, " +
                    "which Android only grants through a shell. GameCore can grant it to " +
                    "itself in one tap once Shizuku is running.",
            )
        }
    }

    /**
     * Applies a correction: writes what this device can express, puts back what a
     * previous correction engaged and this one does not.
     *
     * Never short-circuits on a failing sink, for the same reason the optimizer does not:
     * a correction that applied its warmth and could not apply its dimming, and says so,
     * is more use than one that stopped at the first obstacle. [packageName] is the game
     * the change was made for, stored with each restore point so session history can
     * attribute a setting that is still pending.
     *
     * [origin] is what separates the user asking for this from a profile asserting it. A
     * correction the user applied — from the colour screen, or from the panel over the game —
     * is their most recent instruction and is written whatever the sinks currently hold. An
     * automatic one passes over the sinks [keptByUser] finds the user has taken over since.
     */
    suspend fun apply(
        correction: ColorCorrection,
        packageName: String? = null,
        origin: ChangeOrigin = ChangeOrigin.USER,
    ): ColorApplyResult = withContext(io) {
        val plan = preview(correction)
        val access = access()
        if (access !is Observed.Value) {
            return@withContext ColorApplyResult(plan, emptyList(), emptyList(), access)
        }
        val kept = if (origin == ChangeOrigin.AUTOMATIC) keptByUser(plan) else emptyList()
        val results = plan.writes
            .filterNot { it.setting in kept }
            .map { applyOne(it, access.value, packageName) }
        ColorApplyResult(
            plan = plan,
            results = results,
            restored = disengageAllExcept(plan.sinks + kept),
            access = access,
            keptByUser = kept,
        )
    }

    /**
     * Which colour sinks the user has set themselves since GameCore last wrote them.
     *
     * Asked of two sets at once, because both have the same consequence. The sinks this
     * correction wants to write are the reported half: re-asserting one over a change made
     * from the shade is the bug. The sinks a *previous* correction engaged and this one does
     * not are the other half — handing one of those back writes the pre-session value over
     * the user's own, which from the outside looks the same as GameCore turning the thing on
     * again.
     *
     * Only keys [writeLog] holds a claim for are read, so this costs nothing on a device
     * where GameCore has not written a colour key yet. An unreadable key is not evidence of
     * anything and is left to [applyOne], which refuses it for want of a restore point.
     */
    private suspend fun keptByUser(plan: ColorPlan): List<WritableSetting> {
        val claimed = writeLog.current()
        return (plan.writes.map { it.setting } + ColorProjection.ALL_SINKS)
            .distinct()
            .filter { claimed.tracks(DeviceKey.of(it)) }
            .filter { setting ->
                val live = settings.read(setting)
                live is Observed.Value && claimed.userChanged(DeviceKey.of(setting), live.value)
            }
    }

    /**
     * Puts the display back the way GameCore found it.
     *
     * The "reset to default" the user asks for, and the revert a game profile performs
     * when its game exits. Restores every colour sink with a recorded value and touches
     * nothing else.
     */
    suspend fun clear(): ColorApplyResult = withContext(io) {
        val empty = ColorPlan(writes = emptyList(), limits = emptyList())
        val access = access()
        if (access !is Observed.Value) {
            return@withContext ColorApplyResult(empty, emptyList(), emptyList(), access)
        }
        ColorApplyResult(
            plan = empty,
            results = emptyList(),
            restored = disengageAllExcept(emptyList()),
            access = access,
        )
    }

    /**
     * Whether GameCore itself has a colour change outstanding, cheaply.
     *
     * [engagement] answers the richer question — what the *display* is doing, whoever set
     * it — and pays for it: a colour key that is unset reads as null through the provider
     * and falls back to a shell round trip, so asking about all eight can be eight shell
     * calls. That is affordable on a settings screen and not behind a control panel
     * sitting over a game, which is the cost §26 rules out.
     *
     * This is the narrower question a toggle needs: *is the correction I applied still in
     * force*. A restore row exists for exactly the sinks GameCore changed and has not put
     * back, so this is one indexed query, and it is dark on a device that refused the
     * writes and dark again the moment [clear] succeeds. It deliberately says nothing
     * about a warmth the ROM's own night schedule is applying — a toggle that lit for that
     * would be offering to undo something GameCore never did.
     */
    suspend fun isEngagedByGameCore(): Boolean = withContext(io) {
        restorePoints.pending().any { it.setting != null && it.setting in ColorProjection.ALL_SINKS }
    }

    /**
     * What the display is currently doing, read from the device rather than from what
     * GameCore last asked for.
     *
     * Reads need no permission, so this works on a device that cannot write a single
     * colour key — which is the point: a user whose ROM has night display on from
     * Android's own schedule should see that, not "nothing is active".
     */
    suspend fun engagement(): Observed<ColorEngagement> = withContext(io) {
        val values = mutableMapOf<WritableSetting, String>()
        var read = false
        var failure: Observed.Failed? = null
        for (sink in ColorProjection.ALL_SINKS) {
            when (val observed = settings.read(sink)) {
                is Observed.Value -> {
                    read = true
                    values[sink] = observed.value.trim()
                }
                // An unset key is a real state, not a failure: the platform is using its
                // own default, which is the same thing as "this is not engaged".
                is Observed.Restricted -> read = true
                is Observed.Failed -> if (failure == null) failure = observed
            }
        }
        if (!read) {
            return@withContext failure
                ?: Observed.Failed("The display's colour state could not be read.")
        }
        Observed.of(
            ColorEngagement(
                engaged = describeEngaged(values),
                ownedByGameCore = restorePoints.pending()
                    .count { it.setting != null && it.setting in ColorProjection.ALL_SINKS },
            ),
            DataSource.SETTINGS_PROVIDER,
        )
    }

    /**
     * The engaged sinks in the user's words.
     *
     * Reads the pair of keys behind each feature together, because the enable flag alone
     * says nothing useful: "night display on" is a fact, and "night display at 3490K" is
     * the same fact with the part the user actually set.
     */
    private fun describeEngaged(values: Map<WritableSetting, String>): List<String> {
        val engaged = mutableListOf<String>()
        if (values[WritableSetting.NIGHT_DISPLAY_ACTIVATED] == "1") {
            val kelvin = values[WritableSetting.NIGHT_DISPLAY_COLOR_TEMPERATURE]
            engaged += if (kelvin != null) "night display at ${kelvin}K" else "night display on"
        }
        when (values[WritableSetting.DISPLAY_COLOR_MODE]?.toIntOrNull()) {
            ColorProjection.MODE_BOOSTED -> engaged += "boosted colour"
            ColorProjection.MODE_SATURATED -> engaged += "saturated colour"
            else -> Unit
        }
        if (values[WritableSetting.DALTONIZER_ENABLED] == "1") {
            engaged += describeDaltonizer(values[WritableSetting.DALTONIZER_MODE])
        }
        if (values[WritableSetting.REDUCE_BRIGHT_COLORS_ACTIVATED] == "1") {
            val level = values[WritableSetting.REDUCE_BRIGHT_COLORS_LEVEL]
            engaged += if (level != null) "extra dimming at $level%" else "extra dimming on"
        }
        if (values[WritableSetting.COLOR_INVERSION_ENABLED] == "1") {
            engaged += "inverted colours"
        }
        return engaged.toList()
    }

    private fun describeDaltonizer(raw: String?): String = when (raw?.toIntOrNull()) {
        ColorProjection.MODE_MONOCHROMACY -> "greyscale"
        ColorProjection.MODE_PROTANOMALY -> "the protanopia filter"
        ColorProjection.MODE_DEUTERANOMALY -> "the deuteranopia filter"
        ColorProjection.MODE_TRITANOMALY -> "the tritanopia filter"
        else -> "a colour-vision filter"
    }

    // ------------------------------------------------------------ one sink at a time

    /**
     * Records the previous value, then writes — in that order, always.
     *
     * A restore point taken after a successful write holds the value GameCore itself
     * wrote, and the device never gets its own setting back. The one refusal is an
     * unreadable key: with nothing recorded there is nothing to put back, and this app
     * does not make changes it cannot undo.
     *
     * A value that already matches is reported as applied without spending a write. That
     * is not tidiness — the panel's colour sliders apply as they move, and re-writing
     * `night_display_activated=1` on every frame of a drag is a binder call per frame for
     * no change at all.
     *
     * Every path out of here except the refusal ends in a [claim], so that the next
     * automatic correction and the session's restore can both tell what GameCore left on
     * this sink from what the user set afterwards.
     */
    private suspend fun applyOne(
        write: ColorWrite,
        mechanism: WriteMechanism,
        packageName: String?,
    ): ColorSinkResult {
        val current = settings.read(write.setting)
        when (current) {
            is Observed.Value -> restorePoints.record(write.setting, current.value, packageName)
            is Observed.Restricted -> restorePoints.record(write.setting, null, packageName)
            is Observed.Failed -> return ColorSinkResult(
                write = write,
                outcome = SettingsWriteOutcome.Failed(
                    "GameCore could not read ${write.setting.userDescription}, so it did " +
                        "not change it — a change it cannot put back is not one it will make.",
                ),
            )
        }
        if (current is Observed.Value && current.value.trim() == write.value) {
            writeLog.wrote(DeviceKey.of(write.setting), current.value)
            return ColorSinkResult(
                write = write,
                outcome = SettingsWriteOutcome.Applied(
                    value = write.value,
                    previousValue = current.value,
                    mechanism = mechanism,
                ),
            )
        }
        val outcome = settings.write(write.setting, write.value)
        claim(write.setting, outcome, current)
        return ColorSinkResult(write, outcome)
    }

    /**
     * Tells [writeLog] what this sink holds now, taking the outcome's word for it.
     *
     * Never the value that was requested: devices normalise, and a claim that disagreed
     * with what a read returns would call the sink the user's on the next correction.
     * [before] is the read taken a moment ago, which is still the answer for the outcomes
     * where nothing reached the provider.
     *
     * A write that went through unverified claims nothing. There is no value GameCore can
     * stand behind, and no claim is the safe way round — the sink stays writable and its
     * restore row stays restorable, which is how it behaved before this log existed.
     */
    private fun claim(
        setting: WritableSetting,
        outcome: SettingsWriteOutcome,
        before: Observed<String>,
    ) {
        val key = DeviceKey.of(setting)
        when (outcome) {
            is SettingsWriteOutcome.Applied -> writeLog.wrote(key, outcome.value)
            is SettingsWriteOutcome.NotHonoured -> writeLog.wrote(key, outcome.actual)
            is SettingsWriteOutcome.AppliedUnverified -> writeLog.release(key)
            is SettingsWriteOutcome.RequiresAccess,
            is SettingsWriteOutcome.Rejected,
            is SettingsWriteOutcome.Failed,
            -> when (before) {
                is Observed.Value -> writeLog.wrote(key, before.value)
                is Observed.Restricted -> writeLog.wrote(key, null)
                is Observed.Failed -> writeLog.release(key)
            }
        }
    }

    /**
     * Puts back every colour sink GameCore owns and this correction does not need.
     *
     * "Owns" means there is a restore row for it, which exists only because GameCore
     * wrote the key at some point. A row is cleared only when the value has gone back and
     * been read back; anything else stays pending, so the dashboard's "settings still
     * changed" count stays true and the next restore retries it.
     *
     * A sink that is genuinely back gives up its claim in [writeLog] with its row. The
     * device now holds the user's own pre-session value, and a claim left behind would have
     * the next automatic correction read that value, decide the user had just set it, and
     * pass over the sink for the rest of the process — the guard turning into a worse
     * version of the bug it was added for.
     */
    private suspend fun disengageAllExcept(engaged: List<WritableSetting>): List<ColorRestore> =
        restorePoints.pending()
            .filter { row ->
                val setting = row.setting
                setting != null && setting in ColorProjection.ALL_SINKS && setting !in engaged
            }
            .mapNotNull { row ->
                val setting = row.setting ?: return@mapNotNull null
                val outcome = settings.restore(setting, row.previousValue)
                val done = outcome is SettingsWriteOutcome.Applied ||
                    (outcome is SettingsWriteOutcome.AppliedUnverified && row.restoresToUnset)
                if (done) {
                    restorePoints.clear(setting)
                    writeLog.release(DeviceKey.of(setting))
                }
                ColorRestore(setting = setting, outcome = outcome, cleared = done)
            }
}

/**
 * One sink's write and what the settings provider did with it.
 *
 * Keeps the [ColorWrite] alongside the outcome so the UI can say which part of the
 * correction failed — "the night display could not be set" rather than "something failed".
 */
data class ColorSinkResult(
    val write: ColorWrite,
    val outcome: SettingsWriteOutcome,
) {
    val setting: WritableSetting get() = write.setting

    /** Unverified counts as applied here; [SettingsWriteOutcome] keeps the distinction. */
    val isApplied: Boolean
        get() = outcome is SettingsWriteOutcome.Applied ||
            outcome is SettingsWriteOutcome.AppliedUnverified
}

/** One sink handed back, and whether its restore row could be cleared. */
data class ColorRestore(
    val setting: WritableSetting,
    val outcome: SettingsWriteOutcome,
    val cleared: Boolean,
)

/**
 * What the display is doing now, as read from the device.
 *
 * [ownedByGameCore] is how many colour sinks have a restore row — the difference between
 * "the screen is warm because GameCore made it warm" and "the screen is warm because
 * Android's own night-display schedule is running", which the UI needs in order to avoid
 * offering to undo something it did not do.
 */
data class ColorEngagement(
    val engaged: List<String>,
    val ownedByGameCore: Int,
) {
    val isActive: Boolean get() = engaged.isNotEmpty()

    val summary: String
        get() = when {
            engaged.isEmpty() -> "No colour correction is active."
            else -> engaged.joinToString(", ").replaceFirstChar { it.uppercaseChar() } + "."
        }
}

/**
 * Everything one apply did: the plan, the per-sink outcomes, the sinks handed back, the sinks
 * left to the user, and whether GameCore was allowed to write at all.
 *
 * The five together are what makes the feature honest. [ColorPlan.limits] says what this
 * device cannot express, [results] says what happened to what it can, [keptByUser] says what
 * was deliberately not touched, [access] says whether the question even got as far as the
 * provider, and [message] turns the lot into the one sentence the panel has room for.
 */
data class ColorApplyResult(
    val plan: ColorPlan,
    val results: List<ColorSinkResult>,
    val restored: List<ColorRestore>,
    val access: Observed<WriteMechanism>,
    /**
     * Sinks passed over because the user had set them since GameCore did.
     *
     * Always empty for a correction the user asked for: the guard runs on automatic applies
     * only, because an explicit instruction is the most recent thing the user has said about
     * the display and outranks anything they said before it.
     */
    val keptByUser: List<WritableSetting> = emptyList(),
) {
    val isBlocked: Boolean get() = access !is Observed.Value

    val appliedCount: Int get() = results.count { it.isApplied }

    val failures: List<ColorSinkResult> get() = results.filterNot { it.isApplied }

    /** True when every write this device could take went through. */
    val isApplied: Boolean get() = !isBlocked && failures.isEmpty()

    /**
     * One sentence, in this order of priority: no access, nothing applicable, nothing left to
     * write, partial failure, applied. Limits are mentioned but never lead — the user asked
     * for a change and wants to know whether it happened first.
     */
    val message: String
        get() = when {
            access is Observed.Restricted -> access.detail
            access is Observed.Failed -> access.detail
            plan.writes.isEmpty() && plan.limits.isNotEmpty() ->
                "Nothing in this correction can be applied on this device."
            plan.writes.isEmpty() -> "Colour correction is off."
            results.isEmpty() && keptByUser.isNotEmpty() ->
                "You changed the screen's colour yourself after GameCore set it, so it has " +
                    "been left alone."
            failures.isNotEmpty() -> failures.first().let { failure ->
                val what = failure.setting.userDescription
                val sentence = if (appliedCount > 0) {
                    "Applied $appliedCount of ${results.size} changes. $what did not take."
                } else {
                    "$what could not be changed."
                }
                sentence + keptClause
            }
            plan.limits.isNotEmpty() ->
                "Applied. ${plan.limits.size} of the values you set have no equivalent on " +
                    "this device." + keptClause
            else -> "Applied.$keptClause"
        }

    /**
     * The sinks left to the user, as a clause the sentences above can append.
     *
     * Counted rather than named. [WritableSetting.userDescription] is a whole sentence with a
     * full stop of its own, which reads as a stutter mid-sentence, and the panel has one line.
     */
    private val keptClause: String
        get() = when (keptByUser.size) {
            0 -> ""
            1 -> " One value was left as you set it."
            else -> " ${keptByUser.size} values were left as you set them."
        }
}

