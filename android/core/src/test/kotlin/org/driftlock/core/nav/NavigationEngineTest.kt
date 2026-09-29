package org.driftlock.core.nav

import org.driftlock.core.sensors.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class NavigationEngineTest {
    private fun imu(t:Double,a:DoubleArray=doubleArrayOf(0.0,0.0,9.80665),g:DoubleArray=DoubleArray(3),segment:Long=0) = PhoneImu(t,t+.001,a,g,segment)
    private fun fix(t:Double,lat:Double=30.0,lon:Double=76.0,speed:Double=0.0,accuracy:Double=2.0) = PhoneGnss(t,t+.001,lat,lon,100.0,accuracy,speed,.2,90.0,3.0,"synthetic test fixture",false)
    private fun maneuver(confirmForward:Boolean=true): NavigationEngine {
        val engine=NavigationEngine()
        if(confirmForward)engine.confirmForwardMotion() // Explicit forward-only fixture declaration.
        javaClass.getResourceAsStream("/nav/maneuver.csv")!!.bufferedReader().useLines { lines ->
            lines.filter { !it.startsWith("#") && it.isNotBlank() }.forEach { line ->
                val s=line.split(','); val x=s.drop(1).map { it.toDouble() }
                if(s[0]=="I") engine.onImu(imu(x[0],x.subList(1,4).toDoubleArray(),x.subList(4,7).toDoubleArray()))
                else engine.onGnss(PhoneGnss(x[0],x[0]+.001,x[1],x[2],x[3],x[4],x[6],x[7],x[8],3.0,"synthetic test fixture",false))
            }
        }
        return engine
    }

    @Test fun noFixMeansUnavailablePositionAndNoManufacturedHeading() {
        val engine=NavigationEngine()
        repeat(40) { engine.onImu(imu(it*.05)) }
        val s=engine.snapshot()
        assertNull(s.position); assertNull(s.origin); assertNull(s.headingRad)
        assertEquals(Horizon.UNRELIABLE,s.horizon)
    }
    @Test fun tiltedStationaryPhoneGivesTiltButNeverYaw() {
        for(a in listOf(doubleArrayOf(0.0,0.0,9.80665),doubleArrayOf(9.80665,0.0,0.0),doubleArrayOf(0.0,-9.80665,0.0))) {
            val engine=NavigationEngine();engine.onGnss(fix(0.0))
            repeat(101) { i -> if(i>0 && i%20==0)engine.onGnss(fix(i*.05));engine.onImu(imu(i*.05,a)) }
            val s=engine.snapshot()
            assertEquals(AlignmentState.PARTIAL,s.alignment)
            assertNull(s.headingRad)
            assertTrue(s.velocityEnuMps.all { abs(it)<.1 })
            assertTrue(s.positionEnuM!!.all { abs(it)<.1 })
        }
    }
    @Test fun rejectedAccuracyCannotEstablishOrigin() {
        val engine=NavigationEngine();engine.onGnss(fix(0.0,accuracy=100.0))
        assertNull(engine.snapshot().origin)
        assertEquals(0,engine.snapshot().acceptedGnss)
    }
    @Test fun motionWithoutForwardDeclarationCannotResolveVehicleSign() {
        val s=maneuver(false).snapshot()
        assertEquals(AlignmentState.PARTIAL,s.alignment);assertNull(s.headingRad)
        assertEquals(Horizon.UNRELIABLE,s.horizon)
    }
    @Test fun forwardDeclarationCannotCertifyEarlierUnknownDirectionEvents() {
        val e=maneuver(false);e.confirmForwardMotion();e.onImu(imu(e.snapshot().t!!+.05))
        assertNotEquals(AlignmentState.ALIGNED,e.snapshot().alignment)
        assertNull(e.snapshot().headingRad)
    }
    @Test fun maneuverEstablishesObservableMountAndFiniteCovariance() {
        val s=maneuver().snapshot()
        assertEquals(AlignmentState.ALIGNED,s.alignment)
        assertNotNull(s.headingRad); assertTrue(s.headingSigmaRad!!.isFinite())
        assertTrue(s.covariance.flatten().all { it.isFinite() })
        assertEquals(19,s.covariance.size)
    }
    @Test fun outageOutliersAndRecoveryUseOnlyAcceptedFixes() {
        val engine=maneuver();val start=engine.snapshot().t!!
        repeat(70) { engine.onImu(imu(start+(it+1)*.05)) }
        assertEquals(GnssState.DENIED,engine.snapshot().gnss)
        val accepted=engine.snapshot().acceptedGnss
        repeat(3) { i ->
            val t=start+3.55+i*.05;engine.onGnss(fix(t,lat=40.0));engine.onImu(imu(t))
        }
        assertEquals(accepted,engine.snapshot().acceptedGnss)
        assertEquals(GnssState.DENIED,engine.snapshot().gnss)
        repeat(3) { i ->
            val t=start+3.7+i*.05;val p=engine.snapshot().position!!
            engine.onGnss(fix(t,p.latitudeDeg,p.longitudeDeg,engine.snapshot().speedMps));engine.onImu(imu(t))
        }
        assertEquals(GnssState.STABLE,engine.snapshot().gnss)
    }
    @Test fun handlingDuringBlackoutInvalidatesVehicleConstraints() {
        val engine=maneuver();val start=engine.snapshot().t!!
        repeat(60) {engine.onImu(imu(start+(it+1)*.05))}
        assertEquals(GnssState.DENIED,engine.snapshot().gnss)
        val t=start+3.05
        engine.onImu(imu(t,g=doubleArrayOf(3.0,0.0,0.0)))
        assertEquals(AlignmentState.RECALIBRATING,engine.snapshot().alignment)
        assertNull(engine.snapshot().headingRad)
        assertFalse(engine.onSpeed(SpeedMeasurement(t,3.0,.5,true)))
    }
    @Test fun pastPacketsAndDiscontinuitiesAreExplicit() {
        val engine=maneuver();val t=engine.snapshot().t!!
        assertFalse(engine.onSpeed(SpeedMeasurement(t-.2,1.0,.5,true)))
        engine.onDiscontinuity("queue_overflow")
        assertEquals(AlignmentState.DEGRADED,engine.snapshot().alignment)
        assertEquals(Horizon.UNRELIABLE,engine.snapshot().horizon)
        assertTrue(engine.snapshot().audit.any { it.reason=="queue_overflow" })
    }
    @Test fun queuedCurrentSpeedUsesOnlyPreviousImuAtIntermediateEpoch() {
        val engine=maneuver();val t=engine.snapshot().t!!
        val accepted=engine.snapshot().acceptedSpeeds
        assertTrue(engine.onSpeed(SpeedMeasurement(t+.025,engine.snapshot().speedMps,2.0,true)))
        engine.onImu(imu(t+.05))
        assertEquals(accepted+1,engine.snapshot().acceptedSpeeds)
        val event=engine.snapshot().audit.last { it.kind=="speed_accepted" }
        assertEquals(t+.025,event.t!!,1e-8)
    }
    @Test fun stableLocalMagneticDirectionCanAidAndReversalIsRejected() {
        val engine=maneuver();val t=engine.snapshot().t!!
        val field=doubleArrayOf(25.0,25.0,25.0)
        repeat(10) {i->val mt=t-.09+i*.01;engine.onMag(PhoneMag(mt,t+.001,field,3))}
        engine.onImu(imu(t+.05));engine.onMag(PhoneMag(t+.05,t+.051,field,3))
        assertEquals(MagneticStatus.ACCEPTED,engine.snapshot().magStatus)
        engine.onImu(imu(t+.10));engine.onMag(PhoneMag(t+.10,t+.101,DoubleArray(3) {-field[it]},3))
        assertEquals(MagneticStatus.REJECTED,engine.snapshot().magStatus)
    }
    @Test fun magneticShockNeverBecomesHeadingAid() {
        val engine=maneuver();val t=engine.snapshot().t!!
        engine.onMag(PhoneMag(t,t+.001,doubleArrayOf(800.0,0.0,0.0),3))
        assertEquals(MagneticStatus.REJECTED,engine.snapshot().magStatus)
    }
    @Test fun wrongClocksAndGapDoNotSilentlyPropagate() {
        val engine=NavigationEngine();engine.onGnss(fix(0.0));engine.onImu(imu(0.0));engine.onImu(imu(1.0))
        assertEquals(AlignmentState.DEGRADED,engine.snapshot().alignment)
        assertTrue(engine.snapshot().audit.any { it.reason=="imu_gap" })
    }
}
