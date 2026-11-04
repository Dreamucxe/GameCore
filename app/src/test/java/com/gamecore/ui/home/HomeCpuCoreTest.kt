package com.gamecore.ui.home

import com.gamecore.core.common.DataSource
import com.gamecore.core.common.Observed
import com.gamecore.core.model.CoreFrequency
import com.gamecore.core.model.CpuReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCpuCoreTest {

    private val missing = Observed.notPresent("not exposed")

    @Test
    fun `utilisation drives the cell bar and the clock is printed compactly`() {
        val cores = homeCpuCores(
            cpu(
                cores = 3,
                percentages = Observed.of(listOf(-4f, 37f, 140f), DataSource.PROC_FS),
                frequencies = Observed.of(
                    listOf(
                        frequency(0, currentKHz = 2_400_000, maxKHz = 2_800_000),
                        frequency(1, currentKHz = 1_800_000, maxKHz = 1_800_000),
                        frequency(2, currentKHz = 2_841_600, maxKHz = 3_200_000),
                    ),
                    DataSource.SYS_FS,
                ),
            ),
        )

        assertEquals(listOf("2.4G", "1.8G", "2.8G"), cores.map { it.speed })
        assertEquals(listOf(0f, 0.37f, 1f), cores.map { it.load })
        assertEquals(listOf(2_800_000L, 1_800_000L, 3_200_000L), cores.map { it.maxKHz })
        assertTrue(cores.all { it.isOnline })
    }

    @Test
    fun `a hidden utilisation falls back to each core's own clock range`() {
        val cores = homeCpuCores(
            cpu(
                cores = 3,
                percentages = missing,
                frequencies = Observed.of(
                    listOf(
                        frequency(0, currentKHz = 1_500_000),
                        frequency(1, currentKHz = 0, isOnline = false),
                    ),
                    DataSource.SYS_FS,
                ),
            ),
        )

        assertEquals(0.5f, cores[0].load)
        assertEquals("—", cores[1].speed)
        assertNull(cores[1].load)
        assertFalse(cores[1].isOnline)
        assertEquals("—", cores[2].speed)
        assertNull(cores[2].load)
        assertTrue(cores[2].isOnline)
    }

    private fun cpu(
        cores: Int,
        percentages: Observed<List<Float>>,
        frequencies: Observed<List<CoreFrequency>>,
    ) = CpuReading(
        coreCount = cores,
        overallPercent = missing,
        perCorePercent = percentages,
        frequenciesKHz = frequencies,
        loadAverage = missing,
        temperatureDeciCelsius = missing,
    )

    private fun frequency(
        index: Int,
        currentKHz: Long,
        isOnline: Boolean = true,
        maxKHz: Long = 2_000_000,
    ) = CoreFrequency(
        coreIndex = index,
        currentKHz = currentKHz,
        minKHz = 1_000_000,
        maxKHz = maxKHz,
        isOnline = isOnline,
    )
}
