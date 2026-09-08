package com.gamecore.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one inference this feature makes, tested as the inference it is.
 *
 * Everything the CPU affinity feature does rests on [CpuClusterLayout.from]: it is the only place in the
 * app that decides which cores are the fast ones, and it decides it from `cpuinfo_max_freq` because the
 * kernel publishes no `is_big_core` file. So the cases worth the most tests here are the ones where it
 * must refuse — a partial `cpufreq` reading, a zeroed frequency, an impossible index — because the failure
 * mode of a wrong answer is not a bad label on a screen. It is a game pinned to a set of cores chosen by
 * SELinux policy, running at a fraction of its normal frame rate, with a chip on screen claiming the user
 * asked for it.
 *
 * The mask arithmetic is tested separately and unsigned throughout. A 32-core mask has the top bit set,
 * `Int.toString(16)` renders that with a minus sign, and `taskset` would reject the string — a bug that
 * would only ever appear on hardware nobody testing this owns.
 */
class CpuAffinityModelsTest {

    private fun core(index: Int, maxKHz: Long) = CoreFrequency(
        coreIndex = index,
        currentKHz = maxKHz / 2,
        minKHz = 300_000L,
        maxKHz = maxKHz,
        isOnline = true,
    )

    /** A 4 + 3 + 1 layout, which is every recent flagship: little, big, and one prime core. */
    private fun flagship(): CpuClusterLayout {
        val cores = listOf(
            core(0, 1_800_000L),
            core(1, 1_800_000L),
            core(2, 1_800_000L),
            core(3, 1_800_000L),
            core(4, 2_600_000L),
            core(5, 2_600_000L),
            core(6, 2_600_000L),
            core(7, 3_200_000L),
        )
        return requireNotNull(CpuClusterLayout.from(cores))
    }

    // ------------------------------------------------------------------- deriving the layout

