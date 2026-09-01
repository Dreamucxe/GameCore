package com.gamecore.core.shizuku

import com.gamecore.core.common.AccessLevel

/**
 * The outcome of running one [ShellCommand].
 *
 * [stdout] and [stderr] are deliberately never logged anywhere in this app. A
 * `dumpsys` dump contains installed package names, account hints and window titles,
 * and the security requirements forbid writing any of that to logcat, where every
 * app with READ_LOGS — and every user with adb — can read it. Callers parse the
 * text and keep the parsed figure; the text itself goes out of scope.
 */
data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val accessLevel: AccessLevel,
    /** Wall-clock cost, so a slow `dumpsys` can be reported rather than guessed at. */
    val durationMillis: Long = 0L,
) {
    val isSuccess: Boolean get() = exitCode == 0

    /** Non-blank lines, which is what every parser here actually wants. */
    fun lines(): List<String> = stdout.lineSequence().filter { it.isNotBlank() }.toList()

    /**
     * The single value a `settings get` or `getprop` returns, or null. `settings
     * get` prints the literal string "null" for an unset key, which is not the same
     * as a failed read and must not be parsed as one.
     */
    fun singleValue(): String? {
        if (!isSuccess) return null
        val trimmed = stdout.trim()
        return if (trimmed.isEmpty() || trimmed == "null") null else trimmed
    }

    /**
     * Why this failed, in words a user can act on. Never includes [stdout]: a
     * failure message is shown on screen, and a dump belongs nowhere near the UI.
     */
    fun failureReason(): String = when {
        isSuccess -> ""
        stderr.isNotBlank() -> stderr.trim().lineSequence().first().take(200)
        exitCode == TIMEOUT_EXIT_CODE -> "The command did not finish in time."
        else -> "The command failed with exit code $exitCode."
    }

    companion object {
        const val TIMEOUT_EXIT_CODE = -2

        fun failure(detail: String, level: AccessLevel) = ShellResult(-1, "", detail, level)

        fun timeout(level: AccessLevel, millis: Long) = ShellResult(
            exitCode = TIMEOUT_EXIT_CODE,
            stdout = "",
            stderr = "Timed out after $millis ms",
            accessLevel = level,
            durationMillis = millis,
        )
    }
}

/**
 * An elevated shell.
 *
 * One implementation exists — Shizuku, which is ADB-level authority the user starts
 * themselves — and it is entirely optional: GameCore is fully functional without
 * it, and every caller of this interface has a standard-Android path to fall back
 * to.
 *
 * Note the parameter type. [execute] takes a [ShellCommand], not a string and not
 * an argument vector, so there is no expressible way to run a command that is not
 * one of the enumerated cases in [ShellCommand]. "GameCore never runs arbitrary
 * shell commands" is therefore a property of the type system rather than a promise
 * in a comment, and the UI — which cannot construct the write-effect commands
 * without going through the optimization layer — cannot reach a shell at all.
 */
interface ElevatedShell {

    val accessLevel: AccessLevel

    /** Cheap liveness check. Must not prompt the user and must not block on a binder. */
    suspend fun isAvailable(): Boolean

    suspend fun execute(command: ShellCommand, timeoutMillis: Long = DEFAULT_TIMEOUT): ShellResult

    companion object {
        const val DEFAULT_TIMEOUT = 5_000L

        /** `dumpsys display` and `dumpsys SurfaceFlinger` are slow on some devices. */
        const val LONG_TIMEOUT = 15_000L
    }
}
