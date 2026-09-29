package org.driftlock.app

import org.driftlock.core.routing.Point
import org.driftlock.core.routing.RoadGraph
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

enum class MapFeatureKind { BUILDING, PARK, WATER }
data class MapArea(val kind:MapFeatureKind,val points:List<Point>)
data class MapScene(val areas:List<MapArea>) {
    companion object {
        /** Appearance only: every polygon is traced from a complete closed source OSM way. */
        fun parse(bytes:ByteArray,graph:RoadGraph):MapScene {
            require(bytes.size in 1..20_000_000) { "OSM region is outside the supported size" }
            val text=bytes.toString(Charsets.UTF_8)
            require(!text.contains("<!DOCTYPE",true) && !text.contains("<!ENTITY",true)) { "External entities are forbidden" }
            val factory=DocumentBuilderFactory.newInstance()
            factory.isExpandEntityReferences=false
            runCatching{factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true)}
            val root=factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes)).documentElement
            require(root.tagName=="osm") { "Expected OSM XML" }
            val coords=HashMap<Long,Point>()
            val nodes=root.getElementsByTagName("node")
            for(i in 0 until nodes.length) {
                val n=nodes.item(i) as Element
                val id=n.getAttribute("id").toLongOrNull() ?: continue
                val lat=n.getAttribute("lat").toDoubleOrNull() ?: continue
                val lon=n.getAttribute("lon").toDoubleOrNull() ?: continue
                if(lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0)
                    coords[id]=NativeController.geoPoint(graph,lat,lon)
            }
            val out=ArrayList<MapArea>()
            val ways=root.getElementsByTagName("way")
            for(i in 0 until ways.length) {
                if(out.size>=4000)break
                val w=ways.item(i) as Element
                val tags=HashMap<String,String>()
                val tagNodes=w.getElementsByTagName("tag")
                for(j in 0 until tagNodes.length) {
                    val tag=tagNodes.item(j) as Element
                    tags[tag.getAttribute("k")]=tag.getAttribute("v")
                }
                val kind=when {
                    tags["building"]?.let{it!="no"}==true -> MapFeatureKind.BUILDING
                    tags["natural"]=="water" || tags["waterway"]=="riverbank" || tags["landuse"]=="reservoir" -> MapFeatureKind.WATER
                    tags["leisure"] in setOf("park","garden") || tags["landuse"] in setOf("grass","forest","recreation_ground") || tags["natural"] in setOf("wood","grassland") -> MapFeatureKind.PARK
                    else -> null
                } ?: continue
                val nd=w.getElementsByTagName("nd")
                if(nd.length !in 4..251)continue
                val refs=(0 until nd.length).map{(nd.item(it) as Element).getAttribute("ref").toLongOrNull()}
                if(refs.first()==null || refs.first()!=refs.last() || refs.any{it==null})continue
                val points=refs.dropLast(1).mapNotNull{coords[it]}
                if(points.size!=refs.size-1 || points.size<3 || points.any{!graph.coverage.contains(it)})continue
                out.add(MapArea(kind,points))
            }
            return MapScene(out)
        }
    }
}
