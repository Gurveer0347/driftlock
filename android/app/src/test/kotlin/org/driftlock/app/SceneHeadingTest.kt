package org.driftlock.app
import kotlin.math.PI
import org.junit.Assert.*
import org.junit.Test
class SceneHeadingTest {
    @Test fun crossingNorthUsesAShortTurnInsteadOfSpinningAlmostAFullCircle() {
        assertEquals(181.0*PI/180,nearestSceneHeading(179.0*PI/180,-179.0*PI/180),1e-8)
        assertEquals(-181.0*PI/180,nearestSceneHeading(-179.0*PI/180,179.0*PI/180),1e-8)
    }
}
