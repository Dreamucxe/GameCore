package com.gamecore.core.diagnostics

import com.gamecore.core.shizuku.ElevatedShell
import com.gamecore.core.shizuku.ShellCommand
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** One `FATAL EXCEPTION` block lifted out of the crash buffer. */
data class ParsedCrash(
    val atMillis: Long,
    val header: String,
    val body: String,
)

/**
 * Reads the system crash buffer and keeps only this game's crash.
 *
 * ## Why this needs the shell
 *
 * A normal app cannot read another app's crash. The crash goes to the `crash` logcat buffer, which is
 * owned by the shell, and Android offers no per-app callback for "the game I was watching died". With
 * Shizuku GameCore already has an ADB-level shell for the user who chose it; the same authority can
 * read that buffer. Without it, this class writes the session boundary and says nothing about a crash
 * it could not see. That limitation is real and is not papered over.
 *
 * ## Why the parser is separate
 *
 * Parsing logcat is where this feature would quietly invent evidence: a crash from yesterday, a
 * different app's process, a line that merely contains the package name. [CrashLogParser] is pure and
 * tested against those cases, so the recorder only has to decide *when* to call it.
 */
@Singleton
class GameCrashRecorder @Inject constructor(
    private val shell: ElevatedShell,
    private val logs: LogStore,
) {

    fun onGameStarted(packageName: String, label: String, atMillis: Long) {
        logs.append(
            logs.note(
                kind = LogKind.GAME_SESSION,
                level = LogLevel.INFO,
                title = "Session started: $label",
                detail = "Package: $packageName\nStarted: ${timestamp(atMillis)}",
                packageName = packageName,
                atMillis = atMillis,
            ),
        )
    }

    /**
     * Records the end of a session, then looks for a crash that happened inside it.
     *
     * The crash check is bounded and best-effort: a slow or absent shell costs the crash record, not
     * the session close. The window is intentionally a minute wider on each side because a native
     * game can crash just before Android reports the activity gone, and the crash buffer's timestamp
     * is the process's, not the detector's.
     */
    suspend fun onGameStopped(
        packageName: String,
        label: String,
        startedAtMillis: Long,
        endedAtMillis: Long,
        reason: String,
    ) {
        val duration = ((endedAtMillis - startedAtMillis).coerceAtLeast(0L) / 1000L)
        logs.append(
            logs.note(
                kind = LogKind.GAME_SESSION,
                level = LogLevel.INFO,
                title = "Session ended: $label",
                detail = buildString {
                    appendLine("Package: $packageName")
                    appendLine("Started: ${timestamp(startedAtMillis)}")
                    appendLine("Ended: ${timestamp(endedAtMillis)}")
                    appendLine("Duration: ${duration}s")
                    append("Reason: $reason")
                },
                packageName = packageName,
                atMillis = endedAtMillis,
            ),
        )
        if (!logs.enabled.value) return

        val result = runCatching {
            shell.execute(ShellCommand.CrashBuffer, ElevatedShell.LONG_TIMEOUT)
        }.getOrNull() ?: return
        if (!result.isSuccess) return

        CrashLogParser.parse(
            stdout = result.stdout,
            packageName = packageName,
            fromMillis = startedAtMillis - WINDOW_SLACK_MILLIS,
            toMillis = endedAtMillis + WINDOW_SLACK_MILLIS,
        ).forEach { crash ->
            logs.append(
                logs.crashRecord(
                    kind = LogKind.GAME_CRASH,
                    title = "${crash.header.take(180)} — $label",
                    detail = crash.body,
                    packageName = packageName,
                    atMillis = crash.atMillis,
                ),
            )
        }
    }

    private fun timestamp(atMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(atMillis)

    private companion object {
        const val WINDOW_SLACK_MILLIS = 60_000L
    }
}

/**
 * A deliberately conservative parser for `logcat -b crash -v threadtime`.
 *
 * Three rules keep it from claiming the wrong crash:
 *
 *  1. A block starts at `FATAL EXCEPTION` and ends at the next one. Everything else is ignored.
 *  2. A block belongs to [packageName] only when it names `Process: <packageName>` or contains the
 *     package as a whole token. A substring match would attribute `com.foo` to `com.foobar`.
 *  3. A block outside the session window is dropped. The crash buffer survives across sessions, and
 *     yesterday's crash must not be reported as this game's.
 */
object CrashLogParser {

    private val timePattern = Regex("""^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})""")

    fun parse(
        stdout: String,
        packageName: String,
        fromMillis: Long,
        toMillis: Long,
    ): List<ParsedCrash> {
        if (stdout.isBlank() || packageName.isBlank()) return emptyList()
        val blocks = ArrayList<MutableList<String>>()
        var current: MutableList<String>? = null
        stdout.lineSequence().forEach { line ->
            if (line.contains("FATAL EXCEPTION")) {
                current = ArrayList<String>().also {
                    it += line
                    blocks += it
                }
            } else {
                current?.add(line)
            }
        }

        return blocks.mapNotNull { block ->
            val body = block.joinToString("\n").trim()
            if (!mentionsPackage(body, packageName)) return@mapNotNull null
            val parsedAt = parseTime(block.firstOrNull().orEmpty())
                ?: return@mapNotNull null
            if (parsedAt < fromMillis || parsedAt > toMillis) return@mapNotNull null
            val header = block
                .firstOrNull { it.contains("FATAL EXCEPTION") }
                ?.substringAfter("FATAL EXCEPTION", "")
                ?.trim()
                ?.trimStart(':', ' ')
                ?.ifBlank { "Fatal exception" }
                ?: "Fatal exception"
            ParsedCrash(
                atMillis = parsedAt,
                header = header,
                body = body.take(LogRecordCodec.MAX_DETAIL_LENGTH),
            )
        }
    }

    /**
     * A whole-token match, never a substring.
     *
     * `com.example.game` is a prefix of `com.example.game.other`, so a plain `contains` would attribute
     * the second app's crash to the first. The boundary on both sides is what makes that impossible,
     * and it is also why the `Process:` line needs no special case: it is an ordinary token in the body.
     */
    private fun mentionsPackage(body: String, packageName: String): Boolean {
        val token = Regex("""(^|[^A-Za-z0-9_.])${Regex.escape(packageName)}([^A-Za-z0-9_.]|$)""")
        return token.containsMatchIn(body)
    }

    /**
     * Logcat's threadtime format omits the year; the crash buffer only holds recent history, so the
     * current year is the honest reading. A crash that lands across midnight on New Year's Eve is
     * outside the window either way and is dropped rather than misdated.
     */
    private fun parseTime(line: String): Long? {
        val stamp = timePattern.find(line)?.groupValues?.getOrNull(1) ?: return null
        val calendar = Calendar.getInstance()
        val year = calendar.get(Calendar.YEAR)
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply {
                isLenient = false
            }.parse("$year-$stamp")?.time
        }.getOrNull()
    }
}
