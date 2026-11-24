package com.gamecore.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * The two pure pieces of the log system: the on-disk shape and the crash-buffer parser.
 *
 * The store itself is Android file IO and is exercised on device; what must be provable without one is
 * that a record survives a round trip and that a parser cannot attribute someone else's crash to the
 * game GameCore was watching.
 */
class LogDiagnosticsTest {

    private fun record(
        id: String = "r1",
        at: Long = 1_700_000_000_000L,
        title: String = "IllegalStateException: boom",
        detail: String = "java.lang.IllegalStateException: boom\n\tat com.gamecore.Foo.bar(Foo.kt:1)",
    ) = LogRecord(
        id = id,
        atMillis = at,
        kind = LogKind.GAME_CRASH,
        level = LogLevel.ERROR,
        title = title,
        detail = detail,
        packageName = "com.example.game",
        appVersion = "5.12.0 (73)",
        device = "Test Device (API 29)",
    )

    @Test
    fun `a record survives encode and decode`() {
        val original = record()
        val decoded = LogRecordCodec.decode(LogRecordCodec.encode(original))

        assertNotNull(decoded)
        assertEquals(original.id, decoded!!.id)
        assertEquals(original.atMillis, decoded.atMillis)
        assertEquals(original.kind, decoded.kind)
        assertEquals(original.level, decoded.level)
        assertEquals(original.title, decoded.title)
        assertEquals(original.detail, decoded.detail)
        assertEquals(original.packageName, decoded.packageName)
        assertEquals(original.appVersion, decoded.appVersion)
        assertEquals(original.device, decoded.device)
    }

    @Test
    fun `a malformed or incomplete line is skipped rather than crashing the reader`() {
        assertNull(LogRecordCodec.decode(""))
        assertNull(LogRecordCodec.decode("not json"))
        assertNull(LogRecordCodec.decode("""{"id":"r1"}"""))
        assertNull(LogRecordCodec.decode("""{"id":"r1","at":0,"title":"x"}"""))
        assertNull(LogRecordCodec.decode("""{"id":"r1","at":1}"""))
    }

    @Test
    fun `unknown keys do not make an older reader reject a newer record`() {
        val line = """{"id":"r1","at":1234,"kind":"app_crash","level":"error",""" +
            """"title":"Crash","detail":"stack","future":"ignored"}"""

        val decoded = LogRecordCodec.decode(line)

        assertEquals("Crash", decoded?.title)
        assertEquals(LogKind.APP_CRASH, decoded?.kind)
    }

    @Test
    fun `an unknown kind falls back to system and an unknown level to info`() {
        val line = """{"id":"r1","at":1234,"kind":"wat","level":"loud","title":"T","detail":""}"""

        val decoded = LogRecordCodec.decode(line)

        assertEquals(LogKind.SYSTEM, decoded?.kind)
        assertEquals(LogLevel.INFO, decoded?.level)
    }

    @Test
    fun `an oversized stack is capped when encoded`() {
        val huge = "x".repeat(LogRecordCodec.MAX_DETAIL_LENGTH * 2)

        val encoded = LogRecordCodec.encode(record(detail = huge))
        val decoded = LogRecordCodec.decode(encoded)

        assertEquals(LogRecordCodec.MAX_DETAIL_LENGTH, decoded?.detail?.length)
    }

    @Test
    fun `the share text carries the build, device and the original stack`() {
        val text = LogRecordCodec.toShareText(listOf(record()))

        assertTrue(text.contains("GameCore diagnostic logs"))
        assertTrue(text.contains("Build: 5.12.0 (73)"))
        assertTrue(text.contains("Device: Test Device (API 29)"))
        assertTrue(text.contains("java.lang.IllegalStateException: boom"))
        assertTrue(text.contains("com.example.game"))
    }

    @Test
    fun `an empty share is still a valid report header`() {
        val text = LogRecordCodec.toShareText(emptyList())

        assertTrue(text.contains("Records: 0"))
        assertFalse(text.contains("Build:"))
    }

    // ------------------------------------------------------------------ parser

    private val game = "com.example.game"
    private val other = "com.example.game.other"

    private fun year() = Calendar.getInstance().get(Calendar.YEAR)

    private fun stamp(millis: Long): String =
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(millis)

