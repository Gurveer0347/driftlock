package org.driftlock.core.routing

import java.util.PriorityQueue
import kotlin.math.min

class OfflineRouter(val graph: RoadGraph) {
    private data class State(val node: Long, val previous: String?)
    private data class Entry(val state: State, val cost: Double)

    private fun search(start: Long, destination: Long, blocked: Set<String>, previous: String? = null,
        requiredArrivalEdge: String? = null): List<RouteLeg>? {
        if (start !in graph.nodes || destination !in graph.nodes) return null
        if (!graph.coverage.contains(graph.nodes.getValue(start).point) ||
            !graph.coverage.contains(graph.nodes.getValue(destination).point)) return null
        val initial=State(start,previous)
        val costs=hashMapOf(initial to 0.0)
        val parent=hashMapOf<State,Pair<State,String>>()
        val queue=PriorityQueue<Entry>(compareBy<Entry>{it.cost}.thenBy{it.state.node}.thenBy{it.state.previous ?: ""})
        queue.add(Entry(initial,0.0))
        while(queue.isNotEmpty()) {
            val current=queue.remove()
            if(current.cost > costs.getValue(current.state)) continue
            if(current.state.node == destination && (requiredArrivalEdge==null || current.state.previous==requiredArrivalEdge)) {
                val path=mutableListOf<RouteLeg>(); var state=current.state
                while(state != initial) {
                    val (prior,edge)=parent.getValue(state); path.add(RouteLeg(edge)); state=prior
                }
                return path.reversed()
            }
            for(edge in graph.outgoing[current.state.node].orEmpty().sortedBy{it.id}) {
                if(edge.segmentId in blocked || !graph.allows(current.state.previous,edge)) continue
                if(!graph.coverage.contains(graph.nodes.getValue(edge.to).point)) continue
                val next=State(edge.to,edge.id); val cost=current.cost+edge.lengthM
                if(cost < costs.getOrDefault(next,Double.POSITIVE_INFINITY)) {
                    costs[next]=cost; parent[next]=current.state to edge.id; queue.add(Entry(next,cost))
                }
            }
        }
        return null
    }

    private fun route(legs: List<RouteLeg>, id: String? = null): Route {
        val distance=legs.sumOf { graph.edges.getValue(it.edgeId).lengthM*(it.endFraction-it.startFraction) }
        val allSpeedsKnown=legs.all{graph.edges.getValue(it.edgeId).speedLimitMps != null}
        val seconds=if(allSpeedsKnown) legs.sumOf {
            val e=graph.edges.getValue(it.edgeId)
            e.lengthM*(it.endFraction-it.startFraction)/e.speedLimitMps!!
        } else null
        return Route(id ?: "local-"+legs.joinToString("|"){it.edgeId+":"+it.startFraction}.hashCode().toUInt().toString(16),legs,distance,seconds)
    }

    fun fromNode(start: Long, destination: Long, blocked: Set<String> = emptySet()): Route? =
        search(start,destination,blocked)?.let{route(it)}

    fun fromPosition(start: RoadPosition, destination: Long, blocked: Set<String> = emptySet()): Route? {
        val edge=graph.edges[start.edgeId] ?: return null
        if(edge.segmentId in blocked && start.fraction < 1.0) return null
        val tail=search(edge.to,destination,blocked,edge.id) ?: return null
        val prefix=if(start.fraction < 1.0) listOf(RouteLeg(edge.id,start.fraction)) else emptyList()
        return route(prefix+tail)
    }

    /** Reach a particular directed edge, including its final turn in the search state. */
    fun fromPositionToEdge(start: RoadPosition, destinationEdge: String, blocked: Set<String> = emptySet()): Route? {
        val edge=graph.edges[start.edgeId] ?: return null
        val target=graph.edges[destinationEdge] ?: return null
        if(target.segmentId in blocked || edge.segmentId in blocked && start.fraction<1.0)return null
        val tail=search(edge.to,target.to,blocked,edge.id,target.id) ?: return null
        val prefix=if(start.fraction<1.0)listOf(RouteLeg(edge.id,start.fraction)) else emptyList()
        return route(prefix+tail)
    }

