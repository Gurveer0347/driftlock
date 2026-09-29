package org.driftlock.app.trips

import android.app.Application
import org.driftlock.core.routing.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class TripRepositoryTest {
    @Test fun roomAndGraphSurviveRepositoryReopenAndDelete() {
        val worker=Executors.newSingleThreadExecutor()
        try { worker.submit {
            val context=RuntimeEnvironment.getApplication()
            val bytes="""<osm version="0.6"><bounds minlat="30" minlon="76" maxlat="30.01" maxlon="76.01"/><node id="1" lat="30.001" lon="76.001"/><node id="2" lat="30.002" lon="76.002"/><way id="10"><nd ref="1"/><nd ref="2"/><tag k="highway" v="residential"/></way></osm>""".toByteArray()
            val graph=OsmImporter.parse(bytes,"test","synthetic fixture").graph
            TripRepository(context).use{it.create("test","Test",bytes,"synthetic fixture",OfflineRouter(graph).alternatives(1,2),2)}
            TripRepository(context).use {
                assertEquals(1,it.list().size);assertEquals(2L,it.load("test").destination)
                val segment=graph.edges.values.first().segmentId
                it.block("test",setOf(segment));assertEquals(setOf(segment),it.load("test").blockedSegments)
                assertTrue(it.delete("test"));assertTrue(it.list().isEmpty())
            }
        }.get() } finally {worker.shutdownNow()}
    }
}