    private fun crashBlock(
        atMillis: Long,
        packageName: String = game,
        header: String = "main",
        message: String = "java.lang.RuntimeException: boom",
        prefix: String = "E AndroidRuntime",
    ): String = buildString {
        appendLine("${stamp(atMillis)}  1234  1234 $prefix: FATAL EXCEPTION: $header")
        appendLine("${stamp(atMillis)}  1234  1234 $prefix: Process: $packageName, PID: 1234")
        appendLine("${stamp(atMillis)}  1234  1234 $prefix: $message")
        appendLine("${stamp(atMillis)}  1234  1234 $prefix: \tat $packageName.Main.run(Main.kt:42)")
    }

    private fun parse(
        stdout: String,
        from: Long = 0L,
        to: Long = Long.MAX_VALUE,
    ) = CrashLogParser.parse(
        stdout = stdout,
        packageName = game,
        fromMillis = from,
        toMillis = to,
    )

    @Test
    fun `a fatal block naming the game is returned once`() {
        val at = System.currentTimeMillis()

        val crashes = parse(crashBlock(at))

        assertEquals(1, crashes.size)
        assertEquals("main", crashes.single().header)
        assertTrue(crashes.single().body.contains("RuntimeException: boom"))
    }

    @Test
    fun `another package is not attributed to the game`() {
        val at = System.currentTimeMillis()

        assertTrue(parse(crashBlock(at, packageName = other)).isEmpty())
    }

    @Test
    fun `a package that merely starts with the game id is not a match`() {
        val at = System.currentTimeMillis()
        val block = buildString {
            appendLine("${stamp(at)}  1234  1234 E AndroidRuntime: FATAL EXCEPTION: main")
            appendLine("${stamp(at)}  1234  1234 E AndroidRuntime: Process: $other, PID: 1234")
            appendLine("${stamp(at)}  1234  1234 E AndroidRuntime: at $other.Main.run(Main.kt:42)")
        }

        assertTrue(parse(block).isEmpty())
    }

    @Test
    fun `a crash outside the session window is dropped`() {
        val at = System.currentTimeMillis()

        assertTrue(parse(crashBlock(at), from = at + 60_000, to = at + 120_000).isEmpty())
        assertTrue(parse(crashBlock(at), from = at - 120_000, to = at - 60_000).isEmpty())
    }

    @Test
    fun `a native-style block that names the package as a token is accepted`() {
        val at = System.currentTimeMillis()
        val block = buildString {
            appendLine("${stamp(at)}  1234  1234 F DEBUG: FATAL EXCEPTION: main")
            appendLine("${stamp(at)}  1234  1234 F DEBUG: *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***")
            appendLine("${stamp(at)}  1234  1234 F DEBUG: Build fingerprint: 'test/fingerprint'")
            appendLine("${stamp(at)}  1234  1234 F DEBUG: Cmdline: $game")
            appendLine("${stamp(at)}  1234  1234 F DEBUG: signal 11 (SIGSEGV), code 1 (SEGV_MAPERR)")
        }

        val crashes = parse(block)

        assertEquals(1, crashes.size)
        assertTrue(crashes.single().body.contains("SIGSEGV"))
    }

    @Test
    fun `two fatal blocks in the buffer stay two records`() {
        val at = System.currentTimeMillis()
        val stdout = crashBlock(at) +
            crashBlock(at + 1_000, message = "java.lang.IllegalStateException: second")

        val crashes = parse(stdout)

        assertEquals(2, crashes.size)
        assertTrue(crashes.any { it.body.contains("second") })
    }

    @Test
    fun `a line without a logcat timestamp cannot become a record`() {
        val block = """
            E AndroidRuntime: FATAL EXCEPTION: main
            E AndroidRuntime: Process: $game, PID: 1234
        """.trimIndent()

        assertTrue(parse(block).isEmpty())
    }

    @Test
    fun `the current year is applied to the yearless logcat stamp`() {
        val at = System.currentTimeMillis()
        val expected = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
            .apply { isLenient = false }
            .parse("${year()}-${stamp(at)}")
            ?.time

        val crashes = parse(crashBlock(at))

        assertEquals(expected, crashes.singleOrNull()?.atMillis)
    }

    @Test
    fun `a blank buffer or blank package writes nothing`() {
        assertTrue(parse("").isEmpty())
        assertTrue(
            CrashLogParser.parse(
                stdout = crashBlock(System.currentTimeMillis()),
                packageName = " ",
                fromMillis = 0,
                toMillis = Long.MAX_VALUE,
            ).isEmpty(),
        )
    }
}
