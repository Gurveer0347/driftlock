package org.driftlock.core.nav

import org.junit.Assert.*
import org.junit.Test
import java.util.Properties

/** Generated exclusively from repaired Python and explicit synthetic states. */
class EsekfParityTest {
    private fun state(n: Esekf) = n.state.p + n.state.v + n.state.qNb + n.state.ba + n.state.bg + n.state.qVb + doubleArrayOf(n.state.ks, n.state.t)
    private fun numbers(p: Properties, key: String) = p.getProperty(key).split(',').map { it.toDouble() }.toDoubleArray()
    @Test fun numericalPropagationAndEveryMeasurementMatchPython() {
        for (name in listOf("mixed", "stationary0", "stationary1", "stationary2")) {
            val p = Properties()
            javaClass.getResourceAsStream("/nav/$name.properties").use { requireNotNull(it); p.load(it) }
            val n = Esekf()
            val initial = numbers(p, "initial.state")
            n.state.p=initial.copyOfRange(0,3); n.state.v=initial.copyOfRange(3,6)
            n.state.qNb=initial.copyOfRange(6,10); n.state.ba=initial.copyOfRange(10,13)
            n.state.bg=initial.copyOfRange(13,16); n.state.qVb=initial.copyOfRange(16,20)
            n.state.ks=initial[20]; n.state.t=initial[21]
            val covariance=numbers(p,"initial.P"); n.P=Array(19) { r -> DoubleArray(19) { c -> covariance[r*19+c] } }
            repeat(p.getProperty("ops").toInt()) { i ->
                val x=numbers(p,"$i.input")
                val accepted: Boolean? = when(p.getProperty("$i.op")) {
                    "predict" -> { n.predict(x.copyOfRange(1,4),x.copyOfRange(4,7),x[0]); null }
                    "gnss_position" -> n.updateGnssPosition(x.copyOfRange(0,3),x[3],x[4])
                    "gnss_velocity" -> n.updateGnssVelocity(x.copyOfRange(0,3),x[3])
                    "nhc" -> n.updateNhc(x[0])
                    "speed" -> n.updateForwardSpeed(x[0],x[1])
                    "map" -> n.updateMapPosition(x.copyOfRange(0,2),x[2])
                    "zupt" -> n.updateZupt()
                    "zaru" -> n.updateZaru(x)
                    else -> error("Unknown fixture operation")
                }
                if (accepted != null) assertEquals("$name operation $i accepted",p.getProperty("$i.accepted").toBoolean(),accepted)
                assertArrayEquals("$name operation $i state",numbers(p,"$i.state"),state(n),1e-8)
                assertArrayEquals("$name operation $i covariance",numbers(p,"$i.P"),n.P.flatMap { it.asIterable() }.toDoubleArray(),1e-8)
                assertEquals("$name heading sigma",p.getProperty("$i.headingSigma").toDouble(),n.yawSigma,1e-8)
            }
        }
    }

    @Test fun invalidInputsCannotChangeFilter() {
        val n=Esekf(); val before=state(n); val p=n.P.map { it.copyOf() }.toTypedArray()
        for (action in listOf<() -> Unit>(
            {n.predict(doubleArrayOf(Double.NaN,0.0,9.8),DoubleArray(3),.1)},
            {n.predict(doubleArrayOf(0.0,0.0,9.8),DoubleArray(3),1.0)},
            {n.updateForwardSpeed(1.0,0.0)}, {n.updateForwardSpeed(1.0,Double.NaN)},
            {n.updateMapPosition(doubleArrayOf(Double.NaN,0.0),3.0)},
            {n.updateGnssPosition(DoubleArray(3),-1.0,3.0)})) {
            try { action(); fail("Expected invalid measurement rejection") } catch (_:IllegalArgumentException) {}
            assertArrayEquals(before,state(n),0.0)
            for(i in p.indices) assertArrayEquals(p[i],n.P[i],0.0)
        }
    }
    @Test fun reverseSpeedAndTinyPositiveSigmaAreNotClamped() {
        val n=Esekf(); n.state.v[0]=-2.0
        assertTrue(n.updateForwardSpeed(-2.0,.001))
        assertTrue(n.P[3][3] < 1.0)
    }
}
