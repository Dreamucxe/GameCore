package com.gamecore.core.shizuku

import android.os.Build
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.model.RootState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects root without ever using it.
 *
 * GameCore does not support root, does not need it, and holds no code path that
 * spawns `su`. This class exists for the one reason the security requirements give:
 * every capability check in this app assumes the OS sandbox is intact, and on a
 * rooted device that assumption is not sound. If `Display.getSupportedModes()`
 * returns one mode, the honest report is different depending on whether that is the
 * platform speaking or a module the user installed rewriting what the platform says.
 * So the finding is surfaced as a caveat on readings and a line on the Shizuku
 * screen, and it changes nothing else.
 *
 * The detection is deliberately passive — filesystem and build-property inspection
 * only. ProcessLens and NEXTERM both *execute* `su -c id`, because for them usable
 * root is a feature and an execution probe is the only thing that proves it. Here
 * running `su` would be wrong twice over: on Magisk the spawn raises the user's grant
 * prompt, which would be GameCore asking for an authority it has no use for and has
 * promised never to take; and the answer to "should readings be caveated" does not
 * depend on whether the request would have been granted.
 *
 * It follows that this is not, and cannot be, a security control. A user who has
 * hidden their root from detection has succeeded, and nothing here depends on
 * catching them — the consequence of a miss is an absent caveat, not a bypassed
 * check.
 */
@Singleton
class RootDetector @Inject constructor(
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * Cached because the answer cannot change without a reboot, and because the
     * capability layer asks on every refresh while a stat-file check costs a syscall
     * each.
     */
    @Volatile
    private var cached: RootState? = null

    suspend fun detect(): RootState = cached ?: withContext(io) {
        val state = when {
            suBinaryPath() != null -> RootState.SU_BINARY_PRESENT
            managerPackagePresent() -> RootState.MANAGER_INSTALLED
            isDebuggableBuild() -> RootState.DEBUGGABLE_BUILD
            else -> RootState.NOT_DETECTED
        }
        cached = state
        state
    }

    /** The last computed answer, or [RootState.NOT_DETECTED] before the first [detect]. */
    fun current(): RootState = cached ?: RootState.NOT_DETECTED

    /**
     * The path of an `su` binary, if one is readable.
     *
     * `canExecute()` rather than `exists()` alone: on several devices `/sbin` is not
     * traversable by an app, so a bare existence check there returns false while the
     * file is plainly present to anyone who can look. Both are attempted and either
     * counts, since a false negative here only costs a caveat.
     */
    private fun suBinaryPath(): String? = SU_PATHS.firstOrNull { path ->
        try {
            val file = File(path)
            file.exists() || file.canExecute()
        } catch (error: Throwable) {
            // A SecurityException from the sandbox is itself uninformative — it means
            // the app cannot look, not that nothing is there.
            false
        }
    }

    /**
     * Whether a root manager's private directory is visible.
     *
     * Checked by directory rather than by `PackageManager`, because package
     * visibility on API 30+ would filter these out unless GameCore declared each of
     * them in `<queries>` — and declaring a list of root managers in the manifest is
     * a permanent, publicly visible statement about an app that does not use root.
     */
    private fun managerPackagePresent(): Boolean = MANAGER_PATHS.any { path ->
        try {
            File(path).exists()
        } catch (error: Throwable) {
            false
        }
    }

    /**
     * A userdebug or eng build, or one whose keys are the public AOSP test keys.
     *
     * Not root as such. The same caveat applies for the same reason: on a build
     * signed with keys anyone has, a system component may not be the one Google
     * shipped, and the platform's answers are correspondingly less authoritative.
     *
     * Read through [Build] rather than `BuildConfig`, so this reflects the device the
     * app is running on rather than how the app itself was compiled — the security
     * requirements are explicit that no behaviour may branch on `BuildConfig.DEBUG`.
     */
    private fun isDebuggableBuild(): Boolean = try {
        val tags = Build.TAGS ?: ""
        tags.contains("test-keys") ||
            Build.TYPE == "userdebug" ||
            Build.TYPE == "eng"
    } catch (error: Throwable) {
        false
    }

    private companion object {
        val SU_PATHS = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/system/sbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/vendor/bin/su",
            "/debug_ramdisk/su",
            "/system_ext/bin/su",
        )

        val MANAGER_PATHS = listOf(
            "/data/adb/magisk",
            "/data/adb/ksu",
            "/data/adb/ap",
            "/sbin/.magisk",
        )
    }
}
