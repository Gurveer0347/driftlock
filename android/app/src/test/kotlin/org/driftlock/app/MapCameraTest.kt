package org.driftlock.app

import org.driftlock.core.routing.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class MapCameraTest {
    @Test fun zoomKeepsFocusPointCentredAndTapInverseFindsSameRoadPoint() {
        val camera=MapCamera(Point(100.0,80.0),2.5,12.0,-8.0)
        val centre=camera.project(Point(100.0,80.0),800.0,600.0)
        assertEquals(412.0,centre.first,1e-9)
        assertEquals(292.0,centre.second,1e-9)
        val actual=Point(115.0,125.0)
        val screen=camera.project(actual,800.0,600.0)
        val recovered=camera.unproject(screen.first,screen.second,800.0,600.0)
        assertEquals(actual.eastM,recovered.eastM,1e-9)
        assertEquals(actual.northM,recovered.northM,1e-9)
    }
    @Test fun crossingRoadAndSurroundingPolygonStayVisibleWithVerticesOutsideViewport() {
        val view=MapCamera(Point(0.0,0.0),1.0)
        assertTrue(view.intersects(listOf(Point(-1000.0,0.0),Point(1000.0,0.0)),400.0,300.0))
        assertTrue(view.intersects(listOf(Point(-1000.0,-1000.0),Point(1000.0,-1000.0),
            Point(1000.0,1000.0),Point(-1000.0,1000.0)),400.0,300.0))
        assertFalse(view.intersects(listOf(Point(1000.0,1000.0),Point(1100.0,1100.0)),400.0,300.0))
    }
}
