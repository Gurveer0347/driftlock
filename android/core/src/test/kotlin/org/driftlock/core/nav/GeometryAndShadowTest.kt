package org.driftlock.core.nav

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class GeometryAndShadowTest {
    @Test fun wgs84EnuAxesRoundTripAndUnknownAltitudeAreExplicit() {
        for(origin in listOf(GeographicPosition(0.0,0.0,10.0),GeographicPosition(30.0,76.0,250.0),GeographicPosition(-45.0,-120.0,null))) {
            val f=LocalFrame(origin)
            assertArrayEquals(DoubleArray(3),f.toEnu(origin),1e-8)
            for(p in listOf(doubleArrayOf(100.0,0.0,0.0),doubleArrayOf(0.0,100.0,0.0),doubleArrayOf(-300.0,150.0,5.0))) {
                val g=f.fromEnu(p)
                if(origin.altitudeM!=null)assertArrayEquals(p,f.toEnu(g),1e-6) else assertNull(g.altitudeM)
            }
            assertTrue(f.fromEnu(doubleArrayOf(0.0,100.0,0.0)).latitudeDeg>origin.latitudeDeg)
        }
    }
    @Test fun shadowNeedsCompletedHealthyReferenceAndRefitsQuadraticDiscrepancy() {
        val cfg=NavConfig(shadowHorizonS=4.0,shadowLaunchIntervalS=20.0)
        val s=ShadowDr(cfg);val live=Esekf(cfg)
        assertNull(s.predicted(10.0));s.tick(0.0,live,true,0.0)
        for(i in 1..80) {
            val t=i*.05;s.predict(doubleArrayOf(0.0,0.0,GRAVITY),DoubleArray(3),.05)
            live.state.t=t;live.state.p[0]=.2*t+.1*t*t
            s.tick(t,live,true,0.0)
        }
        assertEquals(1,s.completed)
        assertEquals(3.5,s.predicted(5.0)!!,1e-7)
        s.clear();assertNull(s.predicted(10.0))
    }
    @Test fun calibratedShadowUsesTheSameConstraintCadenceAsLiveFilter() {
        val cfg=NavConfig(shadowHorizonS=2.0,shadowLaunchIntervalS=20.0)
        val s=ShadowDr(cfg);val live=Esekf(cfg);live.state.v[0]=10.0
        val accel=doubleArrayOf(.1,.02,GRAVITY);val gyro=doubleArrayOf(0.0,0.0,.01)
        var lastNhc=0.0;s.tick(0.0,live,true,lastNhc)
        repeat(200) {
            live.predict(accel,gyro,.01);s.predict(accel,gyro,.01)
            if(live.state.t-lastNhc+cfg.timeToleranceS>=.1) {live.updateNhc(gyro[2]-live.state.bg[2]);lastNhc=live.state.t}
            s.constraints(gyro,false);s.tick(live.state.t,live,true,lastNhc)
        }
        assertEquals(1,s.completed)
        assertEquals(0.0,s.comparison()!!.calibratedMeanDiscrepancyM,1e-10)
        assertEquals(0.0,s.predicted(10.0)!!,1e-9)
    }
    @Test fun magneticAidingAlsoReachesTheSatelliteFreeShadow() {
        val cfg=NavConfig(shadowHorizonS=2.0,shadowLaunchIntervalS=20.0)
        val s=ShadowDr(cfg);val live=Esekf(cfg);live.state.v[0]=10.0
        val accel=doubleArrayOf(.5,0.0,GRAVITY);val gyro=doubleArrayOf(0.0,0.0,.01)
        val field=doubleArrayOf(40.0,5.0,2.0);s.tick(0.0,live,true,0.0,0.0,norm(field))
        repeat(200) {i->
            live.predict(accel,gyro,.01);s.predict(accel,gyro,.01)
            if(i%20==0 && live.updateMagneticDirection(field,0.0,.2))s.magnetic(field,0.0,.2)
            s.tick(live.state.t,live,true,0.0)
        }
        assertEquals(0.0,s.comparison()!!.calibratedMeanDiscrepancyM,1e-10)
    }
    @Test fun shadowReferenceInterruptedByRealDenialCannotTrainDrift() {
        val cfg=NavConfig(shadowHorizonS=2.0,shadowLaunchIntervalS=20.0)
        val s=ShadowDr(cfg);val live=Esekf(cfg);s.tick(0.0,live,true,0.0)
        for(i in 1..40) {s.predict(doubleArrayOf(0.0,0.0,GRAVITY),DoubleArray(3),.05);live.state.t=i*.05;s.tick(live.state.t,live,i!=20,0.0)}
        assertEquals(0,s.completed);assertNull(s.predicted(5.0))
    }
}
