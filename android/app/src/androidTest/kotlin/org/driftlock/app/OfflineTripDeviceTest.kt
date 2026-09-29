package org.driftlock.app

import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.driftlock.app.trips.TripRepository
import org.driftlock.core.routing.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class OfflineTripDeviceTest {
    @Test fun savedGraphRoutesAndBlockageWorkWithRadiosDisabled() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("Airplane mode must actually be enabled for this test",1,Settings.Global.getInt(context.contentResolver,Settings.Global.AIRPLANE_MODE_ON,0))
        assertEquals("Wi-Fi must also be disabled",0,Settings.Global.getInt(context.contentResolver,"wifi_on",-1))
        val bytes=context.assets.open("maps/chandigarh.osm").use{it.readBytes()}
        val graph=OsmImporter.parse(bytes,"offline-device-probe","bundled independent OpenStreetMap extract").graph
        val router=OfflineRouter(graph);val start=1430552667L;val destination=1431041684L
        val routes=router.alternatives(start,destination)
        assertEquals(3,routes.size)
        val id="device-offline-probe-${System.nanoTime()}"
        var measuredBytes=0L;var detourDistance=0.0
        try {
            TripRepository(context).use{repository->
                measuredBytes=repository.create(id,"Explicit offline device test",bytes,graph.source,routes,destination).byteSize
            }
            TripRepository(context).use{repository->
                val pack=repository.load(id)
                assertEquals(routes.map{it.legs},pack.routes.map{it.legs})
                val reconnectable=routes[0].legs.drop(2).firstNotNullOfOrNull { leg ->
                    val segment=graph.edges.getValue(leg.edgeId).segmentId
                    val result=router.reroute(RoadPosition(routes[0].legs[0].edgeId,.2),destination,routes.drop(1),setOf(segment))
                    result.route?.let{segment to it}
                }
                assertNotNull("A connected real-graph detour must exist",reconnectable)
                val (segment,detour)=reconnectable!!;detourDistance=detour.distanceM
                assertTrue(detour.legs.none{graph.edges.getValue(it.edgeId).segmentId==segment})
                repository.block(id,setOf(segment));assertEquals(setOf(segment),repository.load(id).blockedSegments)
            }
            TripRepository(context).use{assertTrue(it.delete(id));assertFalse(it.list().any{p->p.id==id})}
        } finally {TripRepository(context).use{it.delete(id)}}
        val report=JSONObject().put("purpose","PHYSICAL_OFFLINE_MAP_STORAGE_ROUTING_CHECK")
            .put("position_source","explicit synthetic fractional route position; no driving accuracy test")
            .put("airplane_mode",true).put("wifi_enabled",false).put("network_downloads",0)
            .put("graph_sha256",MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)})
            .put("directed_edges",graph.edges.size).put("prepared_routes",routes.size).put("measured_pack_bytes",measuredBytes)
            .put("legal_detour_m",detourDistance).put("room_reopen",true).put("blocked_segment_persisted",true).put("test_pack_deleted",true)
        File(context.cacheDir,"offline_device_report.json").writeText(report.toString(2))
    }
}
