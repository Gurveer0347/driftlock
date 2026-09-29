package org.driftlock.app

import org.driftlock.core.routing.*
import org.junit.Assert.*
import org.junit.Test

class MapGeometryTest {
    @Test fun partialRouteLegDrawsOnlyItsActualPolylineFraction() {
        val edge=RoadEdge("e",1,2,"s",3,"Main",20.0,
            listOf(Point(0.0,0.0),Point(10.0,0.0),Point(10.0,10.0)))
        val points=routeLegGeometry(edge,RouteLeg("e",0.25,0.75))
        assertEquals(listOf(Point(5.0,0.0),Point(10.0,0.0),Point(10.0,5.0)),points)
    }
}
