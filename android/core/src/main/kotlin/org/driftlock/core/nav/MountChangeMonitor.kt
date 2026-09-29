package org.driftlock.core.nav

import kotlin.math.*

/** Port of the reference rolling NHC check, plus independent-stop rotation evidence. */
internal class MountChangeMonitor(private val cfg:NavConfig) {
    private val innovations=ArrayDeque<Double>()
    private var stoppedRotation=0.0
    private var cooldownUntil=Double.NEGATIVE_INFINITY
    fun resetEvidence() {innovations.clear();stoppedRotation=0.0}
    private fun fire(t:Double,reason:String):String {resetEvidence();cooldownUntil=t+cfg.mountDetectionCooldownS;return reason}
    fun observe(t:Double,dt:Double,unbiasedGyro:DoubleArray,independentStop:Boolean):String? {
        if(t<cooldownUntil)return null
        if(!independentStop)stoppedRotation=0.0
        else if(dt>0 && dt<=.5+cfg.timeToleranceS && norm(unbiasedGyro)>cfg.zuptGyroMeanThreshold)stoppedRotation+=norm(unbiasedGyro)*dt
        return if(stoppedRotation>Math.toRadians(cfg.mountStoppedRotationDeg))fire(t,"rotation_during_trusted_stop") else null
    }
    fun pushInnovation(t:Double,nis:Double?):String? {
        if(t<cooldownUntil || nis==null || !nis.isFinite())return null
        innovations.addLast(nis);if(innovations.size>cfg.mountInnovationWindow)innovations.removeFirst()
        return if(innovations.size==cfg.mountInnovationWindow && innovations.average()>cfg.mountInnovationThreshold)fire(t,"constraint_innovation") else null
    }
}
