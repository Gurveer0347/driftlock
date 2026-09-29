package org.driftlock.core.edge

import org.driftlock.core.nav.*
import org.driftlock.core.sensors.*
import org.junit.Assert.*
import org.junit.Test

internal fun syntheticDeclaration()=ExternalImuDeclaration("synthetic-edge-imu","m/s^2",true,"rad/s","fixed_right_handed_sensor_xyz","fixture-body","seconds_since_boot_shared_monotonic","fixture-clock","Explicit synthetic software fixture; no hardware declaration")
internal fun edgeSample(t:Double,accel:DoubleArray=doubleArrayOf(0.0,0.0,GRAVITY),gyro:DoubleArray=DoubleArray(3))=ExternalImuSample("synthetic-edge-imu","fixture-body","fixture-clock",t,accel,gyro)

class ExternalImuAdapterTest {
    @Test fun undeclaredOrUnsupportedSemanticsCannotStartAdapter() {
        val good=syntheticDeclaration()
        for(bad in listOf(good.copy(accelerationUnits=null),good.copy(accelerationUnits="g"),good.copy(includesGravity=false),good.copy(angularRateUnits="deg/s"),good.copy(frameConvention="ENU"),good.copy(clockConvention="unix_time"),good.copy(clockIdentity=""),good.copy(sourceIdentity=""),good.copy(evidence=""))) {
            try {ExternalImuAdapter(NavigationEngine(),bad);fail("Expected declaration rejection: $bad")}catch(_:IllegalArgumentException){}
        }
    }
    @Test fun sourceAvailabilityAndIdentityRemainExplicit() {
        val e=NavigationEngine();val a=ExternalImuAdapter(e,syntheticDeclaration())
        assertFalse(a.accept(edgeSample(.1),.09))
        assertNull(e.snapshot().t)
        assertFalse(a.accept(ExternalImuSample("other-source","fixture-body","fixture-clock",.1,doubleArrayOf(0.0,0.0,GRAVITY),DoubleArray(3)),.101))
        assertTrue(a.accept(edgeSample(.1),.101));assertEquals(.1,e.snapshot().t!!,0.0)
        assertEquals(.101,a.status().lastAvailableT!!,0.0);assertEquals(1,a.status().deliveredSamples)
    }
    @Test fun resetOrGapLatchesSessionFaultUntilNewSession() {
        for(reset in listOf(true,false)) {
            val e=NavigationEngine();val a=ExternalImuAdapter(e,syntheticDeclaration())
            assertTrue(a.accept(edgeSample(1.0),1.001))
            assertFalse(a.accept(edgeSample(if(reset).5 else 2.0),2.001))
            assertNotNull(a.status().sessionFault)
            assertFalse(a.accept(edgeSample(2.1),2.101))
            assertEquals(AlignmentState.DEGRADED,e.snapshot().alignment)
        }
    }
    @Test fun nonFiniteValuesAndClockDomainMismatchAreRejected() {
        val a=ExternalImuAdapter(NavigationEngine(),syntheticDeclaration())
        assertFalse(a.accept(edgeSample(.1,doubleArrayOf(Double.NaN,0.0,GRAVITY)),.101))
        assertFalse(a.accept(ExternalImuSample("synthetic-edge-imu","fixture-body","wrong-clock",.1,doubleArrayOf(0.0,0.0,GRAVITY),DoubleArray(3)),.101))
        assertEquals(0,a.status().deliveredSamples)
    }
    @Test fun declaredBodySamplesMatchDirectEngineAcrossObservableManeuver() {
        val direct=NavigationEngine(allowMockFixes=true);val bridged=NavigationEngine(allowMockFixes=true)
        val adapter=ExternalImuAdapter(bridged,syntheticDeclaration())
        SyntheticEdgeFixture.fill(direct,null);SyntheticEdgeFixture.fill(bridged,adapter)
        assertEquals(AlignmentState.ALIGNED,bridged.snapshot().alignment)
        assertEquals(direct.snapshot(),bridged.snapshot())
        assertEquals(900,adapter.status().deliveredSamples)
    }
}

internal object SyntheticEdgeFixture {
    fun fill(engine:NavigationEngine,adapter:ExternalImuAdapter?):Pair<Double,DoubleArray> {
        engine.confirmForwardMotion()
        var last=0.0 to DoubleArray(6)
        javaClass.getResourceAsStream("/nav/maneuver.csv")!!.bufferedReader().useLines {lines->
            lines.filter {!it.startsWith("#") && it.isNotBlank()}.forEach {line->
                val cells=line.split(',');val x=cells.drop(1).map {it.toDouble()}
                if(cells[0]=="I") {
                    val accel=x.subList(1,4).toDoubleArray();val gyro=x.subList(4,7).toDoubleArray()
                    if(adapter==null)engine.onImu(PhoneImu(x[0],x[0]+.001,accel,gyro,0))
                    else check(adapter.accept(edgeSample(x[0],accel,gyro),x[0]+.001))
                    last=x[0] to (accel+gyro)
                } else engine.onGnss(PhoneGnss(x[0],x[0]+.001,x[1],x[2],x[3],x[4],x[6],x[7],x[8],3.0,"synthetic software fixture",true,x[5]))
            }
        }
        return last
    }
}
