// Adapted from preserved team source data/raw/team_archives_20260909/extracted/DRIFTLOCK/kotlin/NavConfigKt.kt
// Original SHA-256: b501872895f28be257560d0e3f88f66a27cc2d611ff813e49d9953979f869f78
// Native 2026-09-20 corrections are documented in docs/NAVIGATION_ENGINE.md.
package org.driftlock.core.nav

/** Filter defaults match repaired Python; native event-clock bounds are additional. */
data class NavConfig(
    val accelNoiseDensity: Double = 0.03,
    val gyroNoiseDensity: Double = 0.002,
    val accelBiasWalk: Double = 1.2e-3,
    val gyroBiasWalk: Double = 6.0e-5,

    val mountWalk: Double = 1.0e-5,
    val speedScaleWalk: Double = 1.0e-5,

    val initPositionSigma: Double = 10.0,
    val initVelocitySigma: Double = 2.0,
    val initRollPitchSigma: Double = 0.05,
    val initYawSigma: Double = 1.0,
    val initAccelBiasSigma: Double = 0.3,
    val initGyroBiasSigma: Double = 0.02,
    val initMountSigma: Double = 0.35,
    val initSpeedScaleSigma: Double = 0.05,
    val bootstrapAccelNoiseDensity: Double = 3.0,

    val nhcLateralSigma: Double = 0.15,
    val nhcVerticalSigma: Double = 0.30,
    val zuptSigma: Double = 0.02,
    val zaruSigma: Double = 0.002,
    val gnssPositionSigmaFloor: Double = 1.5,
    val gnssVelocitySigmaFloor: Double = 0.3,

    val gateChi2Dof1: Double = 6.63,
    val gateChi2Dof2: Double = 9.21,
    val gateChi2Dof3: Double = 11.34,

    val nhcMinSpeed: Double = 1.5,
    val nhcMaxYawRate: Double = 0.6,
    val nhcRateHz: Double = 10.0,
    val speedUpdateRateHz: Double = 10.0,

    val zuptWindowS: Double = 0.5,
    val zuptAccelStdThreshold: Double = 0.25,
    val zuptGyroStdThreshold: Double = 0.02,
    val zuptGyroMeanThreshold: Double = 0.05,
    val zuptSpeedThreshold: Double = 0.4,

    val fixTimeoutS: Double = 2.5,
    val fixAccuracyRejectM: Double = 35.0,
    val reacquireRequiredFixes: Int = 3,
    val reacquireInflation: Double = 4.0,

    val mountInnovationWindow: Int = 120,
    val mountInnovationThreshold: Double = 9.0,
    val mountGravityShiftDeg: Double = 8.0,
    val mountDetectionCooldownS: Double = 15.0,
    val mountHandlingGyroThreshold: Double = 2.5,
    val mountHandlingAccelThreshold: Double = 25.0,
    val mountResetSigma: Double = 0.5,
    val mountStoppedRotationDeg: Double = 20.0,
    val maxDirectionalRmsDeg: Double = 15.0,

    // Map matcher weighting: a probability turned into a distance.
    val mapConfidenceGate: Double = 0.80,
    val mapSigmaFloorM: Double = 3.0,
    val mapSigmaScaleM: Double = 40.0,

    val maxGnssAgeS: Double = .5,
    val gnssPropagationAccelBound: Double = 3.0,
    val speedHintMaxAgeS: Double = .15,
    val timeToleranceS: Double = 1e-8,
    val shadowLaunchIntervalS: Double = 20.0,
    val shadowHorizonS: Double = 30.0,
    val maxAuditEvents: Int = 128,
    val calibGoodMountSigma: Double = .03,
    val calibGoodGyroBiasSigma: Double = .004,
    val calibGoodAccelBiasSigma: Double = .06,
    val calibGoodScaleSigma: Double = .01,
    val calibMinTurnExcitation: Double = 1.2,
    val calibMinAccelExcitation: Double = 12.0,
    val calibMinGoodFixTimeS: Double = 25.0,
)
