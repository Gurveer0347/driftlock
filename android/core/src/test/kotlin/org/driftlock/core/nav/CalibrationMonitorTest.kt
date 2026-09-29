package org.driftlock.core.nav

import org.junit.Assert.*
import org.junit.Test

class CalibrationMonitorTest {
    private fun converged():Esekf {val n=Esekf();n.P=identity(19);for(i in 0..18)n.P[i][i]=1e-8;return n}
    @Test fun qualityRequiresAllBiasScaleMountCovariancesAndObservedManeuvers() {
        val c=CalibrationMonitor(NavConfig());val n=converged()
        c.accumulate(30.0,.1,.5,true)
        assertEquals(CalibrationState.GOOD,c.grade(n))
        for(i in listOf(9,10,11,12,13,14,15,16,17,18)) {
            n.P[i][i]=1.0;assertNotEquals("Unobserved state $i",CalibrationState.GOOD,c.grade(n));n.P[i][i]=1e-8
        }
    }
    @Test fun rejectedFixIntervalsAndRemountCannotManufactureCalibration() {
        val n=converged();val c=CalibrationMonitor(NavConfig())
        c.accumulate(100.0,2.0,10.0,false);assertEquals(CalibrationState.UNCALIBRATED,c.grade(n))
        c.accumulate(30.0,.1,.5,true);assertEquals(CalibrationState.GOOD,c.grade(n))
        c.clear();assertEquals(CalibrationState.UNCALIBRATED,c.grade(n))
    }
    @Test fun straightConstantSpeedKeepsCalibrationBelowGood() {
        val c=CalibrationMonitor(NavConfig());c.accumulate(100.0,0.0,0.0,true)
        assertNotEquals(CalibrationState.GOOD,c.grade(converged()))
    }
}
