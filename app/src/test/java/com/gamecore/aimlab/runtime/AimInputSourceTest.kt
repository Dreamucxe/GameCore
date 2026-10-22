package com.gamecore.aimlab.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sensor preference order: a real gyroscope always wins, the accelerometer is only a fallback, and a device
 * with neither is told so rather than offered a source it does not have.
 */
class AimInputSourceTest {

    @Test
    fun `a gyroscope is chosen whenever one was reported`() {
        assertEquals(
            AimInputSource.GYRO,
            AimInputSource.choose(hasGyroscope = true, hasAccelerometer = true),
        )
        assertEquals(
            AimInputSource.GYRO,
            AimInputSource.choose(hasGyroscope = true, hasAccelerometer = false),
        )
    }

    @Test
    fun `the accelerometer is the fallback when there is no gyroscope`() {
        assertEquals(
            AimInputSource.TILT,
            AimInputSource.choose(hasGyroscope = false, hasAccelerometer = true),
        )
    }

    @Test
    fun `a device with neither sensor has no motion source`() {
        assertEquals(
            AimInputSource.NONE,
            AimInputSource.choose(hasGyroscope = false, hasAccelerometer = false),
        )
    }

    @Test
    fun `every source carries a non-blank label`() {
        for (source in AimInputSource.entries) {
            assertTrue("blank label for ${source.name}", source.label.isNotBlank())
        }
    }
}
