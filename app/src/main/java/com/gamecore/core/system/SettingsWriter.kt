package com.gamecore.core.system

import android.content.Context
import android.provider.Settings
import com.gamecore.core.common.AccessLevel
import com.gamecore.core.common.DataSource
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.common.Observed
import com.gamecore.core.permissions.PermissionChecker
import com.gamecore.core.shizuku.ElevatedShell
import com.gamecore.core.shizuku.SettingsNamespace
import com.gamecore.core.shizuku.ShellCommand
import com.gamecore.core.shizuku.WritableSetting
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The only place GameCore writes to the settings provider.
 *
 * Three mechanisms reach these keys and none works for all of them. An app holding
 * WRITE_SETTINGS can write `Settings.System` directly. `Settings.Global` needs
 * WRITE_SECURE_SETTINGS, so every global key requires the elevated shell. `Settings
 * .Secure` — the colour keys — needs the same permission, but that one *can* be held by
 * an installed app: WRITE_SECURE_SETTINGS is declared `signature|privileged|development`
 * and the `development` flag is what makes `pm grant` from a uid-2000 shell succeed. So
 * once the user has granted it through Shizuku once, the secure writes happen in this
 * process, which is the only path fast enough for a slider that applies as it moves.
 * [WritableSetting.requiredAccess] and the namespace decide which path is taken — so no
 * caller has to know, and no caller can pick wrong.
 *
 * Three rules hold for every write:
 *
 *  1. **The value is validated before the write, by the setting's own declared form.**
 *     A malformed value that reaches `settings put` outlives GameCore's process and
 *     possibly its installation.
 *  2. **The previous value is read first and returned to the caller.** That is what makes
 *     a game profile reversible: [SettingsWriteOutcome.Applied.previousValue] is what the
 *     restore writes back when the game exits.
 *  3. **A write is never reported as applied because it did not throw.** `Settings
 *     .System.putString` returns a boolean that some builds return true from without
 *     honouring the write, and `settings put` exits 0 for a key the platform then
 *     ignores — so the value is read back and compared.
 */
