package org.driftlock.core.routing

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class DownloadedMapTest {
    @Test fun officialOsmAssetSupportsConnectedRoutesWithoutNetwork() {
        val bytes=File("../app/src/main/assets/maps/chandigarh.osm").readBytes()
        val imported=OsmImporter.parse(bytes,"chandigarh","https://api.openstreetmap.org/api/0.6/map")
        val graph=imported.graph
        assertTrue(graph.edges.size>50)
        val nodes=graph.nodes.values.filter{it.id in graph.outgoing}.sortedBy{it.point.eastM+it.point.northM}
        val router=OfflineRouter(graph)
        val start=nodes[nodes.size/5].id
        val destination=nodes.asReversed().first{router.fromNode(start,it.id)?.distanceM?.let{d->d>700}==true}.id
        val routes=router.alternatives(start,destination)
        assertTrue(routes.isNotEmpty());assertTrue(routes.size<=3)
        routes.forEach{r->assertEquals(destination,graph.edges.getValue(r.legs.last().edgeId).to)}
        println("REAL_OSM nodes=${graph.nodes.size} directed_edges=${graph.edges.size} restrictions=${graph.restrictions.size} warnings=${imported.warnings.size} start=$start destination=$destination routes=${routes.map{it.distanceM}} bytes=${bytes.size}")
        val blocked=routes[0].legs.drop(1).firstOrNull()?.let{graph.edges.getValue(it.edgeId).segmentId}
        if(blocked!=null) {
            val rerouted=router.reroute(RoadPosition(routes[0].legs[0].edgeId,0.2),destination,routes.drop(1),setOf(blocked))
            rerouted.route?.let{assertTrue(it.legs.none{leg->graph.edges.getValue(leg.edgeId).segmentId==blocked})}
            println("REAL_OSM blocked=$blocked reroute=${rerouted.status}")
        }
        val reconnectable=routes[0].legs.drop(2).firstNotNullOfOrNull { leg ->
            val segment=graph.edges.getValue(leg.edgeId).segmentId
            val result=router.reroute(RoadPosition(routes[0].legs[0].edgeId,.2),destination,routes.drop(1),setOf(segment))
            result.route?.let{segment to result}
        }
        assertNotNull("Independent OSM graph must demonstrate a real legal reconnectable detour",reconnectable)
        val (avoided,result)=reconnectable!!
        assertTrue(result.route!!.legs.none{graph.edges.getValue(it.edgeId).segmentId==avoided})
        println("REAL_OSM reconnectable_block=$avoided status=${result.status} distance_m=${result.route!!.distanceM}")
    }
}
