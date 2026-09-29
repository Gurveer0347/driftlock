package org.driftlock.app

import org.driftlock.core.routing.Point
import org.driftlock.core.routing.RoadEdge
import org.driftlock.core.routing.RouteLeg

/** Clip a routed edge to its actual fractional start/end, including intermediate bends. */
internal fun routeLegGeometry(edge:RoadEdge,leg:RouteLeg):List<Point> {
    val lengths=edge.geometry.zipWithNext().map{it.first.distance(it.second)}
    val total=lengths.sum()
    if(total<=0.0)return edge.geometry
    val start=leg.startFraction.coerceIn(0.0,1.0)*total
    val end=leg.endFraction.coerceIn(0.0,1.0)*total
    if(end<start)return emptyList()
    fun at(distance:Double):Point {
        var travelled=0.0
        for(i in lengths.indices){
            val length=lengths[i]
            if(distance<=travelled+length || i==lengths.lastIndex){
                val u=if(length<=0.0)0.0 else ((distance-travelled)/length).coerceIn(0.0,1.0)
                val a=edge.geometry[i];val b=edge.geometry[i+1]
                return Point(a.eastM+(b.eastM-a.eastM)*u,a.northM+(b.northM-a.northM)*u)
            }
            travelled+=length
        }
        return edge.geometry.last()
    }
    val out=mutableListOf(at(start))
    var travelled=0.0
    for(i in lengths.indices){
        travelled+=lengths[i]
        if(travelled>start && travelled<end)out.add(edge.geometry[i+1])
    }
    out.add(at(end))
    return out.filterIndexed{i,p->i==0 || p!=out[i-1]}
}
