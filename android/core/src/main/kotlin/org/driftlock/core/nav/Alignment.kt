package org.driftlock.core.nav

import kotlin.math.*

internal data class MountSolution(val rotation:Mat,val residualRad:Double)
/** Gravity determines tilt only. Forward sign additionally requires the driver's declaration. */
internal class AlignmentTracker(private val cfg:NavConfig) {
    private data class Row(val t:Double,val a:DoubleArray,val g:DoubleArray)
    private val recent=ArrayDeque<Row>()
    private val gravity=ArrayDeque<DoubleArray>()
    private val staticGyro=ArrayDeque<DoubleArray>()
    private val events=ArrayDeque<Pair<Double,DoubleArray>>()
    var up:DoubleArray?=null;private set
    var stationary=false;private set
    fun push(t:Double,a:DoubleArray,g:DoubleArray,speed:Double?,speedAge:Double,forwardAccel:Double) {
        recent.addLast(Row(t,a.copyOf(),g.copyOf()))
        while(recent.isNotEmpty() && t-recent.first().t>cfg.zuptWindowS)recent.removeFirst()
        stationary=recent.size>=5 && speed!=null && abs(speed)<cfg.zuptSpeedThreshold && speedAge in 0.0..1.1 &&
            std(recent.map {norm(it.a)})<cfg.zuptAccelStdThreshold &&
            std(recent.map {norm(it.g)})<cfg.zuptGyroStdThreshold && recent.map {norm(it.g)}.average()<cfg.zuptGyroMeanThreshold
        if(stationary) {
            gravity.addLast(a.copyOf());staticGyro.addLast(g.copyOf())
            if(gravity.size>200)gravity.removeFirst();if(staticGyro.size>200)staticGyro.removeFirst()
            if(gravity.size>=20)up=unit(mean(gravity))
        }
        val upNow=up?:return
        if(abs(forwardAccel)>=.6 && abs(dot(g,upNow))<.15 && speedAge in 0.0..1.5) {
            val horizontal=DoubleArray(3) {a[it]-dot(a,upNow)*upNow[it]}
            if(norm(horizontal)>.15) {
                events.addLast(t to DoubleArray(3) {horizontal[it]*sign(forwardAccel)})
                if(events.size>600)events.removeFirst()
            }
        }
    }
    fun clearDirectionalEvents() {events.clear()}
    fun solve():MountSolution? {
        val upNow=up?:return null
        if(events.size<40 || events.last().first-events.first().first<8.0)return null
        val m=mean(events.map {it.second});if(norm(m)<.15)return null
        val forward=unit(DoubleArray(3) {m[it]-dot(m,upNow)*upNow[it]})
        val left=unit(cross(upNow,forward))
        val angles=events.map {acos(dot(unit(it.second),forward).coerceIn(-1.0,1.0))}
        // Standard deviation of unsigned angles is zero for symmetric ±theta
        // errors. Directional RMS preserves that real spread before averaging.
        val directionalRms=sqrt(angles.sumOf {it*it}/angles.size)
        if(directionalRms>Math.toRadians(cfg.maxDirectionalRmsDeg))return null
        val residual=directionalRms/sqrt(angles.size.toDouble())
        if(residual>Math.toRadians(6.0))return null
        return MountSolution(arrayOf(forward,left,upNow),residual)
    }
    fun gyroBias():DoubleArray=if(staticGyro.size>=20)mean(staticGyro) else DoubleArray(3)
    fun gravityShift(a:DoubleArray):Double?=up?.let {acos(dot(unit(a),it).coerceIn(-1.0,1.0))}
}
