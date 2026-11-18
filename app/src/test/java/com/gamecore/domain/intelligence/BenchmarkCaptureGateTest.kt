package com.gamecore.domain.intelligence

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkCaptureGateTest {

    @Test
    fun `only one capture can enter and the gate reopens after leaving`() {
        val gate = BenchmarkCaptureGate()

        assertTrue(gate.tryEnter())
        assertTrue(gate.isRunning)
        assertFalse(gate.tryEnter())

        gate.leave()
        assertFalse(gate.isRunning)
        assertTrue(gate.tryEnter())
        gate.leave()
    }
}
