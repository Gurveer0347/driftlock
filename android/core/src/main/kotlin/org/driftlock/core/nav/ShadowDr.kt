package org.driftlock.core.nav

import kotlin.math.*

internal enum class StationarySpeedSource { NONE, GNSS, MODEL }

/** Discrepancy against the GNSS-aided estimator, never independent ground truth. */
internal class ShadowDr(private val cfg:NavConfig) {
    private data class Run(val nav:Esekf,val launched:Double,val control:Boolean,var nhcT:Double,
                           val history:MutableList<Pair<Double,Double>> = mutableListOf(),
                           var continuousReference:Boolean=true, val magneticAnchor:Double?=null,val magneticNorm:Double?=null)
    private val runs=mutableListOf<Run>()
    private val finishedRuns=ArrayDeque<Run>()
    private var lastLaunch=Double.NEGATIVE_INFINITY
    private var linear:Double?=null
    private var quadratic=0.0
    var completed=0;private set
    fun clear() {runs.clear();finishedRuns.clear();linear=null;quadratic=0.0;lastLaunch=Double.NEGATIVE_INFINITY}
    fun predict(a:DoubleArray,g:DoubleArray,dt:Double) {runs.forEach {it.nav.predict(a,g,dt)}}
    fun speed(t:Double,value:Double,sigma:Double) {runs.filter {abs(it.nav.state.t-t)<cfg.timeToleranceS}.forEach {it.nav.updateForwardSpeed(value,sigma)}}
    fun magnetic(fieldRaw:DoubleArray,referenceAngle:Double,sigmaRad:Double,gyroRaw:DoubleArray=DoubleArray(3),age:Double=0.0) {
        for(run in runs) {
            val anchor=run.magneticAnchor?:continue;val referenceNorm=run.magneticNorm?:continue
            if(abs(wrap(anchor-referenceAngle))>cfg.timeToleranceS || age !in 0.0..0.1 || abs(norm(fieldRaw)-referenceNorm)>referenceNorm*.15)continue
            val corrected=matVec(quatToMatrix(quatFromRotVec(DoubleArray(3) {-(gyroRaw[it]-run.nav.state.bg[it])*age})),fieldRaw)
            val world=matVec(run.nav.state.rNb,corrected)
            if(abs(wrap(atan2(world[1],world[0])-anchor))>Math.toRadians(20.0))continue
            run.nav.updateMagneticDirection(corrected,anchor,sigmaRad)
        }
    }
    fun constraints(g:DoubleArray,stationary:Boolean,source:StationarySpeedSource=StationarySpeedSource.NONE) {
        for(run in runs) {
            val n=run.nav
            if(stationary && source==StationarySpeedSource.MODEL) {n.updateZupt();n.updateZaru(g)}
            else if(n.state.t-run.nhcT+cfg.timeToleranceS>=1/cfg.nhcRateHz) {
                val rate=matVec(n.state.rVb,DoubleArray(3) {g[it]-n.state.bg[it]})[2]
                if(hypot(n.state.v[0],n.state.v[1])>=cfg.nhcMinSpeed && abs(rate)<=cfg.nhcMaxYawRate) {
                    n.updateNhc(rate);run.nhcT=n.state.t
                }
            }
        }
    }
    fun tick(t:Double,live:Esekf,healthy:Boolean,lastNhc:Double,magneticAnchor:Double?=null,magneticNorm:Double?=null) {
        for(run in runs) {
            if(!healthy)run.continuousReference=false
            val age=t-run.launched
            if(healthy && (run.history.isEmpty() || age-run.history.last().first>=.1-cfg.timeToleranceS)) {
                val discrepancy=hypot(run.nav.state.p[0]-live.state.p[0],run.nav.state.p[1]-live.state.p[1])
                run.history.add(age to discrepancy)
            }
        }
        // Expire on time even during real denial; never compare a later fix to an old horizon.
        val finished=runs.filter {t-it.launched>=cfg.shadowHorizonS-cfg.timeToleranceS}
        for(run in finished) {
            if(healthy && run.continuousReference) {
                finishedRuns.addLast(run);if(finishedRuns.size>12)finishedRuns.removeFirst()
                if(!run.control)completed++
            }
            runs.remove(run)
        }
        if(finished.isNotEmpty())refit()
        if(healthy && t-lastLaunch>=cfg.shadowLaunchIntervalS) {
            lastLaunch=t;runs.add(Run(live.copy(),t,false,lastNhc,magneticAnchor=magneticAnchor,magneticNorm=magneticNorm))
            val control=live.copy();control.state.ba=DoubleArray(3);control.state.bg=DoubleArray(3);control.state.ks=1.0
            runs.add(Run(control,t,true,lastNhc,magneticAnchor=magneticAnchor,magneticNorm=magneticNorm))
        }
    }
    private fun refit() {
        val samples=finishedRuns.filter {!it.control}.flatMap {it.history}.filter {it.first>1.0}
        if(samples.size<10)return
        // Same two-column least-squares model as repaired Python: d = a*t + b*t².
        val s2=samples.sumOf {it.first.pow(2)};val s3=samples.sumOf {it.first.pow(3)};val s4=samples.sumOf {it.first.pow(4)}
        val y1=samples.sumOf {it.first*it.second};val y2=samples.sumOf {it.first*it.first*it.second}
        val determinant=s2*s4-s3*s3
        if(!determinant.isFinite() || determinant<=1e-12)return
        linear=maxOf(0.0,(y1*s4-y2*s3)/determinant)
        quadratic=maxOf(0.0,(s2*y2-s3*y1)/determinant)
    }
    fun predicted(duration:Double):Double?=linear?.let {it*maxOf(0.0,duration)+quadratic*maxOf(0.0,duration).pow(2)}
    fun comparison():ShadowComparison? {
        val calibrated=finishedRuns.filter {!it.control}.mapNotNull {it.history.lastOrNull()?.second}
        val controls=finishedRuns.filter {it.control}.mapNotNull {it.history.lastOrNull()?.second}
        if(calibrated.isEmpty() || controls.isEmpty())return null
        return ShadowComparison(calibrated.size,calibrated.average(),controls.average(),linear,quadratic)
    }
}