@Singleton
class SettingsWriter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shell: ElevatedShell,
    private val permissions: PermissionChecker,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * The current value, or the reason it could not be read.
     *
     * Reads never need a permission — `Settings.System.getString` is open to any app —
     * so this is the one direction that always works, and it is what makes a restore
     * possible even for keys GameCore cannot itself write.
     */
    suspend fun read(setting: WritableSetting): Observed<String> = withContext(io) {
        val direct = try {
            when (setting.namespace) {
                SettingsNamespace.SYSTEM ->
                    Settings.System.getString(context.contentResolver, setting.key)
                SettingsNamespace.GLOBAL ->
                    Settings.Global.getString(context.contentResolver, setting.key)
                SettingsNamespace.SECURE ->
                    Settings.Secure.getString(context.contentResolver, setting.key)
            }
        } catch (error: Throwable) {
            null
        }
        if (direct != null && direct.isNotBlank()) {
            return@withContext Observed.of(direct, DataSource.SETTINGS_PROVIDER)
        }

        // An unset key reads as null through the provider and prints "null" through the
        // shell. Neither is a failure: the platform is using its own default.
        if (!shell.isAvailable()) {
            return@withContext Observed.notPresent(
                "${setting.key} has no value set on this device",
            )
        }
        val result = shell.execute(ShellCommand.getSetting(setting))
        val value = result.singleValue()
        when {
            value != null -> Observed.of(value, DataSource.SHELL_SHIZUKU)
            result.isSuccess -> Observed.notPresent(
                "${setting.key} has no value set on this device",
            )
            else -> Observed.Failed("${setting.key} could not be read", result.failureReason())
        }
    }

    /**
     * Which mechanism would be used for this setting right now, without writing anything.
     *
     * The capability layer asks so the UI can say "this needs Shizuku on your device"
     * before the user taps something that will fail.
     */
    suspend fun mechanismFor(setting: WritableSetting): WriteMechanism = withContext(io) {
        when {
            setting.requiredAccess == AccessLevel.NORMAL &&
                setting.namespace == SettingsNamespace.SYSTEM &&
                permissions.hasWriteSettings() -> WriteMechanism.WRITE_SETTINGS
            // Ahead of the shell deliberately: an in-process write costs a binder call
            // to the settings provider, where the shell path costs a process launch.
            // That difference is what a colour slider applying as it moves depends on.
            setting.namespace == SettingsNamespace.SECURE &&
                permissions.hasWriteSecureSettings() -> WriteMechanism.WRITE_SECURE_SETTINGS
            shell.isAvailable() -> WriteMechanism.SHIZUKU
            setting.requiredAccess == AccessLevel.NORMAL &&
                setting.namespace == SettingsNamespace.SYSTEM -> WriteMechanism.NEEDS_WRITE_SETTINGS
            else -> WriteMechanism.NEEDS_SHIZUKU
        }
    }

    /**
     * Writes one setting and confirms it.
     *
     * The read-back is not belt-and-braces. Vendor builds accept writes to
     * `peak_refresh_rate` and leave the value unchanged, and the settings provider
     * normalises some values on the way in — a float written as "60" reads back as
     * "60.0" — so the comparison is numeric where the form is numeric and exact
     * otherwise. A value that did not take is reported as
     * [SettingsWriteOutcome.NotHonoured], which is the truth and is what lets the
     * refresh-rate controller escalate rather than claim success.
     */
    suspend fun write(setting: WritableSetting, value: String): SettingsWriteOutcome =
        withContext(io) {
            if (!setting.accepts(value)) {
                return@withContext SettingsWriteOutcome.Rejected(
                    "\"$value\" is not a valid value for ${setting.key}.",
                )
            }

            val previous = read(setting)
            val previousValue = (previous as? Observed.Value)?.value

            when (mechanismFor(setting)) {
                WriteMechanism.WRITE_SETTINGS -> {
                    val wrote = try {
                        Settings.System.putString(context.contentResolver, setting.key, value)
                    } catch (error: SecurityException) {
                        return@withContext SettingsWriteOutcome.RequiresAccess(
                            detail = "GameCore needs permission to modify system settings.",
                            needsShizuku = false,
                        )
                    } catch (error: Throwable) {
                        return@withContext SettingsWriteOutcome.Failed(
                            "${setting.key} could not be written.",
                        )
                    }
                    if (!wrote) {
                        return@withContext SettingsWriteOutcome.Failed(
                            "The settings provider rejected the write to ${setting.key}.",
                        )
                    }
                    confirm(setting, value, previousValue, WriteMechanism.WRITE_SETTINGS)
                }

                WriteMechanism.SHIZUKU -> writeThroughShell(setting, value, previousValue)

                /**
                 * The secure keys, written in this process because the user granted
                 * WRITE_SECURE_SETTINGS through Shizuku at some earlier point.
                 *
                 * Both failure paths fall back to the shell rather than reporting a
                 * refusal, because the grant is revocable: Shizuku's own manager and
                 * several OEM permission managers can take it away between two
                 * movements of a slider, and the app that notices by taking a
                 * SecurityException is the app that keeps working.
                 */
                WriteMechanism.WRITE_SECURE_SETTINGS -> {
                    val wrote = try {
                        Settings.Secure.putString(context.contentResolver, setting.key, value)
                    } catch (error: SecurityException) {
                        return@withContext writeThroughShell(setting, value, previousValue)
                    } catch (error: Throwable) {
                        return@withContext SettingsWriteOutcome.Failed(
                            "${setting.key} could not be written.",
                        )
                    }
                    if (!wrote) {
                        return@withContext writeThroughShell(setting, value, previousValue)
                    }
                    confirm(setting, value, previousValue, WriteMechanism.WRITE_SECURE_SETTINGS)
                }

                WriteMechanism.NEEDS_WRITE_SETTINGS -> SettingsWriteOutcome.RequiresAccess(
                    detail = "Change this by allowing GameCore to modify system settings.",
                    needsShizuku = false,
                )

                WriteMechanism.NEEDS_SHIZUKU -> SettingsWriteOutcome.RequiresAccess(
                    detail = "Android does not let an ordinary app change ${setting.key}. " +
                        "With Shizuku running, GameCore can set it directly.",
                    needsShizuku = true,
                )
            }
        }

    /**
     * Restores a previously captured value, or the setting's documented default when no
     * reading was captured — which happens when GameCore was killed between the write
     * and the restore. The default is the platform's own, not a GameCore invention.
     */
    suspend fun restore(setting: WritableSetting, previousValue: String?): SettingsWriteOutcome =
        write(setting, previousValue ?: setting.restoreDefault)

    /**
     * The elevated-shell write, and the answer when there is no shell to write with.
     *
     * Its own function because two mechanisms end here: the keys that always need
     * ADB-level authority, and the secure keys whose in-process grant has just been
     * found to be gone.
     */
    private suspend fun writeThroughShell(
        setting: WritableSetting,
        value: String,
        previousValue: String?,
    ): SettingsWriteOutcome {
        if (!shell.isAvailable()) {
            return SettingsWriteOutcome.RequiresAccess(
                detail = "Android does not let an ordinary app change ${setting.key}. " +
                    "With Shizuku running, GameCore can set it directly.",
                needsShizuku = true,
            )
        }
        val command = ShellCommand.putSetting(setting, value)
            ?: return SettingsWriteOutcome.Rejected(
                "\"$value\" is not a valid value for ${setting.key}.",
            )
        val result = shell.execute(command)
        if (!result.isSuccess) {
            return SettingsWriteOutcome.Failed(result.failureReason())
        }
        return confirm(setting, value, previousValue, WriteMechanism.SHIZUKU)
    }

    private suspend fun confirm(
        setting: WritableSetting,
        expected: String,
        previousValue: String?,
        mechanism: WriteMechanism,
    ): SettingsWriteOutcome {
        val readBack = read(setting)
        val actual = (readBack as? Observed.Value)?.value
        return when {
            actual == null -> SettingsWriteOutcome.AppliedUnverified(
                value = expected,
                previousValue = previousValue,
                mechanism = mechanism,
                reason = "the value could not be read back",
            )
            valuesMatch(expected, actual) -> SettingsWriteOutcome.Applied(
                value = actual,
                previousValue = previousValue,
                mechanism = mechanism,
            )
            else -> SettingsWriteOutcome.NotHonoured(
                requested = expected,
                actual = actual,
                mechanism = mechanism,
            )
        }
    }

    /**
     * Compares numerically when both sides parse as numbers, exactly otherwise.
     *
     * The settings provider normalises: "60" written to a float key reads back "60.0",
     * and "1" written to an animation scale reads back "1.0". Comparing those as strings
     * would report every successful float write as unhonoured.
     */
    private fun valuesMatch(expected: String, actual: String): Boolean {
        if (expected == actual) return true
        val a = expected.toFloatOrNull()
        val b = actual.toFloatOrNull()
        return a != null && b != null && kotlin.math.abs(a - b) < 0.01f
    }
}

