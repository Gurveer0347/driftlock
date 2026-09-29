package org.driftlock.app

import org.driftlock.core.routing.OsmImporter
import org.junit.Assert.*
import org.junit.Test

class MapSceneTest {
    private val xml = """<osm version="0.6"><bounds minlat="30.0" minlon="76.0" maxlat="30.01" maxlon="76.01"/>
        <node id="1" lat="30.001" lon="76.001"/><node id="2" lat="30.002" lon="76.002"/>
        <node id="3" lat="30.003" lon="76.003"/><node id="4" lat="30.003" lon="76.004"/>
        <node id="5" lat="30.004" lon="76.004"/><node id="6" lat="30.004" lon="76.003"/>
        <way id="10"><nd ref="1"/><nd ref="2"/><tag k="highway" v="residential"/></way>
        <way id="11"><nd ref="3"/><nd ref="4"/><nd ref="5"/><nd ref="6"/><nd ref="3"/><tag k="building" v="yes"/></way>
        <way id="12"><nd ref="1"/><nd ref="3"/><nd ref="4"/><nd ref="1"/><tag k="leisure" v="park"/></way>
        </osm>""".trimIndent().toByteArray()

    @Test fun footprintsComeFromRecordedClosedWaysAndShareRoadProjection() {
        val graph=OsmImporter.parse(xml,"tiny","fixture").graph
        val scene=MapScene.parse(xml,graph)
        assertEquals(2,scene.areas.size)
        assertEquals(MapFeatureKind.BUILDING,scene.areas[0].kind)
        assertEquals(MapFeatureKind.PARK,scene.areas[1].kind)
        assertEquals(4,scene.areas[0].points.size)
        assertTrue(scene.areas[0].points.all{graph.coverage.contains(it)})
        val buildingFirst=scene.areas[0].points.first()
        assertEquals(NativeController.geoPoint(graph,30.003,76.003).eastM,buildingFirst.eastM,0.01)
    }

    @Test fun rejectsDoctypeAndDoesNotInventIncompleteFootprints() {
        val graph=OsmImporter.parse(xml,"tiny","fixture").graph
        assertThrows(IllegalArgumentException::class.java) {
            MapScene.parse("<!DOCTYPE osm [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><osm/>".toByteArray(),graph)
        }
        val incomplete=xml.toString(Charsets.UTF_8).replace("<nd ref=\"3\"/><tag k=\"building\"", "<nd ref=\"99\"/><tag k=\"building\"")
        assertEquals(1,MapScene.parse(incomplete.toByteArray(),graph).areas.size)
    }
}
