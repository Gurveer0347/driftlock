package org.driftlock.core.nav

import kotlin.math.*

/** Covariance convergence alone cannot demonstrate that a calibration was observed. */
internal class CalibrationMonitor(private val cfg:NavConfig) {
    private var goodSeconds=0.0
    private var turnExcitation=0.0
    private var accelExcitation=0.0
    fun accumulate(dt:Double,yawRate:Double,forwardAccel:Double,healthy:Boolean) {
        if(healthy) {goodSeconds+=dt;turnExcitation+=abs(yawRate)*dt;accelExcitation+=abs(forwardAccel)*dt}
        else goodSeconds=maxOf(0.0,goodSeconds-dt)
    }
    fun clear() {goodSeconds=0.0;turnExcitation=0.0;accelExcitation=0.0}
    fun grade(n:Esekf):CalibrationState {
        val mount=(15..17).maxOf {sqrt(maxOf(n.P[it][it],0.0))}
        val gyro=(12..14).maxOf {sqrt(maxOf(n.P[it][it],0.0))}
        val accel=(9..11).maxOf {sqrt(maxOf(n.P[it][it],0.0))}
        val scale=sqrt(maxOf(n.P[18][18],0.0))
        val observed=goodSeconds>cfg.calibMinGoodFixTimeS && turnExcitation>cfg.calibMinTurnExcitation && accelExcitation>cfg.calibMinAccelExcitation
        val converged=mount<cfg.calibGoodMountSigma && gyro<cfg.calibGoodGyroBiasSigma && accel<cfg.calibGoodAccelBiasSigma && scale<cfg.calibGoodScaleSigma
        return when {
            observed && converged->CalibrationState.GOOD
            mount<4*cfg.calibGoodMountSigma && goodSeconds>5.0->CalibrationState.COARSE
            else->CalibrationState.UNCALIBRATED
        }
    }
}
