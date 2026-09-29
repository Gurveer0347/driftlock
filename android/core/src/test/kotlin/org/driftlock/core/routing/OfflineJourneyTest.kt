package org.driftlock.core.routing

import org.junit.Assert.*
import org.junit.Test

class OfflineJourneyTest {
    private val nodes=listOf(RoadNode(1,Point(0.0,0.0)),RoadNode(2,Point(100.0,0.0)),RoadNode(3,Point(200.0,0.0)),RoadNode(4,Point(100.0,100.0)))
    private fun e(id:String,a:Long,b:Long):RoadEdge { val p=nodes.first{it.id==a}.point;val q=nodes.first{it.id==b}.point;return RoadEdge(id,a,b,id,1,id,p.distance(q),listOf(p,q)) }
    private val graph=RoadGraph("synthetic",nodes,listOf(e("a",1,2),e("b",2,3),e("c",2,4),e("d",4,3)),emptySet(),Coverage(-1.0,-1.0,201.0,101.0),30.0,76.0,"synthetic test","test")
    private fun journey()=OfflineJourney(graph,3,listOf(OfflineRouter(graph).fromNode(1,3)!!))
    @Test fun explicitBlockUsesCurrentFractionAndPersistsAvoidance() {
        val j=journey();j.observe(1.0,RoadPosition("a",0.4));val result=j.block("b")
        assertEquals(setOf("b"),result.blocked)
        assertEquals(listOf("a","c","d"),result.route!!.legs.map{it.edgeId})
        assertEquals(.4,result.route.legs[0].startFraction,0.0)
    }
    @Test fun ambiguousOrSingleWrongMatchDoesNotTriggerReroute() {
        val j=journey();j.observe(1.0,RoadPosition("a",.2));j.observe(2.0,null)
        assertEquals("b",j.state.route!!.legs.last().edgeId)
        j.observe(3.0,RoadPosition("c",.2));assertEquals("b",j.state.route!!.legs.last().edgeId)
        j.observe(4.0,RoadPosition("c",.3));j.observe(5.0,RoadPosition("c",.4))
        assertEquals(listOf("c","d"),j.state.route!!.legs.map{it.edgeId})
    }
    @Test fun noCurrentMatchCannotInventConnectionAndRepeatedTimesRejected() {
        val j=journey();val result=j.block("b")
        assertNull(result.route);assertEquals(RouteStatus.NO_DOWNLOADED_ROUTE_AVAILABLE,result.status)
        j.observe(3.0,RoadPosition("a",.4));j.observe(2.0,RoadPosition("c",.8))
        assertEquals("a",j.state.position!!.edgeId)
        assertThrows(IllegalArgumentException::class.java){j.block("missing")}
    }
    @Test fun newSensorSessionCannotReuseOldMatchedPositionOrPartialDetour() {
        val j=journey();j.observe(10.0,RoadPosition("a",.4));j.block("b")
        assertEquals(.4,j.state.route!!.legs[0].startFraction,0.0)
        j.resetTracking();assertNull(j.state.position)
        assertEquals(setOf("b"),j.state.blocked)
        assertNull(j.state.route) // All originally prepared routes contain the blocked segment.
        j.observe(1.0,RoadPosition("a",.2));assertEquals(.2,j.state.position!!.fraction,0.0)
    }
}