    /** Distance-first routes. ETA is free-flow speed-limit arithmetic, never live traffic. */
    fun alternatives(start: Long, destination: Long, blocked: Set<String> = emptySet(), count: Int = 3): List<Route> {
        require(count in 1..3)
        val first=fromNode(start,destination,blocked) ?: return emptyList()
        if(first.legs.isEmpty()) return listOf(first.copy(id="A"))
        val accepted=mutableListOf(first)
        val candidates=mutableMapOf<String,Route>()
        fun add(candidate: Route?) { if(candidate != null) candidates[candidate.legs.joinToString("|"){it.edgeId}]=candidate }
        repeat(count-1) {
            for(r in accepted.toList()) for(leg in r.legs) {
                add(fromNode(start,destination,blocked+graph.edges.getValue(leg.edgeId).segmentId))
            }
            add(fromNode(start,destination,blocked+accepted.flatMap{it.legs}.map{graph.edges.getValue(it.edgeId).segmentId}))
            val choice=candidates.values.sortedBy{it.distanceM}.firstOrNull { candidate ->
                candidate.distanceM <= first.distanceM*2.5 && accepted.all { overlap(it,candidate) <= 0.75 }
            }
            if(choice != null) accepted.add(choice)
        }
        return accepted.mapIndexed{i,r->r.copy(id=('A'.code+i).toChar().toString())}
    }

    private fun overlap(a: Route, b: Route): Double {
        val bSegments=b.legs.map{graph.edges.getValue(it.edgeId).segmentId}.toSet()
        val shared=a.legs.filter{graph.edges.getValue(it.edgeId).segmentId in bSegments}
            .sumOf{graph.edges.getValue(it.edgeId).lengthM*(it.endFraction-it.startFraction)}
        return shared/min(a.distanceM,b.distanceM).coerceAtLeast(1e-9)
    }

    fun reroute(start: RoadPosition, destination: Long, alternatives: List<Route>, blocked: Set<String>): RoutingResult {
        val edge=graph.edges[start.edgeId]
            ?: return RoutingResult(RouteStatus.OUTSIDE_COVERAGE,null,"Current road is not in the downloaded graph")
        if(destination !in graph.nodes) return RoutingResult(RouteStatus.OUTSIDE_COVERAGE,null,"Destination is outside downloaded roads")
        if(edge.segmentId in blocked && start.fraction < 1.0)
            return RoutingResult(RouteStatus.NO_DOWNLOADED_ROUTE_AVAILABLE,null,"Current segment is blocked; no legal connection established")
        val prefix=if(start.fraction < 1.0) listOf(RouteLeg(edge.id,start.fraction)) else emptyList()
        for(alternative in alternatives) {
            for(index in alternative.legs.indices) {
                val suffix=alternative.legs.drop(index)
                var node=edge.to; var previous=edge.id; var usable=true
                for(leg in suffix) {
                    val next=graph.edges[leg.edgeId]
                    if(next == null || leg.startFraction != 0.0 || leg.endFraction != 1.0 || next.from != node ||
                        next.segmentId in blocked || !graph.allows(previous,next)) { usable=false; break }
                    node=next.to; previous=next.id
                }
                if(usable && node==destination) return RoutingResult(RouteStatus.ALTERNATE_ACTIVE,
                    route(prefix+suffix,"alternate-${alternative.id}"),"Connected downloaded alternative selected")
            }
        }
        val local=fromPosition(start,destination,blocked)
        return if(local != null) RoutingResult(RouteStatus.REROUTED_OFFLINE,local,"Route computed using downloaded roads")
        else RoutingResult(RouteStatus.NO_DOWNLOADED_ROUTE_AVAILABLE,null,"No legal unblocked path in downloaded roads")
    }
}
