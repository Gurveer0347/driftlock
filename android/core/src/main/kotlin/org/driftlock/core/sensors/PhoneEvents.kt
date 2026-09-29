package org.driftlock.core.sensors

/** Hardware and receipt clocks are seconds since boot. Arrays never escape ownership. */
class TimedVector(val t: Double, val receiptT: Double, values: DoubleArray, val accuracy: Int = 0) {
    private val data = values.copyOf()
    val values: DoubleArray get() = data.copyOf()
    fun valid() = validClock(t, receiptT) && data.size == 3 && data.all { it.isFinite() }
}

class PhoneImu(val t: Double, val receiptT: Double, accel: DoubleArray, gyro: DoubleArray, val segmentId: Long) {
    private val a = accel.copyOf()
    private val g = gyro.copyOf()
    val accel: DoubleArray get() = a.copyOf()
    val gyro: DoubleArray get() = g.copyOf()
    fun valid() = validClock(t, receiptT) && a.size == 3 && g.size == 3 && (a + g).all { it.isFinite() }
    fun channels() = (a + g).map { it.toFloat() }.toFloatArray()
}

data class PhoneGnss(
    val t: Double, val receiptT: Double, val latitudeDeg: Double, val longitudeDeg: Double,
    val altitudeM: Double?, val horizontalAccuracyM: Double?, val speedMps: Double?,
    val speedAccuracyMps: Double?, val courseDeg: Double?, val courseAccuracyDeg: Double?,
    val provider: String, val isMock: Boolean, val verticalAccuracyM: Double? = null,
) {
    fun valid() = validClock(t, receiptT) && latitudeDeg.isFinite() && latitudeDeg in -90.0..90.0 &&
        longitudeDeg.isFinite() && longitudeDeg in -180.0..180.0 &&
        (altitudeM == null || altitudeM.isFinite()) &&
        listOf(horizontalAccuracyM, speedMps, speedAccuracyMps, courseAccuracyDeg, verticalAccuracyM).all { it == null || it.isFinite() && it >= 0 } &&
        (courseDeg == null || courseDeg.isFinite() && courseDeg in 0.0..360.0)
}

typealias PhoneMag = TimedVector
data class SpeedMeasurement(val t: Double, val speedMps: Double, val sigmaMps: Double, val valid: Boolean)

interface SensorSink {
    fun onImu(sample: PhoneImu)
    fun onGnss(fix: PhoneGnss)
    fun onMag(sample: PhoneMag)
    fun onDiscontinuity(reason: String, receiptT: Double)
}

fun validClock(t: Double, receiptT: Double): Boolean = t.isFinite() && receiptT.isFinite() &&
    t >= 0.0 && receiptT >= t && receiptT < 10_000_000.0

class ImuWindow(channels: FloatArray, timestampsS: DoubleArray, val availableT: Double, val segmentId: Long) {
    private val x = channels.copyOf()
    private val times = timestampsS.copyOf()
    val channels: FloatArray get() = x.copyOf()
    val timestampsS: DoubleArray get() = times.copyOf()
}
