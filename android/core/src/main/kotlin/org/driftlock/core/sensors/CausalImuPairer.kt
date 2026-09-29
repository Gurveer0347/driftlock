package org.driftlock.core.sensors

data class PairingResult(val imu: PhoneImu?, val reason: String? = null)

/** Serial-owner component. Arrival order can differ across sensor types; clocks within each cannot. */
class CausalImuPairer(private val capacity: Int = 256, private val maxAgeS: Double = .05, private val gapS: Double = .15) {
    private val accel = ArrayDeque<TimedVector>()
    private var lastAccel: Double? = null
    private var lastGyro: Double? = null
    var segmentId = 0L; private set
    var overflowCount = 0L; private set
    val bufferedAccelerometerCount get() = accel.size
    init { require(capacity >= 2 && maxAgeS > 0 && gapS >= maxAgeS) }

    fun reset() { accel.clear(); lastAccel = null; lastGyro = null; segmentId++ }

    fun accelerometer(sample: TimedVector): String? {
        if (!sample.valid()) { reset(); return "invalid_accelerometer" }
        val previous = lastAccel
        if (previous != null && sample.t <= previous) { reset(); return "non_monotonic_accelerometer" }
        var reason: String? = null
        if (previous != null && sample.t - previous > gapS + 1e-8) { reset(); reason = "accelerometer_gap" }
        if (accel.size >= capacity) { reset(); overflowCount++; reason = "accelerometer_buffer_overflow" }
        lastAccel = sample.t
        accel.addLast(sample)
        return reason
    }

    fun gyroscope(sample: TimedVector): PairingResult {
        if (!sample.valid()) { reset(); return PairingResult(null, "invalid_gyro") }
        val previous = lastGyro
        if (previous != null && sample.t <= previous) { reset(); return PairingResult(null, "non_monotonic_gyro") }
        if (previous != null && sample.t - previous > gapS + 1e-8) {
            // Retain only this target's fresh acceleration; never bridge old window state.
            val fresh = accel.lastOrNull { it.t <= sample.t }
            reset(); fresh?.let { accel.add(it); lastAccel = it.t }
        }
        lastGyro = sample.t
        val candidate = accel.lastOrNull { it.t <= sample.t }
            ?: return PairingResult(null, "missing_past_accelerometer")
        if (sample.t - candidate.t > maxAgeS + 1e-8) {
            reset(); lastGyro = sample.t
            return PairingResult(null, "stale_accelerometer")
        }
        while (accel.size > 1 && accel[1].t <= sample.t) accel.removeFirst()
        return PairingResult(PhoneImu(sample.t, maxOf(sample.receiptT, candidate.receiptT), candidate.values, sample.values, segmentId))
    }
}
