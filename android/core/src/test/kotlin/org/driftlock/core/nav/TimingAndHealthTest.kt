package org.driftlock.core.nav

import org.driftlock.core.sensors.*
import org.junit.Assert.*
import org.junit.Test

class TimingAndHealthTest {
    private fun imu(t:Double,a:DoubleArray=doubleArrayOf(0.0,0.0,GRAVITY))=PhoneImu(t,t+.001,a,DoubleArray(3),0)
    private fun fix(t:Double,receipt:Double=t+.001)=PhoneGnss(t,receipt,30.0,76.0,null,2.0,10.0,.2,90.0,3.0,"test",false)
    private fun at(t:Double):NavigationEngine {val e=NavigationEngine();repeat((t/.05).toInt()+1) {e.onImu(imu(it*.05))};return e}
    @Test fun delayedProviderFixIsPropagatedAndRetainsOriginalAge() {
        val e=at(1.0);e.onGnss(fix(.8,1.001));val s=e.snapshot()
        assertEquals(1,s.acceptedGnss);assertEquals(.2,s.timeSinceFixS!!,1e-9)
        val current=at(1.0);current.onGnss(fix(1.0,1.001))
        // The EKF posterior also reflects position/velocity cross covariance. Compare the
        // propagated fix with its same-epoch counterpart, not the raw measurement.
        assertTrue(s.positionEnuM!![0]-current.snapshot().positionEnuM!![0] in 1.5..2.5);assertNull(s.position!!.altitudeM)
        val event=s.audit.last {it.kind=="gnss_accepted"}
        assertEquals(1.0,event.t!!,1e-9);assertEquals(.8,event.measurementT!!,1e-9);assertEquals(1.001,event.receiptT!!,1e-9)
        assertEquals("propagated_provider_velocity_to_current_epoch",event.reason)
        repeat(48) {e.onImu(imu(1.05+it*.05))}
        assertEquals(GnssState.DENIED,e.snapshot().gnss)
    }
    @Test fun lateFixCovarianceIncludesPropagationUncertainty() {
        val late=at(1.0);val current=at(1.0)
        late.onGnss(fix(.8,1.001));current.onGnss(fix(1.0,1.001))
        assertTrue(late.snapshot().positionSigmaM!!>current.snapshot().positionSigmaM!!)
    }
    @Test fun unsupportedLateFixesCannotDefineOrigin() {
        for (f in listOf(fix(.8,1.001).copy(speedAccuracyMps=null),fix(.8,1.001).copy(courseAccuracyDeg=null),fix(.4,1.001),fix(.8,1.001).copy(speedAccuracyMps=3.0))) {
            val e=at(1.0);e.onGnss(f);assertNull(e.snapshot().origin);assertEquals(1,e.snapshot().rejectedGnss)
        }
    }
    @Test fun delayedOutlierCannotRenewTrustedMeasurementTime() {
        val e=at(1.0);e.onGnss(fix(.8,1.001))
        e.onImu(imu(1.05));e.onGnss(fix(.9,1.051).copy(latitudeDeg=40.0))
        assertEquals(1,e.snapshot().acceptedGnss);assertEquals(.25,e.snapshot().timeSinceFixS!!,1e-9)
        assertEquals(GnssState.DEGRADING,e.snapshot().gnss)
    }
    @Test fun finiteButOverflowingAccelerationIsRejectedBeforePropagation() {
        val e=at(1.0);e.onGnss(fix(1.0).copy(speedMps=0.0))
        e.onImu(imu(1.05,doubleArrayOf(1e200,0.0,GRAVITY)))
        val s=e.snapshot();assertEquals(AlignmentState.DEGRADED,s.alignment)
        assertTrue(s.covariance.flatten().all {it.isFinite()});assertTrue(s.velocityEnuMps.all {it.isFinite()})
        assertTrue(s.audit.any {it.reason=="invalid_imu"})
    }
    @Test fun firstTrustedVelocityIsNotGatedAgainstInventedStationaryPrior() {
        val e=NavigationEngine();e.onGnss(fix(0.0).copy(speedMps=20.0))
        assertEquals(20.0,e.snapshot().velocityEnuMps[0],.1)
    }
    @Test fun firstImuAfterTrustedFixPreservesElapsedBootstrapMotion() {
        val e=NavigationEngine();e.onGnss(fix(0.0).copy(speedMps=2.0));val v=e.snapshot().velocityEnuMps[0]
        e.onImu(imu(.2));assertEquals(v*.2,e.snapshot().positionEnuM!![0],1e-8)
    }
    @Test fun untrustedZeroProviderSpeedCannotTriggerStationaryConstraints() {
        for(missingAccuracy in listOf(true,false)) {
            val e=NavigationEngine()
            e.onGnss(fix(0.0).copy(speedMps=if(missingAccuracy)0.0 else 10.0,speedAccuracyMps=if(missingAccuracy)null else .2))
            e.onImu(imu(0.0));e.onImu(imu(.05));e.onImu(imu(.10))
            if(!missingAccuracy)e.onGnss(fix(.10).copy(speedMps=0.0)) // Position passes, velocity innovation fails.
            repeat(30) {e.onImu(imu(.15+it*.01))}
            assertEquals("No trusted stationary speed: $missingAccuracy",.02*.02,e.snapshot().covariance[12][12],1e-12)
        }
    }
    @Test fun rejectedFutureClockCannotPoisonLaterValidFixSequence() {
        val e=at(1.0);e.onGnss(fix(10.0));assertNull(e.snapshot().origin)
        e.onGnss(fix(1.0));assertEquals(1,e.snapshot().acceptedGnss)
    }
    @Test fun mockAndBackwardFixesNeverRenewTrust() {
        val e=at(1.0);e.onGnss(fix(1.0).copy(isMock=true));assertNull(e.snapshot().origin)
        e.onGnss(fix(1.0));e.onGnss(fix(.99));assertEquals(1,e.snapshot().acceptedGnss)
    }
}
