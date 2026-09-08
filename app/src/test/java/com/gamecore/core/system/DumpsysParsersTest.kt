package com.gamecore.core.system

import com.gamecore.core.common.DataSource
import com.gamecore.core.common.Observed
import com.gamecore.core.common.valueOrNull
import com.gamecore.core.model.AppProcessState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `dumpsys activity processes`, which is both the enumeration and the verification for the launch-time
 * memory reclaim.
 *
 * The format is not API — the columns before the process record have been renamed, reordered and added
 * to between Android 8 and 15 — so what is pinned here is the two anchors the parser actually reads and
 * the three properties the reclaim depends on. That a package carries the most protective state among
 * its processes, because per-process states would offer the cached half of a music player as a
 * candidate. That an unrecognised label becomes [AppProcessState.UNKNOWN] rather than being dropped,
 * because the label set grows with each release and this parser will always be behind the newest one.
 * And that a dump yielding nothing is an absence rather than an empty map, since an empty map reads as
 * "nothing is running" — the one reading under which every app on the device looks closable.
 */
class DumpsysParsersTest {

    @Test
    fun `row shapes from several releases all read, and the reading names where it came from`() {
        val dump = """
            ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)
              All known processes:
              *APP* UID 1000 ProcessRecord{a1b2c3d 1234:system/1000}
                Proc # 0: PERS F/ /PER  LCM: 0 1234:system/1000 (fixed)
                Proc # 3: fore T/A/TOP  trm: 0 4021:com.example.game/u0a234 (top-activity)
                Proc # 7: vis  B/S/FGS  LCM: 4 5120:com.example.player:playback/u0a201 (fg-service)
                Proc #12: prev B/ /PREV LCM: 9 5311:com.example.player/u0a201 (previous)
                Proc #18: cch  B/ /CRE  LCM: 15 3891:com.example.notes/u0a190 s0 (cch-empty)
        """.trimIndent()
        val expected = mapOf(
            "com.example.game" to AppProcessState.TOP,
            "com.example.player" to AppProcessState.FOREGROUND_SERVICE,
            "com.example.notes" to AppProcessState.CACHED,
        )
        assertEquals(expected, parse(dump))
        val observed = DumpsysParsers.parseRunningProcesses(dump)
        assertEquals(DataSource.DUMPSYS_SHIZUKU, (observed as Observed.Value).source)
    }

    @Test
    fun `the adjustment labels this parser knows each read as the state they describe`() {
        val expected = mapOf(
            "top-activity" to AppProcessState.TOP,
            "bound-top" to AppProcessState.TOP,
            "top-sleeping" to AppProcessState.TOP,
            "fg-service" to AppProcessState.FOREGROUND_SERVICE,
            // Composed labels: a process holding a foreground service and an activity is one thing on
            // one release and another on the next, and both are a foreground service.
            "fg-service-act" to AppProcessState.FOREGROUND_SERVICE,
            "imp-fg" to AppProcessState.PERCEPTIBLE,
            "vis-activity" to AppProcessState.PERCEPTIBLE,
            "perceptible" to AppProcessState.PERCEPTIBLE,
            "home" to AppProcessState.HOME,
            "service" to AppProcessState.BACKGROUND_SERVICE,
            "service-b" to AppProcessState.BACKGROUND_SERVICE,
            "started-services" to AppProcessState.BACKGROUND_SERVICE,
            "imp-bg" to AppProcessState.BACKGROUND_SERVICE,
            "cch-empty" to AppProcessState.CACHED,
            // Cached wins over the service it once started, because the service is gone.
            "cch-started-services" to AppProcessState.CACHED,
            "previous" to AppProcessState.CACHED,
            "previous-expired" to AppProcessState.CACHED,
            "empty" to AppProcessState.CACHED,
            // Persistent platform processes. Whether an app is part of the system is a package-manager
            // question, so the filter asks it there and this says only that it did not recognise them.
            "fixed" to AppProcessState.UNKNOWN,
            "system" to AppProcessState.UNKNOWN,
            "a-label-from-a-later-release" to AppProcessState.UNKNOWN,
        )
        for ((label, state) in expected) {
            assertEquals(label, mapOf(APP to state), parse(row(APP, label)))
        }
    }

