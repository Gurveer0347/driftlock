package org.driftlock.core.routing

import kotlin.math.*

data class Point(val eastM: Double, val northM: Double) {
    init { require(eastM.isFinite() && northM.isFinite()) }
    fun distance(other: Point) = hypot(eastM - other.eastM, northM - other.northM)
}
data class RoadNode(val id: Long, val point: Point)
data class RoadEdge(
    val id: String, val from: Long, val to: Long, val segmentId: String,
    val wayId: Long, val name: String, val lengthM: Double,
    val geometry: List<Point>, val speedLimitMps: Double? = null,
) {
    init {
        require(id.isNotBlank() && segmentId.isNotBlank())
        require(from != to && lengthM.isFinite() && lengthM > 0)
        require(geometry.size >= 2)
        require(speedLimitMps == null || speedLimitMps.isFinite() && speedLimitMps > 0)
    }
}
data class TurnRestriction(val incomingEdge: String, val outgoingEdge: String, val only: Boolean = false)
data class Coverage(val minEastM: Double, val minNorthM: Double, val maxEastM: Double, val maxNorthM: Double) {
    init { require(listOf(minEastM,minNorthM,maxEastM,maxNorthM).all { it.isFinite() }); require(maxEastM > minEastM && maxNorthM > minNorthM) }
    fun contains(p: Point) = p.eastM in minEastM..maxEastM && p.northM in minNorthM..maxNorthM
}
class RoadGraph(
    val id: String, nodes: List<RoadNode>, edges: List<RoadEdge>,
    val restrictions: Set<TurnRestriction>, val coverage: Coverage,
    val originLatDeg: Double, val originLonDeg: Double,
    val source: String, val attribution: String,
) {
    val nodes = nodes.associateBy { it.id }
    val edges = edges.associateBy { it.id }
    val outgoing = edges.groupBy { it.from }
    init {
        require(id.isNotBlank() && source.isNotBlank() && attribution.isNotBlank())
        require(originLatDeg.isFinite() && originLatDeg in -85.0..85.0)
        require(originLonDeg.isFinite() && originLonDeg in -180.0..180.0)
        require(this.nodes.size == nodes.size && this.edges.size == edges.size)
        for (edge in edges) {
            require(edge.from in this.nodes && edge.to in this.nodes)
            require(edge.geometry.first().distance(this.nodes.getValue(edge.from).point) < 0.1)
            require(edge.geometry.last().distance(this.nodes.getValue(edge.to).point) < 0.1)
        }
        for (r in restrictions) {
            require(r.incomingEdge in this.edges && r.outgoingEdge in this.edges)
            require(this.edges.getValue(r.incomingEdge).to == this.edges.getValue(r.outgoingEdge).from)
        }
    }
    fun allows(previousEdge: String?, next: RoadEdge): Boolean {
        if (previousEdge == null) return true
        val prev = edges.getValue(previousEdge)
        if (prev.to != next.from) return false
        // Avoid implicit mid-road reversals. A legal loop/connector can reverse direction.
        if (prev.segmentId == next.segmentId && prev.from == next.to) return false
        val rules = restrictions.filter { it.incomingEdge == previousEdge }
        if (rules.any { !it.only && it.outgoingEdge == next.id }) return false
        val only = rules.filter { it.only }
        return only.isEmpty() || only.any { it.outgoingEdge == next.id }
    }
}
data class RoadPosition(val edgeId: String, val fraction: Double) {
    init { require(fraction.isFinite() && fraction in 0.0..1.0) }
}
data class RouteLeg(val edgeId: String, val startFraction: Double = 0.0, val endFraction: Double = 1.0)
data class Route(val id: String, val legs: List<RouteLeg>, val distanceM: Double, val estimatedSeconds: Double?)
enum class RouteStatus { READY, ALTERNATE_ACTIVE, REROUTED_OFFLINE, NO_DOWNLOADED_ROUTE_AVAILABLE, OUTSIDE_COVERAGE }
data class RoutingResult(val status: RouteStatus, val route: Route?, val reason: String)
