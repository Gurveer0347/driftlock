package org.driftlock.app

import org.driftlock.core.routing.Point

/** Invertible local map projection. Depth is presentation styling, not measured elevation. */
internal data class MapCamera(
    val focus:Point,val pixelsPerMeter:Double,val panX:Double=0.0,val panY:Double=0.0,
) {
    private val tilt=.14
    private val foreshorten=.78
    fun project(point:Point,width:Double,height:Double):Pair<Double,Double> {
        val east=point.eastM-focus.eastM
        val south=focus.northM-point.northM
        return width/2+(east+tilt*south)*pixelsPerMeter+panX to
            height/2+south*foreshorten*pixelsPerMeter+panY
    }
    fun unproject(x:Double,y:Double,width:Double,height:Double):Point {
        val south=(y-height/2-panY)/(foreshorten*pixelsPerMeter)
        val east=(x-width/2-panX)/pixelsPerMeter-tilt*south
        return Point(focus.eastM+east,focus.northM-south)
    }
    /** Conservative bounds also retain crossing segments and areas surrounding the viewport. */
    fun intersects(points:List<Point>,width:Double,height:Double,padding:Double=90.0):Boolean {
        if(points.isEmpty())return false
        val projected=points.map{project(it,width,height)}
        return projected.minOf{it.first}<=width+padding && projected.maxOf{it.first}>=-padding &&
            projected.minOf{it.second}<=height+padding && projected.maxOf{it.second}>=-padding
    }
}
