package org.driftlock.app

import org.driftlock.core.routing.*
import org.junit.Assert.*
import org.junit.Test

class DemoRoutePlanTest {
    private val a=RoadNode(1,Point(0.0,0.0))
    private val b=RoadNode(2,Point(100.0,0.0))
    private val c=RoadNode(3,Point(200.0,0.0))
    private val d=RoadNode(4,Point(100.0,100.0))
    private fun edge(id:String,from:RoadNode,to:RoadNode)=RoadEdge(id,from.id,to.id,id,1,id,
        from.point.distance(to.point),listOf(from.point,to.point))
    private val ab=edge("ab",a,b)
    private val bc=edge("bc",b,c)
    private val bd=edge("bd",b,d)
    private val dc=edge("dc",d,c)
    private val graph=RoadGraph("fixture",listOf(a,b,c,d),listOf(ab,bc,bd,dc),emptySet(),
        Coverage(-10.0,-10.0,210.0,110.0),30.0,76.0,"test","test")
    private val primary=Route("A",listOf(RouteLeg("ab"),RouteLeg("bc")),200.0,null)

    @Test fun closurePlanExcludesBlockedPhysicalSegmentAndStartsExactlyAtAnchor() {
        val plan=DemoRoutePlan.find(graph,primary,3,listOf(primary))
        assertNotNull(plan)
        plan!!
        assertEquals("bc",plan.blocked.segmentId)
        assertEquals(Point(50.0,0.0),plan.anchor)
        assertEquals(50.0,plan.anchorDistanceM,1e-9)
        assertEquals(Point(50.0,0.0),DemoRoutePlan.pose(graph,plan.alternate,0.0).point)
        assertEquals(Point(200.0,0.0),DemoRoutePlan.pose(graph,plan.alternate,plan.alternate.distanceM).point)
        assertFalse(plan.alternate.legs.any{graph.edges.getValue(it.edgeId).segmentId==plan.blocked.segmentId})
    }

    @Test fun routePoseUsesActualBentGeometryAndClampsAtEndpoints() {
        val bend=RoadEdge("bend",1,3,"bend",2,"bend",200.0,
            listOf(Point(0.0,0.0),Point(100.0,0.0),Point(100.0,100.0)))
        val bent=RoadGraph("bent",listOf(a,RoadNode(3,Point(100.0,100.0))),listOf(bend),emptySet(),
            Coverage(-10.0,-10.0,110.0,110.0),30.0,76.0,"test","test")
        val route=Route("bent",listOf(RouteLeg("bend")),200.0,null)
        assertEquals(Point(50.0,0.0),DemoRoutePlan.pose(bent,route,50.0).point)
        assertEquals(Point(100.0,50.0),DemoRoutePlan.pose(bent,route,150.0).point)
        assertEquals(Point(100.0,100.0),DemoRoutePlan.pose(bent,route,500.0).point)
        assertEquals(Point(0.0,0.0),DemoRoutePlan.pose(bent,route,-1.0).point)
    }

    @Test fun noConnectedDetourReturnsNullInsteadOfInventingAPath() {
        val straight=RoadGraph("straight",listOf(a,b,c),listOf(ab,bc),emptySet(),
            Coverage(-10.0,-10.0,210.0,10.0),30.0,76.0,"test","test")
        assertNull(DemoRoutePlan.find(straight,primary,3,listOf(primary)))
    }
}
