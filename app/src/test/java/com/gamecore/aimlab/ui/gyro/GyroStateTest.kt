package com.gamecore.aimlab.ui.gyro

import com.gamecore.aimlab.engine.TrainingMode
import com.gamecore.aimlab.runtime.AimInputSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What each sensor source makes the gyro screen offer.
 *
 * The split that matters: a gyro device files under [TrainingMode.GYRO], a tilt device under
 * [TrainingMode.TILT], and neither can start a new run while one is already running.
 */
class GyroStateTest {

    @Test
    fun `a fresh state has no motion input and cannot start`() {
        val state = GyroState()
        assertEquals(AimInputSource.NONE, state.inputSource)
        assertFalse(state.gyroAvailable)
        assertFalse(state.tiltFallback)
        assertFalse(state.hasMotionInput)
        assertFalse(state.canStart)
    }

    @Test
    fun `a gyro device offers a gyro run`() {
        val state = GyroState(inputSource = AimInputSource.GYRO)
        assertTrue(state.gyroAvailable)
        assertFalse(state.tiltFallback)
        assertTrue(state.hasMotionInput)
        assertTrue(state.canStart)
        assertEquals(TrainingMode.GYRO, state.trainingMode)
    }

    @Test
    fun `a device without a gyroscope offers a tilt run`() {
        val state = GyroState(inputSource = AimInputSource.TILT)
        assertFalse(state.gyroAvailable)
        assertTrue(state.tiltFallback)
        assertTrue(state.hasMotionInput)
        assertTrue(state.canStart)
        assertEquals(TrainingMode.TILT, state.trainingMode)
    }

    @Test
    fun `a running gyro session cannot be started again`() {
        val state = GyroState(phase = GyroPhase.Running, inputSource = AimInputSource.GYRO)
        assertTrue(state.hasMotionInput)
        assertFalse(state.canStart)
    }

    @Test
    fun `a running tilt session cannot be started again`() {
        val state = GyroState(phase = GyroPhase.Running, inputSource = AimInputSource.TILT)
        assertTrue(state.hasMotionInput)
        assertFalse(state.canStart)
    }

    @Test
    fun `a finished session can be started again`() {
        val state = GyroState(phase = GyroPhase.Result, inputSource = AimInputSource.TILT)
        assertTrue(state.canStart)
    }
}