/** Which path a write took, or would need. Surfaced so the UI can explain a refusal. */
enum class WriteMechanism(val label: String) {
    WRITE_SETTINGS("Modify system settings"),
    WRITE_SECURE_SETTINGS("Secure settings permission"),
    SHIZUKU("Shizuku"),
    NEEDS_WRITE_SETTINGS("Needs modify-system-settings"),
    NEEDS_SHIZUKU("Needs Shizuku"),
    ;

    val isUsable: Boolean
        get() = this == WRITE_SETTINGS || this == WRITE_SECURE_SETTINGS || this == SHIZUKU
}

/**
 * What a settings write did.
 *
 * [Applied] means the value was read back and matched. Nothing else means success, and
 * in particular there is no case for "the call returned without throwing" — that is
 * exactly the claim that makes a refresh-rate button lie on a MediaTek device.
 */
sealed interface SettingsWriteOutcome {

    data class Applied(
        val value: String,
        /** What to write back on restore. Null when the key had no value set. */
        val previousValue: String?,
        val mechanism: WriteMechanism,
    ) : SettingsWriteOutcome

    /** The write went through and the value did not change. */
    data class NotHonoured(
        val requested: String,
        val actual: String,
        val mechanism: WriteMechanism,
    ) : SettingsWriteOutcome

    /** Written, but the result could not be read back. Shown as unconfirmed, not as done. */
    data class AppliedUnverified(
        val value: String,
        val previousValue: String?,
        val mechanism: WriteMechanism,
        val reason: String,
    ) : SettingsWriteOutcome

    /** The value failed its own form check. A GameCore bug, not a device limitation. */
    data class Rejected(val detail: String) : SettingsWriteOutcome

    data class RequiresAccess(val detail: String, val needsShizuku: Boolean) : SettingsWriteOutcome

    data class Failed(val detail: String) : SettingsWriteOutcome

    val isApplied: Boolean get() = this is Applied

    /** The value to write back when unwinding, or null when nothing was changed. */
    val restoreValue: String?
        get() = when (this) {
            is Applied -> previousValue
            is AppliedUnverified -> previousValue
            else -> null
        }
}
