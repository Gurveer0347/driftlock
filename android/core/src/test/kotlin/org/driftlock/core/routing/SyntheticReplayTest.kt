package org.driftlock.core.routing
import org.driftlock.core.demo.SyntheticReplay
import org.junit.Assert.*
import org.junit.Test

class SyntheticReplayTest {
    private val fixture="# Synthetic fixture, software verification only\nI,0,0,0,9.80665,0,0,0\nG,1,30,76,100,3,6,2,.2,90\nI,1,0,0,9.80665,0,0,0\n"
    @Test fun explicitReplayPreservesTiesAndDoesNotReleaseFutureRows() {
        val r=SyntheticReplay(fixture)
        assertEquals(1,r.takeUntil(.5).size);assertTrue(r.takeUntil(.9).isEmpty());assertFalse(r.finished)
        assertEquals(listOf("G","I"),r.takeUntil(1.0).map{it.kind});assertTrue(r.finished)
    }
    @Test fun unmarkedRealOrNonmonotonicDataCannotEnterSyntheticDemo() {
        assertThrows(IllegalArgumentException::class.java){SyntheticReplay(fixture.substringAfter('\n'))}
        assertThrows(IllegalArgumentException::class.java){SyntheticReplay(fixture+"I,.5,0,0,9.8,0,0,0\n")}
    }
}
