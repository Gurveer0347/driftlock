package org.driftlock.core.nav

/** No percentage here: the categories describe current evidence and limitations. */
enum class Horizon { HIGH, MODERATE, LOW, UNRELIABLE }
enum class AlignmentState { UNINITIALIZED, PARTIAL, ALIGNED, DEGRADED, RECALIBRATING }
enum class GnssState { UNKNOWN, HEALTHY, DEGRADING, DENIED, REACQUIRING, STABLE }
enum class MagneticStatus { UNAVAILABLE, CALIBRATING, ACCEPTED, REJECTED }
enum class CalibrationState { UNCALIBRATED, COARSE, GOOD }
data class GeographicPosition(val latitudeDeg: Double, val longitudeDeg: Double, val altitudeM: Double?)
data class NavigationEvent(val t: Double?, val kind: String, val reason: String,
                           val measurementT: Double? = null, val receiptT: Double? = null)
data class ShadowComparison(val pairedRuns:Int,val calibratedMeanDiscrepancyM:Double,
                            val controlMeanDiscrepancyM:Double,val linearMps:Double?,val quadraticMps2:Double)
data class NavigationSnapshot(
    val t: Double?, val origin: GeographicPosition?, val position: GeographicPosition?,
    val positionEnuM: List<Double>?, val velocityEnuMps: List<Double>, val speedMps: Double,
    val headingRad: Double?, val headingSigmaRad: Double?, val positionSigmaM: Double?,
    val covariance: List<List<Double>>, val alignment: AlignmentState, val gnss: GnssState,
    val horizon: Horizon, val timeSinceFixS: Double?, val predictedDriftM: Double?,
    val calibration: CalibrationState, val magStatus: MagneticStatus,
    val acceptedGnss: Int, val rejectedGnss: Int, val acceptedSpeeds: Int,
    val rejectedSpeeds: Int, val shadowCompleted: Int, val audit: List<NavigationEvent>,
    val shadowComparison: ShadowComparison? = null,
) {
    val courseDeg: Double? get() = headingRad?.let { ((90.0-Math.toDegrees(it))%360.0+360.0)%360.0 }
    val headingSigmaDeg: Double? get() = headingSigmaRad?.let(Math::toDegrees)
}
