package com.gamecore.domain.monitoring

import com.gamecore.core.common.DataSource
import com.gamecore.core.common.Observed
import com.gamecore.core.model.CoreFrequency
import com.gamecore.core.model.CpuReading
import org.junit.Assert.assertEquals
import org.junit.Test

class CoreActivityHistoryTest {

    private val missing = Observed.notPresent("not exposed")

    @Test
    fun `utilisation wins and is clamped per core`() {
        val cpu = cpu(
            cores = 3,
            percentages = Observed.of(listOf(-4f, 37f, 140f), DataSource.PROC_FS),
            frequencies = Observed.of(emptyList(), DataSource.SYS_FS),
        )

        assertEquals(listOf(0f, 37f, 100f), coreActivityPercent(cpu))
    }

    @Test
    fun `frequency fallback normalises each core and keeps parked cores as gaps`() {
        val cpu = cpu(
            cores = 3,
            percentages = missing,
            frequencies = Observed.of(
                listOf(
                    CoreFrequency(0, currentKHz = 1_500, minKHz = 1_000, maxKHz = 2_000, isOnline = true),
                    CoreFrequency(1, currentKHz = 0, minKHz = 1_000, maxKHz = 2_000, isOnline = false),
                    CoreFrequency(2, currentKHz = 900, minKHz = 900, maxKHz = 900, isOnline = true),
                ),
                DataSource.SYS_FS,
            ),
        )

        assertEquals(listOf(50f, null, null), coreActivityPercent(cpu))
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
}
