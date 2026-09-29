package org.driftlock.core.routing

import org.junit.Assert.*
import org.junit.Test

class OfflineRouterTest {
    private val nodes = listOf(
        RoadNode(1, Point(0.0,0.0)), RoadNode(2, Point(100.0,0.0)),
        RoadNode(3, Point(100.0,100.0)), RoadNode(4, Point(200.0,0.0)),
        RoadNode(5, Point(0.0,-100.0)), RoadNode(6, Point(200.0,-100.0)),
        RoadNode(7, Point(300.0,300.0)),
    )
    private fun edge(id: String, from: Long, to: Long, segment: String = id): RoadEdge {
        val a=nodes.single{it.id==from}.point; val b=nodes.single{it.id==to}.point
        return RoadEdge(id,from,to,segment,1,"Road $id",a.distance(b),listOf(a,b),10.0)
    }
    private fun graph(rules: Set<TurnRestriction> = emptySet()) = RoadGraph(
        "unit-test-synthetic",nodes,listOf(edge("a",1,2),edge("b",2,4),edge("c",1,3),edge("d",3,4),
            edge("e",1,5),edge("f",5,6),edge("g",6,4),edge("h",2,3)),rules,
        Coverage(-1.0,-101.0,301.0,301.0),30.0,76.0,"synthetic test fixture","test fixture"
    )
    @Test fun returnsCompleteShortestPathAndDistance() {
        val route=OfflineRouter(graph()).fromNode(1,4)!!
        assertEquals(listOf("a","b"),route.legs.map{it.edgeId})
        assertEquals(200.0,route.distanceM,1e-9); assertEquals(20.0,route.estimatedSeconds!!,1e-9)
    }
    @Test fun blockedSegmentIsExcludedAndDirectedRoadIsNotReversed() {
        val router=OfflineRouter(graph())
        assertEquals(listOf("c","d"),router.fromNode(1,4,setOf("b"))!!.legs.map{it.edgeId})
        assertNull(router.fromNode(4,1)); assertNull(router.fromNode(1,7))
    }
    @Test fun turnRestrictionUsesIncomingEdgeState() {
        val r=OfflineRouter(graph(setOf(TurnRestriction("a","b")))).fromNode(1,4)!!
        assertFalse(r.legs.zipWithNext().any { it.first.edgeId=="a" && it.second.edgeId=="b" })
        val only=OfflineRouter(graph(setOf(TurnRestriction("a","h",true))))
        assertEquals(listOf("a","h","d"),only.fromPosition(RoadPosition("a",0.8),4)!!.legs.map{it.edgeId})
    }
    @Test fun fractionalStartDoesNotTeleportToPassedJunction() {
        val r=OfflineRouter(graph()).fromPosition(RoadPosition("a",0.8),4,setOf("b"))!!
        assertEquals(listOf("a","h","d"),r.legs.map{it.edgeId})
        assertEquals(0.8,r.legs.first().startFraction,0.0)
        assertEquals(20.0+100.0+kotlin.math.sqrt(20000.0),r.distanceM,1e-8)
        assertNull(OfflineRouter(graph()).fromPosition(RoadPosition("a",0.5),4,setOf("a")))
    }
    @Test fun returnsThreeDiverseRoutesWithoutInventingAlternatives() {
        val routes=OfflineRouter(graph()).alternatives(1,4)
        assertEquals(3,routes.size)
        assertEquals(3,routes.map{it.legs.map{l->l.edgeId}}.toSet().size)
        assertTrue(routes.all{it.legs.last().edgeId in setOf("b","d","g")})
        assertTrue(OfflineRouter(graph()).alternatives(4,1).isEmpty())
    }
    @Test fun connectedPrecomputedAlternativeIsUsedBeforeLocalSearch() {
        val router=OfflineRouter(graph())
        val alt=router.fromNode(2,4,setOf("b"))!!
        val result=router.reroute(RoadPosition("a",0.8),4,listOf(alt),setOf("b"))
        assertEquals(RouteStatus.ALTERNATE_ACTIVE,result.status)
        assertEquals(listOf("a","h","d"),result.route!!.legs.map{it.edgeId})
    }
    @Test fun wrongTurnUsesLocalSearchWhenNoDownloadedAlternativeConnects() {
        val router=OfflineRouter(graph())
        val result=router.reroute(RoadPosition("a",0.8),4,emptyList(),setOf("b"))
        assertEquals(RouteStatus.REROUTED_OFFLINE,result.status)
        assertEquals(RouteStatus.NO_DOWNLOADED_ROUTE_AVAILABLE,router.reroute(RoadPosition("a",0.8),7,emptyList(),emptySet()).status)
    }
    @Test fun sameNodeIsArrivalNotFailure() {
        val result=OfflineRouter(graph()).fromNode(1,1)!!
        assertTrue(result.legs.isEmpty()); assertEquals(0.0,result.distanceM,0.0)
    }
    @Test fun invalidStartOrDestinationIsRejected() {
        val router=OfflineRouter(graph())
        assertNull(router.fromNode(99,4)); assertNull(router.fromPosition(RoadPosition("absent",0.2),4))
    }
    @Test fun requiredArrivalEdgeCannotBeReplacedByShorterArrivalAtItsNode() {
        val router=OfflineRouter(graph())
        assertEquals(listOf("a","b"),router.fromPosition(RoadPosition("a",.5),4)!!.legs.map{it.edgeId})
        val route=router.fromPositionToEdge(RoadPosition("a",.5),"d")!!
        assertEquals(listOf("a","h","d"),route.legs.map{it.edgeId})
        assertEquals(.5,route.legs.first().startFraction,0.0)
        assertEquals(50.0+100.0+kotlin.math.sqrt(20000.0),route.distanceM,1e-8)
        assertNull(router.fromPositionToEdge(RoadPosition("a",.5),"d",setOf("d")))
        assertNull(OfflineRouter(graph(setOf(TurnRestriction("a","h")))).fromPositionToEdge(RoadPosition("a",.5),"d"))
    }
}
