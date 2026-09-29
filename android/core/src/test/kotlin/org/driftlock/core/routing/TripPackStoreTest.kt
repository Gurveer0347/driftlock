package org.driftlock.core.routing

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class TripPackStoreTest {
    private val xml="""<osm version="0.6"><bounds minlat="30" minlon="76" maxlat="30.01" maxlon="76.01"/>
      <node id="1" lat="30.001" lon="76.001"/><node id="2" lat="30.001" lon="76.002"/><node id="3" lat="30.002" lon="76.002"/>
      <way id="10"><nd ref="1"/><nd ref="2"/><nd ref="3"/><tag k="highway" v="residential"/></way></osm>""".toByteArray()
    @Test fun createLoadBlockDeleteWithoutNetworkAndSizesAreActual() {
        val dir=Files.createTempDirectory("driftlock-pack-test").toFile()
        try {
            val graph=OsmImporter.parse(xml,"trip","synthetic fixture").graph
            val routes=OfflineRouter(graph).alternatives(1,3)
            val store=TripPackStore(dir)
            val created=store.create("trip","Test trip",xml,"synthetic fixture",routes,3)
            assertEquals(listOf("trip"),store.listIds())
            assertEquals(dir.resolve("trip").walkTopDown().filter{it.isFile}.sumOf{it.length()},created.byteSize)
            val loaded=TripPackStore(dir).load("trip")
            assertEquals(routes[0].legs,loaded.routes[0].legs);assertEquals(3L,loaded.destination)
            val segment=loaded.graph.edges.values.first().segmentId
            store.saveBlocked("trip",setOf(segment));assertEquals(setOf(segment),TripPackStore(dir).load("trip").blockedSegments)
            assertTrue(store.delete("trip"));assertTrue(store.listIds().isEmpty())
        } finally { dir.deleteRecursively() }
    }
    @Test fun corruptedGraphAndEscapingPathAreRejected() {
        val dir=Files.createTempDirectory("driftlock-pack-test").toFile()
        try {
            val graph=OsmImporter.parse(xml,"trip","synthetic fixture").graph
            val store=TripPackStore(dir)
            store.create("trip","Test",xml,"synthetic fixture",OfflineRouter(graph).alternatives(1,3),3)
            dir.resolve("trip/roads.osm").appendText("corrupted")
            assertThrows(IllegalArgumentException::class.java){store.load("trip")}
            assertThrows(IllegalArgumentException::class.java){store.delete("../outside")}
        } finally {dir.deleteRecursively()}
    }
    @Test fun routeWhoseEdgesDoNotExistCannotBeSaved() {
        val dir=Files.createTempDirectory("driftlock-pack-test").toFile()
        try {
            assertThrows(IllegalArgumentException::class.java){TripPackStore(dir).create("bad","Bad",xml,"synthetic",listOf(Route("A",listOf(RouteLeg("absent")),1.0,null)),3)}
            assertTrue(dir.listFiles().orEmpty().isEmpty())
        } finally {dir.deleteRecursively()}
    }
}
