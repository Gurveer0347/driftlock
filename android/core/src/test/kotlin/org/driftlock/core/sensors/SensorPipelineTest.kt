package org.driftlock.core.sensors

import org.junit.Assert.*
import org.junit.Test

class SensorPipelineTest {
    private fun sample(t: Double, x: Double = 0.0) = TimedVector(t, t + .01, doubleArrayOf(x, 0.0, 9.80665))

    @Test fun newerAccelerometerCallbackCannotLeakIntoOlderGyro() {
        val p = CausalImuPairer()
        p.accelerometer(sample(1.0, 1.0))
        p.accelerometer(sample(1.04, 9.0))
        val result = p.gyroscope(sample(1.02))
        assertEquals(1.0, result.imu!!.accel[0], 0.0)
        assertEquals(1.02, result.imu!!.t, 0.0)
        assertEquals(1.04, p.gyroscope(sample(1.04)).imu!!.t, 0.0)
    }

    @Test fun staleMissingAndNonMonotonicSensorsCauseDiscontinuity() {
        val p = CausalImuPairer()
        assertNull(p.gyroscope(sample(1.0)).imu)
        p.accelerometer(sample(1.1))
        assertEquals("stale_accelerometer", p.gyroscope(sample(1.2)).reason)
        p.accelerometer(sample(1.3))
        assertNotNull(p.gyroscope(sample(1.3)).imu)
        assertEquals("non_monotonic_gyro", p.gyroscope(sample(1.3)).reason)
        assertNull(p.gyroscope(sample(1.32)).imu)
    }

    @Test fun copiedArraysCannotMutatePublishedSensorValues() {
        val a = doubleArrayOf(1.0, 2.0, 3.0)
        val s = TimedVector(1.0, 1.1, a)
        a[0] = 99.0
        s.values[1] = 99.0
        assertArrayEquals(doubleArrayOf(1.0, 2.0, 3.0), s.values, 0.0)
    }

    @Test fun boundedPairerDropsOverloadAndStartsNewSegment() {
        val p = CausalImuPairer(capacity = 3)
        repeat(5) { p.accelerometer(sample(1.0 + it * .02)) }
        assertTrue(p.overflowCount > 0)
        assertTrue(p.bufferedAccelerometerCount <= 3)
        assertTrue(p.segmentId > 0)
    }

    @Test fun causalGridDoesNotUseTheNextInputAndResetsAtRawGap() {
        val w = CausalWindowAssembler(windowSamples = 3)
        fun imu(t: Double, x: Double, segment: Long = 0) = PhoneImu(t, t + .01,
            doubleArrayOf(x, 0.0, 9.80665), doubleArrayOf(0.0, 0.0, 0.0), segment)
        assertTrue(w.push(imu(1.0, 1.0)).isEmpty())
        assertTrue(w.push(imu(1.11, 2.0)).isEmpty())
        val window = w.push(imu(1.21, 3.0)).single()
        assertArrayEquals(doubleArrayOf(1.0, 1.1, 1.2), window.timestampsS, 1e-9)
        assertEquals(2f, window.channels[12], 0f)
        assertEquals(1.22, window.availableT, 1e-9)
        // Even when a grid hold would be under 0.15 s, the 0.2 s source gap resets.
        assertTrue(w.push(imu(1.41, 4.0)).isEmpty())
        assertTrue(w.push(imu(1.51, 5.0, 1)).isEmpty())
    }

    @Test fun nonFiniteAndFutureMeasurementClocksAreRejected() {
        val p = CausalImuPairer()
        assertEquals("invalid_accelerometer", p.accelerometer(TimedVector(2.0, 1.0, doubleArrayOf(0.0, 0.0, 9.8))))
        assertEquals("invalid_accelerometer", p.accelerometer(sample(Double.NaN)))
        assertNull(p.gyroscope(sample(2.0)).imu)
    }

    @Test fun epsilonFutureSourceCannotSupplyGridValueOrEmitFutureTick() {
        fun imu(t: Double, x: Double) = PhoneImu(t, t + .01,
            doubleArrayOf(x, 0.0, 9.80665), doubleArrayOf(0.0, 0.0, 0.0), 0)
        val after = CausalWindowAssembler(windowSamples = 2)
        after.push(imu(1.0, 1.0))
        val held = after.push(imu(1.1 + 1e-12, 9.0)).single()
        assertEquals(1f, held.channels[6], 0f)
        val before = CausalWindowAssembler(windowSamples = 2)
        before.push(imu(1.0, 1.0))
        assertTrue(before.push(imu(1.1 - 1e-12, 9.0)).isEmpty())
        val due = before.push(imu(1.12, 20.0)).single()
        assertEquals(9f, due.channels[6], 0f)
        assertTrue(due.timestampsS.last() <= 1.12)
    }
}
