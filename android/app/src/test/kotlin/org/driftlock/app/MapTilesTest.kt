package org.driftlock.app

import androidx.compose.ui.graphics.toPixelMap
import org.driftlock.core.routing.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapTilesTest {
    private val a=RoadNode(1,Point(-1000.0,0.0))
    private val b=RoadNode(2,Point(1000.0,0.0))
    private val edge=RoadEdge("ab",1,2,"ab",1,"Test road",2000.0,listOf(a.point,b.point))
    private val graph=RoadGraph("tiles",listOf(a,b),listOf(edge),emptySet(),Coverage(-1100.0,-1100.0,1100.0,1100.0),30.0,76.0,"test","test")

    @Test fun tileProjectionMatchesTapCameraWithNegativeCoordinatesAndPan() {
        val view=MapCamera(Point(-82.0,50.0),1.3,18.0,-7.0)
        val point=Point(-5.0,-40.0)
        val key=MapTileKey.forPoint(point,view.pixelsPerMeter)
        val p=key.screenPoint(point,view,400.0,700.0)
        val expected=view.project(point,400.0,700.0)
        assertEquals(expected.first,p.first,1e-8)
        assertEquals(expected.second,p.second,1e-8)
        assertTrue(MapTileKey.visible(view,400.0,700.0).contains(key))
    }
    @Test fun neighbouringFramesReuseTilesInsteadOfRebuildingGeography() {
        val scene=MapTiles(graph,MapScene(emptyList()))
        val key=MapTileKey.forPoint(Point(30.0,0.0),1.0)
        val first=scene.tile(key)
        repeat(120){assertSame(first,scene.tile(key))}
        assertEquals(1,scene.renderCount)
        assertTrue(scene.cacheSize<=MapTiles.MAX_CACHED_TILES)
    }
    @Test fun aCrossingRoadIsRasterizedEvenWhenBothVerticesAreOutsideTheTile() {
        val scene=MapTiles(graph,MapScene(emptyList()))
        val key=MapTileKey.forPoint(Point(30.0,-30.0),1.0)
        val image=scene.tile(key)
        val pixels=image.toPixelMap()
        val near=key.pixel(Point(30.0,-2.0))
        val away=key.pixel(Point(30.0,-80.0))
        assertNotEquals(pixels[away.first.toInt(),away.second.toInt()],pixels[near.first.toInt(),near.second.toInt()])
    }
    @Test fun cacheRemainsBoundedAfterLongPanAndZoom() {
        val scene=MapTiles(graph,MapScene(emptyList()))
        repeat(MapTiles.MAX_CACHED_TILES+8){scene.tile(MapTileKey(0,it,0))}
        assertEquals(MapTiles.MAX_CACHED_TILES,scene.cacheSize)
    }
    @Test fun suppressedLongRoadNameDoesNotReappearAsASlicedLabelInNextTile() {
        fun point(u:Double)=Point(u-.14*128/.78,-128/.78)
        val nodes=listOf(RoadNode(1,point(6.0)),RoadNode(2,point(16.0)),RoadNode(3,point(126.0)),RoadNode(4,point(136.0)))
        val edges=listOf(RoadEdge("short",1,2,"short",1,"I",10.0,listOf(nodes[0].point,nodes[1].point)),
            RoadEdge("long",3,4,"long",2,"The extraordinarily long road name",10.0,listOf(nodes[2].point,nodes[3].point)))
        val local=RoadGraph("labels",nodes,edges,emptySet(),Coverage(-100.0,-300.0,700.0,10.0),30.0,76.0,"test","test")
        val pixels=MapTiles(local,MapScene(emptyList())).tile(MapTileKey(0,1,0)).toPixelMap()
        val background=androidx.compose.ui.graphics.Color(0xFFE5ECE5)
        for(y in 104..126)for(x in 1..150)assertEquals("A suppressed name must not leak into its neighbouring tile",background,pixels[x,y])
    }
}
