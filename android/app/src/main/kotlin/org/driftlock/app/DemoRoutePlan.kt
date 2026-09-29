package org.driftlock.app

import org.driftlock.core.routing.*
import kotlin.math.abs
import kotlin.math.atan2

/** Geometry for an explicitly scripted journey on actual offline route edges. */
internal data class RoutePose(val point:Point,val headingRad:Double)

data class GuidedDetour(
    val anchor:Point,
    val anchorDistanceM:Double,
    val blocked:RoadEdge,
    val anchorRoad:RoadEdge,
    val alternate:Route,
    val deltaM:Double,
)

internal object DemoRoutePlan {
    fun find(graph:RoadGraph,primary:Route,destination:Long,alternatives:List<Route>):GuidedDetour? {
        if(primary.legs.size<2)return null
        val middle=primary.legs.lastIndex/3.0
        return (0 until primary.legs.lastIndex).sortedBy{abs(it-middle)}.firstNotNullOfOrNull {i->
            val leg=primary.legs[i]
            val current=graph.edges[leg.edgeId] ?: return@firstNotNullOfOrNull null
            val next=graph.edges[primary.legs[i+1].edgeId] ?: return@firstNotNullOfOrNull null
            val fraction=(leg.startFraction+leg.endFraction)/2
            val result=OfflineRouter(graph).reroute(RoadPosition(current.id,fraction),destination,
                alternatives,setOf(next.segmentId))
            val alternate=result.route ?: return@firstNotNullOfOrNull null
            if(alternate.legs.any{graph.edges[it.edgeId]?.segmentId==next.segmentId})return@firstNotNullOfOrNull null
            val before=primary.legs.take(i).sumOf{prior->
                graph.edges.getValue(prior.edgeId).lengthM*(prior.endFraction-prior.startFraction)
            }
            val anchorDistance=before+current.lengthM*(fraction-leg.startFraction)
            val remaining=primary.distanceM-anchorDistance
            val anchor=pose(graph,primary,anchorDistance).point
            if(anchor.distance(pose(graph,alternate,0.0).point)>1.0)return@firstNotNullOfOrNull null
            GuidedDetour(anchor,anchorDistance,next,current,alternate,alternate.distanceM-remaining)
        }
    }

    fun pose(graph:RoadGraph,route:Route,distanceM:Double):RoutePose {
        require(route.legs.isNotEmpty()){"A route needs mapped legs"}
        var remaining=distanceM.coerceIn(0.0,route.distanceM)
        for((index,leg) in route.legs.withIndex()) {
            val edge=graph.edges.getValue(leg.edgeId)
            val length=edge.lengthM*(leg.endFraction-leg.startFraction)
            if(remaining<=length || index==route.legs.lastIndex) {
                val geometry=routeLegGeometry(edge,leg)
                require(geometry.size>=2){"Route leg has no usable geometry"}
                val segments=geometry.zipWithNext().map{it.first.distance(it.second)}
                var along=if(length<=0.0)0.0 else remaining/length*segments.sum()
                for((part,segment) in segments.withIndex()) {
                    if(along<=segment || part==segments.lastIndex) {
                        val from=geometry[part];val to=geometry[part+1]
                        val u=if(segment<=0.0)0.0 else (along/segment).coerceIn(0.0,1.0)
                        return RoutePose(Point(from.eastM+(to.eastM-from.eastM)*u,
                            from.northM+(to.northM-from.northM)*u),
                            atan2(to.northM-from.northM,to.eastM-from.eastM))
                    }
                    along-=segment
                }
            }
            remaining-=length
        }
        error("Unreachable route geometry")
    }
}