    @Test
    fun `a package keeps the most protective state among its processes, in either print order`() {
        // The music player from the parser's own KDoc: the foreground service is in one process and the
        // UI is cached in another, and per-process states would have offered the cached half up.
        val playing = row("com.example.player:playback", "fg-service", pid = 5120)
        val cached = row("com.example.player", "cch-empty", pid = 5311)
        val expected = mapOf("com.example.player" to AppProcessState.FOREGROUND_SERVICE)
        assertEquals(expected, parse("$playing\n$cached"))
        assertEquals(expected, parse("$cached\n$playing"))
    }

    @Test
    fun `a process nobody could read protects a package whose other process is merely cached`() {
        val opaque = row(APP, "a-label-from-a-later-release", pid = 2001)
        val cached = row(APP, "cch-empty", pid = 2002)
        val expected = mapOf(APP to AppProcessState.UNKNOWN)
        assertEquals(expected, parse("$opaque\n$cached"))
        assertEquals(expected, parse("$cached\n$opaque"))
    }

    @Test
    fun `between two states of the same kind the earlier declaration wins`() {
        // Both protect: on screen outranks holding a service, which is the order AppProcessState is
        // declared in and the order the user is told about.
        val onScreen = row(APP, "top-activity", pid = 3001)
        val serving = row(APP, "fg-service", pid = 3002)
        assertEquals(mapOf(APP to AppProcessState.TOP), parse("$serving\n$onScreen"))
        // Neither protects, and the answer still cannot depend on which row printed first.
        val background = row(APP, "service", pid = 3003)
        val cached = row(APP, "cch-empty", pid = 3004)
        assertEquals(mapOf(APP to AppProcessState.BACKGROUND_SERVICE), parse("$cached\n$background"))
    }

    @Test
    fun `a process name that is not a package name is dropped rather than guessed at`() {
        // Persistent platform processes print a bare name with no dot in it. Passing one on would put a
        // token in front of the filter that cannot match a profile and might match a package by prefix.
        val dump = listOf(
            row("system", "fixed", pid = 1234, uid = "1000"),
            row("zygote64", "fixed", pid = 900, uid = "1000"),
            row(APP, "cch-empty"),
        ).joinToString("\n")
        assertEquals(mapOf(APP to AppProcessState.CACHED), parse(dump))
    }

    @Test
    fun `a dump that lists no processes is an absence, not an empty map`() {
        // An empty map would read as "nothing is running", which is the one reading under which every
        // app on the device looks closable. Both the failure and the refusal are checked, because
        // valueOrNull is what the reclaim actually asks.
        val nothing = """
            ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)
              All known processes:
        """.trimIndent()
        val denied = "java.lang.SecurityException: Permission Denial: can't dump ActivityManager"
        for (dump in listOf("", nothing, denied)) {
            assertTrue(dump, DumpsysParsers.parseRunningProcesses(dump) is Observed.Failed)
            assertNull(dump, DumpsysParsers.parseRunningProcesses(dump).valueOrNull)
        }
    }

    // ---- the pid of one package's main process

    @Test
    fun `the main process of a package is found by its pid`() {
        // The same rows the state parser reads, through the same regex. A second regex over the same
        // output could disagree with the first about what is running, which is the class of bug that
        // produces "GameCore closed an app it says was not running".
        val dump = listOf(
            row("system", "fixed", pid = 1234, uid = "1000"),
            row(GAME, "top-activity", pid = 4021),
            row(APP, "cch-empty", pid = 3891),
        ).joinToString("\n")
        assertEquals(4021, DumpsysParsers.parseMainProcessPid(dump, GAME).valueOrNull)
        assertEquals(3891, DumpsysParsers.parseMainProcessPid(dump, APP).valueOrNull)
        assertEquals(
            DataSource.DUMPSYS_SHIZUKU,
            (DumpsysParsers.parseMainProcessPid(dump, GAME) as Observed.Value).source,
        )
    }

