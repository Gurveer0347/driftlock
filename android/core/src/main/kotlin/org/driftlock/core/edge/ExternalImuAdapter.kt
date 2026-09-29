package org.driftlock.core.edge

import org.driftlock.core.nav.NavigationEngine
import org.driftlock.core.sensors.PhoneImu
import org.driftlock.core.sensors.validClock
import kotlin.math.sqrt

/** Explicit declarations, not an assertion that a vendor device has been verified. */
data class ExternalImuDeclaration(
    val sourceIdentity:String,
    val accelerationUnits:String?,
    val includesGravity:Boolean?,
    val angularRateUnits:String?,
    val frameConvention:String?,
    val frameIdentity:String,
    val clockConvention:String?,
    val clockIdentity:String,
    val evidence:String,
) {
    internal fun requireSupported() {
        require(sourceIdentity.isNotBlank() && frameIdentity.isNotBlank() && clockIdentity.isNotBlank() && evidence.isNotBlank()) {"Source, body frame, shared clock and evidence must be declared"}
        require(accelerationUnits=="m/s^2" && includesGravity==true) {"Require SI acceleration including gravity; no guessed conversion"}
        require(angularRateUnits=="rad/s") {"Require radians per second"}
        require(frameConvention=="fixed_right_handed_sensor_xyz") {"Require declared fixed right-handed sensor body axes"}
        require(clockConvention=="seconds_since_boot_shared_monotonic") {"Require source and availability in the same declared boot clock"}
    }
}

class ExternalImuSample(val sourceIdentity:String,val frameIdentity:String,val clockIdentity:String,
                        val sourceT:Double,accel:DoubleArray,gyro:DoubleArray) {
    private val acceleration=accel.copyOf()
    private val angularRate=gyro.copyOf()
    val accel:DoubleArray get()=acceleration.copyOf()
    val gyro:DoubleArray get()=angularRate.copyOf()
}

data class ExternalImuStatus(val deliveredSamples:Int,val rejectedSamples:Int,val sessionFault:String?,
                             val lastSourceT:Double?,val lastAvailableT:Double?,val lastRejection:String?)

/**
 * A transport-neutral boundary into the SAME navigator. No serial/BLE/USB driver,
 * axis rotation, unit conversion, network, model or Android dependency.
 * A single caller supplies the actual transport-observed availability time.
 */
class ExternalImuAdapter(private val engine:NavigationEngine,val declaration:ExternalImuDeclaration) {
    init {declaration.requireSupported()}
    private var delivered=0
    private var rejected=0
    private var fault:String?=null
    private var lastT:Double?=null
    private var lastAvailable:Double?=null
    private var lastReason:String?=null
    fun accept(sample:ExternalImuSample,availableT:Double):Boolean {
        if(fault!=null)return reject("session_requires_restart",availableT)
        if(sample.sourceIdentity!=declaration.sourceIdentity || sample.frameIdentity!=declaration.frameIdentity || sample.clockIdentity!=declaration.clockIdentity)
            return reject("source_frame_or_clock_identity_mismatch",availableT)
        if(!validClock(sample.sourceT,availableT))return reject("source_after_availability_or_invalid_clock",availableT)
        if(lastT!=null && sample.sourceT<lastT!!)return reject("source_clock_reset",availableT,true)
        if(lastAvailable!=null && availableT<lastAvailable!!)return reject("availability_clock_reset",availableT,true)
        if(lastT!=null && sample.sourceT==lastT!!)return reject("duplicate_source_time",availableT)
        if(lastT!=null && sample.sourceT-lastT!!>.5+1e-8)return reject("source_gap",availableT,true)
        val accel=sample.accel;val gyro=sample.gyro
        if(accel.size!=3 || gyro.size!=3 || (accel+gyro).any {!it.isFinite()})return reject("invalid_imu_values",availableT,true)
        val magnitude=sqrt(accel.sumOf {it*it});val rotation=sqrt(gyro.sumOf {it*it})
        if(!magnitude.isFinite() || magnitude !in .1..500.0 || !rotation.isFinite() || rotation>5.0)return reject("outside_navigation_sensor_envelope",availableT,true)
        engine.onImu(PhoneImu(sample.sourceT,availableT,accel,gyro,0))
        lastT=sample.sourceT;lastAvailable=availableT;delivered++;return true
    }
    private fun reject(reason:String,availableT:Double,invalidate:Boolean=false):Boolean {
        rejected++;lastReason=reason
        if(invalidate) {fault=reason;engine.onDiscontinuity("external_imu_$reason",availableT)}
        return false
    }
    fun status()=ExternalImuStatus(delivered,rejected,fault,lastT,lastAvailable,lastReason)
}
