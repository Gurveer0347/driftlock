package org.driftlock.core.routing

import org.junit.Assert.*
import org.junit.Test

class OsmImporterTest {
    private val base = """<osm version="0.6"><bounds minlat="30" minlon="76" maxlat="30.02" maxlon="76.02"/>
        <node id="1" lat="30.001" lon="76.001"/><node id="2" lat="30.001" lon="76.002"/>
        <node id="3" lat="30.002" lon="76.002"/><node id="4" lat="30.001" lon="76.003"/>
        <way id="10"><nd ref="1"/><nd ref="2"/><tag k="highway" v="residential"/><tag k="name" v="First Road"/><tag k="oneway" v="yes"/><tag k="maxspeed" v="36"/></way>
        <way id="11"><nd ref="2"/><nd ref="3"/><tag k="highway" v="residential"/></way>
        <way id="12"><nd ref="2"/><nd ref="4"/><tag k="highway" v="residential"/><tag k="access" v="private"/></way>
        %REL%</osm>"""
    private fun parse(relation: String = "") = OsmImporter.parse(base.replace("%REL%",relation).toByteArray(),"fixture","synthetic XML test")
    @Test fun readsActualNamesDirectionsAccessAndUnits() {
        val graph=parse().graph
        assertEquals(3,graph.edges.size)
        assertTrue(graph.edges.values.none{it.wayId==12L})
        val first=graph.edges.values.single{it.wayId==10L}
        assertEquals(1L,first.from);assertEquals(2L,first.to)
        assertEquals("First Road",first.name);assertEquals(10.0,first.speedLimitMps!!,1e-8)
        assertNull(OfflineRouter(graph).fromNode(2,1))
        assertTrue(first.lengthM>90 && first.lengthM<100)
    }
    @Test fun retainsViaNodeTurnRestriction() {
        val graph=parse("""<relation id="99"><member type="way" ref="10" role="from"/><member type="node" ref="2" role="via"/><member type="way" ref="11" role="to"/><tag k="type" v="restriction"/><tag k="restriction" v="no_right_turn"/></relation>""").graph
        assertEquals(1,graph.restrictions.size)
        assertNull(OfflineRouter(graph).fromNode(1,3))
    }
    @Test fun unsupportedRestrictionExcludesAffectedWayAndReportsReason() {
        val imported=parse("""<relation id="99"><member type="way" ref="10" role="from"/><member type="way" ref="11" role="via"/><member type="way" ref="12" role="to"/><tag k="type" v="restriction"/><tag k="restriction" v="no_right_turn"/></relation>
            <way id="13"><nd ref="3"/><nd ref="4"/><tag k="highway" v="residential"/></way>""")
        assertTrue(imported.warnings.any{it.contains("99")})
        assertTrue(imported.graph.edges.values.none{it.wayId==10L})
    }
    @Test fun excludingOnlyTurnTargetCannotOpenForbiddenTurnsThroughEarlierRestrictions() {
        val xml="""<osm version="0.6"><bounds minlat="30" minlon="76" maxlat="30.02" maxlon="76.02"/>
            <node id="1" lat="30.001" lon="76.001"/><node id="2" lat="30.001" lon="76.002"/>
            <node id="3" lat="30.001" lon="76.003"/><node id="4" lat="30.001" lon="76.004"/>
            <node id="5" lat="30.002" lon="76.002"/>
            <way id="9"><nd ref="1"/><nd ref="2"/><tag k="highway" v="residential"/><tag k="oneway" v="yes"/></way>
            <way id="10"><nd ref="2"/><nd ref="3"/><tag k="highway" v="residential"/><tag k="oneway" v="yes"/></way>
            <way id="11"><nd ref="3"/><nd ref="4"/><tag k="highway" v="residential"/><tag k="oneway" v="yes"/></way>
            <way id="12"><nd ref="2"/><nd ref="5"/><tag k="highway" v="residential"/><tag k="oneway" v="yes"/></way>
            <way id="13"><nd ref="3"/><nd ref="5"/><tag k="highway" v="residential"/><tag k="oneway" v="yes"/></way>
            <relation id="89"><member type="way" ref="9" role="from"/><member type="node" ref="2" role="via"/><member type="way" ref="10" role="to"/><tag k="type" v="restriction"/><tag k="restriction" v="only_straight_on"/></relation>
            <relation id="90"><member type="way" ref="10" role="from"/><member type="node" ref="3" role="via"/><member type="way" ref="11" role="to"/><tag k="type" v="restriction"/><tag k="restriction" v="only_straight_on"/></relation>
            <relation id="91"><member type="way" ref="11" role="from"/><member type="node" ref="4" role="via"/><member type="way" ref="99" role="to"/><tag k="type" v="restriction"/><tag k="restriction" v="no_left_turn"/></relation>
            </osm>"""
        val imported=OsmImporter.parse(xml.toByteArray(),"fixture","synthetic chained restriction fixture")
        val router=OfflineRouter(imported.graph)
        // 11 is unavailable; only-turn constraints must not become permission to take 12 or 13.
        assertNull(router.fromNode(1,5))
        // Starting at the junction has no incoming restriction, so unrelated legal roads remain usable.
        assertEquals(listOf("12:0:f"),router.fromNode(2,5)!!.legs.map{it.edgeId})
        assertTrue(imported.warnings.any{it.contains("89")})
        assertTrue(imported.warnings.any{it.contains("90")})
    }
    @Test fun reverseOneWayRespectsMotorcarOverride() {
        val xml=base.replace("%REL%","").replace("v=\"yes\"","v=\"-1\"")
        val graph=OsmImporter.parse(xml.toByteArray(),"fixture","synthetic").graph
        assertNotNull(OfflineRouter(graph).fromNode(2,1)); assertNull(OfflineRouter(graph).fromNode(1,2))
    }
    @Test fun rejectsExternalEntityAndMissingBounds() {
        assertThrows(IllegalArgumentException::class.java) { OsmImporter.parse("<!DOCTYPE osm [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><osm/>".toByteArray(),"x","synthetic") }
        assertThrows(IllegalArgumentException::class.java) { OsmImporter.parse("<osm/>".toByteArray(),"x","synthetic") }
    }
}
