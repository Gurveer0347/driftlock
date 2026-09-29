package org.driftlock.core.matching

import org.driftlock.core.routing.*
import org.junit.Assert.*
import org.junit.Test

class RoadMatcherTest {
    private fun graph(second: Boolean=true): RoadGraph {
        val nodes=listOf(RoadNode(1,Point(0.0,0.0)),RoadNode(2,Point(100.0,0.0)),RoadNode(3,Point(0.0,10.0)),RoadNode(4,Point(100.0,10.0)))
        fun edge(id: String,a: Int,b: Int)=RoadEdge(id,nodes[a].id,nodes[b].id,id,1,id,100.0,listOf(nodes[a].point,nodes[b].point))
        return RoadGraph("synthetic",nodes,listOfNotNull(edge("a",0,1),if(second)edge("b",2,3) else null),emptySet(),
            Coverage(-50.0,-50.0,150.0,60.0),30.0,76.0,"synthetic unit fixture","synthetic")
    }
    @Test fun loneDistantRoadDoesNotGetCertainLock() {
        val r=RoadMatcher(graph(false)).update(MatchObservation(1.0,Point(40.0,20.0),1.0,0.0,0.1,5.0))
        assertEquals(MatchStatus.NO_MATCH,r.status);assertNull(r.committed)
    }
    @Test fun parallelRoadsRemainAmbiguousAndProbabilitiesSumToOne() {
        val r=RoadMatcher(graph()).update(MatchObservation(1.0,Point(40.0,5.0),5.0,0.0,0.2,5.0))
        assertEquals(MatchStatus.AMBIGUOUS,r.status)
        assertEquals(2,r.hypotheses.size);assertEquals(1.0,r.hypotheses.sumOf{it.posterior},1e-10)
        assertNull(r.committed)
    }
    @Test fun connectedMotionLocksAndReportsActualFraction() {
        val matcher=RoadMatcher(graph(false))
        matcher.update(MatchObservation(1.0,Point(10.0,0.5),1.0,0.0,0.2,10.0))
        val r=matcher.update(MatchObservation(2.0,Point(20.0,0.5),1.0,0.0,0.2,10.0))
        assertEquals(MatchStatus.LOCKED,r.status);assertEquals(0.2,r.committed!!.position.fraction,1e-9)
    }
    @Test fun inconsistentDirectionDoesNotLock() {
        val r=RoadMatcher(graph(false)).update(MatchObservation(1.0,Point(40.0,0.0),1.0,Math.PI,0.1,10.0))
        assertEquals(MatchStatus.NO_MATCH,r.status)
    }
    @Test fun decreasingTimeRejectedAndGapResetsHistory() {
        val matcher=RoadMatcher(graph())
        matcher.update(MatchObservation(2.0,Point(10.0,0.0),1.0,0.0,0.1,10.0))
        assertEquals(MatchStatus.NO_MATCH,matcher.update(MatchObservation(1.0,Point(20.0,0.0),1.0)).status)
        val after=matcher.update(MatchObservation(10.0,Point(40.0,10.0),1.0,0.0,0.1,10.0))
        assertEquals("b",after.committed!!.position.edgeId)
    }
    @Test fun disconnectedRoadCannotWinByProximityAfterOneTick() {
        val matcher=RoadMatcher(graph())
        matcher.update(MatchObservation(1.0,Point(10.0,0.0),0.5,0.0,0.1,10.0))
        val next=matcher.update(MatchObservation(1.1,Point(11.0,10.0),0.5,0.0,0.1,10.0))
        assertEquals(MatchStatus.NO_MATCH,next.status)
    }
    @Test fun outsideDownloadedCoverageIsExplicit() {
        val r=RoadMatcher(graph()).update(MatchObservation(1.0,Point(200.0,0.0),2.0))
        assertEquals(MatchStatus.OUTSIDE_COVERAGE,r.status)
    }
    private fun restrictedJunction(withLoop:Boolean):RoadGraph {
        val nodes=listOf(RoadNode(1,Point(-100.0,0.0)),RoadNode(2,Point(0.0,0.0)),RoadNode(3,Point(100.0,0.0)),
            RoadNode(4,Point(0.0,20.0)),RoadNode(5,Point(-20.0,20.0)))
        fun edge(id:String,from:Long,to:Long):RoadEdge {
            val a=nodes.single{it.id==from}.point;val b=nodes.single{it.id==to}.point
            return RoadEdge(id,from,to,id,1,id,a.distance(b),listOf(a,b))
        }
        val edges=listOf(edge("a",1,2),edge("b",2,3))+if(withLoop)listOf(edge("c",2,4),edge("d",4,5),edge("e",5,2)) else emptyList()
        return RoadGraph("synthetic restricted junction",nodes,edges,setOf(TurnRestriction("a","b")),
            Coverage(-101.0,-1.0,101.0,21.0),30.0,76.0,"synthetic unit fixture","synthetic")
    }
    @Test fun legalLoopCanReachCandidateWhoseDirectFinalTurnIsForbidden() {
        val graph=restrictedJunction(true)
        val route=OfflineRouter(graph).fromPosition(RoadPosition("a",.8),3)!!
        assertEquals(listOf("a","c","d","e","b"),route.legs.map{it.edgeId})
        val matcher=RoadMatcher(graph)
        val initial=matcher.update(MatchObservation(1.0,Point(-20.0,0.0),.1,0.0,.1,25.0))
        assertEquals("a",initial.committed!!.position.edgeId)
        val after=matcher.update(MatchObservation(5.0,Point(20.0,0.0),.1,0.0,.1,25.0))
        assertEquals(MatchStatus.LOCKED,after.status)
        assertEquals("b",after.committed!!.position.edgeId)
        assertEquals(.2,after.committed!!.position.fraction,1e-9)
    }
    @Test fun forbiddenFinalTurnWithoutLegalLoopRemainsDisconnected() {
        val matcher=RoadMatcher(restrictedJunction(false))
        matcher.update(MatchObservation(1.0,Point(-20.0,0.0),.1,0.0,.1,25.0))
        val after=matcher.update(MatchObservation(5.0,Point(20.0,0.0),.1,0.0,.1,25.0))
        assertEquals(MatchStatus.NO_MATCH,after.status)
        assertNull(after.committed)
    }
}
