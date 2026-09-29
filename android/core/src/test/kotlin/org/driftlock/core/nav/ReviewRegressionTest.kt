package org.driftlock.core.nav

import org.driftlock.core.sensors.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class ReviewRegressionTest {
    private fun imu(t:Double,gz:Double=0.0)=PhoneImu(t,t+.001,doubleArrayOf(0.0,0.0,GRAVITY),doubleArrayOf(0.0,0.0,gz),0)
    private fun fix(t:Double,speed:Double?=null)=PhoneGnss(t,t+.001,30.0,76.0,null,2.0,speed,if(speed==null)null else .2,if(speed==null)null else 90.0,if(speed==null)null else 3.0,"test",false)
    @Test fun rejectedVelocityCannotMoveALateFixBeforePositionGating() {
        val e=NavigationEngine();e.onGnss(fix(0.0,20.0));repeat(11) {e.onImu(imu(it*.05))}
        val before=e.snapshot();val p=LocalFrame(before.origin!!).fromEnu(doubleArrayOf(6.0,0.0,0.0))
        e.onGnss(fix(.3,0.0).copy(receiptT=.501,latitudeDeg=p.latitudeDeg,longitudeDeg=p.longitudeDeg))
        val after=e.snapshot();assertEquals(before.acceptedGnss,after.acceptedGnss)
        assertEquals(before.positionEnuM,after.positionEnuM);assertEquals(before.velocityEnuMps,after.velocityEnuMps)
        assertEquals(before.covariance,after.covariance)
        assertTrue(after.audit.any {it.reason=="late_gnss_velocity_innovation"})
    }
    private fun collect(angle:Double):AlignmentTracker {
        val c=AlignmentTracker(NavConfig())
        repeat(50) {c.push(it*.05,doubleArrayOf(0.0,0.0,GRAVITY),DoubleArray(3),0.0,0.0,0.0)}
        repeat(200) {i->c.push(3+i*.05,doubleArrayOf(1.0,if(i%2==0)tan(angle) else -tan(angle),GRAVITY),DoubleArray(3),10.0,0.0,1.0)}
        return c
    }
    @Test fun equalMagnitudeOpposingDirectionErrorsCannotMeanPerfectMount() {
        assertNull(collect(Math.toRadians(60.0)).solve())
        val small=collect(Math.toRadians(5.0)).solve()!!
        assertEquals(Math.toRadians(5.0)/sqrt(200.0),small.residualRad,1e-9)
    }
    @Test fun satelliteOnlyStopsCannotCalibrateSatelliteFreeShadow() {
        fun seeded():NavigationEngine {
            val e=NavigationEngine();e.onGnss(fix(0.0));e.onImu(imu(0.0))
            // Test-only known level alignment makes the provenance gate observable.
            for((name,value) in listOf("aligned" to true,"forwardConfirmed" to true,"alignment" to AlignmentState.ALIGNED)) {
                e.javaClass.getDeclaredField(name).apply {isAccessible=true}.set(e,value)
            }
            return e
        }
        val a=seeded();val b=seeded()
        for(i in 1..100) {val t=i*.05
            if(i%20==0) {a.onGnss(fix(t,0.0));b.onGnss(fix(t))}
            a.onImu(imu(t,.01));b.onImu(imu(t,.01))
        }
        fun shadow(e:NavigationEngine):Esekf {
            val runner=e.javaClass.getDeclaredField("shadow").apply {isAccessible=true}.get(e)
            val runs=runner.javaClass.getDeclaredField("runs").apply {isAccessible=true}.get(runner) as List<*>
            val run=runs.first()!!
            return run.javaClass.getDeclaredField("nav").apply {isAccessible=true}.get(run) as Esekf
        }
        assertEquals(0,a.snapshot().acceptedSpeeds)
        assertArrayEquals(shadow(b).state.bg,shadow(a).state.bg,1e-12)
        assertEquals(shadow(b).P[14][14],shadow(a).P[14][14],1e-12)
    }
    @Test fun moderateYawRotationDuringConfirmedStopInvalidatesMount() {
        val e=NavigationEngine();e.onGnss(fix(0.0,0.0));e.onImu(imu(0.0))
        for((name,value) in listOf("aligned" to true,"forwardConfirmed" to true,"alignment" to AlignmentState.ALIGNED))
            e.javaClass.getDeclaredField(name).apply {isAccessible=true}.set(e,value)
        for(i in 1..120) {val t=i*.05
            if(i%10==0)e.onGnss(fix(t,0.0))
            e.onImu(imu(t,if(t>2 && t<=3.6)1.0 else 0.0))
        }
        assertNotEquals(AlignmentState.ALIGNED,e.snapshot().alignment)
        assertEquals(Horizon.UNRELIABLE,e.snapshot().horizon)
        assertTrue(e.snapshot().audit.any {it.reason=="rotation_during_trusted_stop"})
    }

    @Test fun persistentNhcInnovationInvalidatesMount() {
        val e=NavigationEngine(NavConfig(mountInnovationWindow=4));e.onGnss(fix(0.0));e.onImu(imu(0.0))
        for((name,value) in listOf("aligned" to true,"forwardConfirmed" to true,"alignment" to AlignmentState.ALIGNED))
            e.javaClass.getDeclaredField(name).apply {isAccessible=true}.set(e,value)
        val nav=e.javaClass.getDeclaredField("nav").apply {isAccessible=true}.get(e) as Esekf
        nav.state.v=doubleArrayOf(10.0,5.0,0.0);nav.P=zeros(N,N)
        for(j in 0 until N)nav.P[j][j]=1e-8
        repeat(20) {e.onImu(imu((it+1)*.05))}
        assertNotEquals(AlignmentState.ALIGNED,e.snapshot().alignment)
        assertTrue(e.snapshot().audit.any {it.reason=="constraint_innovation"})
    }
    @Test fun newlyLearnedMagneticAnchorCannotEnterEarlierShadow() {
        val s=ShadowDr(NavConfig());val live=Esekf();s.tick(0.0,live,true,0.0)
        val runs=s.javaClass.getDeclaredField("runs").apply {isAccessible=true}.get(s) as List<*>
        val run=runs.first()!!
        val n=run.javaClass.getDeclaredField("nav").apply {isAccessible=true}.get(run) as Esekf
        val before=n.state.qNb.copyOf()
        s.magnetic(doubleArrayOf(40.0,5.0,2.0),0.0,.2)
        assertArrayEquals(before,n.state.qNb,0.0)
    }

}