    @Test
    fun `a process with a suffix is not the main process and is left alone`() {
        // `com.game:audio` and `com.game:downloader` are matched by nothing here on purpose. The render
        // thread of an Android game lives in the main process, and pinning an audio service to the
        // performance cores would spend them on work that does not need them. It is also what keeps the
        // change single-valued, so one recorded mask puts it back.
        val dump = listOf(
            row("$GAME:audio", "fg-service", pid = 4088),
            row("$GAME:downloader", "service", pid = 4099),
        ).joinToString("\n")
        assertTrue(DumpsysParsers.parseMainProcessPid(dump, GAME) is Observed.Failed)

        // And the main row among them is the only one that answers.
        val withMain = "${row(GAME, "top-activity", pid = 4021)}\n$dump"
        assertEquals(4021, DumpsysParsers.parseMainProcessPid(withMain, GAME).valueOrNull)
    }

    @Test
    fun `a game that has not finished starting has no pid rather than a guessed one`() {
        val dump = row(APP, "cch-empty", pid = 3891)
        val observed = DumpsysParsers.parseMainProcessPid(dump, GAME)
        assertTrue(observed is Observed.Failed)
        assertNull(observed.valueOrNull)
        // Not zero, and not the first pid in the dump. The caller retries or says the game is not
        // running; a pid this parser invented would be another process entirely.
        assertTrue((observed as Observed.Failed).detail.contains(GAME))
    }

    @Test
    fun `the same game running under two users is refused rather than picked between`() {
        // A work profile or a second user prints the same process name twice with different uids, and
        // this row shape does not distinguish them. Picking either is a coin flip about whose process
        // GameCore reaches into, and the wrong side of it is another user's game.
        val dump = listOf(
            row(GAME, "top-activity", pid = 4021, uid = "u0a234"),
            row(GAME, "top-activity", pid = 8120, uid = "u10a234"),
        ).joinToString("\n")
        val observed = DumpsysParsers.parseMainProcessPid(dump, GAME)
        assertTrue(observed is Observed.Failed)
        assertTrue((observed as Observed.Failed).detail.contains("more than one user"))
    }

    @Test
    fun `two rows for one process at one pid are one answer, not an ambiguity`() {
        // A dump can list the same process twice — under `All known processes:` and again in a
        // per-user section. Distinct pids are the ambiguity, not distinct lines.
        val line = row(GAME, "top-activity", pid = 4021)
        assertEquals(4021, DumpsysParsers.parseMainProcessPid("$line\n$line", GAME).valueOrNull)
    }

    @Test
    fun `something that is not a package name is refused before the dump is read`() {
        val dump = row(GAME, "top-activity", pid = 4021)
        for (bad in listOf("", " ", "not a package", "../../etc/passwd", "com.game;id")) {
            val observed = DumpsysParsers.parseMainProcessPid(dump, bad)
            assertTrue(bad, observed is Observed.Failed)
            assertNull(bad, observed.valueOrNull)
        }
    }

    // ---- fixtures

    /**
     * One row in the shape `dumpsys activity processes` prints it.
     *
     * The columns before the process record are held constant on purpose, and deliberately disagree with
     * the bracket: the parser is anchored on `pid:process/uid` and the adjustment label, and a row whose
     * columns say something else is what proves it reads neither.
     */
    private fun row(processName: String, label: String, pid: Int = 4021, uid: String = "u0a234") =
        "    Proc # 6: cch  B/ /CRE  LCM: 11 $pid:$processName/$uid ($label)"

    private fun parse(dump: String): Map<String, AppProcessState> {
        val observed = DumpsysParsers.parseRunningProcesses(dump)
        assertNotNull("Expected a reading, got $observed", observed.valueOrNull)
        return observed.valueOrNull.orEmpty()
    }
}

private const val APP = "com.example.notes"

/** A second package, for the pid lookup: the one thing here that asks about a *named* app. */
private const val GAME = "com.example.game"
