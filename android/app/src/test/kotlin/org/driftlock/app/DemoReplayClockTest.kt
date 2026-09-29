package org.driftlock.app

import org.driftlock.core.nav.NavigationEngine
import org.driftlock.core.nav.AlignmentState
import org.driftlock.core.sensors.PhoneImu
import org.driftlock.core.sensors.PhoneGnss
import org.junit.Assert.*
import org.junit.Test

class DemoReplayClockTest {
    @Test fun presenterPauseDoesNotCreateAnImuGapOrStaleSyntheticFix() {
        val clock=DemoReplayClock(1000.0)
        val engine=NavigationEngine(allowMockFixes=true)
        fun imu(relative:Double) {
            val t=clock.sourceTimestamp(relative)
            engine.onImu(PhoneImu(t,t,doubleArrayOf(0.0,0.0,9.80665),doubleArrayOf(0.0,0.0,0.0),0))
        }
        fun fix(relative:Double) {
            val t=clock.sourceTimestamp(relative)
            engine.onGnss(PhoneGnss(t,t,30.0,76.0,0.0,3.0,0.0,.1,0.0,3.0,"explicit_synthetic_replay",true))
        }
        fix(0.0);imu(0.0);imu(.1)
        clock.advance(1000.1,false)
        clock.advance(1010.1,true)
        assertEquals(.1,clock.elapsedS,1e-8)
        clock.advance(1010.12,false)
        assertEquals(.12,clock.elapsedS,1e-8)
        imu(.12);fix(.12)
        val result=engine.snapshot()
        assertFalse(result.audit.any{it.reason=="imu_gap" || it.reason=="gnss_too_old_at_receipt"})
        assertEquals(AlignmentState.PARTIAL,result.alignment)
        assertEquals(2,result.acceptedGnss)
    }
    @Test fun schedulingStallIsBoundedWithoutChangingSourceEpochs() {
        val clock=DemoReplayClock(100.0)
        clock.advance(105.0,false)
        assertEquals(.25,clock.elapsedS,1e-9)
        assertEquals(100.02,clock.sourceTimestamp(.02),1e-9)
    }
}