    @Test
    fun `cores are grouped by their ceiling, fastest cluster first`() {
        val layout = flagship()
        assertEquals(3, layout.clusters.size)
        assertEquals(8, layout.coreCount)
        // Highest first is what makes `clusters.first()` the performance cluster, so the order is part
        // of the contract rather than an incidental property of the grouping.
        assertEquals(listOf(7), layout.clusters[0].coreIndices)
        assertEquals(listOf(4, 5, 6), layout.clusters[1].coreIndices)
        assertEquals(listOf(0, 1, 2, 3), layout.clusters[2].coreIndices)
        assertEquals(listOf(7), layout.performanceCores)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), layout.efficiencyCores)
        assertFalse(layout.isHomogeneous)
    }

    @Test
    fun `a big-little pair is two clusters and eight identical cores are one`() {
        val bigLittle = requireNotNull(
            CpuClusterLayout.from(
                (0..3).map { core(it, 1_600_000L) } + (4..7).map { core(it, 2_400_000L) },
            ),
        )
        assertEquals(2, bigLittle.clusters.size)
        assertEquals(listOf(4, 5, 6, 7), bigLittle.performanceCores)
        assertEquals(listOf(0, 1, 2, 3), bigLittle.efficiencyCores)
        assertFalse(bigLittle.isHomogeneous)

        val uniform = requireNotNull(CpuClusterLayout.from((0..7).map { core(it, 2_000_000L) }))
        assertEquals(1, uniform.clusters.size)
        assertTrue(uniform.isHomogeneous)
        // Not an error and not a rare device: it is every uniform CPU, and it is also what a
        // big.LITTLE phone looks like when its kernel reports one shared policy for all eight.
        assertEquals(emptyList<Int>(), uniform.efficiencyCores)
    }

    @Test
    fun `two clusters a hundred kilohertz apart stay two clusters`() {
        // Frequencies are compared for equality rather than bucketed. Cores in one cluster report the
        // identical ceiling because the same policy file is behind them, so a tolerance would exist
        // only to merge clusters the kernel is telling us are distinct.
        val layout = requireNotNull(
            CpuClusterLayout.from(listOf(core(0, 2_000_000L), core(1, 2_100_000L))),
        )
        assertEquals(2, layout.clusters.size)
        assertEquals(listOf(1), layout.performanceCores)
    }

    @Test
    fun `a single core is a layout, and a homogeneous one`() {
        val layout = requireNotNull(CpuClusterLayout.from(listOf(core(0, 1_400_000L))))
        assertEquals(1, layout.coreCount)
        assertTrue(layout.isHomogeneous)
        assertEquals(1, layout.allCoresMask)
    }

    @Test
    fun `an unreadable cpufreq refuses the whole layout rather than describing part of it`() {
        // `readCoreFrequencies` coalesces an unreadable frequency to 0L, so a zero here is an absence
        // wearing a number's clothes. One of them poisons the reading: keeping the seven cores that did
        // answer would produce a "performance cluster" chosen by whichever files SELinux happened to
        // leave readable, and it would look exactly like a working feature.
        val partial = (0..6).map { core(it, 2_000_000L) } + core(7, 0L)
        assertNull(CpuClusterLayout.from(partial))
        assertNull(CpuClusterLayout.from((0..7).map { core(it, 0L) }))
        assertNull(CpuClusterLayout.from(listOf(core(0, -1L))))
    }

    @Test
    fun `a reading that cannot be a core list is refused`() {
        assertNull("nothing read", CpuClusterLayout.from(emptyList()))
        assertNull(
            "duplicate index",
            CpuClusterLayout.from(listOf(core(0, 1_800_000L), core(0, 2_400_000L))),
        )
        assertNull("negative index", CpuClusterLayout.from(listOf(core(-1, 1_800_000L))))
        assertNull(
            "index past the mask",
            CpuClusterLayout.from(listOf(core(CpuClusterLayout.MAX_CORES, 1_800_000L))),
        )
        // More cores than a 32-bit mask can name. Refusing says the reading is wrong, which is the
        // truthful reading of it — no Android device is anywhere near this.
        assertNull(
            "too many cores",
            CpuClusterLayout.from((0..CpuClusterLayout.MAX_CORES).map { core(it, 1_800_000L) }),
        )
    }

    @Test
    fun `the last core a mask can hold is included, not off by one`() {
        val top = CpuClusterLayout.MAX_CORES - 1
        val layout = requireNotNull(
            CpuClusterLayout.from(listOf(core(0, 1_800_000L), core(top, 2_400_000L))),
        )
        assertEquals(listOf(top), layout.performanceCores)
        assertEquals(1 shl top, layout.clusters.first().mask)
        // The top bit is set, so this is the number a signed `Int` renders with a minus sign.
        assertTrue(layout.allCoresMask < 0)
        assertEquals("80000001", CpuAffinityMask.hex(layout.allCoresMask))
    }

    // ------------------------------------------------------------------------------- presets

    @Test
    fun `performance cores only keeps the top cluster and nothing else`() {
        val choice = CpuAffinityPreset.PERFORMANCE_ONLY.on(flagship())
        assertTrue(choice.isAvailable)
        assertNull(choice.unavailableBecause)
        assertEquals(listOf(7), choice.coreIndices)
        assertEquals(1 shl 7, choice.mask)
    }

    @Test
    fun `all cores minus one drops the lowest slow core and keeps every other`() {
        val choice = CpuAffinityPreset.AVOID_ONE_EFFICIENCY_CORE.on(flagship())
        assertTrue(choice.isAvailable)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), choice.coreIndices)
        // cpu0 rather than any other slow core: on every ARM layout this app will meet it is in the
        // little cluster and is where the kernel lands its timers and interrupt work, so it is both
        // the core the game gains least from and the one that gains most from not being shared.
        assertFalse(0 in choice.coreIndices)
        assertEquals(0b1111_1110, choice.mask)
    }

    @Test
    fun `a homogeneous layout offers neither preset and says why`() {
        val uniform = requireNotNull(CpuClusterLayout.from((0..7).map { core(it, 2_000_000L) }))
        val choices = uniform.choices()
        assertEquals(CpuAffinityPreset.entries.size, choices.size)
        choices.forEach { choice ->
            assertFalse(choice.preset.name, choice.isAvailable)
            assertEquals(choice.preset.name, emptyList<Int>(), choice.coreIndices)
            // An unavailable choice's mask must be 0 and 0 is not a mask anything may write: a
            // process allowed no cores cannot run.
            assertEquals(choice.preset.name, 0, choice.mask)
            assertTrue(
                choice.preset.name,
                requireNotNull(choice.unavailableBecause).contains("same maximum frequency"),
            )
        }
    }

    @Test
    fun `a layout with one slow core hides the preset that would duplicate the other`() {
        // 1 + 7. "All cores, minus one" would compute exactly the performance cluster's mask here, so
        // offering both would put two chips on screen that do one thing and disagree about its name.
        val layout = requireNotNull(
            CpuClusterLayout.from(listOf(core(0, 1_500_000L)) + (1..7).map { core(it, 2_800_000L) }),
        )
        val performance = CpuAffinityPreset.PERFORMANCE_ONLY.on(layout)
        val avoidOne = CpuAffinityPreset.AVOID_ONE_EFFICIENCY_CORE.on(layout)
        assertTrue(performance.isAvailable)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), performance.coreIndices)
        assertFalse(avoidOne.isAvailable)
        assertNotNull(avoidOne.unavailableBecause)
        // And the reason names the preset it would have duplicated, so the editor's note is a
        // comparison the user can act on rather than a bare "unavailable".
        assertTrue(
            requireNotNull(avoidOne.unavailableBecause)
                .contains(CpuAffinityPreset.PERFORMANCE_ONLY.label.lowercase()),
        )
    }

    @Test
    fun `every preset is resolved on a layout and the order is the enum's`() {
        val choices = flagship().choices()
        assertEquals(CpuAffinityPreset.entries.map { it }, choices.map { it.preset })
    }

    @Test
    fun `no preset claims the device gets faster and every one says what it costs`() {
        // §14 and the brief's honesty rule, as an assertion rather than a review comment. These
        // sentences are rendered verbatim in the editor, so a rewrite that slipped a promise into one
        // of them should fail here.
        val forbidden = listOf("boost", "faster", "more fps", "unlock", "overclock", "speed up")
        CpuAffinityPreset.entries.forEach { preset ->
            assertTrue(preset.name, preset.label.isNotBlank())
            assertTrue(preset.name, preset.explanation.length > 80)
            forbidden.forEach { claim ->
                assertFalse("$preset: $claim", preset.explanation.lowercase().contains(claim))
            }
        }
        // There is no "leave it to the OS" member, deliberately: null on the profile is that state's
        // only spelling. A member added here would give it a second one.
        assertEquals(2, CpuAffinityPreset.entries.size)
        assertFalse(CpuAffinityPreset.entries.any { it.name.contains("LEAVE") })
    }

    @Test
    fun `the honesty sentence says all three things it is required to say`() {
        val honesty = CpuAffinityPreset.HONESTY.lowercase()
        assertTrue(honesty.contains("may reduce stutter"))
        assertTrue(honesty.contains("does not make the device faster"))
        assertTrue(honesty.contains("more harm than good"))
    }

    // ---------------------------------------------------------------------------------- masks

    @Test
    fun `a mask round-trips as bare lowercase hex, including the top-bit case`() {
        listOf(1, 0b1111_0000, 0x55, Int.MAX_VALUE, -1, 1 shl 31).forEach { mask ->
            val hex = CpuAffinityMask.hex(mask)
            assertEquals(hex, hex.lowercase())
            assertFalse(hex, hex.startsWith("0x"))
            assertFalse(hex, hex.startsWith("-"))
            assertEquals(hex, mask, CpuAffinityMask.parse(hex))
        }
        assertEquals("f0", CpuAffinityMask.hex(0b1111_0000))
        assertEquals("ffffffff", CpuAffinityMask.hex(-1))
        assertEquals("80000000", CpuAffinityMask.hex(1 shl 31))
    }

    @Test
    fun `a mask with an 0x prefix or surrounding space is still a mask`() {
        assertEquals(0xf0, CpuAffinityMask.parse("0xf0"))
        assertEquals(0xf0, CpuAffinityMask.parse("0Xf0"))
        assertEquals(0xf0, CpuAffinityMask.parse(" f0\n"))
        assertEquals(0xf0, CpuAffinityMask.parse("F0"))
    }

    @Test
    fun `a zero mask is refused, because a process on no cores cannot run`() {
        assertNull(CpuAffinityMask.parse("0"))
        assertNull(CpuAffinityMask.parse("0x0"))
        assertNull(CpuAffinityMask.parse("00000000"))
    }

    @Test
    fun `text that is not a mask reads as no mask rather than as some mask`() {
        assertNull(CpuAffinityMask.parse(null))
        assertNull(CpuAffinityMask.parse(""))
        assertNull(CpuAffinityMask.parse("   "))
        assertNull(CpuAffinityMask.parse("ff,ff"))
        assertNull(CpuAffinityMask.parse("g0"))
        assertNull(CpuAffinityMask.parse("-1"))
        // Nine digits is not a longer mask, it is not a mask — and a restore row holding one must
        // read back as "nothing recorded" rather than as a truncation of it.
        assertNull(CpuAffinityMask.parse("1ffffffff"))
    }

    @Test
    fun `the cores a mask names are the bits that are set, ascending`() {
        assertEquals(listOf(0), CpuAffinityMask.cores(1))
        assertEquals(listOf(4, 5, 6, 7), CpuAffinityMask.cores(0b1111_0000))
        assertEquals(listOf(0, 7), CpuAffinityMask.cores(0b1000_0001))
        assertEquals((0 until CpuClusterLayout.MAX_CORES).toList(), CpuAffinityMask.cores(-1))
        assertEquals(listOf(CpuClusterLayout.MAX_CORES - 1), CpuAffinityMask.cores(1 shl 31))
        assertEquals(emptyList<Int>(), CpuAffinityMask.cores(0))
    }

    // ------------------------------------------------------------------------- thread masks

    @Test
    fun `a process whose threads all share a mask matches it`() {
        val state = CpuAffinityState(mapOf(4100 to 0xf0, 4101 to 0xf0, 4102 to 0xf0))
        assertEquals(3, state.threadCount)
        assertEquals(setOf(0xf0), state.masks)
        assertEquals(0xf0, state.uniformMask)
        assertTrue(state.matches(0xf0))
        assertFalse(state.matches(0xff))
    }

    @Test
    fun `a process whose threads disagree matches nothing`() {
        // The all-or-nothing rule, which is the whole reason this type is a map and not an Int. A game
        // engine that pins its own workers has already set masks GameCore did not, and a verify step
        // that compared one line to the target would report success for a process where only the main
        // thread moved.
        val split = CpuAffinityState(mapOf(4100 to 0xf0, 4101 to 0xff))
        assertNull(split.uniformMask)
        assertEquals(setOf(0xf0, 0xff), split.masks)
        assertFalse(split.matches(0xf0))
        assertFalse(split.matches(0xff))
    }

    @Test
    fun `the main thread is the one whose tid is the pid`() {
        val state = CpuAffinityState(mapOf(4100 to 0xf0, 4101 to 0xff))
        assertEquals(0xf0, state.mainThreadMask(4100))
        assertEquals(0xff, state.mainThreadMask(4101))
        // Absent rather than a guess: a `taskset -a` dump that never printed the main thread's own line
        // has no value to record, and a restore cannot put back a mask nothing wrote down.
        assertNull(state.mainThreadMask(9999))
    }

    @Test
    fun `an empty state matches nothing at all`() {
        val empty = CpuAffinityState(emptyMap())
        assertFalse(empty.matches(0xf0))
        assertFalse(empty.matches(0))
        assertNull(empty.uniformMask)
        assertEquals(0, empty.threadCount)
    }

    // ---------------------------------------------------------------------------- outcomes

    @Test
    fun `only the three cases that discharge the obligation are successes`() {
        val applied = CpuAffinityOutcome.Applied(
            preset = CpuAffinityPreset.PERFORMANCE_ONLY,
            coreIndices = listOf(4, 5, 6, 7),
            verifiedBy = "Shizuku shell",
        )
        assertTrue(applied.isSuccess)
        assertTrue(CpuAffinityOutcome.Restored(listOf(0, 1), "Shizuku shell").isSuccess)
        // Success, and the one that matters most: the game exited, a mask lives in a task struct, so
        // it went with the process. A failure here would leave a restore row pending that no future
        // attempt could ever clear.
        assertTrue(CpuAffinityOutcome.NothingToRestore("The game is not running.").isSuccess)

        assertFalse(CpuAffinityOutcome.NotHonoured(null, setOf(0xf0, 0xff)).isSuccess)
        assertFalse(CpuAffinityOutcome.AppliedUnverified(null, "the shell stopped answering").isSuccess)
        assertFalse(
            CpuAffinityOutcome.PresetUnsupported(
                CpuAffinityPreset.PERFORMANCE_ONLY,
                "One cluster.",
            ).isSuccess,
        )
        assertFalse(CpuAffinityOutcome.ProcessNotFound("com.example.game", "Not running.").isSuccess)
        assertFalse(CpuAffinityOutcome.RequiresAccess("Needs Shizuku.").isSuccess)
        assertFalse(CpuAffinityOutcome.Failed("The shell errored.").isSuccess)
    }

    @Test
    fun `every outcome has a sentence and none of them claims a frame rate`() {
        val outcomes = listOf(
            CpuAffinityOutcome.Applied(CpuAffinityPreset.PERFORMANCE_ONLY, listOf(7), "Shizuku shell"),
            CpuAffinityOutcome.Applied(
                CpuAffinityPreset.AVOID_ONE_EFFICIENCY_CORE,
                listOf(1, 2, 3, 4, 5, 6, 7),
                "Shizuku shell",
            ),
            CpuAffinityOutcome.Restored(listOf(0, 1, 2, 3, 4, 5, 6, 7), "Shizuku shell"),
            CpuAffinityOutcome.NothingToRestore("That game is not running, so nothing was written."),
            CpuAffinityOutcome.NotHonoured(CpuAffinityPreset.PERFORMANCE_ONLY, setOf(0xf0, 0xff)),
            CpuAffinityOutcome.NotHonoured(null, emptySet()),
            CpuAffinityOutcome.AppliedUnverified(CpuAffinityPreset.PERFORMANCE_ONLY, "no read back"),
            CpuAffinityOutcome.AppliedUnverified(null, "no read back"),
            CpuAffinityOutcome.PresetUnsupported(
                CpuAffinityPreset.AVOID_ONE_EFFICIENCY_CORE,
                "This device has one slower core.",
            ),
            CpuAffinityOutcome.ProcessNotFound(
                "com.example.game",
                "That game has no process yet, so nothing was changed.",
            ),
            CpuAffinityOutcome.RequiresAccess("This needs the elevated shell."),
            CpuAffinityOutcome.Failed("The shell returned an error instead of a mask."),
        )
        val forbidden = listOf("boost", "faster", "more fps", "optimised")
        outcomes.forEach { outcome ->
            assertTrue(outcome.toString(), outcome.message.length > 20)
            forbidden.forEach { claim ->
                assertFalse(outcome.toString(), outcome.message.lowercase().contains(claim))
            }
        }
    }

    @Test
    fun `a contiguous run of cores is a range and a scattered set is a list`() {
        assertTrue(
            CpuAffinityOutcome.Restored(listOf(4, 5, 6, 7), "shell").message.contains("cores 4–7"),
        )
        assertTrue(CpuAffinityOutcome.Restored(listOf(7), "shell").message.contains("core 7"))
        assertTrue(
            CpuAffinityOutcome.Restored(listOf(0, 7), "shell").message.contains("cores 0, 7"),
        )
    }

    @Test
    fun `a change this device accepted and did not make is not reported as applied`() {
        // The sentence names the two reasons it happens rather than blaming the user's device in
        // general: some kernels refuse an affinity change for another app's process, and a game that
        // pins its own threads overrides one that succeeded.
        val ignored = CpuAffinityOutcome.NotHonoured(CpuAffinityPreset.PERFORMANCE_ONLY, setOf(1, 2))
        assertFalse(ignored.isSuccess)
        assertTrue(ignored.message.contains("2 different core sets"))

        // And the no-cores-reported case gets its own sentence, because "spread across 0 core sets"
        // is not a thing to say to a user.
        val silent = CpuAffinityOutcome.NotHonoured(null, emptySet())
        assertTrue(silent.message.contains("reported no cores back"))
    }
}
