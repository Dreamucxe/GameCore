package com.gamecore.core.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The JVM maths behind the two motion readings a plugin may draw through the approved stat feed.
 *
 * Pure arithmetic, like [MotionRecorderTest]: a [MotionFrame] in, a figure out, and no Android.
 * The two things worth pinning are the definitions (rotation rate is the gyro magnitude in
 * degrees per second; movement is |a| − g) and the honesty rule — a sensor the device does not
 * have is reported as unavailable, never as a fabricated zero.
 */
class MotionReadingReaderTest {

    private fun frame(gyro: Axis3? = null, accel: Axis3? = null): MotionFrame = MotionFrame(
        wallClockMillis = 0L,
        eventTimestampNanos = 0L,
        gyro = gyro,
        acceleration = accel,
        orientation = null,
        gyroAccuracy = 3,
        accelerationAccuracy = 3,
        sampleCount = 1L,
        deliveredHz = null,
    )

    // ------------------------------------------------------------------------------ definitions

    @Test
    fun `rotation rate is the gyroscope magnitude in degrees per second`() {
        // |(1,0,0)| = 1 rad/s -> 57.2958 deg/s.
        val r = MotionReadingReader.read(MotionReading.ROTATION_RATE, frame(gyro = Axis3(1f, 0f, 0f)))
        assertTrue(r.isAvailable)
        assertEquals(Math.toDegrees(1.0), r.value!!, 1e-4)
    }

    @Test
    fun `movement is how far acceleration strays from rest`() {
        // |a| = g + 2 -> 2.0 m/s^2 of movement.
        val up = MotionReadingReader.read(
            MotionReading.MOVEMENT,
            frame(accel = Axis3(0f, 0f, MotionSummary.GRAVITY + 2f)),
        )
        assertEquals(2.0, up.value!!, 1e-3)

        // Below rest reads as a positive deviation too: |8.80665 - 9.80665| = 1.0.
        val down = MotionReadingReader.read(
            MotionReading.MOVEMENT,
            frame(accel = Axis3(0f, 0f, MotionSummary.GRAVITY - 1f)),
        )
        assertEquals(1.0, down.value!!, 1e-3)
    }

    // ------------------------------------------------------------------------------ unavailability

    @Test
    fun `a missing sensor is unavailable, never zero`() {
        val noGyro = MotionReadingReader.read(MotionReading.ROTATION_RATE, frame(gyro = null))
        assertFalse(noGyro.isAvailable)
        assertNull(noGyro.value)
        assertNull(noGyro.display())

        val noAccel = MotionReadingReader.read(MotionReading.MOVEMENT, frame(accel = null))
        assertFalse(noAccel.isAvailable)
        assertNull(noAccel.display())
    }

    // ------------------------------------------------------------------------------ ids

    @Test
    fun `the reading ids are the exact strings the host feed publishes`() {
        assertEquals("rotation_rate", MotionReading.ROTATION_RATE.id)
        assertEquals("movement", MotionReading.MOVEMENT.id)
        assertEquals(setOf("rotation_rate", "movement"), MotionReading.IDS)
    }

    @Test
    fun `fromId resolves a trimmed, case-folded id and nothing else`() {
        assertEquals(MotionReading.ROTATION_RATE, MotionReading.fromId(" Rotation_Rate "))
        assertEquals(MotionReading.MOVEMENT, MotionReading.fromId("movement"))
        assertNull(MotionReading.fromId("gyro"))
        assertNull(MotionReading.fromId(""))
        assertNull(MotionReadingReader.read("gyro", frame(gyro = Axis3(1f, 1f, 1f))))
    }

    // ------------------------------------------------------------------------------ display

    @Test
    fun `display carries the unit and rounds half up`() {
        assertEquals("124°/s", MotionReadingResult(MotionReading.ROTATION_RATE, 123.5).display())
        assertEquals("57°/s", MotionReadingResult(MotionReading.ROTATION_RATE, 57.2958).display())
        assertEquals("2.5m/s²", MotionReadingResult(MotionReading.MOVEMENT, 2.45).display())
        assertEquals("0.0m/s²", MotionReadingResult(MotionReading.MOVEMENT, 0.0).display())
    }

    @Test
    fun `display uses a dot separator regardless of device locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY) // a locale whose decimal separator is a comma
            assertEquals("2.5m/s²", MotionReadingResult(MotionReading.MOVEMENT, 2.5).display())
        } finally {
            Locale.setDefault(previous)
        }
    }
}
